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

    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            String platform = detectPlatform();
            String arch = detectArch();
            String ext = libExt(platform);
            String resourceName = resourceName(platform, arch, ext);
            String loadingKey = "dev.nekoobfuscator.native.loading." + resourceName;
            if (Boolean.getBoolean(loadingKey)) {
                loaded = true;
                return;
            }
            String resourcePath = "/neko/native/" + resourceName;
            try {
                System.setProperty(loadingKey, "true");
                Path tmp = extractResource(resourcePath, ext);
                System.load(tmp.toAbsolutePath().toString());
                loaded = true;
            } catch (IOException e) {
                System.clearProperty(loadingKey);
                throw new UnsatisfiedLinkError("Failed to load native library: " + e.getMessage());
            } catch (LinkageError e) {
                System.clearProperty(loadingKey);
                throw e;
            } finally {
                System.clearProperty(loadingKey);
            }
        }
    }

    public static void load(Class<?> owner) {
        boolean loading = isLoading();
        load();
        if (owner != null && !loading) {
            bindClass(owner);
        }
    }

    private static native void bindClass(Class<?> owner);

    private static boolean isLoading() {
        String platform = detectPlatform();
        String arch = detectArch();
        String ext = libExt(platform);
        String resourceName = resourceName(platform, arch, ext);
        String loadingKey = "dev.nekoobfuscator.native.loading." + resourceName;
        return Boolean.getBoolean(loadingKey);
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
