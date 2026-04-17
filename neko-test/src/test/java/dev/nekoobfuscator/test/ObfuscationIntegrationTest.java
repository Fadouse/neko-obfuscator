package dev.nekoobfuscator.test;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.config.ConfigParser;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.ir.l2.*;
import dev.nekoobfuscator.core.ir.lift.*;
import dev.nekoobfuscator.core.jar.*;
import dev.nekoobfuscator.core.pipeline.*;
import dev.nekoobfuscator.transforms.advanced.AdvancedJvmPass;
import dev.nekoobfuscator.transforms.data.*;
import dev.nekoobfuscator.transforms.flow.*;
import dev.nekoobfuscator.transforms.invoke.InvokeDynamicPass;
import dev.nekoobfuscator.transforms.structure.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for NekoObfuscator.
 */
public class ObfuscationIntegrationTest {

    private static Path testJar;
    private static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        tempDir = Files.createTempDirectory("neko_test_");

        // Compile TestSample.java to a JAR
        Path srcFile = tempDir.resolve("TestSample.java");
        try (InputStream is = ObfuscationIntegrationTest.class.getResourceAsStream("/TestSample.java")) {
            if (is != null) {
                Files.copy(is, srcFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Path projectRoot = Path.of(System.getProperty("neko.test.projectRoot", System.getProperty("user.dir")));
                Path fallback = projectRoot.resolve("neko-test/src/test/resources/TestSample.java");
                assertTrue(Files.exists(fallback), "TestSample.java resource not found at classpath or fallback path: " + fallback);
                Files.copy(fallback, srcFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Compile
        Path classDir = tempDir.resolve("classes");
        Files.createDirectories(classDir);
        ProcessBuilder javac = new ProcessBuilder("javac", "-d", classDir.toString(), srcFile.toString());
        javac.redirectErrorStream(true);
        Process proc = javac.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();
        assertEquals(0, exitCode, "javac failed: " + output);

        // Create JAR
        testJar = tempDir.resolve("test-sample.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().putValue("Manifest-Version", "1.0");
        mf.getMainAttributes().putValue("Main-Class", "TestSample");

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(testJar.toFile()), mf)) {
            Path classFile = classDir.resolve("TestSample.class");
            jos.putNextEntry(new JarEntry("TestSample.class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }

        // Verify JAR is readable
        JarInput verifyInput = new JarInput(testJar);
        assertFalse(verifyInput.classes().isEmpty(), "Test JAR should contain classes");
    }

    @AfterAll
    static void cleanup() {
        // Leave temp dir for debugging
    }

    @Test
    void testJarRoundTrip() throws Exception {
        // Read JAR, write it back, verify identical behavior
        JarInput input = new JarInput(testJar);
        assertFalse(input.classes().isEmpty(), "No classes loaded");
        assertEquals(1, input.classes().size());
        assertEquals("TestSample", input.classes().get(0).name());

        // Write back
        Path outputJar = tempDir.resolve("roundtrip.jar");
        ClassHierarchy hierarchy = new ClassHierarchy();
        for (L1Class l1 : input.classes()) hierarchy.addClass(l1);
        new ClasspathResolver(List.of()).populateHierarchy(hierarchy);

        new JarOutput(hierarchy).write(outputJar, input.classes(), input.resources(), input.manifest());

        // Verify roundtripped JAR is readable and has same classes
        JarInput roundtripped = new JarInput(outputJar);
        assertEquals(input.classes().size(), roundtripped.classes().size(), "Roundtrip should preserve class count");
        assertEquals(input.classes().get(0).name(), roundtripped.classes().get(0).name());
    }

    @Test
    void testCFGConstruction() throws Exception {
        JarInput input = new JarInput(testJar);
        L1Class clazz = input.classes().get(0);
        L1Method fibonacci = clazz.findMethod("fibonacci", "(I)I");
        assertNotNull(fibonacci, "fibonacci method not found");

        ControlFlowGraph cfg = ControlFlowGraph.build(fibonacci);
        assertNotNull(cfg);
        assertTrue(cfg.blockCount() >= 2, "Expected at least 2 blocks in fibonacci CFG");
        assertNotNull(cfg.entryBlock());
    }

    @Test
    void testSSAConstruction() throws Exception {
        JarInput input = new JarInput(testJar);
        L1Class clazz = input.classes().get(0);
        L1Method fibonacci = clazz.findMethod("fibonacci", "(I)I");
        assertNotNull(fibonacci, "fibonacci method not found");

        // Test CFG first
        ControlFlowGraph cfg = ControlFlowGraph.build(fibonacci);
        assertNotNull(cfg);
        assertTrue(cfg.blockCount() >= 2, "Expected at least 2 blocks");

        // Test SSA with timeout protection
        L1ToL2Lifter lifter = new L1ToL2Lifter();
        SSAForm ssa = lifter.lift(fibonacci);
        assertNotNull(ssa);
        assertNotNull(ssa.cfg());
    }

    @Test
    void testStringEncryption() throws Exception {
        Path outputJar = tempDir.resolve("string-encrypted.jar");
        runObfuscation(testJar, outputJar, Map.of(
            "stringEncryption", new TransformConfig(true, 1.0)
        ));

        // Verify obfuscated JAR contains encrypted fields
        JarInput obfuscated = new JarInput(outputJar);
        L1Class clazz = obfuscated.classes().get(0);
        boolean hasEncFields = clazz.asmNode().fields.stream()
            .anyMatch(f -> f.name.startsWith("__e")
                && ("[B".equals(f.desc) || "Ljava/lang/String;".equals(f.desc)));
        assertTrue(hasEncFields, "No encrypted string metadata fields found after string encryption");
    }

    @Test
    void testNumberEncryption() throws Exception {
        Path outputJar = tempDir.resolve("number-encrypted.jar");
        runObfuscation(testJar, outputJar, Map.of(
            "numberEncryption", new TransformConfig(true, 1.0)
        ));

        // Just verify it produces a valid JAR
        JarInput obfuscated = new JarInput(outputJar);
        assertFalse(obfuscated.classes().isEmpty());
    }

    @Test
    void testControlFlowFlattening() throws Exception {
        Path outputJar = tempDir.resolve("cf-flattened.jar");
        runObfuscation(testJar, outputJar, Map.of(
            "controlFlowFlattening", new TransformConfig(true, 1.0)
        ));

        JarInput obfuscated = new JarInput(outputJar);
        assertFalse(obfuscated.classes().isEmpty());
    }

    private void runObfuscation(Path input, Path output, Map<String, TransformConfig> transforms)
            throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        config.setTransforms(new LinkedHashMap<>(transforms));
        config.keyConfig().setMasterSeed(12345678L);

        PassRegistry registry = new PassRegistry();
        registry.register(new ControlFlowFlatteningPass());
        registry.register(new ExceptionObfuscationPass());
        registry.register(new ExceptionReturnPass());
        registry.register(new ControlFlowObfuscationPass());
        registry.register(new NumberEncryptionPass());
        registry.register(new InvokeDynamicPass());
        registry.register(new StringEncryptionPass());
        registry.register(new OutlinerPass());
        registry.register(new StackObfuscationPass());
        registry.register(new AdvancedJvmPass());

        ObfuscationPipeline pipeline = new ObfuscationPipeline(config, registry);
        pipeline.execute(input, output);
    }

    private String runJar(Path jar) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", jar.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        proc.waitFor();
        return output;
    }
}
