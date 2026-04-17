package dev.nekoobfuscator.test;

import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import dev.nekoobfuscator.native_.translator.OpcodeTranslator;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OpcodeTranslatorUnitTest {
    private static final String TARGET_LABEL = "L_target";

    @Test
    void opcodeTranslator_constantsEmitExpectedPushes() {
        OpcodeTranslator translator = translator();

        String iconst = render(translator.translate(new InsnNode(Opcodes.ICONST_0)));
        String bipush = render(translator.translate(new IntInsnNode(Opcodes.BIPUSH, 42)));
        String sipush = render(translator.translate(new IntInsnNode(Opcodes.SIPUSH, 32000)));
        String dconst = render(translator.translate(new InsnNode(Opcodes.DCONST_1)));
        String nullConst = render(translator.translate(new InsnNode(Opcodes.ACONST_NULL)));

        assertContains(iconst, "PUSH_I(0);");
        assertContains(bipush, "PUSH_I(42);");
        assertContains(sipush, "PUSH_I(32000);");
        assertContains(dconst, "PUSH_D(1.0);");
        assertContains(nullConst, "PUSH_O(NULL);");
    }

    @Test
    void opcodeTranslator_loadsReadFromLocals() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new VarInsnNode(Opcodes.ILOAD, 1)).getFirst(),
            translator.translate(new VarInsnNode(Opcodes.LLOAD, 2)).getFirst(),
            translator.translate(new VarInsnNode(Opcodes.DLOAD, 3)).getFirst(),
            translator.translate(new VarInsnNode(Opcodes.ALOAD, 4)).getFirst()
        ));

        assertContains(code, "PUSH_I(locals[1].i);", "PUSH_L(locals[2].j);", "PUSH_D(locals[3].d);", "PUSH_O(locals[4].o);");
    }

    @Test
    void opcodeTranslator_storesPopIntoLocals() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new VarInsnNode(Opcodes.ISTORE, 1)).getFirst(),
            translator.translate(new VarInsnNode(Opcodes.LSTORE, 2)).getFirst(),
            translator.translate(new VarInsnNode(Opcodes.ASTORE, 3)).getFirst()
        ));

        assertContains(code, "locals[1].i = POP_I();", "locals[2].j = POP_L();", "locals[3].o = POP_O();");
    }

    @Test
    void opcodeTranslator_integerArithmeticUsesTwoPopsAndOnePush() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new InsnNode(Opcodes.IADD)).getFirst(),
            translator.translate(new InsnNode(Opcodes.IMUL)).getFirst(),
            translator.translate(new InsnNode(Opcodes.IDIV)).getFirst(),
            translator.translate(new InsnNode(Opcodes.IREM)).getFirst(),
            translator.translate(new InsnNode(Opcodes.INEG)).getFirst()
        ));

        assertContains(code,
            "jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b);",
            "jint b = POP_I(); jint a = POP_I(); PUSH_I(a * b);",
            "jint b = POP_I(); jint a = POP_I(); PUSH_I(a / b);",
            "jint b = POP_I(); jint a = POP_I(); PUSH_I(a % b);",
            "PUSH_I(-POP_I());");
    }

    @Test
    void opcodeTranslator_longArithmeticAndShiftUseWidePopPushPatterns() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new InsnNode(Opcodes.LADD)).getFirst(),
            translator.translate(new InsnNode(Opcodes.LMUL)).getFirst(),
            translator.translate(new InsnNode(Opcodes.LSHL)).getFirst(),
            translator.translate(new InsnNode(Opcodes.IUSHR)).getFirst()
        ));

        assertContains(code,
            "jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a + b);",
            "jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a * b);",
            "jint __s = POP_I(); jlong __v = POP_L(); PUSH_L(__v << (__s & 0x3F));",
            "PUSH_I((jint)((uint32_t)a >> (b & 0x1f)));"
        );
    }

    @Test
    void opcodeTranslator_floatAndDoubleArithmeticUseMathHelpers() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new InsnNode(Opcodes.FADD)).getFirst(),
            translator.translate(new InsnNode(Opcodes.FREM)).getFirst(),
            translator.translate(new InsnNode(Opcodes.DADD)).getFirst(),
            translator.translate(new InsnNode(Opcodes.DREM)).getFirst()
        ));

        assertContains(code,
            "jfloat b = POP_F(); jfloat a = POP_F(); PUSH_F(a + b);",
            "PUSH_F(fmodf(a, b));",
            "jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(a + b);",
            "PUSH_D(fmod(a, b));"
        );
    }

    @Test
    void opcodeTranslator_bitwiseAndConversionsEmitExpectedCasts() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new InsnNode(Opcodes.IAND)).getFirst(),
            translator.translate(new InsnNode(Opcodes.IOR)).getFirst(),
            translator.translate(new InsnNode(Opcodes.IXOR)).getFirst(),
            translator.translate(new InsnNode(Opcodes.I2L)).getFirst(),
            translator.translate(new InsnNode(Opcodes.L2I)).getFirst(),
            translator.translate(new InsnNode(Opcodes.F2D)).getFirst(),
            translator.translate(new InsnNode(Opcodes.I2B)).getFirst()
        ));

        assertContains(code,
            "PUSH_I(a & b);",
            "PUSH_I(a | b);",
            "PUSH_I(a ^ b);",
            "PUSH_L((jlong)POP_I());",
            "PUSH_I((jint)POP_L());",
            "PUSH_D((jdouble)POP_F());",
            "PUSH_I((jbyte)POP_I());"
        );
    }

    @Test
    void opcodeTranslator_stackOpsManipulateSlotsDirectly() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new InsnNode(Opcodes.POP)).getFirst(),
            translator.translate(new InsnNode(Opcodes.POP2)).getFirst(),
            translator.translate(new InsnNode(Opcodes.DUP)).getFirst(),
            translator.translate(new InsnNode(Opcodes.DUP_X1)).getFirst(),
            translator.translate(new InsnNode(Opcodes.DUP_X2)).getFirst(),
            translator.translate(new InsnNode(Opcodes.SWAP)).getFirst()
        ));

        assertContains(code,
            "sp--;",
            "sp -= 2;",
            "stack[sp] = stack[sp-1]; sp++;",
            "stack[sp-1] = stack[sp-2];",
            "stack[sp-3] = v1;",
            "stack[sp-1] = stack[sp-2]; stack[sp-2] = t;"
        );
    }

    @Test
    void opcodeTranslator_comparisonsPushComparisonResults() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new InsnNode(Opcodes.LCMP)).getFirst(),
            translator.translate(new InsnNode(Opcodes.FCMPL)).getFirst(),
            translator.translate(new InsnNode(Opcodes.DCMPG)).getFirst()
        ));

        assertContains(code,
            "PUSH_I(a > b ? 1 : (a < b ? -1 : 0));",
            "PUSH_I(a > b ? 1 : (a < b ? -1 : (a == b ? 0 : -1)));",
            "PUSH_I(a > b ? 1 : (a < b ? -1 : (a == b ? 0 : 1)));"
        );
    }

    @Test
    void opcodeTranslator_jumpsEmitTargetLabels() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translateJump(new JumpInsnNode(Opcodes.IFEQ, new LabelNode()), TARGET_LABEL),
            translator.translateJump(new JumpInsnNode(Opcodes.IF_ICMPNE, new LabelNode()), TARGET_LABEL),
            translator.translateJump(new JumpInsnNode(Opcodes.IFNULL, new LabelNode()), TARGET_LABEL)
        ));

        assertContains(code,
            "if (POP_I() == 0) goto " + TARGET_LABEL + ';',
            "jint b = POP_I(); jint a = POP_I(); if (a != b) goto " + TARGET_LABEL + ';',
            "if (POP_O() == NULL) goto " + TARGET_LABEL + ';'
        );
    }

    @Test
    void opcodeTranslator_returnsAndNoopEmitTerminalStatements() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new InsnNode(Opcodes.IRETURN)).getFirst(),
            translator.translate(new InsnNode(Opcodes.LRETURN)).getFirst(),
            translator.translate(new InsnNode(Opcodes.RETURN)).getFirst(),
            translator.translate(new InsnNode(Opcodes.NOP)).getFirst(),
            translator.translate(new IincInsnNode(4, 3)).getFirst()
        ));

        assertContains(code,
            "return POP_I();",
            "return POP_L();",
            "return;",
            "/* nop */",
            "locals[4].i += 3;"
        );
    }

    @Test
    void opcodeTranslator_arrayOpsUseJniArrayHelpers() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new InsnNode(Opcodes.IALOAD)).getFirst(),
            translator.translate(new InsnNode(Opcodes.IASTORE)).getFirst(),
            translator.translate(new InsnNode(Opcodes.ARRAYLENGTH)).getFirst(),
            translator.translate(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT)).getFirst(),
            translator.translate(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String")).getFirst(),
            translator.translate(new MultiANewArrayInsnNode("[[I", 2)).getFirst()
        ));

        assertContains(code,
            "neko_get_int_array_region",
            "neko_set_int_array_region",
            "neko_get_array_length(env, arr)",
            "PUSH_O(neko_new_int_array(env, len));",
            "PUSH_O(neko_new_object_array(env, len, cls, NULL));",
            "PUSH_O(neko_multi_new_array(env, 2, __dims, \"[[I\"));"
        );
    }

    @Test
    void opcodeTranslator_fieldOpsResolveFieldIdsAndAccessors() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")).getFirst(),
            translator.translate(new FieldInsnNode(Opcodes.PUTFIELD, "java/lang/String", "value", "[B")).getFirst()
        ));

        assertContains(code,
            "jfieldID fid =",
            "PUSH_O(",
            "jobject obj = POP_O();",
            "fid, val);"
        );
    }

    @Test
    void opcodeTranslator_invokeOpsBuildJniCallSequences() {
        OpcodeTranslator translator = translator();
        String code = render(List.of(
            translator.translate(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false)).getFirst(),
            translator.translate(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)).getFirst()
        ));

        assertContains(code,
            "jclass cls =",
            "jmethodID mid =",
            "__args",
            "jobject obj = POP_O();"
        );
    }

    @Test
    void opcodeTranslator_objectOpsAllocateAndCheckTypes() {
        OpcodeTranslator translator = translator();
        translator.beginMethod("pkg/Owner", "demo", "()V", true);
        String code = render(List.of(
            translator.translate(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder")).getFirst(),
            translator.translate(new TypeInsnNode(Opcodes.INSTANCEOF, "java/lang/String")).getFirst(),
            translator.translate(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String")).getFirst()
        ));

        assertContains(code,
            "neko_alloc_object(env, cls)",
            "neko_is_instance_of(env, obj, cls)",
            "ClassCastException",
            "goto __neko_exception_exit;"
        );
    }

    private static OpcodeTranslator translator() {
        return new OpcodeTranslator(new CCodeGenerator(12345L), Map.of());
    }

    private static String render(List<CStatement> statements) {
        StringBuilder builder = new StringBuilder();
        for (CStatement statement : statements) {
            if (statement instanceof CStatement.RawC rawC) {
                builder.append(rawC.code());
            } else if (statement instanceof CStatement.Goto go) {
                builder.append("goto ").append(go.label()).append(';');
            } else if (statement instanceof CStatement.Label label) {
                builder.append(label.name()).append(':');
            } else {
                builder.append(statement);
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private static void assertContains(String text, String... expectedParts) {
        for (String expectedPart : expectedParts) {
            assertTrue(text.contains(expectedPart), () -> "Expected C snippet to contain `" + expectedPart + "` but got:\n" + text);
        }
    }
}
