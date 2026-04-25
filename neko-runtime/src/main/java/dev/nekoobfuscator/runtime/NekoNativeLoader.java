package dev.nekoobfuscator.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class NekoNativeLoader {
    private static volatile boolean loaded;
    private static final Object LOCK = new Object();

    private NekoNativeLoader() {
    }

    private static native void refresh(Class<?> owner);

    public static void load() {
        loadForClass(null);
    }

    public static void loadForClass(Class<?> owner) {
        if (loaded) {
            refresh(owner);
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                refresh(owner);
                return;
            }
            String platform = detectPlatform();
            String arch = detectArch();
            String ext = libExt(platform);
            String resourcePath = "/neko/native/" + resourceName(platform, arch, ext);
            try {
                Path tmp = extractResource(resourcePath, ext);
                System.load(tmp.toAbsolutePath().toString());
                loaded = true;
                refresh(owner);
            } catch (IOException e) {
                throw new UnsatisfiedLinkError("Failed to load native library: " + e.getMessage());
            }
        }
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        return "linux";
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }
        return "x64";
    }

    private static String libExt(String platform) {
        if ("windows".equals(platform)) {
            return ".dll";
        }
        if ("macos".equals(platform)) {
            return ".dylib";
        }
        return ".so";
    }

    private static String resourceName(String platform, String arch, String ext) {
        return "libneko_" + platform + "_" + arch + ext;
    }

    private static Path extractResource(String resourcePath, String ext) throws IOException {
        Path tmp = Files.createTempFile("libneko_", ext);
        tmp.toFile().deleteOnExit();
        try (InputStream is = NekoNativeLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new UnsatisfiedLinkError("Native library not found: " + resourcePath);
            }
            Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }
}
