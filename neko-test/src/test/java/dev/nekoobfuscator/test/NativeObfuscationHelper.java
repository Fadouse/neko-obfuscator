package dev.nekoobfuscator.test;

import org.junit.jupiter.api.Assertions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NativeObfuscationHelper {
    private static final Duration DEFAULT_OBFUSCATION_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofMinutes(2);
    private static final Pattern CALC_MS_PATTERN = Pattern.compile("Calc:\\s+(\\d+)ms");
    private static final Map<String, NativeArtifact> ARTIFACTS = new LinkedHashMap<>();
    private static final Map<String, JarRunResult> CACHED_RUNS = new LinkedHashMap<>();

    private NativeObfuscationHelper() {}

    static synchronized Map<String, NativeArtifact> ensureObfuscatedFixtures() throws Exception {
        boolean rebuild = ARTIFACTS.isEmpty()
            || ARTIFACTS.values().stream().anyMatch(artifact -> !Files.exists(artifact.outputJar()));
        if (rebuild) {
            ARTIFACTS.clear();
            CACHED_RUNS.clear();
            ARTIFACTS.put("TEST", obfuscateFixture("TEST", "TEST.jar", "TEST-native.jar", "native-test.yml"));
            ARTIFACTS.put("SnakeGame", obfuscateFixture("SnakeGame", "SnakeGame.jar", "SnakeGame-native.jar", "native-snake.yml"));
            ARTIFACTS.put("obfusjack", obfuscateFixture("obfusjack", "obfusjack-test21.jar", "obfusjack-native.jar", "native-obfusjack.yml"));
        }
        return Map.copyOf(ARTIFACTS);
    }

    static synchronized NativeArtifact artifact(String name) throws Exception {
        NativeArtifact artifact = ensureObfuscatedFixtures().get(name);
        Assertions.assertNotNull(artifact, "Missing native artifact for fixture: " + name);
        return artifact;
    }

    static synchronized JarRunResult runCachedOriginal(String fixture, List<String> jvmArgs, List<String> appArgs, Duration timeout) throws Exception {
        String key = "original:" + fixture + ':' + String.join(" ", jvmArgs) + ':' + String.join(" ", appArgs);
        JarRunResult existing = CACHED_RUNS.get(key);
        if (existing != null) {
            return existing;
        }

        Path jar = switch (fixture) {
            case "TEST" -> jarsDir().resolve("TEST.jar");
            case "SnakeGame" -> jarsDir().resolve("SnakeGame.jar");
            case "obfusjack" -> jarsDir().resolve("obfusjack-test21.jar");
            default -> throw new IllegalArgumentException("Unknown fixture: " + fixture);
        };
        Path stdout = nativeWorkDir().resolve(key.replace(':', '_') + ".stdout.log");
        Path stderr = nativeWorkDir().resolve(key.replace(':', '_') + ".stderr.log");
        JarRunResult result = runJar(jar, jvmArgs, appArgs, stdout, stderr, timeout);
        CACHED_RUNS.put(key, result);
        return result;
    }

    static synchronized JarRunResult runCachedObfuscated(String fixture, List<String> jvmArgs, List<String> appArgs, Duration timeout) throws Exception {
        String key = "native:" + fixture + ':' + String.join(" ", jvmArgs) + ':' + String.join(" ", appArgs);
        JarRunResult existing = CACHED_RUNS.get(key);
        if (existing != null) {
            return existing;
        }

        NativeArtifact artifact = artifact(fixture);
        Path stdout = nativeWorkDir().resolve(key.replace(':', '_') + ".stdout.log");
        Path stderr = nativeWorkDir().resolve(key.replace(':', '_') + ".stderr.log");
        JarRunResult result = runJar(artifact.outputJar(), jvmArgs, appArgs, stdout, stderr, timeout);
        CACHED_RUNS.put(key, result);
        return result;
    }

    static ObfuscationRunResult obfuscateJar(Path input, Path output, Path configYaml) throws Exception {
        return obfuscateJar(input, output, configYaml, DEFAULT_OBFUSCATION_TIMEOUT);
    }

    static ObfuscationRunResult obfuscateJar(Path input, Path output, Path configYaml, Duration timeout) throws Exception {
        Files.createDirectories(Objects.requireNonNull(output.getParent(), "output parent"));
        String stem = stripExtension(output.getFileName().toString());
        Path stdout = output.resolveSibling(stem + ".obfuscate.stdout.log");
        Path stderr = output.resolveSibling(stem + ".obfuscate.stderr.log");

        List<String> command = List.of(
            cliPath().toString(),
            "obfuscate",
            "-c", configYaml.toString(),
            "-i", input.toString(),
            "-o", output.toString()
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectRoot().toFile());
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());

        long start = System.nanoTime();
        Process process = processBuilder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            Assertions.fail("Timed out obfuscating " + input + " after " + timeout);
        }

        int exitCode = process.exitValue();
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        String stdoutText = readIfExists(stdout);
        String stderrText = readIfExists(stderr);

        Assertions.assertEquals(0, exitCode, () -> "CLI obfuscation failed for " + input + "\nSTDOUT:\n" + stdoutText + "\nSTDERR:\n" + stderrText);
        Assertions.assertTrue(Files.exists(output), () -> "Expected obfuscated jar to exist: " + output + "\nSTDOUT:\n" + stdoutText + "\nSTDERR:\n" + stderrText);

        return new ObfuscationRunResult(output, stdout, stderr, stdoutText, stderrText, exitCode, duration);
    }

    static JarRunResult runJar(Path jar, List<String> appArgs, Path stdout, Path stderr, Duration timeout) throws Exception {
        return runJar(jar, List.of(), appArgs, stdout, stderr, timeout);
    }

    static JarRunResult runJar(Path jar, List<String> jvmArgs, List<String> appArgs, Path stdout, Path stderr, Duration timeout) throws Exception {
        Files.createDirectories(Objects.requireNonNull(stdout.getParent(), "stdout parent"));
        Files.createDirectories(Objects.requireNonNull(stderr.getParent(), "stderr parent"));

        List<String> command = new ArrayList<>();
        command.add("java");
        command.addAll(jvmArgs);
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(appArgs);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectRoot().toFile());
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());

        long start = System.nanoTime();
        Process process = processBuilder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            Assertions.fail("Timed out running jar " + jar + " after " + timeout);
        }

        int exitCode = process.exitValue();
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        return new JarRunResult(jar, stdout, stderr, readIfExists(stdout), readIfExists(stderr), exitCode, duration);
    }

    static byte[] extractEntry(Path jar, String entryName) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entry = jarFile.getJarEntry(entryName);
            Assertions.assertNotNull(entry, "Missing JAR entry " + entryName + " in " + jar);
            try (var inputStream = jarFile.getInputStream(entry)) {
                return inputStream.readAllBytes();
            }
        }
    }

    static Set<String> jarEntries(Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Set<String> entries = new TreeSet<>();
            jarFile.stream().forEach(entry -> entries.add(entry.getName()));
            return entries;
        }
    }

    static List<ClassNode> readAllClasses(Path jar) throws IOException {
        List<ClassNode> classes = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (var inputStream = jarFile.getInputStream(entry)) {
                    classes.add(readClass(inputStream.readAllBytes()));
                }
            }
        }
        classes.sort(Comparator.comparing(classNode -> classNode.name));
        return classes;
    }

    static ClassNode readClass(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return classNode;
    }

    static MethodNode requireMethod(byte[] classBytes, String methodName, String descriptor) {
        ClassNode classNode = readClass(classBytes);
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(methodName) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        Assertions.fail("Method " + methodName + descriptor + " not found in class " + classNode.name);
        return null;
    }

    static void assertClassHasNativeMethod(byte[] classBytes, String methodName, String descriptor) {
        MethodNode method = requireMethod(classBytes, methodName, descriptor);
        Assertions.assertTrue((method.access & Opcodes.ACC_NATIVE) != 0,
            () -> "Expected method " + methodName + descriptor + " to be ACC_NATIVE");
    }

    static long countNativeMethods(Path jar) throws IOException {
        long count = 0;
        for (ClassNode classNode : readAllClasses(jar)) {
            for (MethodNode method : classNode.methods) {
                if ((method.access & Opcodes.ACC_NATIVE) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    static long parseCalcMillis(String output) {
        Matcher matcher = CALC_MS_PATTERN.matcher(output);
        Assertions.assertTrue(matcher.find(), () -> "Missing Calc timing line in output:\n" + output);
        return Long.parseLong(matcher.group(1));
    }

    static String combinedOutput(JarRunResult result) {
        return result.stdout() + result.stderr();
    }

    static void assertNoFatalNativeCrash(JarRunResult result) {
        String combined = combinedOutput(result);
        Assertions.assertFalse(combined.contains("SIGSEGV"), () -> "Detected SIGSEGV in process output:\n" + combined);
        Assertions.assertFalse(combined.contains("A fatal error has been detected by the Java Runtime Environment"),
            () -> "Detected fatal JVM error in process output:\n" + combined);
    }

    static String platformLibraryEntryName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        String platform = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos" : "linux";
        String archSuffix = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x64";
        String ext = os.contains("win") ? ".dll" : os.contains("mac") || os.contains("darwin") ? ".dylib" : ".so";
        return "neko/native/libneko_" + platform + '_' + archSuffix + ext;
    }

    static Path projectRoot() {
        return Path.of(requiredProperty("neko.test.projectRoot"));
    }

    static Path jarsDir() {
        return Path.of(requiredProperty("neko.test.jarsDir"));
    }

    static Path configsDir() {
        return Path.of(requiredProperty("neko.test.configsDir"));
    }

    static Path cliPath() {
        Path path = Path.of(requiredProperty("neko.test.cliPath"));
        Assertions.assertTrue(Files.exists(path), () -> "CLI not found at " + path + ". Ensure :neko-cli:installDist ran.");
        return path;
    }

    static Path nativeWorkDir() {
        Path path = Path.of(requiredProperty("neko.test.nativeWorkDir"));
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create native test work dir: " + path, e);
        }
        return path;
    }

    private static NativeArtifact obfuscateFixture(String fixtureName, String inputJarName, String outputJarName, String configName) throws Exception {
        Path input = jarsDir().resolve(inputJarName);
        Path output = nativeWorkDir().resolve(outputJarName);
        Path config = configsDir().resolve(configName);
        ObfuscationRunResult result = obfuscateJar(input, output, config);
        return new NativeArtifact(fixtureName, input, output, config, result.stdoutPath(), result.stderrPath(), result.duration());
    }

    private static String requiredProperty(String key) {
        String value = System.getProperty(key);
        Assertions.assertNotNull(value, "Missing system property: " + key);
        return value;
    }

    private static String readIfExists(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path) : "";
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }

    record NativeArtifact(
        String fixtureName,
        Path inputJar,
        Path outputJar,
        Path configYaml,
        Path obfuscationStdout,
        Path obfuscationStderr,
        Duration obfuscationDuration
    ) {}

    record ObfuscationRunResult(
        Path outputJar,
        Path stdoutPath,
        Path stderrPath,
        String stdout,
        String stderr,
        int exitCode,
        Duration duration
    ) {
        String combinedOutput() {
            return stdout + stderr;
        }
    }

    record JarRunResult(
        Path jar,
        Path stdoutPath,
        Path stderrPath,
        String stdout,
        String stderr,
        int exitCode,
        Duration duration
    ) {
        String combinedOutput() {
            return stdout + stderr;
        }
    }
}
