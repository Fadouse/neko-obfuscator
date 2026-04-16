package dev.nekoobfuscator.core.pipeline;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.jar.*;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

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
            if (config.isTransformEnabled(pass.id())) {
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

            // Invalidate cached IR after each pass (transforms may have changed bytecode)
            ctx.invalidateAll();
        }

        // Step 6: Clean up bytecode for all dirty classes
        for (L1Class clazz : input.classes()) {
            if (clazz.isDirty()) {
                for (var mn : clazz.asmNode().methods) {
                    if (mn.instructions == null || mn.instructions.size() == 0) continue;

                    // 6a. Remove all existing FrameNodes - they'll be recomputed
                    var it = mn.instructions.iterator();
                    while (it.hasNext()) {
                        var insn = it.next();
                        if (insn instanceof org.objectweb.asm.tree.FrameNode) {
                            it.remove();
                        }
                    }

                    // 6b. Remove unreachable instructions (dead code elimination)
                    // This is CRITICAL for COMPUTE_FRAMES to succeed after CFF
                    removeDeadCode(mn);

                    // 6c. Drop try/catch entries whose protected range collapsed after cleanup
                    sanitizeTryCatchBlocks(mn);

                    // 6d. Preserve maxStack as a safe analysis bound and restore a valid maxLocals floor
                    mn.maxLocals = recomputeMaxLocals(mn);
                }
            }
        }

        // Step 7: Inject runtime classes into output and patch master seed
        List<L1Class> allClasses = new ArrayList<>(input.classes());
        injectRuntimeClasses(allClasses, hierarchy, ctx.masterSeed());

        // Also include any classes added by passes (e.g., NekoFlowException)
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

        // Step 8: Write output JAR
        log.info("Writing output JAR: {}", outputJar);
        JarOutput output = new JarOutput(hierarchy);
        output.write(outputJar, allClasses, input.resources(), input.manifest());

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Obfuscation completed in {}ms", elapsed);
    }

    /**
     * Inject neko-runtime classes into the output JAR so the obfuscated code
     * can find bootstrap methods, key derivation, decryptors, etc.
     */
    private void injectRuntimeClasses(List<L1Class> classes, ClassHierarchy hierarchy, long masterSeed) {
        String[] runtimeClasses = {
            "dev/nekoobfuscator/runtime/NekoBootstrap",
            "dev/nekoobfuscator/runtime/NekoKeyDerivation",
            "dev/nekoobfuscator/runtime/NekoStringDecryptor",
            "dev/nekoobfuscator/runtime/NekoFlowException",
            "dev/nekoobfuscator/runtime/NekoContext",
            "dev/nekoobfuscator/runtime/NekoClassLoader",
            "dev/nekoobfuscator/runtime/NekoNativeLoader",
            "dev/nekoobfuscator/runtime/NekoResourceLoader",
        };

        for (String className : runtimeClasses) {
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
                // Patch MASTER_SEED in NekoKeyDerivation
                if (className.endsWith("NekoKeyDerivation")) {
                    patchMasterSeed(cn, masterSeed);
                }
                L1Class l1 = new L1Class(cn);
                classes.add(l1);
                hierarchy.addClass(l1);
                log.debug("Injected runtime class: {}", className);
            } catch (Exception e) {
                log.warn("Failed to inject runtime class {}: {}", className, e.getMessage());
            }
        }
    }

    /**
     * Patch the MASTER_SEED static field in NekoKeyDerivation to match
     * the master seed used during this obfuscation run.
     */
    private void patchMasterSeed(org.objectweb.asm.tree.ClassNode cn, long masterSeed) {
        // Patch all occurrences: FieldNode.value AND <clinit> LDC
        for (org.objectweb.asm.tree.FieldNode fn : cn.fields) {
            if ("MASTER_SEED".equals(fn.name) && "J".equals(fn.desc)) {
                fn.value = masterSeed;
            }
        }
        // Patch <clinit> - find the LDC that initializes MASTER_SEED
        for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name)) {
                for (org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof Long l) {
                        if (l == 0x4E454B4F4F42464CL) { // original default seed
                            ldc.cst = masterSeed;
                            log.info("Patched MASTER_SEED to 0x{}", Long.toHexString(masterSeed));
                        }
                    }
                }
            }
        }
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
}
