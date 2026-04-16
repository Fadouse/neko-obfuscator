package dev.nekoobfuscator.native_.codegen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Builds native libraries from generated C source files using zig cc.
 */
public final class NativeBuildEngine {
    private static final Logger log = LoggerFactory.getLogger(NativeBuildEngine.class);

    private final String zigPath;

    public NativeBuildEngine(String zigPath) {
        this.zigPath = zigPath;
    }

    public Map<String, byte[]> build(String cSource, String headerSource, List<String> targets) throws IOException {
        Path tempDir = Files.createTempDirectory("neko_native_");
        Map<String, byte[]> results = new LinkedHashMap<>();
        try {
            Path srcFile = tempDir.resolve("neko_native.c");
            Path hdrFile = tempDir.resolve("neko_native.h");
            Files.writeString(srcFile, cSource);
            Files.writeString(hdrFile, headerSource);

            // Find JNI headers
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
                    "-shared", "-O2",
                    "-target", zigTarget,
                    "-I", jniInclude.toString()
                ));
                if (jniPlatformInclude != null) {
                    cmd.addAll(List.of("-I", jniPlatformInclude.toString()));
                }
                cmd.addAll(List.of(
                    "-o", outputLib.toString(),
                    srcFile.toString()
                ));

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
        } finally {
            deleteRecursive(tempDir);
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

    private void deleteRecursive(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) {} });
        } catch (IOException e) {}
    }
}
