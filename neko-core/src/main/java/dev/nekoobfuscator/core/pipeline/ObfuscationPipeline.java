package dev.nekoobfuscator.core.pipeline;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.JvmObfuscationCoverage;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.jar.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Manifest;

/**
 * Main obfuscation pipeline orchestrator.
 * Reads input JAR -> runs analysis -> runs transform passes -> writes output JAR.
 */
public final class ObfuscationPipeline {
    private static final Logger log = LoggerFactory.getLogger(ObfuscationPipeline.class);

    private final ObfuscationConfig config;
    private final PassRegistry registry;
    private final PassScheduler scheduler;

    public ObfuscationPipeline(ObfuscationConfig config, PassRegistry registry) {
        this.config = config;
        this.registry = registry;
        this.scheduler = new PassScheduler();
    }

    public void execute(Path inputJar, Path outputJar) throws IOException {
        long startTime = System.currentTimeMillis();

        // Step 1: Read input JAR
        log.info("Reading input JAR: {}", inputJar);
        JarInput input = new JarInput(inputJar);
        log.info("Loaded {} classes, {} resources", input.classes().size(), input.resources().size());
        List<ResourceEntry> resources = new ArrayList<>(input.resources());

        // Step 2: Build class hierarchy
        ClassHierarchy hierarchy = new ClassHierarchy();
        for (L1Class l1 : input.classes()) {
            hierarchy.addClass(l1);
        }
        ClasspathResolver resolver = new ClasspathResolver(config.classpath());
        resolver.populateHierarchy(hierarchy);
        log.info("Class hierarchy: {} entries", hierarchy.size());

        // Step 3: Create pipeline context
        PipelineContext ctx = new PipelineContext(config, hierarchy, input.classMap());
        log.info("Master seed: 0x{}", Long.toHexString(ctx.masterSeed()));

        // Step 4: Schedule and filter passes
        List<TransformPass> enabledPasses = new ArrayList<>();
        for (TransformPass pass : registry.all()) {
            if (config.isTransformEnabledForAnyClass(pass.id())) {
                enabledPasses.add(pass);
            }
        }
        List<TransformPass> ordered = scheduler.schedule(enabledPasses);
        log.info("Scheduled {} transform passes", ordered.size());
        for (TransformPass pass : ordered) {
            log.info("  [{}] {} (phase: {}, IR: {})",
                pass.id(), pass.name(), pass.phase(), pass.requiredLevel());
        }

        // Step 5: Execute passes
        for (TransformPass pass : ordered) {
            log.info("Running pass: {} [{}]", pass.name(), pass.id());
            long passStart = System.currentTimeMillis();

            for (L1Class clazz : input.classes()) {
                ctx.setCurrentL1Class(clazz);
                ctx.setCurrentL1Method(null);
                if (!ctx.isTransformEnabledForClass(pass.id(), clazz)) continue;

                // Check if pass applies to this class
                if (!pass.isApplicable(ctx)) continue;

                // Transform class-level
                pass.transformClass(ctx);

                // Transform each method
                for (L1Method method : clazz.methods()) {
                    if (!method.hasCode()) continue;
                    ctx.setCurrentL1Method(method);
                    pass.transformMethod(ctx);
                }
            }

            long passElapsed = System.currentTimeMillis() - passStart;
            log.info("Pass {} completed in {}ms", pass.id(), passElapsed);

            refreshHierarchy(input.classes(), hierarchy);
            // Invalidate cached IR after each pass (transforms may have changed bytecode)
            ctx.invalidateAll();
        }

        // Step 6: Clean up bytecode for all dirty classes
        cleanupDirtyClasses(input.classes());
        runGeneratedHelperHardening(input.classes(), ctx, ordered);
        cleanupDirtyClasses(input.classes());
        obfuscateGeneratedHelperApi(input.classes(), hierarchy, ctx);
        cleanupDirtyClasses(input.classes());

        // Step 7: Inject the minimal Java runtime surface required by native mode.
        List<L1Class> allClasses = new ArrayList<>(input.classes());
        injectRuntimeClasses(allClasses, hierarchy);

        // Also include any classes added by passes.
        for (var entry : input.classMap().entrySet()) {
            if (!allClasses.contains(entry.getValue())) {
                allClasses.add(entry.getValue());
            }
        }
        // Add dynamically injected classes from passData
        for (var entry : ctx.classMap().entrySet()) {
            boolean found = false;
            for (L1Class c : allClasses) {
                if (c.name().equals(entry.getKey())) { found = true; break; }
            }
            if (!found) {
                allClasses.add(entry.getValue());
                hierarchy.addClass(entry.getValue());
            }
        }

        runRuntimeControlFlow(allClasses, hierarchy, ctx, ordered);
        cleanupDirtyClasses(allClasses);
        refreshHierarchy(allClasses, hierarchy);

        // Step 7.5: Native compilation
        Manifest outputManifest = input.manifest();
        updateManifestForRenamer(outputManifest, ctx);
        resources = updateResourcesForRenamer(resources, ctx);
        if (config.nativeConfig().enabled()) {
            try {
                log.info("Running native compilation stage");
                Class<?> stageClass = Class.forName("dev.nekoobfuscator.native_.stage.NativeCompilationStage");
                Object stage = stageClass.getConstructor(ObfuscationConfig.NativeConfig.class, long.class)
                    .newInstance(config.nativeConfig(), ctx.masterSeed());
                Method applyMethod = stageClass.getMethod("apply", Map.class, List.class);
                Object result = applyMethod.invoke(stage, toBytecodeMap(allClasses), resources);

                Object classMapObj = result.getClass().getMethod("allClasses").invoke(result);
                Object resourceListObj = result.getClass().getMethod("resources").invoke(result);
                int translated = (Integer) result.getClass().getMethod("translatedMethodCount").invoke(result);
                int rejected = (Integer) result.getClass().getMethod("rejectedMethodCount").invoke(result);

                Map<?, ?> nativeClasses = (Map<?, ?>) classMapObj;
                List<?> nativeResources = (List<?>) resourceListObj;
                allClasses = parseClasses(nativeClasses);
                resources = castResources(nativeResources);
                log.info("Native stage: translated={} rejected={}", translated, rejected);
            } catch (ClassNotFoundException e) {
                log.warn("Native stage requested but neko-native not on classpath");
            } catch (Exception e) {
                if (config.nativeConfig().skipOnError()) {
                    log.error("Native stage failed; continuing without native translation", e);
                } else {
                    throw new RuntimeException("Native stage failed", e);
                }
            }
        }

        logJvmCoverage(ctx);
        validateJvmCoverage(allClasses, ctx, ordered);
        obfuscateRuntimeApi(allClasses, hierarchy);
        cleanupDirtyClasses(allClasses);
        refreshHierarchy(allClasses, hierarchy);
        runJvmOutputFinalizers(allClasses, hierarchy, ctx, ordered);
        refreshHierarchy(allClasses, hierarchy);

        // Step 8: Write output JAR
        log.info("Writing output JAR: {}", outputJar);
        JarOutput output = new JarOutput(hierarchy);
        writeMapping(outputJar, ctx);
        output.write(outputJar, allClasses, resources, outputManifest);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Obfuscation completed in {}ms", elapsed);
    }

