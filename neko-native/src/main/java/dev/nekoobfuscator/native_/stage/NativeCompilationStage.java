package dev.nekoobfuscator.native_.stage;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.jar.ResourceEntry;
import dev.nekoobfuscator.native_.codegen.NativeBuildEngine;
import dev.nekoobfuscator.native_.resource.ResourceEncryptor;
import dev.nekoobfuscator.native_.translator.NativeTranslationSafetyChecker;
import dev.nekoobfuscator.native_.translator.NativeTranslator;
import dev.nekoobfuscator.native_.translator.NativeTranslator.MethodSelection;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import dev.nekoobfuscator.native_.translator.NativeTranslator.TranslationResult;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Compiles selected JVM methods into JNI libraries and rewrites classes to native stubs.
 */
public final class NativeCompilationStage {
    private static final Logger log = LoggerFactory.getLogger(NativeCompilationStage.class);
    private static final String NATIVE_TRANSLATE_DESC = "Ldev/nekoobfuscator/api/annotation/NativeTranslate;";
    private static final String NATIVE_LOADER_OWNER = "dev/nekoobfuscator/runtime/NekoNativeLoader";
    private static final String NATIVE_LOAD_NAME = "load";
    private static final String NATIVE_LOAD_DESC = "()V";
    private static final String LINKAGE_ERROR_INTERNAL = "java/lang/LinkageError";
    private static final String LINKAGE_ERROR_INIT_DESC = "(Ljava/lang/String;)V";
    private static final String LINKAGE_ERROR_MESSAGE = "please check your native library load correctly";

    private final ObfuscationConfig.NativeConfig cfg;
    private final long masterSeed;
    private final NativeTranslationSafetyChecker safetyChecker = new NativeTranslationSafetyChecker();

    public NativeCompilationStage(ObfuscationConfig.NativeConfig cfg, long masterSeed) {
        this.cfg = cfg;
        this.masterSeed = masterSeed;
    }

    public NativeStageResult apply(Map<String, byte[]> allClasses, List<ResourceEntry> resources) {
        Map<String, byte[]> originalClasses = new LinkedHashMap<>(allClasses);
        List<ResourceEntry> originalResources = new ArrayList<>(resources);

        Map<String, ParsedClass> parsedClasses = parseClasses(allClasses);
        List<MethodSelection> selectedMethods = new ArrayList<>();
        Map<String, List<String>> rejectionReasons = new LinkedHashMap<>();
        int rejectedMethodCount = 0;

        for (ParsedClass parsedClass : parsedClasses.values()) {
            boolean classAnnotated = hasAnnotation(parsedClass.l1Class().asmNode().visibleAnnotations)
                || hasAnnotation(parsedClass.l1Class().asmNode().invisibleAnnotations);

            for (L1Method method : parsedClass.l1Class().methods()) {
                if (!shouldTranslate(method, parsedClass.l1Class().name(), classAnnotated)) {
                    continue;
                }
                if (method.isConstructor() || method.isClassInit() || method.isAbstract() || method.isNative() || method.isBridge()) {
                    continue;
                }

                List<String> reasons = new ArrayList<>();
                if (parsedClass.l1Class().isInterface()) {
                    reasons.add("interface methods cannot be marked ACC_NATIVE in valid classfiles");
                }
                if (!safetyChecker.isSafe(method, reasons)) {
                    rejectedMethodCount++;
                    rejectionReasons.put(methodKey(parsedClass.l1Class().name(), method), reasons);
                    log.warn("Rejected native translation for {}: {}", methodKey(parsedClass.l1Class().name(), method), String.join("; ", reasons));
                    if (!cfg.skipOnError()) {
                        throw new IllegalStateException("Unsafe native translation target: " + methodKey(parsedClass.l1Class().name(), method));
                    }
                    continue;
                }
                selectedMethods.add(new MethodSelection(parsedClass.l1Class(), method));
            }
        }

        if (selectedMethods.isEmpty()) {
            log.info("Native stage selected no methods");
            return new NativeStageResult(originalClasses, originalResources, 0, rejectedMethodCount);
        }

        TranslationResult translationResult;
        try {
            translationResult = new NativeTranslator(cfg.outputPrefix(), cfg.obfuscateJniSlotDispatch(), cfg.cacheJniIds(), masterSeed).translate(selectedMethods);
        } catch (RuntimeException ex) {
            if (cfg.skipOnError()) {
                log.error("Native translation failed; continuing without native stage", ex);
                return new NativeStageResult(originalClasses, originalResources, 0, rejectedMethodCount + selectedMethods.size());
            }
            throw ex;
        }

        Map<String, byte[]> builtLibraries;
        try {
            builtLibraries = new NativeBuildEngine(cfg.zigPath()).build(
                translationResult.source(),
                translationResult.header(),
                cfg.targets()
            );
        } catch (Exception ex) {
            if (cfg.skipOnError()) {
                log.error("Native compilation failed; continuing without native stage", ex);
                return new NativeStageResult(originalClasses, originalResources, 0, rejectedMethodCount + selectedMethods.size());
            }
            throw new RuntimeException("Native compilation failed", ex);
        }

        if (builtLibraries.isEmpty()) {
            String message = "Native compilation produced no libraries";
            if (cfg.skipOnError()) {
                log.error(message);
                return new NativeStageResult(originalClasses, originalResources, 0, rejectedMethodCount + selectedMethods.size());
            }
            throw new IllegalStateException(message);
        }

        List<String> modifiedClasses = rewriteClasses(parsedClasses, translationResult.bindings());

        Map<String, byte[]> mutatedClasses = new LinkedHashMap<>(originalClasses);
        for (String className : modifiedClasses) {
            ParsedClass parsedClass = parsedClasses.get(className);
            mutatedClasses.put(className, writeClass(parsedClass.classNode()));
        }

        List<ResourceEntry> updatedResources = new ArrayList<>(resources);
        for (Map.Entry<String, byte[]> entry : builtLibraries.entrySet()) {
            updatedResources.add(new ResourceEntry(entry.getKey(), entry.getValue()));
        }
        if (cfg.resourceEncryption()) {
            updatedResources = new ResourceEncryptor(masterSeed).encryptResources(updatedResources);
        }

        if (!rejectionReasons.isEmpty()) {
            log.info("Native stage rejected {} method(s)", rejectionReasons.size());
        }
        return new NativeStageResult(mutatedClasses, updatedResources, translationResult.methodCount(), rejectedMethodCount);
    }

