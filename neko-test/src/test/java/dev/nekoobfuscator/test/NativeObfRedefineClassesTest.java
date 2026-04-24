package dev.nekoobfuscator.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeObfRedefineClassesTest {
    private static final Pattern REDEFINE_TRACE = Pattern.compile("neko_redefine_detected=(\\d+)");

    @Test
    @Timeout(5)
    void translatedDispatchRescanObservesRedefinedMethod() throws Exception {
        Path workDir = NativeObfuscationHelper.nativeWorkDir().resolve("redefine-classes");
        Files.createDirectories(workDir);
        Path appSrc = workDir.resolve("app-src");
        Path appClasses = workDir.resolve("app-classes");
        Path agentSrc = workDir.resolve("agent-src");
        Path agentClasses = workDir.resolve("agent-classes");
        Files.createDirectories(appSrc);
        Files.createDirectories(appClasses);
        Files.createDirectories(agentSrc);
        Files.createDirectories(agentClasses);

        Files.writeString(appSrc.resolve("Victim.java"), "public class Victim { public static int tick(int value) { return value + 1; } }\n");
        Files.writeString(appSrc.resolve("Probe.java"), "public class Probe { public static int pulse(int value) { return value ^ 7; } }\n");
        Files.writeString(appSrc.resolve("Main.java"), """
            import java.lang.reflect.Method;
            import java.nio.file.Files;
            import java.nio.file.Path;

            public class Main {
              public static void main(String[] args) throws Exception {
                for (int i = 0; i < 2500; i++) {
                  if (Probe.pulse(i) != (i ^ 7)) throw new AssertionError("probe-warmup-" + i);
                }
                Class<?> agent = Class.forName("RedefAgent");
                Method redefine = agent.getMethod("redefine", Class.class, byte[].class);
                redefine.invoke(null, Victim.class, Files.readAllBytes(Path.of(args[0])));
                for (int i = 0; i < 5000; i++) {
                  if (Probe.pulse(i) != (i ^ 7)) throw new AssertionError("probe-rescan-" + i);
                }
                int value = Victim.tick(7);
                if (value != 107) throw new AssertionError("redefined-victim=" + value);
                System.out.println("redefine-driver-ok");
              }
            }
            """);
        Files.writeString(agentSrc.resolve("RedefAgent.java"), """
            import java.lang.instrument.ClassDefinition;
            import java.lang.instrument.Instrumentation;

            public class RedefAgent {
              private static volatile Instrumentation instrumentation;

              public static void premain(String args, Instrumentation inst) {
                instrumentation = inst;
                try {
                  ClassLoader loader = ClassLoader.getSystemClassLoader();
                  Class.forName("Victim", false, loader);
                  Class.forName("Probe", true, loader);
                } catch (ClassNotFoundException ex) {
                  throw new IllegalStateException("failed to preload redefine fixture", ex);
                }
              }

              public static void redefine(Class<?> target, byte[] bytes) throws Exception {
                Instrumentation inst = instrumentation;
                if (inst == null) throw new IllegalStateException("agent not initialized");
                if (!inst.isRedefineClassesSupported()) throw new IllegalStateException("redefine unsupported");
                inst.redefineClasses(new ClassDefinition(target, bytes));
              }
            }
            """);

        compile(workDir, appClasses, appSrc.resolve("Victim.java"), appSrc.resolve("Probe.java"), appSrc.resolve("Main.java"));
        compile(workDir, agentClasses, agentSrc.resolve("RedefAgent.java"));

        Path agentJar = workDir.resolve("redefine-agent.jar");
        writeAgentJar(agentJar, agentClasses);

        Path inputJar = workDir.resolve("redefine-input.jar");
        writeJar(inputJar, "Main", appClasses, "Victim.class", "Probe.class", "Main.class");

        Path config = workDir.resolve("redefine-native.yml");
        Files.writeString(config, """
            version: 1
            preset: STANDARD

            transforms:
              controlFlowFlattening:
                enabled: false
              exceptionObfuscation:
                enabled: false
              exceptionReturn:
                enabled: false
              opaquePredicates:
                enabled: false
              stringEncryption:
                enabled: false
              numberEncryption:
                enabled: false
              invokeDynamic:
                enabled: false
              outliner:
                enabled: false
              stackObfuscation:
                enabled: false
              advancedJvm:
                enabled: false

            native:
              enabled: true
              targets:
                - LINUX_X64
              methods:
                - "Victim#tick"
                - "Probe#pulse"
              excludePatterns:
                - "Main#main"
              includeAnnotated: false
              skipOnError: true
              outputPrefix: "neko_impl_"
              resourceEncryption: false

            keys:
              masterSeed: 12345
            """);

        Path outputJar = workDir.resolve("redefine-output.jar");
        NativeObfuscationHelper.ObfuscationRunResult obfuscation = obfuscateJarWithNativeDebug(inputJar, outputJar, config, Duration.ofMinutes(3));
        assertTrue(obfuscation.combinedOutput().contains("[NekoObfuscator] Done"), obfuscation::combinedOutput);

        Path replacementClass = workDir.resolve("Victim-redefined.class");
        Files.write(replacementClass, redefineTickBody(NativeObfuscationHelper.extractEntry(outputJar, "Victim.class")));

        NativeObfuscationHelper.JarRunResult run = runJarWithDebug(workDir, outputJar, agentJar, replacementClass);
        assertEquals(0, run.exitCode(), run::combinedOutput);
        NativeObfuscationHelper.assertNoFatalNativeCrash(run);
        assertTrue(run.combinedOutput().contains("redefine-driver-ok"), run::combinedOutput);
        Matcher matcher = REDEFINE_TRACE.matcher(run.combinedOutput());
        assertTrue(matcher.find(), () -> "Missing redefine trace in output:\n" + run.combinedOutput());
        assertTrue(Integer.parseInt(matcher.group(1)) >= 1, run::combinedOutput);
    }

    private static void compile(Path workDir, Path classesDir, Path... sources) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("javac");
        command.add("-d");
        command.add(classesDir.toString());
        for (Path source : sources) command.add(source.toString());
        ProcessBuilder javac = new ProcessBuilder(command);
        javac.directory(workDir.toFile());
        javac.redirectErrorStream(true);
        Process process = javac.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), () -> output);
    }

    private static byte[] redefineTickBody(byte[] originalClass) {
        ClassNode classNode = new ClassNode();
        new ClassReader(originalClass).accept(classNode, 0);
        for (MethodNode method : classNode.methods) {
            if (method.name.equals("tick") && method.desc.equals("(I)I")) {
                InsnList replacement = new InsnList();
                replacement.add(new VarInsnNode(Opcodes.ILOAD, 0));
                replacement.add(new IntInsnNode(Opcodes.BIPUSH, 100));
                replacement.add(new InsnNode(Opcodes.IADD));
                replacement.add(new InsnNode(Opcodes.IRETURN));
                method.instructions = replacement;
                method.tryCatchBlocks.clear();
                method.localVariables = null;
                method.maxStack = 2;
                method.maxLocals = 1;
                ClassWriter writer = new ClassWriter(0);
                classNode.accept(writer);
                return writer.toByteArray();
            }
        }
        throw new AssertionError("Victim.tick(I)I not found in obfuscated class");
    }

    private static void writeJar(Path jar, String mainClass, Path classesDir, String... entries) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (mainClass != null) manifest.getMainAttributes().putValue("Main-Class", mainClass);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()), manifest)) {
            for (String entry : entries) {
                jos.putNextEntry(new JarEntry(entry));
                jos.write(Files.readAllBytes(classesDir.resolve(entry)));
                jos.closeEntry();
            }
        }
    }

    private static void writeAgentJar(Path jar, Path classesDir) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Premain-Class", "RedefAgent");
        attributes.putValue("Can-Redefine-Classes", "true");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()), manifest)) {
            jos.putNextEntry(new JarEntry("RedefAgent.class"));
            jos.write(Files.readAllBytes(classesDir.resolve("RedefAgent.class")));
            jos.closeEntry();
        }
    }

    private static NativeObfuscationHelper.JarRunResult runJarWithDebug(Path workDir, Path jar, Path agentJar, Path replacementClass) throws Exception {
        Path stdout = workDir.resolve("redefine-run.stdout.log");
        Path stderr = workDir.resolve("redefine-run.stderr.log");
        List<String> command = List.of("java", "-javaagent:" + agentJar, "-jar", jar.toString(), replacementClass.toString());
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(NativeObfuscationHelper.projectRoot().toFile());
        processBuilder.environment().put("NEKO_DEBUG", "1");
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());
        Process process = processBuilder.start();
        boolean finished = process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("Timed out running redefine jar");
        }
        return new NativeObfuscationHelper.JarRunResult(
            jar,
            stdout,
            stderr,
            Files.readString(stdout),
            Files.readString(stderr),
            process.exitValue(),
            Duration.ZERO
        );
    }

    private static NativeObfuscationHelper.ObfuscationRunResult obfuscateJarWithNativeDebug(Path input, Path output, Path config, Duration timeout) throws Exception {
        Files.createDirectories(output.getParent());
        Path stdout = output.resolveSibling("redefine-obfuscate.stdout.log");
        Path stderr = output.resolveSibling("redefine-obfuscate.stderr.log");
        List<String> command = List.of(
            NativeObfuscationHelper.cliPath().toString(),
            "obfuscate",
            "-c", config.toString(),
            "-i", input.toString(),
            "-o", output.toString()
        );
        long start = System.nanoTime();
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(NativeObfuscationHelper.projectRoot().toFile());
        processBuilder.environment().put("NEKO_NATIVE_BUILD_DEBUG", "true");
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());
        Process process = processBuilder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("Timed out obfuscating redefine fixture");
        }
        String stdoutText = Files.exists(stdout) ? Files.readString(stdout) : "";
        String stderrText = Files.exists(stderr) ? Files.readString(stderr) : "";
        assertEquals(0, process.exitValue(), () -> "CLI obfuscation failed\nSTDOUT:\n" + stdoutText + "\nSTDERR:\n" + stderrText);
        assertTrue(Files.exists(output), () -> "Expected obfuscated jar to exist: " + output);
        return new NativeObfuscationHelper.ObfuscationRunResult(
            output,
            stdout,
            stderr,
            stdoutText,
            stderrText,
            process.exitValue(),
            Duration.ofNanos(System.nanoTime() - start)
        );
    }
}
