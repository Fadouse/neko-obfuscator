package dev.nekoobfuscator.test;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.native_.translator.NativeTranslationSafetyChecker;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.Type;
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
    void admitsDirectLdcStringReferenceReturn() {
        L1Method method = method("constant", "()Ljava/lang/String;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new LdcInsnNode("PASS"));
            insns.add(new InsnNode(Opcodes.ARETURN));
        }, 1, 0);

        List<String> reasons = new ArrayList<>();
        assertTrue(checker.isSafe(method, reasons), () -> String.join("; ", reasons));
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

    @Test
    void admitsPrimitiveStaticFieldFlow() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "nk/test/sample/SampleClass";
        classNode.superName = "java/lang/Object";
        classNode.fields = new ArrayList<>();
        classNode.methods = new ArrayList<>();
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "counter", "I", null, null));

        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sampleMethod", "()V", null, null);
        methodNode.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, "counter", "I"));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.IADD));
        methodNode.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, "counter", "I"));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));
        methodNode.maxStack = 2;
        methodNode.maxLocals = 0;
        classNode.methods.add(methodNode);

        L1Class owner = new L1Class(classNode);
        L1Method method = owner.findMethod("sampleMethod", "()V");
        List<String> reasons = new ArrayList<>();

        assertTrue(checker.isSafe(method, reasons), () -> String.join("; ", reasons));
    }

    @Test
    void admitsLdcClassReferenceReturn() {
        L1Method method = method("sampleClass", "()Ljava/lang/Class;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new LdcInsnNode(Type.getObjectType("nk/test/sample/SampleType")));
            insns.add(new InsnNode(Opcodes.ARETURN));
        }, 1, 0);

        List<String> reasons = new ArrayList<>();
        assertTrue(checker.isSafe(method, reasons), () -> String.join("; ", reasons));
    }

    @Test
    void admitsLdcArrayReferenceReturn() {
        L1Method method = method("sampleArray", "()Ljava/lang/Class;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new LdcInsnNode(Type.getType("[Lnk/test/sample/SampleType;")));
            insns.add(new InsnNode(Opcodes.ARETURN));
        }, 1, 0);

        List<String> reasons = new ArrayList<>();
        assertTrue(checker.isSafe(method, reasons), () -> String.join("; ", reasons));
    }

    @Test
    void admitsLdcClassInTryCatch() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/SafetyCheckerTryCatchOwner";
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sampleTry", "()Ljava/lang/Class;", null, null);
        org.objectweb.asm.tree.LabelNode start = new org.objectweb.asm.tree.LabelNode();
        org.objectweb.asm.tree.LabelNode end = new org.objectweb.asm.tree.LabelNode();
        org.objectweb.asm.tree.LabelNode handler = new org.objectweb.asm.tree.LabelNode();
        methodNode.instructions.add(start);
        methodNode.instructions.add(new LdcInsnNode(Type.getObjectType("nk/test/sample/SampleType")));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));
        methodNode.instructions.add(end);
        methodNode.instructions.add(handler);
        methodNode.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));
        methodNode.tryCatchBlocks = new ArrayList<>();
        methodNode.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        methodNode.maxStack = 1;
        methodNode.maxLocals = 0;
        classNode.methods.add(methodNode);

        L1Class owner = new L1Class(classNode);
        L1Method method = owner.findMethod("sampleTry", "()Ljava/lang/Class;");
        List<String> reasons = new ArrayList<>();
        assertTrue(checker.isSafe(method, reasons), () -> String.join("; ", reasons));
    }

    @Test
    void rejectsLdcPrimitiveClassReferenceReturn() {
        L1Method method = method("samplePrimitive", "()Ljava/lang/Class;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new LdcInsnNode(Type.INT_TYPE));
            insns.add(new InsnNode(Opcodes.ARETURN));
        }, 1, 0);

        List<String> reasons = new ArrayList<>();
        assertFalse(checker.isSafe(method, reasons));
        assertTrue(reasons.contains("unsupported LDC Type sort: 5"), () -> String.join("; ", reasons));
    }

    @Test
    void rejectsLdcMethodHandleReferenceReturnFromNonDirectProducer() {
        Handle handle = new Handle(Opcodes.H_INVOKESTATIC, "pkg/HandleOwner", "call", "()V", false);
        L1Method method = method("sampleHandle", "()Ljava/lang/Object;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new LdcInsnNode(handle));
            insns.add(new InsnNode(Opcodes.ARETURN));
        }, 1, 0);

        List<String> reasons = new ArrayList<>();
        assertFalse(checker.isSafe(method, reasons));
        assertTrue(reasons.contains("reference return requires direct ALOAD/ACONST_NULL producer"), () -> String.join("; ", reasons));
    }

    @Test
    void rejectsMonitorEnterUntilM5kStrictNoJniRuntimeExists() {
        L1Method method = method("sampleMonitor", "(Ljava/lang/Object;)V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
            insns.add(new InsnNode(Opcodes.MONITORENTER));
            insns.add(new InsnNode(Opcodes.RETURN));
        }, 1, 1);

        List<String> reasons = new ArrayList<>();
        assertFalse(checker.isSafe(method, reasons));
        assertTrue(reasons.stream().anyMatch(reason -> reason.contains("M5f deferred to M5k/W12")), () -> String.join("; ", reasons));
    }

    @Test
    void rejectsInvokeDynamicUntilBootstrapOnlyStrictNoJniRuntimeExists() {
        Handle bootstrap = new Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false
        );
        L1Method method = method("sampleIndy", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, insns -> {
            insns.add(new InvokeDynamicInsnNode("run", "()Ljava/lang/Runnable;", bootstrap));
            insns.add(new InsnNode(Opcodes.POP));
            insns.add(new InsnNode(Opcodes.RETURN));
        }, 1, 0);

        List<String> reasons = new ArrayList<>();
        assertFalse(checker.isSafe(method, reasons));
        assertTrue(reasons.stream().anyMatch(reason -> reason.contains("M5f deferred to M5k/W12")), () -> String.join("; ", reasons));
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
