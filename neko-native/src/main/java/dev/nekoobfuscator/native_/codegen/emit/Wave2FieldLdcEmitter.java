package dev.nekoobfuscator.native_.codegen.emit;

public final class Wave2FieldLdcEmitter {
    public String renderWave2Support() {
        StringBuilder sb = new StringBuilder();
        sb.append("// === Wave 2 field/LDC support ===\n");
        sb.append("static jclass g_neko_wave2_class_cls = NULL;\n");
        sb.append("static jclass g_neko_wave2_field_cls = NULL;\n");
        sb.append("static jclass g_neko_wave2_modifier_cls = NULL;\n");
        sb.append("static jclass g_neko_wave2_unsafe_cls = NULL;\n");
        sb.append("static jobject g_neko_wave2_unsafe_singleton = NULL;\n");
        sb.append("static jmethodID g_neko_wave2_class_get_declared_field = NULL;\n");
        sb.append("static jmethodID g_neko_wave2_field_get_modifiers = NULL;\n");
        sb.append("static jmethodID g_neko_wave2_modifier_is_volatile = NULL;\n");
        sb.append("static jmethodID g_neko_wave2_unsafe_object_field_offset = NULL;\n");
        sb.append("static jmethodID g_neko_wave2_unsafe_static_field_offset = NULL;\n");
        sb.append("static jmethodID g_neko_wave2_unsafe_static_field_base = NULL;\n");
        sb.append("static jmethodID g_neko_wave2_unsafe_array_base_offset = NULL;\n");
        sb.append("static jmethodID g_neko_wave2_unsafe_array_index_scale = NULL;\n\n");
        sb.append("static char* neko_wave2_copy_bytes(const uint8_t *bytes, size_t len) {\n");
        sb.append("    char *copy = (char*)malloc(len + 1u);\n");
        sb.append("    if (copy == NULL) return NULL;\n");
        sb.append("    if (len != 0u && bytes != NULL) memcpy(copy, bytes, len);\n");
        sb.append("    copy[len] = '\\0';\n");
        sb.append("    return copy;\n");
        sb.append("}\n\n");
        sb.append("static void neko_wave2_capture_pending(JNIEnv *env, void *thread, const char *fallback_class, const char *fallback_message) {\n");
        sb.append("    void *oop = neko_take_pending_jni_exception_oop(env);\n");
        sb.append("    if (oop == NULL && fallback_class != NULL) oop = neko_new_exception_oop(env, fallback_class, fallback_message);\n");
        sb.append("    if (oop != NULL) neko_set_pending_exception(thread, oop);\n");
        sb.append("}\n\n");
        sb.append("static void neko_raise_null_pointer_exception(void *thread) {\n");
        sb.append("    JNIEnv *env = neko_current_env();\n");
        sb.append("    void *oop = neko_new_exception_oop(env, \"java/lang/NullPointerException\", NULL);\n");
        sb.append("    if (oop == NULL) oop = neko_take_pending_jni_exception_oop(env);\n");
        sb.append("    if (oop != NULL) neko_set_pending_exception(thread, oop);\n");
        sb.append("}\n\n");
        sb.append("static jobject neko_wave2_unsafe(JNIEnv *env) {\n");
        sb.append("    const char *unsafe_name = g_neko_vm_layout.java_spec_version >= 9 ? \"jdk/internal/misc/Unsafe\" : \"sun/misc/Unsafe\";\n");
        sb.append("    if (env == NULL) return NULL;\n");
        sb.append("    if (g_neko_wave2_unsafe_singleton != NULL) return g_neko_wave2_unsafe_singleton;\n");
        sb.append("    if (g_neko_wave2_unsafe_cls == NULL) {\n");
        sb.append("        jclass local = neko_find_class(env, unsafe_name);\n");
        sb.append("        if (local == NULL || neko_exception_check(env)) { if (neko_exception_check(env)) neko_exception_clear(env); return NULL; }\n");
        sb.append("        g_neko_wave2_unsafe_cls = (jclass)neko_new_global_ref(env, local);\n");
        sb.append("        neko_delete_local_ref(env, local);\n");
        sb.append("    }\n");
        sb.append("    if (g_neko_wave2_unsafe_cls == NULL) return NULL;\n");
        sb.append("    {\n");
        sb.append("        jfieldID fid = neko_get_static_field_id(env, g_neko_wave2_unsafe_cls, \"theUnsafe\", g_neko_vm_layout.java_spec_version >= 9 ? \"Ljdk/internal/misc/Unsafe;\" : \"Lsun/misc/Unsafe;\");\n");
        sb.append("        jobject local;\n");
        sb.append("        if (fid == NULL || neko_exception_check(env)) { if (neko_exception_check(env)) neko_exception_clear(env); return NULL; }\n");
        sb.append("        local = neko_get_static_object_field(env, g_neko_wave2_unsafe_cls, fid);\n");
        sb.append("        if (local == NULL || neko_exception_check(env)) { if (neko_exception_check(env)) neko_exception_clear(env); return NULL; }\n");
        sb.append("        g_neko_wave2_unsafe_singleton = neko_new_global_ref(env, local);\n");
        sb.append("        neko_delete_local_ref(env, local);\n");
        sb.append("    }\n");
        sb.append("    return g_neko_wave2_unsafe_singleton;\n");
        sb.append("}\n\n");
        sb.append("static jobject neko_wave2_declared_field(JNIEnv *env, jclass owner, const char *field_name) {\n");
        sb.append("    jvalue args[1];\n");
        sb.append("    if (env == NULL || owner == NULL || field_name == NULL) return NULL;\n");
        sb.append("    g_neko_wave2_class_cls = NEKO_ENSURE_CLASS(g_neko_wave2_class_cls, env, \"java/lang/Class\");\n");
        sb.append("    g_neko_wave2_class_get_declared_field = NEKO_ENSURE_METHOD_ID(g_neko_wave2_class_get_declared_field, env, g_neko_wave2_class_cls, \"getDeclaredField\", \"(Ljava/lang/String;)Ljava/lang/reflect/Field;\");\n");
        sb.append("    if (g_neko_wave2_class_get_declared_field == NULL) return NULL;\n");
        sb.append("    args[0].l = neko_new_string_utf(env, field_name);\n");
        sb.append("    return neko_call_object_method_a(env, owner, g_neko_wave2_class_get_declared_field, args);\n");
        sb.append("}\n\n");
        sb.append("static ptrdiff_t neko_wave2_reflected_field_offset(JNIEnv *env, jobject reflected_field, jboolean is_static) {\n");
        sb.append("    jobject unsafe = neko_wave2_unsafe(env);\n");
        sb.append("    jvalue args[1];\n");
        sb.append("    if (unsafe == NULL || reflected_field == NULL) return -1;\n");
        sb.append("    g_neko_wave2_unsafe_object_field_offset = NEKO_ENSURE_METHOD_ID(g_neko_wave2_unsafe_object_field_offset, env, g_neko_wave2_unsafe_cls, \"objectFieldOffset\", \"(Ljava/lang/reflect/Field;)J\");\n");
        sb.append("    g_neko_wave2_unsafe_static_field_offset = NEKO_ENSURE_METHOD_ID(g_neko_wave2_unsafe_static_field_offset, env, g_neko_wave2_unsafe_cls, \"staticFieldOffset\", \"(Ljava/lang/reflect/Field;)J\");\n");
        sb.append("    args[0].l = reflected_field;\n");
        sb.append("    return (ptrdiff_t)neko_call_long_method_a(env, unsafe, is_static ? g_neko_wave2_unsafe_static_field_offset : g_neko_wave2_unsafe_object_field_offset, args);\n");
        sb.append("}\n\n");
        sb.append("static jobject neko_wave2_reflected_static_field_base(JNIEnv *env, jobject reflected_field) {\n");
        sb.append("    jobject unsafe = neko_wave2_unsafe(env);\n");
        sb.append("    jvalue args[1];\n");
        sb.append("    if (unsafe == NULL || reflected_field == NULL) return NULL;\n");
        sb.append("    g_neko_wave2_unsafe_static_field_base = NEKO_ENSURE_METHOD_ID(g_neko_wave2_unsafe_static_field_base, env, g_neko_wave2_unsafe_cls, \"staticFieldBase\", \"(Ljava/lang/reflect/Field;)Ljava/lang/Object;\");\n");
        sb.append("    if (g_neko_wave2_unsafe_static_field_base == NULL) return NULL;\n");
        sb.append("    args[0].l = reflected_field;\n");
        sb.append("    return neko_call_object_method_a(env, unsafe, g_neko_wave2_unsafe_static_field_base, args);\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_wave2_field_is_volatile(JNIEnv *env, jobject reflected_field) {\n");
        sb.append("    jvalue args[1];\n");
        sb.append("    if (env == NULL || reflected_field == NULL) return JNI_FALSE;\n");
        sb.append("    g_neko_wave2_field_cls = NEKO_ENSURE_CLASS(g_neko_wave2_field_cls, env, \"java/lang/reflect/Field\");\n");
        sb.append("    g_neko_wave2_modifier_cls = NEKO_ENSURE_CLASS(g_neko_wave2_modifier_cls, env, \"java/lang/reflect/Modifier\");\n");
        sb.append("    g_neko_wave2_field_get_modifiers = NEKO_ENSURE_METHOD_ID(g_neko_wave2_field_get_modifiers, env, g_neko_wave2_field_cls, \"getModifiers\", \"()I\");\n");
        sb.append("    g_neko_wave2_modifier_is_volatile = NEKO_ENSURE_STATIC_METHOD_ID(g_neko_wave2_modifier_is_volatile, env, g_neko_wave2_modifier_cls, \"isVolatile\", \"(I)Z\");\n");
        sb.append("    if (g_neko_wave2_field_get_modifiers == NULL || g_neko_wave2_modifier_is_volatile == NULL) return JNI_FALSE;\n");
        sb.append("    args[0].i = neko_call_int_method_a(env, reflected_field, g_neko_wave2_field_get_modifiers, NULL);\n");
        sb.append("    return neko_call_static_boolean_method_a(env, g_neko_wave2_modifier_cls, g_neko_wave2_modifier_is_volatile, args);\n");
        sb.append("}\n\n");
        sb.append("static ptrdiff_t neko_wave2_object_field_offset_by_name(JNIEnv *env, const char *owner_internal, const char *field_name) {\n");
        sb.append("    jclass owner = NULL;\n");
        sb.append("    jobject reflected = NULL;\n");
        sb.append("    ptrdiff_t offset = -1;\n");
        sb.append("    if (env == NULL || owner_internal == NULL || field_name == NULL) return -1;\n");
        sb.append("    owner = neko_find_class(env, owner_internal);\n");
        sb.append("    if (owner == NULL || neko_exception_check(env)) goto cleanup;\n");
        sb.append("    reflected = neko_wave2_declared_field(env, owner, field_name);\n");
        sb.append("    if (reflected == NULL || neko_exception_check(env)) goto cleanup;\n");
        sb.append("    offset = neko_wave2_reflected_field_offset(env, reflected, JNI_FALSE);\n");
        sb.append("cleanup:\n");
        sb.append("    if (neko_exception_check(env)) neko_exception_clear(env);\n");
        sb.append("    if (reflected != NULL) neko_delete_local_ref(env, reflected);\n");
        sb.append("    if (owner != NULL) neko_delete_local_ref(env, owner);\n");
        sb.append("    return offset;\n");
        sb.append("}\n\n");
        sb.append("static ptrdiff_t neko_wave2_array_metric(JNIEnv *env, const char *descriptor, jboolean want_base) {\n");
        sb.append("    jobject unsafe = neko_wave2_unsafe(env);\n");
        sb.append("    jclass array_class = NULL;\n");
        sb.append("    jvalue args[1];\n");
        sb.append("    ptrdiff_t value = -1;\n");
        sb.append("    if (env == NULL || descriptor == NULL || unsafe == NULL) return -1;\n");
        sb.append("    g_neko_wave2_unsafe_array_base_offset = NEKO_ENSURE_METHOD_ID(g_neko_wave2_unsafe_array_base_offset, env, g_neko_wave2_unsafe_cls, \"arrayBaseOffset\", \"(Ljava/lang/Class;)I\");\n");
        sb.append("    g_neko_wave2_unsafe_array_index_scale = NEKO_ENSURE_METHOD_ID(g_neko_wave2_unsafe_array_index_scale, env, g_neko_wave2_unsafe_cls, \"arrayIndexScale\", \"(Ljava/lang/Class;)I\");\n");
        sb.append("    array_class = neko_class_for_descriptor(env, descriptor);\n");
        sb.append("    if (array_class == NULL || neko_exception_check(env)) goto cleanup;\n");
        sb.append("    args[0].l = array_class;\n");
        sb.append("    value = want_base ? (ptrdiff_t)neko_call_int_method_a(env, unsafe, g_neko_wave2_unsafe_array_base_offset, args) : (ptrdiff_t)neko_call_int_method_a(env, unsafe, g_neko_wave2_unsafe_array_index_scale, args);\n");
        sb.append("cleanup:\n");
        sb.append("    if (neko_exception_check(env)) neko_exception_clear(env);\n");
        sb.append("    if (array_class != NULL) neko_delete_local_ref(env, array_class);\n");
        sb.append("    return value;\n");
        sb.append("}\n\n");
        sb.append("static void neko_derive_wave2_layout_offsets(JNIEnv *env) {\n");
        sb.append("    g_neko_vm_layout.instance_klass_fields_strategy = g_neko_vm_layout.off_instance_klass_fields >= 0 ? 'A' : 'C';\n");
        sb.append("    if (g_neko_vm_layout.off_string_value >= 0) { g_neko_vm_layout.string_value_strategy = 'A'; } else { g_neko_vm_layout.off_string_value = neko_wave2_object_field_offset_by_name(env, \"java/lang/String\", \"value\"); g_neko_vm_layout.string_value_strategy = g_neko_vm_layout.off_string_value >= 0 ? 'B' : 'C'; }\n");
        sb.append("    if (g_neko_vm_layout.java_spec_version < 9) { g_neko_vm_layout.off_string_coder = -1; g_neko_vm_layout.string_coder_strategy = 'N'; } else if (g_neko_vm_layout.off_string_coder >= 0) { g_neko_vm_layout.string_coder_strategy = 'A'; } else { g_neko_vm_layout.off_string_coder = neko_wave2_object_field_offset_by_name(env, \"java/lang/String\", \"coder\"); g_neko_vm_layout.string_coder_strategy = g_neko_vm_layout.off_string_coder >= 0 ? 'B' : 'C'; }\n");
        sb.append("    g_neko_vm_layout.class_klass_strategy = g_neko_vm_layout.off_class_klass >= 0 ? 'A' : 'B';\n");
        sb.append("    if (g_neko_vm_layout.off_array_base_byte >= 0) { g_neko_vm_layout.array_base_byte_strategy = 'A'; } else { g_neko_vm_layout.off_array_base_byte = neko_wave2_array_metric(env, \"[B\", JNI_TRUE); g_neko_vm_layout.array_base_byte_strategy = g_neko_vm_layout.off_array_base_byte >= 0 ? 'B' : 'C'; }\n");
        sb.append("    if (g_neko_vm_layout.off_array_scale_byte >= 0) { g_neko_vm_layout.array_scale_byte_strategy = 'A'; } else { g_neko_vm_layout.off_array_scale_byte = neko_wave2_array_metric(env, \"[B\", JNI_FALSE); g_neko_vm_layout.array_scale_byte_strategy = g_neko_vm_layout.off_array_scale_byte >= 0 ? 'B' : 'C'; }\n");
        sb.append("    if (g_neko_vm_layout.off_array_base_char >= 0) { g_neko_vm_layout.array_base_char_strategy = 'A'; } else { g_neko_vm_layout.off_array_base_char = neko_wave2_array_metric(env, \"[C\", JNI_TRUE); g_neko_vm_layout.array_base_char_strategy = g_neko_vm_layout.off_array_base_char >= 0 ? 'B' : 'C'; }\n");
        sb.append("    if (g_neko_vm_layout.off_array_scale_char >= 0) { g_neko_vm_layout.array_scale_char_strategy = 'A'; } else { g_neko_vm_layout.off_array_scale_char = neko_wave2_array_metric(env, \"[C\", JNI_FALSE); g_neko_vm_layout.array_scale_char_strategy = g_neko_vm_layout.off_array_scale_char >= 0 ? 'B' : 'C'; }\n");
        sb.append("    neko_log_offset_strategy(\"instance_klass_fields_offset\", g_neko_vm_layout.off_instance_klass_fields, g_neko_vm_layout.instance_klass_fields_strategy);\n");
        sb.append("    neko_log_offset_strategy(\"string_value_offset\", g_neko_vm_layout.off_string_value, g_neko_vm_layout.string_value_strategy);\n");
        sb.append("    neko_log_offset_strategy(\"string_coder_offset\", g_neko_vm_layout.off_string_coder, g_neko_vm_layout.string_coder_strategy);\n");
        sb.append("    neko_log_offset_strategy(\"class_klass_offset\", g_neko_vm_layout.off_class_klass, g_neko_vm_layout.class_klass_strategy);\n");
        sb.append("    neko_log_offset_strategy(\"array_base_byte\", g_neko_vm_layout.off_array_base_byte, g_neko_vm_layout.array_base_byte_strategy);\n");
        sb.append("    neko_log_offset_strategy(\"array_scale_byte\", g_neko_vm_layout.off_array_scale_byte, g_neko_vm_layout.array_scale_byte_strategy);\n");
        sb.append("    neko_log_offset_strategy(\"array_base_char\", g_neko_vm_layout.off_array_base_char, g_neko_vm_layout.array_base_char_strategy);\n");
        sb.append("    neko_log_offset_strategy(\"array_scale_char\", g_neko_vm_layout.off_array_scale_char, g_neko_vm_layout.array_scale_char_strategy);\n");
        sb.append("}\n\n");
        sb.append("static inline jboolean neko_uses_compressed_oops(void) {\n");
        sb.append("    return g_neko_vm_layout.narrow_oop_shift > 0 || g_neko_vm_layout.narrow_oop_base != 0u;\n");
        sb.append("}\n\n");
        sb.append("static inline jboolean neko_uses_compressed_klass_pointers(void) {\n");
        sb.append("    return g_neko_vm_layout.narrow_klass_shift > 0 || g_neko_vm_layout.narrow_klass_base != 0u;\n");
        sb.append("}\n\n");
        sb.append("static inline void* neko_load_heap_oop_at(void *base, ptrdiff_t offset, jboolean is_volatile) {\n");
        sb.append("    if (base == NULL || offset < 0) return NULL;\n");
        sb.append("    if (neko_uses_compressed_oops()) {\n");
        sb.append("        u4 narrow = is_volatile ? __atomic_load_n((u4*)((uint8_t*)base + offset), __ATOMIC_SEQ_CST) : *(u4*)((uint8_t*)base + offset);\n");
        sb.append("        return neko_decode_heap_oop(narrow);\n");
        sb.append("    }\n");
        sb.append("    return is_volatile ? __atomic_load_n((void**)((uint8_t*)base + offset), __ATOMIC_SEQ_CST) : *(void**)((uint8_t*)base + offset);\n");
        sb.append("}\n\n");
        sb.append("typedef struct Klass Klass;\n");
        sb.append("typedef void* oop;\n");
        sb.append("static inline void *volatile *neko_decode_jni_global_ref_slot(jobject global_ref, int jdk_feature) {\n");
        sb.append("    uintptr_t handle = (uintptr_t)global_ref;\n");
        sb.append("    if (global_ref == NULL) return NULL;\n");
        sb.append("    if (jdk_feature >= 21) return (void *volatile *)(uintptr_t)(handle - 2u);\n");
        sb.append("    return (void *volatile *)(uintptr_t)handle;\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_resolve_field_site_with_class(JNIEnv *env, void *thread, NekoManifestFieldSite *site, jclass owner_class) {\n");
        sb.append("    jfieldID fid;\n");
        sb.append("    jobject reflected = NULL;\n");
        sb.append("    jobject static_base = NULL;\n");
        sb.append("    jobject static_base_global = NULL;\n");
        sb.append("    ptrdiff_t offset;\n");
        sb.append("    char message[320];\n");
        sb.append("    if (env == NULL || site == NULL || owner_class == NULL) return JNI_FALSE;\n");
        sb.append("    fid = site->is_static ? neko_get_static_field_id(env, owner_class, site->field_name, site->field_desc) : neko_get_field_id(env, owner_class, site->field_name, site->field_desc);\n");
        sb.append("    if (fid == NULL || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, \"java/lang/NoSuchFieldError\", site->field_name); return JNI_FALSE; }\n");
        sb.append("    reflected = neko_to_reflected_field(env, owner_class, fid, site->is_static ? JNI_TRUE : JNI_FALSE);\n");
        sb.append("    if (reflected == NULL || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, \"java/lang/NoSuchFieldError\", site->field_name); return JNI_FALSE; }\n");
        sb.append("    offset = neko_wave2_reflected_field_offset(env, reflected, site->is_static ? JNI_TRUE : JNI_FALSE);\n");
        sb.append("    if (offset < 0 || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); snprintf(message, sizeof(message), \"failed to resolve field offset %s.%s:%s\", site->owner_internal, site->field_name, site->field_desc); neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStateException\", message); neko_delete_local_ref(env, reflected); return JNI_FALSE; }\n");
        sb.append("    if (site->is_static) {\n");
        sb.append("        static_base = neko_wave2_reflected_static_field_base(env, reflected);\n");
        sb.append("        if (static_base == NULL || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); snprintf(message, sizeof(message), \"failed to resolve static field base %s.%s:%s\", site->owner_internal, site->field_name, site->field_desc); neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStateException\", message); if (static_base != NULL) neko_delete_local_ref(env, static_base); neko_delete_local_ref(env, reflected); return JNI_FALSE; }\n");
        sb.append("        static_base_global = neko_new_global_ref(env, static_base);\n");
        sb.append("        neko_delete_local_ref(env, static_base);\n");
        sb.append("        if (static_base_global == NULL || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); snprintf(message, sizeof(message), \"failed to globalize static field base %s.%s:%s\", site->owner_internal, site->field_name, site->field_desc); neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStateException\", message); neko_delete_local_ref(env, reflected); return JNI_FALSE; }\n");
        sb.append("        site->static_base_global_ref = static_base_global;\n");
        sb.append("        __atomic_store_n(&site->static_base_slot, neko_decode_jni_global_ref_slot(static_base_global, g_neko_vm_layout.java_spec_version), __ATOMIC_RELEASE);\n");
        sb.append("        site->field_offset_cookie = offset;\n");
        sb.append("        neko_native_trace_log(2, \"sfb %s.%s:%s g=%p slot=%p off=%td\", site->owner_internal, site->field_name, site->field_desc, static_base_global, site->static_base_slot, offset);\n");
        sb.append("    }\n");
        sb.append("    site->is_volatile = neko_wave2_field_is_volatile(env, reflected) ? 1u : 0u;\n");
        sb.append("    if (neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStateException\", \"failed to inspect field modifiers\"); neko_delete_local_ref(env, reflected); return JNI_FALSE; }\n");
        sb.append("    __atomic_store_n(&site->resolved_offset, offset, __ATOMIC_RELEASE);\n");
        sb.append("    neko_delete_local_ref(env, reflected);\n");
        sb.append("    neko_native_debug_log(\"fsr %s.%s:%s off=%td v=%u\", site->owner_internal, site->field_name, site->field_desc, offset, (unsigned)site->is_volatile);\n");
        sb.append("    return JNI_TRUE;\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_ensure_field_site_resolved(void *thread, NekoManifestFieldSite *site) {\n");
        sb.append("    ptrdiff_t current = site == NULL ? NEKO_FIELD_SITE_FAILED : __atomic_load_n(&site->resolved_offset, __ATOMIC_ACQUIRE);\n");
        sb.append("    JNIEnv *env;\n");
        sb.append("    jclass owner_class;\n");
        sb.append("    if (site == NULL) return JNI_FALSE;\n");
        sb.append("    if (current >= 0) return JNI_TRUE;\n");
        sb.append("    if (current == NEKO_FIELD_SITE_FAILED) {\n");
        sb.append("        void *oop = neko_new_exception_oop(neko_current_env(), \"java/lang/NoSuchFieldError\", site->field_name);\n");
        sb.append("        if (oop == NULL) oop = neko_take_pending_jni_exception_oop(neko_current_env());\n");
        sb.append("        if (oop != NULL) neko_set_pending_exception(thread, oop);\n");
        sb.append("        return JNI_FALSE;\n");
        sb.append("    }\n");
        sb.append("    env = neko_current_env();\n");
        sb.append("    if (env == NULL) return JNI_FALSE;\n");
        sb.append("    owner_class = (site->owner_class_slot != NULL) ? *site->owner_class_slot : NULL;\n");
        sb.append("    if (owner_class == NULL) {\n");
        sb.append("        jclass local = neko_load_class_noinit(env, site->owner_internal);\n");
        sb.append("        if (local == NULL || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, \"java/lang/NoClassDefFoundError\", site->owner_internal); return JNI_FALSE; }\n");
        sb.append("        owner_class = (jclass)neko_new_global_ref(env, local);\n");
        sb.append("        neko_delete_local_ref(env, local);\n");
        sb.append("        if (site->owner_class_slot != NULL && owner_class != NULL) *site->owner_class_slot = owner_class;\n");
        sb.append("    }\n");
        sb.append("    return neko_resolve_field_site_with_class(env, thread, site, owner_class);\n");
        sb.append("}\n\n");
        sb.append("static void neko_resolve_prepared_class_field_sites(JNIEnv *env, jclass klass, const char *owner_internal, void *owner_klass) {\n");
        sb.append("    void *thread = neko_get_current_thread();\n");
        sb.append("    jboolean cached_klass_logged = JNI_FALSE;\n");
        sb.append("    if (env == NULL || klass == NULL || owner_internal == NULL) return;\n");
        sb.append("    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {\n");
        sb.append("        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];\n");
        sb.append("        for (uint32_t site_index = 0; site_index < method->field_site_count; site_index++) {\n");
        sb.append("            NekoManifestFieldSite *site = &method->field_sites[site_index];\n");
        sb.append("            if (site->owner_internal == NULL || strcmp(site->owner_internal, owner_internal) != 0) continue;\n");
        sb.append("            if (owner_klass != NULL && __atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE) == NULL) {\n");
        sb.append("                __atomic_store_n(&site->cached_klass, owner_klass, __ATOMIC_RELEASE);\n");
        sb.append("                if (!cached_klass_logged) {\n");
        sb.append("                    cached_klass_logged = JNI_TRUE;\n");
        sb.append("                    neko_native_debug_log(\"ck %s -> %p\", owner_internal, owner_klass);\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("            if (site->owner_class_slot != NULL && *site->owner_class_slot == NULL) *site->owner_class_slot = (jclass)neko_new_global_ref(env, klass);\n");
        sb.append("            if (__atomic_load_n(&site->resolved_offset, __ATOMIC_ACQUIRE) == NEKO_FIELD_SITE_UNRESOLVED) (void)neko_resolve_field_site_with_class(env, thread, site, klass);\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n\n");
        sb.append("static uint32_t neko_count_cached_static_field_bases(void) {\n");
        sb.append("    uint32_t count = 0u;\n");
        sb.append("    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {\n");
        sb.append("        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];\n");
        sb.append("        for (uint32_t site_index = 0; site_index < method->field_site_count; site_index++) {\n");
        sb.append("            NekoManifestFieldSite *site = &method->field_sites[site_index];\n");
        sb.append("            if (site->is_static && __atomic_load_n(&site->static_base_slot, __ATOMIC_ACQUIRE) != NULL) count++;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    return count;\n");
        sb.append("}\n\n");
        sb.append("__attribute__((visibility(\"default\"))) void* neko_field_site_static_base(void *thread, NekoManifestFieldSite *site) {\n");
        sb.append("    void *volatile *static_base_slot;\n");
        sb.append("    oop mirror;\n");
        sb.append("    JNIEnv *env;\n");
        sb.append("    if (!neko_ensure_field_site_resolved(thread, site)) return NULL;\n");
        sb.append("    env = neko_current_env();\n");
        sb.append("    static_base_slot = __atomic_load_n(&site->static_base_slot, __ATOMIC_ACQUIRE);\n");
        sb.append("    if (static_base_slot == NULL) {\n");
        sb.append("        if (env != NULL) neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStateException\", \"missing static field base slot\");\n");
        sb.append("        return NULL;\n");
        sb.append("    }\n");
        sb.append("    mirror = *static_base_slot;\n");
        sb.append("    if (mirror == NULL) {\n");
        sb.append("        if (env != NULL && neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStateException\", \"failed to resolve static field mirror\");\n");
        sb.append("        return NULL;\n");
        sb.append("    }\n");
        sb.append("    neko_native_debug_log(\"sfm %s.%s:%s slot=%p mir=%p off=%td\", site->owner_internal, site->field_name, site->field_desc, static_base_slot, mirror, site->field_offset_cookie);\n");
        sb.append("    neko_native_trace_log(2, \"sfr %s.%s:%s slot=%p mir=%p off=%td addr=%p\", site->owner_internal, site->field_name, site->field_desc, static_base_slot, mirror, site->field_offset_cookie, (void*)((char*)mirror + site->field_offset_cookie));\n");
        sb.append("    return mirror;\n");
        sb.append("}\n\n");
        sb.append("static inline void *neko_static_mirror(const NekoManifestFieldSite *site) {\n");
        sb.append("    return *(site->static_base_slot);\n");
        sb.append("}\n\n");
        sb.append("static inline int32_t *neko_static_i32_addr(const NekoManifestFieldSite *site) {\n");
        sb.append("    void *mirror = neko_static_mirror(site);\n");
        sb.append("    return (int32_t *)((char *)mirror + (ptrdiff_t)site->field_offset_cookie);\n");
        sb.append("}\n\n");
        sb.append("static inline int32_t neko_getstatic_i32(const NekoManifestFieldSite *site) {\n");
        sb.append("    return *neko_static_i32_addr(site);\n");
        sb.append("}\n\n");
        sb.append("static inline void neko_putstatic_i32(const NekoManifestFieldSite *site, int32_t v) {\n");
        sb.append("    *neko_static_i32_addr(site) = v;\n");
        sb.append("}\n\n");
        sb.append("static inline int64_t *neko_static_i64_addr(const NekoManifestFieldSite *site) {\n");
        sb.append("    void *mirror = neko_static_mirror(site);\n");
        sb.append("    return (int64_t *)((char *)mirror + (ptrdiff_t)site->field_offset_cookie);\n");
        sb.append("}\n\n");
        sb.append("static inline int64_t neko_getstatic_i64(const NekoManifestFieldSite *site) {\n");
        sb.append("    return *neko_static_i64_addr(site);\n");
        sb.append("}\n\n");
        sb.append("static inline void neko_putstatic_i64(const NekoManifestFieldSite *site, int64_t v) {\n");
        sb.append("    *neko_static_i64_addr(site) = v;\n");
        sb.append("}\n\n");
        sb.append("static inline float *neko_static_f32_addr(const NekoManifestFieldSite *site) {\n");
        sb.append("    void *mirror = neko_static_mirror(site);\n");
        sb.append("    return (float *)((char *)mirror + (ptrdiff_t)site->field_offset_cookie);\n");
        sb.append("}\n\n");
        sb.append("static inline float neko_getstatic_f32(const NekoManifestFieldSite *site) {\n");
        sb.append("    return *neko_static_f32_addr(site);\n");
        sb.append("}\n\n");
        sb.append("static inline void neko_putstatic_f32(const NekoManifestFieldSite *site, float v) {\n");
        sb.append("    *neko_static_f32_addr(site) = v;\n");
        sb.append("}\n\n");
        sb.append("static inline double *neko_static_f64_addr(const NekoManifestFieldSite *site) {\n");
        sb.append("    void *mirror = neko_static_mirror(site);\n");
        sb.append("    return (double *)((char *)mirror + (ptrdiff_t)site->field_offset_cookie);\n");
        sb.append("}\n\n");
        sb.append("static inline double neko_getstatic_f64(const NekoManifestFieldSite *site) {\n");
        sb.append("    return *neko_static_f64_addr(site);\n");
        sb.append("}\n\n");
        sb.append("static inline void neko_putstatic_f64(const NekoManifestFieldSite *site, double v) {\n");
        sb.append("    *neko_static_f64_addr(site) = v;\n");
        sb.append("}\n\n");
        sb.append("static inline void* neko_field_read_oop(void *base, const NekoManifestFieldSite *site) {\n");
        sb.append("    return neko_load_heap_oop_at(base, site == NULL ? -1 : site->resolved_offset, site != NULL && site->is_volatile ? JNI_TRUE : JNI_FALSE);\n");
        sb.append("}\n\n");
        appendWave2PrimitiveFieldAccessors(sb, 'Z', "jboolean");
        appendWave2PrimitiveFieldAccessors(sb, 'B', "jbyte");
        appendWave2PrimitiveFieldAccessors(sb, 'C', "jchar");
        appendWave2PrimitiveFieldAccessors(sb, 'S', "jshort");
        appendWave2PrimitiveFieldAccessors(sb, 'I', "jint");
        appendWave2PrimitiveFieldAccessors(sb, 'J', "jlong");
        appendWave2PrimitiveFieldAccessors(sb, 'F', "jfloat");
        appendWave2PrimitiveFieldAccessors(sb, 'D', "jdouble");
        sb.append("""
#define NEKO_MAX_INLINE_UTF16 1024
#define NEKO_MAX_INLINE_PAYLOAD 2048

static jboolean neko_mutf8_decode_unit(const uint8_t *bytes, size_t len, size_t *cursor, uint16_t *out) {
    uint8_t b0;
    if (bytes == NULL || cursor == NULL || out == NULL || *cursor >= len) return JNI_FALSE;
    b0 = bytes[(*cursor)++];
    if ((b0 & 0x80u) == 0u) {
        if (b0 == 0u) return JNI_FALSE;
        *out = (uint16_t)b0;
        return JNI_TRUE;
    }
    if ((b0 & 0xE0u) == 0xC0u) {
        uint8_t b1;
        uint16_t value;
        if (*cursor >= len) return JNI_FALSE;
        b1 = bytes[(*cursor)++];
        if ((b1 & 0xC0u) != 0x80u) return JNI_FALSE;
        value = (uint16_t)(((uint16_t)(b0 & 0x1Fu) << 6) | (uint16_t)(b1 & 0x3Fu));
        if (value == 0u && (b0 != 0xC0u || b1 != 0x80u)) return JNI_FALSE;
        *out = value;
        return JNI_TRUE;
    }
    if ((b0 & 0xF0u) == 0xE0u) {
        uint8_t b1;
        uint8_t b2;
        if ((*cursor + 1u) >= len) return JNI_FALSE;
        b1 = bytes[(*cursor)++];
        b2 = bytes[(*cursor)++];
        if ((b1 & 0xC0u) != 0x80u || (b2 & 0xC0u) != 0x80u) return JNI_FALSE;
        *out = (uint16_t)((((uint16_t)(b0 & 0x0Fu)) << 12)
            | (((uint16_t)(b1 & 0x3Fu)) << 6)
            | (uint16_t)(b2 & 0x3Fu));
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean neko_decode_mutf8_to_utf16(const uint8_t *bytes, size_t len, uint16_t **utf16_out, int32_t *utf16_len_out, int32_t *heap_alloc_out) {
    size_t cursor = 0u;
    size_t count = 0u;
    uint16_t *utf16;
    int32_t heap_alloc = 0;
    if (utf16_out == NULL || *utf16_out == NULL || utf16_len_out == NULL || heap_alloc_out == NULL) return JNI_FALSE;
    while (cursor < len) {
        uint16_t code_unit;
        if (!neko_mutf8_decode_unit(bytes, len, &cursor, &code_unit)) return JNI_FALSE;
        count++;
        if (count > 0x7fffffffU) return JNI_FALSE;
    }
    utf16 = *utf16_out;
    if (count > NEKO_MAX_INLINE_UTF16) {
        utf16 = (uint16_t*)malloc((count == 0u ? 1u : count) * sizeof(uint16_t));
        if (utf16 == NULL) return JNI_FALSE;
        heap_alloc = 1;
    }
    cursor = 0u;
    count = 0u;
    while (cursor < len) {
        uint16_t code_unit;
        if (!neko_mutf8_decode_unit(bytes, len, &cursor, &code_unit)) {
            if (heap_alloc) free(utf16);
            return JNI_FALSE;
        }
        utf16[count++] = code_unit;
    }
    *utf16_out = utf16;
    *utf16_len_out = (int32_t)count;
    *heap_alloc_out = heap_alloc;
    return JNI_TRUE;
}

static uint32_t neko_string_intern_hash(uint32_t coder, uint32_t char_length, const uint8_t *payload, uint32_t payload_length) {
    uint32_t hash = 2166136261u;
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)(coder & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((coder >> 8) & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((coder >> 16) & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((coder >> 24) & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)(char_length & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((char_length >> 8) & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((char_length >> 16) & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((char_length >> 24) & 0xFFu));
    for (uint32_t i = 0; i < payload_length; i++) {
        hash = neko_fnv1a32_update_byte(hash, payload[i]);
    }
    return hash;
}

static void neko_resolve_ldc_string(NekoManifestLdcSite* site) {
    uint16_t utf16_buf[NEKO_MAX_INLINE_UTF16];
    uint16_t* utf16 = utf16_buf;
    int32_t utf16_len = 0;
    int32_t heap_alloc = 0;
    int32_t is_jdk8;
    int32_t coder;
    void* array_klass;
    int32_t array_len;
    void* inner_array;
    uint32_t lh_arr;
    uint32_t header_bytes;
    uint8_t* array_base;
    uint32_t lh_str;
    size_t string_size;
    void* string_oop;
    uint32_t key_payload_bytes;
    uint8_t key_stackbuf[NEKO_MAX_INLINE_PAYLOAD];
    uint8_t* key_bytes = key_stackbuf;
    uint8_t* key_heap = NULL;
    uint32_t h;
    uint32_t bucket_idx;
    NekoStringInternEntry* existing;
    uint32_t slot;
    NekoStringInternEntry* entry;
    uint8_t* stored_payload;
    size_t stored_payload_size;
    int32_t elem_off;
    if (site == NULL) return;
    if (site->resolved_cache_handle != NULL) {
        return;
    }
    if (g_neko_vm_layout.klass_java_lang_String == NULL) return;
    if (g_neko_vm_layout.off_string_value < 0) return;
    if (g_neko_vm_layout.off_string_hash < 0) return;
    if (!neko_decode_mutf8_to_utf16(site->raw_constant_utf8, site->raw_constant_utf8_len, &utf16, &utf16_len, &heap_alloc)) {
        return;
    }
    is_jdk8 = (g_neko_vm_layout.off_string_coder < 0);
    if (is_jdk8) {
        coder = 1;
    } else {
        coder = 0;
        for (int32_t i = 0; i < utf16_len; i++) {
            if (utf16[i] > 0x00FFu) {
                coder = 1;
                break;
            }
        }
    }
    array_klass = is_jdk8
        ? g_neko_vm_layout.klass_array_char
        : g_neko_vm_layout.klass_array_byte;
    if (array_klass == NULL) {
        if (heap_alloc) free(utf16);
        return;
    }
    array_len = is_jdk8
        ? utf16_len
        : (coder == 0 ? utf16_len : utf16_len * 2);
    inner_array = neko_rt_try_alloc_array_fast_nosafepoint(array_klass, array_len);
    if (inner_array == NULL) {
        if (heap_alloc) free(utf16);
        return;
    }
    lh_arr = *(uint32_t*)((uint8_t*)array_klass + g_neko_vm_layout.off_klass_layout_helper);
    header_bytes = neko_lh_header_size(lh_arr);
    array_base = (uint8_t*)inner_array + header_bytes;
    if (is_jdk8) {
        for (int32_t i = 0; i < utf16_len; i++) {
            *(uint16_t*)(array_base + i * 2) = utf16[i];
        }
    } else if (coder == 0) {
        for (int32_t i = 0; i < utf16_len; i++) {
            array_base[i] = (uint8_t)utf16[i];
        }
    } else {
        for (int32_t i = 0; i < utf16_len; i++) {
            array_base[2 * i] = (uint8_t)(utf16[i] >> 8);
            array_base[2 * i + 1] = (uint8_t)(utf16[i] & 0xFFu);
        }
    }
    lh_str = *(uint32_t*)((uint8_t*)g_neko_vm_layout.klass_java_lang_String + g_neko_vm_layout.off_klass_layout_helper);
    string_size = neko_lh_instance_size(lh_str);
    string_oop = neko_rt_try_alloc_instance_fast_nosafepoint((Klass*)g_neko_vm_layout.klass_java_lang_String, string_size);
    if (string_oop == NULL) {
        if (heap_alloc) free(utf16);
        return;
    }
    neko_store_heap_oop_at_unpublished(string_oop, g_neko_vm_layout.off_string_value, inner_array);
    if (!is_jdk8) {
        *(uint8_t*)((uint8_t*)string_oop + g_neko_vm_layout.off_string_coder) = (uint8_t)coder;
    }
    *(int32_t*)((uint8_t*)string_oop + g_neko_vm_layout.off_string_hash) = 0;
    key_payload_bytes = is_jdk8
        ? (uint32_t)(utf16_len * 2)
        : (coder == 0 ? (uint32_t)utf16_len : (uint32_t)utf16_len * 2u);
    if ((size_t)key_payload_bytes > sizeof(key_stackbuf)) {
        key_heap = (uint8_t*)malloc(key_payload_bytes);
        if (key_heap == NULL) {
            if (heap_alloc) free(utf16);
            return;
        }
        key_bytes = key_heap;
    }
    if (is_jdk8) {
        for (int32_t i = 0; i < utf16_len; i++) {
            key_bytes[2 * i] = (uint8_t)(utf16[i] >> 8);
            key_bytes[2 * i + 1] = (uint8_t)(utf16[i] & 0xFFu);
        }
    } else if (key_payload_bytes != 0u) {
        memcpy(key_bytes, array_base, key_payload_bytes);
    }
    h = neko_string_intern_hash((uint32_t)coder, (uint32_t)utf16_len, key_bytes, key_payload_bytes);
    bucket_idx = h % NEKO_STRING_INTERN_BUCKET_COUNT;
    existing = g_neko_string_intern_buckets[bucket_idx];
    while (existing != NULL) {
        if (existing->coder == (uint32_t)coder
            && existing->char_length == (uint32_t)utf16_len
            && existing->payload_length == key_payload_bytes
            && (key_payload_bytes == 0u || memcmp(existing->payload, key_bytes, key_payload_bytes) == 0)) {
            if (key_heap) free(key_heap);
            if (heap_alloc) free(utf16);
            site->resolved_cache_handle = existing;
            return;
        }
        existing = existing->next;
    }
    if (g_neko_string_intern_filled >= NEKO_STRING_INTERN_SLOT_COUNT) {
        if (key_heap) free(key_heap);
        if (heap_alloc) free(utf16);
        return;
    }
    if (g_neko_string_roots_array == NULL || g_neko_vm_layout.klass_array_object == NULL) {
        if (key_heap) free(key_heap);
        if (heap_alloc) free(utf16);
        return;
    }
    stored_payload_size = key_payload_bytes == 0u ? 1u : (size_t)key_payload_bytes;
    stored_payload = (uint8_t*)malloc(stored_payload_size);
    if (stored_payload == NULL) {
        if (key_heap) free(key_heap);
        if (heap_alloc) free(utf16);
        return;
    }
    if (key_payload_bytes != 0u) {
        memcpy(stored_payload, key_bytes, key_payload_bytes);
    }
    slot = g_neko_string_intern_filled;
    entry = &g_neko_string_intern_entries[slot];
    entry->coder = (uint32_t)coder;
    entry->char_length = (uint32_t)utf16_len;
    entry->payload_length = key_payload_bytes;
    entry->slot_index = slot;
    entry->payload = stored_payload;
    entry->next = g_neko_string_intern_buckets[bucket_idx];
    g_neko_string_intern_buckets[bucket_idx] = entry;
    g_neko_string_intern_filled = slot + 1u;
    elem_off = neko_object_array_element_offset(g_neko_vm_layout.klass_array_object, (int32_t)slot);
    neko_store_heap_oop_at_unpublished(g_neko_string_roots_array, elem_off, string_oop);
    site->resolved_cache_handle = entry;
    if (key_heap) free(key_heap);
    if (heap_alloc) free(utf16);
}

""");
        sb.append("static jobject neko_owner_class_loader(JNIEnv *env, jclass owner_class) {\n");
        sb.append("    jclass classClass;\n");
        sb.append("    jmethodID getClassLoader;\n");
        sb.append("    if (env == NULL || owner_class == NULL) return NULL;\n");
        sb.append("    classClass = neko_find_class(env, \"java/lang/Class\");\n");
        sb.append("    if (classClass == NULL || neko_exception_check(env)) return NULL;\n");
        sb.append("    getClassLoader = neko_get_method_id(env, classClass, \"getClassLoader\", \"()Ljava/lang/ClassLoader;\");\n");
        sb.append("    if (getClassLoader == NULL || neko_exception_check(env)) { neko_delete_local_ref(env, classClass); return NULL; }\n");
        sb.append("    { jobject loader = neko_call_object_method_a(env, owner_class, getClassLoader, NULL); neko_delete_local_ref(env, classClass); return loader; }\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_ldc_site_signature_equals(const NekoManifestLdcSite *site, const char *signature) {\n");
        sb.append("    size_t signature_len;\n");
        sb.append("    if (site == NULL || signature == NULL || site->kind != NEKO_LDC_KIND_CLASS || site->raw_constant_utf8 == NULL) return JNI_FALSE;\n");
        sb.append("    signature_len = strlen(signature);\n");
        sb.append("    if (signature_len != site->raw_constant_utf8_len) return JNI_FALSE;\n");
        sb.append("    return memcmp(site->raw_constant_utf8, signature, signature_len) == 0 ? JNI_TRUE : JNI_FALSE;\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_ldc_site_matches_loaded_class(JNIEnv *env, NekoManifestLdcSite *site, jclass candidate, const char *signature) {\n");
        sb.append("    jclass owner_class;\n");
        sb.append("    jobject owner_loader;\n");
        sb.append("    jobject candidate_loader;\n");
        sb.append("    jboolean same_loader;\n");
        sb.append("    if (env == NULL || site == NULL || candidate == NULL || !neko_ldc_site_signature_equals(site, signature)) return JNI_FALSE;\n");
        sb.append("    owner_class = site->owner_class_slot == NULL ? NULL : *site->owner_class_slot;\n");
        sb.append("    if (owner_class == NULL) return JNI_FALSE;\n");
        sb.append("    owner_loader = neko_owner_class_loader(env, owner_class);\n");
        sb.append("    if (neko_exception_check(env)) return JNI_FALSE;\n");
        sb.append("    candidate_loader = neko_owner_class_loader(env, candidate);\n");
        sb.append("    if (neko_exception_check(env)) { if (owner_loader != NULL) neko_delete_local_ref(env, owner_loader); return JNI_FALSE; }\n");
        sb.append("    same_loader = neko_is_same_object(env, owner_loader, candidate_loader);\n");
        sb.append("    if (candidate_loader != NULL) neko_delete_local_ref(env, candidate_loader);\n");
        sb.append("    if (owner_loader != NULL) neko_delete_local_ref(env, owner_loader);\n");
        sb.append("    return same_loader;\n");
        sb.append("}\n\n");
        sb.append("static void* neko_class_klass_pointer(jclass klass_obj) {\n");
        sb.append("    if (klass_obj == NULL || g_neko_vm_layout.off_class_klass < 0) return NULL;\n");
        sb.append("    return *(void**)((uint8_t*)neko_handle_oop((jobject)klass_obj) + g_neko_vm_layout.off_class_klass);\n");
        sb.append("}\n\n");
        sb.append("static char* neko_ldc_site_binary_name(const NekoManifestLdcSite *site) {\n");
        sb.append("    if (site == NULL || site->raw_constant_utf8 == NULL) return NULL;\n");
        sb.append("    if (site->raw_constant_utf8_len == 0u) return neko_wave2_copy_bytes(site->raw_constant_utf8, 0u);\n");
        sb.append("    if (site->raw_constant_utf8[0] == '[') return neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);\n");
        sb.append("    if (site->raw_constant_utf8_len >= 2u && site->raw_constant_utf8[0] == 'L' && site->raw_constant_utf8[site->raw_constant_utf8_len - 1u] == ';') {\n");
        sb.append("        char *binary = (char*)malloc(site->raw_constant_utf8_len - 1u);\n");
        sb.append("        if (binary == NULL) return NULL;\n");
        sb.append("        memcpy(binary, site->raw_constant_utf8 + 1, site->raw_constant_utf8_len - 2u);\n");
        sb.append("        binary[site->raw_constant_utf8_len - 2u] = '\\0';\n");
        sb.append("        return binary;\n");
        sb.append("    }\n");
        sb.append("    return neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_ensure_ldc_class_site_resolved(void *thread, NekoManifestLdcSite *site) {\n");
        sb.append("    void *cached_klass;\n");
        sb.append("    JNIEnv *env;\n");
        sb.append("    jvmtiEnv *jvmti;\n");
        sb.append("    jclass *classes = NULL;\n");
        sb.append("    jint class_count = 0;\n");
        sb.append("    char *binary_name = NULL;\n");
        sb.append("    jclass owner_class;\n");
        sb.append("    jobject loader = NULL;\n");
        sb.append("    jclass klass_obj = NULL;\n");
        sb.append("    void *resolved_klass = NULL;\n");
        sb.append("    if (site == NULL || site->kind != NEKO_LDC_KIND_CLASS) return JNI_FALSE;\n");
        sb.append("    cached_klass = __atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);\n");
        sb.append("    if (cached_klass != NULL) return JNI_TRUE;\n");
        sb.append("    env = neko_current_env();\n");
        sb.append("    if (env == NULL) return JNI_FALSE;\n");
        sb.append("    jvmti = g_neko_jvmti;\n");
        sb.append("    if (jvmti != NULL) {\n");
        sb.append("        jvmtiError err = (*jvmti)->GetLoadedClasses(jvmti, &class_count, &classes);\n");
        sb.append("        if (err == JVMTI_ERROR_NONE) {\n");
        sb.append("            for (jint i = 0; i < class_count; i++) {\n");
        sb.append("                char *signature = NULL;\n");
        sb.append("                if (classes[i] == NULL) continue;\n");
        sb.append("                err = (*jvmti)->GetClassSignature(jvmti, classes[i], &signature, NULL);\n");
        sb.append("                if (err != JVMTI_ERROR_NONE) continue;\n");
        sb.append("                if (neko_ldc_site_matches_loaded_class(env, site, classes[i], signature)) {\n");
        sb.append("                    resolved_klass = neko_class_klass_pointer(classes[i]);\n");
        sb.append("                    neko_jvmti_deallocate(jvmti, signature);\n");
        sb.append("                    if (resolved_klass != NULL && !neko_exception_check(env)) {\n");
        sb.append("                        __atomic_store_n(&site->cached_klass, resolved_klass, __ATOMIC_RELEASE);\n");
        sb.append("                        neko_jvmti_deallocate(jvmti, classes);\n");
        sb.append("                        return JNI_TRUE;\n");
        sb.append("                    }\n");
        sb.append("                    break;\n");
        sb.append("                }\n");
        sb.append("                neko_jvmti_deallocate(jvmti, signature);\n");
        sb.append("            }\n");
        sb.append("            neko_jvmti_deallocate(jvmti, classes);\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    owner_class = site->owner_class_slot == NULL ? NULL : *site->owner_class_slot;\n");
        sb.append("    if (owner_class == NULL) {\n");
        sb.append("        void *oop = neko_new_exception_oop(env, \"java/lang/LinkageError\", \"missing owner class for ldc site\");\n");
        sb.append("        if (oop == NULL) oop = neko_take_pending_jni_exception_oop(env);\n");
        sb.append("        if (oop != NULL) neko_set_pending_exception(thread, oop);\n");
        sb.append("        return JNI_FALSE;\n");
        sb.append("    }\n");
        sb.append("    loader = neko_owner_class_loader(env, owner_class);\n");
        sb.append("    if (neko_exception_check(env)) { neko_wave2_capture_pending(env, thread, NULL, NULL); return JNI_FALSE; }\n");
        sb.append("    binary_name = neko_ldc_site_binary_name(site);\n");
        sb.append("    if (binary_name == NULL) { if (loader != NULL) neko_delete_local_ref(env, loader); return JNI_FALSE; }\n");
        sb.append("    klass_obj = neko_load_class_noinit_with_loader(env, binary_name, loader);\n");
        sb.append("    free(binary_name);\n");
        sb.append("    if (loader != NULL) neko_delete_local_ref(env, loader);\n");
        sb.append("    if (klass_obj != NULL && !neko_exception_check(env)) resolved_klass = neko_class_klass_pointer(klass_obj);\n");
        sb.append("    if (resolved_klass != NULL) { __atomic_store_n(&site->cached_klass, resolved_klass, __ATOMIC_RELEASE); if (klass_obj != NULL) neko_delete_local_ref(env, klass_obj); return JNI_TRUE; }\n");
        sb.append("    if (klass_obj != NULL) neko_delete_local_ref(env, klass_obj);\n");
        sb.append("    if (neko_exception_check(env)) { neko_wave2_capture_pending(env, thread, NULL, NULL); return JNI_FALSE; }\n");
        sb.append("    {\n");
        sb.append("        char *message = neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);\n");
        sb.append("        void *oop = neko_new_exception_oop(env, \"java/lang/NoClassDefFoundError\", message);\n");
        sb.append("        if (message != NULL) free(message);\n");
        sb.append("        if (oop == NULL) oop = neko_take_pending_jni_exception_oop(env);\n");
        sb.append("        if (oop != NULL) neko_set_pending_exception(thread, oop);\n");
        sb.append("    }\n");
        sb.append("    return JNI_FALSE;\n");
        sb.append("}\n\n");
        sb.append("__attribute__((visibility(\"default\"))) void* neko_ldc_class_site_oop(void *thread, NekoManifestLdcSite *site) {\n");
        sb.append("    if (!neko_ensure_ldc_class_site_resolved(thread, site)) return NULL;\n");
        sb.append("    return neko_rt_mirror_from_klass_nosafepoint((Klass*)__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE));\n");
        sb.append("}\n\n");
        sb.append("static inline jobject neko_ldc_string_site_oop(JNIEnv *env, NekoManifestLdcSite *site) {\n");
        sb.append("    NekoStringInternEntry* entry;\n");
        sb.append("    void* loader_mirror;\n");
        sb.append("    void* root_array;\n");
        sb.append("    int32_t elem_off;\n");
        sb.append("    (void)env;\n");
        sb.append("    if (site == NULL) return NULL;\n");
        sb.append("    entry = (NekoStringInternEntry*)site->resolved_cache_handle;\n");
        sb.append("    if (entry == NULL) return NULL;\n");
        sb.append("    if (g_neko_vm_layout.klass_array_object == NULL) return NULL;\n");
        sb.append("    if (g_neko_vm_layout.klass_neko_native_loader == NULL) return NULL;\n");
        sb.append("    if (g_neko_vm_layout.off_loader_string_roots < 0) return NULL;\n");
        sb.append("    loader_mirror = neko_rt_mirror_from_klass_nosafepoint((Klass*)g_neko_vm_layout.klass_neko_native_loader);\n");
        sb.append("    if (loader_mirror == NULL) return NULL;\n");
        sb.append("    root_array = neko_load_heap_oop_from_published(loader_mirror, g_neko_vm_layout.off_loader_string_roots);\n");
        sb.append("    if (root_array == NULL) return NULL;\n");
        sb.append("    elem_off = neko_object_array_element_offset(g_neko_vm_layout.klass_array_object, (int32_t)entry->slot_index);\n");
        sb.append("    return neko_load_heap_oop_from_published(root_array, elem_off);\n");
        sb.append("}\n\n");
        sb.append("static void* neko_resolve_ldc_site_oop(void *thread, NekoManifestLdcSite *site) {\n");
        sb.append("    if (site == NULL) return NULL;\n");
        sb.append("    if (site->kind == NEKO_LDC_KIND_CLASS) {\n");
        sb.append("        return neko_ldc_class_site_oop(thread, site);\n");
        sb.append("    }\n");
        sb.append("    return neko_ldc_string_site_oop(neko_current_env(), site);\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_prewarm_ldc_sites(JNIEnv *env) {\n");
        sb.append("    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {\n");
        sb.append("        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];\n");
        sb.append("        for (uint32_t site_index = 0; site_index < method->ldc_site_count; site_index++) {\n");
        sb.append("            NekoManifestLdcSite *site = &method->ldc_sites[site_index];\n");
        sb.append("            void *cached = __atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE);\n");
        sb.append("            void *created;\n");
        sb.append("            if (site->kind == NEKO_LDC_KIND_CLASS) {\n");
        sb.append("                const char *signature = neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);\n");
        sb.append("                if (__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE) == NULL) {\n");
        sb.append("                    if (!neko_ensure_ldc_class_site_resolved(neko_get_current_thread(), site)) {\n");
        sb.append("                        if (neko_pending_exception(neko_get_current_thread()) != NULL) { neko_clear_pending_exception(neko_get_current_thread()); }\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("                if (__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE) == NULL) NEKO_TRACE(0, \"[nk] ldc-cls prewarm miss idx=%u sig=\\\"%s\\\"\", site->site_id, signature == NULL ? \"\" : signature);\n");
        sb.append("                if (signature != NULL) free((void*)signature);\n");
        sb.append("                continue;\n");
        sb.append("            }\n");
        sb.append("            if (site->kind == NEKO_LDC_KIND_STRING) continue;\n");
        sb.append("            if (cached != NULL) continue;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    return JNI_TRUE;\n");
        sb.append("}\n\n");
        sb.append("static void neko_log_wave2_ready(void) {\n");
        sb.append("    neko_native_debug_log(\"w2 fs=%u ls=%u lc=%u\", g_neko_manifest_field_site_count, g_neko_manifest_ldc_string_site_count, g_neko_manifest_ldc_class_site_count);\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private void appendWave2PrimitiveFieldAccessors(StringBuilder sb, char descriptor, String cType) {
        if (descriptor == 'F') {
            sb.append("static inline ").append(cType).append(" neko_field_read_").append(descriptor).append("(void *base, const NekoManifestFieldSite *site) {\n")
                .append("    ").append(cType).append(" *slot = (").append(cType).append("*)((uint8_t*)base + site->resolved_offset);\n")
                .append("    if (site->is_volatile) { uint32_t bits = __atomic_load_n((uint32_t*)slot, __ATOMIC_SEQ_CST); ").append(cType).append(" value; __builtin_memcpy(&value, &bits, sizeof(bits)); return value; }\n")
                .append("    return *slot;\n")
                .append("}\n\n")
                .append("static inline void neko_field_write_").append(descriptor).append("(void *base, const NekoManifestFieldSite *site, ").append(cType).append(" value) {\n")
                .append("    ").append(cType).append(" *slot = (").append(cType).append("*)((uint8_t*)base + site->resolved_offset);\n")
                .append("    if (site->is_volatile) { uint32_t bits; __builtin_memcpy(&bits, &value, sizeof(bits)); __atomic_store_n((uint32_t*)slot, bits, __ATOMIC_SEQ_CST); } else { *slot = value; }\n")
                .append("}\n\n");
            return;
        }
        if (descriptor == 'D') {
            sb.append("static inline ").append(cType).append(" neko_field_read_").append(descriptor).append("(void *base, const NekoManifestFieldSite *site) {\n")
                .append("    ").append(cType).append(" *slot = (").append(cType).append("*)((uint8_t*)base + site->resolved_offset);\n")
                .append("    if (site->is_volatile) { uint64_t bits = __atomic_load_n((uint64_t*)slot, __ATOMIC_SEQ_CST); ").append(cType).append(" value; __builtin_memcpy(&value, &bits, sizeof(bits)); return value; }\n")
                .append("    return *slot;\n")
                .append("}\n\n")
                .append("static inline void neko_field_write_").append(descriptor).append("(void *base, const NekoManifestFieldSite *site, ").append(cType).append(" value) {\n")
                .append("    ").append(cType).append(" *slot = (").append(cType).append("*)((uint8_t*)base + site->resolved_offset);\n")
                .append("    if (site->is_volatile) { uint64_t bits; __builtin_memcpy(&bits, &value, sizeof(bits)); __atomic_store_n((uint64_t*)slot, bits, __ATOMIC_SEQ_CST); } else { *slot = value; }\n")
                .append("}\n\n");
            return;
        }
        sb.append("static inline ").append(cType).append(" neko_field_read_").append(descriptor).append("(void *base, const NekoManifestFieldSite *site) {\n")
            .append("    ").append(cType).append(" *slot = (").append(cType).append("*)((uint8_t*)base + site->resolved_offset);\n")
            .append("    return site->is_volatile ? __atomic_load_n(slot, __ATOMIC_SEQ_CST) : *slot;\n")
            .append("}\n\n")
            .append("static inline void neko_field_write_").append(descriptor).append("(void *base, const NekoManifestFieldSite *site, ").append(cType).append(" value) {\n")
            .append("    ").append(cType).append(" *slot = (").append(cType).append("*)((uint8_t*)base + site->resolved_offset);\n")
            .append("    if (site->is_volatile) { __atomic_store_n(slot, value, __ATOMIC_SEQ_CST); } else { *slot = value; }\n")
            .append("}\n\n");
    }

    public boolean isPrimitiveFieldDescriptor(String desc) {
        return desc != null && desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0;
    }
}
