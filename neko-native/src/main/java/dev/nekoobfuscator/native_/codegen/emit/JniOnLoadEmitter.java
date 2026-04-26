package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Emits {@code JNI_OnLoad}. The Java runtime loader only calls
 * {@code System.load}; all HotSpot probing and manifest discovery happen from
 * native initialization and no Java bootstrap method is registered or exported.
 */
public final class JniOnLoadEmitter {

    public String renderRegistrationTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("/* === No Java native bridge: NekoNativeLoader only calls System.load === */\n\n");
        return sb.toString();
    }

    public String renderJniOnLoadAndBootstrap() {
        return """
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    (void)reserved;
    g_neko_java_vm = vm;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    neko_hotspot_init(env);
    if (!neko_method_layout_init(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
    }
    neko_bootstrap_owner_discovery(env);
    if (neko_exception_check(env)) neko_exception_clear(env);
    return JNI_VERSION_1_6;
}

static void neko_bootstrap_owner_discovery(JNIEnv *env) {
    if (env == NULL) return;
    neko_manifest_discover_and_patch(env);
    if (neko_exception_check(env)) neko_exception_clear(env);
}

""";
    }
}
