package dev.nekoobfuscator.core.ir.l1;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldNode;

/**
 * L1 IR wrapper around ASM FieldNode.
 */
public final class L1Field {
    private final L1Class owner;
    private final FieldNode node;
    private final String name;
    private final String descriptor;

    public L1Field(L1Class owner, FieldNode node) {
        this.owner = owner;
        this.node = node;
        this.name = node.name;
        this.descriptor = node.desc;
    }

    public L1Class owner() { return owner; }
    public FieldNode asmNode() { return node; }
    public String name() { return name; }
    public String descriptor() { return descriptor; }
    public int access() { return node.access; }
    public Object value() { return node.value; }

    public Type type() { return Type.getType(descriptor); }

    public boolean isStatic() { return (node.access & Opcodes.ACC_STATIC) != 0; }
    public boolean isFinal() { return (node.access & Opcodes.ACC_FINAL) != 0; }
    public boolean isPrivate() { return (node.access & Opcodes.ACC_PRIVATE) != 0; }

    @Override
    public String toString() {
        return "L1Field{" + owner.name() + "." + name + ":" + descriptor + "}";
    }
}
