package dev.nekoobfuscator.native_.codegen.emit;

import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits the {@code NekoManifestMethod} struct, the data table populated from
 * {@link NativeMethodBinding}s, and the parallel {@code g_neko_manifest_method_stars}
 * array that the trampoline scans to map a HotSpot {@code Method*} to a manifest index.
 *
 * Also emits the discovery driver {@code neko_manifest_discover_and_patch} that:
 *   - groups manifest entries by owner internal name,
 *   - resolves each owner mirror through the native class resolver,
 *   - resolves each method's HotSpot Method* through metadata walking,
 *   - calls {@code neko_patch_method_entry} (defined in {@link MethodPatcherEmitter}).
 *
 * No JNI function-table helpers are used: missing metadata or VM symbols abort.
 */
public final class ManifestEmitter {

    public String renderStructAndForwardDecls() {
        StringBuilder sb = new StringBuilder();
        sb.append("static void neko_bootstrap_owner_discovery(JNIEnv *env);\n");
        sb.append("static jboolean neko_manifest_discover_and_patch(JNIEnv *env);\n");
        sb.append("static jboolean neko_manifest_patch_defined_class(JNIEnv *env, jclass owner_cls);\n");
        sb.append("typedef struct NekoManifestMethod NekoManifestMethod;\n");
        sb.append("struct NekoManifestMethod {\n");
        sb.append("    const char *owner_internal;   /* +0 */\n");
        sb.append("    const char *method_name;      /* +8 */\n");
        sb.append("    const char *method_desc;      /* +16 */\n");
        sb.append("    void *impl_fn;                /* +24 */\n");
        sb.append("    uint32_t signature_id;        /* +32 */\n");
        sb.append("    uint8_t is_static;            /* +36 */\n");
        sb.append("    uint8_t patch_state;          /* +37 */\n");
        sb.append("    uint8_t _pad0;                /* +38 */\n");
        sb.append("    uint8_t _pad1;                /* +39 */\n");
        /* Stable owner class mirror for static dispatch. Populated once from
         * the native resolver and stored as a direct mirror oop, avoiding JNI
         * function-table global-ref creation. */
        sb.append("    void *owner_class_global_ref; /* +40 */\n");
        sb.append("};\n");
        sb.append("_Static_assert(sizeof(struct NekoManifestMethod) == ")
            .append(PatcherLayoutConstants.MANIFEST_METHOD_SIZE)
            .append(", \"NekoManifestMethod size mismatch with PatcherLayoutConstants\");\n");
        sb.append("#define NEKO_PATCH_STATE_NONE     0u\n");
        sb.append("#define NEKO_PATCH_STATE_APPLIED  1u\n");
        sb.append("#define NEKO_PATCH_STATE_FAILED   2u\n\n");
        return sb.toString();
    }

    public String renderTables(List<NativeMethodBinding> bindings, SignaturePlan plan) {
        /* These globals are referenced from naked-asm trampolines via direct
         * RIP-relative loads. Using `static` triggers ld.lld PIC complaints
         * because the inline asm references them as if they were external
         * symbols. Mark them with hidden visibility instead — the linker
         * sees a defined symbol in this module and is happy. */
        StringBuilder sb = new StringBuilder();
        sb.append("/* === Manifest tables (hidden visibility for asm RIP-rel access) === */\n");
        sb.append("__attribute__((visibility(\"hidden\"))) struct NekoManifestMethod g_neko_manifest_methods[] = {\n");
        for (int i = 0; i < bindings.size(); i++) {
            NativeMethodBinding b = bindings.get(i);
            int sigId = plan.signatureIdFor(i);
            sb.append("    { \"")
                .append(escape(b.ownerInternalName())).append("\", \"")
                .append(escape(b.methodName())).append("\", \"")
                .append(escape(b.descriptor())).append("\", (void*)&")
                .append(b.rawFunctionName()).append(", ")
                .append(sigId).append("u, ")
                .append(b.isStatic() ? '1' : '0').append(", NEKO_PATCH_STATE_NONE, 0, 0, NULL },\n");
        }
        if (bindings.isEmpty()) {
            sb.append("    { NULL, NULL, NULL, NULL, 0, 0, NEKO_PATCH_STATE_NONE, 0, 0, NULL }\n");
        }
        sb.append("};\n");
        sb.append("__attribute__((visibility(\"hidden\"))) const uint32_t g_neko_manifest_method_count = ")
            .append(bindings.size()).append("u;\n");
        sb.append("/* Parallel array of Method* values, populated by the discovery pass.\n");
        sb.append(" * Trampolines scan this to map HotSpot Method* -> manifest index. */\n");
        sb.append("__attribute__((visibility(\"hidden\"))) void *g_neko_manifest_method_stars[")
            .append(Math.max(1, bindings.size()))
            .append("] = {0};\n\n");
        sb.append("#define NEKO_METHOD_ALIAS_CAPACITY ")
            .append(Math.max(1, bindings.size() * 4))
            .append("u\n");
        sb.append("__attribute__((visibility(\"hidden\"))) void *g_neko_manifest_alias_method_stars[NEKO_METHOD_ALIAS_CAPACITY] = {0};\n");
        sb.append("__attribute__((visibility(\"hidden\"))) uint32_t g_neko_manifest_alias_indices[NEKO_METHOD_ALIAS_CAPACITY] = {0};\n");
        sb.append("__attribute__((visibility(\"hidden\"))) uint32_t g_neko_manifest_alias_count = 0u;\n\n");
        return sb.toString();
    }

