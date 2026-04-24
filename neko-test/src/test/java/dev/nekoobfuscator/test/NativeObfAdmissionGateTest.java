package dev.nekoobfuscator.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeObfAdmissionGateTest {
    private static final String LINKAGE_ERROR_OWNER = "java/lang/LinkageError";
    private static final String LINKAGE_ERROR_MESSAGE = "please check your native library load correctly";
    private static final Pattern NATIVE_STAGE_COUNTS = Pattern.compile("Native stage: translated=(\\d+) rejected=(\\d+)");
    private static final List<ExpectedAdmission> EXPECTED = List.of(
        new ExpectedAdmission("TEST", "TEST.jar", 14, 75, 61),
        new ExpectedAdmission("obfusjack", "obfusjack-test21.jar", 17, 84, 67),
        new ExpectedAdmission("SnakeGame", "SnakeGame.jar", 12, 14, 2)
    );
    private static final Map<String, AdmissionCounts> MEASURED = new LinkedHashMap<>();

    @BeforeAll
    static void prepareFixtures() throws Exception {
        NativeObfuscationHelper.ensureObfuscatedFixtures();
    }

    @AfterAll
    static void writeAdmissionCountsArtifact() throws Exception {
        for (ExpectedAdmission expected : EXPECTED) {
            MEASURED.computeIfAbsent(expected.fixtureName(), fixture -> {
                try {
                    return measure(expected);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to measure admission counts for " + expected.jarName(), e);
                }
            });
        }

        StringJoiner rows = new StringJoiner(System.lineSeparator(), "", System.lineSeparator());
        for (ExpectedAdmission expected : EXPECTED) {
            AdmissionCounts actual = MEASURED.get(expected.fixtureName());
            rows.add(expected.jarName()
                + " admitted=" + actual.admitted()
                + " total=" + actual.total()
                + " excluded=" + actual.excluded());
        }

        Path output = NativeObfuscationHelper.projectRoot().resolve("verification/w10/admission-counts.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, rows.toString());
    }

    @Test
    void nativeObfuscation_TEST_admissionGate() throws Exception {
        assertAdmission(EXPECTED.get(0));
    }

    @Test
    void nativeObfuscation_obfusjack_admissionGate() throws Exception {
        assertAdmission(EXPECTED.get(1));
    }

    @Test
    void nativeObfuscation_SnakeGame_admissionGate() throws Exception {
        assertAdmission(EXPECTED.get(2));
    }

    private static void assertAdmission(ExpectedAdmission expected) throws Exception {
        AdmissionCounts actual = MEASURED.computeIfAbsent(expected.fixtureName(), fixture -> {
            try {
                return measure(expected);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to measure admission counts for " + expected.jarName(), e);
            }
        });

        assertEquals(expected.total(), actual.total(), () -> expected.jarName()
            + " admission denominator changed; update the W10 hardcoded expectation only with an intentional plan change");
        assertExactAdmission(expected, actual);
        assertEquals(expected.excluded(), actual.excluded(), () -> expected.jarName()
            + " exclusion count changed; admitted=" + actual.admitted()
            + " total=" + actual.total()
            + " excluded=" + actual.excluded());
    }

    private static void assertExactAdmission(ExpectedAdmission expected, AdmissionCounts actual) {
        if (actual.admitted() < expected.admitted()) {
            assertEquals(expected.admitted(), actual.admitted(), () -> expected.jarName()
                + " admission regressed; W10 requires exactly " + expected.admitted()
                + " admitted methods but measured " + actual.admitted());
        }
        if (actual.admitted() > expected.admitted()) {
            assertEquals(expected.admitted(), actual.admitted(), () -> expected.jarName()
                + " admission grew; update hardcoded expectation in W11 commit");
        }
        assertEquals(expected.admitted(), actual.admitted(), () -> expected.jarName() + " admission count changed");
    }

    private static AdmissionCounts measure(ExpectedAdmission expected) throws Exception {
        NativeObfuscationHelper.NativeArtifact artifact = NativeObfuscationHelper.artifact(expected.fixtureName());
        AdmissionCounts stageCounts = parseNativeStageCounts(artifact.obfuscationStdout());
        Map<String, MethodNode> originalMethods = allMethodsByKey(artifact.inputJar());
        Map<String, MethodNode> outputMethods = allMethodsByKey(artifact.outputJar());

        int admitted = 0;
        List<String> missingMethods = new ArrayList<>();
        for (Map.Entry<String, MethodNode> entry : originalMethods.entrySet()) {
            MethodNode outputMethod = outputMethods.get(entry.getKey());
            if (outputMethod == null) {
                missingMethods.add(entry.getKey());
                continue;
            }
            if (isTranslatedThrowBody(outputMethod)) {
                admitted++;
            }
        }

        assertTrue(missingMethods.isEmpty(), () -> "Post-obfuscation jar is missing original admission target methods: " + missingMethods);
        assertEquals(stageCounts.admitted(), admitted, () -> expected.jarName()
            + " native stage translated count does not match translated output body count");
        return stageCounts;
    }

    private static AdmissionCounts parseNativeStageCounts(Path stdout) throws Exception {
        String output = Files.readString(stdout);
        Matcher matcher = NATIVE_STAGE_COUNTS.matcher(output);
        assertTrue(matcher.find(), () -> "Missing native stage counts in " + stdout + "\n" + output);
        int admitted = Integer.parseInt(matcher.group(1));
        int total = Integer.parseInt(matcher.group(2));
        return new AdmissionCounts(admitted, total, total - admitted);
    }

    private static Map<String, MethodNode> allMethodsByKey(Path jar) throws Exception {
        Map<String, MethodNode> methods = new LinkedHashMap<>();
        for (ClassNode classNode : NativeObfuscationHelper.readAllClasses(jar)) {
            for (MethodNode method : classNode.methods) {
                methods.put(methodKey(classNode, method), method);
            }
        }
        return methods;
    }

    private static String methodKey(ClassNode classNode, MethodNode method) {
        return classNode.name + '#' + method.name + method.desc;
    }

    private static boolean isTranslatedThrowBody(MethodNode method) {
        if ((method.access & Opcodes.ACC_NATIVE) != 0) {
            return false;
        }
        List<AbstractInsnNode> instructions = realInstructions(method);
        if (instructions.size() != 5) {
            return false;
        }
        if (!(instructions.get(0) instanceof TypeInsnNode newInsn) || newInsn.getOpcode() != Opcodes.NEW || !LINKAGE_ERROR_OWNER.equals(newInsn.desc)) {
            return false;
        }
        if (instructions.get(1).getOpcode() != Opcodes.DUP) {
            return false;
        }
        if (!(instructions.get(2) instanceof LdcInsnNode ldcInsn) || !LINKAGE_ERROR_MESSAGE.equals(ldcInsn.cst)) {
            return false;
        }
        if (!(instructions.get(3) instanceof MethodInsnNode initInsn)
            || initInsn.getOpcode() != Opcodes.INVOKESPECIAL
            || !LINKAGE_ERROR_OWNER.equals(initInsn.owner)
            || !"<init>".equals(initInsn.name)
            || !"(Ljava/lang/String;)V".equals(initInsn.desc)) {
            return false;
        }
        return instructions.get(4).getOpcode() == Opcodes.ATHROW;
    }

    private static List<AbstractInsnNode> realInstructions(MethodNode method) {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != -1) {
                instructions.add(insn);
            }
        }
        return instructions;
    }

    private record ExpectedAdmission(String fixtureName, String jarName, int admitted, int total, int excluded) {}

    private record AdmissionCounts(int admitted, int total, int excluded) {}
}
