package dev.nekoobfuscator.test;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NekoNativeLoaderReflectionTest {
    @Test
    void probeHelpersPresent() throws Exception {
        Class<?> loaderClass = Class.forName("dev.nekoobfuscator.runtime.NekoNativeLoader");

        Method vmOption = helper(loaderClass, "nekoVmOption", String.class);
        Method addressSize = helper(loaderClass, "nekoAddressSize");
        Method arrayBaseOffset = helper(loaderClass, "nekoArrayBaseOffset", String.class);
        Method arrayIndexScale = helper(loaderClass, "nekoArrayIndexScale", String.class);
        Method instanceFieldOffset = helper(loaderClass, "nekoInstanceFieldOffset", Class.class, String.class);
        Method staticFieldOffset = helper(loaderClass, "nekoStaticFieldOffset", Class.class, String.class);
        Method staticFieldBase = helper(loaderClass, "nekoStaticFieldBase", Class.class, String.class);

        assertNull(vmOption.invoke(null, "__neko_missing_vm_option__"));
        Object addressSizeValue = addressSize.invoke(null);
        assertTrue(addressSizeValue instanceof Integer);
        assertTrue(((Integer) addressSizeValue) >= 0);
        assertEquals(-1, ((Integer) arrayBaseOffset.invoke(null, "__missing_primitive__")).intValue());
        assertEquals(0, ((Integer) arrayIndexScale.invoke(null, "__missing_primitive__")).intValue());
        assertEquals(-1L, ((Long) instanceFieldOffset.invoke(null, String.class, "__missing_field__")).longValue());
        assertEquals(-1L, ((Long) staticFieldOffset.invoke(null, Integer.class, "__missing_field__")).longValue());
        assertNull(staticFieldBase.invoke(null, Integer.class, "__missing_field__"));
    }

    private static Method helper(Class<?> owner, String name, Class<?>... parameterTypes) throws Exception {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        assertPackagePrivateStatic(method);
        return method;
    }

    private static void assertPackagePrivateStatic(Method method) {
        int modifiers = method.getModifiers();
        assertNotNull(method);
        assertTrue(Modifier.isStatic(modifiers), () -> method + " should be static");
        assertFalse(Modifier.isPublic(modifiers), () -> method + " should not be public");
        assertFalse(Modifier.isProtected(modifiers), () -> method + " should not be protected");
        assertFalse(Modifier.isPrivate(modifiers), () -> method + " should not be private");
    }
}