    private void writeMapping(Path outputJar, PipelineContext ctx) {
        List<String> mapLines = ctx.getPassData("renamer.mapLines");
        if (mapLines == null || mapLines.isEmpty()) return;
        Path mapPath = outputJar.resolveSibling(outputJar.getFileName() + ".map");
        try {
            Files.write(mapPath, mapLines, StandardCharsets.UTF_8);
            log.info("Wrote mapping file: {}", mapPath);
        } catch (IOException e) {
            log.warn("Failed to write mapping file {}: {}", mapPath, e.getMessage());
        }
    }

    private void refreshHierarchy(Collection<L1Class> classes, ClassHierarchy hierarchy) {
        for (L1Class clazz : classes) {
            hierarchy.addClass(clazz);
        }
    }

    private void cleanupDirtyClasses(Collection<L1Class> classes) {
        for (L1Class clazz : classes) {
            if (!clazz.isDirty()) continue;
            for (var mn : clazz.asmNode().methods) {
                if (mn.instructions == null || mn.instructions.size() == 0) continue;

                var it = mn.instructions.iterator();
                while (it.hasNext()) {
                    var insn = it.next();
                    if (insn instanceof org.objectweb.asm.tree.FrameNode) {
                        it.remove();
                    }
                }

                removeDeadCode(mn);
                sanitizeTryCatchBlocks(mn);
                mn.maxLocals = recomputeMaxLocals(mn);
            }
        }
    }

    private void runRuntimeControlFlow(List<L1Class> classes, ClassHierarchy hierarchy,
            PipelineContext ctx, List<TransformPass> ordered) {
        if (!config.isTransformEnabledForAnyClass("controlFlowFlattening")) return;

        TransformPass cff = null;
        for (TransformPass pass : ordered) {
            if ("controlFlowFlattening".equals(pass.id())) {
                cff = pass;
                break;
            }
        }
        if (cff == null) return;

        List<L1Class> runtimeClasses = new ArrayList<>();
        for (L1Class clazz : classes) {
            if (isRuntimeClass(clazz.name()) && canRuntimeControlFlow(clazz.name())) {
                runtimeClasses.add(clazz);
                ctx.classMap().putIfAbsent(clazz.name(), clazz);
                hierarchy.addClass(clazz);
            }
        }
        if (runtimeClasses.isEmpty()) return;

        log.info("Running post-injection runtime control-flow pass on {} classes", runtimeClasses.size());
        long passStart = System.currentTimeMillis();
        for (L1Class clazz : runtimeClasses) {
            ctx.setCurrentL1Class(clazz);
            ctx.setCurrentL1Method(null);
            if (!cff.isApplicable(ctx)) continue;
            cff.transformClass(ctx);

            for (L1Method method : clazz.methods()) {
                if (!method.hasCode()) continue;
                ctx.setCurrentL1Method(method);
                cff.transformMethod(ctx);
            }
        }
        ctx.invalidateAll();
        log.info("Runtime control-flow pass completed in {}ms", System.currentTimeMillis() - passStart);
    }

