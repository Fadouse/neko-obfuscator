package dev.nekoobfuscator.transforms.flow;

import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Exception-Return Obfuscation: wraps method body in try/catch, replaces return
 * with storing value and throwing a sentinel exception. The catch block retrieves
 * the stored value and returns it.
 */
public final class ExceptionReturnPass implements TransformPass {
    private static final String RETURN_EXCEPTION_CLASS = "dev/nekoobfuscator/runtime/NekoReturnFlowException";
    private static final String RETURN_EXCEPTION_INJECTED_KEY = "exceptionReturnClassInjected";

    @Override public String id() { return "exceptionReturn"; }
    @Override public String name() { return "Exception-Return Obfuscation"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() { return Set.of("exceptionObfuscation"); }

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        if (pctx.getPassData(RETURN_EXCEPTION_INJECTED_KEY) == null) {
            injectExceptionClass(pctx);
            pctx.putPassData(RETURN_EXCEPTION_INJECTED_KEY, Boolean.TRUE);
        }
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        if (!method.hasCode() || method.isConstructor() || method.isClassInit()) return;

        double intensity = pctx.config().getTransformIntensity("exceptionReturn");
        if (pctx.random().nextDouble() > intensity) return;

        Type returnType = method.returnType();
        if (returnType.getSort() == Type.VOID) return;

        MethodNode mn = method.asmNode();
        InsnList insns = mn.instructions;

        int retValLocal = mn.maxLocals;
        mn.maxLocals += returnType.getSize();

        // Find all return instructions
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) {
                returns.add(insn);
            }
        }
        if (returns.isEmpty()) return;

        if (mn.tryCatchBlocks == null) mn.tryCatchBlocks = new ArrayList<>();

        // Replace each return with a tiny local throw/catch island.
        for (AbstractInsnNode ret : returns) {
            InsnList replacement = new InsnList();
            LabelNode tryStart = new LabelNode();
            LabelNode tryEnd = new LabelNode();
            LabelNode handlerStart = new LabelNode();

            replacement.add(tryStart);
            replacement.add(new VarInsnNode(storeOpcode(returnType), retValLocal));
            replacement.add(new TypeInsnNode(Opcodes.NEW, RETURN_EXCEPTION_CLASS));
            replacement.add(new InsnNode(Opcodes.DUP));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, RETURN_EXCEPTION_CLASS, "<init>", "()V", false));
            replacement.add(new InsnNode(Opcodes.ATHROW));
            replacement.add(tryEnd);
            replacement.add(handlerStart);
            replacement.add(new InsnNode(Opcodes.POP));
            replacement.add(new VarInsnNode(loadOpcode(returnType), retValLocal));
            replacement.add(new InsnNode(returnOpcode(returnType)));

            insns.insertBefore(ret, replacement);
            insns.remove(ret);

            mn.tryCatchBlocks.add(0, new TryCatchBlockNode(tryStart, tryEnd, handlerStart, RETURN_EXCEPTION_CLASS));
        }

        mn.maxStack = Math.max(mn.maxStack, 3);
        pctx.currentL1Class().markDirty();
    }

    private void injectExceptionClass(PipelineContext ctx) {
        if (ctx.classMap().containsKey(RETURN_EXCEPTION_CLASS)) return;

        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC;
        cn.name = RETURN_EXCEPTION_CLASS;
        cn.superName = "java/lang/RuntimeException";
        cn.interfaces = List.of();

        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions = new InsnList();
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            "java/lang/RuntimeException", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1;
        init.maxLocals = 1;
        cn.methods.add(init);

        MethodNode fillIn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED,
            "fillInStackTrace", "()Ljava/lang/Throwable;", null, null);
        fillIn.instructions = new InsnList();
        fillIn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fillIn.instructions.add(new InsnNode(Opcodes.ARETURN));
        fillIn.maxStack = 1;
        fillIn.maxLocals = 1;
        cn.methods.add(fillIn);

        ctx.classMap().put(RETURN_EXCEPTION_CLASS, new L1Class(cn));
    }

    private int storeOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> Opcodes.ISTORE;
            case Type.LONG -> Opcodes.LSTORE;
            case Type.FLOAT -> Opcodes.FSTORE;
            case Type.DOUBLE -> Opcodes.DSTORE;
            default -> Opcodes.ASTORE;
        };
    }

    private int loadOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> Opcodes.ILOAD;
            case Type.LONG -> Opcodes.LLOAD;
            case Type.FLOAT -> Opcodes.FLOAD;
            case Type.DOUBLE -> Opcodes.DLOAD;
            default -> Opcodes.ALOAD;
        };
    }

    private int returnOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> Opcodes.IRETURN;
            case Type.LONG -> Opcodes.LRETURN;
            case Type.FLOAT -> Opcodes.FRETURN;
            case Type.DOUBLE -> Opcodes.DRETURN;
            default -> Opcodes.ARETURN;
        };
    }
}
