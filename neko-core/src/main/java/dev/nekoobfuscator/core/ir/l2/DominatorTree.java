package dev.nekoobfuscator.core.ir.l2;

import java.util.*;

/**
 * Dominator tree computed using the Cooper-Harvey-Kennedy iterative algorithm.
 * "A Simple, Fast Dominance Algorithm" (Cooper, Harvey, Kennedy, 2001).
 */
public final class DominatorTree {
    private final Map<BasicBlock, BasicBlock> idom; // immediate dominator
    private final Map<BasicBlock, List<BasicBlock>> children; // dominator tree children
    private final List<BasicBlock> rpo; // reverse post-order numbering

    private DominatorTree(Map<BasicBlock, BasicBlock> idom, List<BasicBlock> rpo) {
        this.idom = idom;
        this.rpo = rpo;
        this.children = new HashMap<>();
        // Build children map (skip self-loops: entry.idom = entry)
        for (var entry : idom.entrySet()) {
            if (entry.getValue() != null && !entry.getKey().equals(entry.getValue())) {
                children.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
            }
        }
    }

    public BasicBlock idom(BasicBlock block) {
        return idom.get(block);
    }

    public List<BasicBlock> children(BasicBlock block) {
        return children.getOrDefault(block, List.of());
    }

    public boolean dominates(BasicBlock a, BasicBlock b) {
        BasicBlock current = b;
        while (current != null) {
            if (current.equals(a)) return true;
            current = idom.get(current);
        }
        return false;
    }

    public static DominatorTree compute(ControlFlowGraph cfg) {
        List<BasicBlock> rpo = cfg.reversePostOrder();
        Map<BasicBlock, Integer> rpoNumber = new HashMap<>();
        for (int i = 0; i < rpo.size(); i++) {
            rpoNumber.put(rpo.get(i), i);
        }

        BasicBlock entry = cfg.entryBlock();
        Map<BasicBlock, BasicBlock> idom = new HashMap<>();
        idom.put(entry, entry);

        boolean changed = true;
        while (changed) {
            changed = false;
            for (BasicBlock b : rpo) {
                if (b.equals(entry)) continue;

                BasicBlock newIdom = null;
                for (BasicBlock pred : b.predecessors()) {
                    if (!idom.containsKey(pred)) continue;
                    if (newIdom == null) {
                        newIdom = pred;
                    } else {
                        newIdom = intersect(pred, newIdom, idom, rpoNumber);
                    }
                }

                if (newIdom != null && !newIdom.equals(idom.get(b))) {
                    idom.put(b, newIdom);
                    changed = true;
                }
            }
        }

        return new DominatorTree(idom, rpo);
    }

    private static BasicBlock intersect(BasicBlock b1, BasicBlock b2,
            Map<BasicBlock, BasicBlock> idom, Map<BasicBlock, Integer> rpoNumber) {
        BasicBlock finger1 = b1;
        BasicBlock finger2 = b2;
        while (!finger1.equals(finger2)) {
            while (rpoNumber.getOrDefault(finger1, Integer.MAX_VALUE) > rpoNumber.getOrDefault(finger2, Integer.MAX_VALUE)) {
                finger1 = idom.get(finger1);
                if (finger1 == null) return b2;
            }
            while (rpoNumber.getOrDefault(finger2, Integer.MAX_VALUE) > rpoNumber.getOrDefault(finger1, Integer.MAX_VALUE)) {
                finger2 = idom.get(finger2);
                if (finger2 == null) return b1;
            }
        }
        return finger1;
    }

    /**
     * Compute the dominance frontier for each block.
     * DF(X) = set of blocks where X's dominance ends.
     */
    public Map<BasicBlock, Set<BasicBlock>> computeDominanceFrontiers(ControlFlowGraph cfg) {
        Map<BasicBlock, Set<BasicBlock>> df = new HashMap<>();
        for (BasicBlock b : cfg.blocks()) {
            df.put(b, new HashSet<>());
        }

        for (BasicBlock b : cfg.blocks()) {
            List<BasicBlock> preds = b.predecessors();
            if (preds.size() >= 2) {
                for (BasicBlock pred : preds) {
                    BasicBlock runner = pred;
                    while (runner != null && !runner.equals(idom.get(b))) {
                        df.get(runner).add(b);
                        runner = idom.get(runner);
                    }
                }
            }
        }

        return df;
    }

    /**
     * Compute the iterated dominance frontier for a set of blocks.
     * Used for SSA phi placement.
     */
    public Set<BasicBlock> iteratedDominanceFrontier(Set<BasicBlock> blocks, Map<BasicBlock, Set<BasicBlock>> df) {
        Set<BasicBlock> result = new HashSet<>();
        Queue<BasicBlock> worklist = new LinkedList<>(blocks);
        Set<BasicBlock> processed = new HashSet<>(blocks);

        while (!worklist.isEmpty()) {
            BasicBlock b = worklist.poll();
            for (BasicBlock d : df.getOrDefault(b, Set.of())) {
                if (result.add(d)) {
                    if (processed.add(d)) {
                        worklist.add(d);
                    }
                }
            }
        }

        return result;
    }
}
