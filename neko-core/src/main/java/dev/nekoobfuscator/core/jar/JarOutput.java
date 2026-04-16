package dev.nekoobfuscator.core.jar;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;

/**
 * Writes L1 IR classes and resource entries back to a JAR file.
 */
public final class JarOutput {
    private static final Logger log = LoggerFactory.getLogger(JarOutput.class);

    private final ClassHierarchy hierarchy;

    public JarOutput(ClassHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public void write(Path outputPath, List<L1Class> classes, List<ResourceEntry> resources,
                      Manifest manifest) throws IOException {
        write(outputPath.toFile(), classes, resources, manifest);
    }

    public void write(File outputFile, List<L1Class> classes, List<ResourceEntry> resources,
                      Manifest manifest) throws IOException {
        try (JarOutputStream jos = manifest != null
                ? new JarOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)), manifest)
                : new JarOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)))) {

            Set<String> written = new HashSet<>();

            for (L1Class l1 : classes) {
                String entryName = l1.name() + ".class";
                if (written.add(entryName)) {
                    byte[] bytecode = writeClass(l1);
                    JarEntry je = new JarEntry(entryName);
                    jos.putNextEntry(je);
                    jos.write(bytecode);
                    jos.closeEntry();
                    log.debug("Wrote class: {}", l1.name());
                }
            }

            for (ResourceEntry re : resources) {
                if (re.name().equals("META-INF/MANIFEST.MF")) continue;
                if (written.add(re.name())) {
                    JarEntry je = new JarEntry(re.name());
                    jos.putNextEntry(je);
                    jos.write(re.data());
                    jos.closeEntry();
                    log.debug("Wrote resource: {}", re.name());
                }
            }
        }
        log.info("Wrote {} classes and {} resources to {}", classes.size(), resources.size(), outputFile);
    }

    private byte[] writeClass(L1Class l1) {
        try {
            HierarchyClassWriter cw = new HierarchyClassWriter(hierarchy);
            l1.asmNode().accept(cw);
            return cw.toByteArray();
        } catch (Throwable e) {
            // Fall back to COMPUTE_MAXS - requires -noverify to run
            String verifierDetails = diagnoseFrameFailure(l1);
            log.warn("COMPUTE_FRAMES failed for {}, using COMPUTE_MAXS (requires -noverify): {}",
                l1.name(), e.getMessage(), e);
            if (!verifierDetails.isBlank()) {
                log.warn("Verifier details for {}:{}{}", l1.name(), System.lineSeparator(), verifierDetails);
            }
            try {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                l1.asmNode().accept(cw);
                return cw.toByteArray();
            } catch (Throwable e2) {
                log.error("Failed to write class {}: {}", l1.name(), e2.getMessage());
                throw new RuntimeException("Cannot write class " + l1.name(), e2);
            }
        }
    }

    private String diagnoseFrameFailure(L1Class l1) {
        try {
            ClassWriter rawWriter = new ClassWriter(0);
            l1.asmNode().accept(rawWriter);

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            CheckClassAdapter.verify(new ClassReader(rawWriter.toByteArray()), JarOutput.class.getClassLoader(), false, pw);
            pw.flush();
            return sw.toString().trim();
        } catch (Throwable diagnosticError) {
            return "<failed to run verifier diagnostics: " + diagnosticError.getMessage() + ">";
        }
    }

    /**
     * ClassWriter that uses ClassHierarchy for common superclass computation.
     */
    private static final class HierarchyClassWriter extends ClassWriter {
        private final ClassHierarchy hierarchy;

        HierarchyClassWriter(ClassHierarchy hierarchy) {
            super(COMPUTE_FRAMES);
            this.hierarchy = hierarchy;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (hierarchy != null) {
                String result = hierarchy.getCommonSuperClass(type1, type2);
                if (result != null && !result.equals("java/lang/Object")) {
                    return result;
                }
            }
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Throwable t) {
                return "java/lang/Object";
            }
        }
    }
}
