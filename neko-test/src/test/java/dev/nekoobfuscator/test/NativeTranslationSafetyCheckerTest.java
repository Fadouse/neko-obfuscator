package dev.nekoobfuscator.test;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.native_.translator.NativeTranslationSafetyChecker;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeTranslationSafetyCheckerTest {
    private final NativeTranslationSafetyChecker checker = new NativeTranslationSafetyChecker();

    @Test
    void admitsDirectObjectIdentityReturn() {
        L1Method method = method("identity", "(Ljava/lang/Object;)Ljava/lang/Object;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
            insns.add(new InsnNode(Opcodes.ARETURN));
        }, 1, 1);

        List<String> reasons = new ArrayList<>();
        assertTrue(checker.isSafe(method, reasons), () -> String.join("; ", reasons));
    }

    @Test
    void rejectsStringConstantReferenceReturn() {
        L1Method method = method("constant", "()Ljava/lang/String;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new LdcInsnNode("PASS"));
            insns.add(new InsnNode(Opcodes.ARETURN));
        }, 1, 0);

        List<String> reasons = new ArrayList<>();
        assertFalse(checker.isSafe(method, reasons));
        assertTrue(reasons.contains("reference return requires direct ALOAD/ACONST_NULL producer"), () -> String.join("; ", reasons));
    }

    @Test
    void rejectsReferenceReturnFromNonDirectProducer() {
        L1Method method = method("delayed", "(Ljava/lang/Object;)Ljava/lang/Object;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new InsnNode(Opcodes.POP));
            insns.add(new InsnNode(Opcodes.ARETURN));
        }, 2, 1);

        List<String> reasons = new ArrayList<>();
        assertFalse(checker.isSafe(method, reasons));
        assertTrue(reasons.contains("reference return requires direct ALOAD/ACONST_NULL producer"), () -> String.join("; ", reasons));
    }

    @Test
    void admitsReferenceReturnFromStoredParameterWithoutGcWindow() {
        L1Method method = method("storeThenReturn", "(Ljava/lang/Object;)Ljava/lang/Object;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
            insns.add(new VarInsnNode(Opcodes.ASTORE, 1));
            insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
            insns.add(new InsnNode(Opcodes.ARETURN));
        }, 1, 2);

        List<String> reasons = new ArrayList<>();
        assertTrue(checker.isSafe(method, reasons), () -> String.join("; ", reasons));
    }

    @Test
    void rejectsReferenceReturnAcrossGcPermittingWindow() {
        L1Method method = method("gcWindow", "(Ljava/lang/Object;)Ljava/lang/Object;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
            insns.add(new VarInsnNode(Opcodes.ASTORE, 1));
            insns.add(new LdcInsnNode("PASS"));
            insns.add(new InsnNode(Opcodes.POP));
            insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
            insns.add(new InsnNode(Opcodes.ARETURN));
        }, 1, 2);

        List<String> reasons = new ArrayList<>();
        assertFalse(checker.isSafe(method, reasons));
        assertTrue(reasons.contains("reference return requires no GC-permitting op between last write and ARETURN"), () -> String.join("; ", reasons));
    }

    private static L1Method method(String name, String desc, int access, MethodBody body, int maxStack, int maxLocals) {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/SafetyCheckerOwner";
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        MethodNode methodNode = new MethodNode(access, name, desc, null, null);
        body.accept(methodNode.instructions);
        methodNode.maxStack = maxStack;
        methodNode.maxLocals = maxLocals;
        classNode.methods.add(methodNode);

        L1Class owner = new L1Class(classNode);
        return owner.findMethod(name, desc);
    }

    @FunctionalInterface
    private interface MethodBody {
        void accept(org.objectweb.asm.tree.InsnList instructions);
    }
}
