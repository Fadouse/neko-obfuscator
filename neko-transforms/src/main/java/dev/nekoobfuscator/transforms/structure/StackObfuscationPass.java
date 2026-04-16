package dev.nekoobfuscator.transforms.structure;

import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.core.util.AsmUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Stack Obfuscation: inserts redundant stack operations that cancel out,
 * making decompiled code harder to read.
 */
public final class StackObfuscationPass implements TransformPass {

    @Override public String id() { return "stackObfuscation"; }
    @Override public String name() { return "Stack Obfuscation"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() { return Set.of("outliner"); }

    @Override
    public void transformClass(TransformContext ctx) {}

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        if (!method.hasCode()) return;

        double intensity = pctx.config().getTransformIntensity("stackObfuscation");
        InsnList insns = method.instructions();
        List<AbstractInsnNode> insertionPoints = new ArrayList<>();

        // Find safe insertion points (between instructions where stack is known)
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (!AsmUtil.isRealInstruction(insn)) continue;
            // Insert before load/store instructions (stack is at known depth)
            if (AsmUtil.isLoad(insn.getOpcode()) || AsmUtil.isStore(insn.getOpcode())) {
                if (pctx.random().nextDouble() <= intensity * 0.3) {
                    insertionPoints.add(insn);
                }
            }
        }

        if (insertionPoints.isEmpty()) return;

        for (AbstractInsnNode point : insertionPoints) {
            InsnList junk = generateStackJunk(pctx);
            insns.insertBefore(point, junk);
        }

        method.asmNode().maxStack = Math.max(method.asmNode().maxStack,
            method.asmNode().maxStack + 2);
        pctx.currentL1Class().markDirty();
    }

    /**
     * Generate stack operations that have no net effect.
     */
    private InsnList generateStackJunk(PipelineContext pctx) {
        InsnList insns = new InsnList();
        int pattern = pctx.random().nextInt(4);
        switch (pattern) {
            case 0 -> {
                // push constant, pop (no-op)
                insns.add(new LdcInsnNode(pctx.random().nextInt()));
                insns.add(new InsnNode(Opcodes.POP));
            }
            case 1 -> {
                // push null, pop
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new InsnNode(Opcodes.POP));
            }
            case 2 -> {
                // push two ints, add, pop
                insns.add(new LdcInsnNode(pctx.random().nextInt()));
                insns.add(new LdcInsnNode(pctx.random().nextInt()));
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new InsnNode(Opcodes.POP));
            }
            case 3 -> {
                // push long, pop2
                insns.add(new LdcInsnNode(pctx.random().nextLong()));
                insns.add(new InsnNode(Opcodes.POP2));
            }
        }
        return insns;
    }
}