    private void runGeneratedHelperHardening(Collection<L1Class> classes, PipelineContext ctx,
            List<TransformPass> ordered) {
        TransformPass invoke = findPass(ordered, "invokeDynamic");
        TransformPass string = findPass(ordered, "stringObfuscation", "stringEncryption");
        TransformPass number = findPass(ordered, "constantObfuscation", "numberEncryption");
        TransformPass stack = findPass(ordered, "stackObfuscation");
        TransformPass cff = findPass(ordered, "controlFlowFlattening");
        if (invoke == null && string == null && number == null && stack == null
                && !config.isTransformEnabledForAnyClass("controlFlowFlattening")) return;

        List<L1Class> targetClasses = new ArrayList<>();
        for (L1Class clazz : classes) {
            if (isRuntimeClass(clazz.name())) continue;
            if (hasGeneratedHelperMethods(clazz)) {
                targetClasses.add(clazz);
            }
        }
        if (targetClasses.isEmpty()) return;

        log.info("Running generated helper hardening on {} classes", targetClasses.size());
        long passStart = System.currentTimeMillis();
        if (cff != null && config.isTransformEnabledForAnyClass("controlFlowFlattening")) {
            ctx.putPassData("controlFlowFlattening.hardenGeneratedHelpers", Boolean.TRUE);
            runControlFlowOnUnkeyedGeneratedHelpers(cff, targetClasses, ctx);
            ctx.putPassData("controlFlowFlattening.hardenGeneratedHelpers", Boolean.FALSE);
        } else {
            insertGeneratedHelperStateGates(targetClasses);
        }
        if (invoke != null && config.isTransformEnabledForAnyClass("invokeDynamic")) {
            ctx.putPassData("invokeDynamic.hardenGeneratedHelpers", Boolean.TRUE);
            runPassOnGeneratedHelpers(invoke, targetClasses, ctx);
            ctx.putPassData("invokeDynamic.hardenGeneratedHelpers", Boolean.FALSE);
            if (cff != null && config.isTransformEnabledForAnyClass("controlFlowFlattening")) {
                ctx.putPassData("controlFlowFlattening.hardenGeneratedHelpers", Boolean.TRUE);
                runControlFlowOnUnkeyedGeneratedHelpers(cff, targetClasses, ctx);
                ctx.putPassData("controlFlowFlattening.hardenGeneratedHelpers", Boolean.FALSE);
            }
        }
        if (string != null && isAnyTransformEnabled("stringObfuscation", "stringEncryption")) {
            ctx.putPassData("stringObfuscation.hardenGeneratedHelpers", Boolean.TRUE);
            ctx.putPassData("stringEncryption.hardenGeneratedHelpers", Boolean.TRUE);
            runPassOnGeneratedHelpers(string, targetClasses, ctx);
            ctx.putPassData("stringObfuscation.hardenGeneratedHelpers", Boolean.FALSE);
            ctx.putPassData("stringEncryption.hardenGeneratedHelpers", Boolean.FALSE);
        }
        if (number != null && isAnyTransformEnabled("constantObfuscation", "numberEncryption")) {
            ctx.putPassData("constantObfuscation.hardenGeneratedHelpers", Boolean.TRUE);
            ctx.putPassData("numberEncryption.hardenGeneratedHelpers", Boolean.TRUE);
            runPassOnGeneratedHelpers(number, targetClasses, ctx);
            ctx.putPassData("constantObfuscation.hardenGeneratedHelpers", Boolean.FALSE);
            ctx.putPassData("numberEncryption.hardenGeneratedHelpers", Boolean.FALSE);
        }
        if (stack != null && config.isTransformEnabledForAnyClass("stackObfuscation")) {
            ctx.putPassData("stackObfuscation.hardenGeneratedHelpers", Boolean.TRUE);
            runPassOnGeneratedHelpers(stack, targetClasses, ctx);
            ctx.putPassData("stackObfuscation.hardenGeneratedHelpers", Boolean.FALSE);
        }
        ctx.invalidateAll();
        log.info("Generated helper hardening completed in {}ms", System.currentTimeMillis() - passStart);
    }

