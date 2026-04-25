package dev.nekoobfuscator.native_.codegen.emit;

import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.FieldRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.IcacheDirectStubRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.IcacheMetaRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.IcacheSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.MethodRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.OwnerResolution;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.StringRef;
import org.objectweb.asm.Type;

import java.util.Map;

public final class Wave3InvokeStaticEmitter {
    private final CCodeGenerator generator;
    private final CEmissionContext ctx;

    public Wave3InvokeStaticEmitter(CCodeGenerator generator, CEmissionContext ctx) {
        this.generator = generator;
        this.ctx = ctx;
    }

    public String renderResolutionCaches() {
        StringBuilder sb = new StringBuilder();
        sb.append("// === Global resolution caches ===\n");
        sb.append("typedef struct neko_icache_site {\n");
        sb.append("    uintptr_t receiver_key;\n");
        sb.append("    void* target;\n");
        sb.append("    uint8_t target_kind;\n");
        sb.append("    uint8_t _pad0;\n");
        sb.append("    uint16_t miss_count;\n");
        sb.append("    uint32_t _pad1;\n");
        sb.append("    jclass cached_class;\n");
        sb.append("} neko_icache_site;\n\n");
        sb.append("#define NEKO_ICACHE_EMPTY 0u\n");
        sb.append("#define NEKO_ICACHE_DIRECT_C 1u\n");
        sb.append("#define NEKO_ICACHE_NONVIRT_MID 2u\n");
        sb.append("#define NEKO_ICACHE_MEGA 3u\n");
        sb.append("#define NEKO_ICACHE_MEGA_THRESHOLD 16u\n\n");
        for (Map.Entry<String, Integer> entry : ctx.classSlotIndex().entrySet()) {
            sb.append("static jclass g_cls_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
        }
        for (Map.Entry<String, Integer> entry : ctx.methodSlotIndex().entrySet()) {
            sb.append("static jmethodID g_mid_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
        }
        for (Map.Entry<String, Integer> entry : ctx.fieldSlotIndex().entrySet()) {
            sb.append("static jfieldID g_fid_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
        }
        for (int i = 0; i < ctx.stringCacheCount(); i++) {
            sb.append("static jstring g_str_").append(i).append(" = NULL;\n");
        }
        for (Map.Entry<String, Integer> entry : ctx.ownerBindIndex().entrySet()) {
            sb.append("static jboolean g_owner_bound_").append(entry.getValue()).append(" = JNI_FALSE;   // ").append(entry.getKey()).append("\n");
        }
        for (IcacheSiteRef site : ctx.icacheSites().values()) {
            sb.append("static neko_icache_site ").append(icacheSiteSymbol(site)).append(" = {0};   // ")
                .append(site.bindingOwner()).append(" :: ").append(site.methodKey()).append(" [site ")
                .append(site.siteIndex()).append("]\n");
        }
        sb.append("\n");
        sb.append("#define NEKO_ENSURE_CLASS(slot, env, name) (slot)\n");
        sb.append("#define NEKO_ENSURE_STRING(slot, env, utf) (slot)\n");
        sb.append("#define NEKO_ENSURE_METHOD_ID(slot, env, cls, name, desc) (slot)\n");
        sb.append("#define NEKO_ENSURE_STATIC_METHOD_ID(slot, env, cls, name, desc) (slot)\n");
        sb.append("#define NEKO_ENSURE_FIELD_ID(slot, env, cls, name, desc) (slot)\n");
        sb.append("#define NEKO_ENSURE_STATIC_FIELD_ID(slot, env, cls, name, desc) (slot)\n\n");
        return sb.toString();
    }

    public String renderBindSupport() {
        return """
static void neko_raise_bound_resolution_error(JNIEnv *env, const char *errorClass, const char *message) {
    (void)errorClass;
    (void)message;
    (void)env;
    (void)neko_raise_cached_pending(neko_get_current_thread(), g_neko_throw_le);
}

static void neko_bind_clear_pending(JNIEnv *env) {
    jthrowable pending;
    void *thread;
    if (env != NULL) {
        pending = (*env)->ExceptionOccurred(env);
        if (pending != NULL) {
            (*env)->ExceptionClear(env);
            (*env)->DeleteLocalRef(env, pending);
        }
    }
    thread = neko_get_current_thread();
    if (thread != NULL && neko_pending_exception(thread) != NULL) {
        neko_clear_pending_exception(thread);
    }
}

static void neko_bind_log_failure(JNIEnv *env, const char *errorClass, const char *message) {
    (void)errorClass;
    (void)message;
    neko_bind_clear_pending(env);
}

static void neko_bind_class_slot(JNIEnv *env, jclass *slot, const char *owner) {
    jclass localClass;
    jobject globalRef;
    if (env == NULL || slot == NULL || *slot != NULL || owner == NULL) return;
    localClass = neko_find_class(env, owner);
    if (localClass == NULL) {
        neko_bind_log_failure(env, "java/lang/NoClassDefFoundError", NULL);
        if (localClass != NULL) neko_delete_local_ref(env, localClass);
        return;
    }
    globalRef = neko_new_global_ref(env, localClass);
    neko_delete_local_ref(env, localClass);
    if (globalRef == NULL) {
        neko_bind_log_failure(env, "java/lang/NoClassDefFoundError", NULL);
        return;
    }
    (void)neko_oop_from_jni_ref(globalRef);
    *slot = (jclass)globalRef;
}

static void neko_bind_method_slot(JNIEnv *env, jmethodID *slot, jclass cls, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    if (env == NULL || slot == NULL || *slot != NULL || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    *slot = isStatic ? neko_get_static_method_id(env, cls, name, desc) : neko_get_method_id(env, cls, name, desc);
    if (*slot == NULL) {
        neko_bind_log_failure(env, "java/lang/NoSuchMethodError", NULL);
        *slot = NULL;
    }
}

static void neko_bind_field_slot(JNIEnv *env, jfieldID *slot, jclass cls, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    if (env == NULL || slot == NULL || *slot != NULL || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    *slot = isStatic ? neko_get_static_field_id(env, cls, name, desc) : neko_get_field_id(env, cls, name, desc);
    if (*slot == NULL) {
        neko_bind_log_failure(env, "java/lang/NoSuchFieldError", NULL);
        *slot = NULL;
    }
}

static void neko_bind_string_slot(JNIEnv *env, jstring *slot, const char *utf) {
    jstring localString;
    jstring internedString;
    jclass stringClass;
    jmethodID internMethod;
    jobject globalRef;
    if (env == NULL || slot == NULL || *slot != NULL || utf == NULL) return;
    localString = neko_new_string_utf(env, utf);
    if (localString == NULL) {
        neko_bind_log_failure(env, "java/lang/IllegalStateException", NULL);
        if (localString != NULL) neko_delete_local_ref(env, localString);
        return;
    }
    internedString = localString;
    stringClass = neko_find_class(env, "java/lang/String");
    if (stringClass == NULL) {
        neko_bind_clear_pending(env);
        internMethod = NULL;
    } else {
        internMethod = neko_get_method_id(env, stringClass, "intern", "()Ljava/lang/String;");
        if (internMethod == NULL) neko_bind_clear_pending(env);
    }
    if (internMethod != NULL) {
        jstring candidate = (jstring)neko_call_object_method_a(env, localString, internMethod, NULL);
        if (candidate != NULL) internedString = candidate;
        else neko_bind_clear_pending(env);
    }
    globalRef = neko_new_global_ref(env, internedString);
    if (internedString != localString) neko_delete_local_ref(env, internedString);
    if (stringClass != NULL) neko_delete_local_ref(env, stringClass);
    neko_delete_local_ref(env, localString);
    if (globalRef == NULL) {
        neko_bind_log_failure(env, "java/lang/IllegalStateException", NULL);
        return;
    }
    *slot = (jstring)globalRef;
}

static void neko_bind_runtime_helper_slots(JNIEnv *env) {
    if (env == NULL) return;
    neko_bind_class_slot(env, &g_neko_rt_cls_stack_trace_element, "java/lang/StackTraceElement");
    neko_bind_class_slot(env, &g_neko_rt_cls_boolean, "java/lang/Boolean");
    neko_bind_class_slot(env, &g_neko_rt_cls_byte, "java/lang/Byte");
    neko_bind_class_slot(env, &g_neko_rt_cls_character, "java/lang/Character");
    neko_bind_class_slot(env, &g_neko_rt_cls_short, "java/lang/Short");
    neko_bind_class_slot(env, &g_neko_rt_cls_integer, "java/lang/Integer");
    neko_bind_class_slot(env, &g_neko_rt_cls_long, "java/lang/Long");
    neko_bind_class_slot(env, &g_neko_rt_cls_float, "java/lang/Float");
    neko_bind_class_slot(env, &g_neko_rt_cls_double, "java/lang/Double");
    neko_bind_class_slot(env, &g_neko_rt_cls_string, "java/lang/String");
    neko_bind_class_slot(env, &g_neko_rt_cls_method_handle, "java/lang/invoke/MethodHandle");
    neko_bind_method_slot(env, &g_neko_rt_mid_stack_trace_element_init, g_neko_rt_cls_stack_trace_element, "java/lang/StackTraceElement", "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V", JNI_FALSE);
    neko_bind_method_slot(env, &g_neko_rt_mid_boolean_value_of, g_neko_rt_cls_boolean, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", JNI_TRUE);
    neko_bind_method_slot(env, &g_neko_rt_mid_byte_value_of, g_neko_rt_cls_byte, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", JNI_TRUE);
    neko_bind_method_slot(env, &g_neko_rt_mid_character_value_of, g_neko_rt_cls_character, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", JNI_TRUE);
    neko_bind_method_slot(env, &g_neko_rt_mid_short_value_of, g_neko_rt_cls_short, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", JNI_TRUE);
    neko_bind_method_slot(env, &g_neko_rt_mid_integer_value_of, g_neko_rt_cls_integer, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", JNI_TRUE);
    neko_bind_method_slot(env, &g_neko_rt_mid_long_value_of, g_neko_rt_cls_long, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", JNI_TRUE);
    neko_bind_method_slot(env, &g_neko_rt_mid_float_value_of, g_neko_rt_cls_float, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", JNI_TRUE);
    neko_bind_method_slot(env, &g_neko_rt_mid_double_value_of, g_neko_rt_cls_double, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", JNI_TRUE);
    neko_bind_method_slot(env, &g_neko_rt_mid_boolean_unbox, g_neko_rt_cls_boolean, "java/lang/Boolean", "booleanValue", "()Z", JNI_FALSE);
    neko_bind_method_slot(env, &g_neko_rt_mid_byte_unbox, g_neko_rt_cls_byte, "java/lang/Byte", "byteValue", "()B", JNI_FALSE);
    neko_bind_method_slot(env, &g_neko_rt_mid_character_unbox, g_neko_rt_cls_character, "java/lang/Character", "charValue", "()C", JNI_FALSE);
    neko_bind_method_slot(env, &g_neko_rt_mid_short_unbox, g_neko_rt_cls_short, "java/lang/Short", "shortValue", "()S", JNI_FALSE);
    neko_bind_method_slot(env, &g_neko_rt_mid_integer_unbox, g_neko_rt_cls_integer, "java/lang/Integer", "intValue", "()I", JNI_FALSE);
    neko_bind_method_slot(env, &g_neko_rt_mid_long_unbox, g_neko_rt_cls_long, "java/lang/Long", "longValue", "()J", JNI_FALSE);
    neko_bind_method_slot(env, &g_neko_rt_mid_float_unbox, g_neko_rt_cls_float, "java/lang/Float", "floatValue", "()F", JNI_FALSE);
    neko_bind_method_slot(env, &g_neko_rt_mid_double_unbox, g_neko_rt_cls_double, "java/lang/Double", "doubleValue", "()D", JNI_FALSE);
    neko_bind_method_slot(env, &g_neko_rt_mid_string_value_of_object, g_neko_rt_cls_string, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", JNI_TRUE);
    neko_bind_method_slot(env, &g_neko_rt_mid_string_concat, g_neko_rt_cls_string, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", JNI_FALSE);
    neko_bind_method_slot(env, &g_neko_rt_mid_method_handle_invoke_with_arguments, g_neko_rt_cls_method_handle, "java/lang/invoke/MethodHandle", "invokeWithArguments", "([Ljava/lang/Object;)Ljava/lang/Object;", JNI_FALSE);
    neko_bind_string_slot(env, &g_neko_rt_str_null, "null");
}

static jclass neko_bound_class(JNIEnv *env, jclass slot, const char *owner) {
    if (slot != NULL) return slot;
    neko_raise_bound_resolution_error(env, "java/lang/NoClassDefFoundError", owner);
    return NULL;
}

static jmethodID neko_bound_method(JNIEnv *env, jmethodID slot, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    (void)isStatic;
    (void)name;
    (void)desc;
    if (slot != NULL) return slot;
    neko_raise_bound_resolution_error(env, "java/lang/NoSuchMethodError", owner);
    return NULL;
}

static jfieldID neko_bound_field(JNIEnv *env, jfieldID slot, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    (void)isStatic;
    (void)name;
    (void)desc;
    if (slot != NULL) return slot;
    neko_raise_bound_resolution_error(env, "java/lang/NoSuchFieldError", owner);
    return NULL;
}

static jstring neko_bound_string(JNIEnv *env, jstring slot, const char *utf) {
    if (slot != NULL) return slot;
    neko_raise_bound_resolution_error(env, "java/lang/IllegalStateException", utf);
    return NULL;
}
""";
    }

    public String renderWave3Support() {
        return """
// === Wave 3 invoke/static-field support ===
static inline void* neko_method_compiled_entry(void *method_star) {
    if (method_star == NULL || g_neko_vm_layout.off_method_from_compiled_entry < 0) return NULL;
    return __atomic_load_n((void**)((uint8_t*)method_star + g_neko_vm_layout.off_method_from_compiled_entry), __ATOMIC_ACQUIRE);
}

__attribute__((visibility("default"))) void* neko_resolve_invoke_site(NekoManifestInvokeSite *site) {
    void *resolved;
    if (site == NULL) return NULL;
    resolved = __atomic_load_n(&site->resolved_method, __ATOMIC_ACQUIRE);
    if (resolved != NULL) {
        if (!neko_method_is_redefined_stale(resolved)) return resolved;
        __atomic_store_n(&site->resolved_method, NULL, __ATOMIC_RELEASE);
        resolved = NULL;
    }
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        const NekoManifestMethod *method = &g_neko_manifest_methods[i];
        void *method_star;
        if (!neko_manifest_method_active(i)) continue;
        if (method->owner_internal == NULL || site->owner_internal == NULL) continue;
        if (strcmp(method->owner_internal, site->owner_internal) != 0) continue;
        if (strcmp(method->method_name, site->method_name) != 0) continue;
        if (strcmp(method->method_desc, site->method_desc) != 0) continue;
        method_star = __atomic_load_n(&g_neko_manifest_method_stars[i], __ATOMIC_ACQUIRE);
        if (method_star == NULL) continue;
        __atomic_store_n(&site->resolved_method, method_star, __ATOMIC_RELEASE);
        return method_star;
    }
    return NULL;
}

static void neko_log_wave3_ready(void) {
    neko_native_debug_log("w3 is=%u sb=%u", g_neko_manifest_invoke_site_count, neko_count_cached_static_field_bases());
}

""";
    }

    public String renderBindOwnerFunctions() {
        StringBuilder sb = new StringBuilder();
        if (ctx.ownerBindIndex().isEmpty()) {
            return "";
        }
        sb.append("// === Bind-time owner resolution ===\n");
        for (Map.Entry<String, Integer> entry : ctx.ownerBindIndex().entrySet()) {
            String owner = entry.getKey();
            int ownerId = entry.getValue();
            OwnerResolution resolution = ctx.ownerResolutions().get(owner);
            sb.append("static void neko_bind_owner_").append(ownerId).append("(JNIEnv *env, jclass self_class) {\n");
            sb.append("    if (env == NULL || g_owner_bound_").append(ownerId).append(") return;\n");
            sb.append("    (void)self_class;\n");
            sb.append("    g_owner_bound_").append(ownerId).append(" = JNI_TRUE;\n");
            for (String classOwner : resolution.classes()) {
                if (owner.equals(classOwner)) {
                    sb.append("    if (self_class != NULL && ").append(generator.classSlotName(classOwner)).append(" == NULL) {\n");
                    sb.append("        ").append(generator.classSlotName(classOwner)).append(" = (jclass)neko_new_global_ref(env, self_class);\n");
                    sb.append("    }\n");
                    sb.append("    neko_bind_class_slot(env, &").append(generator.classSlotName(classOwner)).append(", \"")
                        .append(c(classOwner)).append("\");\n");
                } else {
                    sb.append("    neko_bind_class_slot(env, &").append(generator.classSlotName(classOwner)).append(", \"")
                        .append(c(classOwner)).append("\");\n");
                }
            }
            for (String classOwner : resolution.classes()) {
                sb.append("    if (").append(generator.classSlotName(classOwner)).append(" != NULL) {\n");
                sb.append("        neko_bootstrap_class_discovery(env, ").append(generator.classSlotName(classOwner)).append(");\n");
                sb.append("    }\n");
            }
            for (MethodRef methodRef : resolution.methods()) {
                sb.append("    neko_bind_method_slot(env, &").append(generator.methodSlotName(methodRef.owner(), methodRef.name(), methodRef.desc(), methodRef.isStatic()))
                    .append(", ").append(generator.classSlotName(methodRef.owner())).append(", \"")
                    .append(c(methodRef.owner())).append("\", \"")
                    .append(c(methodRef.name())).append("\", \"")
                    .append(c(methodRef.desc())).append("\", ")
                    .append(methodRef.isStatic() ? "JNI_TRUE" : "JNI_FALSE").append(");\n");
            }
            for (FieldRef fieldRef : resolution.fields()) {
                sb.append("    neko_bind_field_slot(env, &").append(generator.fieldSlotName(fieldRef.owner(), fieldRef.name(), fieldRef.desc(), fieldRef.isStatic()))
                    .append(", ").append(generator.classSlotName(fieldRef.owner())).append(", \"")
                    .append(c(fieldRef.owner())).append("\", \"")
                    .append(c(fieldRef.name())).append("\", \"")
                    .append(c(fieldRef.desc())).append("\", ")
                    .append(fieldRef.isStatic() ? "JNI_TRUE" : "JNI_FALSE").append(");\n");
            }
            for (StringRef stringRef : resolution.strings()) {
                sb.append("    neko_bind_string_slot(env, &").append(stringRef.cacheVar()).append(", \"")
                    .append(c(stringRef.value())).append("\");\n");
            }
            sb.append("}\n\n");
        }
        sb.append("static void neko_bind_owner_by_internal_name(JNIEnv *env, const char *owner_internal, jclass self_class) {\n");
        sb.append("    if (env == NULL || owner_internal == NULL) return;\n");
        for (Map.Entry<String, Integer> entry : ctx.ownerBindIndex().entrySet()) {
            String owner = entry.getKey();
            int ownerId = entry.getValue();
            sb.append("    if (strcmp(owner_internal, \"").append(c(owner)).append("\") == 0) { neko_bind_owner_")
                .append(ownerId).append("(env, self_class); return; }\n");
        }
        sb.append("}\n\n");
        sb.append("static void neko_bind_all_owners(JNIEnv *env) {\n");
        sb.append("    if (env == NULL) return;\n");
        for (Map.Entry<String, Integer> entry : ctx.ownerBindIndex().entrySet()) {
            int ownerId = entry.getValue();
            sb.append("    neko_bind_owner_").append(ownerId).append("(env, NULL);\n");
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    public String renderIcacheDirectStubs() {
        if (ctx.icacheDirectStubs().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// === Inline-cache direct-call stubs ===\n");
        for (IcacheDirectStubRef stub : ctx.icacheDirectStubs().values()) {
            sb.append(renderIcacheDirectStub(stub));
        }
        sb.append('\n');
        return sb.toString();
    }

    public String renderIcacheMetas() {
        if (ctx.icacheMetas().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// === Inline-cache metadata ===\n");
        for (IcacheMetaRef meta : ctx.icacheMetas().values()) {
            sb.append("static const neko_icache_meta ").append(icacheMetaSymbol(meta)).append(" = {\"")
                .append(c(meta.name())).append("\", \"").append(c(meta.desc())).append("\", ")
                .append(meta.translatedClassSlot() == null ? "NULL" : "&" + meta.translatedClassSlot()).append(", ")
                .append(meta.translatedStubSymbol() == null ? "NULL" : meta.translatedStubSymbol()).append(", ")
                .append(meta.isInterface() ? "JNI_TRUE" : "JNI_FALSE").append("};\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderIcacheDirectStub(IcacheDirectStubRef stub) {
        StringBuilder sb = new StringBuilder();
        sb.append("static jvalue ").append(icacheDirectStubSymbol(stub)).append("(JNIEnv *env, jobject receiver, const jvalue *args) {\n");
        sb.append("    jvalue result = {0};\n");
        sb.append("    void *thread = neko_get_current_thread();\n");
        sb.append("    (void)thread;\n");
        sb.append("    (void)neko_manifest_method_active(").append(stub.binding().manifestIndex()).append("u);\n");
        if (stub.returnType().getSort() != Type.VOID) {
            sb.append("    result").append(jvalueAccessor(stub.returnType())).append(" = ");
        } else {
            sb.append("    ");
        }
        sb.append(stub.binding().cFunctionName()).append('(');
        boolean first = true;
        if (!stub.binding().isStatic()) {
            sb.append("neko_oop_for_direct(receiver)");
            first = false;
        }
        for (int i = 0; i < stub.args().length; i++) {
            if (!first) {
                sb.append(", ");
            }
            if (stub.args()[i].getSort() == Type.ARRAY) {
                sb.append("neko_oop_for_direct((jobject)");
                sb.append("args[").append(i).append("]").append(jvalueAccessor(stub.args()[i])).append(')');
                first = false;
                continue;
            } else if (stub.args()[i].getSort() == Type.OBJECT) {
                sb.append("neko_oop_for_direct((jobject)");
                sb.append("args[").append(i).append("]").append(jvalueAccessor(stub.args()[i])).append(')');
                first = false;
                continue;
            }
            sb.append("args[").append(i).append("]").append(jvalueAccessor(stub.args()[i]));
            first = false;
        }
        sb.append(");\n");
        sb.append("    return result;\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String icacheSiteSymbol(IcacheSiteRef site) {
        return "neko_icache_" + site.ownerId() + '_' + site.methodId() + '_' + site.siteIndex();
    }

    private String icacheDirectStubSymbol(IcacheDirectStubRef stub) {
        return "neko_icache_stub_" + stub.ownerId() + '_' + stub.methodId() + '_' + stub.siteIndex();
    }

    private String icacheMetaSymbol(IcacheMetaRef meta) {
        return "neko_icache_meta_" + meta.ownerId() + '_' + meta.methodId() + '_' + meta.siteIndex();
    }

    private String jvalueAccessor(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> ".z";
            case Type.BYTE -> ".b";
            case Type.CHAR -> ".c";
            case Type.SHORT -> ".s";
            case Type.INT -> ".i";
            case Type.FLOAT -> ".f";
            case Type.LONG -> ".j";
            case Type.DOUBLE -> ".d";
            default -> ".l";
        };
    }

    private String c(String s) {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\f", "\\f");
    }
}
