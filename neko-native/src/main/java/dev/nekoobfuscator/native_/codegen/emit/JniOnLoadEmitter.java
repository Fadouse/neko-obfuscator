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
    neko_string_intern_prewarm_and_publish(env);
    NEKO_TRACE(0, "[nk] ol prewarm ok backend=%d", (int)g_neko_string_root_backend);
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
