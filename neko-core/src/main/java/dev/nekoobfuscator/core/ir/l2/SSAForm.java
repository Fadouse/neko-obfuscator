package dev.nekoobfuscator.core.ir.l2;

import dev.nekoobfuscator.core.util.AsmUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * SSA (Static Single Assignment) form for a method.
 * Constructed using Cytron et al. algorithm (1991):
 *   Phase 1: Phi placement using iterated dominance frontiers
 *   Phase 2: Variable renaming via dominator tree walk
 */
public final class SSAForm {
    private final ControlFlowGraph cfg;
    private final Map<Integer, List<SSAValue>> localVersions = new HashMap<>();
    private final Map<BasicBlock, List<PhiNode>> blockPhis = new HashMap<>();
    private final DefUseChain defUse = new DefUseChain();
    private int versionCounter = 0;

    private SSAForm(ControlFlowGraph cfg) {
        this.cfg = cfg;
    }

    public ControlFlowGraph cfg() { return cfg; }
    public DefUseChain defUseChain() { return defUse; }

    public List<PhiNode> getPhis(BasicBlock block) {
        return blockPhis.getOrDefault(block, List.of());
    }

    public SSAValue getVersion(int local, int version) {
        List<SSAValue> versions = localVersions.get(local);
        if (versions != null) {
            for (SSAValue v : versions) {
                if (v.version() == version) return v;
            }
        }
        return null;
    }

    /**
     * Construct SSA form from a control flow graph.
     */
    public static SSAForm construct(ControlFlowGraph cfg) {
        SSAForm ssa = new SSAForm(cfg);
        DominatorTree domTree = cfg.dominatorTree();
        Map<BasicBlock, Set<BasicBlock>> df = domTree.computeDominanceFrontiers(cfg);

        // Find all local variable definitions per block
        Map<Integer, Set<BasicBlock>> defSites = findDefSites(cfg);

        // Phase 1: Place phi functions
        for (var entry : defSites.entrySet()) {
            int localIdx = entry.getKey();
            Set<BasicBlock> idf = domTree.iteratedDominanceFrontier(entry.getValue(), df);

            for (BasicBlock block : idf) {
                // Only place phi if variable is live at this block
                SSAValue phiResult = ssa.newVersion(localIdx);
                PhiNode phi = new PhiNode(phiResult, localIdx, block);
                ssa.blockPhis.computeIfAbsent(block, k -> new ArrayList<>()).add(phi);
            }
        }

        // Phase 2: Rename variables (walk dominator tree)
        Map<Integer, Deque<SSAValue>> stacks = new HashMap<>();
        ssa.rename(cfg.entryBlock(), domTree, stacks);

        return ssa;
    }

    private static Map<Integer, Set<BasicBlock>> findDefSites(ControlFlowGraph cfg) {
        Map<Integer, Set<BasicBlock>> defs = new HashMap<>();
        for (BasicBlock block : cfg.blocks()) {
            for (AbstractInsnNode insn : block.instructions()) {
                if (!AsmUtil.isRealInstruction(insn)) continue;
                if (insn instanceof VarInsnNode var && AsmUtil.isStore(var.getOpcode())) {
                    defs.computeIfAbsent(var.var, k -> new HashSet<>()).add(block);
                } else if (insn instanceof IincInsnNode iinc) {
                    defs.computeIfAbsent(iinc.var, k -> new HashSet<>()).add(block);
                }
            }
        }
        return defs;
    }

    private record RenameFrame(BasicBlock block, List<BasicBlock> children, int childIdx, Map<Integer, Integer> pushCounts) {}

    /**
     * Iterative rename using explicit work stack to avoid StackOverflowError on deep CFGs.
     */
    private void rename(BasicBlock entry, DominatorTree domTree, Map<Integer, Deque<SSAValue>> stacks) {
        Deque<RenameFrame> workStack = new ArrayDeque<>();
        workStack.push(processBlock(entry, domTree, stacks));

        while (!workStack.isEmpty()) {
            RenameFrame frame = workStack.peek();
            if (frame.childIdx() < frame.children().size()) {
                BasicBlock child = frame.children().get(frame.childIdx());
                workStack.pop();
                workStack.push(new RenameFrame(frame.block(), frame.children(), frame.childIdx() + 1, frame.pushCounts()));
                workStack.push(processBlock(child, domTree, stacks));
            } else {
                workStack.pop();
                for (var e : frame.pushCounts().entrySet()) {
                    Deque<SSAValue> stack = stacks.get(e.getKey());
                    for (int i = 0; i < e.getValue(); i++) {
                        if (stack != null && !stack.isEmpty()) stack.pop();
                    }
                }
            }
        }
    }

    private RenameFrame processBlock(BasicBlock block, DominatorTree domTree, Map<Integer, Deque<SSAValue>> stacks) {
        Map<Integer, Integer> pushCounts = new HashMap<>();

        for (PhiNode phi : getPhis(block)) {
            int local = phi.localIndex();
            pushStack(stacks, local, phi.result());
            pushCounts.merge(local, 1, Integer::sum);
        }

        for (AbstractInsnNode insn : block.instructions()) {
            if (!AsmUtil.isRealInstruction(insn)) continue;
            if (insn instanceof VarInsnNode var && AsmUtil.isLoad(var.getOpcode())) {
                SSAValue current = peekStack(stacks, var.var);
                if (current != null) { current.addUseSite(insn); defUse.addUse(insn, current); }
            } else if (insn instanceof IincInsnNode iinc) {
                SSAValue current = peekStack(stacks, iinc.var);
                if (current != null) { current.addUseSite(insn); defUse.addUse(insn, current); }
            }
            if (insn instanceof VarInsnNode var && AsmUtil.isStore(var.getOpcode())) {
                SSAValue newVal = newVersion(var.var); newVal.setDefSite(insn);
                pushStack(stacks, var.var, newVal); pushCounts.merge(var.var, 1, Integer::sum);
                defUse.addDef(insn, newVal);
            } else if (insn instanceof IincInsnNode iinc) {
                SSAValue newVal = newVersion(iinc.var); newVal.setDefSite(insn);
                pushStack(stacks, iinc.var, newVal); pushCounts.merge(iinc.var, 1, Integer::sum);
                defUse.addDef(insn, newVal);
            }
        }

        for (BasicBlock succ : block.successors()) {
            for (PhiNode phi : getPhis(succ)) {
                SSAValue current = peekStack(stacks, phi.localIndex());
                if (current != null) phi.setOperand(block, current);
            }
        }

        List<BasicBlock> children = domTree.children(block);
        return new RenameFrame(block, children, 0, pushCounts);
    }

    private SSAValue newVersion(int local) {
        SSAValue v = new SSAValue(local, versionCounter++);
        localVersions.computeIfAbsent(local, k -> new ArrayList<>()).add(v);
        return v;
    }

    private void pushStack(Map<Integer, Deque<SSAValue>> stacks, int local, SSAValue value) {
        stacks.computeIfAbsent(local, k -> new ArrayDeque<>()).push(value);
    }

    private SSAValue peekStack(Map<Integer, Deque<SSAValue>> stacks, int local) {
        Deque<SSAValue> stack = stacks.get(local);
        return (stack != null && !stack.isEmpty()) ? stack.peek() : null;
    }
}
