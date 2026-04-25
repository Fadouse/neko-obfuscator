package dev.nekoobfuscator.native_.codegen.emit;

public final class JniOnLoadEmitter {
    public String renderJniOnLoad() {
        return """
static void neko_onload_clear_pending_exception(JNIEnv *env, const char *where) {
    const char *label = where == NULL ? "unknown" : where;
    if (env != NULL && *env != NULL && (*env)->ExceptionCheck(env)) {
        neko_error_log("clearing JNI pending exception during JNI_OnLoad at %s", label);
        (*env)->ExceptionClear(env);
    }
    if (g_neko_vm_layout.off_thread_pending_exception >= 0
        && g_neko_vm_layout.off_thread_pending_exception != g_neko_vm_layout.off_java_thread_jni_environment) {
        void *thread = neko_get_current_thread();
        if (thread != NULL && neko_pending_exception(thread) != NULL) {
            neko_error_log("clearing HotSpot pending exception during JNI_OnLoad at %s", label);
            neko_clear_pending_exception(thread);
        }
    }
}

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
        neko_onload_clear_pending_exception(env, "resolve_vm_symbols");
        return JNI_VERSION_1_6;
    }
    if (!neko_parse_vm_layout_strict(env)) {
        neko_onload_clear_pending_exception(env, "parse_vm_layout");
        return JNI_VERSION_1_6;
    }
    if (!neko_init_throwable_cache(env)) {
        neko_error_log("failed to initialize bootstrap throwable cache");
    }
    neko_onload_clear_pending_exception(env, "throwable_cache");
    neko_native_debug_log(
        "throwable_cache_ok=%d npe=%p le=%p loader_le=%p",
        (g_neko_throw_npe != NULL && g_neko_throw_le != NULL && g_neko_throw_loader_linkage != NULL) ? 1 : 0,
        g_neko_throw_npe,
        g_neko_throw_le,
        g_neko_throw_loader_linkage
    );
    if (g_neko_vm_layout.compact_object_headers) {
        NEKO_TRACE(0, "[nk] compact_object_headers=on; not supported by NekoObfuscator (JDK 8-21 target)");
        g_neko_vm_layout.compact_object_headers = JNI_FALSE;
        g_neko_vm_layout.use_compact_object_headers = JNI_FALSE;
    }
    if (!neko_capture_wellknown_klasses()) {
        neko_onload_clear_pending_exception(env, "capture_wellknown_klasses");
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
    NEKO_TRACE(0, "[nk] owner discovery deferred until loader_refresh");
    neko_log_wave2_ready();
    neko_log_wave3_ready();
    neko_onload_clear_pending_exception(env, "complete");
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

JNIEXPORT void JNICALL Java_dev_nekoobfuscator_runtime_NekoNativeLoader_refresh(JNIEnv *env, jclass loaderClass, jclass ownerClass) {
    (void)loaderClass;
    neko_bootstrap_class_discovery(env, ownerClass);
    neko_onload_clear_pending_exception(env, "loader_refresh");
}
""";
    }
}
