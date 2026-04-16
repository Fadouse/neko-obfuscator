package dev.nekoobfuscator.core.ir.lift;

import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.ir.l2.*;
import dev.nekoobfuscator.core.util.AsmUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Lowers L2 IR (CFG) back to L1 IR (ASM bytecode).
 * Performs:
 *   1. Block linearization (reverse post-order)
 *   2. Instruction list reconstruction
 *   3. Jump target patching
 *   4. Try-catch block reconstruction
 */
public final class L2ToL1Lowerer {

    /**
     * Lower a CFG back to bytecode in the target method.
     */
    public void lower(ControlFlowGraph cfg, L1Method target) {
        MethodNode mn = target.asmNode();
        InsnList newInsns = new InsnList();

        // Linearize blocks in reverse post-order (good for fall-through optimization)
        List<BasicBlock> order = cfg.reversePostOrder();

        // Assign labels to each block (for jump targeting)
        Map<BasicBlock, LabelNode> blockLabels = new HashMap<>();
        for (BasicBlock block : order) {
            blockLabels.put(block, new LabelNode());
        }

        // Emit instructions
        for (int i = 0; i < order.size(); i++) {
            BasicBlock block = order.get(i);
            BasicBlock nextBlock = (i + 1 < order.size()) ? order.get(i + 1) : null;

            // Emit block label
            newInsns.add(blockLabels.get(block));

            // Emit block instructions (skipping original labels)
            for (AbstractInsnNode insn : block.instructions()) {
                if (insn instanceof LabelNode) continue; // use our generated labels
                if (insn instanceof FrameNode) continue;  // will be recomputed
                if (insn instanceof LineNumberNode) continue; // skip for now

                // Patch jump targets
                if (insn instanceof JumpInsnNode jump) {
                    BasicBlock jumpTarget = findTargetBlock(cfg, jump.label);
                    if (jumpTarget != null) {
                        LabelNode newLabel = blockLabels.get(jumpTarget);
                        if (newLabel != null) {
                            // Check if jump is to the immediate next block (can be eliminated for GOTO)
                            if (jump.getOpcode() == Opcodes.GOTO && jumpTarget == nextBlock) {
                                continue; // skip redundant goto
                            }
                            newInsns.add(new JumpInsnNode(jump.getOpcode(), newLabel));
                            continue;
                        }
                    }
                } else if (insn instanceof TableSwitchInsnNode ts) {
                    LabelNode dflt = remapSwitchLabel(ts.dflt, cfg, blockLabels);
                    List<LabelNode> labels = new ArrayList<>();
                    for (LabelNode l : ts.labels) {
                        labels.add(remapSwitchLabel(l, cfg, blockLabels));
                    }
                    newInsns.add(new TableSwitchInsnNode(ts.min, ts.max, dflt, labels.toArray(new LabelNode[0])));
                    continue;
                } else if (insn instanceof LookupSwitchInsnNode ls) {
                    LabelNode dflt = remapSwitchLabel(ls.dflt, cfg, blockLabels);
                    List<LabelNode> labels = new ArrayList<>();
                    for (LabelNode l : ls.labels) {
                        labels.add(remapSwitchLabel(l, cfg, blockLabels));
                    }
                    newInsns.add(new LookupSwitchInsnNode(dflt, ls.keys.stream().mapToInt(Integer::intValue).toArray(),
                        labels.toArray(new LabelNode[0])));
                    continue;
                }

                // Clone the instruction (can't add same node to two lists)
                newInsns.add(insn.clone(Map.of()));
            }

            // If block doesn't end with a terminator and there's a next block, add implicit goto if needed
            AbstractInsnNode lastReal = findLastReal(block);
            if (lastReal != null && !AsmUtil.isTerminator(lastReal) && !AsmUtil.isReturn(lastReal.getOpcode())) {
                // Check if it falls through to next block
                List<BasicBlock> succs = block.successors();
                if (succs.size() == 1 && succs.get(0) != nextBlock) {
                    LabelNode target2 = blockLabels.get(succs.get(0));
                    if (target2 != null) {
                        newInsns.add(new JumpInsnNode(Opcodes.GOTO, target2));
                    }
                }
            }
        }

        // Replace method's instruction list
        mn.instructions = newInsns;

        // Reconstruct try-catch blocks with new labels
        rebuildTryCatchBlocks(cfg, mn, blockLabels);

        // Clear old local variables and frames (will be recomputed by ClassWriter.COMPUTE_FRAMES)
        mn.localVariables = null;
        mn.maxStack = 0;
        mn.maxLocals = 0;
        // These will be recomputed by ClassWriter with COMPUTE_FRAMES flag

        target.owner().markDirty();
    }

    private BasicBlock findTargetBlock(ControlFlowGraph cfg, LabelNode label) {
        for (BasicBlock b : cfg.blocks()) {
            for (AbstractInsnNode insn : b.instructions()) {
                if (insn == label) return b;
            }
        }
        return null;
    }

    private LabelNode remapSwitchLabel(LabelNode original, ControlFlowGraph cfg,
                                        Map<BasicBlock, LabelNode> blockLabels) {
        BasicBlock target = findTargetBlock(cfg, original);
        if (target != null && blockLabels.containsKey(target)) {
            return blockLabels.get(target);
        }
        return original;
    }

    private AbstractInsnNode findLastReal(BasicBlock block) {
        for (int i = block.instructions().size() - 1; i >= 0; i--) {
            AbstractInsnNode insn = block.instructions().get(i);
            if (AsmUtil.isRealInstruction(insn)) return insn;
        }
        return null;
    }

    private void rebuildTryCatchBlocks(ControlFlowGraph cfg, MethodNode mn,
                                        Map<BasicBlock, LabelNode> blockLabels) {
        // For now, preserve original try-catch blocks if possible
        // Full reconstruction from exception edges will be implemented in Phase 4
        // when exception obfuscation needs it
        if (mn.tryCatchBlocks != null) {
            // Try to remap handler labels
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                BasicBlock handlerBlock = findTargetBlock(cfg, tcb.handler);
                if (handlerBlock != null && blockLabels.containsKey(handlerBlock)) {
                    tcb.handler = blockLabels.get(handlerBlock);
                }
                BasicBlock startBlock = findTargetBlock(cfg, tcb.start);
                if (startBlock != null && blockLabels.containsKey(startBlock)) {
                    tcb.start = blockLabels.get(startBlock);
                }
                BasicBlock endBlock = findTargetBlock(cfg, tcb.end);
                if (endBlock != null && blockLabels.containsKey(endBlock)) {
                    tcb.end = blockLabels.get(endBlock);
                }
            }
        }
    }
}
