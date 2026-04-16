package dev.nekoobfuscator.core.ir.l2;

import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.util.AsmUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Control Flow Graph built from a method's bytecode.
 * Nodes are BasicBlocks, edges represent control flow transitions.
 */
public final class ControlFlowGraph {
    private final L1Method method;
    private final List<BasicBlock> blocks;
    private final BasicBlock entryBlock;
    private final List<BasicBlock> exitBlocks;
    private final Map<AbstractInsnNode, BasicBlock> insnToBlock;
    private DominatorTree domTree;

    private ControlFlowGraph(L1Method method, List<BasicBlock> blocks, BasicBlock entryBlock,
                             List<BasicBlock> exitBlocks, Map<AbstractInsnNode, BasicBlock> insnToBlock) {
        this.method = method;
        this.blocks = blocks;
        this.entryBlock = entryBlock;
        this.exitBlocks = exitBlocks;
        this.insnToBlock = insnToBlock;
    }

    public L1Method method() { return method; }
    public List<BasicBlock> blocks() { return blocks; }
    public BasicBlock entryBlock() { return entryBlock; }
    public List<BasicBlock> exitBlocks() { return exitBlocks; }

    public BasicBlock blockForInsn(AbstractInsnNode insn) {
        return insnToBlock.get(insn);
    }

    public int blockCount() { return blocks.size(); }

    public DominatorTree dominatorTree() {
        if (domTree == null) {
            domTree = DominatorTree.compute(this);
        }
        return domTree;
    }

    /**
     * Compute reverse post-order traversal of the CFG.
     * Used for data flow analysis (iterating in RPO converges faster).
     */
    public List<BasicBlock> reversePostOrder() {
        List<BasicBlock> rpo = new ArrayList<>();
        Set<BasicBlock> visited = new HashSet<>();
        Deque<BasicBlock> stack = new ArrayDeque<>();

        // Iterative post-order DFS
        dfsPostOrder(entryBlock, visited, rpo);
        Collections.reverse(rpo);
        return rpo;
    }

    private void dfsPostOrder(BasicBlock block, Set<BasicBlock> visited, List<BasicBlock> result) {
        if (!visited.add(block)) return;
        for (BasicBlock succ : block.successors()) {
            dfsPostOrder(succ, visited, result);
        }
        result.add(block);
    }

    // ===== CFG Construction =====

    public static ControlFlowGraph build(L1Method method) {
        InsnList insns = method.instructions();
        if (insns == null || insns.size() == 0) {
            BasicBlock empty = new BasicBlock(0);
            return new ControlFlowGraph(method, List.of(empty), empty, List.of(empty), Map.of());
        }

        // Step 1: Find all leader instructions (start of basic blocks)
        Set<AbstractInsnNode> leaders = findLeaders(insns, method.tryCatchBlocks());

        // Step 2: Partition instructions into basic blocks
        Map<AbstractInsnNode, BasicBlock> insnToBlock = new HashMap<>();
        List<BasicBlock> blocks = new ArrayList<>();
        Map<LabelNode, BasicBlock> labelToBlock = new HashMap<>();

        int blockId = 0;
        BasicBlock currentBlock = null;

        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            // Start a new block at each leader
            if (leaders.contains(insn) || currentBlock == null) {
                currentBlock = new BasicBlock(blockId++);
                blocks.add(currentBlock);
            }

            // Map labels to their blocks
            if (insn instanceof LabelNode label) {
                labelToBlock.put(label, currentBlock);
            }

            currentBlock.addInstruction(insn);
            insnToBlock.put(insn, currentBlock);

            // End block after terminators (the next insn, if a leader, starts a new block)
            if (AsmUtil.isRealInstruction(insn) && AsmUtil.isTerminator(insn)) {
                currentBlock = null; // force new block on next instruction
            }
        }

        if (blocks.isEmpty()) {
            BasicBlock empty = new BasicBlock(0);
            return new ControlFlowGraph(method, List.of(empty), empty, List.of(empty), Map.of());
        }

        // Step 3: Build edges
        List<BasicBlock> exitBlocks = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock block = blocks.get(i);
            AbstractInsnNode lastReal = findLastRealInsn(block);
            if (lastReal == null) {
                // Empty block or only labels/frames - fall through
                if (i + 1 < blocks.size()) {
                    addEdge(block, blocks.get(i + 1), CFGEdge.Type.FALL_THROUGH);
                }
                continue;
            }

            int opcode = lastReal.getOpcode();

