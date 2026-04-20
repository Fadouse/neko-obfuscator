package dev.nekoobfuscator.test;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l3.CExpression;
import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.core.ir.l3.CType;
import dev.nekoobfuscator.core.ir.l3.CVariable;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import dev.nekoobfuscator.native_.codegen.emit.Wave4aRuntimeApiEmitter;
import dev.nekoobfuscator.native_.translator.NativeTranslator;
import dev.nekoobfuscator.native_.translator.NativeTranslator.MethodSelection;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CCodeGeneratorTest {
    @Test
    void hotspotProbeEmitted() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/ProbeOwner";
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();
        classNode.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "demo", "()V", null, null));
        L1Class owner = new L1Class(classNode);

        CFunction function = new CFunction(
            "Java_pkg_ProbeOwner_demo",
            CType.VOID,
            List.of(new CVariable("env", CType.JOBJECT, 0), new CVariable("self", CType.JOBJECT, 1))
        );
        function.setMaxStack(0);
        function.setMaxLocals(1);
        function.addStatement(new CStatement.ReturnVoid());

        NativeMethodBinding binding = new NativeMethodBinding(
            owner.name(),
            "demo",
            "()V",
            function.name(),
            "neko_binding_demo",
            "()V",
            false,
            false
        );

        String source = new CCodeGenerator(12345L).generateSource(List.of(function), List.of(binding));

        assertTrue(source.contains("neko_hotspot_init("), source);
        assertTrue(source.contains("g_hotspot"), source);
        assertTrue(source.contains("JNI_OnLoad") && source.contains("neko_hotspot_init(env);"), source);
    }

    @Test
    void signaturePlanDistinguishesReferenceReturns() {
        NativeMethodBinding intBinding = new NativeMethodBinding(
            "pkg/SignatureOwner",
            "sum",
            "(II)I",
            "Java_pkg_SignatureOwner_sum",
            "neko_binding_sum",
            "(II)I",
            true,
            true
        );
        NativeMethodBinding objectBinding = new NativeMethodBinding(
            "pkg/SignatureOwner",
            "pick",
            "(II)Ljava/lang/Object;",
            "Java_pkg_SignatureOwner_pick",
            "neko_binding_pick",
            "(II)Ljava/lang/Object;",
            true,
            true
        );

        List<CCodeGenerator.SignatureInfo> signatures = new CCodeGenerator(12345L).signatureInfos(List.of(intBinding, objectBinding));
        boolean sawPrimitive = false;
        boolean sawReference = false;
        for (CCodeGenerator.SignatureInfo signature : signatures) {
            sawPrimitive |= "(II)I".equals(signature.key());
            sawReference |= "(II)L".equals(signature.key());
        }

        assertTrue(signatures.size() == 2, () -> "Expected split signatures for primitive vs reference returns: " + signatures);
        assertTrue(sawPrimitive, () -> "Missing primitive signature key: " + signatures);
        assertTrue(sawReference, () -> "Missing reference signature key: " + signatures);
    }

    @Test
    void objectReturnFunctionsUseRawOopAbiType() {
        CFunction function = new CFunction(
            "Java_pkg_ObjectReturn_identity",
            CType.JOBJECT,
            List.of(new CVariable("_this", CType.JOBJECT, 0))
        );
        function.setMaxStack(1);
        function.setMaxLocals(1);
        function.addStatement(new CStatement.Return(new CExpression.VarRef(function.params().getFirst())));

        NativeMethodBinding binding = new NativeMethodBinding(
            "pkg/ObjectReturn",
            "identity",
            "()Ljava/lang/Object;",
            function.name(),
            "neko_binding_identity",
            "()Ljava/lang/Object;",
            false,
            true
        );

        String source = new CCodeGenerator(12345L).generateSource(List.of(function), List.of(binding));

        assertTrue(source.contains("void* Java_pkg_ObjectReturn_identity(void* _this)"), source);
    }

    @Test
    void objectReturnStubsLeaveRaxUndisturbed() {
        NativeMethodBinding binding = new NativeMethodBinding(
            "pkg/ObjectReturn",
            "identity",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            "neko_impl_0",
            "neko_binding_identity",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            true,
            true
        );

        String assembly = new CCodeGenerator(12345L).generateAdditionalSources(List.of(binding)).getFirst().content();

        assertTrue(assembly.contains("call neko_sig_0_dispatch_static"), assembly);
        assertTrue(assembly.contains("mov rsp, r13\n    jmp r10"), assembly);
        assertFalse(assembly.contains("neko_encode_heap_oop_runtime"), assembly);
    }

    @Test
    void jniGlobalRefSlotDecoderUsesRawSlotForJdk8() throws Exception {
        String output = compileAndRunJniGlobalRefHarness(8);

        assertTrue(output.contains("raw-slot"), output);
    }

    @Test
    void jniGlobalRefSlotDecoderUsesRawSlotForJdk11() throws Exception {
        String output = compileAndRunJniGlobalRefHarness(11);

        assertTrue(output.contains("raw-slot"), output);
    }

    @Test
    void jniGlobalRefSlotDecoderUntagsJdk21StrongGlobals() throws Exception {
        String output = compileAndRunJniGlobalRefHarness(21);

        assertTrue(output.contains("raw-slot"), output);
    }

    @Test
    void bindTimeResolved() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/BindOwner";
        classNode.superName = "java/lang/Object";
        classNode.fields = new ArrayList<>();
        classNode.methods = new ArrayList<>();
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MESSAGE", "Ljava/lang/String;", null, null));
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "prefix", "Ljava/lang/String;", null, null));

        MethodNode virtualValue = new MethodNode(Opcodes.ACC_PUBLIC, "virtualValue", "()Ljava/lang/String;", null, null);
        virtualValue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        virtualValue.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, classNode.name, "prefix", "Ljava/lang/String;"));
        virtualValue.instructions.add(new InsnNode(Opcodes.ARETURN));
        virtualValue.maxStack = 1;
        virtualValue.maxLocals = 1;
        classNode.methods.add(virtualValue);

        MethodNode demo = new MethodNode(Opcodes.ACC_PUBLIC, "demo", "()Ljava/lang/String;", null, null);
        demo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        demo.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, classNode.name, "virtualValue", "()Ljava/lang/String;", false));
        demo.instructions.add(new InsnNode(Opcodes.POP));
        demo.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, "MESSAGE", "Ljava/lang/String;"));
        demo.instructions.add(new InsnNode(Opcodes.POP));
        demo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        demo.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, classNode.name, "prefix", "Ljava/lang/String;"));
        demo.instructions.add(new InsnNode(Opcodes.POP));
        demo.instructions.add(new LdcInsnNode("hello-bind"));
        demo.instructions.add(new InsnNode(Opcodes.ARETURN));
        demo.maxStack = 1;
        demo.maxLocals = 1;
        classNode.methods.add(demo);

        L1Class owner = new L1Class(classNode);
        NativeTranslator translator = new NativeTranslator("bind", false, false, 12345L);
        String source = translator.translate(List.of(new MethodSelection(owner, owner.findMethod("demo", "()Ljava/lang/String;")))).source();

        Matcher bindMatcher = Pattern.compile("neko_bind_owner_[A-Za-z0-9_]+\\s*\\(").matcher(source);
        assertTrue(bindMatcher.find(), () -> "Missing bind-owner initializer in generated C.\n" + source);

        String bodySection = translatedBodySection(source, "Java_pkg_BindOwner_demo");
        assertFalse(Pattern.compile("NEKO_ENSURE_CLASS\\(").matcher(bodySection).find(), () -> failure("NEKO_ENSURE_CLASS(", bodySection));
        assertFalse(Pattern.compile("NEKO_ENSURE_METHOD(?:_ID)?\\(").matcher(bodySection).find(), () -> failure("NEKO_ENSURE_METHOD", bodySection));
        assertFalse(Pattern.compile("NEKO_ENSURE_FIELD(?:_ID)?\\(").matcher(bodySection).find(), () -> failure("NEKO_ENSURE_FIELD", bodySection));
        assertFalse(Pattern.compile("NEKO_ENSURE_STRING\\(").matcher(bodySection).find(), () -> failure("NEKO_ENSURE_STRING(", bodySection));
        assertFalse(bodySection.contains("neko_get_object_class(env, self)"), () -> failure("neko_get_object_class(env, self)", bodySection));
    }

    @Test
    void ldcClassSitesCacheKlassPointersAtBindTime() {
        String source = minimalGeneratedSource(ldcClassProbeBinding());

        assertTrue(source.contains("void* cached_klass;"), source);
        assertTrue(source.contains("neko_publish_prepared_ldc_class_site("), source);
        assertTrue(source.contains("site->kind == NEKO_LDC_KIND_CLASS"), source);
        assertTrue(source.contains("__atomic_store_n(&site->cached_klass, prepared_klass, __ATOMIC_RELEASE);"), source);
        assertTrue(source.contains("NEKO_TRACE(0, \"[nk] ldc-cls bind %s %p\", signature, prepared_klass);"), source);
    }

    @Test
    void shouldEmitLdcClassSiteForNamedType() {
        String descriptor = "Lnk/test/sample/SampleA;";
        String source = translatedLdcClassSource("nk/test/sample/SampleA", Type.getObjectType("nk/test/sample/SampleA"));

        assertContains(source,
            "uint32_t owner_class_index;",
            "jclass *owner_class_slot;",
            "void* cached_klass;",
            "neko_ldc_class_site_oop(thread,"
        );
        assertLdcClassBlob(source, descriptor);
    }

    @Test
    void shouldEmitLdcClassSiteForInterface() {
        String descriptor = "Lnk/test/sample/SampleIface;";
        String source = translatedLdcClassSource("nk/test/sample/SampleIface", Type.getObjectType("nk/test/sample/SampleIface"));

        assertContains(source,
            "neko_ldc_class_site_oop(thread,"
        );
        assertLdcClassBlob(source, descriptor);
    }

    @Test
    void shouldEmitLdcClassSiteForArray() {
        String descriptor = "[Lnk/test/sample/SampleA;";
        String source = translatedLdcClassSource("nk/test/sample/SampleArray", Type.getType("[Lnk/test/sample/SampleA;"));

        assertContains(source,
            "neko_ldc_class_site_oop(thread,"
        );
        assertLdcClassBlob(source, descriptor);
    }

    @Test
    void shouldEmitLdcStringSiteForAsciiLiteral() {
        String source = translatedLdcStringSource("nk/test/sample/SampleStringAscii", "hello-neko");

        assertContains(source,
            "neko_ldc_string_site_oop(env,",
            expectedUtf8BlobFragment("hello-neko")
        );
    }

    @Test
    void shouldEmitLdcStringSiteForUnicodeSupplementaryLiteral() {
        String literal = "Neko猫𐐷";
        String source = translatedLdcStringSource("nk/test/sample/SampleStringUnicode", literal);

        assertContains(source,
            "neko_ldc_string_site_oop(env,",
            expectedModifiedUtf8BlobFragment(literal)
        );
    }

    @Test
    void ldcStringResolverInternsBeforeCaching() {
        String source = minimalGeneratedSource(ldcStringProbeBinding());

        int newString = source.indexOf("NewStringUTF");
        int intern = source.indexOf("\"intern\"");
        int global = source.indexOf("global = neko_new_global_ref(env, interned);");

        assertTrue(newString >= 0, source);
        assertTrue(intern > newString, source);
        assertTrue(global > intern, source);
    }

    @Test
    void ldcStringSiteCasPublishesSingleHandleAndReleasesLoser() {
        String source = minimalGeneratedSource(ldcStringProbeBinding());

        assertContains(source,
            "__atomic_compare_exchange_n(&site->resolved_cache_handle, &expected, (void*)global, 0, __ATOMIC_RELEASE, __ATOMIC_ACQUIRE)",
            "neko_delete_global_ref(env, global);",
            "return (jobject)expected;"
        );
    }

    @Test
    void ldcClassMirrorResolverUsesJdk9DoubleDerefPath() throws Exception {
        String output = compileAndRunLdcClassMirrorHarness(11);

        assertTrue(output.contains("mirror-ok"), output);
    }

    @Test
    void icacheScaffoldEmitted() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/IcacheOwner";
        classNode.superName = "java/lang/Object";
        classNode.fields = new ArrayList<>();
        classNode.methods = new ArrayList<>();
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "prefix", "Ljava/lang/String;", null, null));

        MethodNode virtualValue = new MethodNode(Opcodes.ACC_PUBLIC, "virtualValue", "()Ljava/lang/String;", null, null);
        virtualValue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        virtualValue.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, classNode.name, "prefix", "Ljava/lang/String;"));
        virtualValue.instructions.add(new InsnNode(Opcodes.ARETURN));
        virtualValue.maxStack = 1;
        virtualValue.maxLocals = 1;
        classNode.methods.add(virtualValue);

        MethodNode demo = new MethodNode(Opcodes.ACC_PUBLIC, "demo", "()Ljava/lang/String;", null, null);
        demo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        demo.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, classNode.name, "virtualValue", "()Ljava/lang/String;", false));
        demo.instructions.add(new InsnNode(Opcodes.ARETURN));
        demo.maxStack = 1;
        demo.maxLocals = 1;
        classNode.methods.add(demo);

        L1Class owner = new L1Class(classNode);
        NativeTranslator translator = new NativeTranslator("icache", false, false, 12345L);
        String source = translator.translate(List.of(new MethodSelection(owner, owner.findMethod("demo", "()Ljava/lang/String;")))).source();

        assertTrue(source.contains("typedef struct neko_icache_site") || source.contains("struct neko_icache_site"), source);
        assertTrue(source.contains("receiver_key;"), source);
        assertTrue(source.contains("target;"), source);
        assertTrue(source.contains("target_kind;"), source);
        assertTrue(source.contains("miss_count;"), source);
        assertTrue(source.contains("cached_class;"), source);
        assertTrue(source.contains("neko_receiver_key("), source);
        assertTrue(source.contains("neko_receiver_key_supported("), source);
        assertTrue(source.contains("typedef jvalue (*neko_icache_direct_stub)") || source.contains("typedef jvalue(*neko_icache_direct_stub)"), source);
        assertTrue(source.contains("neko_icache_dispatch("), source);
    }

    private static String translatedBodySection(String source, String functionName) {
        int functionIndex = source.indexOf("JNIEXPORT jobject JNICALL " + functionName);
        assertTrue(functionIndex >= 0, () -> "Missing translated function `" + functionName + "` in generated C.\n" + source);
        return source.substring(functionIndex);
    }

    private static String compileAndRunJniGlobalRefHarness(int javaSpecVersion) throws Exception {
        Path tempDir = Files.createTempDirectory("neko_jni_global_ref_");
        Path sourceFile = tempDir.resolve("jni_global_ref.c");
        Path binaryFile = tempDir.resolve("jni_global_ref");
        Files.writeString(sourceFile, jniGlobalRefHarnessSource(javaSpecVersion));

        ProcessBuilder compile = new ProcessBuilder("cc", "-std=c11", sourceFile.toString(), "-o", binaryFile.toString());
        compile.redirectErrorStream(true);
        Process compileProcess = compile.start();
        String compileOutput = new String(compileProcess.getInputStream().readAllBytes());
        assertTrue(compileProcess.waitFor() == 0, compileOutput);

        ProcessBuilder run = new ProcessBuilder(binaryFile.toString());
        run.redirectErrorStream(true);
        Process runProcess = run.start();
        String runOutput = new String(runProcess.getInputStream().readAllBytes());
        assertTrue(runProcess.waitFor() == 0, runOutput);
        return runOutput;
    }

    private static String jniGlobalRefHarnessSource(int javaSpecVersion) {
        return """
            #include <stddef.h>
            #include <stdint.h>
            #include <stdio.h>

            typedef void* oop;
            typedef void* jobject;
            typedef struct Klass Klass;
            typedef struct NekoVmLayout {
                int java_spec_version;
            } NekoVmLayout;

            """ + jniGlobalRefHelpers() + """

            int main(void) {
                uintptr_t marker = 0x12345678u;
                oop mirror = (oop)&marker;
                oop slot = mirror;
                void *volatile *raw_slot = (void *volatile *)&slot;
                NekoVmLayout layout = { %d };
                uintptr_t handle = layout.java_spec_version >= 21 ? ((uintptr_t)raw_slot + 2u) : (uintptr_t)raw_slot;
                (void)layout;

                if (neko_decode_jni_global_ref_slot((jobject)handle, layout.java_spec_version) != raw_slot) {
                    fputs("slot-mismatch\\n", stderr);
                    return 1;
                }

                if (*neko_decode_jni_global_ref_slot((jobject)handle, layout.java_spec_version) != mirror) {
                    fputs("mirror-mismatch\\n", stderr);
                    return 1;
                }

                puts("raw-slot");
                return 0;
            }
            """.formatted(javaSpecVersion);
    }

    private static String jniGlobalRefHelpers() {
        String source = minimalGeneratedSource();
        String startMarker = "static inline void *volatile *neko_decode_jni_global_ref_slot(jobject global_ref, int jdk_feature) {";
        String endMarker = "static jboolean neko_resolve_field_site_with_class(JNIEnv *env, void *thread, NekoManifestFieldSite *site, jclass owner_class) {";
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start, () -> "Missing JNI global-ref helpers in generated C.\n" + source);
        return source.substring(start, end);
    }

    private static String minimalGeneratedSource() {
        return minimalGeneratedSource(baseProbeBinding());
    }

    private static String minimalGeneratedSource(NativeMethodBinding binding) {
        CFunction function = new CFunction(
            "Java_nk_test_sample_SampleProbe_run",
            CType.VOID,
            List.of(new CVariable("env", CType.JOBJECT, 0), new CVariable("self", CType.JOBJECT, 1))
        );
        function.setMaxStack(0);
        function.setMaxLocals(1);
        function.addStatement(new CStatement.ReturnVoid());

        String source = new CCodeGenerator(12345L).generateSource(List.of(function), List.of(binding));
        assertTrue(source.contains(new Wave4aRuntimeApiEmitter().renderWave4ASupport().substring(0, 32)), source);
        return source;
    }

    private static NativeMethodBinding baseProbeBinding() {
        return new NativeMethodBinding(
            "nk/test/sample/SampleProbe",
            "run",
            "()V",
            "Java_nk_test_sample_SampleProbe_run",
            "neko_binding_sample_probe",
            "()V",
            false,
            false
        );
    }

    private static NativeMethodBinding ldcClassProbeBinding() {
        return new NativeMethodBinding(
            "nk/test/sample/SampleClassProbe",
            "sampleMethod",
            "()Ljava/lang/Class;",
            "Java_nk_test_sample_SampleClassProbe_sampleMethod",
            "neko_binding_sample_class_probe",
            "()Ljava/lang/Class;",
            true,
            true
        );
    }

    private static NativeMethodBinding ldcStringProbeBinding() {
        return new NativeMethodBinding(
            "nk/test/sample/SampleStringProbe",
            "sampleMethod",
            "()Ljava/lang/String;",
            "Java_nk_test_sample_SampleStringProbe_sampleMethod",
            "neko_binding_sample_string_probe",
            "()Ljava/lang/String;",
            true,
            true
        );
    }

    private static String translatedLdcClassSource(String ownerName, Type type) {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = ownerName;
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sampleMethod", "()Ljava/lang/Class;", null, null);
        method.instructions.add(new LdcInsnNode(type));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 0;
        classNode.methods.add(method);

        L1Class owner = new L1Class(classNode);
        NativeTranslator translator = new NativeTranslator("ldc-class", false, false, 12345L);
        return translator.translate(List.of(new MethodSelection(owner, owner.findMethod("sampleMethod", "()Ljava/lang/Class;")))).source();
    }

    private static String translatedLdcStringSource(String ownerName, String literal) {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = ownerName;
        classNode.superName = "java/lang/Object";
        classNode.methods = new ArrayList<>();

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sampleMethod", "()Ljava/lang/String;", null, null);
        method.instructions.add(new LdcInsnNode(literal));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 0;
        classNode.methods.add(method);

        L1Class owner = new L1Class(classNode);
        NativeTranslator translator = new NativeTranslator("ldc-string", false, false, 12345L);
        return translator.translate(List.of(new MethodSelection(owner, owner.findMethod("sampleMethod", "()Ljava/lang/String;")))).source();
    }

    private static String compileAndRunLdcClassMirrorHarness(int javaSpecVersion) throws Exception {
        Path tempDir = Files.createTempDirectory("neko_mirror_resolver_");
        Path sourceFile = tempDir.resolve("ldc_class_mirror.c");
        Path binaryFile = tempDir.resolve("ldc_class_mirror");
        Files.writeString(sourceFile, ldcClassMirrorHarnessSource(javaSpecVersion));

        ProcessBuilder compile = new ProcessBuilder("cc", "-std=c11", sourceFile.toString(), "-o", binaryFile.toString());
        compile.redirectErrorStream(true);
        Process compileProcess = compile.start();
        String compileOutput = new String(compileProcess.getInputStream().readAllBytes());
        assertTrue(compileProcess.waitFor() == 0, compileOutput);

        ProcessBuilder run = new ProcessBuilder(binaryFile.toString());
        run.redirectErrorStream(true);
        Process runProcess = run.start();
        String runOutput = new String(runProcess.getInputStream().readAllBytes());
        assertTrue(runProcess.waitFor() == 0, runOutput);
        return runOutput;
    }

    private static String ldcClassMirrorHarnessSource(int javaSpecVersion) {
        return """
            #include <stddef.h>
            #include <stdint.h>
            #include <stdio.h>

            typedef uint8_t jboolean;
            #define JNI_FALSE 0
            #define JNI_TRUE 1
            typedef uint32_t u4;
            typedef void* oop;
            typedef struct Klass Klass;
            typedef struct NekoVmLayout {
                int java_spec_version;
                ptrdiff_t off_klass_java_mirror;
                ptrdiff_t off_oophandle_obj;
                uint32_t wave4a_disabled;
            } NekoVmLayout;

            struct SampleKlass {
                void *java_mirror_handle;
            };

            static NekoVmLayout g_neko_vm_layout = { %d, (ptrdiff_t)offsetof(struct SampleKlass, java_mirror_handle), 0, JNI_FALSE };

            static inline jboolean neko_wave4a_enabled(void) {
                return g_neko_vm_layout.wave4a_disabled ? JNI_FALSE : JNI_TRUE;
            }

            %s

            typedef struct NekoManifestLdcSite {
                uint32_t site_id;
                uint8_t kind;
                uint8_t _pad0;
                uint16_t _pad1;
                const uint8_t* raw_constant_utf8;
                size_t raw_constant_utf8_len;
                void* cached_klass;
                void* resolved_cache_handle;
            } NekoManifestLdcSite;

            static inline void* neko_resolve_ldc_class_handle(const NekoManifestLdcSite *site) {
                void *cached_klass;
                if (site == NULL) return NULL;
                cached_klass = site->cached_klass;
                if (cached_klass == NULL) return NULL;
                return neko_rt_mirror_from_klass_nosafepoint((Klass*)cached_klass);
            }

            int main(void) {
                uintptr_t marker = 0x42424242u;
                oop mirror = (oop)&marker;
                oop slot = mirror;
                oop *slot_ptr = &slot;
                struct SampleKlass klass = { .java_mirror_handle = &slot_ptr };
                NekoManifestLdcSite site = {0};
                site.cached_klass = &klass;
                if (neko_resolve_ldc_class_handle(&site) != mirror) {
                    fputs("mirror-mismatch\\n", stderr);
                    return 1;
                }
                puts("mirror-ok");
                return 0;
            }
            """.formatted(javaSpecVersion, mirrorHelpers());
    }

    private static String mirrorHelpers() {
        String source = minimalGeneratedSource();
        String startMarker = "static inline void* neko_resolve_mirror_locator_from_klass(const NekoVmLayout *layout, Klass *klass) {";
        String endMarker = "__attribute__((visibility(\"default\"))) oop neko_rt_static_base_from_holder_nosafepoint(Klass *holder) {";
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start, () -> "Missing mirror helpers in generated C.\n" + source);
        return source.substring(start, end);
    }

    private static String failure(String needle, String text) {
        int index = text.indexOf(needle.replace("(?:_ID)?", ""));
        if (index < 0) {
            Matcher matcher = Pattern.compile(needle).matcher(text);
            index = matcher.find() ? matcher.start() : -1;
        }
        return "Unexpected match for `" + needle + "` at line " + lineNumber(text, index) + ": " + context(text, index);
    }

    private static String expectedUtf8BlobFragment(String descriptor) {
        byte[] bytes = descriptor.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format("0x%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String expectedModifiedUtf8BlobFragment(String literal) {
        byte[] bytes;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);
            data.writeUTF(literal);
            data.flush();
            bytes = out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < bytes.length; i++) {
            if (i > 2) {
                sb.append(", ");
            }
            sb.append(String.format("0x%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static int lineNumber(String text, int index) {
        if (index < 0) {
            return -1;
        }
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String context(String text, int index) {
        if (index < 0) {
            return "<no match context>";
        }
        int start = Math.max(0, index - 40);
        int end = Math.min(text.length(), index + 80);
        return text.substring(start, end).replace('\n', ' ');
    }

    private static void assertContains(String text, String... expectedParts) {
        for (String expectedPart : expectedParts) {
            assertTrue(text.contains(expectedPart), () -> "Expected generated text to contain `" + expectedPart + "` but got:\n" + text);
        }
    }

    private static void assertLdcClassBlob(String source, String descriptor) {
        assertContains(source,
            "static const uint8_t g_neko_utf8_",
            expectedUtf8BlobFragment(descriptor)
        );
        assertTrue(Pattern.compile("\\{[^\\n]*g_neko_utf8_\\d+_\\d+, " + descriptor.length() + "u,").matcher(source).find(), source);
    }
}
