package dev.nekoobfuscator.test;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeObfuscationIntegrationTest {
    private static final Set<String> FORBIDDEN_RUNTIME_CLASSES = Set.of(
        "dev/nekoobfuscator/runtime/NekoBootstrap.class",
        "dev/nekoobfuscator/runtime/NekoKeyDerivation.class",
        "dev/nekoobfuscator/runtime/NekoStringDecryptor.class",
        "dev/nekoobfuscator/runtime/NekoFlowException.class",
        "dev/nekoobfuscator/runtime/NekoContext.class",
        "dev/nekoobfuscator/runtime/NekoClassLoader.class",
        "dev/nekoobfuscator/runtime/NekoResourceLoader.class",
        "dev/nekoobfuscator/runtime/NekoUnsafe.class",
        "dev/nekoobfuscator/runtime/NekoIndyDispatch.class"
    );
    private static final Pattern HELPER_NAME_PATTERN = Pattern.compile("neko_impl_helper_.*");

    @BeforeAll
    static void prepareFixtures() throws Exception {
        NativeObfuscationHelper.ensureObfuscatedFixtures();
    }

    @Test
    @Timeout(2)
    void nativeObfuscation_TEST_calcUnder150ms() throws Exception {
        NativeObfuscationHelper.JarRunResult result = NativeObfuscationHelper.runCachedObfuscated("TEST", List.of(), List.of(), Duration.ofMinutes(2));

        assertEquals(0, result.exitCode(), () -> result.combinedOutput());
        NativeObfuscationHelper.assertNoFatalNativeCrash(result);
        long calcMillis = NativeObfuscationHelper.parseCalcMillis(result.combinedOutput());
        assertTrue(calcMillis <= 150, () -> "Expected Calc benchmark <= 150ms but got " + calcMillis + "ms\n" + result.combinedOutput());
    }

    @Test
    @Timeout(2)
    void nativeObfuscation_TEST_allTestsExceptSecurityPass() throws Exception {
        NativeObfuscationHelper.JarRunResult original = NativeObfuscationHelper.runCachedOriginal("TEST", List.of(), List.of(), Duration.ofMinutes(2));
        NativeObfuscationHelper.JarRunResult nativeRun = NativeObfuscationHelper.runCachedObfuscated("TEST", List.of(), List.of(), Duration.ofMinutes(2));

        assertEquals(0, original.exitCode(), () -> original.combinedOutput());
        assertEquals(0, nativeRun.exitCode(), () -> nativeRun.combinedOutput());
        NativeObfuscationHelper.assertNoFatalNativeCrash(nativeRun);

        String originalOutput = original.combinedOutput();
        String nativeOutput = nativeRun.combinedOutput();

        List<String> expectedPassLines = List.of(
            "Test 1.1: Inheritance PASS",
            "Test 1.2: Cross PASS",
            "Test 1.3: Throw PASS",
            "Test 1.4: Accuracy PASS",
            "Test 1.5: SubClass PASS",
            "Test 1.6: Pool PASS",
            "Test 1.7: InnerClass PASS",
            "Test 2.1: Counter PASS",
            "Test 2.3: Resource PASS",
            "Test 2.4: Field PASS",
            "Test 2.5: Loader PASS",
            "Test 2.6: ReTrace PASS",
            "Test 2.7: Annotation PASS"
        );

        for (String expectedPassLine : expectedPassLines) {
            assertTrue(originalOutput.contains(expectedPassLine), () -> "Original TEST.jar output missing baseline line: " + expectedPassLine + "\n" + originalOutput);
            assertTrue(nativeOutput.contains(expectedPassLine), () -> "Native TEST.jar output missing pass line: " + expectedPassLine + "\n" + nativeOutput);
        }

        assertTrue(originalOutput.contains("Test 2.2: Chinese"), () -> originalOutput);
        assertTrue(nativeOutput.contains("Test 2.2: Chinese"), () -> nativeOutput);
        assertTrue(nativeOutput.contains("Test 2.8: Sec ERROR"), () -> "Expected known Test 2.8 baseline failure to remain visible\n" + nativeOutput);
    }

    @Test
    @Timeout(2)
    void nativeObfuscation_obfusjack_reachesCompletion() throws Exception {
        NativeObfuscationHelper.JarRunResult result = NativeObfuscationHelper.runCachedObfuscated("obfusjack", List.of(), List.of(), Duration.ofMinutes(2));

        assertEquals(0, result.exitCode(), () -> result.combinedOutput());
        NativeObfuscationHelper.assertNoFatalNativeCrash(result);
        assertTrue(result.combinedOutput().contains("=== All tests completed ==="), () -> result.combinedOutput());
    }

    @Test
    @Timeout(2)
    void nativeObfuscation_SnakeGame_headlessExceptionOnly() throws Exception {
        NativeObfuscationHelper.JarRunResult result = NativeObfuscationHelper.runCachedObfuscated(
            "SnakeGame",
            List.of("-Djava.awt.headless=true"),
            List.of(),
            Duration.ofMinutes(2)
        );

        String combined = result.combinedOutput();
        assertFalse(combined.contains("UnsatisfiedLinkError"), () -> combined);
        assertFalse(combined.contains("ClassFormatError"), () -> combined);
        assertTrue(combined.contains("HeadlessException"), () -> combined);
    }

    @Test
    void nativeObfuscation_noHelperMethodsInOutput() throws Exception {
        for (NativeObfuscationHelper.NativeArtifact artifact : NativeObfuscationHelper.ensureObfuscatedFixtures().values()) {
            List<String> offendingMethods = new ArrayList<>();
            for (ClassNode classNode : NativeObfuscationHelper.readAllClasses(artifact.outputJar())) {
                for (MethodNode method : classNode.methods) {
                    if (HELPER_NAME_PATTERN.matcher(method.name).matches()) {
                        offendingMethods.add(classNode.name + '#' + method.name + method.desc);
                    }
                }
            }
            assertTrue(offendingMethods.isEmpty(), () -> "Found helper methods in " + artifact.outputJar() + ": " + offendingMethods);
        }
    }

    @Test
    void nativeObfuscation_noClassesListInOutput() throws Exception {
        for (NativeObfuscationHelper.NativeArtifact artifact : NativeObfuscationHelper.ensureObfuscatedFixtures().values()) {
            Set<String> entries = NativeObfuscationHelper.jarEntries(artifact.outputJar());
            assertFalse(entries.contains("classes.list"), () -> "Unexpected classes.list in " + artifact.outputJar());
            assertFalse(entries.contains("neko/native/classes.list"), () -> "Unexpected native classes.list in " + artifact.outputJar());
        }
    }

    @Test
    void nativeObfuscation_onlyNekoNativeLoaderInjected() throws Exception {
        for (NativeObfuscationHelper.NativeArtifact artifact : NativeObfuscationHelper.ensureObfuscatedFixtures().values()) {
            Set<String> entries = NativeObfuscationHelper.jarEntries(artifact.outputJar());
            assertTrue(entries.contains("dev/nekoobfuscator/runtime/NekoNativeLoader.class"), () -> "Missing NekoNativeLoader in " + artifact.outputJar());
            for (String forbidden : FORBIDDEN_RUNTIME_CLASSES) {
                assertFalse(entries.contains(forbidden), () -> "Unexpected runtime class " + forbidden + " in " + artifact.outputJar());
            }
        }
    }

    @Test
    void nativeObfuscation_sharedLibraryPresent() throws Exception {
        String expectedEntry = NativeObfuscationHelper.platformLibraryEntryName();
        for (NativeObfuscationHelper.NativeArtifact artifact : NativeObfuscationHelper.ensureObfuscatedFixtures().values()) {
            Set<String> entries = NativeObfuscationHelper.jarEntries(artifact.outputJar());
            long count = entries.stream().filter(expectedEntry::equals).count();
            assertEquals(1L, count, () -> "Expected exactly one native library entry `" + expectedEntry + "` in " + artifact.outputJar() + " but found " + count + " entries: " + entries);
        }
    }

    @Test
    void nativeObfuscation_TEST_translatedMethodsAreNative() throws Exception {
        byte[] calcClass = NativeObfuscationHelper.extractEntry(NativeObfuscationHelper.artifact("TEST").outputJar(), "pack/tests/bench/Calc.class");

        NativeObfuscationHelper.assertClassHasNativeMethod(calcClass, "runAll", "()V");
        NativeObfuscationHelper.assertClassHasNativeMethod(calcClass, "call", "(I)V");
        NativeObfuscationHelper.assertClassHasNativeMethod(calcClass, "runAdd", "()V");
        NativeObfuscationHelper.assertClassHasNativeMethod(calcClass, "runStr", "()V");
    }

    @Test
    void nativeObfuscation_isIdempotent() throws Exception {
        Path workDir = NativeObfuscationHelper.nativeWorkDir();
        Path firstOutput = workDir.resolve("TEST-idempotent-1.jar");
        Path secondOutput = workDir.resolve("TEST-idempotent-2.jar");
        Path config = NativeObfuscationHelper.configsDir().resolve("native-test.yml");
        Path input = NativeObfuscationHelper.jarsDir().resolve("TEST.jar");

        NativeObfuscationHelper.obfuscateJar(input, firstOutput, config);
        NativeObfuscationHelper.obfuscateJar(input, secondOutput, config);

        long firstNativeCount = NativeObfuscationHelper.countNativeMethods(firstOutput);
        long secondNativeCount = NativeObfuscationHelper.countNativeMethods(secondOutput);

        assertTrue(firstNativeCount > 0, "Expected first obfuscated jar to contain native methods");
        assertEquals(firstNativeCount, secondNativeCount, "Expected repeated obfuscation to preserve native method count");
    }

    @Test
    void nativeObfuscation_nativeMethodsHaveOriginalSignatures() throws Exception {
        byte[] originalCalc = NativeObfuscationHelper.extractEntry(NativeObfuscationHelper.jarsDir().resolve("TEST.jar"), "pack/tests/bench/Calc.class");
        byte[] nativeCalc = NativeObfuscationHelper.extractEntry(NativeObfuscationHelper.artifact("TEST").outputJar(), "pack/tests/bench/Calc.class");

        Map<String, Integer> originalMethods = methodAccessBySignature(originalCalc, List.of(
            "runAll()V",
            "call(I)V",
            "runAdd()V",
            "runStr()V"
        ));
        Map<String, Integer> nativeMethods = methodAccessBySignature(nativeCalc, List.of(
            "runAll()V",
            "call(I)V",
            "runAdd()V",
            "runStr()V"
        ));

        assertEquals(originalMethods.keySet(), nativeMethods.keySet(), "Method signatures changed during native rewrite");
        for (Map.Entry<String, Integer> entry : nativeMethods.entrySet()) {
            assertEquals(0, entry.getValue() & Opcodes.ACC_NATIVE,
                () -> "Expected method to NOT be ACC_NATIVE (no-native-keyword refactor): " + entry.getKey());
        }
    }

    private static Map<String, Integer> methodAccessBySignature(byte[] classBytes, List<String> signatures) {
        ClassNode classNode = NativeObfuscationHelper.readClass(classBytes);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String signature : signatures) {
            MethodNode method = classNode.methods.stream()
                .filter(candidate -> (candidate.name + candidate.desc).equals(signature))
                .findFirst()
                .orElse(null);
            assertNotNull(method, () -> "Missing method `" + signature + "` in class " + classNode.name);
            result.put(signature, method.access);
        }
        return result;
    }
}
