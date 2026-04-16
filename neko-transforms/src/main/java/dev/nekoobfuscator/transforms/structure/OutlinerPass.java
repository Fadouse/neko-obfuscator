package dev.nekoobfuscator.transforms.structure;

import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.ir.l2.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.core.util.AsmUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Outliner: extracts basic blocks into separate private static methods.
 * This splits the control flow across methods, making analysis harder.
 */
public final class OutlinerPass implements TransformPass {

    private static final int MIN_BLOCK_SIZE = 5;

    @Override public String id() { return "outliner"; }
    @Override public String name() { return "Outliner"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() { return Set.of("controlFlowFlattening"); }

    private int outlinedMethodCounter;

    @Override
    public void transformClass(TransformContext ctx) {
        outlinedMethodCounter = 0;
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();
        if (!method.hasCode() || method.isConstructor() || method.isClassInit()) return;

        double intensity = pctx.config().getTransformIntensity("outliner");
        if (pctx.random().nextDouble() > intensity) return;

        // Build CFG and find blocks suitable for outlining
        ControlFlowGraph cfg = pctx.getCFG(method);
        List<BasicBlock> candidates = new ArrayList<>();
        for (BasicBlock block : cfg.blocks()) {
            // Count real instructions
            int realInsns = 0;
            for (AbstractInsnNode insn : block.instructions()) {
                if (AsmUtil.isRealInstruction(insn)) realInsns++;
            }
            if (realInsns >= MIN_BLOCK_SIZE
                && !block.isExceptionHandler()
                && block.successors().size() <= 1) {
                candidates.add(block);
            }
        }

        if (candidates.isEmpty()) return;

        // Outline selected blocks
        int maxOutline = Math.min(candidates.size(), 3); // limit per method
        for (int i = 0; i < maxOutline; i++) {
            BasicBlock block = candidates.get(i);
            outlineBlock(clazz, method, block, pctx);
        }

        pctx.invalidate(method);
        clazz.markDirty();
    }

    private void outlineBlock(L1Class clazz, L1Method method, BasicBlock block, PipelineContext pctx) {
        MethodNode mn = method.asmNode();
        String outlinedName = "__neko_o" + (outlinedMethodCounter++);

        // Analyze which locals the block reads and writes
        Set<Integer> reads = new LinkedHashSet<>();
        Set<Integer> writes = new LinkedHashSet<>();
        analyzeLocals(block, reads, writes);

        // Build parameter list from reads
        List<Integer> paramLocals = new ArrayList<>(reads);

        // Build descriptor: all read locals become parameters, first written local becomes return
        StringBuilder descBuilder = new StringBuilder("(");
        for (int local : paramLocals) {
            descBuilder.append("I"); // simplified: treat all as int for now
        }
        descBuilder.append(")V"); // void return for simplicity
        String desc = descBuilder.toString();

        // Create the outlined method
        MethodNode outlined = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            outlinedName, desc, null, null);
        outlined.instructions = new InsnList();

        // Map original locals to parameter indices
        Map<Integer, Integer> localMap = new HashMap<>();
        int paramIdx = 0;
        for (int local : paramLocals) {
            localMap.put(local, paramIdx++);
        }

        // Copy block instructions, remapping local variable references
        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }
            if (AsmUtil.isTerminator(insn)) continue;

            AbstractInsnNode cloned = insn.clone(Map.of());
            if (cloned instanceof VarInsnNode var) {
                Integer mapped = localMap.get(var.var);
                if (mapped != null) var.var = mapped;
            } else if (cloned instanceof IincInsnNode iinc) {
                Integer mapped = localMap.get(iinc.var);
                if (mapped != null) iinc.var = mapped;
            }
            outlined.instructions.add(cloned);
        }
        outlined.instructions.add(new InsnNode(Opcodes.RETURN));
        outlined.maxStack = mn.maxStack;
        outlined.maxLocals = paramLocals.size();

        clazz.asmNode().methods.add(outlined);

        // Replace block instructions with a call to the outlined method
        InsnList call = new InsnList();
        for (int local : paramLocals) {
            call.add(new VarInsnNode(Opcodes.ILOAD, local));
        }
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, clazz.name(), outlinedName, desc, false));

        // Replace block content in original method
        InsnList insns = mn.instructions;
        List<AbstractInsnNode> toRemove = new ArrayList<>();
        boolean inBlock = false;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (block.instructions().contains(insn)) {
                if (!inBlock) {
                    // Insert call before first block instruction
                    insns.insertBefore(insn, call);
                    inBlock = true;
                }
                if (!AsmUtil.isTerminator(insn) && !(insn instanceof LabelNode)) {
                    toRemove.add(insn);
                }
            }
        }
        for (AbstractInsnNode insn : toRemove) {
            insns.remove(insn);
        }
    }

    private void analyzeLocals(BasicBlock block, Set<Integer> reads, Set<Integer> writes) {
        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof VarInsnNode var) {
                if (AsmUtil.isLoad(var.getOpcode())) {
                    reads.add(var.var);
                } else if (AsmUtil.isStore(var.getOpcode())) {
                    writes.add(var.var);
                }
            } else if (insn instanceof IincInsnNode iinc) {
                reads.add(iinc.var);
                writes.add(iinc.var);
            }
        }
    }
}
