package dev.nekoobfuscator.native_.codegen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Builds native libraries from generated C and assembly source files using zig cc.
 */
public final class NativeBuildEngine {
    private static final Logger log = LoggerFactory.getLogger(NativeBuildEngine.class);
    private static final String NATIVE_DEBUG_PROPERTY = "neko.native.debug";
    private static final String NATIVE_DEBUG_ENV = "NEKO_NATIVE_BUILD_DEBUG";

    private final String zigPath;

    public NativeBuildEngine(String zigPath) {
        this.zigPath = zigPath;
    }

    private static boolean isNativeDebugEnabled() {
        String property = System.getProperty(NATIVE_DEBUG_PROPERTY);
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        String env = System.getenv(NATIVE_DEBUG_ENV);
        return env != null && Boolean.parseBoolean(env);
    }

    public Map<String, byte[]> build(String cSource, String headerSource, List<String> targets) throws IOException {
        Path tempDir = Files.createTempDirectory("neko_native_");
        try {
            Path srcFile = tempDir.resolve("neko_native.c");
            Path hdrFile = tempDir.resolve("neko_native.h");
            Files.writeString(srcFile, cSource);
            Files.writeString(hdrFile, headerSource);
            Files.writeString(Path.of("/tmp/neko_native_debug.c"), cSource);
            Files.writeString(Path.of("/tmp/neko_native_debug.h"), headerSource);
            return build(List.of(srcFile), targets, tempDir);
        } finally {
            deleteRecursive(tempDir);
        }
    }

    public Map<String, byte[]> build(Collection<Path> sourceFiles, List<String> targets) throws IOException {
        Path tempDir = Files.createTempDirectory("neko_native_");
        try {
            return build(sourceFiles, targets, tempDir);
        } finally {
            deleteRecursive(tempDir);
        }
    }

    private Map<String, byte[]> build(Collection<Path> sourceFiles, List<String> targets, Path tempDir) throws IOException {
        List<Path> normalizedSourceFiles = normalizeSourceFiles(sourceFiles);
        int cSourceCount = 0;
        int assemblySourceCount = 0;
        for (Path sourceFile : normalizedSourceFiles) {
            if (isAssemblySourceFile(sourceFile)) {
                assemblySourceCount++;
            } else {
                cSourceCount++;
            }
        }

        Map<String, byte[]> results = new LinkedHashMap<>();

        String javaHome = System.getProperty("java.home");
        Path jniInclude = Path.of(javaHome, "include");
        Path jniPlatformInclude = findPlatformInclude(jniInclude);

        for (String target : targets) {
            String zigTarget = mapTarget(target);
            String ext = target.contains("WINDOWS") ? ".dll" : target.contains("MACOS") ? ".dylib" : ".so";
            String libName = "libneko_" + target.toLowerCase() + ext;
            Path outputLib = tempDir.resolve(libName);

            List<String> cmd = new ArrayList<>(List.of(
                zigPath, "cc",
                "-shared", "-Oz", "-std=c11", "-Wall", "-Wextra",
                "-target", zigTarget,
                "-I", jniInclude.toString()
            ));
            if (isNativeDebugEnabled()) {
                // `./gradlew :neko-cli:installDist -PnekoNativeDebug=true` injects `-Dneko.native.debug=true`
                // into the installed CLI launcher so obfuscation runs emit debug-enabled native builds.
                cmd.add("-DNEKO_DEBUG_ENABLED=1");
            }
            if (jniPlatformInclude != null) {
                cmd.addAll(List.of("-I", jniPlatformInclude.toString()));
            }
            cmd.addAll(List.of("-o", outputLib.toString()));
            for (Path sourceFile : normalizedSourceFiles) {
                cmd.add(sourceFile.toString());
            }

            if (assemblySourceCount > 0) {
                log.info("zig cc: compiling {} .c + {} .S files for target {}", cSourceCount, assemblySourceCount, zigTarget);
            }
            log.info("Building native for {}: {}", target, String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exitCode;
            try { exitCode = proc.waitFor(); } catch (InterruptedException e) { exitCode = -1; }

            if (exitCode == 0 && Files.exists(outputLib)) {
                results.put("neko/native/" + libName, Files.readAllBytes(outputLib));
                log.info("Built {} ({} bytes)", libName, Files.size(outputLib));
            } else {
                log.warn("Failed to build for {}: exit={}\n{}", target, exitCode, output);
            }
        }

        return results;
    }

    private String mapTarget(String target) {
        return switch (target) {
            case "LINUX_X64" -> "x86_64-linux-gnu";
            case "LINUX_AARCH64" -> "aarch64-linux-gnu";
            case "WINDOWS_X64" -> "x86_64-windows-gnu";
            case "MACOS_X64" -> "x86_64-macos-none";
            case "MACOS_AARCH64" -> "aarch64-macos-none";
            default -> target.toLowerCase();
        };
    }

    private Path findPlatformInclude(Path jniInclude) {
        String os = System.getProperty("os.name").toLowerCase();
        String platform = os.contains("win") ? "win32" : os.contains("mac") ? "darwin" : "linux";
        Path p = jniInclude.resolve(platform);
        return Files.isDirectory(p) ? p : null;
    }

    private List<Path> normalizeSourceFiles(Collection<Path> sourceFiles) {
        Objects.requireNonNull(sourceFiles, "sourceFiles");
        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("No native source files provided");
        }

        List<Path> normalizedSourceFiles = new ArrayList<>(sourceFiles.size());
        for (Path sourceFile : sourceFiles) {
            Path normalizedSourceFile = Objects.requireNonNull(sourceFile, "sourceFile").toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalizedSourceFile)) {
                throw new IllegalArgumentException("Native source file does not exist: " + normalizedSourceFile);
            }
            if (!isSupportedSourceFile(normalizedSourceFile)) {
                throw new IllegalArgumentException("Unsupported native source file extension: " + normalizedSourceFile);
            }
            normalizedSourceFiles.add(normalizedSourceFile);
        }
        return normalizedSourceFiles;
    }

    private boolean isSupportedSourceFile(Path sourceFile) {
        String fileName = sourceFile.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".c") || fileName.endsWith(".s");
    }

    private boolean isAssemblySourceFile(Path sourceFile) {
        return sourceFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".s");
    }

    private void deleteRecursive(Path dir) {
        // Intentionally left in place for debugging generated native artifacts.
    }
}
