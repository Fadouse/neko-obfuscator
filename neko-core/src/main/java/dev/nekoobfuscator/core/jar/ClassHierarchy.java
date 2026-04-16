package dev.nekoobfuscator.core.jar;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Maintains class hierarchy information for computing common super classes
 * during bytecode generation. This avoids ClassWriter having to use ClassLoader
 * which would fail for obfuscated classes.
 */
public final class ClassHierarchy {
    private static final Logger log = LoggerFactory.getLogger(ClassHierarchy.class);
    private static final String OBJECT = "java/lang/Object";

    private final Map<String, HierarchyInfo> infoMap = new HashMap<>();

    public void addClass(L1Class l1) {
        boolean isInterface = (l1.access() & Opcodes.ACC_INTERFACE) != 0;
        infoMap.put(l1.name(), new HierarchyInfo(
            l1.name(),
            l1.superName(),
            l1.interfaces(),
            isInterface
        ));
    }

    public void addSystemClass(String name, String superName, List<String> interfaces, boolean isInterface) {
        infoMap.put(name, new HierarchyInfo(name, superName, interfaces, isInterface));
    }

    public String getCommonSuperClass(String type1, String type2) {
        if (type1.equals(type2)) return type1;
        if (OBJECT.equals(type1) || OBJECT.equals(type2)) return OBJECT;

        HierarchyInfo info1 = infoMap.get(type1);
        HierarchyInfo info2 = infoMap.get(type2);

        if (info1 == null || info2 == null) return OBJECT;

        if (info1.isInterface || info2.isInterface) return OBJECT;

        // Walk ancestors of type1
        Set<String> ancestors1 = new LinkedHashSet<>();
        String current = type1;
        while (current != null && !OBJECT.equals(current)) {
            ancestors1.add(current);
            HierarchyInfo info = infoMap.get(current);
            current = info != null ? info.superName : null;
        }
        ancestors1.add(OBJECT);

        // Walk ancestors of type2, find first common
        current = type2;
        while (current != null) {
            if (ancestors1.contains(current)) return current;
            HierarchyInfo info = infoMap.get(current);
            current = info != null ? info.superName : null;
        }

        return OBJECT;
    }

    public boolean isAssignableFrom(String parent, String child) {
        if (parent.equals(child)) return true;
        if (OBJECT.equals(parent)) return true;

        String current = child;
        while (current != null && !OBJECT.equals(current)) {
            HierarchyInfo info = infoMap.get(current);
            if (info == null) return false;
            if (parent.equals(info.superName)) return true;
            if (info.interfaces.contains(parent)) return true;
            current = info.superName;
        }
        return false;
    }

    public HierarchyInfo getInfo(String name) {
        return infoMap.get(name);
    }

    public int size() {
        return infoMap.size();
    }

    public record HierarchyInfo(
        String name,
        String superName,
        List<String> interfaces,
        boolean isInterface
    ) {}
}
