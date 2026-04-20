package dev.nekoobfuscator.test;

import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.native_.translator.NativeTranslator;
import dev.nekoobfuscator.native_.translator.NativeTranslator.MethodSelection;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import dev.nekoobfuscator.native_.translator.OpcodeTranslator;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
            translator.translate(new InsnNode(Opcodes.ARETURN)).getFirst(),
            translator.translate(new InsnNode(Opcodes.RETURN)).getFirst(),
            translator.translate(new InsnNode(Opcodes.NOP)).getFirst(),
            translator.translate(new IincInsnNode(4, 3)).getFirst()
        ));

        assertContains(code,
            "return POP_I();",
            "return POP_L();",
            "return (void*)POP_O();",
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
    void opcodeTranslator_ldcClassUsesCachedKlassResolver() {
        OpcodeTranslator translator = translator();
        translator.beginMethod("nk/test/sample/SampleOwner", "sampleMethod", "()Ljava/lang/Class;", true);

        String code = render(translator.translate(new LdcInsnNode(Type.getObjectType("nk/test/sample/SampleType"))));

        assertContains(code,
            "neko_ldc_class_site_oop(thread,",
            "PUSH_O(__ldc);"
        );
        assertFalse(code.contains("resolved_cache_handle"), code);
    }

    @Test
    void opcodeTranslator_ldcArrayClassUsesSiteResolver() {
        OpcodeTranslator translator = translator();
        translator.beginMethod("nk/test/sample/SampleOwner", "sampleArrayMethod", "()Ljava/lang/Class;", true);

        String code = render(translator.translate(new LdcInsnNode(Type.getType("[Lnk/test/sample/SampleType;"))));

        assertContains(code,
            "neko_ldc_class_site_oop(thread,",
            "PUSH_O(__ldc);"
        );
    }

    @Test
    void primitiveFieldFastPath() {
        for (char primitive : primitiveFieldTypes()) {
            String desc = String.valueOf(primitive);

            String instanceGetBody = translatedBodySection(translateSingleMethod(
                primitiveFieldOwner("FieldGet" + primitive, "value", desc, 0, primitiveReturnInsn(primitive), false)
            ));
            assertTrue(instanceGetBody.contains("neko_fast_get_" + primitive + "_field("), instanceGetBody);
            assertFalse(instanceGetBody.contains(jniFieldGetterName(primitive) + "(env,"), instanceGetBody);

            String instancePutBody = translatedBodySection(translateSingleMethod(
                primitiveFieldOwner("FieldPut" + primitive, "value", desc, primitiveLoadOpcode(primitive), Opcodes.RETURN, false)
            ));
            assertTrue(instancePutBody.contains("neko_fast_set_" + primitive + "_field("), instancePutBody);
            assertFalse(instancePutBody.contains(jniFieldSetterName(primitive) + "(env,"), instancePutBody);

            String staticGetBody = translatedBodySection(translateSingleMethod(
                primitiveFieldOwner("FieldGetStatic" + primitive, "value", desc, 0, primitiveReturnInsn(primitive), true)
            ));
            assertTrue(staticGetBody.contains("neko_field_site_static_base(thread, __site)"), staticGetBody);
            assertTrue(staticGetBody.contains("neko_field_read_" + primitive + "(__base, __site)"), staticGetBody);
            assertFalse(staticGetBody.contains(jniStaticFieldGetterName(primitive) + "(env,"), staticGetBody);

            String staticPutBody = translatedBodySection(translateSingleMethod(
                primitiveFieldOwner("FieldPutStatic" + primitive, "value", desc, primitiveLoadOpcode(primitive), Opcodes.RETURN, true)
            ));
            assertTrue(staticPutBody.contains("neko_field_site_static_base(thread, __site)"), staticPutBody);
            assertTrue(staticPutBody.contains("neko_field_write_" + primitive + "(__base, __site, val)"), staticPutBody);
            assertFalse(staticPutBody.contains(jniStaticFieldSetterName(primitive) + "(env,"), staticPutBody);
        }
    }

    @Test
    void primitiveArrayScalarFastPath() {
        for (ArrayFastCase testCase : primitiveArrayFastCases()) {
            String loadBody = translatedBodySection(translateSingleMethod(primitiveArrayLoadOwner(testCase)));
            assertTrue(loadBody.contains("neko_fast_" + testCase.helperPrefix() + "aload("), loadBody);
            assertFalse(loadBody.contains(testCase.jniGetHelper() + "(env,"), loadBody);

            String storeBody = translatedBodySection(translateSingleMethod(primitiveArrayStoreOwner(testCase)));
            assertTrue(storeBody.contains("neko_fast_" + testCase.helperPrefix() + "astore("), storeBody);
            assertFalse(storeBody.contains(testCase.jniSetHelper() + "(env,"), storeBody);
        }
    }

    @Test
    void objectArrayOpcodesUnchanged() {
        String aaloadBody = translatedBodySection(translateSingleMethod(objectArrayLoadOwner()));
        assertContains(aaloadBody, "neko_get_object_array_element(");

        String aastoreBody = translatedBodySection(translateSingleMethod(objectArrayStoreOwner()));
        assertContains(aastoreBody, "neko_set_object_array_element(");
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
            "POP_O();"
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

    @Test
    void methodHandleExactBridgeNoArrayAlloc() {
        for (boolean invokeExact : List.of(false, true)) {
            TranslationArtifact artifact = translateSingleMethodArtifact(methodHandleBridgeOwner(invokeExact));
            String body = translatedBodySection(artifact.source());

            assertTrue(body.contains("neko_call_static_int_method_a("), body);
            assertTrue(body.contains("neko$mh$"), body);

            int mhLoad = body.indexOf("jobject __mh = POP_O();");
            int callSite = body.indexOf("neko_call_static_int_method_a(", mhLoad);
            assertTrue(mhLoad >= 0 && callSite > mhLoad, body);
            String between = body.substring(mhLoad, callSite);
            assertFalse(between.contains("neko_new_object_array("), between);
            assertFalse(between.contains("neko_set_object_array_element("), between);

            MethodNode bridge = artifact.classNode().methods.stream()
                .filter(candidate -> candidate.name.startsWith("neko$mh$"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing MethodHandle bridge method"));
            assertTrue(bridge.desc.startsWith("(Ljava/lang/invoke/MethodHandle;I)I"), bridge.desc);
        }
    }

    @Test
    void methodHandleUnknownDescriptorFallsBack() {
        for (boolean invokeExact : List.of(false, true)) {
            OpcodeTranslator translator = translator();
            translator.beginMethod("pkg/FallbackMh", "run", "(Ljava/lang/invoke/MethodHandle;I)I", true);

            String code = render(translator.translate(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                invokeExact ? "invokeExact" : "invoke",
                "(I)I",
                false
            )));

            assertContains(code, "neko_call_mh(", "neko_new_object_array(", "neko_set_object_array_element(");
            assertFalse(code.contains("neko_call_static_int_method_a("), code);
        }
    }

    @Test
    void invokeVirtualUsesReceiverKeyCache() {
        String source = translateSingleMethod(virtualInvokeOwner());
        String body = translatedBodySection(source);

        assertContains(body, "neko_icache_dispatch(", "&neko_icache_");
        assertTrue(source.contains("neko_receiver_key("), source);
        assertTrue(Pattern.compile("neko_call_nonvirtual_\\w+_method_a\\(").matcher(source).find(), source);
        assertTrue(source.contains("neko_call_object_method_a(") || source.contains("neko_call_int_method_a(") || source.contains("neko_call_void_method_a("), source);
    }

    @Test
    void invokeInterfaceUsesReceiverKeyCache() {
        String source = translateSingleMethod(interfaceInvokeOwner());
        String body = translatedBodySection(source);

        assertContains(body, "neko_icache_dispatch(", "JNI_TRUE", "&neko_icache_");
        assertTrue(source.contains("neko_receiver_key("), source);
        assertTrue(Pattern.compile("neko_call_nonvirtual_\\w+_method_a\\(").matcher(source).find(), source);
        assertTrue(source.contains("neko_call_object_method_a(") || source.contains("neko_call_int_method_a(") || source.contains("neko_call_void_method_a("), source);
    }

    @Test
    void invokeStaticSpecialFinalUnchanged() {
        String source = translateMethods(
            multiTargetHelperClass(),
            multiTargetBaseClass(),
            multiTargetFinalClass(),
            multiTargetCallerClass()
        );
        String body = translatedBodySection(source, "Java_pkg_MultiTargetCaller_run");

        assertContains(body, "Java_pkg_MultiTargetHelper_staticValue", "Java_pkg_MultiTargetBase_baseValue", "Java_pkg_MultiTargetFinal_finalValue");
        assertFalse(body.contains("neko_receiver_key("), body);
        assertFalse(body.contains("neko_icache_"), body);
    }

    private static OpcodeTranslator translator() {
        return new OpcodeTranslator(new CCodeGenerator(12345L), Map.of());
    }

    private static List<Character> primitiveFieldTypes() {
        return List.of('Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D');
    }

    private static List<ArrayFastCase> primitiveArrayFastCases() {
        return List.of(
            new ArrayFastCase('Z', "b", Opcodes.BALOAD, Opcodes.BASTORE, "[Z", "neko_get_byte_array_region", "neko_set_byte_array_region"),
            new ArrayFastCase('B', "b", Opcodes.BALOAD, Opcodes.BASTORE, "[B", "neko_get_byte_array_region", "neko_set_byte_array_region"),
            new ArrayFastCase('C', "c", Opcodes.CALOAD, Opcodes.CASTORE, "[C", "neko_get_char_array_region", "neko_set_char_array_region"),
            new ArrayFastCase('S', "s", Opcodes.SALOAD, Opcodes.SASTORE, "[S", "neko_get_short_array_region", "neko_set_short_array_region"),
            new ArrayFastCase('I', "i", Opcodes.IALOAD, Opcodes.IASTORE, "[I", "neko_get_int_array_region", "neko_set_int_array_region"),
            new ArrayFastCase('J', "l", Opcodes.LALOAD, Opcodes.LASTORE, "[J", "neko_get_long_array_region", "neko_set_long_array_region"),
            new ArrayFastCase('F', "f", Opcodes.FALOAD, Opcodes.FASTORE, "[F", "neko_get_float_array_region", "neko_set_float_array_region"),
            new ArrayFastCase('D', "d", Opcodes.DALOAD, Opcodes.DASTORE, "[D", "neko_get_double_array_region", "neko_set_double_array_region")
        );
    }

    private static String translatedBodySection(String source) {
        int functionIndex = source.indexOf("JNIEXPORT ");
        assertTrue(functionIndex >= 0, () -> "Missing translated function in generated C.\n" + source);
        return source.substring(functionIndex);
    }

    private static String translatedBodySection(String source, String functionName) {
        int functionIndex = source.indexOf("JNIEXPORT jint JNICALL " + functionName);
        assertTrue(functionIndex >= 0, () -> "Missing translated function `" + functionName + "` in generated C.\n" + source);
        return source.substring(functionIndex);
    }

    private static TranslationArtifact translateSingleMethodArtifact(ClassNode classNode) {
        L1Class owner = new L1Class(classNode);
        MethodNode method = classNode.methods.stream()
            .filter(candidate -> !"<init>".equals(candidate.name) && (candidate.access & Opcodes.ACC_NATIVE) == 0)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No translated method found"));
        NativeTranslator translator = new NativeTranslator("unit", false, false, 12345L);
        String source = translator.translate(List.of(new MethodSelection(owner, owner.findMethod(method.name, method.desc)))).source();
        return new TranslationArtifact(source, classNode);
    }

    private static String translateSingleMethod(ClassNode classNode) {
        return translateSingleMethodArtifact(classNode).source();
    }

    private static String translateMethods(ClassNode... classNodes) {
        NativeTranslator translator = new NativeTranslator("unit", false, false, 12345L);
        List<MethodSelection> selections = new ArrayList<>();
        for (ClassNode classNode : classNodes) {
            L1Class owner = new L1Class(classNode);
            for (MethodNode method : classNode.methods) {
                if ((method.access & Opcodes.ACC_NATIVE) == 0 && !"<init>".equals(method.name)) {
                    selections.add(new MethodSelection(owner, owner.findMethod(method.name, method.desc)));
                }
            }
        }
        return translator.translate(selections).source();
    }

    private static ClassNode methodHandleBridgeOwner(boolean invokeExact) {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V9;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/MhBridge" + (invokeExact ? "Exact" : "Invoke");
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        MethodNode method = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "run",
            "(Ljava/lang/invoke/MethodHandle;I)I",
            null,
            new String[] {"java/lang/Throwable"}
        );
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/invoke/MethodHandle",
            invokeExact ? "invokeExact" : "invoke",
            "(I)I",
            false
        ));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
        classNode.methods.add(method);
        return classNode;
    }

    private static ClassNode virtualInvokeOwner() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/VirtualInvokeOwner";
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Lpkg/VirtualDispatchTarget;)Ljava/lang/String;", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "pkg/VirtualDispatchTarget", "value", "()Ljava/lang/String;", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        classNode.methods.add(method);
        return classNode;
    }

    private static ClassNode interfaceInvokeOwner() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/InterfaceInvokeOwner";
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Lpkg/Greeter;)Ljava/lang/String;", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "pkg/Greeter", "greet", "()Ljava/lang/String;", true));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        classNode.methods.add(method);
        return classNode;
    }

    private static ClassNode multiTargetHelperClass() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/MultiTargetHelper";
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "staticValue", "()I", null, null);
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 0;
        classNode.methods.add(method);
        return classNode;
    }

    private static ClassNode multiTargetBaseClass() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/MultiTargetBase";
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "baseValue", "()I", null, null);
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        classNode.methods.add(method);
        return classNode;
    }

    private static ClassNode multiTargetFinalClass() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        classNode.name = "pkg/MultiTargetFinal";
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "finalValue", "()I", null, null);
        method.instructions.add(new InsnNode(Opcodes.ICONST_3));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        classNode.methods.add(method);
        return classNode;
    }

    private static ClassNode multiTargetCallerClass() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/MultiTargetCaller";
        classNode.superName = "pkg/MultiTargetBase";
        classNode.methods = new ArrayList<>();

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "run", "(Lpkg/MultiTargetFinal;)I", null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "pkg/MultiTargetHelper", "staticValue", "()I", false));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "pkg/MultiTargetBase", "baseValue", "()I", false));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "pkg/MultiTargetFinal", "finalValue", "()I", false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
        classNode.methods.add(method);
        return classNode;
    }

    private static ClassNode primitiveFieldOwner(String ownerSuffix, String fieldName, String desc, int valueLoadOpcode, int returnOpcode, boolean isStaticField) {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/" + ownerSuffix;
        classNode.superName = "java/lang/Object";
        classNode.fields = new ArrayList<>();
        classNode.methods = new ArrayList<>();
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | (isStaticField ? Opcodes.ACC_STATIC : 0), fieldName, desc, null, null));

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "run", methodDescriptor(returnOpcode, desc), null, null);
        if (!isStaticField) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        if (valueLoadOpcode != 0) {
            method.instructions.add(new VarInsnNode(valueLoadOpcode, isStaticField ? 1 : 1));
        }
        method.instructions.add(new FieldInsnNode(isStaticField
            ? (returnOpcode == Opcodes.RETURN ? Opcodes.PUTSTATIC : Opcodes.GETSTATIC)
            : (returnOpcode == Opcodes.RETURN ? Opcodes.PUTFIELD : Opcodes.GETFIELD), classNode.name, fieldName, desc));
        method.instructions.add(new InsnNode(returnOpcode));
        method.maxStack = 4;
        method.maxLocals = isStaticField
            ? (valueLoadOpcode == 0 ? 1 : 1 + Type.getType(desc).getSize())
            : (valueLoadOpcode == 0 ? 1 : 1 + Type.getType(desc).getSize());
        classNode.methods.add(method);
        return classNode;
    }

    private static ClassNode primitiveArrayLoadOwner(ArrayFastCase testCase) {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/ArrayLoad" + testCase.descriptorChar();
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(" + testCase.arrayDesc() + "I)" + arrayLoadReturnDesc(testCase.descriptorChar()), null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(testCase.loadOpcode()));
        method.instructions.add(new InsnNode(arrayLoadReturnOpcode(testCase.descriptorChar())));
        method.maxStack = 2;
        method.maxLocals = 2;
        classNode.methods.add(method);
        return classNode;
    }

    private static ClassNode primitiveArrayStoreOwner(ArrayFastCase testCase) {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/ArrayStore" + testCase.descriptorChar();
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        String valueDesc = arrayStoreValueDesc(testCase.descriptorChar());
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(" + testCase.arrayDesc() + "I" + valueDesc + ")V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new VarInsnNode(arrayStoreLoadOpcode(testCase.descriptorChar()), 2));
        method.instructions.add(new InsnNode(testCase.storeOpcode()));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = Type.getType(valueDesc).getSize() == 2 ? 4 : 3;
        method.maxLocals = 2 + Type.getType(valueDesc).getSize();
        classNode.methods.add(method);
        return classNode;
    }

    private static ClassNode objectArrayLoadOwner() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/ObjectArrayLoad";
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "([Ljava/lang/String;I)Ljava/lang/String;", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.AALOAD));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
        classNode.methods.add(method);
        return classNode;
    }

    private static ClassNode objectArrayStoreOwner() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/ObjectArrayStore";
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "([Ljava/lang/String;ILjava/lang/String;)V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.AASTORE));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 3;
        method.maxLocals = 3;
        classNode.methods.add(method);
        return classNode;
    }

    private static String methodDescriptor(int returnOpcode, String desc) {
        return returnOpcode == Opcodes.RETURN ? "(" + desc + ")V" : "()" + desc;
    }

    private static int primitiveReturnInsn(char primitive) {
        return switch (primitive) {
            case 'J' -> Opcodes.LRETURN;
            case 'F' -> Opcodes.FRETURN;
            case 'D' -> Opcodes.DRETURN;
            default -> Opcodes.IRETURN;
        };
    }

    private static int primitiveLoadOpcode(char primitive) {
        return switch (primitive) {
            case 'J' -> Opcodes.LLOAD;
            case 'F' -> Opcodes.FLOAD;
            case 'D' -> Opcodes.DLOAD;
            default -> Opcodes.ILOAD;
        };
    }

    private static String jniFieldGetterName(char primitive) {
        return switch (primitive) {
            case 'Z' -> "neko_get_boolean_field";
            case 'B' -> "neko_get_byte_field";
            case 'C' -> "neko_get_char_field";
            case 'S' -> "neko_get_short_field";
            case 'I' -> "neko_get_int_field";
            case 'J' -> "neko_get_long_field";
            case 'F' -> "neko_get_float_field";
            case 'D' -> "neko_get_double_field";
            default -> throw new IllegalArgumentException();
        };
    }

    private static String jniFieldSetterName(char primitive) {
        return jniFieldGetterName(primitive).replace("get", "set");
    }

    private static String jniStaticFieldGetterName(char primitive) {
        return switch (primitive) {
            case 'Z' -> "neko_get_static_boolean_field";
            case 'B' -> "neko_get_static_byte_field";
            case 'C' -> "neko_get_static_char_field";
            case 'S' -> "neko_get_static_short_field";
            case 'I' -> "neko_get_static_int_field";
            case 'J' -> "neko_get_static_long_field";
            case 'F' -> "neko_get_static_float_field";
            case 'D' -> "neko_get_static_double_field";
            default -> throw new IllegalArgumentException();
        };
    }

    private static String jniStaticFieldSetterName(char primitive) {
        return jniStaticFieldGetterName(primitive).replace("get", "set");
    }

    private static String arrayLoadReturnDesc(char primitive) {
        return switch (primitive) {
            case 'J' -> "J";
            case 'F' -> "F";
            case 'D' -> "D";
            default -> "I";
        };
    }

    private static int arrayLoadReturnOpcode(char primitive) {
        return switch (primitive) {
            case 'J' -> Opcodes.LRETURN;
            case 'F' -> Opcodes.FRETURN;
            case 'D' -> Opcodes.DRETURN;
            default -> Opcodes.IRETURN;
        };
    }

    private static String arrayStoreValueDesc(char primitive) {
        return switch (primitive) {
            case 'J' -> "J";
            case 'F' -> "F";
            case 'D' -> "D";
            default -> "I";
        };
    }

    private static int arrayStoreLoadOpcode(char primitive) {
        return switch (primitive) {
            case 'J' -> Opcodes.LLOAD;
            case 'F' -> Opcodes.FLOAD;
            case 'D' -> Opcodes.DLOAD;
            default -> Opcodes.ILOAD;
        };
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

    private record TranslationArtifact(String source, ClassNode classNode) {}

    private record ArrayFastCase(char descriptorChar, String helperPrefix, int loadOpcode, int storeOpcode, String arrayDesc,
                                 String jniGetHelper, String jniSetHelper) {}
}
