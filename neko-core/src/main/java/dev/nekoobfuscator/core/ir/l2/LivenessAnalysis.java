package dev.nekoobfuscator.core.ir.l2;

import dev.nekoobfuscator.core.util.AsmUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Live variable analysis on the CFG.
 * Computes which local variables are live (will be used before being redefined) at each block.
 */
public final class LivenessAnalysis {
    private final Map<BasicBlock, Set<Integer>> liveIn = new HashMap<>();
    private final Map<BasicBlock, Set<Integer>> liveOut = new HashMap<>();

    private LivenessAnalysis() {}

    public Set<Integer> liveIn(BasicBlock block) {
        return liveIn.getOrDefault(block, Set.of());
    }

    public Set<Integer> liveOut(BasicBlock block) {
        return liveOut.getOrDefault(block, Set.of());
    }

    /**
     * Compute liveness using iterative backward data flow analysis.
     */
    public static LivenessAnalysis compute(ControlFlowGraph cfg) {
        LivenessAnalysis analysis = new LivenessAnalysis();

        // Initialize
        for (BasicBlock b : cfg.blocks()) {
            analysis.liveIn.put(b, new HashSet<>());
            analysis.liveOut.put(b, new HashSet<>());
        }

        // Compute gen (used before defined) and kill (defined) sets for each block
        Map<BasicBlock, Set<Integer>> gen = new HashMap<>();
        Map<BasicBlock, Set<Integer>> kill = new HashMap<>();
        for (BasicBlock b : cfg.blocks()) {
            Set<Integer> g = new HashSet<>();
            Set<Integer> k = new HashSet<>();
            for (AbstractInsnNode insn : b.instructions()) {
                if (!AsmUtil.isRealInstruction(insn)) continue;
                int opcode = insn.getOpcode();

                // Uses (gen)
                if (insn instanceof VarInsnNode var && AsmUtil.isLoad(opcode)) {
                    if (!k.contains(var.var)) g.add(var.var);
                } else if (insn instanceof IincInsnNode iinc) {
                    if (!k.contains(iinc.var)) g.add(iinc.var);
                }

                // Defs (kill)
                if (insn instanceof VarInsnNode var && AsmUtil.isStore(opcode)) {
                    k.add(var.var);
                } else if (insn instanceof IincInsnNode iinc) {
                    k.add(iinc.var);
                }
            }
            gen.put(b, g);
            kill.put(b, k);
        }

        // Iterative backward dataflow
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BasicBlock b : cfg.blocks()) {
                // liveOut = union of liveIn of all successors
                Set<Integer> newOut = new HashSet<>();
                for (BasicBlock succ : b.successors()) {
                    newOut.addAll(analysis.liveIn.get(succ));
                }

                // liveIn = gen union (liveOut - kill)
                Set<Integer> newIn = new HashSet<>(gen.get(b));
                Set<Integer> diff = new HashSet<>(newOut);
                diff.removeAll(kill.get(b));
                newIn.addAll(diff);

                if (!newIn.equals(analysis.liveIn.get(b)) || !newOut.equals(analysis.liveOut.get(b))) {
                    analysis.liveIn.put(b, newIn);
                    analysis.liveOut.put(b, newOut);
                    changed = true;
                }
            }
        }

        return analysis;
    }
}
