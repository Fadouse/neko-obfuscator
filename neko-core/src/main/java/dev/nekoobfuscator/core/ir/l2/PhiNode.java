package dev.nekoobfuscator.core.ir.l2;

import java.util.*;

/**
 * SSA phi function at a control flow merge point.
 * phi(v1, v2, ...) = result, where each operand comes from a specific predecessor.
 */
public final class PhiNode {
    private final SSAValue result;
    private final int localIndex;
    private final BasicBlock block;
    private final Map<BasicBlock, SSAValue> operands = new LinkedHashMap<>();

    public PhiNode(SSAValue result, int localIndex, BasicBlock block) {
        this.result = result;
        this.localIndex = localIndex;
        this.block = block;
        result.setDefPhi(this);
    }

    public SSAValue result() { return result; }
    public int localIndex() { return localIndex; }
    public BasicBlock block() { return block; }
    public Map<BasicBlock, SSAValue> operands() { return operands; }

    public void setOperand(BasicBlock predecessor, SSAValue value) {
        operands.put(predecessor, value);
    }

    public SSAValue getOperand(BasicBlock predecessor) {
        return operands.get(predecessor);
    }

    public Collection<SSAValue> operandValues() {
        return operands.values();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(result.toName()).append(" = phi(");
        boolean first = true;
        for (var entry : operands.entrySet()) {
            if (!first) sb.append(", ");
            sb.append("BB#").append(entry.getKey().id()).append(":").append(entry.getValue().toName());
            first = false;
        }
        sb.append(")");
        return sb.toString();
    }
}
