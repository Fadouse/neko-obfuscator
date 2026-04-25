package dev.nekoobfuscator.native_.codegen.emit;

public final class Wave2FieldLdcEmitter {
    public String renderWave2Support() {
        StringBuilder sb = new StringBuilder();
        sb.append("// === Wave 2 field/LDC support ===\n");
        sb.append("static jclass g_neko_wave2_class_cls = NULL;\n");
        sb.append("static jclass g_neko_wave2_field_cls = NULL;\n");
        sb.append("static jclass g_neko_wave2_unsafe_cls = NULL;\n");
        sb.append("static jobject g_neko_wave2_unsafe_singleton = NULL;\n");
        sb.append("static jmethodID g_neko_wave2_class_get_declared_field = NULL;\n");
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
        sb.append("    (void)thread;\n");
        sb.append("    (void)fallback_message;\n");
        sb.append("    if (fallback_class == NULL) { (void)neko_throw_cached(env, g_neko_throw_le); return; }\n");
        sb.append("    if (strcmp(fallback_class, \"java/lang/NullPointerException\") == 0) { (void)neko_throw_cached(env, g_neko_throw_npe); return; }\n");
        sb.append("    if (strcmp(fallback_class, \"java/lang/ArrayIndexOutOfBoundsException\") == 0) { (void)neko_throw_cached(env, g_neko_throw_aioobe); return; }\n");
        sb.append("    if (strcmp(fallback_class, \"java/lang/ClassCastException\") == 0) { (void)neko_throw_cached(env, g_neko_throw_cce); return; }\n");
        sb.append("    if (strcmp(fallback_class, \"java/lang/ArithmeticException\") == 0) { (void)neko_throw_cached(env, g_neko_throw_ae); return; }\n");
        sb.append("    if (strcmp(fallback_class, \"java/lang/OutOfMemoryError\") == 0) { (void)neko_throw_cached(env, g_neko_throw_oom); return; }\n");
        sb.append("    if (strcmp(fallback_class, \"java/lang/IllegalMonitorStateException\") == 0) { (void)neko_throw_cached(env, g_neko_throw_imse); return; }\n");
        sb.append("    if (strcmp(fallback_class, \"java/lang/ArrayStoreException\") == 0) { (void)neko_throw_cached(env, g_neko_throw_ase); return; }\n");
        sb.append("    if (strcmp(fallback_class, \"java/lang/NegativeArraySizeException\") == 0) { (void)neko_throw_cached(env, g_neko_throw_nase); return; }\n");
        sb.append("    if (strcmp(fallback_class, \"java/lang/BootstrapMethodError\") == 0) { (void)neko_throw_cached(env, g_neko_throw_bme); return; }\n");
        sb.append("    (void)neko_throw_cached(env, g_neko_throw_le);\n");
        sb.append("}\n\n");
        sb.append("static void neko_raise_null_pointer_exception(void *thread) {\n");
        sb.append("    JNIEnv *env = neko_current_env();\n");
        sb.append("    (void)thread;\n");
        sb.append("    (void)neko_throw_cached(env, g_neko_throw_npe);\n");
        sb.append("}\n\n");
        sb.append("static jobject neko_wave2_unsafe(JNIEnv *env) {\n");
        sb.append("    const char *unsafe_name = g_neko_vm_layout.java_spec_version >= 9 ? \"jdk/internal/misc/Unsafe\" : \"sun/misc/Unsafe\";\n");
        sb.append("    if (env == NULL) return NULL;\n");
        sb.append("    if (g_neko_wave2_unsafe_singleton != NULL) return g_neko_wave2_unsafe_singleton;\n");
        sb.append("    if (g_neko_wave2_unsafe_cls == NULL) {\n");
        sb.append("        jclass local = neko_find_class(env, unsafe_name);\n");
        sb.append("        if (local == NULL) return NULL;\n");
        sb.append("        g_neko_wave2_unsafe_cls = (jclass)neko_new_global_ref(env, local);\n");
        sb.append("        neko_delete_local_ref(env, local);\n");
        sb.append("    }\n");
        sb.append("    if (g_neko_wave2_unsafe_cls == NULL) return NULL;\n");
        sb.append("    {\n");
        sb.append("        jfieldID fid = neko_get_static_field_id(env, g_neko_wave2_unsafe_cls, \"theUnsafe\", g_neko_vm_layout.java_spec_version >= 9 ? \"Ljdk/internal/misc/Unsafe;\" : \"Lsun/misc/Unsafe;\");\n");
        sb.append("        jobject local;\n");
        sb.append("        if (fid == NULL) return NULL;\n");
        sb.append("        local = neko_get_static_object_field(env, g_neko_wave2_unsafe_cls, fid);\n");
        sb.append("        if (local == NULL) return NULL;\n");
        sb.append("        g_neko_wave2_unsafe_singleton = neko_new_global_ref(env, local);\n");
        sb.append("        neko_delete_local_ref(env, local);\n");
        sb.append("    }\n");
        sb.append("    return g_neko_wave2_unsafe_singleton;\n");
        sb.append("}\n\n");
        sb.append("static jobject neko_wave2_declared_field(JNIEnv *env, jclass owner, const char *field_name) {\n");
        sb.append("    jvalue args[1];\n");
        sb.append("    if (env == NULL || owner == NULL || field_name == NULL) return NULL;\n");
        sb.append("    neko_bind_class_slot(env, &g_neko_wave2_class_cls, \"java/lang/Class\");\n");
        sb.append("    neko_bind_method_slot(env, &g_neko_wave2_class_get_declared_field, g_neko_wave2_class_cls, \"java/lang/Class\", \"getDeclaredField\", \"(Ljava/lang/String;)Ljava/lang/reflect/Field;\", JNI_FALSE);\n");
        sb.append("    if (g_neko_wave2_class_get_declared_field == NULL) return NULL;\n");
        sb.append("    args[0].l = neko_new_string_utf(env, field_name);\n");
        sb.append("    return neko_call_object_method_a(env, owner, g_neko_wave2_class_get_declared_field, args);\n");
        sb.append("}\n\n");
        sb.append("static ptrdiff_t neko_wave2_reflected_field_offset(JNIEnv *env, jobject reflected_field, jboolean is_static) {\n");
        sb.append("    jobject unsafe = neko_wave2_unsafe(env);\n");
        sb.append("    jvalue args[1];\n");
        sb.append("    if (unsafe == NULL || reflected_field == NULL) return -1;\n");
        sb.append("    neko_bind_method_slot(env, &g_neko_wave2_unsafe_object_field_offset, g_neko_wave2_unsafe_cls, \"jdk/internal/misc/Unsafe\", \"objectFieldOffset\", \"(Ljava/lang/reflect/Field;)J\", JNI_FALSE);\n");
        sb.append("    neko_bind_method_slot(env, &g_neko_wave2_unsafe_static_field_offset, g_neko_wave2_unsafe_cls, \"jdk/internal/misc/Unsafe\", \"staticFieldOffset\", \"(Ljava/lang/reflect/Field;)J\", JNI_FALSE);\n");
        sb.append("    args[0].l = reflected_field;\n");
        sb.append("    return (ptrdiff_t)neko_call_long_method_a(env, unsafe, is_static ? g_neko_wave2_unsafe_static_field_offset : g_neko_wave2_unsafe_object_field_offset, args);\n");
        sb.append("}\n\n");
        sb.append("static ptrdiff_t neko_wave2_field_offset_by_name(JNIEnv *env, const char *owner_internal, const char *field_name, jboolean is_static) {\n");
        sb.append("    jclass owner = NULL;\n");
        sb.append("    jobject reflected = NULL;\n");
        sb.append("    ptrdiff_t offset = -1;\n");
        sb.append("    if (env == NULL || owner_internal == NULL || field_name == NULL) return -1;\n");
        sb.append("    owner = neko_find_class(env, owner_internal);\n");
        sb.append("    if (owner == NULL) goto cleanup;\n");
        sb.append("    reflected = neko_wave2_declared_field(env, owner, field_name);\n");
        sb.append("    if (reflected == NULL) goto cleanup;\n");
        sb.append("    offset = neko_wave2_reflected_field_offset(env, reflected, is_static);\n");
        sb.append("cleanup:\n");
        sb.append("    if (reflected != NULL) neko_delete_local_ref(env, reflected);\n");
        sb.append("    if (owner != NULL) neko_delete_local_ref(env, owner);\n");
        sb.append("    return offset;\n");
        sb.append("}\n\n");
        sb.append("static ptrdiff_t neko_wave2_object_field_offset_by_name(JNIEnv *env, const char *owner_internal, const char *field_name) {\n");
        sb.append("    return neko_wave2_field_offset_by_name(env, owner_internal, field_name, JNI_FALSE);\n");
        sb.append("}\n\n");
        sb.append("static ptrdiff_t neko_wave2_static_field_offset_by_name(JNIEnv *env, const char *owner_internal, const char *field_name) {\n");
        sb.append("    return neko_wave2_field_offset_by_name(env, owner_internal, field_name, JNI_TRUE);\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_wave2_can_precache_static_reference_owner(const char *owner_internal) {\n");
        sb.append("    if (owner_internal == NULL) return JNI_FALSE;\n");
        sb.append("    if (strncmp(owner_internal, \"java/\", 5u) == 0) return JNI_TRUE;\n");
        sb.append("    if (strncmp(owner_internal, \"javax/\", 6u) == 0) return JNI_TRUE;\n");
        sb.append("    if (strncmp(owner_internal, \"jdk/\", 4u) == 0) return JNI_TRUE;\n");
        sb.append("    if (strncmp(owner_internal, \"sun/\", 4u) == 0) return JNI_TRUE;\n");
        sb.append("    if (strncmp(owner_internal, \"com/sun/\", 8u) == 0) return JNI_TRUE;\n");
        sb.append("    return JNI_FALSE;\n");
        sb.append("}\n\n");
        sb.append("static void neko_wave2_cache_static_reference_field(JNIEnv *env, NekoManifestFieldSite *site) {\n");
        sb.append("    jclass owner = NULL;\n");
        sb.append("    jfieldID fid = NULL;\n");
        sb.append("    jobject local = NULL;\n");
        sb.append("    if (env == NULL || site == NULL || !site->is_static || !site->is_reference || site->owner_internal == NULL || site->field_name == NULL || site->field_desc == NULL) return;\n");
        sb.append("    if (!neko_wave2_can_precache_static_reference_owner(site->owner_internal)) return;\n");
        sb.append("    owner = neko_find_class(env, site->owner_internal);\n");
        sb.append("    if (owner == NULL) { if (neko_exception_occurred(env) != NULL) neko_exception_clear(env); return; }\n");
        sb.append("    fid = neko_get_static_field_id(env, owner, site->field_name, site->field_desc);\n");
        sb.append("    if (fid == NULL) { if (neko_exception_occurred(env) != NULL) neko_exception_clear(env); goto cleanup; }\n");
        sb.append("    local = neko_get_static_object_field(env, owner, fid);\n");
        sb.append("    if (local == NULL) { if (neko_exception_occurred(env) != NULL) neko_exception_clear(env); goto cleanup; }\n");
        sb.append("    (void)neko_oop_from_jni_ref(local);\n");
        sb.append("cleanup:\n");
        sb.append("    if (local != NULL) neko_delete_local_ref(env, local);\n");
        sb.append("    if (owner != NULL) neko_delete_local_ref(env, owner);\n");
        sb.append("}\n\n");
        sb.append("static ptrdiff_t neko_wave2_array_metric(JNIEnv *env, const char *descriptor, jboolean want_base) {\n");
        sb.append("    jobject unsafe = neko_wave2_unsafe(env);\n");
        sb.append("    jclass array_class = NULL;\n");
        sb.append("    jvalue args[1];\n");
        sb.append("    ptrdiff_t value = -1;\n");
        sb.append("    if (env == NULL || descriptor == NULL || unsafe == NULL) return -1;\n");
        sb.append("    neko_bind_method_slot(env, &g_neko_wave2_unsafe_array_base_offset, g_neko_wave2_unsafe_cls, \"jdk/internal/misc/Unsafe\", \"arrayBaseOffset\", \"(Ljava/lang/Class;)I\", JNI_FALSE);\n");
        sb.append("    neko_bind_method_slot(env, &g_neko_wave2_unsafe_array_index_scale, g_neko_wave2_unsafe_cls, \"jdk/internal/misc/Unsafe\", \"arrayIndexScale\", \"(Ljava/lang/Class;)I\", JNI_FALSE);\n");
        sb.append("    array_class = neko_class_for_descriptor(env, descriptor);\n");
        sb.append("    if (array_class == NULL) goto cleanup;\n");
        sb.append("    args[0].l = array_class;\n");
        sb.append("    value = want_base ? (ptrdiff_t)neko_call_int_method_a(env, unsafe, g_neko_wave2_unsafe_array_base_offset, args) : (ptrdiff_t)neko_call_int_method_a(env, unsafe, g_neko_wave2_unsafe_array_index_scale, args);\n");
        sb.append("cleanup:\n");
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
        sb.append("typedef void (*neko_jvmci_write_barrier_pre_fn)(void *thread, void *obj);\n");
        sb.append("typedef void (*neko_jvmci_write_barrier_post_fn)(void *thread, void *card_addr);\n\n");
        sb.append("static inline void neko_g1_enqueue_satb(void *obj) {\n");
        sb.append("    void *thread;\n");
        sb.append("    uint8_t *active_addr;\n");
        sb.append("    size_t *index_addr;\n");
        sb.append("    void ***buffer_addr;\n");
        sb.append("    size_t index;\n");
        sb.append("    void **buffer;\n");
        sb.append("    if (obj == NULL) return;\n");
        sb.append("    thread = neko_get_current_thread();\n");
        sb.append("    if (thread == NULL) return;\n");
        sb.append("    if (g_neko_vm_layout.off_g1_satb_mark_queue_active >= 0 && g_neko_vm_layout.off_g1_satb_mark_queue_index >= 0 && g_neko_vm_layout.off_g1_satb_mark_queue_buffer >= 0) {\n");
        sb.append("        active_addr = (uint8_t*)((uint8_t*)thread + g_neko_vm_layout.off_g1_satb_mark_queue_active);\n");
        sb.append("        if (__atomic_load_n(active_addr, __ATOMIC_ACQUIRE) == 0u) return;\n");
        sb.append("        index_addr = (size_t*)((uint8_t*)thread + g_neko_vm_layout.off_g1_satb_mark_queue_index);\n");
        sb.append("        buffer_addr = (void***)((uint8_t*)thread + g_neko_vm_layout.off_g1_satb_mark_queue_buffer);\n");
        sb.append("        index = __atomic_load_n(index_addr, __ATOMIC_ACQUIRE);\n");
        sb.append("        buffer = __atomic_load_n(buffer_addr, __ATOMIC_ACQUIRE);\n");
        sb.append("        if (index >= sizeof(void*) && buffer != NULL) {\n");
        sb.append("            index -= sizeof(void*);\n");
        sb.append("            __atomic_store_n((void**)((uint8_t*)buffer + index), obj, __ATOMIC_RELEASE);\n");
        sb.append("            __atomic_store_n(index_addr, index, __ATOMIC_RELEASE);\n");
        sb.append("            return;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    if (g_neko_vm_layout.jvmci_write_barrier_pre_fn != 0u) {\n");
        sb.append("        ((neko_jvmci_write_barrier_pre_fn)g_neko_vm_layout.jvmci_write_barrier_pre_fn)(thread, obj);\n");
        sb.append("    }\n");
        sb.append("}\n\n");
        sb.append("static inline void neko_g1_enqueue_dirty_card(void *card_addr) {\n");
        sb.append("    void *thread;\n");
        sb.append("    size_t *index_addr;\n");
        sb.append("    void ***buffer_addr;\n");
        sb.append("    size_t index;\n");
        sb.append("    void **buffer;\n");
        sb.append("    if (card_addr == NULL) return;\n");
        sb.append("    thread = neko_get_current_thread();\n");
        sb.append("    if (thread == NULL) return;\n");
        sb.append("    if (g_neko_vm_layout.off_g1_dirty_card_queue_index >= 0 && g_neko_vm_layout.off_g1_dirty_card_queue_buffer >= 0) {\n");
        sb.append("        index_addr = (size_t*)((uint8_t*)thread + g_neko_vm_layout.off_g1_dirty_card_queue_index);\n");
        sb.append("        buffer_addr = (void***)((uint8_t*)thread + g_neko_vm_layout.off_g1_dirty_card_queue_buffer);\n");
        sb.append("        index = __atomic_load_n(index_addr, __ATOMIC_ACQUIRE);\n");
        sb.append("        buffer = __atomic_load_n(buffer_addr, __ATOMIC_ACQUIRE);\n");
        sb.append("        if (index >= sizeof(void*) && buffer != NULL) {\n");
        sb.append("            index -= sizeof(void*);\n");
        sb.append("            __atomic_store_n((void**)((uint8_t*)buffer + index), card_addr, __ATOMIC_RELEASE);\n");
        sb.append("            __atomic_store_n(index_addr, index, __ATOMIC_RELEASE);\n");
        sb.append("            return;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    if (g_neko_vm_layout.jvmci_write_barrier_post_fn != 0u) {\n");
        sb.append("        ((neko_jvmci_write_barrier_post_fn)g_neko_vm_layout.jvmci_write_barrier_post_fn)(thread, card_addr);\n");
        sb.append("    }\n");
        sb.append("}\n\n");
        sb.append("static inline void neko_oop_store_pre_barrier(void *old_value) {\n");
        sb.append("    if (old_value == NULL) return;\n");
        sb.append("    if (!neko_plausible_oop_value(old_value)) return;\n");
        sb.append("    neko_g1_enqueue_satb(old_value);\n");
        sb.append("}\n\n");
        sb.append("static inline void neko_oop_store_post_barrier(void *base, ptrdiff_t offset, void *value) {\n");
        sb.append("    volatile uint8_t *card;\n");
        sb.append("    uintptr_t field_addr;\n");
        sb.append("    uint8_t card_value;\n");
        sb.append("    if (base == NULL || offset < 0 || value == NULL) return;\n");
        sb.append("    if (g_neko_vm_layout.card_table_byte_map_base == 0u || g_neko_vm_layout.card_table_card_shift < 0) return;\n");
        sb.append("    field_addr = (uintptr_t)((uint8_t*)base + offset);\n");
        sb.append("    if (g_neko_vm_layout.g1_heap_region_shift > 0 && ((field_addr ^ ((uintptr_t)value)) >> g_neko_vm_layout.g1_heap_region_shift) == 0u) return;\n");
        sb.append("    card = (volatile uint8_t*)(g_neko_vm_layout.card_table_byte_map_base + (field_addr >> g_neko_vm_layout.card_table_card_shift));\n");
        sb.append("    card_value = __atomic_load_n(card, __ATOMIC_ACQUIRE);\n");
        sb.append("    if (card_value == (uint8_t)g_neko_vm_layout.g1_young_card) return;\n");
        sb.append("    __sync_synchronize();\n");
        sb.append("    card_value = __atomic_load_n(card, __ATOMIC_ACQUIRE);\n");
        sb.append("    if (card_value == (uint8_t)g_neko_vm_layout.card_table_dirty_card) return;\n");
        sb.append("    __atomic_store_n(card, (uint8_t)g_neko_vm_layout.card_table_dirty_card, __ATOMIC_RELEASE);\n");
        sb.append("    neko_g1_enqueue_dirty_card((void*)card);\n");
        sb.append("}\n\n");
        sb.append("static inline void neko_store_heap_oop_at(void *base, ptrdiff_t offset, void *value, jboolean is_volatile) {\n");
        sb.append("    void *old_value;\n");
        sb.append("    if (base == NULL || offset < 0) return;\n");
        sb.append("    if (value != NULL) value = neko_oop_for_direct((jobject)value);\n");
        sb.append("    if (value != NULL && !neko_plausible_oop_value(value)) value = NULL;\n");
        sb.append("    old_value = neko_load_heap_oop_at(base, offset, is_volatile);\n");
        sb.append("    neko_oop_store_pre_barrier(old_value);\n");
        sb.append("    if (neko_uses_compressed_oops()) {\n");
        sb.append("        u4 narrow = neko_encode_heap_oop(value);\n");
        sb.append("        if (is_volatile) __atomic_store_n((u4*)((uint8_t*)base + offset), narrow, __ATOMIC_SEQ_CST); else *(u4*)((uint8_t*)base + offset) = narrow;\n");
        sb.append("        neko_oop_store_post_barrier(base, offset, value);\n");
        sb.append("        return;\n");
        sb.append("    }\n");
        sb.append("    if (is_volatile) __atomic_store_n((void**)((uint8_t*)base + offset), value, __ATOMIC_SEQ_CST); else *(void**)((uint8_t*)base + offset) = value;\n");
        sb.append("    neko_oop_store_post_barrier(base, offset, value);\n");
        sb.append("}\n\n");
        sb.append("typedef struct Klass Klass;\n");
        sb.append("typedef void* oop;\n");
        sb.append("static inline Klass* neko_site_owner_klass(const NekoManifestFieldSite *site) {\n");
        sb.append("    return site == NULL ? NULL : (Klass*)__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);\n");
        sb.append("}\n\n");
        sb.append("static inline void *neko_site_static_base(const NekoManifestFieldSite *site) {\n");
        sb.append("    Klass *owner_klass = site == NULL ? NULL : neko_site_owner_klass(site);\n");
        sb.append("    return neko_resolve_mirror_oop_from_klass(&g_neko_vm_layout, owner_klass);\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_field_site_access_flags(const NekoManifestFieldSite *site, uint32_t *access_flags_out) {\n");
        sb.append("    Klass *owner_klass = site == NULL ? NULL : neko_site_owner_klass(site);\n");
        sb.append("    uint32_t name_len;\n");
        sb.append("    uint32_t desc_len;\n");
        sb.append("    void *cp;\n");
        sb.append("    if (access_flags_out != NULL) *access_flags_out = 0u;\n");
        sb.append("    if (site == NULL || owner_klass == NULL || site->field_name == NULL || site->field_desc == NULL || access_flags_out == NULL) return JNI_FALSE;\n");
        sb.append("    name_len = (uint32_t)strlen(site->field_name);\n");
        sb.append("    desc_len = (uint32_t)strlen(site->field_desc);\n");
        sb.append("    if (g_neko_vm_layout.off_instance_klass_constants < 0) return JNI_FALSE;\n");
        sb.append("    cp = *(void**)((uint8_t*)owner_klass + g_neko_vm_layout.off_instance_klass_constants);\n");
        sb.append("    if (cp == NULL) return JNI_FALSE;\n");
        sb.append("    if (g_neko_vm_layout.off_instance_klass_fieldinfo_stream >= 0) {\n");
        sb.append("        void *fis = *(void**)((uint8_t*)owner_klass + g_neko_vm_layout.off_instance_klass_fieldinfo_stream);\n");
        sb.append("        int fis_len = fis == NULL ? 0 : *(const int*)((const uint8_t*)fis + 0);\n");
        sb.append("        int p = 0;\n");
        sb.append("        const uint8_t *fis_data = fis == NULL ? NULL : (const uint8_t*)fis + sizeof(int);\n");
        sb.append("        uint32_t num_java_fields = 0u;\n");
        sb.append("        uint32_t ignored = 0u;\n");
        sb.append("        if (fis_data == NULL || fis_len <= 0) return JNI_FALSE;\n");
        sb.append("        if (!neko_read_u5(fis_data, fis_len, &p, &num_java_fields)) return JNI_FALSE;\n");
        sb.append("        if (!neko_read_u5(fis_data, fis_len, &p, &ignored)) return JNI_FALSE;\n");
        sb.append("        for (uint32_t i = 0; i < num_java_fields; i++) {\n");
        sb.append("            uint32_t name_index;\n");
        sb.append("            uint32_t signature_index;\n");
        sb.append("            uint32_t offset;\n");
        sb.append("            uint32_t access_flags;\n");
        sb.append("            uint32_t field_flags;\n");
        sb.append("            uint32_t unused;\n");
        sb.append("            jboolean is_static;\n");
        sb.append("            if (!neko_read_u5(fis_data, fis_len, &p, &name_index)) return JNI_FALSE;\n");
        sb.append("            if (!neko_read_u5(fis_data, fis_len, &p, &signature_index)) return JNI_FALSE;\n");
        sb.append("            if (!neko_read_u5(fis_data, fis_len, &p, &offset)) return JNI_FALSE;\n");
        sb.append("            if (!neko_read_u5(fis_data, fis_len, &p, &access_flags)) return JNI_FALSE;\n");
        sb.append("            if (!neko_read_u5(fis_data, fis_len, &p, &field_flags)) return JNI_FALSE;\n");
        sb.append("            if ((field_flags & 1u) != 0u && !neko_read_u5(fis_data, fis_len, &p, &unused)) return JNI_FALSE;\n");
        sb.append("            if ((field_flags & 4u) != 0u && !neko_read_u5(fis_data, fis_len, &p, &unused)) return JNI_FALSE;\n");
        sb.append("            if ((field_flags & 16u) != 0u && !neko_read_u5(fis_data, fis_len, &p, &unused)) return JNI_FALSE;\n");
        sb.append("            is_static = (access_flags & JVM_ACC_STATIC) != 0u ? JNI_TRUE : JNI_FALSE;\n");
        sb.append("            if (is_static != (site->is_static ? JNI_TRUE : JNI_FALSE)) continue;\n");
        sb.append("            if (!neko_cp_utf8_matches(cp, (uint16_t)name_index, site->field_name, name_len)) continue;\n");
        sb.append("            if (!neko_cp_utf8_matches(cp, (uint16_t)signature_index, site->field_desc, desc_len)) continue;\n");
        sb.append("            *access_flags_out = access_flags;\n");
        sb.append("            (void)offset;\n");
        sb.append("            return JNI_TRUE;\n");
        sb.append("        }\n");
        sb.append("        return JNI_FALSE;\n");
        sb.append("    }\n");
        sb.append("    if (g_neko_vm_layout.off_instance_klass_fields >= 0 && g_neko_vm_layout.off_instance_klass_java_fields_count >= 0) {\n");
        sb.append("        void *fields = *(void**)((uint8_t*)owner_klass + g_neko_vm_layout.off_instance_klass_fields);\n");
        sb.append("        uint32_t java_fields_count = (uint32_t)*(const uint16_t*)((const uint8_t*)owner_klass + g_neko_vm_layout.off_instance_klass_java_fields_count);\n");
        sb.append("        int fields_len = fields == NULL ? 0 : *(const int*)((const uint8_t*)fields + 0);\n");
        sb.append("        const uint16_t *fields_data = fields == NULL ? NULL : (const uint16_t*)((const uint8_t*)fields + sizeof(int));\n");
        sb.append("        if (fields_data == NULL || fields_len < 0 || java_fields_count > (uint32_t)(fields_len / 6)) return JNI_FALSE;\n");
        sb.append("        for (uint32_t i = 0; i < java_fields_count; i++) {\n");
        sb.append("            const uint16_t *tuple = fields_data + (i * 6u);\n");
        sb.append("            uint32_t access_flags = tuple[0];\n");
        sb.append("            jboolean is_static = (access_flags & JVM_ACC_STATIC) != 0u ? JNI_TRUE : JNI_FALSE;\n");
        sb.append("            if (is_static != (site->is_static ? JNI_TRUE : JNI_FALSE)) continue;\n");
        sb.append("            if (!neko_cp_utf8_matches(cp, tuple[1], site->field_name, name_len)) continue;\n");
        sb.append("            if (!neko_cp_utf8_matches(cp, tuple[2], site->field_desc, desc_len)) continue;\n");
        sb.append("            *access_flags_out = access_flags;\n");
        sb.append("            return JNI_TRUE;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    return JNI_FALSE;\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_resolve_field_site_with_klass(JNIEnv *env, void *thread, NekoManifestFieldSite *site, Klass *owner_klass) {\n");
        sb.append("    int32_t resolved_offset;\n");
        sb.append("    ptrdiff_t offset;\n");
        sb.append("    uint32_t access_flags = 0u;\n");
        sb.append("    if (env == NULL || site == NULL || owner_klass == NULL || site->field_name == NULL || site->field_desc == NULL) return JNI_FALSE;\n");
        sb.append("    if (site->is_static) {\n");
        sb.append("        ptrdiff_t static_offset = neko_wave2_static_field_offset_by_name(env, site->owner_internal, site->field_name);\n");
        sb.append("        if (static_offset >= 0) {\n");
        sb.append("            resolved_offset = (int32_t)static_offset;\n");
        sb.append("        } else if (!neko_resolve_field_offset(owner_klass, site->field_name, (uint32_t)strlen(site->field_name), site->field_desc, (uint32_t)strlen(site->field_desc), true, &resolved_offset)) {\n");
        sb.append("            __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE);\n");
        sb.append("            neko_wave2_capture_pending(env, thread, \"java/lang/NoSuchFieldError\", site->field_name);\n");
        sb.append("            return JNI_FALSE;\n");
        sb.append("        }\n");
        sb.append("    } else if (!neko_resolve_field_offset(owner_klass, site->field_name, (uint32_t)strlen(site->field_name), site->field_desc, (uint32_t)strlen(site->field_desc), false, &resolved_offset)) {\n");
        sb.append("        __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE);\n");
        sb.append("        neko_wave2_capture_pending(env, thread, \"java/lang/NoSuchFieldError\", site->field_name);\n");
        sb.append("        return JNI_FALSE;\n");
        sb.append("    }\n");
        sb.append("    offset = (ptrdiff_t)resolved_offset;\n");
        sb.append("    if (site->is_static) {\n");
        sb.append("        site->field_offset_cookie = offset;\n");
        sb.append("        neko_wave2_cache_static_reference_field(env, site);\n");
        sb.append("        neko_native_trace_log(2, \"sfb %s.%s:%s klass=%p mir=%p off=%td\", site->owner_internal, site->field_name, site->field_desc, owner_klass, neko_site_static_base(site), offset);\n");
        sb.append("    }\n");
        sb.append("    site->is_volatile = (neko_field_site_access_flags(site, &access_flags) && ((access_flags & 0x0040u) != 0u)) ? 1u : 0u;\n");
        sb.append("    __atomic_store_n(&site->resolved_offset, offset, __ATOMIC_RELEASE);\n");
        sb.append("    neko_native_debug_log(\"fsr %s.%s:%s off=%td v=%u\", site->owner_internal, site->field_name, site->field_desc, offset, (unsigned)site->is_volatile);\n");
        sb.append("    return JNI_TRUE;\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_ensure_field_site_resolved(void *thread, NekoManifestFieldSite *site) {\n");
        sb.append("    ptrdiff_t current = site == NULL ? NEKO_FIELD_SITE_FAILED : __atomic_load_n(&site->resolved_offset, __ATOMIC_ACQUIRE);\n");
        sb.append("    JNIEnv *env;\n");
        sb.append("    Klass *owner_klass;\n");
        sb.append("    size_t owner_len;\n");
        sb.append("    if (site == NULL) return JNI_FALSE;\n");
        sb.append("    neko_maybe_rescan_cld_liveness();\n");
        sb.append("    if (current >= 0) return JNI_TRUE;\n");
        sb.append("    if (current == NEKO_FIELD_SITE_FAILED) {\n");
        sb.append("        (void)thread;\n");
        sb.append("        (void)neko_throw_cached(neko_current_env(), g_neko_throw_le);\n");
        sb.append("        return JNI_FALSE;\n");
        sb.append("    }\n");
        sb.append("    env = neko_current_env();\n");
        sb.append("    if (env == NULL) return JNI_FALSE;\n");
        sb.append("    owner_klass = neko_site_owner_klass(site);\n");
        sb.append("    if (owner_klass == NULL) {\n");
        sb.append("        if (site->owner_internal == NULL) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, \"java/lang/NoClassDefFoundError\", \"<null>\"); return JNI_FALSE; }\n");
        sb.append("        owner_len = strlen(site->owner_internal);\n");
        sb.append("        if (owner_len >= 65536u) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, \"java/lang/NoClassDefFoundError\", site->owner_internal); return JNI_FALSE; }\n");
        sb.append("        owner_klass = neko_find_klass_by_name_in_cld_graph(site->owner_internal, (uint16_t)owner_len);\n");
        sb.append("        if (owner_klass == NULL) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, \"java/lang/NoClassDefFoundError\", site->owner_internal); return JNI_FALSE; }\n");
        sb.append("        __atomic_store_n(&site->cached_klass, owner_klass, __ATOMIC_RELEASE);\n");
        sb.append("    }\n");
        sb.append("    return neko_resolve_field_site_with_klass(env, thread, site, owner_klass);\n");
        sb.append("}\n\n");
        sb.append("static void neko_resolve_prepared_class_field_sites(JNIEnv *env, jclass klass, const char *owner_internal, void *owner_klass) {\n");
        sb.append("    void *thread = neko_get_current_thread();\n");
        sb.append("    jboolean cached_klass_logged = JNI_FALSE;\n");
        sb.append("    if (env == NULL || klass == NULL || owner_internal == NULL) return;\n");
        sb.append("    (void)klass;\n");
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
        sb.append("            if (__atomic_load_n(&site->resolved_offset, __ATOMIC_ACQUIRE) == NEKO_FIELD_SITE_UNRESOLVED) (void)neko_resolve_field_site_with_klass(env, thread, site, (Klass*)owner_klass);\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n\n");
        sb.append("static uint32_t neko_count_cached_static_field_bases(void) {\n");
        sb.append("    uint32_t count = 0u;\n");
        sb.append("    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {\n");
        sb.append("        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];\n");
        sb.append("        for (uint32_t site_index = 0; site_index < method->field_site_count; site_index++) {\n");
        sb.append("            NekoManifestFieldSite *site = &method->field_sites[site_index];\n");
        sb.append("            if (site->is_static && neko_site_static_base(site) != NULL) count++;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    return count;\n");
        sb.append("}\n\n");
        sb.append("__attribute__((visibility(\"default\"))) void* neko_field_site_static_base(void *thread, NekoManifestFieldSite *site) {\n");
        sb.append("    oop mirror;\n");
        sb.append("    JNIEnv *env;\n");
        sb.append("    if (!neko_ensure_field_site_resolved(thread, site)) return NULL;\n");
        sb.append("    env = neko_current_env();\n");
        sb.append("    mirror = neko_site_static_base(site);\n");
        sb.append("    if (mirror == NULL) {\n");
        sb.append("        if (env != NULL && neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStateException\", \"failed to resolve static field mirror\");\n");
        sb.append("        return NULL;\n");
        sb.append("    }\n");
        sb.append("    neko_native_debug_log(\"sfm %s.%s:%s klass=%p mir=%p off=%td\", site->owner_internal, site->field_name, site->field_desc, neko_site_owner_klass(site), mirror, site->field_offset_cookie);\n");
        sb.append("    neko_native_trace_log(2, \"sfr %s.%s:%s klass=%p mir=%p off=%td addr=%p\", site->owner_internal, site->field_name, site->field_desc, neko_site_owner_klass(site), mirror, site->field_offset_cookie, (void*)((char*)mirror + site->field_offset_cookie));\n");
        sb.append("    return mirror;\n");
        sb.append("}\n\n");
        sb.append("static inline void *neko_static_mirror(const NekoManifestFieldSite *site) {\n");
        sb.append("    return neko_site_static_base(site);\n");
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
        sb.append("static inline void neko_field_write_oop(void *base, const NekoManifestFieldSite *site, void *value) {\n");
        sb.append("    neko_store_heap_oop_at(base, site == NULL ? -1 : site->resolved_offset, value, site != NULL && site->is_volatile ? JNI_TRUE : JNI_FALSE);\n");
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

static void* neko_create_ldc_string_oop(NekoManifestLdcSite* site, uint32_t *coder_out, uint32_t *char_length_out, uint8_t **key_bytes_out, uint32_t *key_payload_bytes_out) {
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
    if (coder_out != NULL) *coder_out = 0u;
    if (char_length_out != NULL) *char_length_out = 0u;
    if (key_bytes_out != NULL) *key_bytes_out = NULL;
    if (key_payload_bytes_out != NULL) *key_payload_bytes_out = 0u;
    if (site == NULL) return NULL;
    if (g_neko_vm_layout.klass_java_lang_String == NULL) return NULL;
    if (g_neko_vm_layout.off_string_value < 0) return NULL;
    if (!neko_decode_mutf8_to_utf16(site->raw_constant_utf8, site->raw_constant_utf8_len, &utf16, &utf16_len, &heap_alloc)) {
        return NULL;
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
        return NULL;
    }
    array_len = is_jdk8
        ? utf16_len
        : (coder == 0 ? utf16_len : utf16_len * 2);
    inner_array = neko_rt_try_alloc_array_fast_nosafepoint(array_klass, array_len);
    if (inner_array == NULL) {
        if (heap_alloc) free(utf16);
        return NULL;
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
            array_base[2 * i] = (uint8_t)(utf16[i] & 0xFFu);
            array_base[2 * i + 1] = (uint8_t)(utf16[i] >> 8);
        }
    }
    lh_str = *(uint32_t*)((uint8_t*)g_neko_vm_layout.klass_java_lang_String + g_neko_vm_layout.off_klass_layout_helper);
    string_size = neko_lh_instance_size(lh_str);
    string_oop = neko_rt_try_alloc_instance_fast_nosafepoint((Klass*)g_neko_vm_layout.klass_java_lang_String, string_size);
    if (string_oop == NULL) {
        if (heap_alloc) free(utf16);
        return NULL;
    }
    neko_store_heap_oop_at_unpublished(string_oop, g_neko_vm_layout.off_string_value, inner_array);
    if (!is_jdk8) {
        *(uint8_t*)((uint8_t*)string_oop + g_neko_vm_layout.off_string_coder) = (uint8_t)coder;
    }
    if (g_neko_vm_layout.off_string_hash >= 0) {
        *(int32_t*)((uint8_t*)string_oop + g_neko_vm_layout.off_string_hash) = 0;
    }
    key_payload_bytes = is_jdk8
        ? (uint32_t)(utf16_len * 2)
        : (coder == 0 ? (uint32_t)utf16_len : (uint32_t)utf16_len * 2u);
    if ((size_t)key_payload_bytes > sizeof(key_stackbuf)) {
        key_heap = (uint8_t*)malloc(key_payload_bytes);
        if (key_heap == NULL) {
            if (heap_alloc) free(utf16);
            return NULL;
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
    if (coder_out != NULL) *coder_out = (uint32_t)coder;
    if (char_length_out != NULL) *char_length_out = (uint32_t)utf16_len;
    if (key_payload_bytes_out != NULL) *key_payload_bytes_out = key_payload_bytes;
    if (key_bytes_out != NULL) {
        if (key_payload_bytes == 0u) {
            *key_bytes_out = NULL;
        } else {
            uint8_t *stored = (uint8_t*)malloc(key_payload_bytes);
            if (stored == NULL) {
                if (key_heap) free(key_heap);
                if (heap_alloc) free(utf16);
                return NULL;
            }
            memcpy(stored, key_bytes, key_payload_bytes);
            *key_bytes_out = stored;
        }
    }
    if (key_heap) free(key_heap);
    if (heap_alloc) free(utf16);
    return string_oop;
}

static void neko_resolve_ldc_string(NekoManifestLdcSite* site) {
    uint32_t coder = 0u;
    uint32_t char_length = 0u;
    uint8_t *key_bytes = NULL;
    uint32_t key_payload_bytes = 0u;
    uint32_t h;
    uint32_t bucket_idx;
    NekoStringInternEntry* existing;
    uint32_t slot;
    NekoStringInternEntry* entry;
    uint8_t* stored_payload;
    size_t stored_payload_size;
    void *string_oop;
    if (site == NULL) return;
    if (site->resolved_cache_handle != NULL) {
        return;
    }
    string_oop = neko_create_ldc_string_oop(site, &coder, &char_length, &key_bytes, &key_payload_bytes);
    if (string_oop == NULL) {
        return;
    }
    h = neko_string_intern_hash((uint32_t)coder, char_length, key_bytes, key_payload_bytes);
    bucket_idx = h % NEKO_STRING_INTERN_BUCKET_COUNT;
    existing = g_neko_string_intern_buckets[bucket_idx];
    while (existing != NULL) {
        if (existing->coder == (uint32_t)coder
            && existing->char_length == char_length
            && existing->payload_length == key_payload_bytes
            && (key_payload_bytes == 0u || memcmp(existing->payload, key_bytes, key_payload_bytes) == 0)) {
            if (key_bytes != NULL) free(key_bytes);
            site->resolved_cache_handle = existing;
            return;
        }
        existing = existing->next;
    }
    if (g_neko_string_intern_filled >= NEKO_STRING_INTERN_SLOT_COUNT) {
        if (key_bytes != NULL) free(key_bytes);
        return;
    }
    stored_payload_size = key_payload_bytes == 0u ? 1u : (size_t)key_payload_bytes;
    stored_payload = (uint8_t*)malloc(stored_payload_size);
    if (stored_payload == NULL) {
        if (key_bytes != NULL) free(key_bytes);
        return;
    }
    if (key_payload_bytes != 0u) {
        memcpy(stored_payload, key_bytes, key_payload_bytes);
    }
    slot = g_neko_string_intern_filled;
    entry = &g_neko_string_intern_entries[slot];
    entry->coder = (uint32_t)coder;
    entry->char_length = char_length;
    entry->payload_length = key_payload_bytes;
    entry->slot_index = slot;
    entry->payload = stored_payload;
    entry->root_cell = NULL;
    entry->next = g_neko_string_intern_buckets[bucket_idx];
    g_neko_string_intern_buckets[bucket_idx] = entry;
    g_neko_string_intern_filled = slot + 1u;
    if (entry->root_cell != NULL) {
        neko_store_oop_to_cell(entry->root_cell, string_oop);
    }
    site->resolved_cache_handle = entry;
    if (key_bytes != NULL) free(key_bytes);
}

""");
        sb.append("static inline Klass* neko_ldc_site_owner_klass(const NekoManifestLdcSite *site) {\n");
        sb.append("    return site == NULL ? NULL : (Klass*)__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_ldc_site_signature_equals(const NekoManifestLdcSite *site, const char *signature) {\n");
        sb.append("    size_t signature_len;\n");
        sb.append("    if (site == NULL || signature == NULL || site->kind != NEKO_LDC_KIND_CLASS || site->raw_constant_utf8 == NULL) return JNI_FALSE;\n");
        sb.append("    signature_len = strlen(signature);\n");
        sb.append("    if (signature_len != site->raw_constant_utf8_len) return JNI_FALSE;\n");
        sb.append("    return memcmp(site->raw_constant_utf8, signature, signature_len) == 0 ? JNI_TRUE : JNI_FALSE;\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_ldc_site_matches_loaded_class(JNIEnv *env, NekoManifestLdcSite *site, jclass candidate, const char *signature) {\n");
        sb.append("    (void)env;\n");
        sb.append("    (void)candidate;\n");
        sb.append("    return neko_ldc_site_signature_equals(site, signature);\n");
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
        sb.append("    char *binary_name = NULL;\n");
        sb.append("    jclass klass_obj = NULL;\n");
        sb.append("    void *resolved_klass = NULL;\n");
        sb.append("    if (site == NULL || site->kind != NEKO_LDC_KIND_CLASS) return JNI_FALSE;\n");
        sb.append("    cached_klass = __atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);\n");
        sb.append("    if (cached_klass != NULL) return JNI_TRUE;\n");
        sb.append("    env = neko_current_env();\n");
        sb.append("    if (env == NULL) return JNI_FALSE;\n");
        sb.append("    binary_name = neko_ldc_site_binary_name(site);\n");
        sb.append("    if (binary_name == NULL) return JNI_FALSE;\n");
        sb.append("    resolved_klass = neko_find_klass_by_name_in_cld_graph(binary_name, (uint16_t)strlen(binary_name));\n");
        sb.append("    if (resolved_klass == NULL) klass_obj = neko_load_class_noinit(env, binary_name);\n");
        sb.append("    free(binary_name);\n");
        sb.append("    if (klass_obj != NULL) { (void)neko_oop_from_jni_ref((jobject)klass_obj); resolved_klass = neko_class_klass_pointer(klass_obj); }\n");
        sb.append("    if (resolved_klass != NULL) { __atomic_store_n(&site->cached_klass, resolved_klass, __ATOMIC_RELEASE); if (klass_obj != NULL) neko_delete_local_ref(env, klass_obj); return JNI_TRUE; }\n");
        sb.append("    if (klass_obj != NULL) neko_delete_local_ref(env, klass_obj);\n");
        sb.append("    {\n");
        sb.append("        char *message = neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);\n");
        sb.append("        if (message != NULL) free(message);\n");
        sb.append("        (void)neko_throw_cached(env, g_neko_throw_le);\n");
        sb.append("    }\n");
        sb.append("    return JNI_FALSE;\n");
        sb.append("}\n\n");
        sb.append("__attribute__((visibility(\"default\"))) void* neko_ldc_class_site_oop(void *thread, NekoManifestLdcSite *site) {\n");
        sb.append("    neko_maybe_rescan_cld_liveness();\n");
        sb.append("    if (!neko_ensure_ldc_class_site_resolved(thread, site)) return NULL;\n");
        sb.append("    return neko_rt_mirror_from_klass_nosafepoint(neko_ldc_site_owner_klass(site));\n");
        sb.append("}\n\n");
        sb.append("static jobject neko_ldc_cache_global(JNIEnv *env, NekoManifestLdcSite *site, jobject local) {\n");
        sb.append("    jobject global_ref;\n");
        sb.append("    void *cached;\n");
        sb.append("    if (env == NULL || site == NULL || local == NULL) return NULL;\n");
        sb.append("    cached = __atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE);\n");
        sb.append("    if (cached != NULL) return (jobject)cached;\n");
        sb.append("    global_ref = neko_new_global_ref(env, local);\n");
        sb.append("    if (global_ref == NULL) { (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }\n");
        sb.append("    cached = NULL;\n");
        sb.append("    if (__atomic_compare_exchange_n(&site->resolved_cache_handle, &cached, global_ref, JNI_FALSE, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return global_ref;\n");
        sb.append("    neko_delete_global_ref(env, global_ref);\n");
        sb.append("    return (jobject)cached;\n");
        sb.append("}\n\n");
        sb.append("static void* neko_ldc_method_type_site_oop(JNIEnv *env, NekoManifestLdcSite *site) {\n");
        sb.append("    char *descriptor;\n");
        sb.append("    jobject local;\n");
        sb.append("    if (site == NULL || site->kind != NEKO_LDC_KIND_METHOD_TYPE) { (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }\n");
        sb.append("    if (__atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE) != NULL) return site->resolved_cache_handle;\n");
        sb.append("    descriptor = neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);\n");
        sb.append("    if (descriptor == NULL) { (void)neko_throw_cached(env, g_neko_throw_oom); return NULL; }\n");
        sb.append("    local = neko_method_type_from_descriptor(env, descriptor);\n");
        sb.append("    free(descriptor);\n");
        sb.append("    if (local == NULL) { if (neko_pending_exception(neko_get_current_thread()) == NULL) (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }\n");
        sb.append("    return neko_ldc_cache_global(env, site, local);\n");
        sb.append("}\n\n");
        sb.append("static void* neko_ldc_method_handle_site_oop(JNIEnv *env, NekoManifestLdcSite *site) {\n");
        sb.append("    char *parts;\n");
        sb.append("    char *cursor;\n");
        sb.append("    char *next;\n");
        sb.append("    char *tag_text;\n");
        sb.append("    char *owner;\n");
        sb.append("    char *name;\n");
        sb.append("    char *desc;\n");
        sb.append("    char *interface_text;\n");
        sb.append("    jobject local;\n");
        sb.append("    if (site == NULL || site->kind != NEKO_LDC_KIND_METHOD_HANDLE) { (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }\n");
        sb.append("    if (__atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE) != NULL) return site->resolved_cache_handle;\n");
        sb.append("    parts = neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);\n");
        sb.append("    if (parts == NULL) { (void)neko_throw_cached(env, g_neko_throw_oom); return NULL; }\n");
        sb.append("    cursor = parts;\n");
        sb.append("    tag_text = cursor; next = strchr(cursor, '\\n'); if (next == NULL) { free(parts); (void)neko_throw_cached(env, g_neko_throw_le); return NULL; } *next = '\\0'; cursor = next + 1;\n");
        sb.append("    owner = cursor; next = strchr(cursor, '\\n'); if (next == NULL) { free(parts); (void)neko_throw_cached(env, g_neko_throw_le); return NULL; } *next = '\\0'; cursor = next + 1;\n");
        sb.append("    name = cursor; next = strchr(cursor, '\\n'); if (next == NULL) { free(parts); (void)neko_throw_cached(env, g_neko_throw_le); return NULL; } *next = '\\0'; cursor = next + 1;\n");
        sb.append("    desc = cursor; next = strchr(cursor, '\\n'); if (next == NULL) { free(parts); (void)neko_throw_cached(env, g_neko_throw_le); return NULL; } *next = '\\0'; cursor = next + 1;\n");
        sb.append("    interface_text = cursor;\n");
        sb.append("    if (tag_text == NULL || owner == NULL || name == NULL || desc == NULL || interface_text == NULL) { free(parts); (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }\n");
        sb.append("    local = neko_method_handle_from_parts(env, (jint)strtol(tag_text, NULL, 10), owner, name, desc, strcmp(interface_text, \"1\") == 0 ? JNI_TRUE : JNI_FALSE);\n");
        sb.append("    free(parts);\n");
        sb.append("    if (local == NULL) { if (neko_pending_exception(neko_get_current_thread()) == NULL) (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }\n");
        sb.append("    return neko_ldc_cache_global(env, site, local);\n");
        sb.append("}\n\n");
        sb.append("static inline void* neko_ldc_string_site_oop(JNIEnv *env, NekoManifestLdcSite *site) {\n");
        sb.append("    NekoStringInternEntry* entry;\n");
        sb.append("    void* value;\n");
        sb.append("    (void)env;\n");
        sb.append("    if (site == NULL) return NULL;\n");
        sb.append("    if (g_neko_string_root_backend != NEKO_STRING_ROOT_BACKEND_BOOT_CLD) {\n");
        sb.append("        value = neko_create_ldc_string_oop(site, NULL, NULL, NULL, NULL);\n");
        sb.append("#ifdef NEKO_DEBUG_ENABLED\n");
        sb.append("        if (neko_debug_enabled()) neko_native_debug_log(\"neko_ldc_string_site_oop=idx=%u ptr=%p\", site->site_id, value);\n");
        sb.append("#endif\n");
        sb.append("        return value;\n");
        sb.append("    }\n");
        sb.append("    entry = (NekoStringInternEntry*)site->resolved_cache_handle;\n");
        sb.append("    if (entry == NULL) return NULL;\n");
        sb.append("    if (entry->root_cell == NULL) return NULL;\n");
        sb.append("    value = neko_load_oop_from_cell(entry->root_cell);\n");
        sb.append("    if (value == NULL && entry->root_cell != NULL) {\n");
        sb.append("        void *new_str = neko_create_ldc_string_oop(site, NULL, NULL, NULL, NULL);\n");
        sb.append("        if (new_str != NULL) {\n");
        sb.append("            if (__sync_bool_compare_and_swap((void**)entry->root_cell, NULL, new_str)) {\n");
        sb.append("                value = new_str;\n");
        sb.append("            } else {\n");
        sb.append("                value = neko_load_oop_from_cell(entry->root_cell);\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("#ifdef NEKO_DEBUG_ENABLED\n");
        sb.append("    if (neko_debug_enabled()) neko_native_debug_log(\"neko_ldc_string_site_oop=idx=%u ptr=%p\", site->site_id, value);\n");
        sb.append("#endif\n");
        sb.append("    return value;\n");
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
