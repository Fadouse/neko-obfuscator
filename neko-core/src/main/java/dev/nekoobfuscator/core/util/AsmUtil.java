package dev.nekoobfuscator.core.util;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * ASM bytecode manipulation helper methods.
 */
public final class AsmUtil {
    private AsmUtil() {}

    public static boolean isReturn(int opcode) {
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
    }

    public static boolean isJump(int opcode) {
        return (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE)
            || opcode == Opcodes.GOTO
            || opcode == Opcodes.JSR
            || opcode == Opcodes.IFNULL
            || opcode == Opcodes.IFNONNULL;
    }

    public static boolean isConditionalJump(int opcode) {
        return (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE)
            || opcode == Opcodes.IFNULL
            || opcode == Opcodes.IFNONNULL;
    }

    public static boolean isTerminator(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return isReturn(opcode) || opcode == Opcodes.ATHROW
            || opcode == Opcodes.GOTO || opcode == 200 /* GOTO_W */
            || insn instanceof TableSwitchInsnNode
            || insn instanceof LookupSwitchInsnNode;
    }

    public static boolean isLoad(int opcode) {
        return (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD);
    }

    public static boolean isStore(int opcode) {
        return (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE);
    }

    public static boolean isInvoke(int opcode) {
        return opcode == Opcodes.INVOKEVIRTUAL
            || opcode == Opcodes.INVOKESPECIAL
            || opcode == Opcodes.INVOKESTATIC
            || opcode == Opcodes.INVOKEINTERFACE
            || opcode == Opcodes.INVOKEDYNAMIC;
    }

    public static boolean isPushConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1)
            || opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH
            || insn instanceof LdcInsnNode;
    }

    public static int getStackDelta(AbstractInsnNode insn) {
        // Simplified: returns how many items pushed minus popped
        // Full implementation would need to handle all 202 opcodes
        return 0; // placeholder - full implementation in StackSimulator
    }

    public static boolean isRealInstruction(AbstractInsnNode insn) {
        return insn.getType() != AbstractInsnNode.LABEL
            && insn.getType() != AbstractInsnNode.LINE
            && insn.getType() != AbstractInsnNode.FRAME;
    }

    public static int countRealInstructions(InsnList insns) {
        int count = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (isRealInstruction(insn)) count++;
        }
        return count;
    }

    public static LabelNode newLabel() {
        return new LabelNode();
    }

    public static InsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        }
        throw new IllegalArgumentException("Use IntInsnNode for values outside -1..5");
    }

    public static AbstractInsnNode pushIntAny(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, value);
        } else {
            return new LdcInsnNode(value);
        }
    }

    public static AbstractInsnNode pushLong(long value) {
        if (value == 0L) return new InsnNode(Opcodes.LCONST_0);
        if (value == 1L) return new InsnNode(Opcodes.LCONST_1);
        return new LdcInsnNode(value);
    }

    public static int typeToLoadOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ILOAD;
            case Type.LONG -> Opcodes.LLOAD;
            case Type.FLOAT -> Opcodes.FLOAD;
            case Type.DOUBLE -> Opcodes.DLOAD;
            default -> Opcodes.ALOAD;
        };
    }

    public static int typeToStoreOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ISTORE;
            case Type.LONG -> Opcodes.LSTORE;
            case Type.FLOAT -> Opcodes.FSTORE;
            case Type.DOUBLE -> Opcodes.DSTORE;
            default -> Opcodes.ASTORE;
        };
    }

    public static int typeToReturnOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> Opcodes.RETURN;
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.IRETURN;
            case Type.LONG -> Opcodes.LRETURN;
            case Type.FLOAT -> Opcodes.FRETURN;
            case Type.DOUBLE -> Opcodes.DRETURN;
            default -> Opcodes.ARETURN;
        };
    }

    public static int typeSize(Type type) {
        return switch (type.getSort()) {
            case Type.LONG, Type.DOUBLE -> 2;
            case Type.VOID -> 0;
            default -> 1;
        };
    }
}
