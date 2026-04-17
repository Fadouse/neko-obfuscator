package dev.nekoobfuscator.runtime;

import java.lang.reflect.Method;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public final class NekoJarLauncher {
    private NekoJarLauncher() {
    }

    public static void main(String[] args) throws Throwable {
        Manifest manifest = new Manifest(NekoJarLauncher.class.getResourceAsStream("/META-INF/MANIFEST.MF"));
        Attributes attrs = manifest.getMainAttributes();
        String mainClass = attrs.getValue("Neko-Original-Main-Class");
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalStateException("Missing Neko-Original-Main-Class in manifest");
        }
        NekoNativeLoader.load();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Class<?> target = Class.forName(mainClass, false, cl);
        Method main = target.getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }
}
