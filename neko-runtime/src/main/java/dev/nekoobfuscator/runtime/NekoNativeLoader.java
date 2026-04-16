package dev.nekoobfuscator.runtime;

import java.io.*;
import java.nio.file.*;

/**
 * Loads platform-specific native libraries from the JAR at runtime.
 * Extracts to a temporary file and loads via System.load().
 */
public final class NekoNativeLoader {
    private static volatile boolean loaded = false;

    private NekoNativeLoader() {}

    public static synchronized void load() {
        if (loaded) return;

        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        String platform;
        if (os.contains("win")) platform = "windows";
        else if (os.contains("mac") || os.contains("darwin")) platform = "macos";
        else platform = "linux";

        String archSuffix;
        if (arch.contains("aarch64") || arch.contains("arm64")) archSuffix = "aarch64";
        else archSuffix = "x64";

        String ext;
        if (os.contains("win")) ext = ".dll";
        else if (os.contains("mac") || os.contains("darwin")) ext = ".dylib";
        else ext = ".so";

        String libName = "libneko_" + platform + "_" + archSuffix + ext;
        String resourcePath = "/neko/native/" + libName;

        try {
            InputStream is = NekoNativeLoader.class.getResourceAsStream(resourcePath);
            if (is == null) {
                throw new UnsatisfiedLinkError("Native library not found: " + resourcePath);
            }

            Path tmp = Files.createTempFile("neko_", ext);
            tmp.toFile().deleteOnExit();
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            is.close();

            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract native library: " + e.getMessage());
        }
    }
}
