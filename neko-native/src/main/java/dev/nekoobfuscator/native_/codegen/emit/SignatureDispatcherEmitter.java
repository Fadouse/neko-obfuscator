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
        sb.append("__attribute__((visibility(\"hidden\"))) ").append(retC)
            .append(" neko_sig_").append(sigId).append("_dispatch(NekoManifestMethod *entry");
        if (!isStatic) sb.append(", void *raw_recv");
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
        sb.append("    if ((*env)->PushLocalFrame(env, 16) != 0) ").append(returnZero(ret)).append(";\n");

        // raw_recv and reference args arrive from the trampoline as POINTERS
        // into the interpreter slot array. Each pointer is already a valid
        // jobject (JNI handle) under HotSpot's resolve-by-deref convention.
        if (isStatic) {
            sb.append("    jclass owner_cls = neko_find_class(env, entry->owner_internal);\n");
        } else {
            sb.append("    jobject self = (jobject)raw_recv;\n");
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] == 'L') {
                sb.append("    jobject ja").append(i).append(" = (jobject)a").append(i).append(";\n");
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

        // return + pop. For ref returns, PopLocalFrame's second arg is the
        // jobject we want surviving past the pop — its raw oop survives in
        // the caller's local frame, and we then deref to a raw oop pointer
        // for the interpreter return slot.
        if (ret == 'V') {
            sb.append("    (void)(*env)->PopLocalFrame(env, NULL);\n");
            sb.append("    return;\n");
        } else if (ret == 'L') {
            sb.append("    jobject __surviving = (*env)->PopLocalFrame(env, __ret);\n");
            sb.append("    return __surviving == NULL ? NULL : *(void**)__surviving;\n");
        } else {
            sb.append("    (void)(*env)->PopLocalFrame(env, NULL);\n");
            sb.append("    return (").append(retC).append(")__ret;\n");
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private String returnZero(char ret) {
        return ret == 'V' ? "{ return; }" : "{ return (" + SignaturePlan.cAbiType(ret) + ")0; }";
    }
}
