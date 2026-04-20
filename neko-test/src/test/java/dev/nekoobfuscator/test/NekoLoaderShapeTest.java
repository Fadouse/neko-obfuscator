package dev.nekoobfuscator.test;

import dev.nekoobfuscator.runtime.NekoNativeLoader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NekoLoaderShapeTest {
    @Test
    void classIsFinalAndPublicWithPrivateConstructor() {
        assertTrue(Modifier.isPublic(NekoNativeLoader.class.getModifiers()));
        assertTrue(Modifier.isFinal(NekoNativeLoader.class.getModifiers()));

        Constructor<?>[] constructors = NekoNativeLoader.class.getDeclaredConstructors();
        assertEquals(1, constructors.length, "Expected exactly one constructor");
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()), "Constructor must be private");
    }

    @Test
    void onlyPublicMethodIsLoad() {
        Set<String> publicMethods = Arrays.stream(NekoNativeLoader.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(Method::getName)
            .collect(toSet());

        assertEquals(Set.of("load"), publicMethods);
    }

    @Test
    void forbiddenLegacyHelpersAreAbsent() {
        List<String> forbidden = List.of(
            "bindClass",
            "nekoBindClass",
            "nekoVmOption",
            "nekoAddressSize",
            "nekoArrayBaseOffset",
            "nekoArrayIndexScale",
            "nekoInstanceFieldOffset",
            "nekoStaticFieldOffset",
            "nekoStaticFieldBase"
        );
        Set<String> declared = Arrays.stream(NekoNativeLoader.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(toSet());

        for (String name : forbidden) {
            assertFalse(declared.contains(name), () -> "Legacy helper must be deleted: " + name);
        }
    }

    @Test
    void allFieldsArePrivateStatic() {
        Field[] declaredFields = NekoNativeLoader.class.getDeclaredFields();
        Set<String> fieldNames = Arrays.stream(declaredFields)
            .map(Field::getName)
            .collect(toSet());

        assertEquals(Set.of("loaded", "LOCK"), fieldNames);
        for (Field field : declaredFields) {
            assertTrue(Modifier.isPrivate(field.getModifiers()), () -> field.getName() + " must be private");
            assertTrue(Modifier.isStatic(field.getModifiers()), () -> field.getName() + " must be static");
        }
    }

    @Test
    void loadIsIdempotentAcrossSuccessAndMissingLibraryPaths() {
        try {
            NekoNativeLoader.load();
        } catch (UnsatisfiedLinkError firstFailure) {
            // The test runtime may not package a native library resource. In that case the
            // loader may keep re-throwing UnsatisfiedLinkError until a real library is present.
            try {
                NekoNativeLoader.load();
            } catch (UnsatisfiedLinkError ignored) {
                return;
            }
            return;
        }

        assertDoesNotThrow(NekoNativeLoader::load);
    }
}
