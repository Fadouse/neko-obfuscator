package dev.nekoobfuscator.core.ir.l1;

import org.objectweb.asm.tree.ClassNode;
import java.util.*;

/**
 * L1 IR wrapper around ASM ClassNode. Adds metadata tracking and convenience methods.
 */
public final class L1Class {
    private final ClassNode node;
    private final String name;
    private final List<L1Method> methods;
    private final List<L1Field> fields;
    private final Set<String> annotationNames;
    private boolean dirty;
    private byte[] originalBytes; // original class file bytes for fallback

    public L1Class(ClassNode node) {
        this.node = node;
        this.name = node.name;
        this.methods = new ArrayList<>();
        this.fields = new ArrayList<>();
        this.annotationNames = new HashSet<>();

        if (node.methods != null) {
            for (var mn : node.methods) {
                methods.add(new L1Method(this, mn));
            }
        }
        if (node.fields != null) {
            for (var fn : node.fields) {
                fields.add(new L1Field(this, fn));
            }
        }
        // Cache annotation names
        if (node.visibleAnnotations != null) {
            for (var ann : node.visibleAnnotations) {
                annotationNames.add(ann.desc);
            }
        }
        if (node.invisibleAnnotations != null) {
            for (var ann : node.invisibleAnnotations) {
                annotationNames.add(ann.desc);
            }
        }
    }

    public ClassNode asmNode() { return node; }
    public String name() { return name; }
    public List<L1Method> methods() { return methods; }
    public List<L1Field> fields() { return fields; }

    public String superName() { return node.superName; }
    public List<String> interfaces() { return node.interfaces != null ? node.interfaces : List.of(); }
    public int access() { return node.access; }
    public int version() { return node.version; }

    public int javaVersion() {
        return node.version & 0xFFFF; // major version: 52=Java8, 55=Java11, 61=Java17, 65=Java21
    }

    public boolean isInterface() {
        return (node.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isEnum() {
        return (node.access & org.objectweb.asm.Opcodes.ACC_ENUM) != 0;
    }

    public boolean isAbstract() {
        return (node.access & org.objectweb.asm.Opcodes.ACC_ABSTRACT) != 0;
    }

    public boolean isAnnotation() {
        return (node.access & org.objectweb.asm.Opcodes.ACC_ANNOTATION) != 0;
    }

    public boolean hasAnnotation(String desc) {
        return annotationNames.contains(desc);
    }

    public L1Method findMethod(String name, String desc) {
        for (L1Method m : methods) {
            if (m.name().equals(name) && m.descriptor().equals(desc)) {
                return m;
            }
        }
        return null;
    }

    public L1Field findField(String name, String desc) {
        for (L1Field f : fields) {
            if (f.name().equals(name) && f.descriptor().equals(desc)) {
                return f;
            }
        }
        return null;
    }

    public boolean isDirty() { return dirty; }
    public void markDirty() { this.dirty = true; }

    public byte[] originalBytes() { return originalBytes; }
    public void setOriginalBytes(byte[] bytes) { this.originalBytes = bytes; }

    @Override
    public String toString() {
        return "L1Class{" + name + "}";
    }
}
