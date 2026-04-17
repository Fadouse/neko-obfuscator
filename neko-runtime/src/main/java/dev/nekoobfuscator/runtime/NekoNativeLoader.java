package dev.nekoobfuscator.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads platform-specific native libraries from the JAR at runtime.
 * Extracts to a temporary file and loads via System.load().
 */
public final class NekoNativeLoader {
    private static volatile boolean loaded = false;
    private static final Object LOCK = new Object();

    private NekoNativeLoader() {}

    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                String arch = System.getProperty("os.arch", "").toLowerCase();

                String platform;
                String ext;
                if (os.contains("win")) {
                    platform = "windows";
                    ext = ".dll";
                } else if (os.contains("mac") || os.contains("darwin")) {
                    platform = "macos";
                    ext = ".dylib";
                } else {
                    platform = "linux";
                    ext = ".so";
                }

                String archSuffix;
                if (arch.contains("aarch64") || arch.contains("arm64")) {
                    archSuffix = "aarch64";
                } else {
                    archSuffix = "x64";
                }

                String libName = "libneko_" + platform + "_" + archSuffix + ext;
                String resourcePath = "/neko/native/" + libName;

                Path tmp = Files.createTempFile("libneko_", ext);
                tmp.toFile().deleteOnExit();
                try (InputStream is = NekoNativeLoader.class.getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        throw new UnsatisfiedLinkError("Native library not found: " + resourcePath);
                    }
                    Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                }

                System.load(tmp.toAbsolutePath().toString());
                loaded = true;
            } catch (IOException e) {
                throw new UnsatisfiedLinkError("Failed to load native library: " + e.getMessage());
            }
        }
    }

    public static void bindClass(Class<?> self, String internalOwnerName) {
        load();
        nekoBindClass(self, internalOwnerName);
    }

    private static native void nekoBindClass(Class<?> self, String internalOwnerName);
}
