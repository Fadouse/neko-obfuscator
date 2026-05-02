package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Emits one C dispatcher per unique {@link SignaturePlan.Shape}.
 *
 * The dispatcher is the direct-C bridge between the per-arch trampoline
 * (which delivers raw interpreter args) and the generated raw translated
 * body. The hot path does not call the JNI function table for frame
 * bookkeeping; it snapshots JavaThread::_active_handles and restores the
 * JNIHandleBlock chain directly after the translated body returns:
 *
 * <ul>
 *   <li>{@code JNIEnv*} comes from {@code JavaThread::_jni_environment}
 *       (direct field read, no {@code GetEnv}/{@code AttachCurrentThread}).</li>
 *   <li>Per-call ref tracking uses {@code neko_handle_save / neko_handle_push /
 *       neko_handle_restore}, which write directly into
 *       {@code JavaThread::_active_handles->_top}/{@code _handles}.</li>
 *   <li>Pending exception detection reads
 *       {@code JavaThread::_pending_exception} directly when VMStructs has
 *       published the offset.</li>
 *   <li>For static methods, {@code jclass owner} is taken from the
 *       per-binding {@code owner_class_global_ref} cached at
 *       {@code JNI_OnLoad} time through native class metadata.</li>
 *   <li>For reference returns, the raw oop is recovered via a tag-aware
 *       deref — JNI globals carry low-bit tags (0x1 weak, 0x2 strong)
 *       that must be masked before dereferencing the slot, mirroring
 *       what {@code JNIHandles::resolve} would do internally.</li>
 * </ul>
 *
 * Resetting just the saved block's {@code _top} leaks roots into chained
 * blocks, so restore walks/free's any blocks linked above the saved one.
 *
 * Constraints documented in {@code todo.md} / {@code problems.md}: no
 * JVMTI; the trampoline-to-impl_fn boundary must not use JNI fallback calls.
 */
public final class SignatureDispatcherEmitter {

    public String render(SignaturePlan plan) {
        if (plan.shapes().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("/* === Per-signature direct-C dispatchers === */\n");
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

        // typedef for impl_fn signature (matches the generated raw body)
        sb.append("typedef ").append(retJ)
            .append(" (*neko_sig_").append(sigId).append("_impl_t)(void*, JNIEnv*, ")
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
        // JNIEnv* via direct field read on JavaThread (falls back to GetEnv
        // only if VMStructs did not expose the offset — see neko_thread_jni_env).
        sb.append("    JNIEnv *env = neko_thread_jni_env(thread);\n");
        sb.append("    if (env == NULL) ").append(returnZero(ret)).append(";\n");
        sb.append("    NEKO_PATCH_LOG(\"sig").append(sigId).append(" enter %s.%s%s\", entry->owner_internal, entry->method_name, entry->method_desc);\n");

        // Save the active handle block top for receiver/ref args we push
        // ourselves (translates raw oops the trampoline gave us into
        // jobject slots). restore also unlinks any JNIHandleBlock chain
        // extension performed by nested native/JNI allocations.
        sb.append("    neko_handle_save_t __hsave;\n");
        sb.append("    neko_handle_save_t *__prev_hsave;\n");
        sb.append("    neko_handle_save(thread, &__hsave);\n");
        sb.append("    __prev_hsave = g_neko_active_handle_save;\n");
        sb.append("    g_neko_active_handle_save = &__hsave;\n");
        sb.append("    neko_bind_manifest_owner_strings(thread, env, entry->owner_internal);\n");

        if (isStatic) {
            // Cached owner mirror captured at JNI_OnLoad from native class metadata.
            sb.append("    jclass owner_cls = (jclass)neko_direct_oop_to_handle(thread, entry->owner_class_global_ref);\n");
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
        sb.append("((neko_sig_").append(sigId).append("_impl_t)entry->impl_fn)(thread, env, ")
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

        // Direct read of JavaThread::_pending_exception. Missing VMStructs
        // metadata is a hard failure here; hot path JNI ExceptionCheck is not
        // a valid fallback.
        sb.append("    if (g_neko_off_thread_pending_exception <= 0) { fprintf(stderr, \"[neko-direct] pending-exception offset missing in sig").append(sigId).append("\\n\"); abort(); }\n");
        sb.append("    int __pending = (*(void**)((char*)thread + g_neko_off_thread_pending_exception) != NULL);\n");
        sb.append("    if (__pending) {\n");
        sb.append("        neko_handle_restore(&__hsave);\n");
        sb.append("        g_neko_active_handle_save = __prev_hsave;\n");
        if (ret == 'V') sb.append("        return;\n");
        else if (ret == 'L') sb.append("        return NULL;\n");
        else sb.append("        return (").append(retC).append(")0;\n");
        sb.append("    }\n");

        // return + restore
        if (ret == 'V') {
            sb.append("    neko_handle_restore(&__hsave);\n");
            sb.append("    g_neko_active_handle_save = __prev_hsave;\n");
            sb.append("    return;\n");
        } else if (ret == 'L') {
            sb.append("    void *__raw_ret = NULL;\n");
            sb.append("    if (__ret != NULL) __raw_ret = neko_handle_oop(__ret);\n");
            sb.append("    neko_handle_restore(&__hsave);\n");
            sb.append("    g_neko_active_handle_save = __prev_hsave;\n");
            sb.append("    return __raw_ret;\n");
        } else {
            sb.append("    neko_handle_restore(&__hsave);\n");
            sb.append("    g_neko_active_handle_save = __prev_hsave;\n");
            sb.append("    return (").append(retC).append(")__ret;\n");
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private String returnZero(char ret) {
        return ret == 'V' ? "{ return; }" : "{ return (" + SignaturePlan.cAbiType(ret) + ")0; }";
    }
}