    private void insertGeneratedHelperStateGates(Collection<L1Class> classes) {
        int changed = 0;
        for (L1Class clazz : classes) {
            for (MethodNode method : clazz.asmNode().methods) {
                if (!isGeneratedHelperMethod(method) || !hasCode(method) || hasSwitch(method)) continue;
                if ((method.access & Opcodes.ACC_STATIC) == 0) continue;
                org.objectweb.asm.tree.LabelNode body = new org.objectweb.asm.tree.LabelNode();
                org.objectweb.asm.tree.LabelNode dflt = new org.objectweb.asm.tree.LabelNode();
                int state = Objects.hash(clazz.name(), method.name, method.desc,
                    config.keyConfig().masterSeed());
                int mask = Integer.rotateLeft(state ^ 0x6D2B79F5, 11);

                org.objectweb.asm.tree.InsnList gate = new org.objectweb.asm.tree.InsnList();
                pushInt(gate, state ^ mask);
                pushInt(gate, mask);
                gate.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IXOR));
                gate.add(new org.objectweb.asm.tree.LookupSwitchInsnNode(dflt, new int[] { state },
                    new org.objectweb.asm.tree.LabelNode[] { body }));
                gate.add(dflt);
                gate.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
                gate.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
                gate.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/lang/IllegalStateException", "<init>", "()V", false));
                gate.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ATHROW));
                gate.add(body);
                method.instructions.insert(gate);
                method.maxStack = Math.max(method.maxStack, 3);
                clazz.markDirty();
                changed++;
            }
        }
        if (changed > 0) {
            log.info("Inserted generated helper state gates: methods={}", changed);
        }
    }

    private void pushInt(org.objectweb.asm.tree.InsnList insns, int value) {
        if (value >= -1 && value <= 5) {
            insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            insns.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            insns.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            insns.add(new org.objectweb.asm.tree.LdcInsnNode(value));
        }
    }

    private TransformPass findPass(List<TransformPass> ordered, String... ids) {
        for (String id : ids) {
            for (TransformPass pass : ordered) {
                if (id.equals(pass.id())) return pass;
            }
        }
        return null;
    }

    private void runJvmOutputFinalizers(List<L1Class> classes, ClassHierarchy hierarchy,
            PipelineContext ctx, List<TransformPass> ordered) {
        for (TransformPass pass : ordered) {
            if (!config.isTransformEnabledForAnyClass(pass.id())) continue;
            try {
                Method finalizer = pass.getClass().getMethod(
                    "finalizeOutput",
                    PipelineContext.class,
                    List.class,
                    ClassHierarchy.class
                );
                log.info("Running JVM output finalizer: {} [{}]", pass.name(), pass.id());
                finalizer.invoke(pass, ctx, classes, hierarchy);
            } catch (NoSuchMethodException ignored) {
                // Most passes do not need an output-finalization stage.
            } catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.error("JVM output finalizer {} [{}] failed", pass.name(), pass.id(), cause);
                throw new RuntimeException(
                    "JVM output finalizer failed for pass " + pass.id(),
                    cause
                );
            }
        }
    }

    private boolean isAnyTransformEnabled(String... ids) {
        for (String id : ids) {
            if (config.isTransformEnabledForAnyClass(id)) return true;
        }
        return false;
    }

    private void runPassOnGeneratedHelpers(TransformPass pass, Collection<L1Class> classes, PipelineContext ctx) {
        for (L1Class clazz : classes) {
            ctx.setCurrentL1Class(clazz);
            ctx.setCurrentL1Method(null);
            if (!pass.isApplicable(ctx)) continue;
            pass.transformClass(ctx);
            List<L1Method> methods = new ArrayList<>(clazz.methods());
            for (L1Method method : methods) {
                if (!method.hasCode() || !method.name().startsWith("__neko_")) continue;
                ctx.setCurrentL1Method(method);
                if (!pass.isApplicable(ctx)) continue;
                pass.transformMethod(ctx);
            }
        }
    }

    private void runControlFlowOnUnkeyedGeneratedHelpers(TransformPass pass,
            Collection<L1Class> classes, PipelineContext ctx) {
        int candidates = 0;
        int changed = 0;
        for (L1Class clazz : classes) {
            ctx.setCurrentL1Class(clazz);
            ctx.setCurrentL1Method(null);
            if (!pass.isApplicable(ctx)) continue;
            pass.transformClass(ctx);
            List<L1Method> methods = new ArrayList<>(clazz.methods());
            for (L1Method method : methods) {
                if (!method.hasCode() || !method.name().startsWith("__neko_")) continue;
                if (hasControlFlowKeyLocal(ctx, method)) continue;
                candidates++;
            }
        }
        if (candidates > 0) {
            log.info(
                "Control-flow generated helpers missing flow keys: candidates={} keyed={}",
                candidates,
                changed
            );
        }
    }

    private boolean hasControlFlowKeyLocal(PipelineContext ctx, L1Method method) {
        Map<String, Integer> locals = ctx.getPassData("controlFlowFlattening.flowKeyLocalByMethod");
        return locals != null && locals.containsKey(method.owner().name() + "." + method.name() + method.descriptor());
    }

    private boolean hasGeneratedHelperMethods(L1Class clazz) {
        for (L1Method method : clazz.methods()) {
            if (method.hasCode() && method.name().startsWith("__neko_")) {
                return true;
            }
        }
        return false;
    }

    private void obfuscateGeneratedHelperApi(Collection<L1Class> classes, ClassHierarchy hierarchy, PipelineContext ctx) {
        if (!config.isTransformEnabledForAnyClass("renamer")) return;
        List<String> renamerMapLines = ctx.getPassData("renamer.mapLines");
        if (renamerMapLines == null) {
            renamerMapLines = new ArrayList<>();
            ctx.putPassData("renamer.mapLines", renamerMapLines);
        }

        Map<RuntimeMemberKey, String> memberMap = new LinkedHashMap<>();
        for (L1Class clazz : classes) {
            if (isRuntimeClass(clazz.name())) continue;
            Set<String> fieldNames = new HashSet<>();
            Set<String> methodNames = new HashSet<>();
            for (FieldNode field : clazz.asmNode().fields) {
                fieldNames.add(field.name);
            }
            for (MethodNode method : clazz.asmNode().methods) {
                methodNames.add(method.name);
            }

            NameSource fieldSource = new NameSource("");
            NameSource methodSource = new NameSource("");
            for (FieldNode field : clazz.asmNode().fields) {
                if (!isGeneratedHelperField(field)) continue;
                String newName = nextUnused(fieldSource, fieldNames);
                memberMap.put(RuntimeMemberKey.field(clazz.name(), field.name, field.desc), newName);
            }
            for (MethodNode method : clazz.asmNode().methods) {
                if (!isGeneratedHelperMethod(method)) continue;
                String newName = nextUnused(methodSource, methodNames);
                memberMap.put(RuntimeMemberKey.method(clazz.name(), method.name, method.desc), newName);
            }
        }
        if (memberMap.isEmpty()) return;

        for (L1Class clazz : classes) {
            if (isRuntimeClass(clazz.name())) continue;
            ClassNode remapped = new ClassNode();
            clazz.asmNode().accept(new ClassRemapper(remapped, new GeneratedMemberRemapper(memberMap)));
            rewriteGeneratedMemberNameStrings(remapped, memberMap);
            copyInto(clazz.asmNode(), remapped);
            clazz.markDirty();
            hierarchy.addClass(clazz);
        }

        JvmObfuscationCoverage coverage = JvmObfuscationCoverage.get(ctx);
        int renamedMethods = 0;
        int renamedFields = 0;
        for (Map.Entry<RuntimeMemberKey, String> entry : memberMap.entrySet()) {
            RuntimeMemberKey key = entry.getKey();
            if (key.method()) {
                renamedMethods++;
                coverage.safe("renamer", key.owner(), entry.getValue(), key.desc(), "generated-helper-api-renamed");
            } else {
                renamedFields++;
            }
            renamerMapLines.add((key.method() ? "METHOD " : "FIELD ") +
                key.owner() + "." + key.name() + key.desc() + " -> " + entry.getValue());
        }
        if (renamedMethods > 0 || renamedFields > 0) {
            log.info("Obfuscated generated helper API: methods={} fields={}", renamedMethods, renamedFields);
        }
    }

    private String nextUnused(NameSource source, Set<String> occupied) {
        String name;
        do {
            name = source.nextSimpleName();
        } while (!occupied.add(name));
        return name;
    }

    private boolean isGeneratedHelperField(FieldNode field) {
        return field.name.startsWith("__e")
            || field.name.startsWith("__neko_n")
            || ((field.access & Opcodes.ACC_SYNTHETIC) != 0 && field.name.startsWith("$"));
    }

    private boolean isGeneratedHelperMethod(MethodNode method) {
        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) return false;
        return method.name.startsWith("__neko_");
    }

    private void rewriteGeneratedMemberNameStrings(ClassNode node, Map<RuntimeMemberKey, String> memberMap) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Map.Entry<RuntimeMemberKey, String> entry : memberMap.entrySet()) {
            RuntimeMemberKey key = entry.getKey();
            if (key.name().startsWith("$") || key.name().startsWith("__")) {
                names.put(key.name(), entry.getValue());
            }
        }
        if (names.isEmpty()) return;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc
                        && ldc.cst instanceof String value) {
                    String mapped = names.get(value);
                    if (mapped != null) {
                        ldc.cst = mapped;
                    }
                }
            }
        }
    }

    private boolean hasCode(MethodNode method) {
        return (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
            && method.instructions != null
            && method.instructions.size() > 0;
    }

    private boolean hasSwitch(MethodNode method) {
        if (method.instructions == null) return false;
        for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst();
                insn != null; insn = insn.getNext()) {
            if (insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode
                    || insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode) {
                return true;
            }
        }
        return false;
    }

    private boolean canRuntimeControlFlow(String internalName) {
        if (internalName.endsWith("FlowException")) return false;
        if (internalName.endsWith("ReturnFlowException")) return false;
        return true;
    }

    private void obfuscateRuntimeApi(List<L1Class> classes, ClassHierarchy hierarchy) {
        if (!config.isTransformEnabledForAnyClass("renamer")
                || !transformBooleanOption("renamer", "renameRuntime", true)) {
            return;
        }

        List<L1Class> runtimeClasses = new ArrayList<>();
        Set<String> occupiedNames = new HashSet<>();
        for (L1Class clazz : classes) {
            occupiedNames.add(clazz.name());
            if (isRuntimeClass(clazz.name())) {
                runtimeClasses.add(clazz);
            }
        }
        if (runtimeClasses.isEmpty()) return;

        runtimeClasses.sort(Comparator.comparing(L1Class::name));
        String classPrefix = transformOption("renamer", "runtimeClassPrefix", "r/");
        NameSource runtimeClassNames = new NameSource(classPrefix);
        Map<String, String> classMap = new LinkedHashMap<>();
        for (L1Class clazz : runtimeClasses) {
            String newName;
            do {
                newName = runtimeClassNames.nextInternalName();
            } while (occupiedNames.contains(newName));
            classMap.put(clazz.name(), newName);
            occupiedNames.add(newName);
        }

        Map<RuntimeMemberKey, String> memberMap = new LinkedHashMap<>();
        for (L1Class clazz : runtimeClasses) {
            NameSource methodNames = new NameSource("");
            NameSource fieldNames = new NameSource("");
            Set<String> reflectiveStrings = runtimeStringConstants(clazz.asmNode());
            for (MethodNode method : clazz.asmNode().methods) {
                if (canRenameRuntimeMethod(method, reflectiveStrings)) {
                    memberMap.put(RuntimeMemberKey.method(clazz.name(), method.name, method.desc),
                        methodNames.nextSimpleName());
                }
            }
            for (FieldNode field : clazz.asmNode().fields) {
                if (canRenameRuntimeField(field, reflectiveStrings)) {
                    memberMap.put(RuntimeMemberKey.field(clazz.name(), field.name, field.desc),
                        fieldNames.nextSimpleName());
                }
            }
        }

        RuntimeApiRemapper remapper = new RuntimeApiRemapper(classMap, memberMap);
        for (L1Class clazz : classes) {
            boolean runtimeClass = isRuntimeClass(clazz.name());
            ClassNode remapped = new ClassNode();
            clazz.asmNode().accept(new ClassRemapper(remapped, remapper));
            if (runtimeClass) {
                stripRuntimeDebugMetadata(remapped);
            }
            copyInto(clazz.asmNode(), remapped);
            clazz.markDirty();
            hierarchy.addClass(clazz);
        }
        log.info("Obfuscated runtime API: classes={} members={}", classMap.size(), memberMap.size());
    }

    private void stripRuntimeDebugMetadata(ClassNode node) {
        node.sourceFile = null;
        node.sourceDebug = null;
        for (MethodNode method : node.methods) {
            method.localVariables = null;
            method.visibleLocalVariableAnnotations = null;
            method.invisibleLocalVariableAnnotations = null;
            if (method.instructions == null) continue;
            for (var insn = method.instructions.getFirst(); insn != null; ) {
                var next = insn.getNext();
                if (insn instanceof org.objectweb.asm.tree.LineNumberNode) {
                    method.instructions.remove(insn);
                }
                insn = next;
            }
        }
    }

    private boolean isRuntimeClass(String internalName) {
        return internalName != null && internalName.startsWith("dev/nekoobfuscator/runtime/");
    }

    private boolean canRenameRuntimeMethod(MethodNode method, Set<String> reflectiveStrings) {
        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) return false;
        if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) return false;
        return !reflectiveStrings.contains(method.name);
    }

    private boolean canRenameRuntimeField(FieldNode field, Set<String> reflectiveStrings) {
        if ("serialVersionUID".equals(field.name)) return false;
        return !reflectiveStrings.contains(field.name);
    }

    private Set<String> runtimeStringConstants(ClassNode node) {
        Set<String> strings = new HashSet<>();
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc
                        && ldc.cst instanceof String text) {
                    strings.add(text);
                }
            }
        }
        return strings;
    }

    private void copyInto(ClassNode target, ClassNode source) {
        target.version = source.version;
        target.access = source.access;
        target.name = source.name;
        target.signature = source.signature;
        target.superName = source.superName;
        target.interfaces = source.interfaces;
        target.sourceFile = source.sourceFile;
        target.sourceDebug = source.sourceDebug;
        target.module = source.module;
        target.outerClass = source.outerClass;
        target.outerMethod = source.outerMethod;
        target.outerMethodDesc = source.outerMethodDesc;
        target.visibleAnnotations = source.visibleAnnotations;
        target.invisibleAnnotations = source.invisibleAnnotations;
        target.visibleTypeAnnotations = source.visibleTypeAnnotations;
        target.invisibleTypeAnnotations = source.invisibleTypeAnnotations;
        target.attrs = source.attrs;
        target.innerClasses = source.innerClasses;
        target.nestHostClass = source.nestHostClass;
        target.nestMembers = source.nestMembers;
        target.permittedSubclasses = source.permittedSubclasses;
        target.recordComponents = source.recordComponents;
        target.fields = source.fields;
        target.methods = source.methods;
    }

    /**
     * Inject neko-runtime classes into the output JAR so the obfuscated code
     * can find bootstrap methods, key derivation, decryptors, etc.
     */
    private void injectRuntimeClasses(List<L1Class> classes, ClassHierarchy hierarchy) {
        Set<String> needed = new LinkedHashSet<>();

        if (config.nativeConfig().enabled()) {
            needed.add("dev/nekoobfuscator/runtime/NekoNativeLoader");
        }

        Set<String> existing = new HashSet<>();
        for (L1Class clazz : classes) {
            existing.add(clazz.name());
        }

        for (String className : needed) {
            if (!existing.add(className)) {
                continue;
            }
            String resourcePath = className + ".class";
            try (var is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    log.warn("Runtime class not found on classpath: {}", className);
                    continue;
                }
                byte[] classBytes = is.readAllBytes();
                org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(classBytes);
                org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
                cr.accept(cn, org.objectweb.asm.ClassReader.EXPAND_FRAMES);
                L1Class l1 = new L1Class(cn);
                classes.add(l1);
                hierarchy.addClass(l1);
                log.debug("Injected runtime class: {}", className);
            } catch (Exception e) {
                log.warn("Failed to inject runtime class {}: {}", className, e.getMessage());
            }
        }
    }

    private String transformOption(String transformId, String optionName, String defaultValue) {
        var transform = config.transforms().get(transformId);
        if (transform == null) return defaultValue;
        Object value = transform.options().get(optionName);
        return value instanceof String text ? text : defaultValue;
    }

    private boolean transformBooleanOption(String transformId, String optionName, boolean defaultValue) {
        var transform = config.transforms().get(transformId);
        if (transform == null) return defaultValue;
        Object value = transform.options().get(optionName);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private void updateManifestForRenamer(Manifest manifest, PipelineContext ctx) {
        if (manifest == null) return;
        Map<String, String> classMap = ctx.getPassData("renamer.classMap");
        if (classMap == null || classMap.isEmpty()) return;
        String mainClass = manifest.getMainAttributes().getValue("Main-Class");
        if (mainClass != null) {
            String remapped = classMap.get(mainClass.replace('.', '/'));
            if (remapped != null) {
                manifest.getMainAttributes().putValue("Main-Class", remapped.replace('/', '.'));
            }
        }
    }

    private List<ResourceEntry> updateResourcesForRenamer(List<ResourceEntry> resources, PipelineContext ctx) {
        Map<String, String> classMap = ctx.getPassData("renamer.classMap");
        if (classMap == null || classMap.isEmpty()) return resources;
        List<ResourceEntry> updated = new ArrayList<>(resources.size());
        for (ResourceEntry resource : resources) {
            if (resource.name().equals("META-INF/MANIFEST.MF")) {
                updated.add(resource);
                continue;
            }
            String newName = remapResourceName(resource.name(), classMap);
            byte[] newData = remapTextResource(resource.name(), resource.data(), classMap);
            updated.add(new ResourceEntry(newName, newData));
        }
        return updated;
    }

    private String remapResourceName(String name, Map<String, String> classMap) {
        if (!name.endsWith(".class")) {
            for (Map.Entry<String, String> entry : classMap.entrySet()) {
                String oldService = "META-INF/services/" + entry.getKey().replace('/', '.');
                if (name.equals(oldService)) {
                    return "META-INF/services/" + entry.getValue().replace('/', '.');
                }
            }
            List<Map.Entry<String, String>> packages = packageMapForRenamer(classMap);
            for (Map.Entry<String, String> entry : packages) {
                String oldPackage = entry.getKey();
                if (name.startsWith(oldPackage + "/")) {
                    return entry.getValue() + name.substring(oldPackage.length());
                }
            }
        }
        return name;
    }

    private byte[] remapTextResource(String name, byte[] data, Map<String, String> classMap) {
        if (!isTextLikeResource(name)) return data;
        String text = new String(data, StandardCharsets.UTF_8);
        String remapped = text;
        List<Map.Entry<String, String>> entries = new ArrayList<>(classMap.entrySet());
        entries.sort((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()));
        for (Map.Entry<String, String> entry : entries) {
            remapped = remapped.replace(entry.getKey(), entry.getValue());
            remapped = remapped.replace(entry.getKey().replace('/', '.'), entry.getValue().replace('/', '.'));
        }
        return remapped.equals(text) ? data : remapped.getBytes(StandardCharsets.UTF_8);
    }

    private List<Map.Entry<String, String>> packageMapForRenamer(Map<String, String> classMap) {
        Map<String, String> packages = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : classMap.entrySet()) {
            String oldPackage = packageName(entry.getKey());
            String newPackage = packageName(entry.getValue());
            if (!oldPackage.isEmpty() && !newPackage.isEmpty()) {
                packages.putIfAbsent(oldPackage, newPackage);
            }
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(packages.entrySet());
        entries.sort((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()));
        return entries;
    }

    private String packageName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private boolean isTextLikeResource(String name) {
        return name.startsWith("META-INF/services/")
            || name.endsWith(".properties")
            || name.endsWith(".txt")
            || name.endsWith(".xml")
            || name.endsWith(".json")
            || name.endsWith(".yml")
            || name.endsWith(".yaml")
            || name.endsWith(".mf")
            || name.endsWith(".MF");
    }

    private void logJvmCoverage(PipelineContext ctx) {
        JvmObfuscationCoverage coverage = ctx.getPassData(JvmObfuscationCoverage.PASS_DATA_KEY);
        if (coverage == null) return;
        for (String line : coverage.summaryLines()) {
            log.info("JVM coverage {}", line);
        }
    }

    private void validateJvmCoverage(List<L1Class> classes, PipelineContext ctx, List<TransformPass> ordered) {
        if (!strictJvmCoverageEnabled()) return;
        JvmObfuscationCoverage coverage = ctx.getPassData(JvmObfuscationCoverage.PASS_DATA_KEY);
        if (coverage == null) {
            throw new IllegalStateException("Strict JVM obfuscation coverage requested, but no pass recorded coverage");
        }
        Set<String> enabled = new LinkedHashSet<>();
        for (TransformPass pass : ordered) {
            if (config.isTransformEnabledForAnyClass(pass.id())) {
                enabled.add(pass.id());
            }
        }
        Set<String> bytecodePasses = new LinkedHashSet<>(List.of(
            "renamer", "controlFlowFlattening", "constantObfuscation", "opaquePredicates", "numberEncryption", "stringEncryption",
            "runtimeVariableObfuscation", "invokeDynamic", "outliner", "exceptionObfuscation", "exceptionReturn",
            "stackObfuscation", "advancedJvm"));
        bytecodePasses.retainAll(enabled);
        List<String> missing = new ArrayList<>();
        for (L1Class clazz : classes) {
            if (isRuntimeClass(clazz.name())) continue;
            Set<String> enabledForClass = new LinkedHashSet<>();
            for (String passId : bytecodePasses) {
                if (ctx.isTransformEnabledForClass(passId, clazz)) {
                    enabledForClass.add(passId);
                }
            }
            if (enabledForClass.isEmpty()) continue;
            for (L1Method method : clazz.methods()) {
                if (!method.hasCode()) continue;
                if (!coverage.hasApplied(clazz.name(), method.name(), method.descriptor(), enabledForClass)) {
                    missing.add(clazz.name() + "." + method.name() + method.descriptor());
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Strict JVM obfuscation coverage missing for " + missing.size()
                + " application methods: " + missing.subList(0, Math.min(20, missing.size())));
        }
    }

    private boolean strictJvmCoverageEnabled() {
        for (TransformConfig transform : config.transforms().values()) {
            Object value = transform.options().get("strictCoverage");
            if (value instanceof Boolean bool && bool) return true;
        }
        for (var rule : config.rules()) {
            for (TransformConfig transform : rule.transforms().values()) {
                Object value = transform.options().get("strictCoverage");
                if (value instanceof Boolean bool && bool) return true;
            }
        }
        return false;
    }

    /**
     * Remove unreachable (dead) code from a method.
     * Uses BFS from method entry + exception handlers to find all reachable instructions.
     * Unreachable instructions are removed. This is essential for COMPUTE_FRAMES to succeed
     * after control flow flattening which may leave dead code after terminators.
     */
    private void removeDeadCode(org.objectweb.asm.tree.MethodNode mn) {
        org.objectweb.asm.tree.InsnList insns = mn.instructions;
        if (insns.size() == 0) return;

        // Build instruction index map
        Map<org.objectweb.asm.tree.AbstractInsnNode, Integer> insnIndex = new java.util.IdentityHashMap<>();
        org.objectweb.asm.tree.AbstractInsnNode[] insnArray = insns.toArray();
        for (int i = 0; i < insnArray.length; i++) {
            insnIndex.put(insnArray[i], i);
        }

        // BFS reachability
        boolean[] reachable = new boolean[insnArray.length];
        Queue<Integer> queue = new LinkedList<>();

        // Entry point
        queue.add(0);

        // Exception handler entry points
        if (mn.tryCatchBlocks != null) {
            for (org.objectweb.asm.tree.TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                Integer idx = insnIndex.get(tcb.handler);
                if (idx != null) queue.add(idx);
            }
        }

        while (!queue.isEmpty()) {
            int idx = queue.poll();
            if (idx < 0 || idx >= insnArray.length || reachable[idx]) continue;
            reachable[idx] = true;

            org.objectweb.asm.tree.AbstractInsnNode insn = insnArray[idx];
            int opcode = insn.getOpcode();

            // Follow control flow
            if (insn instanceof org.objectweb.asm.tree.JumpInsnNode jump) {
                Integer target = insnIndex.get(jump.label);
                if (target != null) queue.add(target);
                // Conditional jumps also fall through
                if (opcode != org.objectweb.asm.Opcodes.GOTO && opcode != 200) {
                    queue.add(idx + 1);
                }
            } else if (insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode ts) {
                Integer dflt = insnIndex.get(ts.dflt);
                if (dflt != null) queue.add(dflt);
                for (org.objectweb.asm.tree.LabelNode l : ts.labels) {
                    Integer t = insnIndex.get(l);
                    if (t != null) queue.add(t);
                }
            } else if (insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode ls) {
                Integer dflt = insnIndex.get(ls.dflt);
                if (dflt != null) queue.add(dflt);
                for (org.objectweb.asm.tree.LabelNode l : ls.labels) {
                    Integer t = insnIndex.get(l);
                    if (t != null) queue.add(t);
                }
            } else if (opcode >= org.objectweb.asm.Opcodes.IRETURN && opcode <= org.objectweb.asm.Opcodes.RETURN) {
                // Return - no successor
            } else if (opcode == org.objectweb.asm.Opcodes.ATHROW) {
                // Throw - no successor
            } else {
                // Normal instruction - fall through
                queue.add(idx + 1);
            }
        }

        // Mark labels that are referenced by reachable jumps or try-catch as reachable
        // (even if the label itself wasn't reached via normal flow)
        for (int i = 0; i < insnArray.length; i++) {
            if (!reachable[i]) continue;
            org.objectweb.asm.tree.AbstractInsnNode insn = insnArray[i];
            if (insn instanceof org.objectweb.asm.tree.JumpInsnNode jump) {
                Integer t = insnIndex.get(jump.label);
                if (t != null) reachable[t] = true;
            }
        }
        // Also mark try-catch labels
        if (mn.tryCatchBlocks != null) {
            for (org.objectweb.asm.tree.TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                Integer si = insnIndex.get(tcb.start); if (si != null) reachable[si] = true;
                Integer ei = insnIndex.get(tcb.end); if (ei != null) reachable[ei] = true;
                Integer hi = insnIndex.get(tcb.handler); if (hi != null) reachable[hi] = true;
            }
        }

        // Remove unreachable instructions (but keep LabelNodes that might be referenced)
        int removed = 0;
        for (int i = 0; i < insnArray.length; i++) {
            if (!reachable[i] && !(insnArray[i] instanceof org.objectweb.asm.tree.LabelNode)) {
                insns.remove(insnArray[i]);
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Removed {} unreachable instructions from {}", removed, mn.name);
        }
    }

    private void sanitizeTryCatchBlocks(org.objectweb.asm.tree.MethodNode mn) {
        if (mn.tryCatchBlocks == null || mn.tryCatchBlocks.isEmpty() || mn.instructions == null || mn.instructions.size() == 0) {
            return;
        }

        Map<org.objectweb.asm.tree.AbstractInsnNode, Integer> codePositions = new IdentityHashMap<>();
        int codeIndex = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            codePositions.put(insn, codeIndex);
            if (isBytecodeInsn(insn)) {
                codeIndex++;
            }
        }

        List<org.objectweb.asm.tree.TryCatchBlockNode> sanitized = new ArrayList<>();
        for (org.objectweb.asm.tree.TryCatchBlockNode tcb : mn.tryCatchBlocks) {
            Integer startPos = codePositions.get(tcb.start);
            Integer endPos = codePositions.get(tcb.end);
            Integer handlerPos = codePositions.get(tcb.handler);
            if (startPos == null || endPos == null || handlerPos == null) {
                continue;
            }
            if (startPos >= endPos) {
                continue;
            }
            sanitized.add(tcb);
        }
        mn.tryCatchBlocks = sanitized;
    }

    private boolean isBytecodeInsn(org.objectweb.asm.tree.AbstractInsnNode insn) {
        return !(insn instanceof org.objectweb.asm.tree.LabelNode)
            && !(insn instanceof org.objectweb.asm.tree.FrameNode)
            && !(insn instanceof org.objectweb.asm.tree.LineNumberNode);
    }

    private int recomputeMaxLocals(org.objectweb.asm.tree.MethodNode mn) {
        int maxLocals = ((mn.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0) ? 1 : 0;
        for (Type argumentType : Type.getArgumentTypes(mn.desc)) {
            maxLocals += argumentType.getSize();
        }

        for (org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof org.objectweb.asm.tree.VarInsnNode varInsn) {
                maxLocals = Math.max(maxLocals, varInsn.var + localSlotSize(varInsn.getOpcode()));
            } else if (insn instanceof org.objectweb.asm.tree.IincInsnNode iincInsn) {
                maxLocals = Math.max(maxLocals, iincInsn.var + 1);
            }
        }

        return maxLocals;
    }

    private int localSlotSize(int opcode) {
        return switch (opcode) {
            case org.objectweb.asm.Opcodes.LLOAD,
                 org.objectweb.asm.Opcodes.DLOAD,
                 org.objectweb.asm.Opcodes.LSTORE,
                 org.objectweb.asm.Opcodes.DSTORE -> 2;
            default -> 1;
        };
    }

    private Map<String, byte[]> toBytecodeMap(List<L1Class> classes) {
        Map<String, byte[]> bytecode = new LinkedHashMap<>();
        for (L1Class clazz : classes) {
            ClassWriter writer = new ClassWriter(0);
            clazz.asmNode().accept(writer);
            bytecode.put(clazz.name(), writer.toByteArray());
        }
        return bytecode;
    }

    private List<L1Class> parseClasses(Map<?, ?> classMap) {
        List<L1Class> classes = new ArrayList<>();
        for (Map.Entry<?, ?> entry : classMap.entrySet()) {
            Object name = entry.getKey();
            Object bytes = entry.getValue();
            if (!(name instanceof String) || !(bytes instanceof byte[] data)) {
                throw new IllegalStateException("Unexpected native stage class result type");
            }
            ClassReader reader = new ClassReader(data);
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            reader.accept(node, ClassReader.EXPAND_FRAMES);
            classes.add(new L1Class(node));
        }
        return classes;
    }

    private List<ResourceEntry> castResources(List<?> nativeResources) {
        List<ResourceEntry> resources = new ArrayList<>(nativeResources.size());
        for (Object resource : nativeResources) {
            if (!(resource instanceof ResourceEntry entry)) {
                throw new IllegalStateException("Unexpected native stage resource result type");
            }
            resources.add(entry);
        }
        return resources;
    }

    private static final class RuntimeApiRemapper extends Remapper {
        private final Map<String, String> classMap;
        private final Map<RuntimeMemberKey, String> memberMap;

        private RuntimeApiRemapper(Map<String, String> classMap, Map<RuntimeMemberKey, String> memberMap) {
            this.classMap = classMap;
            this.memberMap = memberMap;
        }

        @Override
        public String map(String internalName) {
            return classMap.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if (name.startsWith("__neko_class_integrity$")) return name;
            return memberMap.getOrDefault(RuntimeMemberKey.method(owner, name, descriptor), name);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return memberMap.getOrDefault(RuntimeMemberKey.field(owner, name, descriptor), name);
        }
    }

    private static final class GeneratedMemberRemapper extends Remapper {
        private final Map<RuntimeMemberKey, String> memberMap;

        private GeneratedMemberRemapper(Map<RuntimeMemberKey, String> memberMap) {
            this.memberMap = memberMap;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return memberMap.getOrDefault(RuntimeMemberKey.method(owner, name, descriptor), name);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return memberMap.getOrDefault(RuntimeMemberKey.field(owner, name, descriptor), name);
        }
    }

    private record RuntimeMemberKey(String owner, String name, String desc, boolean method) {
        static RuntimeMemberKey method(String owner, String name, String desc) {
            return new RuntimeMemberKey(owner, name, desc, true);
        }

        static RuntimeMemberKey field(String owner, String name, String desc) {
            return new RuntimeMemberKey(owner, name, desc, false);
        }
    }

    private static final class NameSource {
        private final String prefix;
        private int index;

        private NameSource(String prefix) {
            this.prefix = prefix == null ? "" : prefix;
        }

        String nextInternalName() {
            return prefix + nextSimpleName();
        }

        String nextSimpleName() {
            int value = index++;
            StringBuilder name = new StringBuilder();
            do {
                name.append((char) ('a' + (value % 26)));
                value = value / 26 - 1;
            } while (value >= 0);
            return name.toString();
        }
    }

}
