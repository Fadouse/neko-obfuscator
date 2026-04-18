package dev.nekoobfuscator.test;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

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
    private static final String CALC_CLASS_ENTRY = "pack/tests/bench/Calc.class";
    private static final String NATIVE_LOADER_OWNER = "dev/nekoobfuscator/runtime/NekoNativeLoader";
    private static final String LINKAGE_ERROR_OWNER = "java/lang/LinkageError";
    private static final String LINKAGE_ERROR_MESSAGE = "please check your native library load correctly";
    private static final List<String> CALC_TRANSLATED_METHODS = List.of(
        "runAll()V",
        "call(I)V",
        "runAdd()V",
        "runStr()V"
    );
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

    @Disabled("re-enable after M3 entry patch lands — translated methods currently throw LinkageError by design")
    @Test
    @Timeout(2)
    void nativeObfuscation_TEST_calcUnder150ms() throws Exception {
        NativeObfuscationHelper.JarRunResult result = NativeObfuscationHelper.runCachedObfuscated("TEST", List.of(), List.of(), Duration.ofMinutes(2));

        assertEquals(0, result.exitCode(), () -> result.combinedOutput());
        NativeObfuscationHelper.assertNoFatalNativeCrash(result);
        long calcMillis = NativeObfuscationHelper.parseCalcMillis(result.combinedOutput());
        assertTrue(calcMillis <= 150, () -> "Expected Calc benchmark <= 150ms but got " + calcMillis + "ms\n" + result.combinedOutput());
    }

    @Disabled("re-enable after M3 entry patch lands — translated methods currently throw LinkageError by design")
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

    @Disabled("re-enable after M3 entry patch lands — translated methods currently throw LinkageError by design")
    @Test
    @Timeout(2)
    void nativeObfuscation_obfusjack_reachesCompletion() throws Exception {
        NativeObfuscationHelper.JarRunResult result = NativeObfuscationHelper.runCachedObfuscated("obfusjack", List.of(), List.of(), Duration.ofMinutes(2));

        assertEquals(0, result.exitCode(), () -> result.combinedOutput());
        NativeObfuscationHelper.assertNoFatalNativeCrash(result);
        assertTrue(result.combinedOutput().contains("=== All tests completed ==="), () -> result.combinedOutput());
    }

    @Disabled("re-enable after M3 entry patch lands — translated methods currently throw LinkageError by design")
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
    void nativeObfuscation_TEST_translatedMethodsThrowLinkageErrorBodies() throws Exception {
        byte[] calcClass = NativeObfuscationHelper.extractEntry(NativeObfuscationHelper.artifact("TEST").outputJar(), CALC_CLASS_ENTRY);

        assertTranslatedCalcClass(calcClass);
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

        assertTranslatedCalcClass(NativeObfuscationHelper.extractEntry(firstOutput, CALC_CLASS_ENTRY));
        assertTranslatedCalcClass(NativeObfuscationHelper.extractEntry(secondOutput, CALC_CLASS_ENTRY));
    }

    @Test
    void nativeObfuscation_translatedMethodsKeepOriginalSignatures() throws Exception {
        byte[] originalCalc = NativeObfuscationHelper.extractEntry(NativeObfuscationHelper.jarsDir().resolve("TEST.jar"), CALC_CLASS_ENTRY);
        byte[] nativeCalc = NativeObfuscationHelper.extractEntry(NativeObfuscationHelper.artifact("TEST").outputJar(), CALC_CLASS_ENTRY);

        Map<String, MethodNode> originalMethods = methodsBySignature(originalCalc, CALC_TRANSLATED_METHODS);
        Map<String, MethodNode> rewrittenMethods = methodsBySignature(nativeCalc, CALC_TRANSLATED_METHODS);

        assertEquals(originalMethods.keySet(), rewrittenMethods.keySet(), "Method signatures changed during native rewrite");
        for (Map.Entry<String, MethodNode> entry : rewrittenMethods.entrySet()) {
            assertThrowLinkageErrorBody(entry.getValue());
        }
        assertSingleLoadCallAndNoBindClass(NativeObfuscationHelper.requireMethod(nativeCalc, "<clinit>", "()V"));
    }

    private static Map<String, MethodNode> methodsBySignature(byte[] classBytes, List<String> signatures) {
        ClassNode classNode = NativeObfuscationHelper.readClass(classBytes);
        Map<String, MethodNode> result = new LinkedHashMap<>();
        for (String signature : signatures) {
            MethodNode method = classNode.methods.stream()
                .filter(candidate -> (candidate.name + candidate.desc).equals(signature))
                .findFirst()
                .orElse(null);
            assertNotNull(method, () -> "Missing method `" + signature + "` in class " + classNode.name);
            result.put(signature, method);
        }
        return result;
    }

    private static void assertTranslatedCalcClass(byte[] classBytes) {
        Map<String, MethodNode> translatedMethods = methodsBySignature(classBytes, CALC_TRANSLATED_METHODS);
        for (MethodNode method : translatedMethods.values()) {
            assertThrowLinkageErrorBody(method);
        }
        assertSingleLoadCallAndNoBindClass(NativeObfuscationHelper.requireMethod(classBytes, "<clinit>", "()V"));
    }

    private static void assertThrowLinkageErrorBody(MethodNode method) {
        assertFalse((method.access & Opcodes.ACC_NATIVE) != 0,
            () -> "Expected translated method to keep bytecode body: " + method.name + method.desc);
        assertEquals(3, method.maxStack, () -> "Unexpected maxStack for " + method.name + method.desc);
        assertEquals(parameterSlotCount(method.access, method.desc), method.maxLocals,
            () -> "Unexpected maxLocals for " + method.name + method.desc);
        assertTrue(method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty(),
            () -> "Translated method should not keep try/catch blocks: " + method.name + method.desc);

        List<AbstractInsnNode> instructions = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != -1) {
                instructions.add(insn);
            }
        }

        assertEquals(5, instructions.size(), () -> "Expected exact 5-opcode throw body for " + method.name + method.desc);
        assertTrue(instructions.get(0) instanceof TypeInsnNode, "First instruction must allocate LinkageError");
        TypeInsnNode newInsn = (TypeInsnNode) instructions.get(0);
        assertEquals(Opcodes.NEW, newInsn.getOpcode());
        assertEquals(LINKAGE_ERROR_OWNER, newInsn.desc);
        assertEquals(Opcodes.DUP, instructions.get(1).getOpcode());
        assertTrue(instructions.get(2) instanceof LdcInsnNode, "Third instruction must load LinkageError message");
        assertEquals(LINKAGE_ERROR_MESSAGE, ((LdcInsnNode) instructions.get(2)).cst);
        assertTrue(instructions.get(3) instanceof MethodInsnNode, "Fourth instruction must call LinkageError.<init>");
        MethodInsnNode init = (MethodInsnNode) instructions.get(3);
        assertEquals(Opcodes.INVOKESPECIAL, init.getOpcode());
        assertEquals(LINKAGE_ERROR_OWNER, init.owner);
        assertEquals("<init>", init.name);
        assertEquals("(Ljava/lang/String;)V", init.desc);
        assertEquals(Opcodes.ATHROW, instructions.get(4).getOpcode());
    }

    private static void assertSingleLoadCallAndNoBindClass(MethodNode clinit) {
        int loadCalls = 0;
        for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode methodInsn)) {
                continue;
            }
            assertFalse("bindClass".equals(methodInsn.name), () -> "Unexpected bindClass call in <clinit>: " + methodInsn.owner);
            if (methodInsn.getOpcode() == Opcodes.INVOKESTATIC
                && NATIVE_LOADER_OWNER.equals(methodInsn.owner)
                && "load".equals(methodInsn.name)
                && "()V".equals(methodInsn.desc)) {
                loadCalls++;
            }
        }
        assertEquals(1, loadCalls, "Expected exactly one NekoNativeLoader.load() invocation in <clinit>");
    }

    private static int parameterSlotCount(int access, String descriptor) {
        int slots = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argumentType : Type.getArgumentTypes(descriptor)) {
            slots += argumentType.getSize();
        }
        return slots;
    }
}