            if (AsmUtil.isReturn(opcode) || opcode == Opcodes.ATHROW) {
                exitBlocks.add(block);
            } else if (lastReal instanceof JumpInsnNode jump) {
                BasicBlock target = labelToBlock.get(jump.label);
                if (target != null) {
                    if (opcode == Opcodes.GOTO || opcode == 200 /* GOTO_W */) {
                        addEdge(block, target, CFGEdge.Type.UNCONDITIONAL);
                    } else {
                        // Conditional: true branch to target, false falls through
                        addEdge(block, target, CFGEdge.Type.CONDITIONAL_TRUE);
                        if (i + 1 < blocks.size()) {
                            addEdge(block, blocks.get(i + 1), CFGEdge.Type.CONDITIONAL_FALSE);
                        }
                    }
                }
            } else if (lastReal instanceof TableSwitchInsnNode ts) {
                BasicBlock dflt = labelToBlock.get(ts.dflt);
                if (dflt != null) addEdge(block, dflt, CFGEdge.Type.SWITCH_DEFAULT);
                for (int k = 0; k < ts.labels.size(); k++) {
                    BasicBlock caseBlock = labelToBlock.get(ts.labels.get(k));
                    if (caseBlock != null) {
                        addEdge(block, caseBlock, CFGEdge.Type.SWITCH_CASE, ts.min + k);
                    }
                }
            } else if (lastReal instanceof LookupSwitchInsnNode ls) {
                BasicBlock dflt = labelToBlock.get(ls.dflt);
                if (dflt != null) addEdge(block, dflt, CFGEdge.Type.SWITCH_DEFAULT);
                for (int k = 0; k < ls.labels.size(); k++) {
                    BasicBlock caseBlock = labelToBlock.get(ls.labels.get(k));
                    if (caseBlock != null) {
                        addEdge(block, caseBlock, CFGEdge.Type.SWITCH_CASE, ls.keys.get(k));
                    }
                }
            } else {
                // Fall through to next block
                if (i + 1 < blocks.size()) {
                    addEdge(block, blocks.get(i + 1), CFGEdge.Type.FALL_THROUGH);
                }
            }
        }

        // Step 4: Exception handler edges
        for (TryCatchBlockNode tcb : method.tryCatchBlocks()) {
            BasicBlock handlerBlock = labelToBlock.get(tcb.handler);
            if (handlerBlock != null) {
                handlerBlock.setExceptionHandler(true);
                handlerBlock.setExceptionType(tcb.type);

                // Find all blocks in the try range [start, end)
                boolean inRange = false;
                for (BasicBlock b : blocks) {
                    for (AbstractInsnNode insn : b.instructions()) {
                        if (insn == tcb.start) inRange = true;
                        if (insn == tcb.end) inRange = false;
                    }
                    if (inRange) {
                        addEdge(b, handlerBlock, CFGEdge.Type.EXCEPTION);
                    }
                }
            }
        }

        BasicBlock entry = blocks.get(0);
        return new ControlFlowGraph(method, blocks, entry, exitBlocks, insnToBlock);
    }

    private static Set<AbstractInsnNode> findLeaders(InsnList insns, List<TryCatchBlockNode> tryCatch) {
        Set<AbstractInsnNode> leaders = new LinkedHashSet<>();

        // First instruction is always a leader
        leaders.add(insns.getFirst());

        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (!AsmUtil.isRealInstruction(insn)) continue;

            if (insn instanceof JumpInsnNode jump) {
                // Jump target is a leader
                leaders.add(jump.label);
                // Instruction after jump is a leader (fall-through for conditional, or start of next block)
                if (insn.getNext() != null) leaders.add(insn.getNext());
            } else if (insn instanceof TableSwitchInsnNode ts) {
                leaders.add(ts.dflt);
                for (LabelNode l : ts.labels) leaders.add(l);
                if (insn.getNext() != null) leaders.add(insn.getNext());
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                leaders.add(ls.dflt);
                for (LabelNode l : ls.labels) leaders.add(l);
                if (insn.getNext() != null) leaders.add(insn.getNext());
            } else if (AsmUtil.isReturn(insn.getOpcode()) || insn.getOpcode() == Opcodes.ATHROW) {
                if (insn.getNext() != null) leaders.add(insn.getNext());
            }
        }

        // Exception handler starts are leaders
        for (TryCatchBlockNode tcb : tryCatch) {
            leaders.add(tcb.handler);
            leaders.add(tcb.start);
            leaders.add(tcb.end);
        }

        return leaders;
    }

    private static AbstractInsnNode findLastRealInsn(BasicBlock block) {
        for (int i = block.instructions().size() - 1; i >= 0; i--) {
            AbstractInsnNode insn = block.instructions().get(i);
            if (AsmUtil.isRealInstruction(insn)) return insn;
        }
        return null;
    }

    private static void addEdge(BasicBlock source, BasicBlock target, CFGEdge.Type type) {
        addEdge(source, target, type, 0);
    }

    private static void addEdge(BasicBlock source, BasicBlock target, CFGEdge.Type type, int switchKey) {
        CFGEdge edge = new CFGEdge(source, target, type, switchKey);
        source.addOutEdge(edge);
        target.addInEdge(edge);
    }

    @Override
    public String toString() {
        return "CFG{" + method + ", " + blocks.size() + " blocks}";
    }
}
