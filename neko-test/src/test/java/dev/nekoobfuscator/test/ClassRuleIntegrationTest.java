package dev.nekoobfuscator.test;

import dev.nekoobfuscator.api.config.ClassRule;
import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClassRuleIntegrationTest {
    @Test
    void ruleOnlyTransformSchedulesPassAndKeepsUnmatchedClassNames() throws Exception {
        Path work = workDir("rule-only");
        Path inputJar = compileRuleJar(work, "RuleEntry");
        Path outputJar = work.resolve("rule-only-obf.jar");

        ObfuscationConfig config = baseConfig(inputJar, outputJar);
        config.setRules(List.of(new ClassRule(
            "pkg.RuleEntry",
            false,
            Map.of("renamer", new TransformConfig(true, 1.0, Map.of("packagePrefix", "z/")))
        )));

        runObfuscation(config, inputJar, outputJar);

        assertEquals("RULE:OK", runJar(outputJar));
        assertTrue(hasClass(outputJar, "z/a"), "matching class was not renamed by rule-only transform");
        assertTrue(hasClass(outputJar, "pkg/KeepShape"), "unmatched class should keep its original name");
        assertFalse(hasClass(outputJar, "pkg/RuleEntry"), "matched class kept its original name");
    }

    @Test
    void excludeRuleOverridesTopLevelTransformForMatchingClass() throws Exception {
        Path work = workDir("exclude");
        Path inputJar = compileRuleJar(work, "RuleEntry");
        Path outputJar = work.resolve("exclude-obf.jar");

        ObfuscationConfig config = baseConfig(inputJar, outputJar);
        config.setTransforms(new LinkedHashMap<>(Map.of(
            "renamer", new TransformConfig(true, 1.0, Map.of("packagePrefix", "z/"))
        )));
        config.setRules(List.of(new ClassRule("pkg.KeepShape", true)));

        runObfuscation(config, inputJar, outputJar);

        assertEquals("RULE:OK", runJar(outputJar));
        assertTrue(hasClass(outputJar, "z/a"), "top-level transform did not rename the non-excluded class");
        assertTrue(hasClass(outputJar, "pkg/KeepShape"), "excluded class should keep its original name");
    }

    @Test
    void excludedClassNamesRemainReservedDuringRenaming() throws Exception {
        Path work = workDir("reserved");
        Path inputJar = compileRuleJar(work, "RuleEntry", true);
        Path outputJar = work.resolve("reserved-obf.jar");

        ObfuscationConfig config = baseConfig(inputJar, outputJar);
        config.setRules(List.of(new ClassRule(
            "pkg.RuleEntry",
            false,
            Map.of("renamer", new TransformConfig(true, 1.0, Map.of("packagePrefix", "pkg/")))
        )));

        runObfuscation(config, inputJar, outputJar);

        assertEquals("RULE:OK", runJar(outputJar));
        assertTrue(hasClass(outputJar, "pkg/a"), "unmatched occupied name should remain reserved");
        assertTrue(hasClass(outputJar, "pkg/b"), "renamer should skip the occupied excluded name");
    }

    @Test
    void unrenamedMainOwnerCanReferenceRuleRenamedClass() throws Exception {
        Path work = workDir("main-boundary");
        Path inputJar = compileMainBoundaryJar(work);
        Path outputJar = work.resolve("main-boundary-obf.jar");

        ObfuscationConfig config = baseConfig(inputJar, outputJar);
        config.setRules(List.of(new ClassRule(
            "pkg.RenameTarget",
            false,
            Map.of("renamer", new TransformConfig(true, 1.0, Map.of("packagePrefix", "z/")))
        )));

        runObfuscation(config, inputJar, outputJar);

        assertEquals("MAIN:OK", runJar(outputJar));
        assertTrue(hasClass(outputJar, "pkg/MainEntry"), "unmatched main owner should keep its original name");
        assertTrue(hasClass(outputJar, "z/a"), "matching non-main class should be renamed");
    }

    @Test
    void keyDispatchKeepsDescriptorsReachedFromExcludedCallers() throws Exception {
        Path work = workDir("key-boundary");
        Path inputJar = compileInboundJar(work);
        Path outputJar = work.resolve("key-boundary-obf.jar");

        ObfuscationConfig config = baseConfig(inputJar, outputJar);
        config.setTransforms(new LinkedHashMap<>(Map.of(
            "keyDispatch", new TransformConfig(true)
        )));
        config.setRules(List.of(new ClassRule("pkg.Caller", true)));

        runObfuscation(config, inputJar, outputJar);

        assertEquals("BOUNDARY:OK", runJar(outputJar));
    }

    @Test
    void keyDispatchKeepsDescriptorsReachedFromExcludedInvokeDynamicCallers() throws Exception {
        Path work = workDir("key-indy-boundary");
        Path inputJar = compileIndyInboundJar(work);
        Path outputJar = work.resolve("key-indy-boundary-obf.jar");

        ObfuscationConfig config = baseConfig(inputJar, outputJar);
        config.setTransforms(new LinkedHashMap<>(Map.of(
            "keyDispatch", new TransformConfig(true)
        )));
        config.setRules(List.of(new ClassRule("pkg.IndyCaller", true)));

        runObfuscation(config, inputJar, outputJar);

        assertEquals("INDY:OK", runJar(outputJar));
    }

    @Test
    void classRuleMatchersUseOrderedMergeAndMatchInnerNamesExplicitly() {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setTransforms(new LinkedHashMap<>(Map.of(
            "renamer", new TransformConfig(true),
            "stringObfuscation", new TransformConfig(true)
        )));
        config.setRules(List.of(
            new ClassRule("com.example.**", false, Map.of("renamer", new TransformConfig(true))),
            new ClassRule("pkg.**", false, Map.of("stringObfuscation", new TransformConfig(false))),
            new ClassRule("pkg.Target", false, Map.of("stringObfuscation", new TransformConfig(true))),
            new ClassRule("pkg.Excluded", true),
            new ClassRule("pkg.Reincluded", true),
            new ClassRule("pkg.Reincluded", false, Map.of("renamer", new TransformConfig(false))),
            new ClassRule("pkg/internal/*", false, Map.of("runtimeVariableObfuscation", new TransformConfig(true))),
            new ClassRule("com.example.Outer$*", false, Map.of("constantObfuscation", new TransformConfig(true)))
        ));

        assertTrue(config.isTransformEnabledForClass("renamer", "com/example/App"));
        assertTrue(config.isTransformEnabledForClass("renamer", "com.example.deep.App"));
        assertTrue(config.isTransformEnabledForClass("renamer", "pkg/Other"));
        assertFalse(config.isTransformEnabledForClass("stringObfuscation", "pkg/Other"));
        assertTrue(config.isTransformEnabledForClass("stringObfuscation", "pkg/Target"));
        assertFalse(config.isTransformEnabledForClass("renamer", "pkg/Excluded"));
        assertFalse(config.isTransformEnabledForClass("stringObfuscation", "pkg/Excluded"));
        assertFalse(config.isTransformEnabledForClass("renamer", "pkg/Reincluded"));
        assertFalse(config.isTransformEnabledForClass("stringObfuscation", "pkg/Reincluded"));
        assertTrue(config.isTransformEnabledForClass("runtimeVariableObfuscation", "pkg/internal/Shape"));
        assertFalse(config.isTransformEnabledForClass("runtimeVariableObfuscation", "pkg/internal/deep/Shape"));
        assertFalse(config.isTransformEnabledForClass("constantObfuscation", "com/example/Outer"));
        assertTrue(config.isTransformEnabledForClass("constantObfuscation", "com/example/Outer$Inner"));
        assertTrue(config.isTransformEnabledForClass("renamer", "com/example/Outer$1"));
    }

    private ObfuscationConfig baseConfig(Path inputJar, Path outputJar) {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(inputJar);
        config.setOutputJar(outputJar);
        config.keyConfig().setMasterSeed(0x434C41535352554CL);
        return config;
    }

    private void runObfuscation(ObfuscationConfig config, Path inputJar, Path outputJar) throws Exception {
        PassRegistry registry = new PassRegistry();
        StandardJvmPasses.register(registry);
        new ObfuscationPipeline(config, registry).execute(inputJar, outputJar);
    }

    private Path compileRuleJar(Path work, String mainClass) throws Exception {
        return compileRuleJar(work, mainClass, false);
    }

    private Path compileRuleJar(Path work, String mainClass, boolean includeOccupiedName) throws Exception {
        Path source = work.resolve(mainClass + ".java");
        Files.writeString(source, entrySourceText(mainClass), StandardCharsets.UTF_8);
        Path keepShape = work.resolve("KeepShape.java");
        Files.writeString(keepShape, keepShapeSourceText(), StandardCharsets.UTF_8);
        Path occupied = work.resolve("a.java");
        if (includeOccupiedName) {
            Files.writeString(occupied, occupiedNameSourceText(), StandardCharsets.UTF_8);
        }

        Path classes = Files.createDirectories(work.resolve("classes"));
        List<String> command = new java.util.ArrayList<>(List.of(
            "javac", "-d", classes.toString(), source.toString(), keepShape.toString()
        ));
        if (includeOccupiedName) {
            command.add(occupied.toString());
        }
        run(command, Duration.ofSeconds(30));

        Path jar = work.resolve(mainClass + ".jar");
        writeJar(jar, classes, "pkg." + mainClass);
        return jar;
    }

    private Path compileInboundJar(Path work) throws Exception {
        Path caller = work.resolve("Caller.java");
        Files.writeString(caller, callerSourceText(), StandardCharsets.UTF_8);
        Path target = work.resolve("Target.java");
        Files.writeString(target, targetSourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), caller.toString(), target.toString()), Duration.ofSeconds(30));

        Path jar = work.resolve("Caller.jar");
        writeJar(jar, classes, "pkg.Caller");
        return jar;
    }

    private Path compileIndyInboundJar(Path work) throws Exception {
        Path caller = work.resolve("IndyCaller.java");
        Files.writeString(caller, indyCallerSourceText(), StandardCharsets.UTF_8);
        Path target = work.resolve("Target.java");
        Files.writeString(target, targetSourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), caller.toString(), target.toString()), Duration.ofSeconds(30));

        Path jar = work.resolve("IndyCaller.jar");
        writeJar(jar, classes, "pkg.IndyCaller");
        return jar;
    }

    private Path compileMainBoundaryJar(Path work) throws Exception {
        Path main = work.resolve("MainEntry.java");
        Files.writeString(main, mainEntrySourceText(), StandardCharsets.UTF_8);
        Path target = work.resolve("RenameTarget.java");
        Files.writeString(target, renameTargetSourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), main.toString(), target.toString()), Duration.ofSeconds(30));

        Path jar = work.resolve("MainEntry.jar");
        writeJar(jar, classes, "pkg.MainEntry");
        return jar;
    }

    private String entrySourceText(String mainClass) {
        return """
            package pkg;

            public class %s {
                public static void main(String[] args) {
                    System.out.println(message());
                }

                static String message() {
                    return "RULE:" + KeepShape.value();
                }
            }
            """.formatted(mainClass);
    }

    private String keepShapeSourceText() {
        return """
            package pkg;

            public class KeepShape {
                public static String value() {
                    return "OK";
                }
            }
            """;
    }

    private String occupiedNameSourceText() {
        return """
            package pkg;

            public class a {
            }
            """;
    }

    private String callerSourceText() {
        return """
            package pkg;

            public class Caller {
                public static void main(String[] args) {
                    System.out.println("BOUNDARY:" + Target.value());
                }
            }
            """;
    }

    private String indyCallerSourceText() {
        return """
            package pkg;

            import java.util.function.Supplier;

            public class IndyCaller {
                public static void main(String[] args) {
                    Supplier<String> supplier = Target::value;
                    System.out.println("INDY:" + supplier.get());
                }
            }
            """;
    }

    private String targetSourceText() {
        return """
            package pkg;

            public class Target {
                public static String value() {
                    return "OK";
                }
            }
            """;
    }

    private String mainEntrySourceText() {
        return """
            package pkg;

            public class MainEntry {
                public static void main(String[] args) {
                    System.out.println("MAIN:" + RenameTarget.value());
                }
            }
            """;
    }

    private String renameTargetSourceText() {
        return """
            package pkg;

            public class RenameTarget {
                public static String value() {
                    return "OK";
                }
            }
            """;
    }

    private void writeJar(Path jar, Path classes, String mainClass) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", mainClass);
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()), manifest);
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                out.putNextEntry(new JarEntry(name));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
    }

    private boolean hasClass(Path jar, String className) throws Exception {
        JarInput input = new JarInput(jar);
        return input.classMap().containsKey(className);
    }

    private String runJar(Path jar) {
        return assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", jar.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            assertEquals(0, exit, output);
            return output.trim();
        });
    }

    private void run(List<String> command, Duration timeout) {
        assertTimeoutPreemptively(timeout, () -> {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            assertEquals(0, exit, output);
        });
    }

    private Path workDir(String name) throws Exception {
        Path projectRoot = Path.of(System.getProperty("neko.test.projectRoot", System.getProperty("user.dir")));
        Path root = Files.createDirectories(projectRoot.resolve("build/tmp/neko-test-class-rules"));
        return Files.createTempDirectory(root, name + "-");
    }
}
