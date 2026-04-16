package dev.nekoobfuscator.core.util;

import org.objectweb.asm.Type;

/**
 * Utilities for parsing and building JVM type descriptors and method descriptors.
 */
public final class DescriptorUtil {
    private DescriptorUtil() {}

    public static String internalToExternal(String internalName) {
        return internalName.replace('/', '.');
    }

    public static String externalToInternal(String externalName) {
        return externalName.replace('.', '/');
    }

    public static Type[] parseMethodArgs(String methodDesc) {
        return Type.getArgumentTypes(methodDesc);
    }

    public static Type parseMethodReturn(String methodDesc) {
        return Type.getReturnType(methodDesc);
    }

    public static int getMethodParameterSlots(String methodDesc, boolean isStatic) {
        int slots = isStatic ? 0 : 1; // 'this' takes slot 0 for instance methods
        for (Type t : Type.getArgumentTypes(methodDesc)) {
            slots += t.getSize();
        }
        return slots;
    }

    public static String getSimpleClassName(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        return lastSlash >= 0 ? internalName.substring(lastSlash + 1) : internalName;
    }

    public static String getPackageName(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        return lastSlash >= 0 ? internalName.substring(0, lastSlash) : "";
    }
}
