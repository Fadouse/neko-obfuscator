package dev.nekoobfuscator.core.ir.l1;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * L1 IR wrapper around ASM MethodNode.
 */
public final class L1Method {
    private final L1Class owner;
    private final MethodNode node;
    private final String name;
    private final String descriptor;
    private final Type[] argumentTypes;
    private final Type returnType;

    public L1Method(L1Class owner, MethodNode node) {
        this.owner = owner;
        this.node = node;
        this.name = node.name;
        this.descriptor = node.desc;
        this.argumentTypes = Type.getArgumentTypes(node.desc);
        this.returnType = Type.getReturnType(node.desc);
    }

    public L1Class owner() { return owner; }
    public MethodNode asmNode() { return node; }
    public String name() { return name; }
    public String descriptor() { return descriptor; }
    public Type[] argumentTypes() { return argumentTypes; }
    public Type returnType() { return returnType; }
    public int access() { return node.access; }

    public InsnList instructions() { return node.instructions; }
    public List<TryCatchBlockNode> tryCatchBlocks() {
        return node.tryCatchBlocks != null ? node.tryCatchBlocks : List.of();
    }
    public List<LocalVariableNode> localVariables() {
        return node.localVariables != null ? node.localVariables : List.of();
    }

    public int maxStack() { return node.maxStack; }
    public int maxLocals() { return node.maxLocals; }

    public int instructionCount() {
        return node.instructions != null ? node.instructions.size() : 0;
    }

    public boolean isStatic() { return (node.access & Opcodes.ACC_STATIC) != 0; }
    public boolean isAbstract() { return (node.access & Opcodes.ACC_ABSTRACT) != 0; }
    public boolean isNative() { return (node.access & Opcodes.ACC_NATIVE) != 0; }
    public boolean isBridge() { return (node.access & Opcodes.ACC_BRIDGE) != 0; }
    public boolean isSynthetic() { return (node.access & Opcodes.ACC_SYNTHETIC) != 0; }
    public boolean isConstructor() { return "<init>".equals(name); }
    public boolean isClassInit() { return "<clinit>".equals(name); }

    public boolean hasCode() {
        return !isAbstract() && !isNative() && node.instructions != null && node.instructions.size() > 0;
    }

    @Override
    public String toString() {
        return "L1Method{" + owner.name() + "." + name + descriptor + "}";
    }
}
