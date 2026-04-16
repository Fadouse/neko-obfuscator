package dev.nekoobfuscator.core.ir.l2;

import org.objectweb.asm.tree.AbstractInsnNode;
import java.util.*;

/**
 * Def-use and use-def chain analysis for SSA values.
 */
public final class DefUseChain {
    private final Map<AbstractInsnNode, List<SSAValue>> defs = new HashMap<>();
    private final Map<AbstractInsnNode, List<SSAValue>> uses = new HashMap<>();

    public void addDef(AbstractInsnNode insn, SSAValue value) {
        defs.computeIfAbsent(insn, k -> new ArrayList<>()).add(value);
    }

    public void addUse(AbstractInsnNode insn, SSAValue value) {
        uses.computeIfAbsent(insn, k -> new ArrayList<>()).add(value);
    }

    public List<SSAValue> getDefs(AbstractInsnNode insn) {
        return defs.getOrDefault(insn, List.of());
    }

    public List<SSAValue> getUses(AbstractInsnNode insn) {
        return uses.getOrDefault(insn, List.of());
    }

    public boolean hasDef(AbstractInsnNode insn) {
        return defs.containsKey(insn);
    }

    public boolean hasUse(AbstractInsnNode insn) {
        return uses.containsKey(insn);
    }
}
