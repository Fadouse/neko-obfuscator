package dev.nekoobfuscator.transforms.advanced;

import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.core.util.AsmUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Advanced JVM obfuscation techniques:
 * - Dead code insertion with valid but confusing bytecode sequences
 * - Overlapping exception handlers
 * - Synthetic debug attributes that mislead
 * - Switch obfuscation (if-else to switch and vice versa)
 */
public final class AdvancedJvmPass implements TransformPass {

    @Override public String id() { return "advancedJvm"; }
    @Override public String name() { return "Advanced JVM Obfuscation"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() {
        return Set.of("controlFlowFlattening", "stringEncryption", "invokeDynamic",
                       "outliner", "stackObfuscation");
    }

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Class clazz = pctx.currentL1Class();
        ClassNode cn = clazz.asmNode();

        // Obfuscate source file name
        cn.sourceFile = generateFakeSourceName(pctx);

        // Add synthetic attributes
        if (cn.attrs == null) cn.attrs = new ArrayList<>();
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        if (!method.hasCode() || method.isConstructor() || method.isClassInit()) return;

        MethodNode mn = method.asmNode();

        // Insert dead code blocks
        insertDeadCode(mn, pctx);

        // Add overlapping exception handlers
        addOverlappingHandlers(mn, pctx);

        // Obfuscate local variable table
        obfuscateLocalVariables(mn, pctx);

        pctx.currentL1Class().markDirty();
    }

    private void insertDeadCode(MethodNode mn, PipelineContext pctx) {
        InsnList insns = mn.instructions;
        // Find GOTO instructions and add dead code after them
        List<AbstractInsnNode> gotos = new ArrayList<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.GOTO) gotos.add(insn);
        }

        for (AbstractInsnNode gotoInsn : gotos) {
            if (pctx.random().nextDouble() > 0.5) continue;

            // Insert unreachable but valid bytecode after GOTO
            InsnList dead = new InsnList();
            int pattern = pctx.random().nextInt(3);
            switch (pattern) {
                case 0 -> {
                    dead.add(new InsnNode(Opcodes.ACONST_NULL));
                    dead.add(new InsnNode(Opcodes.ATHROW));
                }
                case 1 -> {
                    dead.add(new InsnNode(Opcodes.ICONST_0));
                    dead.add(new InsnNode(Opcodes.IRETURN));
                }
                case 2 -> {
                    dead.add(new LdcInsnNode("dead"));
                    dead.add(new InsnNode(Opcodes.POP));
                    dead.add(new InsnNode(Opcodes.RETURN));
                }
            }

            AbstractInsnNode next = gotoInsn.getNext();
            if (next != null) {
                insns.insertBefore(next, dead);
            }
        }
    }

    private void addOverlappingHandlers(MethodNode mn, PipelineContext pctx) {
        if (mn.tryCatchBlocks == null || mn.tryCatchBlocks.isEmpty()) return;
        if (pctx.random().nextDouble() > 0.3) return;

        // Add a duplicate exception handler for the same range
        // This creates overlapping handlers that confuse decompilers
        InsnList insns = mn.instructions;
        LabelNode handlerLabel = new LabelNode();

        // Find first real instruction for try range
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        insns.insertBefore(insns.getFirst(), tryStart);
        insns.add(tryEnd);

        // Handler that catches RuntimeException and re-throws
        InsnList handler = new InsnList();
        handler.add(handlerLabel);
        handler.add(new InsnNode(Opcodes.ATHROW)); // re-throw
        insns.add(handler);

        mn.tryCatchBlocks.add(new TryCatchBlockNode(
            tryStart, tryEnd, handlerLabel, "java/lang/VirtualMachineError"));
    }

    private void obfuscateLocalVariables(MethodNode mn, PipelineContext pctx) {
        // Generate misleading local variable table
        if (mn.localVariables != null) {
            for (LocalVariableNode lvn : mn.localVariables) {
                // Randomize variable names
                lvn.name = generateFakeVarName(pctx);
            }
        }
    }

    private String generateFakeSourceName(PipelineContext pctx) {
        String[] fakes = {"", "\u0000", "SourceFile", "a.java", "\u200b.java", "NativeMethod"};
        return fakes[pctx.random().nextInt(fakes.length)];
    }

    private String generateFakeVarName(PipelineContext pctx) {
        String[] prefixes = {"\u200b", "\u00a0", "Il", "O0", "lI", "I1"};
        return prefixes[pctx.random().nextInt(prefixes.length)] + pctx.random().nextInt(100);
    }
}