    public String renderDiscoveryDriver(List<NativeMethodBinding> bindings, Map<String, Integer> ownerBindIds) {
        Map<String, List<Integer>> byOwner = new LinkedHashMap<>();
        for (int i = 0; i < bindings.size(); i++) {
            byOwner.computeIfAbsent(bindings.get(i).ownerInternalName(), ignored -> new ArrayList<>()).add(i);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("/* === Discovery + patch driver === */\n");
        sb.append("static jboolean neko_manifest_has_method_star(uint32_t idx, void *method_star) {\n");
        sb.append("    uint32_t i;\n");
        sb.append("    if (idx >= g_neko_manifest_method_count || method_star == NULL) return JNI_FALSE;\n");
        sb.append("    if (g_neko_manifest_method_stars[idx] == method_star) return JNI_TRUE;\n");
        sb.append("    for (i = 0u; i < g_neko_manifest_alias_count; i++) {\n");
        sb.append("        if (g_neko_manifest_alias_indices[i] == idx && g_neko_manifest_alias_method_stars[i] == method_star) return JNI_TRUE;\n");
        sb.append("    }\n");
        sb.append("    return JNI_FALSE;\n");
        sb.append("}\n\n");
        sb.append("static void neko_manifest_register_method_star(uint32_t idx, void *method_star) {\n");
        sb.append("    uint32_t slot;\n");
        sb.append("    if (idx >= g_neko_manifest_method_count || method_star == NULL) return;\n");
        sb.append("    if (g_neko_manifest_method_stars[idx] == NULL || g_neko_manifest_method_stars[idx] == method_star) {\n");
        sb.append("        g_neko_manifest_method_stars[idx] = method_star;\n");
        sb.append("        return;\n");
        sb.append("    }\n");
        sb.append("    if (neko_manifest_has_method_star(idx, method_star)) return;\n");
        sb.append("    slot = __atomic_fetch_add(&g_neko_manifest_alias_count, 1u, __ATOMIC_ACQ_REL);\n");
        sb.append("    if (slot >= NEKO_METHOD_ALIAS_CAPACITY) {\n");
        sb.append("        __atomic_store_n(&g_neko_manifest_alias_count, NEKO_METHOD_ALIAS_CAPACITY, __ATOMIC_RELEASE);\n");
        sb.append("        return;\n");
        sb.append("    }\n");
        sb.append("    g_neko_manifest_alias_indices[slot] = idx;\n");
        sb.append("    __atomic_store_n(&g_neko_manifest_alias_method_stars[slot], method_star, __ATOMIC_RELEASE);\n");
        sb.append("}\n\n");
        sb.append("static void neko_manifest_abort_patch_failure(const NekoManifestMethod *entry, const char *phase) {\n");
        sb.append("    fprintf(stderr, \"[neko-patch] required method patch failed during %s: %s.%s%s\\n\",\n");
        sb.append("        phase != NULL ? phase : \"manifest discovery\",\n");
        sb.append("        entry != NULL && entry->owner_internal != NULL ? entry->owner_internal : \"?\",\n");
        sb.append("        entry != NULL && entry->method_name != NULL ? entry->method_name : \"?\",\n");
        sb.append("        entry != NULL && entry->method_desc != NULL ? entry->method_desc : \"?\");\n");
        sb.append("    abort();\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_manifest_resolve_one(JNIEnv *env, uint32_t idx, jclass owner_cls) {\n");
        sb.append("    NekoManifestMethod *entry;\n");
        sb.append("    void *owner_klass;\n");
        sb.append("    void *method_star;\n");
        sb.append("    if (env == NULL || idx >= g_neko_manifest_method_count) return JNI_FALSE;\n");
        sb.append("    entry = &g_neko_manifest_methods[idx];\n");
        sb.append("    if (entry->owner_class_global_ref == NULL && owner_cls != NULL) {\n");
        sb.append("        jobject __owner_mirror = neko_persistent_handle(env, owner_cls, entry->owner_internal);\n");
        sb.append("        if (__owner_mirror == NULL || neko_exception_check(env)) {\n");
        sb.append("            if (neko_exception_check(env)) neko_exception_clear(env);\n");
        sb.append("        } else {\n");
        sb.append("            __atomic_store_n((void**)&entry->owner_class_global_ref, (void*)__owner_mirror, __ATOMIC_RELEASE);\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    if (owner_cls == NULL) return JNI_FALSE;\n");
        sb.append("    owner_klass = neko_class_mirror_to_klass(owner_cls);\n");
        sb.append("    if (owner_klass == NULL || neko_exception_check(env)) {\n");
        sb.append("        if (neko_exception_check(env)) neko_exception_clear(env);\n");
        sb.append("        return JNI_FALSE;\n");
        sb.append("    }\n");
        sb.append("    neko_link_class_methods(env, owner_cls, entry->owner_internal, entry->method_name, entry->method_desc);\n");
        sb.append("    method_star = neko_resolve_method(owner_klass, entry->method_name, entry->method_desc);\n");
        sb.append("    if (method_star == NULL) {\n");
        sb.append("        entry->patch_state = NEKO_PATCH_STATE_FAILED;\n");
        sb.append("        return JNI_FALSE;\n");
        sb.append("    }\n");
        sb.append("    if (neko_manifest_has_method_star(idx, method_star)) {\n");
        sb.append("        entry->patch_state = NEKO_PATCH_STATE_APPLIED;\n");
        sb.append("        return JNI_TRUE;\n");
        sb.append("    }\n");
        sb.append("    if (!neko_patch_method_entry(method_star, entry)) {\n");
        sb.append("        entry->patch_state = NEKO_PATCH_STATE_FAILED;\n");
        sb.append("        return JNI_FALSE;\n");
        sb.append("    }\n");
        sb.append("    neko_manifest_register_method_star(idx, method_star);\n");
        sb.append("    entry->patch_state = NEKO_PATCH_STATE_APPLIED;\n");
        sb.append("    return JNI_TRUE;\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_manifest_class_matches(jclass owner_cls, const char *expected) {\n");
        sb.append("    void *klass;\n");
        sb.append("    void *name;\n");
        sb.append("    if (owner_cls == NULL || expected == NULL) return JNI_FALSE;\n");
        sb.append("    klass = neko_class_mirror_to_klass(owner_cls);\n");
        sb.append("    if (klass == NULL || g_neko_method_layout.off_klass_name < 0) return JNI_FALSE;\n");
        sb.append("    name = *(void**)((char*)klass + g_neko_method_layout.off_klass_name);\n");
        sb.append("    return neko_symbol_equals_utf8(name, expected);\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_manifest_patch_defined_class(JNIEnv *env, jclass owner_cls) {\n");
        sb.append("    if (env == NULL || owner_cls == NULL || g_neko_manifest_method_count == 0u) return JNI_TRUE;\n");
        for (Map.Entry<String, List<Integer>> e : byOwner.entrySet()) {
            sb.append("    if (neko_manifest_class_matches(owner_cls, \"").append(escape(e.getKey())).append("\")) {\n");
            Integer bindId = ownerBindIds.get(e.getKey());
            if (bindId != null) {
                sb.append("        neko_bind_owner_").append(bindId).append("(env, owner_cls);\n");
            }
            for (int idx : e.getValue()) {
                sb.append("        if (!neko_manifest_resolve_one(env, ").append(idx).append("u, owner_cls))\n");
                sb.append("            neko_manifest_abort_patch_failure(&g_neko_manifest_methods[").append(idx).append("u], \"defineClass\");\n");
            }
            sb.append("        return JNI_TRUE;\n");
            sb.append("    }\n");
        }
        sb.append("    return JNI_TRUE;\n");
        sb.append("}\n\n");
        sb.append("static jboolean neko_manifest_discover_and_patch(JNIEnv *env) {\n");
        sb.append("    jclass owner_cls;\n");
        sb.append("    if (env == NULL || g_neko_manifest_method_count == 0u) return JNI_TRUE;\n");
        for (Map.Entry<String, List<Integer>> e : byOwner.entrySet()) {
            if (e.getKey().contains("$NekoLambda$")) {
                continue;
            }
            sb.append("    owner_cls = neko_resolve_class_mirror_with_env(env, \"").append(escape(e.getKey())).append("\", NULL, NULL);\n");
            sb.append("    if (owner_cls == NULL || neko_exception_check(env)) {\n");
            sb.append("        if (neko_exception_check(env)) neko_exception_clear(env);\n");
            sb.append("    } else {\n");
            Integer bindId = ownerBindIds.get(e.getKey());
            if (bindId != null) {
                sb.append("        /* Bind this owner's native class cache. */\n");
                sb.append("        neko_bind_owner_").append(bindId).append("(env, owner_cls);\n");
            }
            for (int idx : e.getValue()) {
                sb.append("        if (!neko_manifest_resolve_one(env, ").append(idx).append("u, owner_cls))\n");
                sb.append("            neko_manifest_abort_patch_failure(&g_neko_manifest_methods[").append(idx).append("u], \"JNI_OnLoad\");\n");
            }
            sb.append("    }\n");
        }
        sb.append("    return JNI_TRUE;\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
