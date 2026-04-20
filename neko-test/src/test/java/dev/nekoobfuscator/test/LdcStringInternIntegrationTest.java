package dev.nekoobfuscator.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdcStringInternIntegrationTest {
    @Test
    @Timeout(3)
    void translatedLdcStringReturnsInternedJvmCanonicalInstance() throws Exception {
        Path workDir = NativeObfuscationHelper.nativeWorkDir().resolve("ldc-string-integration");
        Files.createDirectories(workDir);
        Path sourceDir = workDir.resolve("src");
        Path classesDir = workDir.resolve("classes");
        Files.createDirectories(sourceDir);
        Files.createDirectories(classesDir);

        String literal = "Neko猫𐐷";
        Files.writeString(sourceDir.resolve("A.java"), "public class A { public static String nativeConst() { return \"" + literal + "\"; } }\n");
        Files.writeString(sourceDir.resolve("B.java"), "public class B { public static String bytecodeConst() { return \"" + literal + "\"; } }\n");
        Files.writeString(sourceDir.resolve("Main.java"), "public class Main { public static void main(String[] args) { for (int i = 0; i < 5000; i++) { if (A.nativeConst() != B.bytecodeConst()) throw new AssertionError(\"identity-mismatch-\" + i); if (i % 100 == 0) System.gc(); } System.out.println(\"ldc-string-intern-ok\"); } }\n");

        ProcessBuilder javac = new ProcessBuilder(
            "javac",
            "-d", classesDir.toString(),
            sourceDir.resolve("A.java").toString(),
            sourceDir.resolve("B.java").toString(),
            sourceDir.resolve("Main.java").toString()
        );
        javac.directory(workDir.toFile());
        javac.redirectErrorStream(true);
        Process javacProcess = javac.start();
        String javacOutput = new String(javacProcess.getInputStream().readAllBytes());
        assertEquals(0, javacProcess.waitFor(), () -> javacOutput);

        Path inputJar = workDir.resolve("ldc-string-input.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "Main");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(inputJar.toFile()), manifest)) {
            addClassEntry(jos, classesDir, "A.class");
            addClassEntry(jos, classesDir, "B.class");
            addClassEntry(jos, classesDir, "Main.class");
        }

        Path config = workDir.resolve("ldc-string-native-test.yml");
        Files.writeString(config, """
            version: 1
            preset: STANDARD

            transforms:
              controlFlowFlattening:
                enabled: false
              exceptionObfuscation:
                enabled: false
              exceptionReturn:
                enabled: false
              opaquePredicates:
                enabled: false
              stringEncryption:
                enabled: false
              numberEncryption:
                enabled: false
              invokeDynamic:
                enabled: false
              outliner:
                enabled: false
              stackObfuscation:
                enabled: false
              advancedJvm:
                enabled: false

            native:
              enabled: true
              targets:
                - LINUX_X64
              methods:
                - "A#nativeConst"
              excludePatterns:
                - "B#bytecodeConst"
                - "Main#main"
              includeAnnotated: false
              skipOnError: true
              outputPrefix: "neko_impl_"
              resourceEncryption: false

            keys:
              masterSeed: 12345
            """);

        Path outputJar = workDir.resolve("ldc-string-output.jar");
        NativeObfuscationHelper.ObfuscationRunResult obfuscation = NativeObfuscationHelper.obfuscateJar(inputJar, outputJar, config, Duration.ofMinutes(3));
        assertTrue(obfuscation.combinedOutput().contains("[NekoObfuscator] Done"), obfuscation::combinedOutput);

        Path stdout = workDir.resolve("ldc-string-run.stdout.log");
        Path stderr = workDir.resolve("ldc-string-run.stderr.log");
        NativeObfuscationHelper.JarRunResult run = NativeObfuscationHelper.runJar(outputJar, List.of(), List.of(), stdout, stderr, Duration.ofMinutes(2));
        assertEquals(0, run.exitCode(), run::combinedOutput);
        NativeObfuscationHelper.assertNoFatalNativeCrash(run);
        assertTrue(run.combinedOutput().contains("ldc-string-intern-ok"), run::combinedOutput);
    }

    private static void addClassEntry(JarOutputStream jos, Path classesDir, String classFileName) throws Exception {
        Path classFile = classesDir.resolve(classFileName);
        jos.putNextEntry(new JarEntry(classFileName));
        jos.write(Files.readAllBytes(classFile));
        jos.closeEntry();
    }
}
