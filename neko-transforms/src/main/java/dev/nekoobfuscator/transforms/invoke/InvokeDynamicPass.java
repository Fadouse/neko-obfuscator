package dev.nekoobfuscator.transforms.invoke;

import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * InvokeDynamic Obfuscation: wraps method calls in invokedynamic instructions.
 * The target metadata is encrypted and recovered in the bootstrap method.
 */
public final class InvokeDynamicPass implements TransformPass {

    private static final String FLOW_KEY_VALUES_KEY = "controlFlowFlattening.flowKeys";
    private static final String SKIP_TRY_CATCH_METHODS_OPTION = "skipMethodsWithTryCatch";
    private static final String SKIP_SWITCH_METHODS_OPTION = "skipMethodsWithSwitches";
    private static final String SKIP_MONITOR_METHODS_OPTION = "skipMethodsWithMonitors";
    private static final String SKIP_SENSITIVE_API_METHODS_OPTION = "skipSensitiveApiMethods";
    private static final String SKIP_PRIMITIVE_LOOP_CALLS_OPTION = "skipPrimitiveLoopCalls";
    private static final String MAX_INSTRUCTION_COUNT_OPTION = "maxApplicableInstructionCount";
    private static final String MAX_BRANCH_COUNT_OPTION = "maxBranchCount";
    private static final String BOOTSTRAP_CLASS = "dev/nekoobfuscator/runtime/NekoBootstrap";
    private static final String BOOTSTRAP_METHOD = "bsmInvoke";
    private static final String BOOTSTRAP_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;IIIIIII)Ljava/lang/invoke/CallSite;";
    private static final Handle BSM_HANDLE = new Handle(
        Opcodes.H_INVOKESTATIC, BOOTSTRAP_CLASS, BOOTSTRAP_METHOD, BOOTSTRAP_DESC, false);
    private static final String INDY_FIELD_PREFIX = "__i";
    private static final int CLASS_METADATA_ACCESS =
        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
    private static final int INTERFACE_METADATA_ACCESS =
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;

    @Override public String id() { return "invokeDynamic"; }
    @Override public String name() { return "InvokeDynamic Obfuscation"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }

    private DynamicKeyDerivationEngine keyEngine;
    private long classKey;
    private int targetCounter;
    private int metadataFieldCounter;

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        keyEngine = pctx.getPassData("keyEngine");
        if (keyEngine == null) {
            keyEngine = new DynamicKeyDerivationEngine(pctx.masterSeed());
            pctx.putPassData("keyEngine", keyEngine);
        }
        classKey = keyEngine.deriveClassKey(pctx.currentL1Class());
        targetCounter = 0;
        metadataFieldCounter = countExistingMetadataFields(pctx.currentL1Class());
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();
        IdentityHashMap<AbstractInsnNode, Long> flowKeyValues = pctx.getPassData(FLOW_KEY_VALUES_KEY);
        if (!method.hasCode() || method.isConstructor() || method.isClassInit()) return;
        MethodRiskStats stats = analyzeMethod(method.instructions());
        if (!isMethodEligible(pctx, method, stats)) return;

        double intensity = pctx.config().getTransformIntensity("invokeDynamic");
        InsnList insns = method.instructions();
        int methodNameHash = method.name().hashCode();
        int methodDescHash = method.descriptor().hashCode();
        long methodKey = DynamicKeyDerivationEngine.mix(
            DynamicKeyDerivationEngine.mix(classKey, methodNameHash), methodDescHash);

        List<MethodInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                int op = mi.getOpcode();
                if (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEINTERFACE) {
                    if (!"<init>".equals(mi.name) && !"<clinit>".equals(mi.name) && !mi.owner.startsWith("[")) {
                        if (!shouldSkipCall(method, mi, stats, pctx.config().transforms().get("invokeDynamic"))
                                && pctx.random().nextDouble() <= intensity) {
                            targets.add(mi);
                        }
                    }
                }
            }
        }

        if (targets.isEmpty()) return;

        MethodNode mn = method.asmNode();
        for (MethodInsnNode mi : targets) {
            int targetId = targetCounter++;
            int siteSalt = pctx.random().nextInt();
            boolean useFlowKey = flowKeyValues != null && flowKeyValues.containsKey(mi);
            long flowKey = useFlowKey ? flowKeyValues.get(mi) : 0L;

            int siteId = metadataFieldCounter++;
            long siteBaseKey = deriveSiteBaseKey(methodKey, siteSalt, targetId, mi.getOpcode(), flowKey, useFlowKey);
            addEncryptedMetadataField(clazz, siteId, 'o', mi.owner, deriveMetadataKey(siteBaseKey, 1));
            addEncryptedMetadataField(clazz, siteId, 'n', mi.name, deriveMetadataKey(siteBaseKey, 2));
            addEncryptedMetadataField(clazz, siteId, 'd', mi.desc, deriveMetadataKey(siteBaseKey, 3));

            Type[] argTypes = Type.getArgumentTypes(mi.desc);
            Type returnType = Type.getReturnType(mi.desc);
            Type receiverType = mi.getOpcode() == Opcodes.INVOKESTATIC ? null : Type.getObjectType(mi.owner);

            InsnList replacement = new InsnList();
            int nextLocal = mn.maxLocals;
            int[] argLocals = new int[argTypes.length];
            int receiverLocal = -1;

            for (int i = argTypes.length - 1; i >= 0; i--) {
                argLocals[i] = nextLocal;
                nextLocal += argTypes[i].getSize();
                replacement.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
            }
            if (receiverType != null) {
                receiverLocal = nextLocal;
                nextLocal += receiverType.getSize();
                replacement.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
            }
            mn.maxLocals = nextLocal;

            int packedCount = argTypes.length + (receiverType != null ? 1 : 0);
            replacement.add(pushInt(packedCount));
            replacement.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            int arrayIndex = 0;

            if (receiverType != null) {
                replacement.add(new InsnNode(Opcodes.DUP));
                replacement.add(pushInt(arrayIndex++));
                replacement.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
                replacement.add(new InsnNode(Opcodes.AASTORE));
            }

            for (int i = 0; i < argTypes.length; i++) {
                replacement.add(new InsnNode(Opcodes.DUP));
                replacement.add(pushInt(arrayIndex++));
                replacement.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
                boxIfNeeded(replacement, argTypes[i]);
                replacement.add(new InsnNode(Opcodes.AASTORE));
            }

            replacement.add(new InvokeDynamicInsnNode(
                "invoke",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                BSM_HANDLE,
                siteId,
                methodNameHash,
                methodDescHash,
                siteSalt,
                mi.getOpcode(),
                targetId,
                useFlowKey ? 1 : 0
            ));
            adaptReturnValue(replacement, returnType);

            insns.insertBefore(mi, replacement);
            insns.remove(mi);
        }

        pctx.currentL1Class().markDirty();
    }

    private boolean isMethodEligible(PipelineContext pctx, L1Method method, MethodRiskStats stats) {
        TransformConfig config = pctx.config().transforms().get("invokeDynamic");
        if (!method.tryCatchBlocks().isEmpty() && booleanOption(config, SKIP_TRY_CATCH_METHODS_OPTION, true)) {
            return false;
        }
        if (stats.hasSwitch() && booleanOption(config, SKIP_SWITCH_METHODS_OPTION, true)) {
            return false;
        }
        if (stats.hasMonitor() && booleanOption(config, SKIP_MONITOR_METHODS_OPTION, true)) {
            return false;
        }
        if (stats.hasSensitiveApi() && booleanOption(config, SKIP_SENSITIVE_API_METHODS_OPTION, true)) {
            return false;
        }
        if (method.instructionCount() > intOption(config, MAX_INSTRUCTION_COUNT_OPTION, 260)) {
            return false;
        }
        return stats.branchCount() <= intOption(config, MAX_BRANCH_COUNT_OPTION, 24);
    }

    private boolean shouldSkipCall(L1Method method, MethodInsnNode mi, MethodRiskStats stats, TransformConfig config) {
        if (stats.backwardBranches() > 0 && booleanOption(config, SKIP_PRIMITIVE_LOOP_CALLS_OPTION, true)) {
            if (hasPrimitiveSignature(mi.desc) || isHotPrimitiveOwner(method.owner().name(), mi.owner)) {
                return true;
            }
        }
        return false;
    }

    private MethodRiskStats analyzeMethod(InsnList insns) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index++);
        }

        int branchCount = 0;
        int backwardBranches = 0;
        boolean hasSwitch = false;
        boolean hasMonitor = false;
        boolean hasSensitiveApi = false;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode jump) {
                branchCount++;
                Integer from = positions.get(insn);
                Integer target = positions.get(jump.label);
                if (from != null && target != null && target <= from) {
                    backwardBranches++;
                }
                continue;
            }
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                hasSwitch = true;
                continue;
            }
            if (insn instanceof MethodInsnNode mi && isSensitiveApiCall(mi)) {
                hasSensitiveApi = true;
            }
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                hasMonitor = true;
            }
        }
        return new MethodRiskStats(branchCount, backwardBranches, hasSwitch, hasMonitor, hasSensitiveApi);
    }

    private boolean hasPrimitiveSignature(String descriptor) {
        for (Type argType : Type.getArgumentTypes(descriptor)) {
            if (isPrimitive(argType)) return true;
        }
        return isPrimitive(Type.getReturnType(descriptor));
    }

    private boolean isPrimitive(Type type) {
        int sort = type.getSort();
        return sort != Type.OBJECT && sort != Type.ARRAY && sort != Type.METHOD && sort != Type.VOID;
    }

    private boolean isHotPrimitiveOwner(String methodOwner, String targetOwner) {
        return methodOwner.contains("/bench/")
            || methodOwner.endsWith("/Calc")
            || targetOwner.equals("java/lang/String")
            || targetOwner.equals("java/lang/Math");
    }

    private boolean isSensitiveApiCall(MethodInsnNode mi) {
        String owner = mi.owner;
        if (owner.startsWith("java/lang/reflect/")) return true;
        if (owner.startsWith("java/lang/annotation/")) return true;
        if (owner.equals("java/lang/Class") || owner.startsWith("java/lang/ClassLoader")) return true;
        if (owner.equals("java/lang/invoke/MethodHandles") || owner.startsWith("java/lang/invoke/MethodHandle")) return true;
        if (owner.equals("java/lang/StackWalker")) return true;
        return (owner.equals("java/lang/Thread") || owner.equals("java/lang/Throwable"))
            && "getStackTrace".equals(mi.name);
    }

    private long deriveSiteBaseKey(long methodKey, int siteSalt, int targetId, int invokeType,
            long flowKey, boolean useFlowKey) {
        long siteKey = DynamicKeyDerivationEngine.mix(methodKey, siteSalt);
        siteKey = DynamicKeyDerivationEngine.mix(siteKey, targetId);
        siteKey = DynamicKeyDerivationEngine.mix(siteKey, invokeType);
        if (useFlowKey) {
            siteKey = DynamicKeyDerivationEngine.mix(siteKey, flowKey);
        }
        return siteKey;
    }

    private long deriveMetadataKey(long siteBaseKey, int componentId) {
        return DynamicKeyDerivationEngine.finalize_(DynamicKeyDerivationEngine.mix(siteBaseKey, componentId));
    }

    private void addEncryptedMetadataField(L1Class clazz, int siteId, char component, String value, long key) {
        String fieldName = metadataFieldName(siteId, component);
        byte[] encrypted = DynamicKeyDerivationEngine.encrypt(value.getBytes(StandardCharsets.UTF_8), key);
        FieldNode fn = new FieldNode(
            metadataAccess(clazz.asmNode()),
            fieldName, "Ljava/lang/String;", null,
            new String(encrypted, StandardCharsets.ISO_8859_1));
        clazz.asmNode().fields.add(fn);
    }

    private int countExistingMetadataFields(L1Class clazz) {
        int maxSiteId = -1;
        for (FieldNode fn : clazz.asmNode().fields) {
            if (!fn.name.startsWith(INDY_FIELD_PREFIX)
                    || (!"[B".equals(fn.desc) && !"Ljava/lang/String;".equals(fn.desc))) {
                continue;
            }
            int suffixStart = INDY_FIELD_PREFIX.length();
            int suffixEnd = fn.name.length() - 1;
            if (suffixEnd <= suffixStart) continue;
            try {
                int siteId = Integer.parseInt(fn.name.substring(suffixStart, suffixEnd));
                maxSiteId = Math.max(maxSiteId, siteId);
            } catch (NumberFormatException ignored) {
            }
        }
        return maxSiteId + 1;
    }

    private String metadataFieldName(int siteId, char component) {
        return INDY_FIELD_PREFIX + siteId + component;
    }

    private int metadataAccess(ClassNode classNode) {
        return (classNode.access & Opcodes.ACC_INTERFACE) != 0
            ? INTERFACE_METADATA_ACCESS
            : CLASS_METADATA_ACCESS;
    }

    private boolean booleanOption(TransformConfig config, String key, boolean defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private int intOption(TransformConfig config, String key, int defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }

    private void boxIfNeeded(InsnList insns, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
            case Type.BYTE -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
            case Type.CHAR -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
            case Type.SHORT -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
            case Type.INT -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            case Type.FLOAT -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
            case Type.LONG -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
            case Type.DOUBLE -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
            default -> {
            }
        }
    }

    private void adaptReturnValue(InsnList insns, Type returnType) {
        switch (returnType.getSort()) {
            case Type.VOID -> insns.add(new InsnNode(Opcodes.POP));
            case Type.BOOLEAN -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Boolean", "booleanValue", "()Z", false));
            }
            case Type.BYTE -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Byte", "byteValue", "()B", false));
            }
            case Type.CHAR -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Character", "charValue", "()C", false));
            }
            case Type.SHORT -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Short", "shortValue", "()S", false));
            }
            case Type.INT -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Integer", "intValue", "()I", false));
            }
            case Type.FLOAT -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Float", "floatValue", "()F", false));
            }
            case Type.LONG -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Long", "longValue", "()J", false));
            }
            case Type.DOUBLE -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Double", "doubleValue", "()D", false));
            }
            default -> insns.add(new TypeInsnNode(Opcodes.CHECKCAST, returnType.getInternalName()));
        }
    }

    private record MethodRiskStats(int branchCount, int backwardBranches, boolean hasSwitch,
                                   boolean hasMonitor, boolean hasSensitiveApi) {}
}
