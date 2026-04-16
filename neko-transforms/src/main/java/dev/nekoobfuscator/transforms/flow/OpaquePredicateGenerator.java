package dev.nekoobfuscator.transforms.flow;

import dev.nekoobfuscator.core.util.RandomUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Generates opaque predicate expressions that always evaluate to true or false
 * but appear non-trivial to static analysis.
 */
public final class OpaquePredicateGenerator {

    private final RandomUtil random;

    public OpaquePredicateGenerator(RandomUtil random) {
        this.random = random;
    }

    /**
     * Generate an opaque predicate that always evaluates to true.
     * Pushes 1 (true) on the stack for IFEQ-style conditionals.
     */
    public InsnList generateAlwaysTrue() {
        return switch (random.nextInt(4)) {
            case 0 -> arithmeticTrue();
            case 1 -> arrayLengthTrue();
            case 2 -> hashCodeTrue();
            default -> threadTrue();
        };
    }

    /**
     * Generate an opaque predicate that always evaluates to false.
     */
    public InsnList generateAlwaysFalse() {
        InsnList insns = generateAlwaysTrue();
        // Negate: push 1, XOR with the true result
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IXOR));
        return insns;
    }

    // (x * x + x) % 2 == 0 is always true for any integer x
    private InsnList arithmeticTrue() {
        InsnList insns = new InsnList();
        int x = random.nextInt();
        insns.add(new LdcInsnNode(x));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.IMUL));   // x * x
        insns.add(new LdcInsnNode(x));
        insns.add(new InsnNode(Opcodes.IADD));    // x * x + x
        insns.add(new InsnNode(Opcodes.ICONST_2));
        insns.add(new InsnNode(Opcodes.IREM));    // % 2
        // result is 0 (always true for "== 0" check)
        // We want to push 1 for "true", so: result == 0 ? 1 : 0
        LabelNode nonZero = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(nonZero);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // new int[0].length >= 0 is always true
    private InsnList arrayLengthTrue() {
        InsnList insns = new InsnList();
        insns.add(new InsnNode(Opcodes.ICONST_1)); // small array
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        // length >= 0 is always true, length is 1
        // Push 1 directly since array.length > 0
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        return insns;
    }

    // System.identityHashCode(new Object()) | 1 != 0 is always true
    private InsnList hashCodeTrue() {
        InsnList insns = new InsnList();
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System",
            "identityHashCode", "(Ljava/lang/Object;)I", false));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
        // result is always != 0 (OR with 1 ensures bit 0 is set)
        // Convert to boolean 1
        LabelNode zero = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFEQ, zero));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(zero);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // Thread.currentThread() != null is always true
    private InsnList threadTrue() {
        InsnList insns = new InsnList();
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread",
            "currentThread", "()Ljava/lang/Thread;", false));
        LabelNode isNull = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFNULL, isNull));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(isNull);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }
}
