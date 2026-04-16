package dev.nekoobfuscator.core.util;

import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Instruction list manipulation helpers.
 */
public final class InsnUtil {
    private InsnUtil() {}

    /**
     * Clone an instruction (deep copy, remapping labels).
     */
    public static AbstractInsnNode clone(AbstractInsnNode insn, Map<LabelNode, LabelNode> labelMap) {
        return insn.clone(labelMap);
    }

    /**
     * Create a label map from an InsnList for cloning.
     */
    public static Map<LabelNode, LabelNode> createLabelMap(InsnList insns) {
        Map<LabelNode, LabelNode> map = new HashMap<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                map.put(label, new LabelNode());
            }
        }
        return map;
    }

    /**
     * Clone an entire InsnList.
     */
    public static InsnList cloneInsnList(InsnList source) {
        Map<LabelNode, LabelNode> labelMap = createLabelMap(source);
        InsnList result = new InsnList();
        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            result.add(insn.clone(labelMap));
        }
        return result;
    }

    /**
     * Find all LabelNodes referenced by jump instructions in an InsnList.
     */
    public static Set<LabelNode> findJumpTargets(InsnList insns) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode jump) {
                targets.add(jump.label);
            } else if (insn instanceof TableSwitchInsnNode ts) {
                targets.add(ts.dflt);
                targets.addAll(ts.labels);
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                targets.add(ls.dflt);
                targets.addAll(ls.labels);
            }
        }
        return targets;
    }

    /**
     * Collect all instructions between two nodes (exclusive of both).
     */
    public static List<AbstractInsnNode> collectBetween(AbstractInsnNode start, AbstractInsnNode end) {
        List<AbstractInsnNode> result = new ArrayList<>();
        AbstractInsnNode current = start.getNext();
        while (current != null && current != end) {
            result.add(current);
            current = current.getNext();
        }
        return result;
    }
}
