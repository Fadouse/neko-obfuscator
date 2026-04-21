package dev.nekoobfuscator.native_.codegen.emit;

public final class JniOnLoadEmitter {
    public String renderJniOnLoad() {
        return """
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    jvmtiEnv *jvmti = NULL;
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
#ifdef NEKO_DEBUG_ENABLED
    if (env_debug == NULL) {
        jclass systemClass = (*env)->FindClass(env, "java/lang/System");
        if (systemClass != NULL) {
            jmethodID getProperty = (*env)->GetStaticMethodID(env, systemClass, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
            if (getProperty != NULL) {
                jstring key = (*env)->NewStringUTF(env, "neko.native.debug");
                if (key != NULL) {
                    jstring value = (jstring)(*env)->CallStaticObjectMethod(env, systemClass, getProperty, key);
                    if (value != NULL) {
                        const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
                        if (chars != NULL) {
                            neko_debug_level = (strcmp(chars, "true") == 0) ? 1 : atoi(chars);
                            (*env)->ReleaseStringUTFChars(env, value, chars);
                        }
                        (*env)->DeleteLocalRef(env, value);
                    }
                    (*env)->DeleteLocalRef(env, key);
                }
            }
            (*env)->DeleteLocalRef(env, systemClass);
        }
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }
#endif
    env_status = (*vm)->GetEnv(vm, (void**)&jvmti, JVMTI_VERSION_1_2);
    if (env_status != JNI_OK || jvmti == NULL) {
        neko_error_log("GetEnv(JVMTI_VERSION_1_2) failed, falling back to throw body");
        return JNI_VERSION_1_6;
    }
    neko_mark_loader_loaded(env);
    g_neko_jvmti = jvmti;
    if (!neko_resolve_vm_symbols()) {
        return JNI_VERSION_1_6;
    }
    if (!neko_parse_vm_layout(env)) {
        return JNI_VERSION_1_6;
    }
    neko_log_runtime_helpers_ready();
    neko_log_wave4a_status();
    if (!neko_init_jvmti(vm, jvmti)) {
        return JNI_VERSION_1_6;
    }
    if (!neko_discover_loaded_classes(env, jvmti)) {
        return JNI_VERSION_1_6;
    }
    if (!neko_discover_manifest_owners(env, jvmti)) {
        return JNI_VERSION_1_6;
    }
    neko_resolve_string_intern_layout();
    neko_string_intern_prewarm_and_publish(env);
    neko_manifest_lock_enter();
    neko_patch_discovered_methods();
    neko_manifest_lock_exit();
    if (!neko_install_class_prepare_callback(jvmti)) {
        return JNI_VERSION_1_6;
    }
    NEKO_TRACE(0, "[nk] dm %u/%u", g_neko_manifest_match_count, g_neko_manifest_method_count);
    NEKO_TRACE(0, "[nk] dp %u/%u", g_neko_manifest_patch_count, g_neko_manifest_method_count);
    neko_log_wave2_ready();
    neko_log_wave3_ready();
    return JNI_VERSION_1_6;
}
""";
    }
}
