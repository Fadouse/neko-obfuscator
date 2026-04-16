package dev.nekoobfuscator.transforms.data;

import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Replaces numeric constants with obfuscated expressions.
 * Uses XOR, arithmetic, and table-lookup patterns with dynamic keys.
 */
public final class NumberEncryptionPass implements TransformPass {
    private static final String SKIP_TRY_CATCH_METHODS_OPTION = "skipMethodsWithTryCatch";
    private static final String SKIP_SWITCH_METHODS_OPTION = "skipMethodsWithSwitches";
    private static final String SKIP_MONITOR_METHODS_OPTION = "skipMethodsWithMonitors";
    private static final String SKIP_SENSITIVE_API_METHODS_OPTION = "skipSensitiveApiMethods";
    private static final String SKIP_SMALL_LOOP_CONSTANTS_OPTION = "skipSmallLoopConstants";
    private static final String MAX_PLAIN_LOOP_CONSTANT_OPTION = "maxPlainLoopConstant";
    private static final String MAX_INSTRUCTION_COUNT_OPTION = "maxApplicableInstructionCount";
    private static final String MAX_BRANCH_COUNT_OPTION = "maxBranchCount";


    @Override public String id() { return "numberEncryption"; }
    @Override public String name() { return "Number Encryption"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }

    private DynamicKeyDerivationEngine keyEngine;
    private long classKey;

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        keyEngine = pctx.getPassData("keyEngine");
        if (keyEngine == null) {
            keyEngine = new DynamicKeyDerivationEngine(pctx.masterSeed());
            pctx.putPassData("keyEngine", keyEngine);
        }
        classKey = keyEngine.deriveClassKey(pctx.currentL1Class());
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        if (!method.hasCode()) return;
        MethodRiskStats stats = analyzeMethod(method.instructions());
        if (!isMethodEligible(pctx, method, stats)) return;

        long methodKey = keyEngine.deriveMethodKey(method, classKey);
        InsnList insns = method.instructions();

        List<AbstractInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            // Target integer constants
            if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
                targets.add(insn);
            } else if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                targets.add(insn);
            } else if (insn instanceof LdcInsnNode ldc) {
                if (ldc.cst instanceof Integer || ldc.cst instanceof Long) {
                    targets.add(insn);
                }
            }
        }

        if (targets.isEmpty()) return;

        int insnIdx = 0;
        for (AbstractInsnNode insn : targets) {
            if (shouldKeepPlain(insn, stats, pctx.config().transforms().get("numberEncryption"))) {
                continue;
            }
            int salt = pctx.random().nextInt();
            long insnKey = keyEngine.deriveInsnKey(methodKey, insnIdx++, salt);
            int keyInt = (int) insnKey;

            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long longVal) {
                // Long XOR: value = (encrypted ^ key)
                long encrypted = longVal ^ insnKey;
                InsnList replacement = new InsnList();
                replacement.add(new LdcInsnNode(encrypted));
                replacement.add(new LdcInsnNode(insnKey));
                replacement.add(new InsnNode(Opcodes.LXOR));
                insns.insertBefore(insn, replacement);
                insns.remove(insn);
            } else {
                // Get the int value
                int value = getIntValue(insn);
                // XOR pattern: value = (a ^ b) where a = value ^ randomKey
                int encrypted = value ^ keyInt;
                InsnList replacement = new InsnList();
                replacement.add(new LdcInsnNode(encrypted));
                replacement.add(new LdcInsnNode(keyInt));
                replacement.add(new InsnNode(Opcodes.IXOR));
                insns.insertBefore(insn, replacement);
                insns.remove(insn);
            }
        }

        pctx.currentL1Class().markDirty();
    }

    private boolean isMethodEligible(PipelineContext pctx, L1Method method, MethodRiskStats stats) {
        TransformConfig config = pctx.config().transforms().get("numberEncryption");
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
        if (method.instructionCount() > intOption(config, MAX_INSTRUCTION_COUNT_OPTION, 220)) {
            return false;
        }
        return stats.branchCount() <= intOption(config, MAX_BRANCH_COUNT_OPTION, 18);
    }

    private boolean shouldKeepPlain(AbstractInsnNode insn, MethodRiskStats stats, TransformConfig config) {
        if (!booleanOption(config, SKIP_SMALL_LOOP_CONSTANTS_OPTION, true) || stats.backwardBranches() == 0) {
            return false;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long) {
            return false;
        }
        int value = getIntValue(insn);
        return Math.abs(value) <= intOption(config, MAX_PLAIN_LOOP_CONSTANT_OPTION, 16);
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

    private boolean isSensitiveApiCall(MethodInsnNode mi) {
        String owner = mi.owner;
        if (owner.startsWith("java/lang/reflect/")) return true;
        if (owner.startsWith("java/lang/annotation/")) return true;
        if (owner.equals("java/lang/Class") || owner.startsWith("java/lang/ClassLoader")) return true;
        if (owner.equals("java/lang/StackWalker")) return true;
        return (owner.equals("java/lang/Thread") || owner.equals("java/lang/Throwable"))
            && "getStackTrace".equals(mi.name);
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

    private int getIntValue(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        } else if (insn instanceof IntInsnNode intInsn) {
            return intInsn.operand;
        } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) {
            return i;
        }
        return 0;
    }

    private record MethodRiskStats(int branchCount, int backwardBranches, boolean hasSwitch,
                                   boolean hasMonitor, boolean hasSensitiveApi) {}
}
