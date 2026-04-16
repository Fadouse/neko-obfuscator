package dev.nekoobfuscator.core.ir.l3;

public final class CVariable {
    private final String name;
    private final CType type;
    private final int index;

    public CVariable(String name, CType type, int index) {
        this.name = name;
        this.type = type;
        this.index = index;
    }

    public String name() { return name; }
    public CType type() { return type; }
    public int index() { return index; }

    public String declaration() {
        return type.jniName() + " " + name;
    }

    @Override
    public String toString() { return name; }
}
