package dev.nekoobfuscator.test;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.core.ir.l3.CType;
import dev.nekoobfuscator.core.ir.l3.CVariable;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import dev.nekoobfuscator.native_.translator.NativeTranslator;
import dev.nekoobfuscator.native_.translator.NativeTranslator.MethodSelection;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

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
            "neko_native_impl_probe",
            CType.VOID,
            List.of(
                new CVariable("thread", CType.JOBJECT, 0),
                new CVariable("env", CType.JOBJECT, 1),
                new CVariable("self", CType.JOBJECT, 2)
            )
        );
        function.setMaxStack(0);
        function.setMaxLocals(1);
        function.addStatement(new CStatement.ReturnVoid());

        NativeMethodBinding binding = new NativeMethodBinding(
            owner.name(),
            "demo",
            "()V",
            "neko_native_entry_probe",
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
        assertTrue(source.contains("jint array_length_offset;"), source);
        assertTrue(source.contains("NEKO_FAST_INLINE jint neko_fast_array_length(jarray arr)"), source);
        assertFalse(source.contains("neko_fast_array_length(JNIEnv *env"), source);
        assertTrue(source.contains("NEKO_FAST_INLINE jint neko_fast_iaload(jarray arr"), source);
        assertTrue(source.contains("NEKO_FAST_INLINE void neko_fast_iastore(jarray arr"), source);
        assertFalse(source.contains("neko_fast_iaload(JNIEnv *env"), source);
        assertFalse(source.contains("neko_fast_iastore(JNIEnv *env"), source);
        assertTrue(source.contains("neko_select_oop_array_load_barrier();"), source);
        assertTrue(source.contains("neko_barrier_load_oop_array("), source);
        assertTrue(source.contains("neko_array_store_check("), source);
        assertTrue(source.contains("NEKO_FAST_INLINE jboolean neko_fast_is_instance_of(JNIEnv *env, jobject obj, jclass cls)"), source);
        assertTrue(source.contains("return neko_klass_is_subtype_of(value_klass, target_klass);"), source);
        assertTrue(source.contains("NEKO_FAST_INLINE jclass neko_fast_get_object_class(void *thread, jobject obj)"), source);
        assertTrue(source.contains("return (jclass)neko_klass_java_mirror_handle(thread, value_klass);"), source);
        assertTrue(source.contains("g_neko_runtime1_monitorenter_entry"), source);
        assertTrue(source.contains("g_neko_runtime1_monitorexit_entry"), source);
        assertTrue(source.contains("NEKO_FAST_INLINE void neko_fast_monitor_enter(void *thread, jobject obj, neko_monitor_record *rec)"), source);
        assertTrue(source.contains("neko_call_runtime1_monitorenter(g_neko_runtime1_monitorenter_entry, thread"), source);
        assertTrue(source.contains("neko_monitor_record monitors["), source);
        assertFalse(source.contains("static inline jint neko_monitor_enter"), source);
        assertFalse(source.contains("static inline jint neko_monitor_exit"), source);
        assertTrue(source.contains("static inline void neko_set_pending_exception(void *thread, jthrowable exc)"), source);
        assertTrue(source.contains("static inline void *neko_pending_exception_oop(void *thread)"), source);
        assertTrue(source.contains("static inline void neko_clear_pending_exception(void *thread)"), source);
        assertTrue(source.contains("static inline jthrowable neko_take_pending_exception(void *thread)"), source);
        assertTrue(source.contains("static inline void neko_raise_implicit_exception(void *thread, JNIEnv *env, jclass cls, void *ctor_method, void *ctor_entry"));
        assertFalse(source.contains("static inline jint neko_throw("), source);
        assertFalse(source.contains("static inline jint neko_throw_new("), source);
        assertFalse(source.contains("NEKO_JNI_FN_PTR"), source);
        assertFalse(source.contains("neko_jni_fn"), source);
        assertFalse(source.contains("return NEKO_JNI_FN_PTR(env, 228"), source);
        assertFalse(source.contains("neko_new_global_ref("), source);
        assertFalse(source.contains("neko_delete_local_ref("), source);
        assertFalse(source.contains("neko_find_class("), source);
        assertFalse(source.contains("neko_get_method_id("), source);
        assertFalse(source.contains("neko_new_object_array("), source);
        assertFalse(source.contains("neko_string_concat2("), source);
        assertFalse(source.contains("neko_string_concat_string("), source);
        assertTrue(source.contains("off_objarrayklass_element_klass"), source);
        assertTrue(source.contains("NEKO_FAST_INLINE jobject neko_fast_alloc_object(void *thread, JNIEnv *env, jclass cls)"), source);
        assertTrue(source.contains("off_klass_layout_helper"), source);
        assertTrue(source.contains("static void neko_boxing_cache_init(JNIEnv *env)"), source);
        assertTrue(source.contains("neko_boxing_cache_init(env);"), source);
        assertTrue(source.contains("return neko_box_call(thread, env, &g_neko_box_int, arg, neko_njx_S_L_I);"), source);
        assertTrue(source.contains("static jint neko_unbox_int(void *thread, JNIEnv *env, jobject obj)"), source);
        assertFalse(source.contains("static jobject neko_box_int(JNIEnv *env"), source);
        assertFalse(source.contains("NEKO_ENSURE_STATIC_METHOD_ID(g_box_int_mid"), source);
        assertFalse(source.contains("neko_call_int_method_a("), source);
        assertFalse(source.contains("neko_call_object_method_a("), source);
        assertFalse(source.contains("neko_call_static_object_method_a("), source);
        assertFalse(source.contains("neko_call_nonvirtual_object_method_a("), source);
        assertTrue(source.contains("neko_array_klass_bits_for_descriptor(env,"), source);
        assertTrue(source.contains("neko_fast_new_primitive_array(thread, env,"), source);
        assertTrue(source.contains("static void neko_ensure_class_initialized_once(JNIEnv *env, jclass cls, const char *owner, volatile jboolean *slot)"), source);
        assertTrue(source.contains("memset(array_oop + base, 0, ((size_t)len * ref_size));"), source);
        assertTrue(source.contains("memset(array_oop + base, 0, ((size_t)len * scale));"), source);
        assertTrue(source.contains("neko_refill_tlab_with_slow_byte_array(env, bytes > (size_t)INT32_MAX ? INT32_MAX : (jint)bytes);"), source);
        assertTrue(source.contains("""
    oop = (char*)neko_fast_tlab_alloc(thread, bytes);
    if (oop == NULL && env != NULL) {
        neko_refill_tlab_with_slow_byte_array(env, bytes > (size_t)INT32_MAX ? INT32_MAX : (jint)bytes);
        oop = (char*)neko_fast_tlab_alloc(thread, bytes);
    }
    if (oop == NULL) {
        fprintf(stderr, "[neko-direct] NEW TLAB allocation failed cls=%p klass=%p bytes=%zu\\n",
"""), source);
        assertTrue(source.contains("primitive array allocation direct path unavailable len=%d kind=%d"), source);
        assertFalse(source.contains("neko_new_object_array(env, 0, elemClass"), source);
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
        demo.instructions.add(new LdcInsnNode(Type.getObjectType(classNode.name)));
        demo.instructions.add(new InsnNode(Opcodes.POP));
        demo.instructions.add(new LdcInsnNode(Type.INT_TYPE));
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

        String bodySection = translatedBodySection(source, "neko_native_impl_0");
        assertFalse(Pattern.compile("NEKO_ENSURE_CLASS\\(").matcher(bodySection).find(), () -> failure("NEKO_ENSURE_CLASS(", bodySection));
        assertFalse(Pattern.compile("NEKO_ENSURE_METHOD(?:_ID)?\\(").matcher(bodySection).find(), () -> failure("NEKO_ENSURE_METHOD", bodySection));
        assertFalse(Pattern.compile("NEKO_ENSURE_FIELD(?:_ID)?\\(").matcher(bodySection).find(), () -> failure("NEKO_ENSURE_FIELD", bodySection));
        assertFalse(Pattern.compile("NEKO_ENSURE_STRING\\(").matcher(bodySection).find(), () -> failure("NEKO_ENSURE_STRING(", bodySection));
        assertFalse(bodySection.contains("neko_get_object_class(env, self)"), () -> failure("neko_get_object_class(env, self)", bodySection));
        assertFalse(bodySection.contains("neko_class_for_descriptor(env"), () -> failure("neko_class_for_descriptor(env", bodySection));
        assertTrue(bodySection.contains("neko_bind_owner_strings_"), () -> bodySection);
        assertTrue(bodySection.contains("neko_bound_current_owner_class(thread, env,"), () -> bodySection);
        assertTrue(bodySection.contains("neko_fast_get_object_field(thread, env,"), () -> bodySection);
        assertTrue(bodySection.contains("neko_fast_get_static_object_field(thread, env,"), () -> bodySection);
        assertFalse(bodySection.contains("if (cls != NULL && fid != NULL)"), () -> bodySection);
        assertFalse(bodySection.contains("if (fid != NULL)"), () -> bodySection);
        assertTrue(source.contains("neko_barrier_load_oop_field("), () -> source);
        assertTrue(source.contains("neko_select_oop_field_load_barrier();"), () -> source);
        assertFalse(source.contains("switch (g_neko_gc_barrier_kind)"), () -> source);
        assertFalse(source.contains("static inline jobject neko_get_object_field"), () -> source);
        assertTrue(source.contains("(void)neko_bind_string_slot(thread, env, &g_str_0, &g_str_0_oop, \"hello-bind\");"), () -> source);
        assertTrue(bodySection.contains("neko_bound_string(thread, env, &g_str_0, &g_str_0_oop, \"hello-bind\")"), () -> bodySection);
        assertTrue(source.contains("neko_bind_primitive_class_slot(env,"), () -> source);
        assertTrue(source.contains("neko_call_stub_guarded(&__stub_args);"), () -> source);
        assertTrue(source.contains("pushq %%rbx"), () -> source);
        assertTrue(source.contains("pushq %%r12"), () -> source);
        assertTrue(source.contains("static volatile jboolean g_cls_initialized_"), () -> source);
        assertTrue(source.contains("neko_ensure_class_initialized_once(env, cls,"), () -> source);
    }

    @Test
    void primitiveFieldsUseOnlyDirectOffsetHelpers() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/PrimitiveFields";
        classNode.superName = "java/lang/Object";
        classNode.fields = new ArrayList<>();
        classNode.methods = new ArrayList<>();
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "value", "I", null, null));
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "STATIC_VALUE", "I", null, null));

        MethodNode run = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        run.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        run.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, classNode.name, "value", "I"));
        run.instructions.add(new InsnNode(Opcodes.POP));
        run.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        run.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 7));
        run.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, classNode.name, "value", "I"));
        run.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, "STATIC_VALUE", "I"));
        run.instructions.add(new InsnNode(Opcodes.POP));
        run.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 9));
        run.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, "STATIC_VALUE", "I"));
        run.instructions.add(new InsnNode(Opcodes.RETURN));
        run.maxStack = 2;
        run.maxLocals = 1;
        classNode.methods.add(run);

        L1Class owner = new L1Class(classNode);
        NativeTranslator translator = new NativeTranslator("primitive-fields", false, false, 12345L);
        String source = translator.translate(List.of(new MethodSelection(owner, owner.findMethod("run", "()V")))).source();
        String bodySection = translatedBodySection(source, "neko_native_impl_0");

        assertTrue(bodySection.contains("neko_fast_get_I_field(env,"), () -> bodySection);
        assertTrue(bodySection.contains("neko_fast_set_I_field(env,"), () -> bodySection);
        assertTrue(bodySection.contains("neko_fast_get_static_I_field(env,"), () -> bodySection);
        assertTrue(bodySection.contains("neko_fast_set_static_I_field(env,"), () -> bodySection);
        assertFalse(bodySection.contains("if (fid != NULL)"), () -> bodySection);
        assertFalse(bodySection.contains("if (cls != NULL && fid != NULL)"), () -> bodySection);
        assertFalse(source.contains("static inline jint neko_get_int_field"), () -> source);
        assertFalse(source.contains("static inline void neko_set_int_field"), () -> source);
        assertFalse(source.contains("static inline jint neko_get_static_int_field"), () -> source);
        assertFalse(source.contains("static inline void neko_set_static_int_field"), () -> source);
    }

    @Test
    void objectFieldStoresUseBarrierAwareDirectHelpers() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "pkg/ObjectFields";
        classNode.superName = "java/lang/Object";
        classNode.fields = new ArrayList<>();
        classNode.methods = new ArrayList<>();
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "value", "Ljava/lang/String;", null, null));
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "STATIC_VALUE", "Ljava/lang/String;", null, null));

        MethodNode run = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        run.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        run.instructions.add(new LdcInsnNode("field-value"));
        run.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, classNode.name, "value", "Ljava/lang/String;"));
        run.instructions.add(new LdcInsnNode("static-value"));
        run.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, "STATIC_VALUE", "Ljava/lang/String;"));
        run.instructions.add(new InsnNode(Opcodes.RETURN));
        run.maxStack = 2;
        run.maxLocals = 1;
        classNode.methods.add(run);

        L1Class owner = new L1Class(classNode);
        NativeTranslator translator = new NativeTranslator("object-fields", false, false, 12345L);
        String source = translator.translate(List.of(new MethodSelection(owner, owner.findMethod("run", "()V")))).source();
        String bodySection = translatedBodySection(source, "neko_native_impl_0");

        assertTrue(bodySection.contains("neko_fast_set_object_field(thread, env,"), () -> bodySection);
        assertTrue(bodySection.contains("neko_fast_set_static_object_field(thread, env,"), () -> bodySection);
        assertFalse(bodySection.contains("if (fid != NULL)"), () -> bodySection);
        assertFalse(bodySection.contains("if (cls != NULL && fid != NULL)"), () -> bodySection);
        assertTrue(source.contains("neko_select_oop_field_store_barrier();"), () -> source);
        assertTrue(source.contains("neko_barrier_pre_store_oop_field("), () -> source);
        assertTrue(source.contains("neko_barrier_post_store_oop_field("), () -> source);
        assertFalse(source.contains("static inline void neko_set_object_field"), () -> source);
        assertFalse(source.contains("static inline void neko_set_static_object_field"), () -> source);
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
        Matcher matcher = Pattern.compile("static\\s+\\S+\\s+" + Pattern.quote(functionName) + "\\([^)]*\\) \\{").matcher(source);
        assertTrue(matcher.find(), () -> "Missing translated raw function `" + functionName + "` in generated C.\n" + source);
        return source.substring(matcher.start());
    }

    private static String failure(String needle, String text) {
        int index = text.indexOf(needle.replace("(?:_ID)?", ""));
        if (index < 0) {
            Matcher matcher = Pattern.compile(needle).matcher(text);
            index = matcher.find() ? matcher.start() : -1;
        }
        return "Unexpected match for `" + needle + "` at line " + lineNumber(text, index) + ": " + context(text, index);
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
}
