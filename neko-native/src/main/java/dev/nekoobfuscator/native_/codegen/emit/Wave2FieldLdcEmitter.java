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
        sb.append("__attribute__((visibility(\"default\"))) oop neko_rt_static_base_from_holder_nosafepoint(Klass *holder);\n\n");
        sb.append("static jboolean neko_resolve_field_site_with_class(JNIEnv *env, void *thread, NekoManifestFieldSite *site, jclass owner_class) {\n");
        sb.append("    jfieldID fid;\n");
        sb.append("    jobject reflected = NULL;\n");
        sb.append("    ptrdiff_t offset;\n");
        sb.append("    char message[320];\n");
        sb.append("    if (env == NULL || site == NULL || owner_class == NULL) return JNI_FALSE;\n");
        sb.append("    fid = site->is_static ? neko_get_static_field_id(env, owner_class, site->field_name, site->field_desc) : neko_get_field_id(env, owner_class, site->field_name, site->field_desc);\n");
        sb.append("    if (fid == NULL || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, \"java/lang/NoSuchFieldError\", site->field_name); return JNI_FALSE; }\n");
        sb.append("    reflected = neko_to_reflected_field(env, owner_class, fid, site->is_static ? JNI_TRUE : JNI_FALSE);\n");
        sb.append("    if (reflected == NULL || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, \"java/lang/NoSuchFieldError\", site->field_name); return JNI_FALSE; }\n");
        sb.append("    offset = neko_wave2_reflected_field_offset(env, reflected, site->is_static ? JNI_TRUE : JNI_FALSE);\n");
        sb.append("    if (offset < 0 || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); snprintf(message, sizeof(message), \"failed to resolve field offset %s.%s:%s\", site->owner_internal, site->field_name, site->field_desc); neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStateException\", message); neko_delete_local_ref(env, reflected); return JNI_FALSE; }\n");
        sb.append("    site->is_volatile = neko_wave2_field_is_volatile(env, reflected) ? 1u : 0u;\n");
        sb.append("    if (neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStateException\", \"failed to inspect field modifiers\"); neko_delete_local_ref(env, reflected); return JNI_FALSE; }\n");
        sb.append("    __atomic_store_n(&site->resolved_offset, offset, __ATOMIC_RELEASE);\n");
        sb.append("    neko_delete_local_ref(env, reflected);\n");
        sb.append("    neko_native_debug_log(\"resolved field site %s.%s:%s -> %td%s\", site->owner_internal, site->field_name, site->field_desc, offset, site->is_volatile ? \" volatile\" : \"\");\n");
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
        sb.append("                    neko_native_debug_log(\"cached klass for owner %s -> %p\", owner_internal, owner_klass);\n");
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
        sb.append("            if (site->is_static && __atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE) != NULL) count++;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    return count;\n");
        sb.append("}\n\n");
        sb.append("__attribute__((visibility(\"default\"))) void* neko_field_site_static_base(void *thread, NekoManifestFieldSite *site) {\n");
        sb.append("    void *cached_klass;\n");
        sb.append("    if (!neko_ensure_field_site_resolved(thread, site)) return NULL;\n");
        sb.append("    cached_klass = __atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);\n");
        sb.append("    if (cached_klass == NULL) return NULL;\n");
        sb.append("    return neko_rt_static_base_from_holder_nosafepoint((Klass*)cached_klass);\n");
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
        sb.append("__attribute__((visibility(\"default\"))) void* neko_resolve_ldc_string(const uint8_t *utf8, size_t utf8_len) {\n");
        sb.append("    JNIEnv *env = neko_current_env();\n");
        sb.append("    char *copy;\n");
        sb.append("    jstring local;\n");
        sb.append("    jobject global;\n");
        sb.append("    if (env == NULL) return NULL;\n");
        sb.append("    copy = neko_wave2_copy_bytes(utf8, utf8_len);\n");
        sb.append("    if (copy == NULL) return NULL;\n");
        sb.append("    local = neko_new_string_utf(env, copy);\n");
        sb.append("    free(copy);\n");
        sb.append("    if (local == NULL || neko_exception_check(env)) return NULL;\n");
        sb.append("    global = neko_new_global_ref(env, local);\n");
        sb.append("    neko_delete_local_ref(env, local);\n");
        sb.append("    return global;\n");
        sb.append("}\n\n");
        sb.append("static void* neko_resolve_ldc_class_handle(const uint8_t *utf8, size_t utf8_len) {\n");
        sb.append("    JNIEnv *env = neko_current_env();\n");
        sb.append("    char *copy;\n");
        sb.append("    jclass local;\n");
        sb.append("    jobject global;\n");
        sb.append("    if (env == NULL) return NULL;\n");
        sb.append("    copy = neko_wave2_copy_bytes(utf8, utf8_len);\n");
        sb.append("    if (copy == NULL) return NULL;\n");
        sb.append("    local = neko_class_for_descriptor(env, copy);\n");
        sb.append("    free(copy);\n");
        sb.append("    if (local == NULL || neko_exception_check(env)) return NULL;\n");
        sb.append("    global = neko_new_global_ref(env, local);\n");
        sb.append("    neko_delete_local_ref(env, local);\n");
        sb.append("    return global;\n");
        sb.append("}\n\n");
        sb.append("static void* neko_resolve_ldc_site_oop(void *thread, NekoManifestLdcSite *site) {\n");
        sb.append("    void *cached;\n");
        sb.append("    if (site == NULL) return NULL;\n");
        sb.append("    cached = __atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE);\n");
        sb.append("    if (cached != NULL) return neko_handle_oop((jobject)cached);\n");
        sb.append("    neko_wave2_capture_pending(neko_current_env(), thread, \"java/lang/IllegalStateException\", \"unwarmed LDC site\");\n");
        sb.append("    return NULL;\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_prewarm_ldc_sites(JNIEnv *env) {\n");
        sb.append("    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {\n");
        sb.append("        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];\n");
        sb.append("        for (uint32_t site_index = 0; site_index < method->ldc_site_count; site_index++) {\n");
        sb.append("            NekoManifestLdcSite *site = &method->ldc_sites[site_index];\n");
        sb.append("            void *cached = __atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE);\n");
        sb.append("            void *created;\n");
        sb.append("            if (cached != NULL) continue;\n");
        sb.append("            created = site->kind == NEKO_LDC_KIND_STRING ? neko_resolve_ldc_string(site->raw_constant_utf8, site->raw_constant_utf8_len) : (site->kind == NEKO_LDC_KIND_CLASS ? neko_resolve_ldc_class_handle(site->raw_constant_utf8, site->raw_constant_utf8_len) : NULL);\n");
        sb.append("            if (created == NULL || neko_exception_check(env)) {\n");
        sb.append("                if (neko_exception_check(env)) neko_exception_clear(env);\n");
        sb.append("                neko_error_log(\"failed to prewarm LDC site %u kind=%u for %s.%s%s\", site->site_id, site->kind, method->owner_internal, method->method_name, method->method_desc);\n");
        sb.append("                return JNI_FALSE;\n");
        sb.append("            }\n");
        sb.append("            __atomic_store_n(&site->resolved_cache_handle, created, __ATOMIC_RELEASE);\n");
        sb.append("            neko_native_debug_log(\"prewarmed LDC site %u kind=%u\", site->site_id, site->kind);\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    return JNI_TRUE;\n");
        sb.append("}\n\n");
        sb.append("static void neko_log_wave2_ready(void) {\n");
        sb.append("    neko_native_debug_log(\"wave2 ready (field_sites=%u, ldc_strings=%u, ldc_classes=%u)\", g_neko_manifest_field_site_count, g_neko_manifest_ldc_string_site_count, g_neko_manifest_ldc_class_site_count);\n");
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
