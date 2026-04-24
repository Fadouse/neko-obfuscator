package dev.nekoobfuscator.native_.codegen.emit;

public final class JniOnLoadEmitter {
    public String renderJniOnLoad() {
        return """
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    jint env_status;
    (void)reserved;
    g_neko_java_vm = vm;
    neko_init_debug_level_from_env();
    neko_native_debug_log("onload enter");
    env_status = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
    if (env_status != JNI_OK || env == NULL) {
        neko_error_log("GetEnv(JNI_VERSION_1_6) failed, falling back to throw body");
        return JNI_VERSION_1_6;
    }
    if (!neko_init_throwable_cache(env)) {
        neko_error_log("failed to initialize bootstrap throwable cache");
        return JNI_ERR;
    }
    neko_native_debug_log(
        "throwable_cache_ok=%d npe=%p le=%p loader_le=%p",
        (g_neko_throw_npe != NULL && g_neko_throw_le != NULL && g_neko_throw_loader_linkage != NULL) ? 1 : 0,
        g_neko_throw_npe,
        g_neko_throw_le,
        g_neko_throw_loader_linkage
    );
    if (!neko_resolve_vm_symbols()) {
        return JNI_VERSION_1_6;
    }
    if (!neko_parse_vm_layout_strict(env)) {
        return JNI_VERSION_1_6;
    }
    if (!neko_capture_wellknown_klasses()) {
        return JNI_VERSION_1_6;
    }
    neko_mark_loader_loaded();
    NEKO_TRACE(0, "[nk] ol mark_loader_loaded ok str=%p", g_neko_vm_layout.klass_java_lang_String);
    neko_log_runtime_helpers_ready();
    neko_log_wave4a_status();
    neko_resolve_string_intern_layout();
    NEKO_TRACE(0, "[nk] ol resolve_string_intern_layout ok hash_off=%td", g_neko_vm_layout.off_string_hash);
    g_neko_string_root_backend = NEKO_STRING_ROOT_BACKEND_FALLBACK_REGENERATE;
    NEKO_TRACE(0, "[nk] ol prewarm skipped backend=%d", (int)g_neko_string_root_backend);
    neko_bootstrap_owner_discovery();
    neko_patch_discovered_methods();
    NEKO_TRACE(0, "[nk] dm %u/%u", g_neko_manifest_match_count, g_neko_manifest_method_count);
    NEKO_TRACE(0, "[nk] dp %u/%u", g_neko_manifest_patch_count, g_neko_manifest_method_count);
    neko_log_wave2_ready();
    neko_log_wave3_ready();
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    jint env_status;
    (void)reserved;
    if (vm == NULL) return;
    env_status = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
    if (env_status != JNI_OK || env == NULL) return;
    neko_manifest_teardown(env);
}
""";
    }
}
