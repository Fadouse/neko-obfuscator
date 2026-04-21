package dev.nekoobfuscator.native_.codegen.emit;

public final class JniOnLoadEmitter {
    public String renderJniOnLoad() {
        return """
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    jint env_status;
#ifdef NEKO_DEBUG_ENABLED
    const char *env_debug = NULL;
#endif
    (void)reserved;
    g_neko_java_vm = vm;
#ifdef NEKO_DEBUG_ENABLED
    env_debug = getenv("NEKO_NATIVE_DEBUG");
    neko_debug_level = env_debug != NULL ? atoi(env_debug) : 0;
#endif
    env_status = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
    if (env_status != JNI_OK || env == NULL) {
        neko_error_log("GetEnv(JNI_VERSION_1_6) failed, falling back to throw body");
        return JNI_VERSION_1_6;
    }
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
    neko_log_runtime_helpers_ready();
    neko_log_wave4a_status();
    neko_resolve_string_intern_layout();
    neko_string_intern_prewarm_and_publish(env);
    neko_bootstrap_owner_discovery();
    neko_patch_discovered_methods();
    NEKO_TRACE(0, "[nk] dm %u/%u", g_neko_manifest_match_count, g_neko_manifest_method_count);
    NEKO_TRACE(0, "[nk] dp %u/%u", g_neko_manifest_patch_count, g_neko_manifest_method_count);
    neko_log_wave2_ready();
    neko_log_wave3_ready();
    return JNI_VERSION_1_6;
}
""";
    }
}
