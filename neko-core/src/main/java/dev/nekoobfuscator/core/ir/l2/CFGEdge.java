package dev.nekoobfuscator.core.ir.l2;

public final class CFGEdge {
    public enum Type {
        FALL_THROUGH,
        CONDITIONAL_TRUE,
        CONDITIONAL_FALSE,
        UNCONDITIONAL,
        SWITCH_CASE,
        SWITCH_DEFAULT,
        EXCEPTION
    }

    private final BasicBlock source;
    private final BasicBlock target;
    private final Type type;
    private final int switchKey; // only for SWITCH_CASE

    public CFGEdge(BasicBlock source, BasicBlock target, Type type) {
        this(source, target, type, 0);
    }

    public CFGEdge(BasicBlock source, BasicBlock target, Type type, int switchKey) {
        this.source = source;
        this.target = target;
        this.type = type;
        this.switchKey = switchKey;
    }

    public BasicBlock source() { return source; }
    public BasicBlock target() { return target; }
    public Type type() { return type; }
    public int switchKey() { return switchKey; }

    @Override
    public String toString() {
        return "Edge{" + source.id() + " -[" + type + "]-> " + target.id() + "}";
    }
}
