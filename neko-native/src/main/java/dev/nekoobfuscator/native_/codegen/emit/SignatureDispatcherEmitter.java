package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Emits one C dispatcher per unique {@link SignaturePlan.Shape}.
 *
 * The dispatcher is the JNI-shim glue between the per-arch trampoline (which
 * delivers raw interpreter args) and main's existing {@code JNICALL}
 * {@code Java_owner_method} implementation symbol.
 *
 * Per call:
 *   1. Acquire {@code JNIEnv*} via the cached {@code g_neko_java_vm} (attaching
 *      if needed).
 *   2. {@code PushLocalFrame(env, 16)}.
 *   3. For reference args (incl. receiver): {@code raw oop -> jobject} via
 *      {@code neko_raw_to_jobject}.
 *   4. Call the JNI-style impl_fn through {@code entry->impl_fn}.
 *   5. For reference return: {@code jobject -> raw oop} via {@code neko_jobject_to_raw}.
 *   6. {@code PopLocalFrame}.
 */
public final class SignatureDispatcherEmitter {

    public String render(SignaturePlan plan) {
        if (plan.shapes().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("/* === Per-signature dispatchers === */\n");
        for (int sigId = 0; sigId < plan.shapes().size(); sigId++) {
            sb.append(renderOne(sigId, plan.shapes().get(sigId)));
        }
        return sb.toString();
    }

    private String renderOne(int sigId, SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        char ret = shape.returnKind();
        char[] args = shape.argKinds();
        String retC = SignaturePlan.cAbiType(ret);
        String retJ = SignaturePlan.jniArgType(ret);
        boolean isStatic = shape.isStatic();

        // typedef for impl_fn signature (matches main's JNICALL Java_... emission)
        sb.append("typedef ").append(retJ)
            .append(" (JNICALL *neko_sig_").append(sigId).append("_impl_t)(JNIEnv*, ")
            .append(isStatic ? "jclass" : "jobject");
        for (char a : args) sb.append(", ").append(SignaturePlan.jniArgType(a));
        sb.append(");\n");

        // dispatcher — hidden visibility (not static) so the naked-asm trampoline can call it.
        // The trampoline passes the JavaThread as the second arg (after entry)
        // so we can push ref args to the active handle block (GC tracking).
        sb.append("__attribute__((visibility(\"hidden\"))) ").append(retC)
            .append(" neko_sig_").append(sigId).append("_dispatch(NekoManifestMethod *entry, void *thread");
        if (!isStatic) sb.append(", void *raw_recv_slot");
        for (int i = 0; i < args.length; i++) {
            sb.append(", ").append(SignaturePlan.cAbiType(args[i])).append(" a").append(i);
        }
        sb.append(") {\n");
        sb.append("    JNIEnv *env = NULL;\n");
        sb.append("    if (g_neko_java_vm == NULL) ").append(returnZero(ret)).append(";\n");
        sb.append("    if ((*g_neko_java_vm)->GetEnv(g_neko_java_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {\n");
        sb.append("        if (g_neko_java_vm != NULL) (*g_neko_java_vm)->AttachCurrentThread(g_neko_java_vm, (void**)&env, NULL);\n");
        sb.append("        if (env == NULL) ").append(returnZero(ret)).append(";\n");
        sb.append("    }\n");
        sb.append("    NEKO_PATCH_LOG(\"sig").append(sigId).append(" enter %s.%s%s\", entry->owner_internal, entry->method_name, entry->method_desc);\n");
        sb.append("    if ((*env)->PushLocalFrame(env, 16) != 0) {\n");
        sb.append("        NEKO_PATCH_LOG(\"sig").append(sigId).append(" PushLocalFrame failed\");\n");
        sb.append("        ").append(returnZero(ret)).append(";\n");
        sb.append("    }\n");

        // Push ref args + receiver into the JavaThread's _active_handles
        // so the GC tracks them as roots during the JNI call. raw_*_slot
        // values are pointers into the interpreter slot array — deref to
        // get the raw oop, then push.
        sb.append("    neko_handle_save_t __hsave;\n");
        sb.append("    neko_handle_save(thread, &__hsave);\n");
        if (isStatic) {
            sb.append("    jclass owner_cls = neko_find_class(env, entry->owner_internal);\n");
        } else {
            sb.append("    void *__recv_oop = raw_recv_slot != NULL ? *(void**)raw_recv_slot : NULL;\n");
            sb.append("    jobject self = (jobject)neko_handle_push(thread, __recv_oop);\n");
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] == 'L') {
                sb.append("    void *__a").append(i).append("_oop = a").append(i)
                    .append(" != NULL ? *(void**)a").append(i).append(" : NULL;\n");
                sb.append("    jobject ja").append(i).append(" = (jobject)neko_handle_push(thread, __a")
                    .append(i).append("_oop);\n");
            }
        }

        // call
        if (ret == 'V') {
            sb.append("    ");
        } else if (ret == 'L') {
            sb.append("    jobject __ret = ");
        } else {
            sb.append("    ").append(retJ).append(" __ret = ");
        }
        sb.append("((neko_sig_").append(sigId).append("_impl_t)entry->impl_fn)(env, ")
            .append(isStatic ? "owner_cls" : "self");
        for (int i = 0; i < args.length; i++) {
            sb.append(", ");
            switch (args[i]) {
                case 'L' -> sb.append("ja").append(i);
                case 'F' -> sb.append("(jfloat)a").append(i);
                case 'D' -> sb.append("(jdouble)a").append(i);
                case 'J' -> sb.append("(jlong)a").append(i);
                default -> sb.append("(jint)a").append(i);
            }
        }
        sb.append(");\n");

        // If impl_fn left an exception pending, do not feed __ret to
        // PopLocalFrame — it may be a NULL or invalid handle, and
        // PopLocalFrame's handle conversion does not null-check on every JDK.
        sb.append("    if ((*env)->ExceptionCheck(env)) {\n");
        sb.append("        (void)(*env)->PopLocalFrame(env, NULL);\n");
        sb.append("        neko_handle_restore(&__hsave);\n");
        if (ret == 'V') sb.append("        return;\n");
        else if (ret == 'L') sb.append("        return NULL;\n");
        else sb.append("        return (").append(retC).append(")0;\n");
        sb.append("    }\n");

        // return + pop
        if (ret == 'V') {
            sb.append("    (void)(*env)->PopLocalFrame(env, NULL);\n");
            sb.append("    neko_handle_restore(&__hsave);\n");
            sb.append("    return;\n");
        } else if (ret == 'L') {
            sb.append("    jobject __surviving = (*env)->PopLocalFrame(env, __ret);\n");
            sb.append("    void *__raw_ret = __surviving == NULL ? NULL : *(void**)__surviving;\n");
            sb.append("    neko_handle_restore(&__hsave);\n");
            sb.append("    return __raw_ret;\n");
        } else {
            sb.append("    (void)(*env)->PopLocalFrame(env, NULL);\n");
            sb.append("    neko_handle_restore(&__hsave);\n");
            sb.append("    return (").append(retC).append(")__ret;\n");
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private String returnZero(char ret) {
        return ret == 'V' ? "{ return; }" : "{ return (" + SignaturePlan.cAbiType(ret) + ")0; }";
    }
}
