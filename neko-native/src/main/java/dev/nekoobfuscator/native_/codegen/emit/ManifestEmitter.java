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
 *   - {@code FindClass}'es each owner once,
 *   - resolves each method's {@code jmethodID} and derives the Method* via
 *     {@code *(Method**)mid},
 *   - calls {@code neko_patch_method_entry} (defined in {@link MethodPatcherEmitter}).
 *
 * No JVM-side helpers are used: the discovery driver uses only standard JNI primitives
 * and HotSpot's stable {@code jmethodID -> Method*} representation.
 */
public final class ManifestEmitter {

    public String renderStructAndForwardDecls() {
        StringBuilder sb = new StringBuilder();
        sb.append("static void neko_bootstrap_owner_discovery(JNIEnv *env);\n");
        sb.append("static void neko_manifest_discover_and_patch(JNIEnv *env);\n");
        sb.append("static void neko_manifest_patch_defined_class(JNIEnv *env, jclass owner_cls);\n");
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
                .append(b.isStatic() ? '1' : '0').append(", NEKO_PATCH_STATE_NONE, 0, 0 },\n");
        }
        if (bindings.isEmpty()) {
            sb.append("    { NULL, NULL, NULL, NULL, 0, 0, NEKO_PATCH_STATE_NONE, 0, 0 }\n");
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
        sb.append("static jboolean neko_manifest_resolve_one(JNIEnv *env, uint32_t idx, jclass owner_cls) {\n");
        sb.append("    NekoManifestMethod *entry;\n");
        sb.append("    jmethodID mid;\n");
        sb.append("    void *method_star;\n");
        sb.append("    if (env == NULL || idx >= g_neko_manifest_method_count) return JNI_FALSE;\n");
        sb.append("    entry = &g_neko_manifest_methods[idx];\n");
        sb.append("    mid = entry->is_static\n");
        sb.append("        ? neko_get_static_method_id(env, owner_cls, entry->method_name, entry->method_desc)\n");
        sb.append("        : neko_get_method_id(env, owner_cls, entry->method_name, entry->method_desc);\n");
        sb.append("    if (mid == NULL || neko_exception_check(env)) {\n");
        sb.append("        if (neko_exception_check(env)) neko_exception_clear(env);\n");
        sb.append("        return JNI_FALSE;\n");
        sb.append("    }\n");
        sb.append("    method_star = neko_jmethodid_to_method_star(mid);\n");
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
        sb.append("static jboolean neko_manifest_internal_name(JNIEnv *env, jclass owner_cls, char *out, size_t out_size) {\n");
        sb.append("    jclass class_cls;\n");
        sb.append("    jmethodID get_name;\n");
        sb.append("    jstring name_obj;\n");
        sb.append("    const char *chars;\n");
        sb.append("    size_t i;\n");
        sb.append("    if (env == NULL || owner_cls == NULL || out == NULL || out_size == 0u) return JNI_FALSE;\n");
        sb.append("    out[0] = '\\0';\n");
        sb.append("    class_cls = neko_find_class(env, \"java/lang/Class\");\n");
        sb.append("    if (class_cls == NULL || neko_exception_check(env)) { if (neko_exception_check(env)) neko_exception_clear(env); return JNI_FALSE; }\n");
        sb.append("    get_name = neko_get_method_id(env, class_cls, \"getName\", \"()Ljava/lang/String;\");\n");
        sb.append("    neko_delete_local_ref(env, class_cls);\n");
        sb.append("    if (get_name == NULL || neko_exception_check(env)) { if (neko_exception_check(env)) neko_exception_clear(env); return JNI_FALSE; }\n");
        sb.append("    name_obj = (jstring)neko_call_object_method_a(env, owner_cls, get_name, NULL);\n");
        sb.append("    if (name_obj == NULL || neko_exception_check(env)) { if (neko_exception_check(env)) neko_exception_clear(env); return JNI_FALSE; }\n");
        sb.append("    chars = neko_get_string_utf_chars(env, name_obj);\n");
        sb.append("    if (chars == NULL || neko_exception_check(env)) { if (neko_exception_check(env)) neko_exception_clear(env); neko_delete_local_ref(env, name_obj); return JNI_FALSE; }\n");
        sb.append("    for (i = 0; i + 1u < out_size && chars[i] != '\\0'; i++) out[i] = chars[i] == '.' ? '/' : chars[i];\n");
        sb.append("    out[i] = '\\0';\n");
        sb.append("    neko_release_string_utf_chars(env, name_obj, chars);\n");
        sb.append("    neko_delete_local_ref(env, name_obj);\n");
        sb.append("    return out[0] != '\\0' ? JNI_TRUE : JNI_FALSE;\n");
        sb.append("}\n\n");
        sb.append("static void neko_manifest_patch_defined_class(JNIEnv *env, jclass owner_cls) {\n");
        sb.append("    char owner_name[512];\n");
        sb.append("    if (env == NULL || owner_cls == NULL || g_neko_manifest_method_count == 0u) return;\n");
        sb.append("    if (!neko_manifest_internal_name(env, owner_cls, owner_name, sizeof(owner_name))) return;\n");
        for (Map.Entry<String, List<Integer>> e : byOwner.entrySet()) {
            sb.append("    if (strcmp(owner_name, \"").append(escape(e.getKey())).append("\") == 0) {\n");
            Integer bindId = ownerBindIds.get(e.getKey());
            if (bindId != null) {
                sb.append("        neko_bind_owner_").append(bindId).append("(env, owner_cls);\n");
            }
            for (int idx : e.getValue()) {
                sb.append("        (void)neko_manifest_resolve_one(env, ").append(idx).append("u, owner_cls);\n");
            }
            sb.append("        return;\n");
            sb.append("    }\n");
        }
        sb.append("}\n\n");
        sb.append("static void neko_manifest_discover_and_patch(JNIEnv *env) {\n");
        sb.append("    jclass owner_cls;\n");
        sb.append("    if (env == NULL || g_neko_manifest_method_count == 0u) return;\n");
        for (Map.Entry<String, List<Integer>> e : byOwner.entrySet()) {
            sb.append("    owner_cls = neko_find_class(env, \"").append(escape(e.getKey())).append("\");\n");
            sb.append("    if (owner_cls == NULL || neko_exception_check(env)) {\n");
            sb.append("        if (neko_exception_check(env)) neko_exception_clear(env);\n");
            sb.append("    } else {\n");
            Integer bindId = ownerBindIds.get(e.getKey());
            if (bindId != null) {
                sb.append("        /* Bind this owner's per-class JNI cache (formerly via bindClass). */\n");
                sb.append("        neko_bind_owner_").append(bindId).append("(env, owner_cls);\n");
            }
            for (int idx : e.getValue()) {
                sb.append("        (void)neko_manifest_resolve_one(env, ").append(idx).append("u, owner_cls);\n");
            }
            sb.append("        neko_delete_local_ref(env, owner_cls);\n");
            sb.append("    }\n");
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
