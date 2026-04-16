package dev.nekoobfuscator.core.ir.l2;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import java.util.*;

/**
 * A versioned SSA value representing a single definition of a local variable.
 */
public final class SSAValue {
    private final int originalLocal;  // original local variable index
    private final int version;        // SSA version number
    private Type type;                // inferred type (may be null initially)
    private AbstractInsnNode defSite; // instruction that defines this value
    private PhiNode defPhi;           // phi that defines this value (null for regular def)
    private final List<AbstractInsnNode> useSites = new ArrayList<>();

    public SSAValue(int originalLocal, int version) {
        this.originalLocal = originalLocal;
        this.version = version;
    }

    public int originalLocal() { return originalLocal; }
    public int version() { return version; }
    public Type type() { return type; }
    public void setType(Type type) { this.type = type; }

    public AbstractInsnNode defSite() { return defSite; }
    public void setDefSite(AbstractInsnNode defSite) { this.defSite = defSite; }

    public PhiNode defPhi() { return defPhi; }
    public void setDefPhi(PhiNode defPhi) { this.defPhi = defPhi; }

    public List<AbstractInsnNode> useSites() { return useSites; }
    public void addUseSite(AbstractInsnNode insn) { useSites.add(insn); }

    public boolean isPhiDefined() { return defPhi != null; }
    public boolean hasUses() { return !useSites.isEmpty(); }

    public String toName() {
        return "v" + originalLocal + "_" + version;
    }

    @Override
    public String toString() {
        return toName();
    }

    @Override
    public int hashCode() {
        return originalLocal * 31 + version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SSAValue v)) return false;
        return originalLocal == v.originalLocal && version == v.version;
    }
}
