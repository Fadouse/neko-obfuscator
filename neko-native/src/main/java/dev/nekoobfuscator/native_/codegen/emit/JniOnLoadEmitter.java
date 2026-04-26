package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Emits {@code JNI_OnLoad} (registers {@code nekoBootstrap}, primes hotspot,
 * runs layout discovery, runs first-pass owner discovery), the
 * {@code Java_dev_nekoobfuscator_runtime_NekoNativeLoader_nekoBootstrap}
 * entry point that the Java loader invokes from each obfuscated class's
 * {@code <clinit>} (so late-loaded owners are picked up), and the small
 * {@code g_neko_loader_methods[]} {@code RegisterNatives} table.
 */
public final class JniOnLoadEmitter {

    public String renderRegistrationTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("/* === Loader native bridge: only nekoBootstrap is exported === */\n");
        sb.append("JNIEXPORT void JNICALL Java_dev_nekoobfuscator_runtime_NekoNativeLoader_nekoBootstrap(JNIEnv *env, jclass loaderClass);\n");
        sb.append("static JNINativeMethod g_neko_loader_methods[] = {\n");
        sb.append("    {\"nekoBootstrap\", \"()V\", (void*)&Java_dev_nekoobfuscator_runtime_NekoNativeLoader_nekoBootstrap},\n");
        sb.append("};\n\n");
        return sb.toString();
    }

    public String renderJniOnLoadAndBootstrap() {
        return """
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    (void)reserved;
    g_neko_java_vm = vm;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass loaderClass = neko_find_class(env, "dev/nekoobfuscator/runtime/NekoNativeLoader");
    if (loaderClass == NULL) return JNI_ERR;
    neko_hotspot_init(env);
    if (!neko_method_layout_init(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
    }
    if (neko_register_natives(env, loaderClass, g_neko_loader_methods, 1) != 0) return JNI_ERR;
    neko_bootstrap_owner_discovery(env);
    if (neko_exception_check(env)) neko_exception_clear(env);
    return JNI_VERSION_1_6;
}

static void neko_bootstrap_owner_discovery(JNIEnv *env) {
    if (env == NULL) return;
    neko_manifest_discover_and_patch(env);
    if (neko_exception_check(env)) neko_exception_clear(env);
}

JNIEXPORT void JNICALL Java_dev_nekoobfuscator_runtime_NekoNativeLoader_nekoBootstrap(JNIEnv *env, jclass loaderClass) {
    (void)loaderClass;
    neko_bootstrap_owner_discovery(env);
}

""";
    }
}
