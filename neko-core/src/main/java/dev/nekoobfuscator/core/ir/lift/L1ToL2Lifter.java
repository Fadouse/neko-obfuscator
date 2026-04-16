package dev.nekoobfuscator.core.ir.lift;

import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.ir.l2.*;

/**
 * Lifts L1 IR (ASM bytecode) to L2 IR (CFG + SSA form).
 */
public final class L1ToL2Lifter {

    /**
     * Build a Control Flow Graph from a method's bytecode.
     */
    public ControlFlowGraph buildCFG(L1Method method) {
        return ControlFlowGraph.build(method);
    }

    /**
     * Lift a method to full SSA form.
     * Steps:
     *   1. Build CFG
     *   2. Compute dominator tree
     *   3. Compute dominance frontiers
     *   4. Place phi functions (Cytron et al.)
     *   5. Rename variables
     */
    public SSAForm lift(L1Method method) {
        ControlFlowGraph cfg = buildCFG(method);
        return SSAForm.construct(cfg);
    }
}
