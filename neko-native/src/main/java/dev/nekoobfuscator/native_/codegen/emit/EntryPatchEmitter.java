package dev.nekoobfuscator.native_.codegen.emit;

public final class EntryPatchEmitter {
    public String renderEntryPatchSupport() {
        return """
static jboolean neko_atomic_or_bits(void *address, size_t width, uint32_t mask) {
    if (address == NULL || mask == 0u) return JNI_FALSE;
    switch (width) {
        case 2:
            __atomic_fetch_or((uint16_t*)address, (uint16_t)mask, __ATOMIC_RELAXED);
            return JNI_TRUE;
        case 4:
            __atomic_fetch_or((uint32_t*)address, (uint32_t)mask, __ATOMIC_RELAXED);
            return JNI_TRUE;
        default:
            return JNI_FALSE;
    }
}

static void neko_log_flag_patch_path_once(const char *path_name) {
    if (g_neko_flag_patch_path_logged) return;
    NEKO_TRACE(1, "[nk] fp %s j=%d", path_name, g_neko_vm_layout.java_spec_version);
    g_neko_flag_patch_path_logged = 1;
}

static jboolean neko_apply_access_flags_path(void *method_star) {
    uint32_t access_mask;
    size_t access_width;
    if (method_star == NULL) return JNI_FALSE;
    access_mask = (g_neko_vm_layout.access_not_c1_compilable != 0u ? g_neko_vm_layout.access_not_c1_compilable : 0x04000000u)
        | (g_neko_vm_layout.access_not_c2_compilable != 0u ? g_neko_vm_layout.access_not_c2_compilable : 0x02000000u)
        | (g_neko_vm_layout.access_not_osr_compilable != 0u ? g_neko_vm_layout.access_not_osr_compilable : 0x08000000u);
    access_width = g_neko_vm_layout.access_flags_size == 0 ? (size_t)4 : g_neko_vm_layout.access_flags_size;
    neko_log_flag_patch_path_once("AccessFlags");
    return neko_atomic_or_bits(
        (uint8_t*)method_star + g_neko_vm_layout.off_method_access_flags,
        access_width,
        access_mask
    );
}

static jboolean neko_apply_method_flags_path(void *method_star) {
    const uint32_t required_mask = (1u << 8) | (1u << 9) | (1u << 10);
    const uint32_t patch_mask = required_mask | (1u << 12);
    uint32_t *status_ptr;
    uint32_t status_value;
    if (method_star == NULL) return JNI_FALSE;
    if (g_neko_vm_layout.off_method_flags_status < 0) return JNI_FALSE;
    neko_log_flag_patch_path_once("MethodFlags");
    status_ptr = (uint32_t*)((uint8_t*)method_star + g_neko_vm_layout.off_method_flags_status);
    __atomic_fetch_or(status_ptr, patch_mask, __ATOMIC_SEQ_CST);
    status_value = __atomic_load_n(status_ptr, __ATOMIC_SEQ_CST);
    if ((status_value & required_mask) != required_mask) {
        neko_error_log(
            "MethodFlags readback mismatch (status=0x%08x, offset=%td)",
            status_value,
            g_neko_vm_layout.off_method_flags_status
        );
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean neko_apply_no_compile_flags(void *method_star) {
    if (g_neko_vm_layout.java_spec_version >= 21) {
        return neko_apply_method_flags_path(method_star);
    }
    return neko_apply_access_flags_path(method_star);
}

static int neko_patch_method(const NekoManifestMethod *entry, void *method_star) {
    uintptr_t index;
    void *compiled_code;
    void *stub_i2i;
    if (entry == NULL || method_star == NULL) return -1;
    index = (uintptr_t)(entry - g_neko_manifest_methods);
    if (index >= g_neko_manifest_method_count) return -1;
    if (g_neko_manifest_patch_states[index] == NEKO_PATCH_STATE_APPLIED) return 0;
    if (g_neko_manifest_patch_states[index] == NEKO_PATCH_STATE_FAILED) return -1;

    compiled_code = __atomic_load_n((void**)((uint8_t*)method_star + g_neko_vm_layout.off_method_code), __ATOMIC_ACQUIRE);
    if (compiled_code != NULL) {
        g_neko_manifest_patch_states[index] = NEKO_PATCH_STATE_FAILED;
    NEKO_TRACE(1, "[nk] pc %s.%s%s", entry->owner_internal, entry->method_name, entry->method_desc);
        return -1;
    }
    if (!neko_apply_no_compile_flags(method_star)) {
        g_neko_manifest_patch_states[index] = NEKO_PATCH_STATE_FAILED;
        neko_error_log("failed to set no-compile bits for %s.%s%s", entry->owner_internal, entry->method_name, entry->method_desc);
        return -1;
    }
    if (entry->signature_id >= g_neko_signature_descriptor_count) {
        g_neko_manifest_patch_states[index] = NEKO_PATCH_STATE_FAILED;
        neko_error_log("signature_id %u out of range for %s.%s%s", entry->signature_id, entry->owner_internal, entry->method_name, entry->method_desc);
        return -1;
    }

    stub_i2i = g_neko_signature_i2i_stubs[entry->signature_id];
    if (stub_i2i == NULL) {
        g_neko_manifest_patch_states[index] = NEKO_PATCH_STATE_FAILED;
        neko_error_log("missing stub for signature %u (%s.%s%s)", entry->signature_id, entry->owner_internal, entry->method_name, entry->method_desc);
        return -1;
    }

    __atomic_store_n((void**)((uint8_t*)method_star + g_neko_vm_layout.off_method_i2i_entry), stub_i2i, __ATOMIC_RELEASE);
    __atomic_store_n((void**)((uint8_t*)method_star + g_neko_vm_layout.off_method_from_interpreted_entry), stub_i2i, __ATOMIC_RELEASE);

    g_neko_manifest_patch_states[index] = NEKO_PATCH_STATE_APPLIED;
    g_neko_manifest_patch_count++;
    NEKO_TRACE(1, "[nk] pp %s.%s%s s=%u", entry->owner_internal, entry->method_name, entry->method_desc, entry->signature_id);
    return 0;
}

static void neko_patch_discovered_methods(void) {
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        if (g_neko_manifest_method_stars[i] == NULL) continue;
        if (g_neko_manifest_patch_states[i] != NEKO_PATCH_STATE_NONE) continue;
        (void)neko_patch_method(&g_neko_manifest_methods[i], g_neko_manifest_method_stars[i]);
    }
}

""";
    }
}
