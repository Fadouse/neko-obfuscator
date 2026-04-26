package dev.nekoobfuscator.test;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NekoNativeLoaderReflectionTest {
    @Test
    void loaderOnlyExposesNativeLibraryLoad() throws Exception {
        Class<?> loaderClass = Class.forName("dev.nekoobfuscator.runtime.NekoNativeLoader");
        Method load = loaderClass.getDeclaredMethod("load");
        Set<String> methodNames = Arrays.stream(loaderClass.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertNotNull(load);
        assertTrue(Modifier.isPublic(load.getModifiers()));
        assertTrue(Modifier.isStatic(load.getModifiers()));

        assertFalse(methodNames.contains("nekoBootstrap"));
        assertFalse(methodNames.contains("nekoVmOption"));
        assertFalse(methodNames.contains("nekoAddressSize"));
        assertFalse(methodNames.contains("nekoArrayBaseOffset"));
        assertFalse(methodNames.contains("nekoArrayIndexScale"));
        assertFalse(methodNames.contains("nekoInstanceFieldOffset"));
        assertFalse(methodNames.contains("nekoStaticFieldOffset"));
        assertFalse(methodNames.contains("nekoStaticFieldBase"));
    }
}