    private Map<String, ParsedClass> parseClasses(Map<String, byte[]> allClasses) {
        Map<String, ParsedClass> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : allClasses.entrySet()) {
            ClassReader reader = new ClassReader(entry.getValue());
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, ClassReader.EXPAND_FRAMES);
            parsed.put(classNode.name, new ParsedClass(classNode, new L1Class(classNode)));
        }
        return parsed;
    }

    private boolean shouldTranslate(L1Method method, String classInternalName, boolean classAnnotated) {
        if (classInternalName.startsWith("dev/nekoobfuscator/runtime/")) {
            return false;
        }
        if (cfg.includeAnnotated()) {
            if (classAnnotated || hasAnnotation(method.asmNode().visibleAnnotations) || hasAnnotation(method.asmNode().invisibleAnnotations)) {
                return true;
            }
        }

        String methodTarget = classInternalName + '#' + method.name();
        for (String pattern : cfg.excludePatterns()) {
            Pattern regex = globToPattern(pattern);
            if (pattern.contains("#")) {
                if (regex.matcher(methodTarget).matches()) {
                    return false;
                }
            } else if (regex.matcher(classInternalName).matches()) {
                return false;
            }
        }
        for (String pattern : cfg.methods()) {
            Pattern regex = globToPattern(pattern);
            if (pattern.contains("#")) {
                if (regex.matcher(methodTarget).matches()) {
                    return true;
                }
            } else if (regex.matcher(classInternalName).matches()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode annotation : annotations) {
            if (NATIVE_TRANSLATE_DESC.equals(annotation.desc)) {
                return true;
            }
        }
        return false;
    }

    private Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*') {
                boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doubleStar) {
                    boolean slashAfter = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                    regex.append(slashAfter ? "(?:.*/)?" : ".*");
                    i += slashAfter ? 2 : 1;
                } else {
                    regex.append("[^#]*");
                }
            } else if ("\\.^$|?+()[]{}".indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }

    private List<String> rewriteClasses(Map<String, ParsedClass> parsedClasses, List<NativeMethodBinding> bindings) {
        Map<String, List<NativeMethodBinding>> byOwner = new HashMap<>();
        List<String> modifiedClasses = new ArrayList<>();
        for (NativeMethodBinding binding : bindings) {
            byOwner.computeIfAbsent(binding.ownerInternalName(), ignored -> new ArrayList<>()).add(binding);
        }

        for (Map.Entry<String, List<NativeMethodBinding>> entry : byOwner.entrySet()) {
            ParsedClass parsedClass = parsedClasses.get(entry.getKey());
            if (parsedClass == null) {
                continue;
            }
            modifiedClasses.add(entry.getKey());

            for (NativeMethodBinding binding : entry.getValue()) {
                MethodNode methodNode = findMethod(parsedClass.classNode(), binding.methodName(), binding.descriptor());
                if (methodNode == null) {
                    continue;
                }
                rewriteTranslatedMethod(methodNode);
            }
        }

        List<String> loaderOwners = new ArrayList<>(modifiedClasses);
        for (String owner : loaderOwners) {
            ensureClinitLoadsNative(parsedClasses.get(owner).classNode());
        }
        return modifiedClasses;
    }

    private void rewriteTranslatedMethod(MethodNode methodNode) {
        methodNode.instructions.clear();
        if (methodNode.tryCatchBlocks != null) {
            methodNode.tryCatchBlocks.clear();
        }
        if (methodNode.localVariables != null) {
            methodNode.localVariables.clear();
        }
        methodNode.visibleLocalVariableAnnotations = null;
        methodNode.invisibleLocalVariableAnnotations = null;
        methodNode.access &= ~(Opcodes.ACC_NATIVE | Opcodes.ACC_STRICT);

        InsnList body = methodNode.instructions;
        // Inflate the body above HotSpot's three inline thresholds:
        //   MaxInlineSize     = 35   (C1 trivial inliner)
        //   MaxInlineLevel    irrelevant
        //   FreqInlineSize    = 325  (C2 frequency-based inliner)
        // Each ICONST_0 + IFEQ pair is 4 bytes; 90 pairs = 360 bytes, which
        // pushes the body over FreqInlineSize so neither C1 nor C2 will
        // inline us. Control always falls through (the comparison is a
        // constant zero) so semantics are preserved. 90 pairs encode their
        // branch targets within s2 reach (jump distance 360 < 32767).
        LabelNode exitLabel = new LabelNode();
        for (int i = 0; i < 90; i++) {
            body.add(new InsnNode(Opcodes.ICONST_0));
            body.add(new JumpInsnNode(Opcodes.IFEQ, exitLabel));
        }
        body.add(exitLabel);
        body.add(new TypeInsnNode(Opcodes.NEW, LINKAGE_ERROR_INTERNAL));
        body.add(new InsnNode(Opcodes.DUP));
        body.add(new LdcInsnNode(LINKAGE_ERROR_MESSAGE));
        body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LINKAGE_ERROR_INTERNAL, "<init>", LINKAGE_ERROR_INIT_DESC, false));
        body.add(new InsnNode(Opcodes.ATHROW));

        methodNode.maxStack = 3;
        methodNode.maxLocals = parameterSlotCount(methodNode.access, methodNode.desc);
    }

    private int parameterSlotCount(int access, String descriptor) {
        int slots = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argumentType : Type.getArgumentTypes(descriptor)) {
            slots += argumentType.getSize();
        }
        return slots;
    }

    private MethodNode findMethod(ClassNode classNode, String name, String desc) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    private void ensureClinitLoadsNative(ClassNode classNode) {
        MethodNode clinit = findMethod(classNode, "<clinit>", "()V");
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            appendNativeBootstrap(clinit.instructions);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            clinit.maxStack = 1;
            clinit.maxLocals = 0;
            classNode.methods.add(clinit);
            return;
        }

        if (containsNativeLoad(clinit)) {
            return;
        }
        InsnList loadCall = new InsnList();
        appendNativeBootstrap(loadCall);
        clinit.instructions.insert(loadCall);
        clinit.maxStack = Math.max(clinit.maxStack, 1);
    }

    private void appendNativeBootstrap(InsnList instructions) {
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, NATIVE_LOADER_OWNER, NATIVE_LOAD_NAME, NATIVE_LOAD_DESC, false));
    }

    private boolean containsNativeLoad(MethodNode clinit) {
        for (var insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode methodInsn
                && methodInsn.getOpcode() == Opcodes.INVOKESTATIC
                && NATIVE_LOADER_OWNER.equals(methodInsn.owner)
                && NATIVE_LOAD_NAME.equals(methodInsn.name)
                && NATIVE_LOAD_DESC.equals(methodInsn.desc)) {
                return true;
            }
        }
        return false;
    }

    private byte[] writeClass(ClassNode classNode) {
        try {
            ClassWriter writer = new ClassWriter(0);
            classNode.accept(writer);
            return writer.toByteArray();
        } catch (Throwable plainWriteError) {
            log.warn("Plain class write failed for {}; falling back to COMPUTE_MAXS: {}", classNode.name, plainWriteError.getMessage());
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            return writer.toByteArray();
        }
    }

    private String methodKey(String owner, L1Method method) {
        return owner + '#' + method.name() + method.descriptor();
    }

    private record ParsedClass(ClassNode classNode, L1Class l1Class) {}

    public record NativeStageResult(
        Map<String, byte[]> allClasses,
        List<ResourceEntry> resources,
        int translatedMethodCount,
        int rejectedMethodCount
    ) {}
}
