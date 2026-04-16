package dev.nekoobfuscator.core.ir.l2;

import org.objectweb.asm.tree.AbstractInsnNode;
import java.util.*;

/**
 * A basic block in the control flow graph.
 * Contains a sequence of instructions with a single entry point and single exit point.
 */
public final class BasicBlock {
    private final int id;
    private final List<AbstractInsnNode> instructions = new ArrayList<>();
    private final List<CFGEdge> inEdges = new ArrayList<>();
    private final List<CFGEdge> outEdges = new ArrayList<>();
    private boolean isExceptionHandler;
    private String exceptionType; // internal name of caught exception, null = catch-all

    public BasicBlock(int id) {
        this.id = id;
    }

    public int id() { return id; }

    public List<AbstractInsnNode> instructions() { return instructions; }
    public void addInstruction(AbstractInsnNode insn) { instructions.add(insn); }

    public List<CFGEdge> inEdges() { return inEdges; }
    public List<CFGEdge> outEdges() { return outEdges; }

    public void addInEdge(CFGEdge edge) { inEdges.add(edge); }
    public void addOutEdge(CFGEdge edge) { outEdges.add(edge); }

    public List<BasicBlock> successors() {
        List<BasicBlock> succs = new ArrayList<>();
        for (CFGEdge e : outEdges) succs.add(e.target());
        return succs;
    }

    public List<BasicBlock> predecessors() {
        List<BasicBlock> preds = new ArrayList<>();
        for (CFGEdge e : inEdges) preds.add(e.source());
        return preds;
    }

    public boolean isExceptionHandler() { return isExceptionHandler; }
    public void setExceptionHandler(boolean eh) { this.isExceptionHandler = eh; }
    public String exceptionType() { return exceptionType; }
    public void setExceptionType(String type) { this.exceptionType = type; }

    public AbstractInsnNode firstInsn() {
        return instructions.isEmpty() ? null : instructions.get(0);
    }

    public AbstractInsnNode lastInsn() {
        return instructions.isEmpty() ? null : instructions.get(instructions.size() - 1);
    }

    public boolean isEmpty() { return instructions.isEmpty(); }
    public int size() { return instructions.size(); }

    @Override
    public String toString() {
        return "BB#" + id + "[" + instructions.size() + " insns]";
    }

    @Override
    public int hashCode() { return id; }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof BasicBlock bb && bb.id == this.id);
    }
}
