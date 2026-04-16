package dev.nekoobfuscator.core.jar;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;

/**
 * Reads a JAR file and produces L1 IR classes and resource entries.
 */
public final class JarInput {
    private static final Logger log = LoggerFactory.getLogger(JarInput.class);

    private final List<L1Class> classes = new ArrayList<>();
    private final List<ResourceEntry> resources = new ArrayList<>();
    private final Map<String, L1Class> classMap = new LinkedHashMap<>();
    private Manifest manifest;

    public JarInput(Path jarPath) throws IOException {
        this(jarPath.toFile());
    }

    public JarInput(File jarFile) throws IOException {
        try (JarInputStream jis = new JarInputStream(new BufferedInputStream(new FileInputStream(jarFile)))) {
            this.manifest = jis.getManifest();
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                byte[] data = readAllBytes(jis);

                if (name.endsWith(".class") && !name.startsWith("META-INF/versions/")) {
                    try {
                        ClassReader cr = new ClassReader(data);
                        ClassNode cn = new ClassNode();
                        cr.accept(cn, ClassReader.EXPAND_FRAMES);
                        L1Class l1 = new L1Class(cn);
                        l1.setOriginalBytes(data);
                        classes.add(l1);
                        classMap.put(cn.name, l1);
                        log.debug("Loaded class: {}", cn.name);
                    } catch (Exception e) {
                        log.warn("Failed to parse class {}: {}", name, e.getMessage());
                        resources.add(new ResourceEntry(name, data));
                    }
                } else {
                    resources.add(new ResourceEntry(name, data));
                }
            }
        }
        log.info("Loaded {} classes and {} resources", classes.size(), resources.size());
    }

    public List<L1Class> classes() { return classes; }
    public List<ResourceEntry> resources() { return resources; }
    public Map<String, L1Class> classMap() { return classMap; }
    public Manifest manifest() { return manifest; }

    public L1Class findClass(String internalName) {
        return classMap.get(internalName);
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
