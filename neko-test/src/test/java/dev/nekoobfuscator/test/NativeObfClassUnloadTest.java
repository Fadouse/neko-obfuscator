package dev.nekoobfuscator.test;

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

class NativeObfClassUnloadTest {
    private static final Pattern CLASS_UNLOAD_TRACE = Pattern.compile("neko_class_unload_observed=(\\d+)");

    @Test
    @Timeout(4)
    void translatedDispatchRescanObservesClassLoaderUnload() throws Exception {
        Path workDir = NativeObfuscationHelper.nativeWorkDir().resolve("class-unload");
        Files.createDirectories(workDir);
        Path appSrc = workDir.resolve("app-src");
        Path appClasses = workDir.resolve("app-classes");
        Path victimSrc = workDir.resolve("victim-src");
        Path victimClasses = workDir.resolve("victim-classes");
        Files.createDirectories(appSrc);
        Files.createDirectories(appClasses);
        Files.createDirectories(victimSrc);
        Files.createDirectories(victimClasses);

        Files.writeString(appSrc.resolve("Ping.java"), "public class Ping { public static int tick(int value) { return value + 1; } }\n");
        Files.writeString(appSrc.resolve("Main.java"), """
            import java.lang.ref.WeakReference;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.nio.file.Path;

            public class Main {
              public static void main(String[] args) throws Exception {
                URLClassLoader loader = new URLClassLoader(new URL[] { Path.of(args[0]).toUri().toURL() }, null);
                Class<?> victim = Class.forName("Victim", true, loader);
                WeakReference<ClassLoader> loaderRef = new WeakReference<>(loader);
                WeakReference<Class<?>> classRef = new WeakReference<>(victim);
                for (int i = 0; i < 1500; i++) {
                  if (Ping.tick(i) != i + 1) throw new AssertionError("warmup-" + i);
                }
                victim = null;
                loader.close();
                loader = null;
                for (int i = 0; i < 80 && (loaderRef.get() != null || classRef.get() != null); i++) {
                  System.gc();
                  System.runFinalization();
                  Thread.sleep(10L);
                }
                for (int i = 0; i < 5000; i++) {
                  if (Ping.tick(i) != i + 1) throw new AssertionError("rescan-" + i);
                }
                System.out.println("class-unload-driver-ok loaderCleared=" + (loaderRef.get() == null) + " classCleared=" + (classRef.get() == null));
              }
            }
            """);
        Files.writeString(victimSrc.resolve("Victim.java"), "public class Victim { static final byte[] PAYLOAD = new byte[4096]; }\n");

        compile(workDir, appClasses, appSrc.resolve("Ping.java"), appSrc.resolve("Main.java"));
        compile(workDir, victimClasses, victimSrc.resolve("Victim.java"));

        Path victimJar = workDir.resolve("victim.jar");
        writeJar(victimJar, null, victimClasses, "Victim.class");

        Path inputJar = workDir.resolve("class-unload-input.jar");
        writeJar(inputJar, "Main", appClasses, "Ping.class", "Main.class");

        Path config = workDir.resolve("class-unload-native.yml");
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
                - "Ping#tick"
              excludePatterns:
                - "Main#main"
              includeAnnotated: false
              skipOnError: true
              outputPrefix: "neko_impl_"
              resourceEncryption: false

            keys:
              masterSeed: 12345
            """);

        Path outputJar = workDir.resolve("class-unload-output.jar");
        NativeObfuscationHelper.ObfuscationRunResult obfuscation = obfuscateJarWithNativeDebug(inputJar, outputJar, config, Duration.ofMinutes(3));
        assertTrue(obfuscation.combinedOutput().contains("[NekoObfuscator] Done"), obfuscation::combinedOutput);

        NativeObfuscationHelper.JarRunResult run = runJarWithDebug(workDir, outputJar, victimJar);
        assertEquals(0, run.exitCode(), run::combinedOutput);
        NativeObfuscationHelper.assertNoFatalNativeCrash(run);
        assertTrue(run.combinedOutput().contains("class-unload-driver-ok"), run::combinedOutput);
        Matcher matcher = CLASS_UNLOAD_TRACE.matcher(run.combinedOutput());
        assertTrue(matcher.find(), () -> "Missing class unload trace in output:\n" + run.combinedOutput());
        assertTrue(Integer.parseInt(matcher.group(1)) >= 1, () -> run.combinedOutput());
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

    private static NativeObfuscationHelper.JarRunResult runJarWithDebug(Path workDir, Path jar, Path victimJar) throws Exception {
        Path stdout = workDir.resolve("class-unload-run.stdout.log");
        Path stderr = workDir.resolve("class-unload-run.stderr.log");
        List<String> command = List.of("java", "-XX:+ClassUnloading", "-jar", jar.toString(), victimJar.toString());
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(NativeObfuscationHelper.projectRoot().toFile());
        processBuilder.environment().put("NEKO_DEBUG", "1");
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());
        Process process = processBuilder.start();
        boolean finished = process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("Timed out running class unload jar");
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
        Path stdout = output.resolveSibling("class-unload-obfuscate.stdout.log");
        Path stderr = output.resolveSibling("class-unload-obfuscate.stderr.log");
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
            throw new AssertionError("Timed out obfuscating class unload fixture");
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
