package dev.nekoobfuscator.test;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeObfStackTraceTest {
    private static final Pattern NATIVE_STAGE_COUNTS = Pattern.compile("Native stage: translated=(\\d+) rejected=(\\d+)");

    @Test
    @Disabled("W11-M5i deferred: translated cached pending exceptions still crash before Throwable.getStackTrace() can observe frames")
    @Timeout(5)
    void translatedCachedExceptionsPreserveJavaStackFrames() throws Exception {
        Path workDir = NativeObfuscationHelper.nativeWorkDir().resolve("stack-trace");
        Files.createDirectories(workDir);
        Path src = workDir.resolve("src");
        Path classes = workDir.resolve("classes");
        Files.createDirectories(src.resolve("pack/stacktrace"));
        Files.createDirectories(classes);

        Files.writeString(src.resolve("pack/stacktrace/StackVictim.java"), """
            package pack.stacktrace;

            public class StackVictim {
              public int probe(int[] values, int index) {
                return values[index];
              }

              public int divide(Integer value, int divisor) {
                return value / divisor;
              }
            }
            """);
        Files.writeString(src.resolve("pack/stacktrace/StackHarness.java"), """
            package pack.stacktrace;

            public class StackHarness {
              public static StackTraceElement[] captureAioobe() {
                StackVictim victim = new StackVictim();
                try {
                  victim.probe(new int[3], 99);
                  throw new AssertionError("probe unexpectedly returned");
                } catch (ArrayIndexOutOfBoundsException expected) {
                  return expected.getStackTrace();
                }
              }

              public static StackTraceElement[] captureNpe() {
                StackVictim victim = new StackVictim();
                try {
                  victim.divide(null, 7);
                  throw new AssertionError("divide unexpectedly returned");
                } catch (NullPointerException expected) {
                  return expected.getStackTrace();
                }
              }
            }
            """);
        Files.writeString(src.resolve("pack/stacktrace/Main.java"), """
            package pack.stacktrace;

            public class Main {
              public static void main(String[] args) {
                assertFrames("AIOOBE", StackHarness.captureAioobe(), "probe", "captureAioobe");
                assertFrames("NPE", StackHarness.captureNpe(), "divide", "captureNpe");
                System.out.println("stack-trace-driver-ok");
              }

              private static void assertFrames(String label, StackTraceElement[] trace, String victimMethod, String harnessMethod) {
                if (trace == null || trace.length == 0) {
                  throw new AssertionError(label + " stack trace is empty");
                }
                boolean sawVictim = false;
                boolean sawHarness = false;
                StringBuilder rendered = new StringBuilder();
                for (StackTraceElement frame : trace) {
                  if (rendered.length() > 0) rendered.append("|");
                  rendered.append(frame.getClassName()).append("#").append(frame.getMethodName());
                  if (frame.getClassName().equals("pack.stacktrace.StackVictim") && frame.getMethodName().equals(victimMethod)) {
                    sawVictim = true;
                  }
                  if (frame.getClassName().equals("pack.stacktrace.StackHarness") && frame.getMethodName().equals(harnessMethod)) {
                    sawHarness = true;
                  }
                }
                System.out.println("STACKTRACE_" + label + "=" + rendered);
                if (!sawVictim) {
                  throw new AssertionError(label + " stack trace missing StackVictim." + victimMethod + ": " + rendered);
                }
                if (!sawHarness) {
                  throw new AssertionError(label + " stack trace missing StackHarness." + harnessMethod + ": " + rendered);
                }
              }
            }
            """);

        compile(workDir, classes,
            src.resolve("pack/stacktrace/StackVictim.java"),
            src.resolve("pack/stacktrace/StackHarness.java"),
            src.resolve("pack/stacktrace/Main.java"));

        Path inputJar = workDir.resolve("stack-trace-input.jar");
        writeJar(inputJar, "pack.stacktrace.Main", classes,
            "pack/stacktrace/StackVictim.class",
            "pack/stacktrace/StackHarness.class",
            "pack/stacktrace/Main.class");

        Path config = workDir.resolve("stack-trace-native.yml");
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
                - "pack/stacktrace/StackVictim#probe"
                - "pack/stacktrace/StackVictim#divide"
              excludePatterns:
                - "pack/stacktrace/Main#main"
                - "pack/stacktrace/StackHarness#*"
              includeAnnotated: false
              skipOnError: true
              outputPrefix: "neko_impl_"
              resourceEncryption: false

            keys:
              masterSeed: 12345
            """);

        Path outputJar = workDir.resolve("stack-trace-output.jar");
        NativeObfuscationHelper.ObfuscationRunResult obfuscation = obfuscateJarWithNativeDebug(inputJar, outputJar, config, Duration.ofMinutes(3));
        assertTrue(obfuscation.combinedOutput().contains("[NekoObfuscator] Done"), obfuscation::combinedOutput);
        assertTranslatedCount(obfuscation.combinedOutput(), 2);

        NativeObfuscationHelper.JarRunResult run = runJar(workDir, outputJar);
        assertEquals(0, run.exitCode(), run::combinedOutput);
        NativeObfuscationHelper.assertNoFatalNativeCrash(run);
        assertTrue(run.stdout().contains("stack-trace-driver-ok"), run::combinedOutput);
        assertTrue(run.stdout().contains("STACKTRACE_AIOOBE="), run::combinedOutput);
        assertTrue(run.stdout().contains("STACKTRACE_NPE="), run::combinedOutput);
    }

    private static void assertTranslatedCount(String output, int expected) {
        Matcher matcher = NATIVE_STAGE_COUNTS.matcher(output);
        assertTrue(matcher.find(), () -> "Missing native stage counts in output:\n" + output);
        assertEquals(expected, Integer.parseInt(matcher.group(1)), () -> output);
        assertEquals(0, Integer.parseInt(matcher.group(2)), () -> output);
    }

    private static void compile(Path workDir, Path classesDir, Path... sources) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("javac");
        command.add("-d");
        command.add(classesDir.toString());
        for (Path source : sources) command.add(source.toString());
        ProcessBuilder javac = new ProcessBuilder(command);
        javac.directory(workDir.toFile());
        javac.redirectErrorStream(true);
        Process process = javac.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), () -> output);
    }

    private static void writeJar(Path jar, String mainClass, Path classesDir, String... entries) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (mainClass != null) manifest.getMainAttributes().putValue("Main-Class", mainClass);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()), manifest)) {
            for (String entry : entries) {
                jos.putNextEntry(new JarEntry(entry));
                jos.write(Files.readAllBytes(classesDir.resolve(entry)));
                jos.closeEntry();
            }
        }
    }

    private static NativeObfuscationHelper.JarRunResult runJar(Path workDir, Path jar) throws Exception {
        Path stdout = workDir.resolve("stack-trace-run.stdout.log");
        Path stderr = workDir.resolve("stack-trace-run.stderr.log");
        List<String> command = List.of("java", "-jar", jar.toString());
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(NativeObfuscationHelper.projectRoot().toFile());
        processBuilder.environment().put("NEKO_DEBUG", "1");
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());
        Process process = processBuilder.start();
        boolean finished = process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("Timed out running stack trace jar");
        }
        return new NativeObfuscationHelper.JarRunResult(
            jar,
            stdout,
            stderr,
            Files.readString(stdout),
            Files.readString(stderr),
            process.exitValue(),
            Duration.ZERO
        );
    }

    private static NativeObfuscationHelper.ObfuscationRunResult obfuscateJarWithNativeDebug(Path input, Path output, Path config, Duration timeout) throws Exception {
        Files.createDirectories(output.getParent());
        Path stdout = output.resolveSibling("stack-trace-obfuscate.stdout.log");
        Path stderr = output.resolveSibling("stack-trace-obfuscate.stderr.log");
        List<String> command = List.of(
            NativeObfuscationHelper.cliPath().toString(),
            "obfuscate",
            "-c", config.toString(),
            "-i", input.toString(),
            "-o", output.toString()
        );
        long start = System.nanoTime();
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(NativeObfuscationHelper.projectRoot().toFile());
        processBuilder.environment().put("NEKO_NATIVE_BUILD_DEBUG", "true");
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());
        Process process = processBuilder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("Timed out obfuscating stack trace fixture");
        }
        String stdoutText = Files.exists(stdout) ? Files.readString(stdout) : "";
        String stderrText = Files.exists(stderr) ? Files.readString(stderr) : "";
        assertEquals(0, process.exitValue(), () -> "CLI obfuscation failed\nSTDOUT:\n" + stdoutText + "\nSTDERR:\n" + stderrText);
        assertTrue(Files.exists(output), () -> "Expected obfuscated jar to exist: " + output);
        return new NativeObfuscationHelper.ObfuscationRunResult(
            output,
            stdout,
            stderr,
            stdoutText,
            stderrText,
            process.exitValue(),
            Duration.ofNanos(System.nanoTime() - start)
        );
    }
}
