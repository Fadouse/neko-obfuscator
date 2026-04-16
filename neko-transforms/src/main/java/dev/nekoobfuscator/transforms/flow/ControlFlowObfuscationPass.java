package dev.nekoobfuscator.transforms.flow;

import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Control Flow Obfuscation using opaque predicates.
 * Inserts bogus conditional branches with opaque predicates on unconditional edges,
 * creating dead code paths that confuse decompilers.
 */
public final class ControlFlowObfuscationPass implements TransformPass {

    @Override public String id() { return "opaquePredicates"; }
    @Override public String name() { return "Opaque Predicate Insertion"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() { return Set.of("controlFlowFlattening"); }

    @Override
    public void transformClass(TransformContext ctx) {}

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        if (!method.hasCode() || method.isConstructor() || method.isClassInit()) return;

        double intensity = pctx.config().getTransformIntensity("opaquePredicates");
        OpaquePredicateGenerator gen = new OpaquePredicateGenerator(pctx.random());
        InsnList insns = method.instructions();

        // Find GOTO instructions to replace with opaque predicated branches
        List<JumpInsnNode> gotos = new ArrayList<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.GOTO && insn instanceof JumpInsnNode jump) {
                if (pctx.random().nextDouble() <= intensity) {
                    gotos.add(jump);
                }
            }
        }

        if (gotos.isEmpty()) return;

        for (JumpInsnNode gotoInsn : gotos) {
            LabelNode realTarget = gotoInsn.label;
            LabelNode fakeTarget = new LabelNode();

            // Generate opaque predicate (always true)
            InsnList predicate = gen.generateAlwaysTrue();

            // Replace GOTO with: if (opaquePredicate) goto realTarget else goto fakeTarget
            InsnList replacement = new InsnList();
            replacement.add(predicate);
            replacement.add(new JumpInsnNode(Opcodes.IFNE, realTarget)); // if true -> real
            // Dead code (fake path) - add confusing but valid bytecode
            replacement.add(fakeTarget);
            replacement.add(generateBogusCode(pctx));
            replacement.add(new JumpInsnNode(Opcodes.GOTO, realTarget)); // safety fallback

            insns.insertBefore(gotoInsn, replacement);
            insns.remove(gotoInsn);
        }

        method.asmNode().maxStack = Math.max(method.asmNode().maxStack, 4);
        pctx.currentL1Class().markDirty();
    }

    private InsnList generateBogusCode(PipelineContext pctx) {
        InsnList bogus = new InsnList();
        // Generate dead code that looks plausible but is never executed
        int pattern = pctx.random().nextInt(3);
        switch (pattern) {
            case 0 -> {
                bogus.add(new InsnNode(Opcodes.ACONST_NULL));
                bogus.add(new InsnNode(Opcodes.POP));
            }
            case 1 -> {
                bogus.add(new LdcInsnNode(pctx.random().nextInt()));
                bogus.add(new InsnNode(Opcodes.POP));
            }
            case 2 -> {
                bogus.add(new InsnNode(Opcodes.ICONST_0));
                bogus.add(new InsnNode(Opcodes.ICONST_1));
                bogus.add(new InsnNode(Opcodes.IADD));
                bogus.add(new InsnNode(Opcodes.POP));
            }
        }
        return bogus;
    }
}
