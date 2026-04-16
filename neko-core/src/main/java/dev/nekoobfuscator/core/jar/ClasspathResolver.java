package dev.nekoobfuscator.core.jar;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Resolves classes from the classpath (JDK + library JARs) for hierarchy building.
 * These classes are used for frame computation but are never transformed.
 */
public final class ClasspathResolver {
    private static final Logger log = LoggerFactory.getLogger(ClasspathResolver.class);

    private final List<Path> classpathEntries;

    public ClasspathResolver(List<Path> classpathEntries) {
        this.classpathEntries = classpathEntries;
    }

    /**
     * Populates the ClassHierarchy with hierarchy info from classpath JARs.
     */
    public void populateHierarchy(ClassHierarchy hierarchy) {
        // Add fundamental JDK classes
        hierarchy.addSystemClass("java/lang/Object", null, List.of(), false);
        hierarchy.addSystemClass("java/lang/String", "java/lang/Object",
            List.of("java/io/Serializable", "java/lang/Comparable", "java/lang/CharSequence"), false);
        hierarchy.addSystemClass("java/lang/Throwable", "java/lang/Object",
            List.of("java/io/Serializable"), false);
        hierarchy.addSystemClass("java/lang/Exception", "java/lang/Throwable", List.of(), false);
        hierarchy.addSystemClass("java/lang/RuntimeException", "java/lang/Exception", List.of(), false);
        hierarchy.addSystemClass("java/lang/Error", "java/lang/Throwable", List.of(), false);
        hierarchy.addSystemClass("java/lang/Enum", "java/lang/Object",
            List.of("java/lang/Comparable", "java/io/Serializable"), false);
        hierarchy.addSystemClass("java/lang/Record", "java/lang/Object", List.of(), false);

        // Process classpath entries
        for (Path entry : classpathEntries) {
            try {
                if (Files.isDirectory(entry)) {
                    processDirectory(entry, hierarchy);
                } else if (entry.toString().endsWith(".jar")) {
                    processJar(entry, hierarchy);
                }
            } catch (IOException e) {
                log.warn("Failed to process classpath entry {}: {}", entry, e.getMessage());
            }
        }
    }

    private void processJar(Path jarPath, ClassHierarchy hierarchy) throws IOException {
        try (JarInputStream jis = new JarInputStream(new BufferedInputStream(Files.newInputStream(jarPath)))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (!entry.getName().endsWith(".class") || entry.isDirectory()) continue;
                processClassBytes(readAllBytes(jis), hierarchy);
            }
        }
    }

    private void processDirectory(Path dir, ClassHierarchy hierarchy) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try {
                    processClassBytes(Files.readAllBytes(p), hierarchy);
                } catch (IOException e) {
                    log.warn("Failed to read class file {}: {}", p, e.getMessage());
                }
            });
        }
    }

    private void processClassBytes(byte[] data, ClassHierarchy hierarchy) {
        try {
            ClassReader cr = new ClassReader(data);
            String name = cr.getClassName();
            String superName = cr.getSuperName();
            String[] interfaces = cr.getInterfaces();
            boolean isInterface = (cr.getAccess() & Opcodes.ACC_INTERFACE) != 0;
            hierarchy.addSystemClass(name, superName,
                interfaces != null ? List.of(interfaces) : List.of(), isInterface);
        } catch (Exception e) {
            // Skip malformed class files
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
