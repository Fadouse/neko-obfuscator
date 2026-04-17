package dev.nekoobfuscator.native_.codegen;

import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.core.ir.l3.CVariable;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.Type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CCodeGenerator {
    @SuppressWarnings("unused")
    private final SymbolTableGenerator symbols;
    private final LinkedHashMap<String, Integer> classSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> methodSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> fieldSlotIndex = new LinkedHashMap<>();
    private int stringCacheCount;

    public CCodeGenerator(long masterSeed) {
        this.symbols = new SymbolTableGenerator(masterSeed);
    }

    public void configureStringCacheCount(int stringCacheCount) {
        this.stringCacheCount = stringCacheCount;
    }

    public int internClass(String internalName) {
        return classSlotIndex.computeIfAbsent(internalName, ignored -> classSlotIndex.size());
    }

    public int internMethod(String owner, String name, String desc, boolean isStatic) {
        String key = owner + "." + name + desc + "/" + (isStatic ? "S" : "V");
        return methodSlotIndex.computeIfAbsent(key, ignored -> methodSlotIndex.size());
    }

    public int internField(String owner, String name, String desc, boolean isStatic) {
        String key = owner + "." + name + desc + "/" + (isStatic ? "S" : "I");
        return fieldSlotIndex.computeIfAbsent(key, ignored -> fieldSlotIndex.size());
    }

    public String classSlotName(String internalName) {
        return "g_cls_" + internClass(internalName);
    }

    public String methodSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mid_" + internMethod(owner, name, desc, isStatic);
    }

    public String fieldSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_fid_" + internField(owner, name, desc, isStatic);
    }

    public String generateHeader(List<NativeMethodBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("#ifndef NEKO_NATIVE_H\n");
        sb.append("#define NEKO_NATIVE_H\n\n");
        sb.append("#include <jni.h>\n\n");
        for (NativeMethodBinding binding : bindings) {
            sb.append(renderPrototype(binding)).append(";\n");
        }
        sb.append("\n#endif\n");
        return sb.toString();
    }

    public String generateSource(List<CFunction> functions, List<NativeMethodBinding> bindings) {
        StringBuilder body = new StringBuilder();
        for (CFunction function : functions) {
            body.append(renderFunction(function)).append("\n");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#include \"neko_native.h\"\n");
        sb.append("#include <stdint.h>\n");
        sb.append("#include <stdlib.h>\n");
        sb.append("#include <string.h>\n");
        sb.append("#include <math.h>\n\n");
        sb.append(renderResolutionCaches());
        sb.append(renderRuntimeSupport());
        sb.append(renderNativeBindingRegistry(bindings));
        sb.append(body);
        sb.append(renderJniOnLoad(bindings));
        return sb.toString();
    }

    private String renderPrototype(NativeMethodBinding binding) {
        StringBuilder sb = new StringBuilder();
        sb.append("JNIEXPORT ").append(jniType(Type.getReturnType(binding.descriptor()))).append(" JNICALL ")
            .append(binding.cFunctionName()).append("(JNIEnv *env, ")
            .append(binding.isStatic() ? "jclass clazz" : "jobject self");
        Type[] args = Type.getArgumentTypes(binding.descriptor());
        for (int i = 0; i < args.length; i++) {
            sb.append(", ").append(jniType(args[i])).append(" p").append(i);
        }
        sb.append(")");
        return sb.toString();
    }

    private String renderFunction(CFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append("JNIEXPORT ").append(fn.returnType().jniName()).append(" JNICALL ").append(fn.name()).append('(');
        for (int i = 0; i < fn.params().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(renderParam(fn.params().get(i)));
        }
        sb.append(") {\n");
        if (requiresLocalCapacity(fn)) {
            sb.append("    neko_ensure_local_capacity(env, 8192);\n");
        }
        sb.append("    neko_slot stack[").append(fn.maxStack() + 16).append("];\n");
        sb.append("    int sp = 0;\n");
        sb.append("    neko_slot locals[").append(fn.maxLocals() + 8).append("];\n");
        sb.append("    memset(locals, 0, sizeof(locals));\n");
        for (CStatement statement : fn.body()) {
            sb.append(renderStatement(statement));
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String renderStatement(CStatement statement) {
        if (statement instanceof CStatement.RawC raw) {
            return "    " + raw.code() + "\n";
        }
        if (statement instanceof CStatement.Label label) {
            return label.name() + ": ;\n";
        }
        if (statement instanceof CStatement.Goto go) {
            return "    goto " + go.label() + ";\n";
        }
        if (statement instanceof CStatement.ReturnVoid) {
            return "    return;\n";
        }
        if (statement instanceof CStatement.Return ret) {
            return "    return " + ret.value() + ";\n";
        }
        if (statement instanceof CStatement.Comment comment) {
            return "    /* " + comment.text() + " */\n";
        }
        throw new IllegalStateException("Unsupported C statement in generator: " + statement.getClass().getSimpleName());
    }

    private String renderJniOnLoad(List<NativeMethodBinding> bindings) {
        return "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {\n"
            + "    JNIEnv *env = NULL;\n"
            + "    (void)reserved;\n"
            + "    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;\n"
            + "    jclass loaderClass = neko_find_class(env, \"dev/nekoobfuscator/runtime/NekoNativeLoader\");\n"
            + "    if (loaderClass == NULL) return JNI_ERR;\n"
            + "    if (neko_register_natives(env, loaderClass, g_neko_loader_methods, 1) != 0) return JNI_ERR;\n"
            + "    return JNI_VERSION_1_6;\n"
            + "}\n";
    }

    private String renderNativeBindingRegistry(List<NativeMethodBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<NativeMethodBinding>> byOwner = new LinkedHashMap<>();
        for (NativeMethodBinding binding : bindings) {
            byOwner.computeIfAbsent(binding.ownerInternalName(), ignored -> new java.util.ArrayList<>()).add(binding);
        }
        sb.append("// === Native binding registry ===\n");
        int groupIndex = 0;
        for (Map.Entry<String, List<NativeMethodBinding>> entry : byOwner.entrySet()) {
            sb.append("static JNINativeMethod g_owner_bindings_").append(groupIndex).append("[] = {\n");
            for (NativeMethodBinding binding : entry.getValue()) {
                sb.append("    {\"").append(c(binding.methodName())).append("\", \"")
                    .append(c(binding.descriptor())).append("\", (void*)&")
                    .append(binding.cFunctionName()).append("},\n");
            }
            sb.append("};\n");
            groupIndex++;
        }
        sb.append("JNIEXPORT void JNICALL Java_dev_nekoobfuscator_runtime_NekoNativeLoader_nekoBindClass(JNIEnv *env, jclass loaderClass, jclass target, jstring internalOwner);\n");
        sb.append("static JNINativeMethod g_neko_loader_methods[] = {\n");
        sb.append("    {\"nekoBindClass\", \"(Ljava/lang/Class;Ljava/lang/String;)V\", (void*)&Java_dev_nekoobfuscator_runtime_NekoNativeLoader_nekoBindClass},\n");
        sb.append("};\n\n");
        sb.append("static jint neko_bind_owner(JNIEnv *env, jclass target, const char *owner) {\n");
        sb.append("    if (target == NULL || owner == NULL) return JNI_ERR;\n");
        groupIndex = 0;
        for (Map.Entry<String, List<NativeMethodBinding>> entry : byOwner.entrySet()) {
            sb.append("    if (strcmp(owner, \"").append(c(entry.getKey())).append("\") == 0) return neko_register_natives(env, target, g_owner_bindings_")
                .append(groupIndex).append(", sizeof(g_owner_bindings_").append(groupIndex).append(") / sizeof(g_owner_bindings_")
                .append(groupIndex).append("[0]));\n");
            groupIndex++;
        }
        sb.append("    return JNI_ERR;\n");
        sb.append("}\n\n");
        sb.append("JNIEXPORT void JNICALL Java_dev_nekoobfuscator_runtime_NekoNativeLoader_nekoBindClass(JNIEnv *env, jclass loaderClass, jclass target, jstring internalOwner) {\n");
        sb.append("    (void)loaderClass;\n");
        sb.append("    if (target == NULL || internalOwner == NULL) return;\n");
        sb.append("    const char *owner = neko_get_string_utf_chars(env, internalOwner);\n");
        sb.append("    if (owner == NULL) return;\n");
        sb.append("    jint status = neko_bind_owner(env, target, owner);\n");
        sb.append("    neko_release_string_utf_chars(env, internalOwner, owner);\n");
        sb.append("    if (status != 0) {\n");
        sb.append("        jclass errCls = neko_find_class(env, \"java/lang/UnsatisfiedLinkError\");\n");
        sb.append("        if (errCls != NULL) neko_throw_new(env, errCls, \"Failed to bind native methods for class\");\n");
        sb.append("    }\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String renderResolutionCaches() {
        StringBuilder sb = new StringBuilder();
        sb.append("// === Global resolution caches ===\n");
        for (Map.Entry<String, Integer> entry : classSlotIndex.entrySet()) {
            sb.append("static jclass g_cls_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
        }
        for (Map.Entry<String, Integer> entry : methodSlotIndex.entrySet()) {
            sb.append("static jmethodID g_mid_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
        }
        for (Map.Entry<String, Integer> entry : fieldSlotIndex.entrySet()) {
            sb.append("static jfieldID g_fid_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
        }
        for (int i = 0; i < stringCacheCount; i++) {
            sb.append("static jstring g_str_").append(i).append(" = NULL;\n");
        }
        sb.append("\n");
        sb.append("#define NEKO_ENSURE_CLASS(slot, env, name) ((slot) != NULL ? (slot) : ((slot) = (jclass)neko_new_global_ref((env), neko_find_class((env), (name)))))\n");
        sb.append("#define NEKO_ENSURE_STRING(slot, env, utf) ((slot) != NULL ? (slot) : ((slot) = (jstring)neko_new_global_ref((env), neko_new_string_utf((env), (utf)))))\n");
        sb.append("#define NEKO_ENSURE_METHOD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_method_id((env), (cls), (name), (desc))))\n");
        sb.append("#define NEKO_ENSURE_STATIC_METHOD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_static_method_id((env), (cls), (name), (desc))))\n");
        sb.append("#define NEKO_ENSURE_FIELD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_field_id((env), (cls), (name), (desc))))\n");
        sb.append("#define NEKO_ENSURE_STATIC_FIELD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_static_field_id((env), (cls), (name), (desc))))\n\n");
        return sb.toString();
    }

    private boolean requiresLocalCapacity(CFunction fn) {
        for (CStatement statement : fn.body()) {
            if (statement instanceof CStatement.RawC raw) {
                String code = raw.code();
                if (code.contains("neko_new_") || code.contains("neko_call_") || code.contains("neko_get_object_array_element")
                    || code.contains("neko_set_object_array_element") || code.contains("neko_get_object_class")
                    || code.contains("NEKO_ENSURE_STRING") || code.contains("neko_string_concat")
                    || code.contains("neko_class_for_descriptor") || code.contains("neko_resolve_indy")
                    || code.contains("neko_resolve_constant_dynamic")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String renderRuntimeSupport() {
        return """
typedef union {
    jint i;
    jlong j;
    jfloat f;
    jdouble d;
    jobject o;
} neko_slot;

#define PUSH_I(v) do { jint __tmp = (jint)(v); stack[sp++].i = __tmp; } while (0)
#define PUSH_L(v) do { jlong __tmp = (jlong)(v); stack[sp].j = __tmp; stack[sp + 1].j = __tmp; sp += 2; } while (0)
#define PUSH_F(v) do { jfloat __tmp = (jfloat)(v); stack[sp++].f = __tmp; } while (0)
#define PUSH_D(v) do { jdouble __tmp = (jdouble)(v); stack[sp].d = __tmp; stack[sp + 1].d = __tmp; sp += 2; } while (0)
#define PUSH_O(v) do { jobject __tmp = (jobject)(v); stack[sp++].o = __tmp; } while (0)
#define POP_I() (stack[--sp].i)
#define POP_L() (sp -= 2, stack[sp].j)
#define POP_F() (stack[--sp].f)
#define POP_D() (sp -= 2, stack[sp].d)
#define POP_O() (stack[--sp].o)

#define NEKO_JNI_FN_PTR(env, idx, ret, ...) ((ret (*)(JNIEnv*, ##__VA_ARGS__))(*((void***)(env)))[idx])

static inline jclass neko_find_class(JNIEnv *env, const char *name) { return NEKO_JNI_FN_PTR(env, 6, jclass, const char*)(env, name); }
static inline jclass neko_get_object_class(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 31, jclass, jobject)(env, obj); }
static inline jboolean neko_is_instance_of(JNIEnv *env, jobject obj, jclass clazz) { return NEKO_JNI_FN_PTR(env, 32, jboolean, jobject, jclass)(env, obj, clazz); }
static inline jmethodID neko_get_method_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 33, jmethodID, jclass, const char*, const char*)(env, c, n, s); }
static inline jmethodID neko_get_static_method_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 113, jmethodID, jclass, const char*, const char*)(env, c, n, s); }
static inline jfieldID neko_get_field_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 94, jfieldID, jclass, const char*, const char*)(env, c, n, s); }
static inline jfieldID neko_get_static_field_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 144, jfieldID, jclass, const char*, const char*)(env, c, n, s); }
static inline jint neko_throw(JNIEnv *env, jthrowable exc) { return NEKO_JNI_FN_PTR(env, 13, jint, jthrowable)(env, exc); }
static inline jint neko_throw_new(JNIEnv *env, jclass cls, const char *msg) { return NEKO_JNI_FN_PTR(env, 14, jint, jclass, const char*)(env, cls, msg); }
static inline jthrowable neko_exception_occurred(JNIEnv *env) { return NEKO_JNI_FN_PTR(env, 15, jthrowable)(env); }
static inline void neko_exception_clear(JNIEnv *env) { NEKO_JNI_FN_PTR(env, 17, void)(env); }
static inline jint neko_ensure_local_capacity(JNIEnv *env, jint capacity) { return NEKO_JNI_FN_PTR(env, 26, jint, jint)(env, capacity); }
static inline jobject neko_new_global_ref(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 21, jobject, jobject)(env, obj); }
static inline void neko_delete_local_ref(JNIEnv *env, jobject obj) { NEKO_JNI_FN_PTR(env, 23, void, jobject)(env, obj); }
static inline jobject neko_alloc_object(JNIEnv *env, jclass cls) { return NEKO_JNI_FN_PTR(env, 27, jobject, jclass)(env, cls); }
static inline jobject neko_new_object_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 30, jobject, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jobject neko_call_object_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 36, jobject, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jboolean neko_call_boolean_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 39, jboolean, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jbyte neko_call_byte_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 42, jbyte, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jchar neko_call_char_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 45, jchar, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jshort neko_call_short_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 48, jshort, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jint neko_call_int_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 51, jint, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jlong neko_call_long_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 54, jlong, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jfloat neko_call_float_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 57, jfloat, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jdouble neko_call_double_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 60, jdouble, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline void neko_call_void_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 63, void, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jobject neko_call_nonvirtual_object_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 66, jobject, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jboolean neko_call_nonvirtual_boolean_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 69, jboolean, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jbyte neko_call_nonvirtual_byte_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 72, jbyte, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jchar neko_call_nonvirtual_char_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 75, jchar, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jshort neko_call_nonvirtual_short_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 78, jshort, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jint neko_call_nonvirtual_int_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 81, jint, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jlong neko_call_nonvirtual_long_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 84, jlong, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jfloat neko_call_nonvirtual_float_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 87, jfloat, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jdouble neko_call_nonvirtual_double_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 90, jdouble, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline void neko_call_nonvirtual_void_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 93, void, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jobject neko_get_object_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 95, jobject, jobject, jfieldID)(env, obj, fid); }
static inline jboolean neko_get_boolean_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 96, jboolean, jobject, jfieldID)(env, obj, fid); }
static inline jbyte neko_get_byte_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 97, jbyte, jobject, jfieldID)(env, obj, fid); }
static inline jchar neko_get_char_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 98, jchar, jobject, jfieldID)(env, obj, fid); }
static inline jshort neko_get_short_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 99, jshort, jobject, jfieldID)(env, obj, fid); }
static inline jint neko_get_int_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 100, jint, jobject, jfieldID)(env, obj, fid); }
static inline jlong neko_get_long_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 101, jlong, jobject, jfieldID)(env, obj, fid); }
static inline jfloat neko_get_float_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 102, jfloat, jobject, jfieldID)(env, obj, fid); }
static inline jdouble neko_get_double_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 103, jdouble, jobject, jfieldID)(env, obj, fid); }
static inline void neko_set_object_field(JNIEnv *env, jobject obj, jfieldID fid, jobject val) { NEKO_JNI_FN_PTR(env, 104, void, jobject, jfieldID, jobject)(env, obj, fid, val); }
static inline void neko_set_boolean_field(JNIEnv *env, jobject obj, jfieldID fid, jboolean val) { NEKO_JNI_FN_PTR(env, 105, void, jobject, jfieldID, jboolean)(env, obj, fid, val); }
static inline void neko_set_byte_field(JNIEnv *env, jobject obj, jfieldID fid, jbyte val) { NEKO_JNI_FN_PTR(env, 106, void, jobject, jfieldID, jbyte)(env, obj, fid, val); }
static inline void neko_set_char_field(JNIEnv *env, jobject obj, jfieldID fid, jchar val) { NEKO_JNI_FN_PTR(env, 107, void, jobject, jfieldID, jchar)(env, obj, fid, val); }
static inline void neko_set_short_field(JNIEnv *env, jobject obj, jfieldID fid, jshort val) { NEKO_JNI_FN_PTR(env, 108, void, jobject, jfieldID, jshort)(env, obj, fid, val); }
static inline void neko_set_int_field(JNIEnv *env, jobject obj, jfieldID fid, jint val) { NEKO_JNI_FN_PTR(env, 109, void, jobject, jfieldID, jint)(env, obj, fid, val); }
static inline void neko_set_long_field(JNIEnv *env, jobject obj, jfieldID fid, jlong val) { NEKO_JNI_FN_PTR(env, 110, void, jobject, jfieldID, jlong)(env, obj, fid, val); }
static inline void neko_set_float_field(JNIEnv *env, jobject obj, jfieldID fid, jfloat val) { NEKO_JNI_FN_PTR(env, 111, void, jobject, jfieldID, jfloat)(env, obj, fid, val); }
static inline void neko_set_double_field(JNIEnv *env, jobject obj, jfieldID fid, jdouble val) { NEKO_JNI_FN_PTR(env, 112, void, jobject, jfieldID, jdouble)(env, obj, fid, val); }
static inline jobject neko_call_static_object_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 116, jobject, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jboolean neko_call_static_boolean_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 119, jboolean, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jbyte neko_call_static_byte_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 122, jbyte, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jchar neko_call_static_char_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 125, jchar, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jshort neko_call_static_short_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 128, jshort, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jint neko_call_static_int_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 131, jint, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jlong neko_call_static_long_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 134, jlong, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jfloat neko_call_static_float_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 137, jfloat, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jdouble neko_call_static_double_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 140, jdouble, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline void neko_call_static_void_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 143, void, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jobject neko_get_static_object_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 145, jobject, jclass, jfieldID)(env, cls, fid); }
static inline jboolean neko_get_static_boolean_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 146, jboolean, jclass, jfieldID)(env, cls, fid); }
static inline jbyte neko_get_static_byte_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 147, jbyte, jclass, jfieldID)(env, cls, fid); }
static inline jchar neko_get_static_char_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 148, jchar, jclass, jfieldID)(env, cls, fid); }
static inline jshort neko_get_static_short_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 149, jshort, jclass, jfieldID)(env, cls, fid); }
static inline jint neko_get_static_int_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 150, jint, jclass, jfieldID)(env, cls, fid); }
static inline jlong neko_get_static_long_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 151, jlong, jclass, jfieldID)(env, cls, fid); }
static inline jfloat neko_get_static_float_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 152, jfloat, jclass, jfieldID)(env, cls, fid); }
static inline jdouble neko_get_static_double_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 153, jdouble, jclass, jfieldID)(env, cls, fid); }
static inline void neko_set_static_object_field(JNIEnv *env, jclass cls, jfieldID fid, jobject val) { NEKO_JNI_FN_PTR(env, 154, void, jclass, jfieldID, jobject)(env, cls, fid, val); }
static inline void neko_set_static_boolean_field(JNIEnv *env, jclass cls, jfieldID fid, jboolean val) { NEKO_JNI_FN_PTR(env, 155, void, jclass, jfieldID, jboolean)(env, cls, fid, val); }
static inline void neko_set_static_byte_field(JNIEnv *env, jclass cls, jfieldID fid, jbyte val) { NEKO_JNI_FN_PTR(env, 156, void, jclass, jfieldID, jbyte)(env, cls, fid, val); }
static inline void neko_set_static_char_field(JNIEnv *env, jclass cls, jfieldID fid, jchar val) { NEKO_JNI_FN_PTR(env, 157, void, jclass, jfieldID, jchar)(env, cls, fid, val); }
static inline void neko_set_static_short_field(JNIEnv *env, jclass cls, jfieldID fid, jshort val) { NEKO_JNI_FN_PTR(env, 158, void, jclass, jfieldID, jshort)(env, cls, fid, val); }
static inline void neko_set_static_int_field(JNIEnv *env, jclass cls, jfieldID fid, jint val) { NEKO_JNI_FN_PTR(env, 159, void, jclass, jfieldID, jint)(env, cls, fid, val); }
static inline void neko_set_static_long_field(JNIEnv *env, jclass cls, jfieldID fid, jlong val) { NEKO_JNI_FN_PTR(env, 160, void, jclass, jfieldID, jlong)(env, cls, fid, val); }
static inline void neko_set_static_float_field(JNIEnv *env, jclass cls, jfieldID fid, jfloat val) { NEKO_JNI_FN_PTR(env, 161, void, jclass, jfieldID, jfloat)(env, cls, fid, val); }
static inline void neko_set_static_double_field(JNIEnv *env, jclass cls, jfieldID fid, jdouble val) { NEKO_JNI_FN_PTR(env, 162, void, jclass, jfieldID, jdouble)(env, cls, fid, val); }
static inline jsize neko_get_string_length(JNIEnv *env, jstring str) { return NEKO_JNI_FN_PTR(env, 164, jsize, jstring)(env, str); }
static inline jstring neko_new_string_utf(JNIEnv *env, const char *utf) { return NEKO_JNI_FN_PTR(env, 167, jstring, const char*)(env, utf); }
static inline const char* neko_get_string_utf_chars(JNIEnv *env, jstring str) { return NEKO_JNI_FN_PTR(env, 169, const char*, jstring, jboolean*)(env, str, NULL); }
static inline void neko_release_string_utf_chars(JNIEnv *env, jstring str, const char *chars) { NEKO_JNI_FN_PTR(env, 170, void, jstring, const char*)(env, str, chars); }
static inline jsize neko_get_array_length(JNIEnv *env, jarray arr) { return NEKO_JNI_FN_PTR(env, 171, jsize, jarray)(env, arr); }
static inline jobjectArray neko_new_object_array(JNIEnv *env, jsize len, jclass cls, jobject init) { return NEKO_JNI_FN_PTR(env, 172, jobjectArray, jsize, jclass, jobject)(env, len, cls, init); }
static inline jobject neko_get_object_array_element(JNIEnv *env, jobjectArray arr, jsize index) { return NEKO_JNI_FN_PTR(env, 173, jobject, jobjectArray, jsize)(env, arr, index); }
static inline void neko_set_object_array_element(JNIEnv *env, jobjectArray arr, jsize index, jobject val) { NEKO_JNI_FN_PTR(env, 174, void, jobjectArray, jsize, jobject)(env, arr, index, val); }
static inline jbooleanArray neko_new_boolean_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 175, jbooleanArray, jsize)(env, len); }
static inline jbyteArray neko_new_byte_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 176, jbyteArray, jsize)(env, len); }
static inline jcharArray neko_new_char_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 177, jcharArray, jsize)(env, len); }
static inline jshortArray neko_new_short_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 178, jshortArray, jsize)(env, len); }
static inline jintArray neko_new_int_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 179, jintArray, jsize)(env, len); }
static inline jlongArray neko_new_long_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 180, jlongArray, jsize)(env, len); }
static inline jfloatArray neko_new_float_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 181, jfloatArray, jsize)(env, len); }
static inline jdoubleArray neko_new_double_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 182, jdoubleArray, jsize)(env, len); }
static inline void neko_get_boolean_array_region(JNIEnv *env, jbooleanArray arr, jsize start, jsize len, jboolean *buf) { NEKO_JNI_FN_PTR(env, 199, void, jbooleanArray, jsize, jsize, jboolean*)(env, arr, start, len, buf); }
static inline void neko_get_byte_array_region(JNIEnv *env, jbyteArray arr, jsize start, jsize len, jbyte *buf) { NEKO_JNI_FN_PTR(env, 200, void, jbyteArray, jsize, jsize, jbyte*)(env, arr, start, len, buf); }
static inline void neko_get_char_array_region(JNIEnv *env, jcharArray arr, jsize start, jsize len, jchar *buf) { NEKO_JNI_FN_PTR(env, 201, void, jcharArray, jsize, jsize, jchar*)(env, arr, start, len, buf); }
static inline void neko_get_short_array_region(JNIEnv *env, jshortArray arr, jsize start, jsize len, jshort *buf) { NEKO_JNI_FN_PTR(env, 202, void, jshortArray, jsize, jsize, jshort*)(env, arr, start, len, buf); }
static inline void neko_get_int_array_region(JNIEnv *env, jintArray arr, jsize start, jsize len, jint *buf) { NEKO_JNI_FN_PTR(env, 203, void, jintArray, jsize, jsize, jint*)(env, arr, start, len, buf); }
static inline void neko_get_long_array_region(JNIEnv *env, jlongArray arr, jsize start, jsize len, jlong *buf) { NEKO_JNI_FN_PTR(env, 204, void, jlongArray, jsize, jsize, jlong*)(env, arr, start, len, buf); }
static inline void neko_get_float_array_region(JNIEnv *env, jfloatArray arr, jsize start, jsize len, jfloat *buf) { NEKO_JNI_FN_PTR(env, 205, void, jfloatArray, jsize, jsize, jfloat*)(env, arr, start, len, buf); }
static inline void neko_get_double_array_region(JNIEnv *env, jdoubleArray arr, jsize start, jsize len, jdouble *buf) { NEKO_JNI_FN_PTR(env, 206, void, jdoubleArray, jsize, jsize, jdouble*)(env, arr, start, len, buf); }
static inline void neko_set_boolean_array_region(JNIEnv *env, jbooleanArray arr, jsize start, jsize len, const jboolean *buf) { NEKO_JNI_FN_PTR(env, 207, void, jbooleanArray, jsize, jsize, const jboolean*)(env, arr, start, len, buf); }
static inline void neko_set_byte_array_region(JNIEnv *env, jbyteArray arr, jsize start, jsize len, const jbyte *buf) { NEKO_JNI_FN_PTR(env, 208, void, jbyteArray, jsize, jsize, const jbyte*)(env, arr, start, len, buf); }
static inline void neko_set_char_array_region(JNIEnv *env, jcharArray arr, jsize start, jsize len, const jchar *buf) { NEKO_JNI_FN_PTR(env, 209, void, jcharArray, jsize, jsize, const jchar*)(env, arr, start, len, buf); }
static inline void neko_set_short_array_region(JNIEnv *env, jshortArray arr, jsize start, jsize len, const jshort *buf) { NEKO_JNI_FN_PTR(env, 210, void, jshortArray, jsize, jsize, const jshort*)(env, arr, start, len, buf); }
static inline void neko_set_int_array_region(JNIEnv *env, jintArray arr, jsize start, jsize len, const jint *buf) { NEKO_JNI_FN_PTR(env, 211, void, jintArray, jsize, jsize, const jint*)(env, arr, start, len, buf); }
static inline void neko_set_long_array_region(JNIEnv *env, jlongArray arr, jsize start, jsize len, const jlong *buf) { NEKO_JNI_FN_PTR(env, 212, void, jlongArray, jsize, jsize, const jlong*)(env, arr, start, len, buf); }
static inline void neko_set_float_array_region(JNIEnv *env, jfloatArray arr, jsize start, jsize len, const jfloat *buf) { NEKO_JNI_FN_PTR(env, 213, void, jfloatArray, jsize, jsize, const jfloat*)(env, arr, start, len, buf); }
static inline void neko_set_double_array_region(JNIEnv *env, jdoubleArray arr, jsize start, jsize len, const jdouble *buf) { NEKO_JNI_FN_PTR(env, 214, void, jdoubleArray, jsize, jsize, const jdouble*)(env, arr, start, len, buf); }
static inline jint neko_register_natives(JNIEnv *env, jclass cls, const JNINativeMethod *methods, jint count) { return NEKO_JNI_FN_PTR(env, 215, jint, jclass, const JNINativeMethod*, jint)(env, cls, methods, count); }
static inline jint neko_monitor_enter(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 217, jint, jobject)(env, obj); }
static inline jint neko_monitor_exit(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 218, jint, jobject)(env, obj); }
static inline jboolean neko_exception_check(JNIEnv *env) { return NEKO_JNI_FN_PTR(env, 228, jboolean)(env); }

static char* neko_dotted_class_name(const char *internalName) {
    size_t len = strlen(internalName);
    char *out = (char*)malloc(len + 1u);
    if (out == NULL) return NULL;
    for (size_t i = 0; i < len; i++) out[i] = internalName[i] == '/' ? '.' : internalName[i];
    out[len] = '\\0';
    return out;
}

static jclass neko_load_class_noinit(JNIEnv *env, const char *internalName) {
    char *dotted = neko_dotted_class_name(internalName);
    if (dotted == NULL) return NULL;
    jclass clClass = neko_find_class(env, "java/lang/ClassLoader");
    jmethodID getSystem = neko_get_static_method_id(env, clClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject loader = neko_call_static_object_method_a(env, clClass, getSystem, NULL);
    jclass classClass = neko_find_class(env, "java/lang/Class");
    jmethodID forName = neko_get_static_method_id(env, classClass, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    jvalue args[3];
    args[0].l = neko_new_string_utf(env, dotted);
    args[1].z = JNI_FALSE;
    args[2].l = loader;
    free(dotted);
    return (jclass)neko_call_static_object_method_a(env, classClass, forName, args);
}

static jobject neko_box_boolean(JNIEnv *env, jboolean v) {
    static jclass g_box_boolean_cls = NULL;
    static jmethodID g_box_boolean_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_boolean_cls, env, "java/lang/Boolean");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_boolean_mid, env, cls, "valueOf", "(Z)Ljava/lang/Boolean;");
    jvalue args[1]; args[0].z = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_byte(JNIEnv *env, jbyte v) {
    static jclass g_box_byte_cls = NULL;
    static jmethodID g_box_byte_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_byte_cls, env, "java/lang/Byte");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_byte_mid, env, cls, "valueOf", "(B)Ljava/lang/Byte;");
    jvalue args[1]; args[0].b = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_char(JNIEnv *env, jchar v) {
    static jclass g_box_char_cls = NULL;
    static jmethodID g_box_char_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_char_cls, env, "java/lang/Character");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_char_mid, env, cls, "valueOf", "(C)Ljava/lang/Character;");
    jvalue args[1]; args[0].c = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_short(JNIEnv *env, jshort v) {
    static jclass g_box_short_cls = NULL;
    static jmethodID g_box_short_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_short_cls, env, "java/lang/Short");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_short_mid, env, cls, "valueOf", "(S)Ljava/lang/Short;");
    jvalue args[1]; args[0].s = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_int(JNIEnv *env, jint v) {
    static jclass g_box_int_cls = NULL;
    static jmethodID g_box_int_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_int_cls, env, "java/lang/Integer");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_int_mid, env, cls, "valueOf", "(I)Ljava/lang/Integer;");
    jvalue args[1]; args[0].i = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_long(JNIEnv *env, jlong v) {
    static jclass g_box_long_cls = NULL;
    static jmethodID g_box_long_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_long_cls, env, "java/lang/Long");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_long_mid, env, cls, "valueOf", "(J)Ljava/lang/Long;");
    jvalue args[1]; args[0].j = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_float(JNIEnv *env, jfloat v) {
    static jclass g_box_float_cls = NULL;
    static jmethodID g_box_float_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_float_cls, env, "java/lang/Float");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_float_mid, env, cls, "valueOf", "(F)Ljava/lang/Float;");
    jvalue args[1]; args[0].f = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_double(JNIEnv *env, jdouble v) {
    static jclass g_box_double_cls = NULL;
    static jmethodID g_box_double_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_double_cls, env, "java/lang/Double");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_double_mid, env, cls, "valueOf", "(D)Ljava/lang/Double;");
    jvalue args[1]; args[0].d = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jboolean neko_unbox_boolean(JNIEnv *env, jobject obj) {
    static jclass g_unbox_boolean_cls = NULL;
    static jmethodID g_unbox_boolean_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_boolean_cls, env, "java/lang/Boolean");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_boolean_mid, env, cls, "booleanValue", "()Z");
    return neko_call_boolean_method_a(env, obj, mid, NULL);
}
static jbyte neko_unbox_byte(JNIEnv *env, jobject obj) {
    static jclass g_unbox_byte_cls = NULL;
    static jmethodID g_unbox_byte_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_byte_cls, env, "java/lang/Byte");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_byte_mid, env, cls, "byteValue", "()B");
    return neko_call_byte_method_a(env, obj, mid, NULL);
}
static jchar neko_unbox_char(JNIEnv *env, jobject obj) {
    static jclass g_unbox_char_cls = NULL;
    static jmethodID g_unbox_char_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_char_cls, env, "java/lang/Character");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_char_mid, env, cls, "charValue", "()C");
    return neko_call_char_method_a(env, obj, mid, NULL);
}
static jshort neko_unbox_short(JNIEnv *env, jobject obj) {
    static jclass g_unbox_short_cls = NULL;
    static jmethodID g_unbox_short_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_short_cls, env, "java/lang/Short");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_short_mid, env, cls, "shortValue", "()S");
    return neko_call_short_method_a(env, obj, mid, NULL);
}
static jint neko_unbox_int(JNIEnv *env, jobject obj) {
    static jclass g_unbox_int_cls = NULL;
    static jmethodID g_unbox_int_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_int_cls, env, "java/lang/Integer");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_int_mid, env, cls, "intValue", "()I");
    return neko_call_int_method_a(env, obj, mid, NULL);
}
static jlong neko_unbox_long(JNIEnv *env, jobject obj) {
    static jclass g_unbox_long_cls = NULL;
    static jmethodID g_unbox_long_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_long_cls, env, "java/lang/Long");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_long_mid, env, cls, "longValue", "()J");
    return neko_call_long_method_a(env, obj, mid, NULL);
}
static jfloat neko_unbox_float(JNIEnv *env, jobject obj) {
    static jclass g_unbox_float_cls = NULL;
    static jmethodID g_unbox_float_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_float_cls, env, "java/lang/Float");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_float_mid, env, cls, "floatValue", "()F");
    return neko_call_float_method_a(env, obj, mid, NULL);
}
static jdouble neko_unbox_double(JNIEnv *env, jobject obj) {
    static jclass g_unbox_double_cls = NULL;
    static jmethodID g_unbox_double_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_double_cls, env, "java/lang/Double");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_double_mid, env, cls, "doubleValue", "()D");
    return neko_call_double_method_a(env, obj, mid, NULL);
}

static jclass neko_class_for_descriptor(JNIEnv *env, const char *desc) {
    switch (desc[0]) {
        case 'Z': { jclass c = neko_find_class(env, "java/lang/Boolean"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'B': { jclass c = neko_find_class(env, "java/lang/Byte"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'C': { jclass c = neko_find_class(env, "java/lang/Character"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'S': { jclass c = neko_find_class(env, "java/lang/Short"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'I': { jclass c = neko_find_class(env, "java/lang/Integer"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'J': { jclass c = neko_find_class(env, "java/lang/Long"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'F': { jclass c = neko_find_class(env, "java/lang/Float"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'D': { jclass c = neko_find_class(env, "java/lang/Double"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'L': {
            const char *start = desc + 1;
            const char *semi = strchr(start, ';');
            size_t len = (size_t)(semi - start);
            char *buf = (char*)malloc(len + 1u);
            memcpy(buf, start, len); buf[len] = '\\0';
            jclass out = neko_find_class(env, buf);
            free(buf);
            return out;
        }
        case '[':
            return neko_find_class(env, desc);
        default:
            return NULL;
    }
}

typedef struct {
    jlong id;
    jobject mh;
} neko_indy_entry;

static neko_indy_entry g_indy_table[4096];
static jint g_indy_count = 0;

static jobject neko_get_indy_mh(jlong site_id) {
    for (jint i = 0; i < g_indy_count; i++) {
        if (g_indy_table[i].id == site_id) return g_indy_table[i].mh;
    }
    return NULL;
}

static jobject neko_put_indy_mh(JNIEnv *env, jlong site_id, jobject mh) {
    jobject gref = mh == NULL ? NULL : neko_new_global_ref(env, mh);
    for (jint i = 0; i < g_indy_count; i++) {
        if (g_indy_table[i].id == site_id) {
            g_indy_table[i].mh = gref;
            return gref;
        }
    }
    if (g_indy_count < (jint)(sizeof(g_indy_table) / sizeof(g_indy_table[0]))) {
        g_indy_table[g_indy_count].id = site_id;
        g_indy_table[g_indy_count].mh = gref;
        g_indy_count++;
    }
    return gref;
}

static jobject neko_public_lookup(JNIEnv *env) {
    jclass mhClass = neko_find_class(env, "java/lang/invoke/MethodHandles");
    jmethodID mid = neko_get_static_method_id(env, mhClass, "publicLookup", "()Ljava/lang/invoke/MethodHandles$Lookup;");
    return neko_call_static_object_method_a(env, mhClass, mid, NULL);
}

static jobject neko_impl_lookup(JNIEnv *env) {
    jclass lookupClass = neko_find_class(env, "java/lang/invoke/MethodHandles$Lookup");
    jfieldID fid = neko_get_static_field_id(env, lookupClass, "IMPL_LOOKUP", "Ljava/lang/invoke/MethodHandles$Lookup;");
    return neko_get_static_object_field(env, lookupClass, fid);
}

static jobject neko_lookup_for_class(JNIEnv *env, const char *owner) {
    jclass mhClass = neko_find_class(env, "java/lang/invoke/MethodHandles");
    jmethodID mid = neko_get_static_method_id(env, mhClass, "privateLookupIn", "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;");
    jvalue args[2];
    args[0].l = neko_find_class(env, owner);
    args[1].l = neko_impl_lookup(env);
    return neko_call_static_object_method_a(env, mhClass, mid, args);
}

static jobject neko_method_type_from_descriptor(JNIEnv *env, const char *desc) {
    jclass mtClass = neko_find_class(env, "java/lang/invoke/MethodType");
    jmethodID mid = neko_get_static_method_id(env, mtClass, "fromMethodDescriptorString", "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
    jvalue args[2];
    args[0].l = neko_new_string_utf(env, desc);
    args[1].l = NULL;
    return neko_call_static_object_method_a(env, mtClass, mid, args);
}

static jobjectArray neko_bootstrap_parameter_array(JNIEnv *env, const char *bsm_desc) {
    jobject mt = neko_method_type_from_descriptor(env, bsm_desc);
    jclass mtClass = neko_find_class(env, "java/lang/invoke/MethodType");
    jmethodID mid = neko_get_method_id(env, mtClass, "parameterArray", "()[Ljava/lang/Class;");
    return (jobjectArray)neko_call_object_method_a(env, mt, mid, NULL);
}

static jobject neko_invoke_bootstrap(JNIEnv *env, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray invoke_args) {
    jclass bsmClass = neko_find_class(env, bsm_owner);
    jobjectArray paramTypes = neko_bootstrap_parameter_array(env, bsm_desc);
    jclass classClass = neko_find_class(env, "java/lang/Class");
    jmethodID getDeclaredMethod = neko_get_method_id(env, classClass, "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
    jvalue getArgs[2];
    getArgs[0].l = neko_new_string_utf(env, bsm_name);
    getArgs[1].l = paramTypes;
    jobject method = neko_call_object_method_a(env, bsmClass, getDeclaredMethod, getArgs);

    jclass accessibleClass = neko_find_class(env, "java/lang/reflect/AccessibleObject");
    jmethodID setAccessible = neko_get_method_id(env, accessibleClass, "setAccessible", "(Z)V");
    jvalue accessibleArgs[1];
    accessibleArgs[0].z = JNI_TRUE;
    neko_call_void_method_a(env, method, setAccessible, accessibleArgs);

    jclass methodClass = neko_find_class(env, "java/lang/reflect/Method");
    jmethodID invoke = neko_get_method_id(env, methodClass, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    jvalue invokeArgs[2];
    invokeArgs[0].l = NULL;
    invokeArgs[1].l = invoke_args;
    return neko_call_object_method_a(env, method, invoke, invokeArgs);
}

static jobject neko_method_handle_from_parts(JNIEnv *env, jint tag, const char *owner, const char *name, const char *desc, jboolean isInterface) {
    (void)isInterface;
    jobject lookup = neko_lookup_for_class(env, owner);
    jclass lookupClass = neko_find_class(env, "java/lang/invoke/MethodHandles$Lookup");
    jclass ownerClass = neko_find_class(env, owner);
    jstring nameString = neko_new_string_utf(env, name);

    switch (tag) {
        case 1: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findGetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 2: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findStaticGetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 3: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findSetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 4: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findStaticSetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 5: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 6: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 7: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findSpecial", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[4]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt; args[3].l = ownerClass;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 8: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findConstructor", "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[2]; args[0].l = ownerClass; args[1].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 9: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        default:
            return NULL;
    }
}

static jobject neko_call_mh(JNIEnv *env, jobject mh, jobjectArray args) {
    jclass mhClass = neko_find_class(env, "java/lang/invoke/MethodHandle");
    jmethodID mid = neko_get_method_id(env, mhClass, "invokeWithArguments", "([Ljava/lang/Object;)Ljava/lang/Object;");
    jvalue callArgs[1];
    callArgs[0].l = args;
    return neko_call_object_method_a(env, mh, mid, callArgs);
}

static jstring neko_string_null(JNIEnv *env) {
    static jstring g_str_null = NULL;
    return NEKO_ENSURE_STRING(g_str_null, env, "null");
}

static jstring neko_string_concat2(JNIEnv *env, jobject left, jobject right) {
    static jclass g_str_cls = NULL;
    static jmethodID g_str_value_of = NULL;
    static jmethodID g_str_concat = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_str_cls, env, "java/lang/String");
    jmethodID valueOf = NEKO_ENSURE_STATIC_METHOD_ID(g_str_value_of, env, cls, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
    jmethodID concat = NEKO_ENSURE_METHOD_ID(g_str_concat, env, cls, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
    jvalue valueOfArgs[1];
    valueOfArgs[0].l = left;
    jstring lhs = (jstring)neko_call_static_object_method_a(env, cls, valueOf, valueOfArgs);
    valueOfArgs[0].l = right;
    jstring rhs = (jstring)neko_call_static_object_method_a(env, cls, valueOf, valueOfArgs);
    jvalue concatArgs[1];
    concatArgs[0].l = rhs;
    return (jstring)neko_call_object_method_a(env, lhs, concat, concatArgs);
}

static jstring neko_string_concat_string(JNIEnv *env, jobject left, jstring right) {
    static jclass g_str_cls2 = NULL;
    static jmethodID g_str_value_of2 = NULL;
    static jmethodID g_str_concat2 = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_str_cls2, env, "java/lang/String");
    jmethodID valueOf = NEKO_ENSURE_STATIC_METHOD_ID(g_str_value_of2, env, cls, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
    jmethodID concat = NEKO_ENSURE_METHOD_ID(g_str_concat2, env, cls, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring lhs;
    if (left == NULL) {
        lhs = neko_string_null(env);
    } else {
        lhs = (jstring)left;
    }
    jvalue concatArgs[1];
    concatArgs[0].l = right == NULL ? neko_string_null(env) : right;
    return (jstring)neko_call_object_method_a(env, lhs, concat, concatArgs);
}

static jobject neko_resolve_indy(JNIEnv *env, jlong site_id, const char *caller_owner, const char *indy_name, const char *indy_desc, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray static_args) {
    jobject cached = neko_get_indy_mh(site_id);
    if (cached != NULL) return cached;

    jobjectArray paramTypes = neko_bootstrap_parameter_array(env, bsm_desc);
    jsize paramCount = neko_get_array_length(env, (jarray)paramTypes);
    jclass objClass = neko_find_class(env, "java/lang/Object");
    jobjectArray invokeArgs = neko_new_object_array(env, paramCount, objClass, NULL);
    neko_set_object_array_element(env, invokeArgs, 0, neko_lookup_for_class(env, caller_owner));
    neko_set_object_array_element(env, invokeArgs, 1, neko_new_string_utf(env, indy_name));
    neko_set_object_array_element(env, invokeArgs, 2, neko_method_type_from_descriptor(env, indy_desc));
    for (jsize i = 0; i < neko_get_array_length(env, (jarray)static_args); i++) {
        neko_set_object_array_element(env, invokeArgs, i + 3, neko_get_object_array_element(env, static_args, i));
    }

    jobject bootstrapResult = neko_invoke_bootstrap(env, bsm_owner, bsm_name, bsm_desc, invokeArgs);
    jclass callSiteClass = neko_find_class(env, "java/lang/invoke/CallSite");
    jobject mh = bootstrapResult;
    if (bootstrapResult != NULL && neko_is_instance_of(env, bootstrapResult, callSiteClass)) {
        jmethodID dynamicInvoker = neko_get_method_id(env, callSiteClass, "dynamicInvoker", "()Ljava/lang/invoke/MethodHandle;");
        mh = neko_call_object_method_a(env, bootstrapResult, dynamicInvoker, NULL);
    }
    return neko_put_indy_mh(env, site_id, mh);
}

static jobject neko_resolve_constant_dynamic(JNIEnv *env, const char *caller_owner, const char *name, const char *desc, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray static_args) {
    jobjectArray paramTypes = neko_bootstrap_parameter_array(env, bsm_desc);
    jsize paramCount = neko_get_array_length(env, (jarray)paramTypes);
    jclass objClass = neko_find_class(env, "java/lang/Object");
    jobjectArray invokeArgs = neko_new_object_array(env, paramCount, objClass, NULL);
    neko_set_object_array_element(env, invokeArgs, 0, neko_lookup_for_class(env, caller_owner));
    neko_set_object_array_element(env, invokeArgs, 1, neko_new_string_utf(env, name));
    neko_set_object_array_element(env, invokeArgs, 2, neko_class_for_descriptor(env, desc));
    for (jsize i = 0; i < neko_get_array_length(env, (jarray)static_args); i++) {
        neko_set_object_array_element(env, invokeArgs, i + 3, neko_get_object_array_element(env, static_args, i));
    }
    return neko_invoke_bootstrap(env, bsm_owner, bsm_name, bsm_desc, invokeArgs);
}

static jobject neko_multi_new_array(JNIEnv *env, jint num_dims, jint *dims, const char *desc) {
    if (num_dims <= 0) return NULL;
    if (num_dims == 1) {
        char leaf = desc[1];
        switch (leaf) {
            case 'Z': return (jobject)neko_new_boolean_array(env, dims[0]);
            case 'B': return (jobject)neko_new_byte_array(env, dims[0]);
            case 'C': return (jobject)neko_new_char_array(env, dims[0]);
            case 'S': return (jobject)neko_new_short_array(env, dims[0]);
            case 'I': return (jobject)neko_new_int_array(env, dims[0]);
            case 'J': return (jobject)neko_new_long_array(env, dims[0]);
            case 'F': return (jobject)neko_new_float_array(env, dims[0]);
            case 'D': return (jobject)neko_new_double_array(env, dims[0]);
            case 'L':
            case '[': {
                jclass elemClass = neko_class_for_descriptor(env, desc + 1);
                return (jobject)neko_new_object_array(env, dims[0], elemClass, NULL);
            }
            default:
                return NULL;
        }
    }
    jclass topElemClass = neko_class_for_descriptor(env, desc + 1);
    jobjectArray arr = (jobjectArray)neko_new_object_array(env, dims[0], topElemClass, NULL);
    for (jint i = 0; i < dims[0]; i++) {
        jobject sub = neko_multi_new_array(env, num_dims - 1, dims + 1, desc + 1);
        neko_set_object_array_element(env, arr, i, sub);
    }
    return (jobject)arr;
}

""";
    }

    private String jniType(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> "void";
            case Type.BOOLEAN -> "jboolean";
            case Type.CHAR -> "jchar";
            case Type.BYTE -> "jbyte";
            case Type.SHORT -> "jshort";
            case Type.INT -> "jint";
            case Type.FLOAT -> "jfloat";
            case Type.LONG -> "jlong";
            case Type.DOUBLE -> "jdouble";
            case Type.ARRAY -> "jarray";
            default -> "jobject";
        };
    }

    private String c(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String renderParam(CVariable variable) {
        if ("env".equals(variable.name())) {
            return "JNIEnv *env";
        }
        return variable.declaration();
    }
}
