package dev.nekoobfuscator.native_.codegen;

import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.core.ir.l3.CVariable;
import dev.nekoobfuscator.native_.codegen.emit.JniHandlesShimEmitter;
import dev.nekoobfuscator.native_.codegen.emit.JniOnLoadEmitter;
import dev.nekoobfuscator.native_.codegen.emit.ManifestEmitter;
import dev.nekoobfuscator.native_.codegen.emit.MethodPatcherEmitter;
import dev.nekoobfuscator.native_.codegen.emit.NativeToJavaInvokeEmitter;
import dev.nekoobfuscator.native_.codegen.emit.SignatureDispatcherEmitter;
import dev.nekoobfuscator.native_.codegen.emit.SignaturePlan;
import dev.nekoobfuscator.native_.codegen.emit.TrampolineEmitter;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.Type;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CCodeGenerator {
    @SuppressWarnings("unused")
    private final SymbolTableGenerator symbols;
    private final ManifestEmitter manifestEmitter = new ManifestEmitter();
    private final SignatureDispatcherEmitter signatureDispatcherEmitter = new SignatureDispatcherEmitter();
    private final TrampolineEmitter trampolineEmitter = new TrampolineEmitter();
    private final MethodPatcherEmitter methodPatcherEmitter = new MethodPatcherEmitter();
    private final JniHandlesShimEmitter jniHandlesShimEmitter = new JniHandlesShimEmitter();
    private final JniOnLoadEmitter jniOnLoadEmitter = new JniOnLoadEmitter();
    private final NativeToJavaInvokeEmitter nativeToJavaInvokeEmitter = new NativeToJavaInvokeEmitter();
    private final LinkedHashMap<String, Integer> classSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> methodSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> fieldSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> ownerBindIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, OwnerResolution> ownerResolutions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> icacheMethodIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheSiteRef> icacheSites = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheDirectStubRef> icacheDirectStubs = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheMetaRef> icacheMetas = new LinkedHashMap<>();
    private int stringCacheCount;

    public CCodeGenerator(long masterSeed) {
        this.symbols = new SymbolTableGenerator(masterSeed);
    }

    public void configureStringCacheCount(int stringCacheCount) {
        this.stringCacheCount = stringCacheCount;
    }

    public int internClass(String internalName) {
        return classSlotIndex.computeIfAbsent(internalName, ignored -> classSlotIndex.size());
    }

    public int internMethod(String owner, String name, String desc, boolean isStatic) {
        String key = owner + "." + name + desc + "/" + (isStatic ? "S" : "V");
        return methodSlotIndex.computeIfAbsent(key, ignored -> methodSlotIndex.size());
    }

    public int internField(String owner, String name, String desc, boolean isStatic) {
        String key = owner + "." + name + desc + "/" + (isStatic ? "S" : "I");
        return fieldSlotIndex.computeIfAbsent(key, ignored -> fieldSlotIndex.size());
    }

    public String classSlotName(String internalName) {
        return "g_cls_" + internClass(internalName);
    }

    public String objectArrayKlassBitsSlotName(String internalName) {
        return "g_obj_array_klass_" + internClass(internalName);
    }

    public String classInitializedSlotName(String internalName) {
        return "g_cls_initialized_" + internClass(internalName);
    }

    public String methodSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mid_" + internMethod(owner, name, desc, isStatic);
    }

    public String methodPtrSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mptr_" + internMethod(owner, name, desc, isStatic);
    }

    public String methodCEntrySlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mcentry_" + internMethod(owner, name, desc, isStatic);
    }

    public String methodIEntrySlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mientry_" + internMethod(owner, name, desc, isStatic);
    }

    public String methodHolderSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mholder_" + internMethod(owner, name, desc, isStatic);
    }

    public String fieldSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_fid_" + internField(owner, name, desc, isStatic);
    }

    public String fieldOffsetSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_off_" + internField(owner, name, desc, isStatic);
    }

    public String staticFieldOffsetSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_static_off_" + internField(owner, name, desc, isStatic);
    }

    public String staticFieldBaseSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_static_base_" + internField(owner, name, desc, isStatic);
    }

    public void registerBindingOwner(String ownerInternalName) {
        ownerBindIndex.computeIfAbsent(ownerInternalName, ignored -> ownerBindIndex.size());
        OwnerResolution resolution = ownerResolutions.computeIfAbsent(ownerInternalName, ignored -> new OwnerResolution());
        resolution.classes.add(ownerInternalName);
        internClass(ownerInternalName);
    }

    public void registerOwnerClassReference(String bindingOwner, String classOwner) {
        registerBindingOwner(bindingOwner);
        ownerResolutions.get(bindingOwner).classes.add(classOwner);
        internClass(classOwner);
    }

    public void registerOwnerMethodReference(String bindingOwner, String owner, String name, String desc, boolean isStatic) {
        registerOwnerClassReference(bindingOwner, owner);
        ownerResolutions.get(bindingOwner).methods.add(new MethodRef(owner, name, desc, isStatic));
        internMethod(owner, name, desc, isStatic);
    }

    public void registerOwnerFieldReference(String bindingOwner, String owner, String name, String desc, boolean isStatic) {
        registerOwnerClassReference(bindingOwner, owner);
        ownerResolutions.get(bindingOwner).fields.add(new FieldRef(owner, name, desc, isStatic));
        internField(owner, name, desc, isStatic);
    }

    public void registerOwnerStringReference(String bindingOwner, String value, String cacheVar) {
        registerBindingOwner(bindingOwner);
        ownerResolutions.get(bindingOwner).strings.add(new StringRef(cacheVar, value));
    }

    public void registerOwnerPrimitiveClassReference(String bindingOwner, String desc) {
        registerBindingOwner(bindingOwner);
        ownerResolutions.get(bindingOwner).primitiveClasses.add(desc);
        internClass(primitiveClassSlotKey(desc));
    }

    public String ownerStringBindCall(String bindingOwner) {
        registerBindingOwner(bindingOwner);
        return "neko_bind_owner_strings_" + ownerBindIndex.get(bindingOwner) + "(thread, env);";
    }

    public String primitiveClassSlotName(String desc) {
        return classSlotName(primitiveClassSlotKey(desc));
    }

    public String reserveInvokeCacheSite(String bindingOwner, String methodKey, int siteIndex) {
        String cacheMethodKey = bindingOwner + '#' + methodKey;
        String siteKey = cacheMethodKey + '#' + siteIndex;
        registerBindingOwner(bindingOwner);
        return icacheSites.computeIfAbsent(siteKey, ignored -> new IcacheSiteRef(
            ownerBindIndex.get(bindingOwner),
            icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()),
            siteIndex,
            bindingOwner,
            methodKey
        )).symbol();
    }

    public String reserveInvokeCacheDirectStub(
        String bindingOwner,
        String methodKey,
        int siteIndex,
        NativeMethodBinding binding,
        Type[] args,
        Type returnType
    ) {
        String cacheMethodKey = bindingOwner + '#' + methodKey;
        String siteKey = cacheMethodKey + '#' + siteIndex;
        registerBindingOwner(bindingOwner);
        return icacheDirectStubs.computeIfAbsent(siteKey, ignored -> new IcacheDirectStubRef(
            ownerBindIndex.get(bindingOwner),
            icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()),
            siteIndex,
            binding,
            args.clone(),
            returnType
        )).symbol();
    }

    /** Register an invoke-callee shape with the native→Java dispatcher emitter.
     * Returns the C dispatcher symbol that call sites can use as a function
     * pointer. Idempotent across duplicate shapes. */
    public String registerInvokeShape(SignaturePlan.Shape shape) {
        return nativeToJavaInvokeEmitter.register(shape);
    }

    /** Returns the dispatcher symbol for the given (isStatic, ret, args)
     * tuple, registering it if not already present. Convenience for callers
     * that don't already have a SignaturePlan.Shape on hand. */
    public String registerInvokeShape(boolean isStatic, char returnKind, char[] argKinds) {
        return nativeToJavaInvokeEmitter.register(SignaturePlan.Shape.of(returnKind, argKinds, isStatic));
    }

    private void registerPrimitiveBoxingInvokeShapes() {
        registerInvokeShape(true, 'L', new char[] { 'I' });
        registerInvokeShape(true, 'L', new char[] { 'J' });
        registerInvokeShape(true, 'L', new char[] { 'F' });
        registerInvokeShape(true, 'L', new char[] { 'D' });
        /* TLAB-NULL fix: ensure neko_njx_V_L_L (instance, return L, args [L])
         * is always emitted so the String.concat NJX fallback path in
         * neko_require_fast_string_concat compiles even when obfuscated
         * bytecode happens not to need this shape on its own. */
        registerInvokeShape(false, 'L', new char[] { 'L' });
    }

    public String reserveInvokeCacheMeta(
        String bindingOwner,
        String methodKey,
        int siteIndex,
        String name,
        String desc,
        boolean isInterface,
        String translatedClassSlot,
        String translatedStubSymbol,
        String directDispatcherSymbol
    ) {
        String cacheMethodKey = bindingOwner + '#' + methodKey;
        String siteKey = cacheMethodKey + '#' + siteIndex;
        registerBindingOwner(bindingOwner);
        return icacheMetas.computeIfAbsent(siteKey, ignored -> new IcacheMetaRef(
            ownerBindIndex.get(bindingOwner),
            icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()),
            siteIndex,
            name,
            desc,
            isInterface,
            translatedClassSlot,
            translatedStubSymbol,
            directDispatcherSymbol
        )).symbol();
    }

    public String generateHeader(List<NativeMethodBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("#ifndef NEKO_NATIVE_H\n");
        sb.append("#define NEKO_NATIVE_H\n\n");
        sb.append("#include <jni.h>\n\n");
        sb.append("\n#endif\n");
        return sb.toString();
    }

    public String generateSource(List<CFunction> functions, List<NativeMethodBinding> bindings) {
        StringBuilder body = new StringBuilder();
        for (CFunction function : functions) {
            body.append(renderRawFunction(function)).append("\n");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#define _POSIX_C_SOURCE 199309L\n");
        sb.append("#include \"neko_native.h\"\n");
        sb.append("#include <stddef.h>\n");
        sb.append("#include <stdint.h>\n");
        sb.append("#include <stdio.h>\n");
        sb.append("#include <stdlib.h>\n");
        sb.append("#include <string.h>\n");
        sb.append("#include <math.h>\n");
        sb.append("#include <time.h>\n\n");
        sb.append("static void neko_post_blocking_call_yield(void) {\n");
        sb.append("#if defined(_WIN32)\n");
        sb.append("    /* Windows backend does not use this helper yet. */\n");
        sb.append("#else\n");
        sb.append("    struct timespec ts; ts.tv_sec = 0; ts.tv_nsec = 1000000L;\n");
        sb.append("    nanosleep(&ts, NULL);\n");
        sb.append("#endif\n");
        sb.append("}\n\n");
        SignaturePlan signaturePlan = SignaturePlan.build(bindings);
        registerPrimitiveBoxingInvokeShapes();
        /* Forward decls + manifest struct first; everything below references them. */
        sb.append(manifestEmitter.renderStructAndForwardDecls());
        sb.append("/* Forward decls for trampoline ↔ patcher coupling. */\n");
        sb.append("struct neko_sig_entry { void *i2i; void *c2i; };\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern const struct neko_sig_entry g_neko_sig_table[];\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern const uint32_t g_neko_sig_table_count;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern const uint32_t g_neko_sig_extraspace_words[];\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void * const g_neko_sig_i2i_path2[];\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_thread_state;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_thread_state_in_java;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_thread_state_in_native;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_thread_state_in_native_trans;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_thread_polling_word;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern jboolean  g_neko_thread_state_ready;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_last_Java_sp;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_last_Java_fp;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_last_Java_pc;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern jboolean  g_neko_frame_anchor_ready;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern jboolean  g_neko_handle_push_ready;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern jboolean  g_neko_native_resolution_ready;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern jboolean  g_neko_gc_barrier_ready;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_gc_barrier_kind;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_addr_compressed_oops_base;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_addr_compressed_oops_shift;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_addr_compressed_klass_base;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_addr_compressed_klass_shift;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_load_oop_field_preloaded;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_load_oop_array;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_store_oop_field;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_write_ref_array_pre;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_write_ref_field_pre;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_write_ref_field_post;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_card_table_byte_map_base;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_card_table_dirty_card;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_card_table_clean_card;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_g1_young_card;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_card_table_shift;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_thread_pending_exception;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) void neko_handle_safepoint_poll(void);\n");
        /* The save/restore/push helpers and neko_thread_jni_env / neko_jni_env_to_thread
         * are defined as `static inline __attribute__((always_inline))` further
         * down (inside the methodPatcherEmitter region). No extern declarations
         * here — extern would shadow the inline definitions and force the
         * dispatcher to make a real function call. */
        sb.append("typedef struct { void *thread; void *block; void *saved_next; void *saved_last; int32_t saved_top; } neko_handle_save_t;\n");
        /* T4.6 — neko_handle_save / _restore are defined inline in
         * methodPatcherEmitter (rendered later). The T4.6 window primitives
         * in JniHandlesShimEmitter (rendered earlier) call into them, so
         * forward-declare here. */
        sb.append("static inline __attribute__((always_inline)) void neko_handle_save(void *thread, neko_handle_save_t *save);\n");
        sb.append("static inline __attribute__((always_inline)) void neko_handle_restore(neko_handle_save_t *save);\n");
        sb.append("static jboolean neko_resolve_jnihandles(void *jvm);\n");
        sb.append("static void *neko_dlsym(void *h, const char *name);\n");
        sb.append("static void *neko_class_mirror_to_klass(jclass mirror);\n");
        sb.append("static void *neko_object_handle_klass(jobject obj);\n");
        sb.append("static void *neko_resolve_method(void *instance_klass, const char *name_utf8, const char *sig_utf8);\n");
        sb.append("static void neko_link_class_methods(JNIEnv *env, jclass cls, const char *owner, const char *name, const char *desc);\n");
        sb.append("static jobject neko_klass_java_mirror_handle(void *thread, void *klass);\n");
        sb.append("static uintptr_t neko_klass_header_bits(void *klass);\n");
        sb.append("static void *neko_decode_klass_header_bits(uintptr_t bits);\n");
        sb.append("static void neko_njx_init_wrappers(void);\n\n");
        /* T4.7: neko_fast_string_runtime_init forward decl removed; the
         * probe function is deleted and neko_method_layout_init now drives
         * the VMStructs path neko_ensure_string_alloc_bits directly. */
        sb.append("static void neko_ensure_string_alloc_bits(JNIEnv *env);\n");
        /* TLAB-NULL fix: cache helper for the String.concat NJX fallback. */
        sb.append("static void neko_ensure_string_concat_njx_cache(JNIEnv *env, void *string_klass);\n");
        sb.append("static void neko_ensure_unsafe_allocate_instance_njx_cache(JNIEnv *env);\n");
        /* T4.8: captured JNI NewGlobalRef / DeleteGlobalRef function pointers,
         * populated once at JNI_OnLoad (see JniHandlesShimEmitter). Bind-time
         * global-ref allocation/release routes through these typed pointers
         * instead of inline JNI function-table indexing for indices 21 / 22.
         * Production HotSpot 21 strips the C++ `JNIHandles::make_global`
         * symbol so plain dlsym is unavailable; capturing the function-table
         * entry once is the equivalent libjvm-internal entry point. */
        sb.append("typedef jobject (*neko_jni_new_global_ref_fn_t)(JNIEnv*, jobject);\n");
        sb.append("typedef void    (*neko_jni_delete_global_ref_fn_t)(JNIEnv*, jobject);\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_new_global_ref_fn_t   g_neko_jni_new_global_ref_fn;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_delete_global_ref_fn_t g_neko_jni_delete_global_ref_fn;\n");
        sb.append("static void neko_capture_global_ref_fns(void);\n");
        /* T4.3 / T4.4 / T4.5 — captured pointers for the bind-time MethodType /
         * MethodHandles.lookup / Throwable.getStackTrace helpers (production
         * HotSpot 21 strips the C++ symbols, so dlsym is unreachable). The
         * actual pointer storage lives in JniHandlesShimEmitter. */
        sb.append("typedef void      (*neko_jni_delete_local_ref_fn_t)(JNIEnv*, jobject);\n");
        sb.append("typedef jstring   (*neko_jni_new_string_utf_fn_t)(JNIEnv*, const char*);\n");
        sb.append("typedef jobject   (*neko_jni_call_object_method_a_fn_t)(JNIEnv*, jobject, jmethodID, const jvalue*);\n");
        sb.append("typedef void      (*neko_jni_call_void_method_a_fn_t)(JNIEnv*, jobject, jmethodID, const jvalue*);\n");
        sb.append("typedef jobject   (*neko_jni_call_static_object_method_a_fn_t)(JNIEnv*, jclass, jmethodID, const jvalue*);\n");
        sb.append("typedef jobject   (*neko_jni_new_object_a_fn_t)(JNIEnv*, jclass, jmethodID, const jvalue*);\n");
        sb.append("typedef jsize     (*neko_jni_get_array_length_fn_t)(JNIEnv*, jarray);\n");
        sb.append("typedef jobjectArray (*neko_jni_new_object_array_fn_t)(JNIEnv*, jsize, jclass, jobject);\n");
        sb.append("typedef void      (*neko_jni_set_object_array_element_fn_t)(JNIEnv*, jobjectArray, jsize, jobject);\n");
        sb.append("typedef jobject   (*neko_jni_get_object_array_element_fn_t)(JNIEnv*, jobjectArray, jsize);\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_delete_local_ref_fn_t            g_neko_jni_delete_local_ref_fn;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_new_string_utf_fn_t              g_neko_jni_new_string_utf_fn;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_call_object_method_a_fn_t        g_neko_jni_call_object_method_a_fn;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_call_void_method_a_fn_t          g_neko_jni_call_void_method_a_fn;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_call_static_object_method_a_fn_t g_neko_jni_call_static_object_method_a_fn;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_new_object_a_fn_t                g_neko_jni_new_object_a_fn;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_get_array_length_fn_t            g_neko_jni_get_array_length_fn;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_new_object_array_fn_t            g_neko_jni_new_object_array_fn;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_set_object_array_element_fn_t    g_neko_jni_set_object_array_element_fn;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern neko_jni_get_object_array_element_fn_t    g_neko_jni_get_object_array_element_fn;\n");
        sb.append("static void neko_capture_bind_time_jni_fns(void);\n");
        sb.append("static void neko_boxing_cache_init(JNIEnv *env);\n\n");
        /* T4.1: cross-block forward declarations for the primitive descriptor →
         * mirror table. Storage lives in renderRuntimeSupport (so the inline
         * neko_class_for_descriptor switch arms can read it without
         * cross-block extern hoops); the populating init function and the
         * wrapper-class TYPE-field read live in renderHotSpotFastAccessHelpers
         * where the g_hotspot compressed-oops state and the neko_decode_narrow_oop
         * / neko_barrier_load_oop_field helpers are visible. The init function is
         * driven by JniOnLoadEmitter once neko_hotspot_init has populated the
         * compressed-oops fields of g_hotspot. */
        sb.append("static void neko_primitive_mirror_table_init(JNIEnv *env);\n");
        sb.append("static jclass neko_primitive_mirror_for_char(JNIEnv *env, char tag);\n\n");
        sb.append("static uintptr_t neko_array_klass_bits_for_descriptor(JNIEnv *env, const char *arrayDesc, jclass fromClass);\n");
        sb.append("static jobject neko_fast_alloc_object(void *thread, JNIEnv *env, jclass cls);\n");
        sb.append("static jobjectArray neko_fast_new_object_array(void *thread, JNIEnv *env, jint len, uintptr_t klass_bits, jobject init);\n");
        sb.append("static jarray neko_fast_new_primitive_array(void *thread, JNIEnv *env, jint len, int kind);\n");
        sb.append("static void neko_fast_aastore(void *thread, JNIEnv *env, jobjectArray arr, jint idx, jobject val);\n\n");
        sb.append("static void neko_refill_tlab_with_slow_byte_array(JNIEnv *env, jint min_payload_len);\n");
        sb.append("static char *neko_alloc_jbyte_array_oop_slow(JNIEnv *env, jint len, jarray *local_ref_out);\n\n");
        sb.append(renderResolutionCaches());
        sb.append(renderRawFunctionPrototypes(bindings));
        sb.append(nativeToJavaInvokeEmitter.renderPrelude());
        sb.append(renderRuntimeSupport());
        sb.append(renderHotSpotSupport());
        sb.append(jniHandlesShimEmitter.render());
        sb.append(methodPatcherEmitter.render());
        /* Fast AALOAD helper: piggy-backs on methodPatcherEmitter's inline
         * neko_handle_push, so it has to come right after. Available to every
         * impl_fn / export wrapper / dispatcher emitted below. */
        sb.append(renderObjectArrayFastHelpers());
        /* Native→Java direct invoke dispatcher bodies — must come AFTER
         * methodPatcherEmitter (uses g_neko_call_stub_entry / g_neko_off_*
         * globals it publishes) and AFTER renderObjectArrayFastHelpers
         * (uses neko_direct_oop_to_handle for object-return marshalling). */
        sb.append(nativeToJavaInvokeEmitter.renderBodies());
        sb.append(nativeToJavaInvokeEmitter.renderInitFunction());
        sb.append(renderBindSupport());
        /* T4.2a — emit the tolerant class-mirror resolver right after
         * renderBindSupport so it can reuse the resolver typedefs and
         * neko_resolve_loaded_class_by_name / neko_jni_env_to_thread /
         * neko_klass_java_mirror_handle helpers defined there.
         *
         * Kept in its own method to avoid pushing renderBindSupport's text
         * block over the JVM 65535-byte string-literal constant pool limit
         * (renderBindSupport is already ~1500 lines of inlined C). */
        sb.append(renderTolerantClassResolver());
        /* TLAB-NULL fix: emit the String.concat NJX cache helper after
         * renderBindSupport so it can call neko_resolve_method and
         * neko_bound_method_i_entry directly. */
        sb.append(renderStringConcatNjxCache());
        /* T4.2b — emit the strict klass-based jmethodID resolver in its
         * own helper; covers both static and instance methods because
         * neko_resolve_method does not distinguish by access flags. */
        sb.append(renderResolveJMethodID());
        /* T4.1 — emit AFTER renderBindSupport so the init function can use
         * neko_resolve_class_with_env, neko_resolve_field, and the
         * neko_field_resolution_t typedef defined there. */
        sb.append(renderPrimitiveMirrorSupport());
        sb.append(renderBoxingSupport());
        sb.append(jniOnLoadEmitter.renderRegistrationTable());
        sb.append(renderBindOwnerFunctions());
        sb.append(renderIcacheDirectStubs());
        sb.append(renderIcacheMetas());
        sb.append(body);
        sb.append(manifestEmitter.renderTables(bindings, signaturePlan));
        sb.append(signatureDispatcherEmitter.render(signaturePlan));
        sb.append(trampolineEmitter.render(signaturePlan));
        sb.append(manifestEmitter.renderDiscoveryDriver(bindings, ownerBindIndex));
        sb.append(jniOnLoadEmitter.renderJniOnLoadAndBootstrap());
        return sb.toString();
    }


    private String renderRawFunction(CFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append("static ").append(fn.returnType().jniName()).append(' ').append(fn.name()).append('(');
        for (int i = 0; i < fn.params().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(renderParam(fn.params().get(i)));
        }
        sb.append(") {\n");
        sb.append("    neko_slot stack[").append(fn.maxStack() + 16).append("];\n");
        sb.append("    int sp = 0;\n");
        sb.append("    neko_monitor_record monitors[").append(fn.maxStack() + 16).append("];\n");
        sb.append("    int monitor_sp = 0;\n");
        /* Locals are uninitialized at function entry (Java spec requires
         * every local be assigned before read). Skipping the memset saves
         * 26+ qword-stores per call on every translated method. The stack
         * slots are also uninitialized — POP only ever returns previously
         * PUSH'd values per JVM verifier guarantees. */
        sb.append("    neko_slot locals[").append(fn.maxLocals() + 8).append("];\n");
        for (CStatement statement : fn.body()) {
            sb.append(renderStatement(statement));
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String renderRawFunctionPrototypes(List<NativeMethodBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("// === Raw translated-body prototypes ===\n");
        for (NativeMethodBinding binding : bindings) {
            sb.append("static ").append(jniType(Type.getReturnType(binding.descriptor()))).append(' ')
                .append(binding.rawFunctionName()).append("(void *thread, JNIEnv *env, ")
                .append(binding.isStatic() ? "jclass clazz" : "jobject self");
            Type[] args = Type.getArgumentTypes(binding.descriptor());
            for (int i = 0; i < args.length; i++) {
                sb.append(", ").append(jniType(args[i])).append(" p").append(i);
            }
            sb.append(");\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderStatement(CStatement statement) {
        if (statement instanceof CStatement.RawC raw) {
            return "    " + raw.code() + "\n";
        }
        if (statement instanceof CStatement.Label label) {
            return label.name() + ": ;\n";
        }
        if (statement instanceof CStatement.Goto go) {
            return "    goto " + go.label() + ";\n";
        }
        if (statement instanceof CStatement.ReturnVoid) {
            return "    return;\n";
        }
        if (statement instanceof CStatement.Return ret) {
            return "    return " + ret.value() + ";\n";
        }
        if (statement instanceof CStatement.Comment comment) {
            return "    /* " + comment.text() + " */\n";
        }
        throw new IllegalStateException("Unsupported C statement in generator: " + statement.getClass().getSimpleName());
    }

    private String renderResolutionCaches() {
        StringBuilder sb = new StringBuilder();
        sb.append("// === Global resolution caches ===\n");
        sb.append("#define NEKO_ICACHE_PIC_SIZE 4u\n");
        sb.append("typedef struct neko_icache_site {\n");
        sb.append("    uintptr_t receiver_key[NEKO_ICACHE_PIC_SIZE];\n");
        sb.append("    void* target[NEKO_ICACHE_PIC_SIZE];\n");
        sb.append("    void* target2[NEKO_ICACHE_PIC_SIZE];\n");
        sb.append("    jclass cached_class[NEKO_ICACHE_PIC_SIZE];\n");
        sb.append("    uint8_t target_kind[NEKO_ICACHE_PIC_SIZE];\n");
        sb.append("    uint8_t next_slot;\n");
        sb.append("    uint16_t miss_count;\n");
        sb.append("    /* legacy icache field names: receiver_key; target; target_kind; cached_class; */\n");
        /* For NEKO_ICACHE_DIRECT_NJX: target = HotSpot Method* (resolved
         * from the receiver-class-specific jmethodID), target2 = the
         * cached _from_compiled_entry pointer. The dispatcher itself
         * comes from neko_icache_meta::direct_dispatcher (shape-specific,
         * known at codegen time). */
        sb.append("} neko_icache_site;\n\n");
        sb.append("#define NEKO_ICACHE_EMPTY 0u\n");
        sb.append("#define NEKO_ICACHE_DIRECT_C 1u\n");
        sb.append("#define NEKO_ICACHE_MEGA 3u\n");
        sb.append("#define NEKO_ICACHE_DIRECT_NJX 4u\n");
        sb.append("#define NEKO_ICACHE_MEGA_THRESHOLD 16u\n\n");
        for (Map.Entry<String, Integer> entry : classSlotIndex.entrySet()) {
            sb.append("static jclass g_cls_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
            sb.append("static uintptr_t g_obj_array_klass_").append(entry.getValue()).append(" = 0;\n");
            sb.append("static volatile jboolean g_cls_initialized_").append(entry.getValue()).append(" = JNI_FALSE;\n");
        }
        for (Map.Entry<String, Integer> entry : methodSlotIndex.entrySet()) {
            sb.append("static jmethodID g_mid_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
            /* HotSpot Method* + cached entry pointers — populated at JNI_OnLoad
             * by neko_bind_method_slot once the layout walker has resolved the
             * Method::_from_compiled_entry / _from_interpreted_entry offsets.
             * Reads on the hot path are direct memory loads; writes are
             * single-pointer stores so torn reads are impossible on x86-64
             * and AArch64 (8-byte aligned, naturally atomic). */
            sb.append("static void* g_mptr_").append(entry.getValue()).append(" = NULL;\n");
            sb.append("static void* g_mcentry_").append(entry.getValue()).append(" = NULL;\n");
            sb.append("static void* g_mientry_").append(entry.getValue()).append(" = NULL;\n");
            sb.append("static void* g_mholder_").append(entry.getValue()).append(" = NULL;\n");
        }
        for (Map.Entry<String, Integer> entry : fieldSlotIndex.entrySet()) {
            sb.append("static jfieldID g_fid_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
            sb.append("static jlong g_off_").append(entry.getValue()).append(" = -1;\n");
            sb.append("static jlong g_static_off_").append(entry.getValue()).append(" = -1;\n");
            sb.append("static jobject g_static_base_").append(entry.getValue()).append(" = NULL;\n");
        }
        for (int i = 0; i < stringCacheCount; i++) {
            sb.append("static jstring g_str_").append(i).append(" = NULL;\n");
        }
        for (Map.Entry<String, Integer> entry : ownerBindIndex.entrySet()) {
            sb.append("static jboolean g_owner_bound_").append(entry.getValue()).append(" = JNI_FALSE;   // ").append(entry.getKey()).append("\n");
            sb.append("static jboolean g_owner_strings_bound_").append(entry.getValue()).append(" = JNI_FALSE;   // ").append(entry.getKey()).append("\n");
        }
        for (IcacheSiteRef site : icacheSites.values()) {
            sb.append("static neko_icache_site ").append(site.symbol()).append(" = {0};   // ")
                .append(site.bindingOwner()).append(" :: ").append(site.methodKey()).append(" [site ")
                .append(site.siteIndex()).append("]\n");
        }
        sb.append("\n");
        sb.append("static jclass neko_ensure_class_slot(jclass *slot, JNIEnv *env, const char *name);\n");
        sb.append("static jstring neko_ensure_string_slot(jstring *slot, JNIEnv *env, const char *utf);\n");
        sb.append("static jmethodID neko_ensure_method_id_slot(jmethodID *slot, JNIEnv *env, jclass cls, const char *name, const char *desc, jboolean isStatic);\n");
        sb.append("static jfieldID neko_ensure_field_id_slot(jfieldID *slot, JNIEnv *env, jclass cls, const char *name, const char *desc, jboolean isStatic);\n");
        sb.append("static jclass neko_resolve_class_mirror_with_env(JNIEnv *env, const char *utf8, jclass from_class, void **klass_out);\n");
        sb.append("static jclass neko_try_resolve_class_mirror_with_env(JNIEnv *env, const char *utf8, jclass from_class);\n");
        sb.append("static jmethodID neko_resolve_jmethodID(JNIEnv *env, jclass cls, const char *name, const char *sig);\n");
        sb.append("static jmethodID neko_resolve_jmethodID_with_kind(JNIEnv *env, jclass cls, const char *name, const char *sig, jboolean is_static);\n");
        sb.append("static void *neko_resolve_method_star_with_kind(JNIEnv *env, jclass cls, const char *name, const char *sig, jboolean is_static);\n");
        /* T4.2c — the new neko_impl_lookup body lives in renderResolveJMethodID()
         * (alongside the T4.2b helpers); the forward declaration in
         * renderRuntimeSupport keeps the existing in-block neko_lookup_for_jclass
         * call site resolving. */
        sb.append("#define NEKO_ENSURE_CLASS(slot, env, name) neko_ensure_class_slot(&(slot), (env), (name))\n");
        sb.append("#define NEKO_ENSURE_STRING(slot, env, utf) neko_ensure_string_slot(&(slot), (env), (utf))\n");
        sb.append("#define NEKO_ENSURE_METHOD_ID(slot, env, cls, name, desc) neko_ensure_method_id_slot(&(slot), (env), (cls), (name), (desc), JNI_FALSE)\n");
        sb.append("#define NEKO_ENSURE_STATIC_METHOD_ID(slot, env, cls, name, desc) neko_ensure_method_id_slot(&(slot), (env), (cls), (name), (desc), JNI_TRUE)\n");
        sb.append("#define NEKO_ENSURE_FIELD_ID(slot, env, cls, name, desc) neko_ensure_field_id_slot(&(slot), (env), (cls), (name), (desc), JNI_FALSE)\n");
        sb.append("#define NEKO_ENSURE_STATIC_FIELD_ID(slot, env, cls, name, desc) neko_ensure_field_id_slot(&(slot), (env), (cls), (name), (desc), JNI_TRUE)\n\n");
        return sb.toString();
    }

    private String renderBindSupport() {
        return """
typedef jclass (*neko_jvm_find_class_boot_t)(JNIEnv*, const char*);
typedef jclass (*neko_jvm_find_class_from_class_t)(JNIEnv*, const char*, jboolean, jclass);
typedef jstring (*neko_jvm_intern_string_t)(JNIEnv*, jstring);
typedef jclass (*neko_jvm_find_primitive_class_t)(JNIEnv*, const char*);
typedef jarray (*neko_jvm_new_array_t)(JNIEnv*, jclass, jint);

typedef struct neko_class_klass_cache_node {
    jclass mirror;
    void *klass;
    struct neko_class_klass_cache_node *next;
} neko_class_klass_cache_node;

static neko_class_klass_cache_node *g_neko_class_klass_cache = NULL;

static void *neko_resolve_loaded_class_by_name(const char *utf8);

static void neko_remember_class_klass(jclass mirror, void *klass) {
    neko_class_klass_cache_node *node;
    if (mirror == NULL || klass == NULL) return;
    for (node = g_neko_class_klass_cache; node != NULL; node = node->next) {
        if (node->mirror == mirror) {
            node->klass = klass;
            return;
        }
    }
    node = (neko_class_klass_cache_node*)malloc(sizeof(*node));
    if (node == NULL) {
        fprintf(stderr, "[neko-bind] class cache allocation failed\\n");
        abort();
    }
    node->mirror = mirror;
    node->klass = klass;
    node->next = g_neko_class_klass_cache;
    g_neko_class_klass_cache = node;
}

static void *neko_class_mirror_to_klass(jclass mirror) {
    neko_class_klass_cache_node *node;
    void *mirror_oop;
    void *klass;
    if (mirror == NULL) return NULL;
    for (node = g_neko_class_klass_cache; node != NULL; node = node->next) {
        if (node->mirror == mirror) return node->klass;
    }
    if (g_neko_method_layout.off_java_lang_class_klass < 0) {
        fprintf(stderr, "[neko-bind] java.lang.Class::_klass offset unavailable\\n");
        abort();
    }
    mirror_oop = neko_handle_oop((jobject)mirror);
    if (mirror_oop == NULL) return NULL;
    klass = *(void**)((char*)mirror_oop + g_neko_method_layout.off_java_lang_class_klass);
    return klass;
}

static jobject neko_klass_java_mirror_handle(void *thread, void *klass) {
    void *mirror_handle;
    void *mirror_oop;
    if (klass == NULL) return NULL;
    if (g_neko_method_layout.off_klass_java_mirror < 0) {
        fprintf(stderr, "[neko-bind] Klass::_java_mirror offset unavailable\\n");
        abort();
    }
    mirror_handle = *(void**)((char*)klass + g_neko_method_layout.off_klass_java_mirror);
    if (mirror_handle == NULL) return NULL;
    mirror_oop = *(void**)mirror_handle;
    return mirror_oop != NULL ? neko_handle_push(thread, mirror_oop) : NULL;
}

static jclass neko_resolve_class_mirror_with_env(JNIEnv *env, const char *utf8, jclass from_class, void **klass_out) {
    jclass resolved = NULL;
    void *mirror_klass;
    void *klass;
    if (klass_out != NULL) *klass_out = NULL;
    if (env == NULL) {
        fprintf(stderr, "[neko-bind] native class resolution missing JNIEnv: %s\\n",
            utf8 == NULL ? "<null>" : utf8);
        abort();
    }
    if (utf8 == NULL || utf8[0] == '\\0') {
        fprintf(stderr, "[neko-bind] class resolution requested with empty name\\n");
        abort();
    }
    klass = neko_resolve_loaded_class_by_name(utf8);
    if (getenv("NEKO_PATCH_DEBUG") != NULL) {
        fprintf(stderr, "[neko-bind] class lookup start name=%s from=%p boot_sym=%p from_sym=%p\\n",
            utf8, (void*)from_class,
            g_neko_method_layout.sym_jvm_find_class_from_boot_loader,
            g_neko_method_layout.sym_jvm_find_class_from_class);
    }
    if (g_neko_method_layout.sym_jvm_find_class_from_boot_loader != NULL) {
        resolved = ((neko_jvm_find_class_boot_t)g_neko_method_layout.sym_jvm_find_class_from_boot_loader)(env, utf8);
        if (getenv("NEKO_PATCH_DEBUG") != NULL) {
            fprintf(stderr, "[neko-bind] class lookup boot result name=%s mirror=%p\\n", utf8, (void*)resolved);
        }
    }
    if (resolved == NULL && from_class != NULL && g_neko_method_layout.sym_jvm_find_class_from_class != NULL) {
        resolved = ((neko_jvm_find_class_from_class_t)g_neko_method_layout.sym_jvm_find_class_from_class)(
            env, utf8, JNI_FALSE, from_class);
        if (getenv("NEKO_PATCH_DEBUG") != NULL) {
            fprintf(stderr, "[neko-bind] class lookup from result name=%s mirror=%p\\n", utf8, (void*)resolved);
        }
    }
    if (resolved != NULL) {
        mirror_klass = NULL;
        if (g_neko_method_layout.off_java_lang_class_klass >= 0) {
            mirror_klass = neko_class_mirror_to_klass(resolved);
        }
        if (klass == NULL) {
            klass = mirror_klass;
        } else if (mirror_klass != NULL && mirror_klass != klass) {
            fprintf(stderr, "[neko-bind] native class mirror mismatch: %s loaded=%p mirror=%p\\n",
                utf8, klass, mirror_klass);
            abort();
        }
        if (klass == NULL) {
            fprintf(stderr, "[neko-bind] native class mirror did not map to Klass*: %s\\n", utf8);
            abort();
        }
        if (klass_out != NULL) *klass_out = klass;
        return resolved;
    }
    if (klass != NULL) {
        void *thread = neko_jni_env_to_thread(env);
        jclass mirror;
        if (thread == NULL) {
            fprintf(stderr, "[neko-bind] JavaThread unavailable for class mirror handle: %s\\n", utf8);
            abort();
        }
        mirror = (jclass)neko_klass_java_mirror_handle(thread, klass);
        if (mirror == NULL) {
            fprintf(stderr, "[neko-bind] native class mirror handle failed: %s\\n", utf8);
            abort();
        }
        if (klass_out != NULL) *klass_out = klass;
        return mirror;
    }
    fprintf(stderr, "[neko-bind] native class resolution failed: %s\\n", utf8);
    abort();
}

static void *neko_resolve_class_with_env(JNIEnv *env, const char *utf8, jclass from_class) {
    void *klass = NULL;
    (void)neko_resolve_class_mirror_with_env(env, utf8, from_class, &klass);
    return klass;
}

static void *neko_resolve_class_with_mirror(const char *utf8, jclass from_class) {
    void *thread = neko_current_thread_register();
    return neko_resolve_class_with_env(neko_thread_jni_env(thread), utf8, from_class);
}

static void *neko_resolve_class(const char *utf8) {
    return neko_resolve_class_with_mirror(utf8, NULL);
}

static void neko_ensure_class_initialized(JNIEnv *env, jclass cls, const char *owner) {
    jclass initialized;
    if (env == NULL || cls == NULL || owner == NULL) {
        fprintf(stderr, "[neko-bind] class initialization missing input: %s cls=%p\\n",
            owner == NULL ? "<null>" : owner,
            (void*)cls);
        abort();
    }
    if (g_neko_method_layout.sym_jvm_find_class_from_class == NULL) {
        fprintf(stderr, "[neko-bind] class initialization symbol unavailable: %s\\n", owner);
        abort();
    }
    initialized = ((neko_jvm_find_class_from_class_t)g_neko_method_layout.sym_jvm_find_class_from_class)(
        env, owner, JNI_TRUE, cls);
    if (initialized == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear_direct(env);
        fprintf(stderr, "[neko-bind] class initialization failed: %s\\n", owner);
        abort();
    }
    g_neko_jni_delete_local_ref_fn(env, initialized);
}

static void neko_ensure_class_initialized_once(JNIEnv *env, jclass cls, const char *owner, volatile jboolean *slot) {
    if (slot != NULL && *slot == JNI_TRUE) return;
    neko_ensure_class_initialized(env, cls, owner);
    if (slot != NULL) *slot = JNI_TRUE;
}

static uintptr_t neko_klass_header_bits(void *klass) {
    uintptr_t base;
    int shift;
    if (klass == NULL) return 0;
    if (g_hotspot.use_compressed_klass_ptrs) {
        if (g_neko_method_layout.addr_compressed_klass_base == NULL
            || g_neko_method_layout.addr_compressed_klass_shift == NULL) {
            fprintf(stderr, "[neko-bind] compressed Klass encoding layout unavailable\\n");
            abort();
        }
        base = (uintptr_t)(*(void**)g_neko_method_layout.addr_compressed_klass_base);
        shift = *(int*)g_neko_method_layout.addr_compressed_klass_shift;
        return (uintptr_t)(((uintptr_t)klass - base) >> shift);
    }
    return (uintptr_t)klass;
}

static void neko_ensure_string_alloc_bits(JNIEnv *env) {
    void *string_klass;
    if (g_neko_fast_string_alloc_ready) return;
    if (!g_hotspot.initialized
        || (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) == 0
        || g_hotspot.use_compact_object_headers
        || g_hotspot.klass_offset_bytes <= 0
        || !g_neko_tlab_alloc_ready
        || g_hotspot.primitive_array_klass_bits[NEKO_PRIM_B] == 0) {
        fprintf(stderr, "[neko-bind] direct String allocation layout unavailable\\n");
        abort();
    }
    string_klass = neko_resolve_class_with_env(env, "java/lang/String", NULL);
    g_neko_string_klass_bits = neko_klass_header_bits(string_klass);
    g_neko_byte_array_klass_bits = g_hotspot.primitive_array_klass_bits[NEKO_PRIM_B];
    g_neko_fast_string_alloc_ready =
        (g_neko_string_klass_bits != 0 && g_neko_byte_array_klass_bits != 0) ? JNI_TRUE : JNI_FALSE;
    if (!g_neko_fast_string_alloc_ready) {
        fprintf(stderr, "[neko-bind] direct String allocation klass bits unavailable\\n");
        abort();
    }
    /* TLAB-NULL fix: cache String.concat NJX dispatch metadata. */
    neko_ensure_string_concat_njx_cache(env, string_klass);
}

static jboolean neko_symbol_equals_utf8(void *symbol, const char *utf8) {
    uint16_t len;
    const char *body;
    if (symbol == NULL || utf8 == NULL) return JNI_FALSE;
    if (g_neko_method_layout.off_symbol_length < 0 || g_neko_method_layout.off_symbol_body < 0) {
        fprintf(stderr, "[neko-bind] Symbol layout unavailable\\n");
        abort();
    }
    len = *(uint16_t*)((char*)symbol + g_neko_method_layout.off_symbol_length);
    body = (const char*)symbol + g_neko_method_layout.off_symbol_body;
    return strlen(utf8) == (size_t)len && memcmp(body, utf8, len) == 0 ? JNI_TRUE : JNI_FALSE;
}

static void *neko_resolve_loaded_class_by_name(const char *utf8) {
    void *head;
    void *cld;
    if (utf8 == NULL) return NULL;
    if (g_neko_method_layout.addr_classloaderdatagraph_head == NULL
        || g_neko_method_layout.off_classloaderdata_next < 0
        || g_neko_method_layout.off_classloaderdata_klasses < 0
        || g_neko_method_layout.off_klass_next_link < 0
        || g_neko_method_layout.off_klass_name < 0) {
        fprintf(stderr, "[neko-bind] ClassLoaderDataGraph layout unavailable for class resolution: head=%p cld_next=%td cld_klasses=%td klass_next=%td klass_name=%td\\n",
            g_neko_method_layout.addr_classloaderdatagraph_head,
            g_neko_method_layout.off_classloaderdata_next,
            g_neko_method_layout.off_classloaderdata_klasses,
            g_neko_method_layout.off_klass_next_link,
            g_neko_method_layout.off_klass_name);
        abort();
    }
    head = *(void**)g_neko_method_layout.addr_classloaderdatagraph_head;
    cld = head;
    for (uint32_t cldDepth = 0; cld != NULL && cldDepth < 65536u; cldDepth++) {
        void *klass = *(void**)((char*)cld + g_neko_method_layout.off_classloaderdata_klasses);
        for (uint32_t klassDepth = 0; klass != NULL && klassDepth < 1048576u; klassDepth++) {
            void *name = *(void**)((char*)klass + g_neko_method_layout.off_klass_name);
            if (neko_symbol_equals_utf8(name, utf8)) {
                return klass;
            }
            klass = *(void**)((char*)klass + g_neko_method_layout.off_klass_next_link);
        }
        cld = *(void**)((char*)cld + g_neko_method_layout.off_classloaderdata_next);
    }
    return NULL;
}

static ptrdiff_t neko_constantpool_base_offset(void) {
    if (g_neko_method_layout.off_constantpool_base >= 0) {
        return g_neko_method_layout.off_constantpool_base;
    }
    if (g_neko_method_layout.sizeof_ConstantPool > 0) {
        return (ptrdiff_t)g_neko_method_layout.sizeof_ConstantPool;
    }
    if (g_neko_method_layout.off_constantpool_length < 0) {
        fprintf(stderr, "[neko-bind] ConstantPool base layout unavailable\\n");
        abort();
    }
    fprintf(stderr, "[neko-bind] ConstantPool base layout unavailable\\n");
    abort();
}

static void *neko_constantpool_symbol_at(void *constant_pool, uint16_t index) {
    int length;
    void **base;
    if (constant_pool == NULL) return NULL;
    if (g_neko_method_layout.off_constantpool_length < 0) {
        fprintf(stderr, "[neko-bind] ConstantPool symbol layout unavailable\\n");
        abort();
    }
    length = *(int*)((char*)constant_pool + g_neko_method_layout.off_constantpool_length);
    if (index == 0 || index >= (uint16_t)length) return NULL;
    base = (void**)((char*)constant_pool + neko_constantpool_base_offset());
    return base[index];
}

static void *neko_method_constmethod(void *method) {
    if (method == NULL) return NULL;
    if (g_neko_method_layout.off_method_constMethod < 0) {
        fprintf(stderr, "[neko-bind] Method::_constMethod layout unavailable\\n");
        abort();
    }
    return *(void**)((char*)method + g_neko_method_layout.off_method_constMethod);
}

typedef struct {
    jboolean found;
    jboolean is_static;
    uint32_t offset;
    uint32_t access_flags;
    void *holder_klass;
} neko_field_resolution_t;

typedef struct {
    const uint8_t *data;
    int length;
    int position;
} neko_u5_reader_t;

#define NEKO_JVM_ACC_STATIC 0x0008u
#define NEKO_FIELD_FLAG_INITIALIZED (1u << 0)
#define NEKO_FIELD_FLAG_INJECTED    (1u << 1)
#define NEKO_FIELD_FLAG_GENERIC     (1u << 2)
#define NEKO_FIELD_FLAG_CONTENDED   (1u << 4)
#define NEKO_FIELDINFO_LEGACY_SLOTS 6
#define NEKO_FIELDINFO_TAG_SIZE     2
#define NEKO_FIELDINFO_TAG_OFFSET   1u
#define NEKO_FIELDINFO_TAG_MASK     3u

static uint32_t neko_u5_next(neko_u5_reader_t *reader, const char *context) {
    uint32_t b0;
    uint32_t sum;
    int shift;
    if (reader == NULL || reader->data == NULL || reader->position < 0 || reader->position >= reader->length) {
        fprintf(stderr, "[neko-bind] truncated fieldinfo stream while reading %s\\n", context == NULL ? "<unknown>" : context);
        abort();
    }
    b0 = reader->data[reader->position];
    if (b0 < 1u) {
        fprintf(stderr, "[neko-bind] invalid unsigned5 zero byte while reading %s at %d\\n",
            context == NULL ? "<unknown>" : context, reader->position);
        abort();
    }
    sum = b0 - 1u;
    if (sum < 191u) {
        reader->position++;
        return sum;
    }
    shift = 6;
    for (int i = 1; i < 5; i++) {
        uint32_t bi;
        if (reader->position + i >= reader->length) {
            fprintf(stderr, "[neko-bind] truncated unsigned5 value while reading %s at %d\\n",
                context == NULL ? "<unknown>" : context, reader->position);
            abort();
        }
        bi = reader->data[reader->position + i];
        if (bi < 1u) {
            fprintf(stderr, "[neko-bind] invalid unsigned5 zero byte while reading %s at %d\\n",
                context == NULL ? "<unknown>" : context, reader->position + i);
            abort();
        }
        sum += (uint32_t)((bi - 1u) << shift);
        if (bi < 192u || i == 4) {
            reader->position += i + 1;
            return sum;
        }
        shift += 6;
    }
    fprintf(stderr, "[neko-bind] unsigned5 decode fell through while reading %s\\n", context == NULL ? "<unknown>" : context);
    abort();
}

static jboolean neko_match_field_symbols(void *constant_pool, uint32_t name_index, uint32_t sig_index, uint32_t field_flags, const char *name_utf8, const char *sig_utf8) {
    void *name_symbol;
    void *sig_symbol;
    if ((field_flags & NEKO_FIELD_FLAG_INJECTED) != 0) {
        return JNI_FALSE;
    }
    if (name_index > 0xffffu || sig_index > 0xffffu) {
        return JNI_FALSE;
    }
    name_symbol = neko_constantpool_symbol_at(constant_pool, (uint16_t)name_index);
    sig_symbol = neko_constantpool_symbol_at(constant_pool, (uint16_t)sig_index);
    return (neko_symbol_equals_utf8(name_symbol, name_utf8)
            && neko_symbol_equals_utf8(sig_symbol, sig_utf8))
        ? JNI_TRUE : JNI_FALSE;
}

static jboolean neko_resolve_declared_field_stream(void *instance_klass, const char *name_utf8, const char *sig_utf8, jboolean is_static, neko_field_resolution_t *out) {
    void *stream_array;
    void *constant_pool;
    int stream_length;
    uint32_t java_fields;
    uint32_t injected_fields;
    uint32_t total_fields;
    neko_u5_reader_t reader;
    if (instance_klass == NULL || out == NULL || g_neko_method_layout.off_instanceklass_fieldinfo_stream < 0) {
        return JNI_FALSE;
    }
    if (g_neko_method_layout.off_instanceklass_constants < 0
        || g_neko_method_layout.off_array_length < 0
        || g_neko_method_layout.off_array_u1_data < 0) {
        fprintf(stderr, "[neko-bind] InstanceKlass fieldinfo stream layout unavailable\\n");
        abort();
    }
    stream_array = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_fieldinfo_stream);
    constant_pool = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_constants);
    if (stream_array == NULL || constant_pool == NULL) {
        return JNI_FALSE;
    }
    stream_length = *(int*)((char*)stream_array + g_neko_method_layout.off_array_length);
    if (stream_length <= 0) {
        return JNI_FALSE;
    }
    reader.data = (const uint8_t*)stream_array + g_neko_method_layout.off_array_u1_data;
    reader.length = stream_length;
    reader.position = 0;
    java_fields = neko_u5_next(&reader, "java field count");
    injected_fields = neko_u5_next(&reader, "injected field count");
    total_fields = java_fields + injected_fields;
    if (total_fields > 65535u) {
        fprintf(stderr, "[neko-bind] unreasonable field count in fieldinfo stream: %u\\n", total_fields);
        abort();
    }
    for (uint32_t i = 0; i < total_fields; i++) {
        uint32_t name_index = neko_u5_next(&reader, "field name index");
        uint32_t sig_index = neko_u5_next(&reader, "field signature index");
        uint32_t offset = neko_u5_next(&reader, "field offset");
        uint32_t access_flags = neko_u5_next(&reader, "field access flags");
        uint32_t field_flags = neko_u5_next(&reader, "field flags");
        if ((field_flags & NEKO_FIELD_FLAG_INITIALIZED) != 0) (void)neko_u5_next(&reader, "field initializer index");
        if ((field_flags & NEKO_FIELD_FLAG_GENERIC) != 0) (void)neko_u5_next(&reader, "field generic signature index");
        if ((field_flags & NEKO_FIELD_FLAG_CONTENDED) != 0) (void)neko_u5_next(&reader, "field contention group");
        if (((access_flags & NEKO_JVM_ACC_STATIC) != 0) != (is_static == JNI_TRUE)) {
            continue;
        }
        if (neko_match_field_symbols(constant_pool, name_index, sig_index, field_flags, name_utf8, sig_utf8)) {
            out->found = JNI_TRUE;
            out->is_static = (access_flags & NEKO_JVM_ACC_STATIC) != 0 ? JNI_TRUE : JNI_FALSE;
            out->offset = offset;
            out->access_flags = access_flags;
            out->holder_klass = instance_klass;
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

static jboolean neko_resolve_declared_field_legacy(void *instance_klass, const char *name_utf8, const char *sig_utf8, jboolean is_static, neko_field_resolution_t *out) {
    void *fields_array;
    void *constant_pool;
    int shorts_length;
    uint16_t *fields_data;
    if (instance_klass == NULL || out == NULL || g_neko_method_layout.off_instanceklass_fields < 0) {
        return JNI_FALSE;
    }
    if (g_neko_method_layout.off_instanceklass_constants < 0
        || g_neko_method_layout.off_array_length < 0
        || g_neko_method_layout.off_array_u2_data < 0) {
        fprintf(stderr, "[neko-bind] legacy InstanceKlass field array layout unavailable\\n");
        abort();
    }
    fields_array = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_fields);
    constant_pool = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_constants);
    if (fields_array == NULL || constant_pool == NULL) {
        return JNI_FALSE;
    }
    shorts_length = *(int*)((char*)fields_array + g_neko_method_layout.off_array_length);
    fields_data = (uint16_t*)((char*)fields_array + g_neko_method_layout.off_array_u2_data);
    if (shorts_length <= 0 || shorts_length % NEKO_FIELDINFO_LEGACY_SLOTS != 0) {
        return JNI_FALSE;
    }
    for (int base = 0; base + (NEKO_FIELDINFO_LEGACY_SLOTS - 1) < shorts_length; base += NEKO_FIELDINFO_LEGACY_SLOTS) {
        uint32_t access_flags = fields_data[base + 0];
        uint32_t name_index = fields_data[base + 1];
        uint32_t sig_index = fields_data[base + 2];
        uint32_t packed = ((uint32_t)fields_data[base + 5] << 16) | fields_data[base + 4];
        uint32_t offset;
        if (((access_flags & NEKO_JVM_ACC_STATIC) != 0) != (is_static == JNI_TRUE)) {
            continue;
        }
        if ((packed & NEKO_FIELDINFO_TAG_MASK) != NEKO_FIELDINFO_TAG_OFFSET) {
            continue;
        }
        if (!neko_match_field_symbols(constant_pool, name_index, sig_index, 0, name_utf8, sig_utf8)) {
            continue;
        }
        offset = packed >> NEKO_FIELDINFO_TAG_SIZE;
        out->found = JNI_TRUE;
        out->is_static = (access_flags & NEKO_JVM_ACC_STATIC) != 0 ? JNI_TRUE : JNI_FALSE;
        out->offset = offset;
        out->access_flags = access_flags;
        out->holder_klass = instance_klass;
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean neko_resolve_declared_field(void *instance_klass, const char *name_utf8, const char *sig_utf8, jboolean is_static, neko_field_resolution_t *out) {
    if (neko_resolve_declared_field_stream(instance_klass, name_utf8, sig_utf8, is_static, out)) {
        return JNI_TRUE;
    }
    return neko_resolve_declared_field_legacy(instance_klass, name_utf8, sig_utf8, is_static, out);
}

static jboolean neko_resolve_interface_field(void *instance_klass, const char *name_utf8, const char *sig_utf8, jboolean is_static, neko_field_resolution_t *out) {
    void *interfaces_array;
    int interface_count;
    void **interface_data;
    if (instance_klass == NULL) return JNI_FALSE;
    if (g_neko_method_layout.off_instanceklass_transitive_interfaces < 0) {
        fprintf(stderr, "[neko-bind] InstanceKlass::_transitive_interfaces layout unavailable for field resolution\\n");
        abort();
    }
    interfaces_array = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_transitive_interfaces);
    if (interfaces_array == NULL) return JNI_FALSE;
    interface_count = *(int*)((char*)interfaces_array + g_neko_method_layout.off_array_length);
    interface_data = (void**)((char*)interfaces_array + g_neko_method_layout.off_array_data);
    for (int i = 0; i < interface_count; i++) {
        if (neko_resolve_declared_field(interface_data[i], name_utf8, sig_utf8, is_static, out)) {
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

static neko_field_resolution_t neko_resolve_field(void *instance_klass, const char *name_utf8, const char *sig_utf8, jboolean is_static) {
    neko_field_resolution_t out;
    void *klass;
    memset(&out, 0, sizeof(out));
    if (instance_klass == NULL || name_utf8 == NULL || sig_utf8 == NULL) {
        fprintf(stderr, "[neko-bind] field resolution requested with null input\\n");
        abort();
    }
    if (g_neko_method_layout.off_klass_super < 0) {
        fprintf(stderr, "[neko-bind] Klass::_super layout unavailable for field resolution\\n");
        abort();
    }
    klass = instance_klass;
    for (int depth = 0; klass != NULL && depth < 256; depth++) {
        if (neko_resolve_declared_field(klass, name_utf8, sig_utf8, is_static, &out)) {
            return out;
        }
        klass = *(void**)((char*)klass + g_neko_method_layout.off_klass_super);
    }
    klass = instance_klass;
    for (int depth = 0; klass != NULL && depth < 256; depth++) {
        if (neko_resolve_interface_field(klass, name_utf8, sig_utf8, is_static, &out)) {
            return out;
        }
        klass = *(void**)((char*)klass + g_neko_method_layout.off_klass_super);
    }
    fprintf(stderr, "[neko-bind] native field resolution failed: %s:%s static=%d\\n",
        name_utf8, sig_utf8, (int)is_static);
    abort();
}

typedef struct {
    jboolean latin1;
    size_t utf16_units;
    size_t latin1_bytes;
} neko_utf8_shape_t;

static uint32_t neko_utf8_next_codepoint(const uint8_t **cursor, const uint8_t *end) {
    const uint8_t *p = *cursor;
    uint32_t cp;
    if (p >= end) return 0;
    if (p[0] < 0x80u) {
        *cursor = p + 1;
        return p[0];
    }
    if ((p[0] & 0xe0u) == 0xc0u && p + 1 < end) {
        cp = ((uint32_t)(p[0] & 0x1fu) << 6) | (uint32_t)(p[1] & 0x3fu);
        if ((p[1] & 0xc0u) != 0x80u || cp < 0x80u) goto invalid;
        *cursor = p + 2;
        return cp;
    }
    if ((p[0] & 0xf0u) == 0xe0u && p + 2 < end) {
        cp = ((uint32_t)(p[0] & 0x0fu) << 12) | ((uint32_t)(p[1] & 0x3fu) << 6) | (uint32_t)(p[2] & 0x3fu);
        if ((p[1] & 0xc0u) != 0x80u || (p[2] & 0xc0u) != 0x80u || cp < 0x800u || (cp >= 0xd800u && cp <= 0xdfffu)) goto invalid;
        *cursor = p + 3;
        return cp;
    }
    if ((p[0] & 0xf8u) == 0xf0u && p + 3 < end) {
        cp = ((uint32_t)(p[0] & 0x07u) << 18) | ((uint32_t)(p[1] & 0x3fu) << 12)
            | ((uint32_t)(p[2] & 0x3fu) << 6) | (uint32_t)(p[3] & 0x3fu);
        if ((p[1] & 0xc0u) != 0x80u || (p[2] & 0xc0u) != 0x80u || (p[3] & 0xc0u) != 0x80u
            || cp < 0x10000u || cp > 0x10ffffu) goto invalid;
        *cursor = p + 4;
        return cp;
    }
invalid:
    fprintf(stderr, "[neko-bind] invalid UTF-8 string literal while interning\\n");
    abort();
}

static neko_utf8_shape_t neko_utf8_shape(const uint8_t *utf, size_t len) {
    neko_utf8_shape_t shape;
    const uint8_t *cursor = utf;
    const uint8_t *end = utf + len;
    memset(&shape, 0, sizeof(shape));
    shape.latin1 = JNI_TRUE;
    while (cursor < end) {
        uint32_t cp = neko_utf8_next_codepoint(&cursor, end);
        if (cp <= 0xffu) {
            shape.latin1_bytes++;
            shape.utf16_units++;
        } else if (cp <= 0xffffu) {
            shape.latin1 = JNI_FALSE;
            shape.utf16_units++;
        } else {
            shape.latin1 = JNI_FALSE;
            shape.utf16_units += 2u;
        }
    }
    return shape;
}

static void neko_put_utf16_unit_le(uint8_t *dst, size_t index, uint16_t value) {
    dst[index * 2u] = (uint8_t)(value & 0xffu);
    dst[index * 2u + 1u] = (uint8_t)(value >> 8);
}

static void neko_fill_string_bytes(uint8_t *dst, const uint8_t *utf, size_t len, jboolean latin1) {
    const uint8_t *cursor = utf;
    const uint8_t *end = utf + len;
    size_t out = 0;
    while (cursor < end) {
        uint32_t cp = neko_utf8_next_codepoint(&cursor, end);
        if (latin1) {
            dst[out++] = (uint8_t)cp;
        } else if (cp <= 0xffffu) {
            neko_put_utf16_unit_le(dst, out++, (uint16_t)cp);
        } else {
            cp -= 0x10000u;
            neko_put_utf16_unit_le(dst, out++, (uint16_t)(0xd800u + (cp >> 10)));
            neko_put_utf16_unit_le(dst, out++, (uint16_t)(0xdc00u + (cp & 0x3ffu)));
        }
    }
}

static char *neko_alloc_jbyte_array_oop_slow(JNIEnv *env, jint len, jarray *local_ref_out) {
    jclass byte_class;
    jarray array;
    char *array_oop;
    if (local_ref_out != NULL) *local_ref_out = NULL;
    if (env == NULL || len < 0) {
        fprintf(stderr, "[neko-bind] invalid slow byte[] allocation request len=%d\\n", (int)len);
        abort();
    }
    if (g_neko_method_layout.sym_jvm_find_primitive_class == NULL
        || g_neko_method_layout.sym_jvm_new_array == NULL) {
        fprintf(stderr, "[neko-bind] JVM byte[] allocation symbols unavailable\\n");
        abort();
    }
    byte_class = ((neko_jvm_find_primitive_class_t)g_neko_method_layout.sym_jvm_find_primitive_class)(env, "byte");
    if (byte_class == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear_direct(env);
        fprintf(stderr, "[neko-bind] JVM_FindPrimitiveClass(byte) failed\\n");
        abort();
    }
    array = ((neko_jvm_new_array_t)g_neko_method_layout.sym_jvm_new_array)(env, byte_class, len);
    g_neko_jni_delete_local_ref_fn(env, byte_class);
    if (array == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear_direct(env);
        fprintf(stderr, "[neko-bind] JVM_NewArray(byte) failed len=%d\\n", (int)len);
        abort();
    }
    array_oop = (char*)neko_handle_oop((jobject)array);
    if (array_oop == NULL) {
        fprintf(stderr, "[neko-bind] JVM_NewArray(byte) returned an unresolved handle len=%d\\n", (int)len);
        abort();
    }
    if (local_ref_out != NULL) *local_ref_out = array;
    return array_oop;
}

static void neko_refill_tlab_with_slow_byte_array(JNIEnv *env, jint min_payload_len) {
    jarray scratch = NULL;
    if (min_payload_len < 0) min_payload_len = 0;
    (void)neko_alloc_jbyte_array_oop_slow(env, min_payload_len, &scratch);
    if (scratch != NULL) g_neko_jni_delete_local_ref_fn(env, scratch);
}

static void *neko_intern_string(void *thread, JNIEnv *env, const uint8_t *modutf, size_t len) {
    void *string_klass;
    neko_field_resolution_t value_field;
    neko_field_resolution_t coder_field;
    neko_utf8_shape_t shape;
    char *array_oop;
    char *string_oop;
    jarray local_array;
    jstring local_string;
    jstring interned;
    size_t payload_bytes;
    size_t array_bytes;
    size_t string_bytes;
    size_t ref_size;
    if (env == NULL) {
        fprintf(stderr, "[neko-bind] null JNIEnv for string intern\\n");
        abort();
    }
    if (modutf == NULL) {
        fprintf(stderr, "[neko-bind] null string literal requested\\n");
        abort();
    }
    if (g_neko_method_layout.sym_jvm_intern_string == NULL) {
        fprintf(stderr, "[neko-bind] JVM_InternString unavailable\\n");
        abort();
    }
    neko_ensure_string_alloc_bits(env);
    if (thread == NULL) {
        fprintf(stderr, "[neko-bind] JavaThread missing for native string intern\\n");
        abort();
    }
    string_klass = neko_resolve_class_with_env(env, "java/lang/String", NULL);
    value_field = neko_resolve_field(string_klass, "value", "[B", JNI_FALSE);
    coder_field = neko_resolve_field(string_klass, "coder", "B", JNI_FALSE);
    if (!value_field.found || !coder_field.found || value_field.offset == 0 || coder_field.offset == 0) {
        fprintf(stderr, "[neko-bind] java/lang/String value/coder metadata unavailable\\n");
        abort();
    }
    shape = neko_utf8_shape(modutf, len);
    payload_bytes = shape.latin1 ? shape.latin1_bytes : shape.utf16_units * 2u;
    if (payload_bytes > (size_t)INT32_MAX) {
        fprintf(stderr, "[neko-bind] string literal too large to intern\\n");
        abort();
    }
    array_bytes = (size_t)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] + payload_bytes;
    local_array = NULL;
    array_oop = (char*)neko_fast_tlab_alloc(thread, array_bytes);
    if (array_oop == NULL) {
        array_oop = neko_alloc_jbyte_array_oop_slow(env, (jint)payload_bytes, &local_array);
    } else {
        neko_init_oop_header(array_oop, g_neko_byte_array_klass_bits);
        *(jint*)(array_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] - 4) = (jint)payload_bytes;
    }
    neko_fill_string_bytes((uint8_t*)array_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B], modutf, len, shape.latin1);
    ref_size = g_hotspot.compressed_oops_enabled ? 4u : sizeof(void*);
    string_bytes = (size_t)value_field.offset + ref_size;
    if ((size_t)coder_field.offset + 1u > string_bytes) string_bytes = (size_t)coder_field.offset + 1u;
    string_oop = (char*)neko_fast_tlab_alloc(thread, string_bytes);
    if (string_oop == NULL) {
        neko_refill_tlab_with_slow_byte_array(env, string_bytes > (size_t)INT32_MAX ? INT32_MAX : (jint)string_bytes);
        string_oop = (char*)neko_fast_tlab_alloc(thread, string_bytes);
    }
    if (string_oop == NULL) {
        fprintf(stderr, "[neko-bind] TLAB String allocation failed for string literal\\n");
        abort();
    }
    neko_init_oop_header(string_oop, g_neko_string_klass_bits);
    neko_store_oop_raw(string_oop, (jlong)value_field.offset, array_oop);
    *(jbyte*)(string_oop + coder_field.offset) = shape.latin1 ? 0 : 1;
    local_string = (jstring)neko_direct_oop_to_handle(thread, string_oop);
    interned = ((neko_jvm_intern_string_t)g_neko_method_layout.sym_jvm_intern_string)(env, local_string);
    if (interned == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear_direct(env);
        fprintf(stderr, "[neko-bind] JVM_InternString failed for string literal\\n");
        abort();
    }
    if (local_array != NULL) g_neko_jni_delete_local_ref_fn(env, local_array);
    return neko_handle_oop((jobject)interned);
}

static void *neko_resolve_declared_method(void *instance_klass, const char *name_utf8, const char *sig_utf8) {
    void *methods_array;
    int method_count;
    void **method_data;
    if (g_neko_method_layout.off_instanceklass_methods < 0
        || g_neko_method_layout.off_array_length < 0
        || g_neko_method_layout.off_array_data < 0
        || g_neko_method_layout.off_constmethod_constants < 0
        || g_neko_method_layout.off_constmethod_name_index < 0
        || g_neko_method_layout.off_constmethod_signature_index < 0) {
        fprintf(stderr, "[neko-bind] InstanceKlass method layout unavailable\\n");
        abort();
    }
    methods_array = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_methods);
    if (methods_array == NULL) {
        return NULL;
    }
    method_count = *(int*)((char*)methods_array + g_neko_method_layout.off_array_length);
    method_data = (void**)((char*)methods_array + g_neko_method_layout.off_array_data);
    for (int i = 0; i < method_count; i++) {
        void *method = method_data[i];
        void *const_method = neko_method_constmethod(method);
        void *constant_pool;
        uint16_t name_index;
        uint16_t sig_index;
        void *name_symbol;
        void *sig_symbol;
        if (const_method == NULL) continue;
        constant_pool = *(void**)((char*)const_method + g_neko_method_layout.off_constmethod_constants);
        name_index = *(uint16_t*)((char*)const_method + g_neko_method_layout.off_constmethod_name_index);
        sig_index = *(uint16_t*)((char*)const_method + g_neko_method_layout.off_constmethod_signature_index);
        name_symbol = neko_constantpool_symbol_at(constant_pool, name_index);
        sig_symbol = neko_constantpool_symbol_at(constant_pool, sig_index);
        if (neko_symbol_equals_utf8(name_symbol, name_utf8)
            && neko_symbol_equals_utf8(sig_symbol, sig_utf8)) {
            return method;
        }
    }
    return NULL;
}

static void *neko_resolve_interface_method(void *instance_klass, const char *name_utf8, const char *sig_utf8) {
    void *interfaces_array;
    int interface_count;
    void **interface_data;
    if (instance_klass == NULL) return NULL;
    if (g_neko_method_layout.off_instanceklass_transitive_interfaces < 0) {
        fprintf(stderr, "[neko-bind] InstanceKlass::_transitive_interfaces layout unavailable\\n");
        abort();
    }
    interfaces_array = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_transitive_interfaces);
    if (interfaces_array == NULL) return NULL;
    interface_count = *(int*)((char*)interfaces_array + g_neko_method_layout.off_array_length);
    interface_data = (void**)((char*)interfaces_array + g_neko_method_layout.off_array_data);
    for (int i = 0; i < interface_count; i++) {
        void *method = neko_resolve_declared_method(interface_data[i], name_utf8, sig_utf8);
        if (method != NULL) return method;
    }
    return NULL;
}

static void *neko_resolve_method(void *instance_klass, const char *name_utf8, const char *sig_utf8) {
    void *klass;
    if (instance_klass == NULL || name_utf8 == NULL || sig_utf8 == NULL) {
        fprintf(stderr, "[neko-bind] method resolution requested with null input\\n");
        abort();
    }
    if (g_neko_method_layout.off_klass_super < 0) {
        fprintf(stderr, "[neko-bind] Klass::_super layout unavailable\\n");
        abort();
    }
    klass = instance_klass;
    for (int depth = 0; klass != NULL && depth < 256; depth++) {
        void *method = neko_resolve_declared_method(klass, name_utf8, sig_utf8);
        if (method != NULL) return method;
        klass = *(void**)((char*)klass + g_neko_method_layout.off_klass_super);
    }
    klass = instance_klass;
    for (int depth = 0; klass != NULL && depth < 256; depth++) {
        void *method = neko_resolve_interface_method(klass, name_utf8, sig_utf8);
        if (method != NULL) return method;
        klass = *(void**)((char*)klass + g_neko_method_layout.off_klass_super);
    }
    fprintf(stderr, "[neko-bind] native method resolution failed: %s%s\\n", name_utf8, sig_utf8);
    abort();
}

static jmethodID neko_make_native_method_id(void *method, const char *owner, const char *name, const char *desc) {
    void **cell;
    if (method == NULL) {
        fprintf(stderr, "[neko-bind] null Method* while creating native jmethodID: %s.%s%s\\n",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
        abort();
    }
    cell = (void**)malloc(sizeof(void*));
    if (cell == NULL) {
        fprintf(stderr, "[neko-bind] native jmethodID cell allocation failed: %s.%s%s\\n",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
        abort();
    }
    *cell = method;
    return (jmethodID)cell;
}

typedef struct {
    void *holder;
    void *next;
    int offset;
} neko_native_static_jniid;

static jfieldID neko_make_native_field_id(neko_field_resolution_t field, const char *owner, const char *name, const char *desc) {
    uintptr_t encoded;
    neko_native_static_jniid *static_id;
    if (!field.found || field.offset == 0) {
        fprintf(stderr, "[neko-bind] invalid native field metadata while creating jfieldID: %s.%s:%s\\n",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
        abort();
    }
    if (!field.is_static) {
        encoded = (((uintptr_t)field.offset) << 2u) | 0x2u;
        return (jfieldID)encoded;
    }
    if (field.holder_klass == NULL) {
        fprintf(stderr, "[neko-bind] static field holder missing while creating jfieldID: %s.%s:%s\\n",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
        abort();
    }
    static_id = (neko_native_static_jniid*)malloc(sizeof(*static_id));
    if (static_id == NULL) {
        fprintf(stderr, "[neko-bind] static jfieldID allocation failed: %s.%s:%s\\n",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
        abort();
    }
    static_id->holder = field.holder_klass;
    static_id->next = NULL;
    static_id->offset = (int)field.offset;
    return (jfieldID)static_id;
}

static void neko_bind_class_slot(JNIEnv *env, jclass *slot, const char *owner);
static void neko_bind_class_slot_from(JNIEnv *env, jclass *slot, const char *owner, jclass from_class);
static void neko_bind_primitive_class_slot(JNIEnv *env, jclass *slot, const char *desc);
static void neko_bind_string_slot(void *thread, JNIEnv *env, jstring *slot, const char *utf);

static jclass neko_ensure_class_slot(jclass *slot, JNIEnv *env, const char *name) {
    if (slot == NULL) {
        fprintf(stderr, "[neko-bind] class slot pointer missing for %s\\n", name == NULL ? "<null>" : name);
        abort();
    }
    if (*slot == NULL) {
        neko_bind_class_slot(env, slot, name);
    }
    return *slot;
}

static jstring neko_ensure_string_slot(jstring *slot, JNIEnv *env, const char *utf) {
    void *thread = neko_jni_env_to_thread(env);
    if (slot == NULL) {
        fprintf(stderr, "[neko-bind] string slot pointer missing for %s\\n", utf == NULL ? "<null>" : utf);
        abort();
    }
    if (*slot == NULL) {
        neko_bind_string_slot(thread, env, slot, utf);
    }
    return *slot;
}

static jmethodID neko_ensure_method_id_slot(jmethodID *slot, JNIEnv *env, jclass cls, const char *name, const char *desc, jboolean isStatic) {
    (void)isStatic;
    if (slot == NULL) {
        fprintf(stderr, "[neko-bind] method slot pointer missing: %s%s\\n",
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
        abort();
    }
    if (*slot == NULL) {
        void *klass;
        void *method;
        if (env == NULL || cls == NULL || name == NULL || desc == NULL) {
            fprintf(stderr, "[neko-bind] method ensure missing input: %s%s\\n",
                name == NULL ? "<null>" : name,
                desc == NULL ? "<null>" : desc);
            abort();
        }
        klass = neko_class_mirror_to_klass(cls);
        method = neko_resolve_method(klass, name, desc);
        *slot = neko_make_native_method_id(method, "<ensure>", name, desc);
    }
    return *slot;
}

static jfieldID neko_ensure_field_id_slot(jfieldID *slot, JNIEnv *env, jclass cls, const char *name, const char *desc, jboolean isStatic) {
    if (slot == NULL) {
        fprintf(stderr, "[neko-bind] field slot pointer missing: %s:%s\\n",
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
        abort();
    }
    if (*slot == NULL) {
        void *klass;
        neko_field_resolution_t field;
        if (env == NULL || cls == NULL || name == NULL || desc == NULL) {
            fprintf(stderr, "[neko-bind] field ensure missing input: %s:%s\\n",
                name == NULL ? "<null>" : name,
                desc == NULL ? "<null>" : desc);
            abort();
        }
        klass = neko_class_mirror_to_klass(cls);
        field = neko_resolve_field(klass, name, desc, isStatic);
        *slot = neko_make_native_field_id(field, "<ensure>", name, desc);
    }
    return *slot;
}

static void neko_bind_owner_class_slot(JNIEnv *env, jclass *slot, jclass self_class, const char *owner) {
    jobject globalRef;
    void *self_klass;
    void *resolved_klass;
    if (env == NULL || slot == NULL || *slot != NULL) return;
    if (self_class == NULL) {
        fprintf(stderr, "[neko-bind] owner class missing: %s\\n", owner == NULL ? "<null>" : owner);
        abort();
    }
    self_klass = neko_class_mirror_to_klass(self_class);
    resolved_klass = neko_resolve_class_with_env(env, owner, self_class);
    if (self_klass == NULL || resolved_klass == NULL || self_klass != resolved_klass) {
        fprintf(stderr, "[neko-bind] owner class native resolver mismatch: %s self=%p resolved=%p\\n",
            owner == NULL ? "<null>" : owner, self_klass, resolved_klass);
        abort();
    }
    globalRef = g_neko_jni_new_global_ref_fn(env, self_class);
    if (globalRef == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear_direct(env);
        fprintf(stderr, "[neko-bind] owner class global-ref failed: %s\\n", owner == NULL ? "<null>" : owner);
        abort();
    }
    *slot = (jclass)globalRef;
    neko_remember_class_klass(*slot, resolved_klass);
}

static void neko_bind_class_slot(JNIEnv *env, jclass *slot, const char *owner) {
    neko_bind_class_slot_from(env, slot, owner, NULL);
}

static void neko_bind_class_slot_from(JNIEnv *env, jclass *slot, const char *owner, jclass from_class) {
    void *klass;
    jobject localClass;
    jobject globalRef;
    void *expected;
    if (env == NULL || slot == NULL || *slot != NULL || owner == NULL) return;
    localClass = neko_resolve_class_mirror_with_env(env, owner, from_class, &klass);
    globalRef = g_neko_jni_new_global_ref_fn(env, localClass);
    if (globalRef == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear_direct(env);
        fprintf(stderr, "[neko-bind] class global-ref failed after native resolution: %s\\n", owner);
        abort();
    }
    expected = NULL;
    if (!__atomic_compare_exchange_n((void**)slot, &expected, (void*)globalRef, JNI_FALSE, __ATOMIC_RELEASE, __ATOMIC_ACQUIRE)) {
        return;
    }
    neko_remember_class_klass((jclass)globalRef, klass);
    if (strstr(owner, "$NekoLambda$") != NULL) {
        if (!neko_manifest_patch_defined_class(env, (jclass)globalRef)) {
            fprintf(stderr, "[neko-bind] generated lambda manifest patch failed: %s\\n", owner);
            abort();
        }
    }
}

static const char *neko_primitive_descriptor_name(const char *desc) {
    if (desc == NULL || desc[0] == '\\0' || desc[1] != '\\0') return NULL;
    switch (desc[0]) {
        case 'Z': return "boolean";
        case 'B': return "byte";
        case 'C': return "char";
        case 'S': return "short";
        case 'I': return "int";
        case 'J': return "long";
        case 'F': return "float";
        case 'D': return "double";
        case 'V': return "void";
        default: return NULL;
    }
}

static void neko_bind_primitive_class_slot(JNIEnv *env, jclass *slot, const char *desc) {
    const char *primitive_name;
    jclass localClass;
    jobject globalRef;
    if (env == NULL || slot == NULL || *slot != NULL) return;
    primitive_name = neko_primitive_descriptor_name(desc);
    if (primitive_name == NULL) {
        fprintf(stderr, "[neko-bind] unsupported primitive class descriptor: %s\\n", desc == NULL ? "<null>" : desc);
        abort();
    }
    if (g_neko_method_layout.sym_jvm_find_primitive_class == NULL) {
        fprintf(stderr, "[neko-bind] JVM_FindPrimitiveClass unavailable for LDC Class descriptor %s\\n", desc);
        abort();
    }
    localClass = ((neko_jvm_find_primitive_class_t)g_neko_method_layout.sym_jvm_find_primitive_class)(env, primitive_name);
    if (localClass == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear_direct(env);
        fprintf(stderr, "[neko-bind] primitive class resolution failed for descriptor %s\\n", desc);
        abort();
    }
    globalRef = g_neko_jni_new_global_ref_fn(env, localClass);
    if (globalRef == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear_direct(env);
        fprintf(stderr, "[neko-bind] primitive class global-ref failed for descriptor %s\\n", desc);
        abort();
    }
    *slot = (jclass)globalRef;
}

static void neko_bind_method_slot(JNIEnv *env, jmethodID *slot, jclass cls, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    void *klass;
    void *method;
    (void)isStatic;
    if (env == NULL || slot == NULL || *slot != NULL || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    klass = neko_class_mirror_to_klass(cls);
    method = neko_resolve_method(klass, name, desc);
    *slot = neko_make_native_method_id(method, owner, name, desc);
}

/* Resolve the underlying HotSpot Method* and harvest cached entry pointers
 * from a JNI-resolved jmethodID. The Method::_from_compiled_entry and
 * _from_interpreted_entry fields contain the stable PC that JIT-compiled
 * callers and the interpreter (respectively) would jump to. We snapshot
 * them once at bind time so the hot dispatch path can issue a direct call
 * without any further JNI traffic.
 *
 * Caller must have already populated *midSlot via neko_bind_method_slot.
 * Holder slot caches the Method::method_holder() (an InstanceKlass*) so
 * callers can keep the holder reachable; on JDK 21+ Methods are kept alive
 * by their holder Klass so this is the right anchor for stability.
 *
 * No JNI on the resolution path here either (only memory reads off the
 * Method* whose layout offsets came from VMStructs at OnLoad time). */
typedef jint (*neko_jvm_get_class_methods_count_t)(JNIEnv*, jclass);
typedef jobjectArray (*neko_jvm_get_class_declared_members_t)(JNIEnv*, jclass, jboolean);

static void neko_link_class_methods(JNIEnv *env, jclass cls, const char *owner, const char *name, const char *desc) {
    jobjectArray members = NULL;
    if (env == NULL || cls == NULL) return;
    if (g_neko_method_layout.sym_jvm_get_class_methods_count != NULL) {
        (void)((neko_jvm_get_class_methods_count_t)g_neko_method_layout.sym_jvm_get_class_methods_count)(env, cls);
    }
    if (name != NULL && strcmp(name, "<init>") == 0) {
        if (g_neko_method_layout.sym_jvm_get_class_declared_constructors == NULL) {
            fprintf(stderr, "[neko-bind] constructor materialization symbol unavailable: %s.%s%s\\n",
                owner == NULL ? "<null>" : owner,
                name,
                desc == NULL ? "<null>" : desc);
            abort();
        }
        members = ((neko_jvm_get_class_declared_members_t)g_neko_method_layout.sym_jvm_get_class_declared_constructors)(env, cls, JNI_FALSE);
    } else {
        if (g_neko_method_layout.sym_jvm_get_class_declared_methods == NULL) {
            fprintf(stderr, "[neko-bind] method materialization symbol unavailable: %s.%s%s\\n",
                owner == NULL ? "<null>" : owner,
                name == NULL ? "<null>" : name,
                desc == NULL ? "<null>" : desc);
            abort();
        }
        members = ((neko_jvm_get_class_declared_members_t)g_neko_method_layout.sym_jvm_get_class_declared_methods)(env, cls, JNI_FALSE);
    }
    if (members == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear_direct(env);
        fprintf(stderr, "[neko-bind] method materialization failed: %s.%s%s\\n",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
        abort();
    }
    g_neko_jni_delete_local_ref_fn(env, members);
}

static void neko_bind_method_entry_slots(JNIEnv *env, jmethodID midSlot, jclass cls, const char *owner, const char *name, const char *desc, void **methodPtr, void **compiledEntry, void **interpretedEntry, void **holder) {
    void *klass;
    void *scanned;
    if (midSlot == NULL || cls == NULL || name == NULL || desc == NULL) {
        fprintf(stderr, "[neko-bind] method entry bind missing input: %s.%s%s\\n",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
        abort();
    }
    if (!g_neko_method_layout.initialized || !g_neko_method_layout.usable) {
        fprintf(stderr, "[neko-bind] method layout not usable while binding: %s.%s%s\\n",
            owner == NULL ? "<null>" : owner, name, desc);
        abort();
    }
    void *m = neko_jmethodid_to_method_star(midSlot);
    if (m == NULL) {
        fprintf(stderr, "[neko-bind] jmethodID did not decode to Method*: %s.%s%s\\n",
            owner == NULL ? "<null>" : owner, name, desc);
        abort();
    }
    klass = neko_class_mirror_to_klass(cls);
    scanned = neko_resolve_method(klass, name, desc);
    if (scanned != m) {
        fprintf(stderr, "[neko-bind] native method resolver mismatch: %s.%s%s jmethodID=%p scanned=%p\\n",
            owner == NULL ? "<null>" : owner, name, desc, m, scanned);
        abort();
    }
    if (methodPtr != NULL && *methodPtr == NULL) {
        *methodPtr = m;
    }
    if (compiledEntry != NULL && *compiledEntry == NULL
        && g_neko_method_layout.off_method_from_compiled_entry > 0) {
        *compiledEntry = *(void**)((char*)m + g_neko_method_layout.off_method_from_compiled_entry);
    }
    if (interpretedEntry != NULL && *interpretedEntry == NULL
        && g_neko_method_layout.off_method_from_interpreted_entry > 0) {
        *interpretedEntry = *(void**)((char*)m + g_neko_method_layout.off_method_from_interpreted_entry);
    }
    if (interpretedEntry != NULL && *interpretedEntry == NULL) {
        neko_link_class_methods(env, cls, owner, name, desc);
        if (compiledEntry != NULL && *compiledEntry == NULL
            && g_neko_method_layout.off_method_from_compiled_entry > 0) {
            *compiledEntry = *(void**)((char*)m + g_neko_method_layout.off_method_from_compiled_entry);
        }
        if (g_neko_method_layout.off_method_from_interpreted_entry > 0) {
            *interpretedEntry = *(void**)((char*)m + g_neko_method_layout.off_method_from_interpreted_entry);
        }
    }
    if (holder != NULL && *holder == NULL) {
        *holder = klass;
    }
}

static void *neko_bound_method_i_entry(void *methodPtr, void **entrySlot, const char *owner, const char *name, const char *desc) {
    void *entry;
    if (methodPtr == NULL || entrySlot == NULL) {
        fprintf(stderr, "[neko-bind] method i-entry missing input: %s.%s%s method=%p slot=%p\\n",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc,
            methodPtr, (void*)entrySlot);
        abort();
    }
    entry = *entrySlot;
    if (entry == NULL && g_neko_method_layout.off_method_from_interpreted_entry > 0) {
        entry = *(void**)((char*)methodPtr + g_neko_method_layout.off_method_from_interpreted_entry);
        if (entry != NULL) {
            *entrySlot = entry;
        }
    }
    if (entry == NULL && g_neko_method_layout.off_method_i2i_entry > 0) {
        entry = *(void**)((char*)methodPtr + g_neko_method_layout.off_method_i2i_entry);
        if (entry != NULL) {
            *entrySlot = entry;
        }
    }
    if (entry == NULL) {
        fprintf(stderr, "[neko-bind] method i-entry unavailable: %s.%s%s method=%p\\n",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc,
            methodPtr);
        abort();
    }
    return entry;
}

static void neko_bind_field_slot(JNIEnv *env, jfieldID *slot, jclass cls, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    void *klass;
    neko_field_resolution_t native_field;
    if (env == NULL || slot == NULL || *slot != NULL || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    klass = neko_class_mirror_to_klass(cls);
    native_field = neko_resolve_field(klass, name, desc, isStatic);
    if (!native_field.found || native_field.offset == 0) {
        fprintf(stderr, "[neko-bind] native field resolver returned invalid metadata: %s.%s:%s static=%d offset=%u\\n",
            owner, name, desc, (int)isStatic, native_field.offset);
        abort();
    }
    *slot = neko_make_native_field_id(native_field, owner, name, desc);
}

static void neko_bind_string_slot(void *thread, JNIEnv *env, jstring *slot, const char *utf) {
    void *string_oop;
    jstring localString;
    jobject globalRef;
    jboolean restoreJavaState = JNI_FALSE;
    if (env == NULL || slot == NULL || *slot != NULL || utf == NULL) return;
    if (thread == NULL) {
        fprintf(stderr, "[neko-bind] JavaThread missing while binding string: %s\\n", utf);
        abort();
    }
    if (g_neko_off_thread_state > 0
        && g_neko_thread_state_in_java != 0
        && *(int32_t*)((char*)thread + g_neko_off_thread_state) == g_neko_thread_state_in_java) {
        neko_transition_java_to_native(thread);
        restoreJavaState = JNI_TRUE;
    }
    string_oop = neko_intern_string(thread, env, (const uint8_t*)utf, strlen(utf));
    localString = (jstring)neko_direct_oop_to_handle(thread, string_oop);
    globalRef = g_neko_jni_new_global_ref_fn(env, localString);
    if (globalRef == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear_direct(env);
        fprintf(stderr, "[neko-bind] global-ref failed for native string literal: %s\\n", utf);
        abort();
    }
    if (restoreJavaState) {
        neko_transition_native_to_java(thread);
    }
    *slot = (jstring)globalRef;
}

static uintptr_t neko_array_klass_bits_for_descriptor(JNIEnv *env, const char *arrayDesc, jclass fromClass) {
    void *array_klass;
    if (env == NULL || arrayDesc == NULL || arrayDesc[0] != '[') {
        fprintf(stderr, "[neko-bind] invalid array descriptor for klass bits: %s\\n",
            arrayDesc == NULL ? "<null>" : arrayDesc);
        abort();
    }
    if (!g_hotspot.initialized
        || ((g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) == 0 && !g_hotspot.use_zgc)
        || g_hotspot.use_compact_object_headers
        || g_hotspot.klass_offset_bytes <= 0) {
        fprintf(stderr, "[neko-bind] array klass bits layout unavailable for %s\\n", arrayDesc);
        abort();
    }
    array_klass = neko_resolve_class_with_env(env, arrayDesc, fromClass);
    return neko_klass_header_bits(array_klass);
}

static void neko_bind_object_array_klass_bits(JNIEnv *env, uintptr_t *slot, const char *arrayDesc, jclass fromClass) {
    if (env == NULL || slot == NULL || *slot != 0) return;
    *slot = neko_array_klass_bits_for_descriptor(env, arrayDesc, fromClass);
    if (*slot == 0) {
        fprintf(stderr, "[neko-bind] object array klass bits unavailable: %s\\n",
            arrayDesc == NULL ? "<null>" : arrayDesc);
        abort();
    }
}

static jclass neko_bound_class(JNIEnv *env, jclass slot, const char *owner) {
    (void)env;
    if (slot != NULL) return slot;
    fprintf(stderr, "[neko-bind] unresolved bound class: %s\\n", owner == NULL ? "<null>" : owner);
    abort();
}

static void *neko_decode_klass_header_bits(uintptr_t bits) {
    uintptr_t base;
    int shift;
    if (bits == 0) return NULL;
    if (g_hotspot.use_compact_object_headers) {
        fprintf(stderr, "[neko-bind] compact object headers unavailable for current-owner Class LDC\\n");
        abort();
    }
    if (g_hotspot.use_compressed_klass_ptrs) {
        if (g_neko_method_layout.addr_compressed_klass_base == NULL
            || g_neko_method_layout.addr_compressed_klass_shift == NULL) {
            fprintf(stderr, "[neko-bind] compressed Klass decode layout unavailable\\n");
            abort();
        }
        base = (uintptr_t)(*(void**)g_neko_method_layout.addr_compressed_klass_base);
        shift = *(int*)g_neko_method_layout.addr_compressed_klass_shift;
        return (void*)(base + (bits << shift));
    }
    return (void*)bits;
}

static void *neko_object_handle_klass(jobject obj) {
    char *oop;
    uintptr_t bits;
    if (obj == NULL) return NULL;
    if (!g_hotspot.initialized || g_hotspot.klass_offset_bytes <= 0) {
        fprintf(stderr, "[neko-bind] object Klass offset unavailable for current-owner Class LDC\\n");
        abort();
    }
    oop = (char*)neko_handle_oop(obj);
    if (oop == NULL) return NULL;
    if (g_hotspot.use_compressed_klass_ptrs) {
        bits = (uintptr_t)(*(uint32_t*)(oop + g_hotspot.klass_offset_bytes));
    } else {
        bits = *(uintptr_t*)(oop + g_hotspot.klass_offset_bytes);
    }
    return neko_decode_klass_header_bits(bits);
}

static jclass neko_bound_current_owner_class(void *thread, JNIEnv *env, jclass slot, const char *owner, jobject self_or_class, jboolean isStatic) {
    void *current_klass;
    void *slot_klass;
    void *klass_name;
    jclass current_mirror;
    (void)env;
    if (thread == NULL || owner == NULL || self_or_class == NULL) {
        fprintf(stderr, "[neko-bind] current-owner Class LDC missing input: %s\\n", owner == NULL ? "<null>" : owner);
        abort();
    }
    if (isStatic) {
        current_mirror = (jclass)self_or_class;
        current_klass = neko_class_mirror_to_klass(current_mirror);
    } else {
        current_klass = neko_object_handle_klass(self_or_class);
        current_mirror = (jclass)neko_klass_java_mirror_handle(thread, current_klass);
    }
    if (current_klass == NULL || current_mirror == NULL) {
        fprintf(stderr, "[neko-bind] current-owner Class LDC mirror unavailable: %s\\n", owner);
        abort();
    }
    if (g_neko_method_layout.off_klass_name < 0) {
        fprintf(stderr, "[neko-bind] Klass::_name offset unavailable for current-owner Class LDC\\n");
        abort();
    }
    klass_name = *(void**)((char*)current_klass + g_neko_method_layout.off_klass_name);
    if (!neko_symbol_equals_utf8(klass_name, owner)) {
        fprintf(stderr, "[neko-bind] current-owner Class LDC owner mismatch: expected=%s klass=%p\\n", owner, current_klass);
        abort();
    }
    slot_klass = slot != NULL ? neko_class_mirror_to_klass(slot) : NULL;
    return slot_klass == current_klass ? slot : current_mirror;
}

static jmethodID neko_bound_method(JNIEnv *env, jmethodID slot, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    (void)env;
    if (slot != NULL) return slot;
    fprintf(stderr, "[neko-bind] unresolved bound %s method: %s.%s%s\\n",
            isStatic ? "static" : "instance",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
    abort();
}

static jfieldID neko_bound_field(JNIEnv *env, jfieldID slot, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    (void)env;
    if (slot != NULL) return slot;
    fprintf(stderr, "[neko-bind] unresolved bound %s field: %s.%s:%s\\n",
            isStatic ? "static" : "instance",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
    abort();
}

static jstring neko_bound_string(void *thread, JNIEnv *env, jstring *slot, const char *utf) {
    (void)thread;
    (void)env;
    if (slot != NULL && *slot != NULL) return *slot;
    fprintf(stderr, "[neko-bind] unresolved bound string literal: %s\\n", utf == NULL ? "<null>" : utf);
    abort();
}

static jboolean neko_bind_primitive_field_metadata_enabled(void) {
    return g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0;
}

static jlong neko_native_instance_field_offset(JNIEnv *env, jclass cls, const char *name, const char *desc) {
    void *klass;
    neko_field_resolution_t native_field;
    if (env == NULL || cls == NULL || name == NULL || desc == NULL) return -1;
    klass = neko_class_mirror_to_klass(cls);
    native_field = neko_resolve_field(klass, name, desc, JNI_FALSE);
    return native_field.found && !native_field.is_static && native_field.offset > 0 ? (jlong)native_field.offset : -1;
}

static jlong neko_native_static_field_offset(JNIEnv *env, jclass cls, const char *name, const char *desc) {
    void *klass;
    neko_field_resolution_t native_field;
    if (env == NULL || cls == NULL || name == NULL || desc == NULL) return -1;
    klass = neko_class_mirror_to_klass(cls);
    native_field = neko_resolve_field(klass, name, desc, JNI_TRUE);
    return native_field.found && native_field.is_static && native_field.offset > 0 ? (jlong)native_field.offset : -1;
}

static void neko_bind_instance_field_offset(JNIEnv *env, jlong *slot, jclass cls, jfieldID fid, const char *owner, const char *name, const char *desc, jboolean requireDirectOffset) {
    jlong offset;
    (void)fid;
    (void)requireDirectOffset;
    if (!neko_bind_primitive_field_metadata_enabled() || env == NULL || slot == NULL || *slot > 0 || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    offset = neko_native_instance_field_offset(env, cls, name, desc);
    if (offset <= 0) {
        fprintf(stderr, "[neko-bind] native instance field metadata invalid: %s.%s:%s offset=%lld\\n",
            owner, name, desc, (long long)offset);
        abort();
    }
    *slot = offset;
}

static void neko_bind_static_field_metadata(JNIEnv *env, jobject *baseSlot, jlong *offsetSlot, jclass cls, const char *owner, const char *name, const char *desc) {
    jlong offset;
    if (!neko_bind_primitive_field_metadata_enabled() || env == NULL || baseSlot == NULL || offsetSlot == NULL
        || (*baseSlot != NULL && *offsetSlot > 0) || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    offset = neko_native_static_field_offset(env, cls, name, desc);
    if (offset <= 0) {
        fprintf(stderr, "[neko-bind] native static field metadata invalid: %s.%s:%s offset=%lld\\n",
            owner, name, desc, (long long)offset);
        abort();
    }
    *offsetSlot = offset;
    *baseSlot = (jobject)cls;
}

""";
    }

    /**
     * T4.2b — convenience helper that mirrors the
     * {@code neko_get_method_id} / {@code neko_get_static_method_id} call
     * shape but routes through the libjvm-internal
     * {@code neko_resolve_method} (Klass-based scan) followed by
     * {@code neko_make_native_method_id} (Method* → synthetic jmethodID).
     * The same helper covers static and instance methods because
     * {@code neko_resolve_method} does not distinguish based on access
     * flags — it scans the InstanceKlass {@code _methods} array by
     * (name, signature) and walks superclasses / interfaces. The strict
     * abort-on-missing behavior matches the {@code R-negative} gate
     * inherited from T2.3. Emitted in its own method to keep
     * renderBindSupport's text block under the 65535-byte string-literal
     * constant pool limit.
     */
    private String renderResolveJMethodID() {
        return """
/* T4.2b helper: resolve (name, sig) on a jclass to a synthetic jmethodID.
 *
 * `is_static` selects between the JNI GetMethodID and GetStaticMethodID
 * semantics: when set we walk superclasses scanning declared methods AND
 * filter out methods missing ACC_STATIC; when unset we filter out methods
 * with ACC_STATIC. This matches what HotSpot's JNI bridge does; relaxing
 * the filter is unsafe because a class hierarchy may declare a static and
 * an instance method with the same (name, sig) at different inheritance
 * depths and JNI's static-vs-instance distinction picks different Method*
 * pointers. The synthetic jmethodID is the same Method**-cell shape used
 * by neko_make_native_method_id elsewhere in the bind path. */
static jmethodID neko_resolve_jmethodID_with_kind(JNIEnv *env, jclass cls, const char *name, const char *sig, jboolean is_static) {
    void *klass;
    void *method;
    void *current_klass;
    uint32_t want_static_mask;
    (void)env;
    if (cls == NULL) {
        fprintf(stderr, "[neko-bind] T4.2b method resolution missing jclass: %s%s\\n",
            name == NULL ? "<null>" : name,
            sig == NULL ? "<null>" : sig);
        abort();
    }
    if (name == NULL || sig == NULL) {
        fprintf(stderr, "[neko-bind] T4.2b method resolution missing name/sig\\n");
        abort();
    }
    klass = neko_class_mirror_to_klass(cls);
    if (klass == NULL) {
        fprintf(stderr, "[neko-bind] T4.2b cannot extract Klass from jclass: %s%s\\n", name, sig);
        abort();
    }
    if (g_neko_method_layout.off_method_access_flags < 0
        || g_neko_method_layout.off_klass_super < 0) {
        fprintf(stderr, "[neko-bind] T4.2b method access-flag layout unavailable\\n");
        abort();
    }
    want_static_mask = is_static ? NEKO_JVM_ACC_STATIC : 0u;
    current_klass = klass;
    for (int depth = 0; current_klass != NULL && depth < 256; depth++) {
        method = neko_resolve_declared_method(current_klass, name, sig);
        if (method != NULL) {
            uint32_t flags;
            size_t width = g_neko_method_layout.access_flags_size == 0
                ? sizeof(uint32_t) : g_neko_method_layout.access_flags_size;
            void *flag_addr = (void*)((char*)method + g_neko_method_layout.off_method_access_flags);
            if (width == 4) flags = *(uint32_t*)flag_addr;
            else if (width == 2) flags = (uint32_t)*(uint16_t*)flag_addr;
            else flags = *(uint32_t*)flag_addr;
            if ((flags & NEKO_JVM_ACC_STATIC) == want_static_mask) {
                return neko_make_native_method_id(method, "<T4.2b>", name, sig);
            }
            /* declared method exists but kind doesn't match — JNI's
             * Get(Static)MethodID would skip it and continue walking the
             * hierarchy, so we do the same. */
        }
        current_klass = *(void**)((char*)current_klass + g_neko_method_layout.off_klass_super);
    }
    /* For non-static methods JNI also walks default-method interfaces.
     * neko_resolve_method's interface-method path already covers that case;
     * we mirror it here only for the !is_static branch. */
    if (!is_static) {
        current_klass = klass;
        for (int depth = 0; current_klass != NULL && depth < 256; depth++) {
            method = neko_resolve_interface_method(current_klass, name, sig);
            if (method != NULL) {
                uint32_t flags;
                size_t width = g_neko_method_layout.access_flags_size == 0
                    ? sizeof(uint32_t) : g_neko_method_layout.access_flags_size;
                void *flag_addr = (void*)((char*)method + g_neko_method_layout.off_method_access_flags);
                if (width == 4) flags = *(uint32_t*)flag_addr;
                else if (width == 2) flags = (uint32_t)*(uint16_t*)flag_addr;
                else flags = *(uint32_t*)flag_addr;
                if ((flags & NEKO_JVM_ACC_STATIC) == 0u) {
                    return neko_make_native_method_id(method, "<T4.2b>", name, sig);
                }
            }
            current_klass = *(void**)((char*)current_klass + g_neko_method_layout.off_klass_super);
        }
    }
    fprintf(stderr, "[neko-bind] T4.2b method resolution failed (is_static=%d): %s%s on klass=%p\\n",
        (int)is_static, name, sig, klass);
    abort();
}

/* Default-instance variant for the call sites that previously used
 * neko_get_method_id (instance methods only). */
static jmethodID neko_resolve_jmethodID(JNIEnv *env, jclass cls, const char *name, const char *sig) {
    return neko_resolve_jmethodID_with_kind(env, cls, name, sig, JNI_FALSE);
}

/* T4.2c — IMPL_LOOKUP read via libjvm-internal static-field machinery.
 * Replaces the previous neko_get_static_field_id (function-table 144) +
 * GetStaticObjectField (function-table 145) pair. T4.4a will further wrap
 * this in a one-shot bind-time cache via JNIHandles::make_global. */
static jobject neko_impl_lookup(JNIEnv *env) {
    jclass lookupClass;
    void *klass;
    neko_field_resolution_t field;
    void *thread;
    lookupClass = neko_resolve_class_mirror_with_env(env, "java/lang/invoke/MethodHandles$Lookup", NULL, NULL);
    if (lookupClass == NULL) {
        fprintf(stderr, "[neko-bind] T4.2c lookup class missing\\n");
        abort();
    }
    /* JNI Get(Static)FieldID has the documented side effect of triggering
     * class init; mirror that here. */
    neko_ensure_class_initialized(env, lookupClass, "java/lang/invoke/MethodHandles$Lookup");
    klass = neko_class_mirror_to_klass(lookupClass);
    if (klass == NULL) {
        fprintf(stderr, "[neko-bind] T4.2c cannot extract Klass from MethodHandles$Lookup mirror\\n");
        abort();
    }
    field = neko_resolve_field(klass, "IMPL_LOOKUP", "Ljava/lang/invoke/MethodHandles$Lookup;", JNI_TRUE);
    if (!field.found || !field.is_static || field.offset == 0u) {
        fprintf(stderr, "[neko-bind] T4.2c IMPL_LOOKUP field metadata invalid (found=%d static=%d off=%u)\\n",
            (int)field.found, (int)field.is_static, field.offset);
        abort();
    }
    thread = neko_jni_env_to_thread(env);
    if (thread == NULL) {
        fprintf(stderr, "[neko-bind] T4.2c thread unavailable for IMPL_LOOKUP read\\n");
        abort();
    }
    return neko_fast_get_static_object_field(thread, env, lookupClass, NULL, lookupClass, (jlong)field.offset);
}

/* Method*-returning variant for paths that don't need a JNI jmethodID at
 * all (the manifest patcher pipes the result straight into
 * neko_patch_method_entry). Skips the synthetic Method**-cell allocation
 * neko_make_native_method_id would do, so each manifest patch costs zero
 * heap allocations. Same kind-aware filter as neko_resolve_jmethodID_with_kind. */
static void *neko_resolve_method_star_with_kind(JNIEnv *env, jclass cls, const char *name, const char *sig, jboolean is_static) {
    void *klass;
    void *method;
    void *current_klass;
    uint32_t want_static_mask;
    (void)env;
    if (cls == NULL) {
        fprintf(stderr, "[neko-bind] T4.2b method resolution missing jclass: %s%s\\n",
            name == NULL ? "<null>" : name,
            sig == NULL ? "<null>" : sig);
        abort();
    }
    if (name == NULL || sig == NULL) {
        fprintf(stderr, "[neko-bind] T4.2b method resolution missing name/sig\\n");
        abort();
    }
    klass = neko_class_mirror_to_klass(cls);
    if (klass == NULL) {
        fprintf(stderr, "[neko-bind] T4.2b cannot extract Klass from jclass: %s%s\\n", name, sig);
        abort();
    }
    if (g_neko_method_layout.off_method_access_flags < 0
        || g_neko_method_layout.off_klass_super < 0) {
        fprintf(stderr, "[neko-bind] T4.2b method access-flag layout unavailable\\n");
        abort();
    }
    want_static_mask = is_static ? NEKO_JVM_ACC_STATIC : 0u;
    current_klass = klass;
    for (int depth = 0; current_klass != NULL && depth < 256; depth++) {
        method = neko_resolve_declared_method(current_klass, name, sig);
        if (method != NULL) {
            uint32_t flags;
            size_t width = g_neko_method_layout.access_flags_size == 0
                ? sizeof(uint32_t) : g_neko_method_layout.access_flags_size;
            void *flag_addr = (void*)((char*)method + g_neko_method_layout.off_method_access_flags);
            if (width == 4) flags = *(uint32_t*)flag_addr;
            else if (width == 2) flags = (uint32_t)*(uint16_t*)flag_addr;
            else flags = *(uint32_t*)flag_addr;
            if ((flags & NEKO_JVM_ACC_STATIC) == want_static_mask) {
                return method;
            }
        }
        current_klass = *(void**)((char*)current_klass + g_neko_method_layout.off_klass_super);
    }
    if (!is_static) {
        current_klass = klass;
        for (int depth = 0; current_klass != NULL && depth < 256; depth++) {
            method = neko_resolve_interface_method(current_klass, name, sig);
            if (method != NULL) {
                uint32_t flags;
                size_t width = g_neko_method_layout.access_flags_size == 0
                    ? sizeof(uint32_t) : g_neko_method_layout.access_flags_size;
                void *flag_addr = (void*)((char*)method + g_neko_method_layout.off_method_access_flags);
                if (width == 4) flags = *(uint32_t*)flag_addr;
                else if (width == 2) flags = (uint32_t)*(uint16_t*)flag_addr;
                else flags = *(uint32_t*)flag_addr;
                if ((flags & NEKO_JVM_ACC_STATIC) == 0u) {
                    return method;
                }
            }
            current_klass = *(void**)((char*)current_klass + g_neko_method_layout.off_klass_super);
        }
    }
    fprintf(stderr, "[neko-bind] T4.2b method resolution failed (is_static=%d): %s%s on klass=%p\\n",
        (int)is_static, name, sig, klass);
    abort();
}

""";
    }

    /**
     * T4.2a — emit the *tolerant* class-mirror resolver. Identical to
     * {@code neko_resolve_class_mirror_with_env} (rendered above by
     * {@code renderBindSupport}) except it returns NULL when the class is
     * not yet loaded / not findable through any of the libjvm-internal
     * symbols (JVM_FindClassFromBootLoader / JVM_FindClassFromClass /
     * loaded-class graph walk).
     *
     * Required by {@code ManifestEmitter.neko_manifest_discover_and_patch}
     * which iterates ALL owner names at JNI_OnLoad: classes that have not
     * yet triggered their static initializer (and thus are not loaded yet)
     * get a deferred patch via {@code neko_manifest_patch_defined_class}
     * on the defineClass hook instead of an immediate JNI_OnLoad-time
     * patch. The strict resolver keeps abort-on-missing semantics for
     * every other call site (T4.4 / T4.3 / T4.5 / T4.10 inherits T2.2
     * R-negative).
     *
     * Kept in its own emit method to avoid pushing renderBindSupport's
     * already-1500-line text block over the JVM 65535-byte string-literal
     * constant pool limit.
     */
    /**
     * TLAB-NULL fix — emit the String.concat NJX cache helper. Lives
     * outside renderBindSupport because that text block already approaches
     * the JVM 65535-byte string-literal constant pool limit; appending
     * here directly would exceed it. The helper calls into resolvers
     * defined in renderBindSupport (neko_resolve_method,
     * neko_bound_method_i_entry) which are visible by the time this
     * render method is appended.
     */
    private String renderStringConcatNjxCache() {
        return """
static void neko_ensure_string_concat_njx_cache(JNIEnv *env, void *string_klass) {
    if (g_neko_string_concat_ready) return;
    if (string_klass == NULL) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: String Klass unavailable for concat NJX cache\\n");
        abort();
    }
    void *concat_method = neko_resolve_method(string_klass, "concat",
        "(Ljava/lang/String;)Ljava/lang/String;");
    if (concat_method == NULL) {
        fprintf(stderr, "[neko-bind] String.concat(String) Method* unavailable\\n");
        abort();
    }
    g_neko_string_concat_method = concat_method;
    g_neko_string_concat_entry = neko_bound_method_i_entry(concat_method,
        &g_neko_string_concat_entry, "java/lang/String", "concat",
        "(Ljava/lang/String;)Ljava/lang/String;");
    if (g_neko_string_concat_entry == NULL) {
        fprintf(stderr, "[neko-bind] String.concat(String) entry pointer unavailable\\n");
        abort();
    }
    (void)env;
    g_neko_string_concat_ready = JNI_TRUE;
    if (getenv("NEKO_PATCH_DEBUG") != NULL) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: String.concat NJX cache ready method=%p entry=%p\\n",
            g_neko_string_concat_method, g_neko_string_concat_entry);
    }
}

/* TLAB-NULL fix for NEW: cache jdk.internal.misc.Unsafe.allocateInstance
 * (Class<?>)Object — bare-instance allocation that delegates to HotSpot's
 * managed allocator. theInternalUnsafe is read from the static slot via
 * the same direct-static-field machinery T4.2c uses for IMPL_LOOKUP. */
static void neko_ensure_unsafe_allocate_instance_njx_cache(JNIEnv *env) {
    if (g_neko_unsafe_allocate_instance_ready) return;
    if (env == NULL) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: Unsafe NJX cache requires JNIEnv*\\n");
        abort();
    }
    /* Resolve jdk.internal.misc.Unsafe class and the theInternalUnsafe
     * static field. */
    void *unsafe_klass = neko_resolve_class_with_env(env,
        "jdk/internal/misc/Unsafe", NULL);
    if (unsafe_klass == NULL) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: jdk.internal.misc.Unsafe Klass unavailable\\n");
        abort();
    }
    neko_ensure_class_initialized(env,
        (jclass)neko_klass_java_mirror_handle(neko_jni_env_to_thread(env), unsafe_klass),
        "jdk/internal/misc/Unsafe");
    neko_field_resolution_t the_unsafe = neko_resolve_field(unsafe_klass,
        "theUnsafe", "Ljdk/internal/misc/Unsafe;", JNI_TRUE);
    if (!the_unsafe.found || !the_unsafe.is_static || the_unsafe.offset == 0u) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: Unsafe.theUnsafe field unavailable (found=%d static=%d off=%u)\\n",
            (int)the_unsafe.found, (int)the_unsafe.is_static, the_unsafe.offset);
        abort();
    }
    /* Read the static field through the wrapper Class oop's static area.
     * jclass mirror reuses the deferred handle path. */
    void *thread = neko_jni_env_to_thread(env);
    if (thread == NULL) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: thread unavailable for Unsafe field read\\n");
        abort();
    }
    jobject unsafe_mirror = neko_klass_java_mirror_handle(thread, unsafe_klass);
    if (unsafe_mirror == NULL) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: Unsafe mirror handle failed\\n");
        abort();
    }
    jobject unsafe_local = neko_fast_get_static_object_field(thread, env,
        (jclass)unsafe_mirror, NULL, unsafe_mirror, (jlong)the_unsafe.offset);
    if (unsafe_local == NULL) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: Unsafe.theUnsafe value NULL\\n");
        abort();
    }
    /* Promote to global ref so it survives across impl_fn frames. */
    g_neko_unsafe_instance_global = g_neko_jni_new_global_ref_fn(env, unsafe_local);
    if (g_neko_unsafe_instance_global == NULL) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: Unsafe global ref failed\\n");
        abort();
    }
    /* Resolve allocateInstance(Class)Object Method* + entry. */
    void *alloc_method = neko_resolve_method(unsafe_klass, "allocateInstance",
        "(Ljava/lang/Class;)Ljava/lang/Object;");
    if (alloc_method == NULL) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: Unsafe.allocateInstance Method* unavailable\\n");
        abort();
    }
    g_neko_unsafe_allocate_instance_method = alloc_method;
    g_neko_unsafe_allocate_instance_entry = neko_bound_method_i_entry(alloc_method,
        &g_neko_unsafe_allocate_instance_entry, "jdk/internal/misc/Unsafe",
        "allocateInstance", "(Ljava/lang/Class;)Ljava/lang/Object;");
    if (g_neko_unsafe_allocate_instance_entry == NULL) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: Unsafe.allocateInstance entry unavailable\\n");
        abort();
    }
    g_neko_unsafe_allocate_instance_ready = JNI_TRUE;
    if (getenv("NEKO_PATCH_DEBUG") != NULL) {
        fprintf(stderr, "[neko-bind] TLAB-NULL fix: Unsafe.allocateInstance NJX cache ready unsafe=%p method=%p entry=%p\\n",
            (void*)g_neko_unsafe_instance_global,
            g_neko_unsafe_allocate_instance_method,
            g_neko_unsafe_allocate_instance_entry);
    }
}

""";
    }

    private String renderTolerantClassResolver() {
        return """
static jclass neko_try_resolve_class_mirror_with_env(JNIEnv *env, const char *utf8, jclass from_class) {
    jclass resolved = NULL;
    void *klass;
    if (env == NULL || utf8 == NULL || utf8[0] == '\\0') return NULL;
    klass = neko_resolve_loaded_class_by_name(utf8);
    if (g_neko_method_layout.sym_jvm_find_class_from_boot_loader != NULL) {
        resolved = ((neko_jvm_find_class_boot_t)g_neko_method_layout.sym_jvm_find_class_from_boot_loader)(env, utf8);
    }
    if (resolved == NULL && from_class != NULL && g_neko_method_layout.sym_jvm_find_class_from_class != NULL) {
        resolved = ((neko_jvm_find_class_from_class_t)g_neko_method_layout.sym_jvm_find_class_from_class)(
            env, utf8, JNI_FALSE, from_class);
    }
    if (resolved != NULL) {
        return resolved;
    }
    if (klass != NULL) {
        void *thread = neko_jni_env_to_thread(env);
        if (thread == NULL) return NULL;
        return (jclass)neko_klass_java_mirror_handle(thread, klass);
    }
    return NULL;
}

""";
    }

    private String renderBoxingSupport() {
        return """
typedef struct {
    const char *owner;
    const char *value_sig;
    const char *valueof_sig;
    void *valueof_method;
    void *valueof_entry;
    jlong value_offset;
} neko_boxing_cache_t;

static neko_boxing_cache_t g_neko_box_boolean = {"java/lang/Boolean", "Z", "(Z)Ljava/lang/Boolean;", NULL, NULL, -1};
static neko_boxing_cache_t g_neko_box_byte = {"java/lang/Byte", "B", "(B)Ljava/lang/Byte;", NULL, NULL, -1};
static neko_boxing_cache_t g_neko_box_char = {"java/lang/Character", "C", "(C)Ljava/lang/Character;", NULL, NULL, -1};
static neko_boxing_cache_t g_neko_box_short = {"java/lang/Short", "S", "(S)Ljava/lang/Short;", NULL, NULL, -1};
static neko_boxing_cache_t g_neko_box_int = {"java/lang/Integer", "I", "(I)Ljava/lang/Integer;", NULL, NULL, -1};
static neko_boxing_cache_t g_neko_box_long = {"java/lang/Long", "J", "(J)Ljava/lang/Long;", NULL, NULL, -1};
static neko_boxing_cache_t g_neko_box_float = {"java/lang/Float", "F", "(F)Ljava/lang/Float;", NULL, NULL, -1};
static neko_boxing_cache_t g_neko_box_double = {"java/lang/Double", "D", "(D)Ljava/lang/Double;", NULL, NULL, -1};
static jboolean g_neko_boxing_cache_ready = JNI_FALSE;

static void neko_boxing_cache_init_one(JNIEnv *env, neko_boxing_cache_t *cache) {
    void *klass = NULL;
    jclass mirror;
    neko_field_resolution_t field;
    if (env == NULL || cache == NULL || cache->owner == NULL || cache->value_sig == NULL || cache->valueof_sig == NULL) {
        fprintf(stderr, "[neko-bind] boxing cache init missing input\\n");
        abort();
    }
    mirror = neko_resolve_class_mirror_with_env(env, cache->owner, NULL, &klass);
    if (mirror == NULL || klass == NULL) {
        fprintf(stderr, "[neko-bind] boxing class resolution failed: %s\\n", cache->owner);
        abort();
    }
    cache->valueof_method = neko_resolve_method(klass, "valueOf", cache->valueof_sig);
    cache->valueof_entry = neko_bound_method_i_entry(cache->valueof_method, &cache->valueof_entry, cache->owner, "valueOf", cache->valueof_sig);
    field = neko_resolve_field(klass, "value", cache->value_sig, JNI_FALSE);
    if (!field.found || field.offset == 0) {
        fprintf(stderr, "[neko-bind] boxing value field resolution failed: %s.value:%s\\n", cache->owner, cache->value_sig);
        abort();
    }
    cache->value_offset = (jlong)field.offset;
}

static void neko_boxing_cache_init(JNIEnv *env) {
    if (g_neko_boxing_cache_ready) return;
    neko_boxing_cache_init_one(env, &g_neko_box_boolean);
    neko_boxing_cache_init_one(env, &g_neko_box_byte);
    neko_boxing_cache_init_one(env, &g_neko_box_char);
    neko_boxing_cache_init_one(env, &g_neko_box_short);
    neko_boxing_cache_init_one(env, &g_neko_box_int);
    neko_boxing_cache_init_one(env, &g_neko_box_long);
    neko_boxing_cache_init_one(env, &g_neko_box_float);
    neko_boxing_cache_init_one(env, &g_neko_box_double);
    g_neko_boxing_cache_ready = JNI_TRUE;
}

static jobject neko_box_call(void *thread, JNIEnv *env, neko_boxing_cache_t *cache, jvalue arg, neko_njx_dispatcher_t dispatcher) {
    jvalue result;
    result.j = 0;
    if (!g_neko_boxing_cache_ready) neko_boxing_cache_init(env);
    if (thread == NULL) thread = neko_jni_env_to_thread(env);
    if (thread == NULL || cache == NULL || cache->valueof_method == NULL || dispatcher == NULL) {
        fprintf(stderr, "[neko-direct] boxing call_stub precondition failed owner=%s thread=%p method=%p dispatcher=%p\\n",
            cache == NULL ? "<null>" : cache->owner, thread, cache == NULL ? NULL : cache->valueof_method, (void*)dispatcher);
        abort();
    }
    result = dispatcher(thread, env, cache->valueof_method,
        neko_bound_method_i_entry(cache->valueof_method, &cache->valueof_entry, cache->owner, "valueOf", cache->valueof_sig),
        NULL, &arg);
    return result.l;
}

static char *neko_unbox_oop(void *thread, JNIEnv *env, jobject obj, neko_boxing_cache_t *cache) {
    char *oop;
    (void)thread;
    if (!g_neko_boxing_cache_ready) neko_boxing_cache_init(env);
    if (obj == NULL || cache == NULL || cache->value_offset <= 0) {
        fprintf(stderr, "[neko-direct] unbox precondition failed owner=%s obj=%p offset=%lld\\n",
            cache == NULL ? "<null>" : cache->owner, (void*)obj, (long long)(cache == NULL ? -1 : cache->value_offset));
        abort();
    }
    oop = (char*)neko_handle_oop(obj);
    if (oop == NULL) {
        fprintf(stderr, "[neko-direct] boxed object handle unresolved owner=%s obj=%p\\n", cache->owner, (void*)obj);
        abort();
    }
    return oop;
}

static jobject neko_box_boolean(void *thread, JNIEnv *env, jboolean v) { jvalue arg; arg.z = v; return neko_box_call(thread, env, &g_neko_box_boolean, arg, neko_njx_S_L_I); }
static jobject neko_box_byte(void *thread, JNIEnv *env, jbyte v) { jvalue arg; arg.b = v; return neko_box_call(thread, env, &g_neko_box_byte, arg, neko_njx_S_L_I); }
static jobject neko_box_char(void *thread, JNIEnv *env, jchar v) { jvalue arg; arg.c = v; return neko_box_call(thread, env, &g_neko_box_char, arg, neko_njx_S_L_I); }
static jobject neko_box_short(void *thread, JNIEnv *env, jshort v) { jvalue arg; arg.s = v; return neko_box_call(thread, env, &g_neko_box_short, arg, neko_njx_S_L_I); }
static jobject neko_box_int(void *thread, JNIEnv *env, jint v) { jvalue arg; arg.i = v; return neko_box_call(thread, env, &g_neko_box_int, arg, neko_njx_S_L_I); }
static jobject neko_box_long(void *thread, JNIEnv *env, jlong v) { jvalue arg; arg.j = v; return neko_box_call(thread, env, &g_neko_box_long, arg, neko_njx_S_L_J); }
static jobject neko_box_float(void *thread, JNIEnv *env, jfloat v) { jvalue arg; arg.f = v; return neko_box_call(thread, env, &g_neko_box_float, arg, neko_njx_S_L_F); }
static jobject neko_box_double(void *thread, JNIEnv *env, jdouble v) { jvalue arg; arg.d = v; return neko_box_call(thread, env, &g_neko_box_double, arg, neko_njx_S_L_D); }

static jboolean neko_unbox_boolean(void *thread, JNIEnv *env, jobject obj) { char *oop = neko_unbox_oop(thread, env, obj, &g_neko_box_boolean); return *(jboolean*)(oop + g_neko_box_boolean.value_offset); }
static jbyte neko_unbox_byte(void *thread, JNIEnv *env, jobject obj) { char *oop = neko_unbox_oop(thread, env, obj, &g_neko_box_byte); return *(jbyte*)(oop + g_neko_box_byte.value_offset); }
static jchar neko_unbox_char(void *thread, JNIEnv *env, jobject obj) { char *oop = neko_unbox_oop(thread, env, obj, &g_neko_box_char); return *(jchar*)(oop + g_neko_box_char.value_offset); }
static jshort neko_unbox_short(void *thread, JNIEnv *env, jobject obj) { char *oop = neko_unbox_oop(thread, env, obj, &g_neko_box_short); return *(jshort*)(oop + g_neko_box_short.value_offset); }
static jint neko_unbox_int(void *thread, JNIEnv *env, jobject obj) { char *oop = neko_unbox_oop(thread, env, obj, &g_neko_box_int); return *(jint*)(oop + g_neko_box_int.value_offset); }
static jlong neko_unbox_long(void *thread, JNIEnv *env, jobject obj) { char *oop = neko_unbox_oop(thread, env, obj, &g_neko_box_long); return *(jlong*)(oop + g_neko_box_long.value_offset); }
static jfloat neko_unbox_float(void *thread, JNIEnv *env, jobject obj) { char *oop = neko_unbox_oop(thread, env, obj, &g_neko_box_float); return *(jfloat*)(oop + g_neko_box_float.value_offset); }
static jdouble neko_unbox_double(void *thread, JNIEnv *env, jobject obj) { char *oop = neko_unbox_oop(thread, env, obj, &g_neko_box_double); return *(jdouble*)(oop + g_neko_box_double.value_offset); }

""";
    }

    private String renderBindOwnerFunctions() {
        StringBuilder sb = new StringBuilder();
        if (ownerBindIndex.isEmpty()) {
            return "";
        }
        sb.append("// === Bind-time owner resolution ===\n");
        for (Map.Entry<String, Integer> entry : ownerBindIndex.entrySet()) {
            String owner = entry.getKey();
            int ownerId = entry.getValue();
            OwnerResolution resolution = ownerResolutions.get(owner);
            sb.append("static void neko_bind_owner_").append(ownerId).append("(JNIEnv *env, jclass self_class) {\n");
            sb.append("    if (env == NULL || g_owner_bound_").append(ownerId).append(") return;\n");
            sb.append("    g_owner_bound_").append(ownerId).append(" = JNI_TRUE;\n");
            sb.append("    neko_bind_owner_class_slot(env, &").append(classSlotName(owner)).append(", self_class, \"")
                .append(c(owner)).append("\");\n");
            sb.append("    neko_bind_object_array_klass_bits(env, &").append(objectArrayKlassBitsSlotName(owner))
                .append(", \"").append(c(objectArrayDescriptor(owner))).append("\", self_class);\n");
            for (String classOwner : resolution.classes) {
                if (owner.equals(classOwner)) {
                    continue;
                }
                sb.append("    neko_bind_class_slot_from(env, &").append(classSlotName(classOwner)).append(", \"")
                    .append(c(classOwner)).append("\", self_class);\n");
                sb.append("    neko_bind_object_array_klass_bits(env, &").append(objectArrayKlassBitsSlotName(classOwner))
                    .append(", \"").append(c(objectArrayDescriptor(classOwner))).append("\", self_class);\n");
            }
            for (String primitiveDesc : resolution.primitiveClasses) {
                sb.append("    neko_bind_primitive_class_slot(env, &").append(primitiveClassSlotName(primitiveDesc)).append(", \"")
                    .append(c(primitiveDesc)).append("\");\n");
            }
            for (MethodRef methodRef : resolution.methods) {
                String midSlot = methodSlotName(methodRef.owner(), methodRef.name(), methodRef.desc(), methodRef.isStatic());
                String mptrSlot = methodPtrSlotName(methodRef.owner(), methodRef.name(), methodRef.desc(), methodRef.isStatic());
                String mcentrySlot = methodCEntrySlotName(methodRef.owner(), methodRef.name(), methodRef.desc(), methodRef.isStatic());
                String mientrySlot = methodIEntrySlotName(methodRef.owner(), methodRef.name(), methodRef.desc(), methodRef.isStatic());
                String mholderSlot = methodHolderSlotName(methodRef.owner(), methodRef.name(), methodRef.desc(), methodRef.isStatic());
                sb.append("    neko_bind_method_slot(env, &").append(midSlot)
                    .append(", ").append(classSlotName(methodRef.owner())).append(", \"")
                    .append(c(methodRef.owner())).append("\", \"")
                    .append(c(methodRef.name())).append("\", \"")
                    .append(c(methodRef.desc())).append("\", ")
                    .append(methodRef.isStatic() ? "JNI_TRUE" : "JNI_FALSE").append(");\n");
                /* Snapshot Method* and HotSpot entry pointers for the direct
                 * native→Java dispatcher. Reads happen straight off the Method*
                 * using VMStructs-published offsets — no JNI here. */
                sb.append("    neko_bind_method_entry_slots(env, ").append(midSlot)
                    .append(", ").append(classSlotName(methodRef.owner()))
                    .append(", \"").append(c(methodRef.owner())).append("\"")
                    .append(", \"").append(c(methodRef.name())).append("\"")
                    .append(", \"").append(c(methodRef.desc())).append("\"")
                    .append(", &").append(mptrSlot)
                    .append(", &").append(mcentrySlot)
                    .append(", &").append(mientrySlot)
                    .append(", &").append(mholderSlot).append(");\n");
            }
            for (FieldRef fieldRef : resolution.fields) {
                sb.append("    neko_bind_field_slot(env, &").append(fieldSlotName(fieldRef.owner(), fieldRef.name(), fieldRef.desc(), fieldRef.isStatic()))
                    .append(", ").append(classSlotName(fieldRef.owner())).append(", \"")
                    .append(c(fieldRef.owner())).append("\", \"")
                    .append(c(fieldRef.name())).append("\", \"")
                    .append(c(fieldRef.desc())).append("\", ")
                    .append(fieldRef.isStatic() ? "JNI_TRUE" : "JNI_FALSE").append(");\n");
                /* Resolve byte-offset metadata for both primitive AND object
                 * fields through the native field metadata scanner. T2.6 will
                 * remove the remaining Unsafe static-base handle path. */
                if (fieldRef.isStatic()) {
                    sb.append("    neko_bind_static_field_metadata(env, &")
                        .append(staticFieldBaseSlotName(fieldRef.owner(), fieldRef.name(), fieldRef.desc(), true))
                        .append(", &")
                        .append(staticFieldOffsetSlotName(fieldRef.owner(), fieldRef.name(), fieldRef.desc(), true))
                        .append(", ")
                        .append(classSlotName(fieldRef.owner()))
                        .append(", \"")
                        .append(c(fieldRef.owner()))
                        .append("\", \"")
                        .append(c(fieldRef.name()))
                        .append("\", \"")
                        .append(c(fieldRef.desc()))
                        .append("\");\n");
                } else {
                    sb.append("    neko_bind_instance_field_offset(env, &")
                        .append(fieldOffsetSlotName(fieldRef.owner(), fieldRef.name(), fieldRef.desc(), false))
                        .append(", ")
                        .append(classSlotName(fieldRef.owner()))
                        .append(", ")
                        .append(fieldSlotName(fieldRef.owner(), fieldRef.name(), fieldRef.desc(), false))
                        .append(", \"")
                        .append(c(fieldRef.owner()))
                        .append("\", \"")
                        .append(c(fieldRef.name()))
                        .append("\", \"")
                        .append(c(fieldRef.desc()))
                        .append("\", ")
                        .append("JNI_TRUE")
                        .append(");\n");
                }
            }
            sb.append("}\n\n");
            sb.append("static void neko_bind_owner_strings_").append(ownerId).append("(void *thread, JNIEnv *env) {\n");
            sb.append("    if (g_owner_strings_bound_").append(ownerId).append(") return;\n");
            if (resolution.strings.isEmpty()) {
                sb.append("    (void)thread;\n");
                sb.append("    (void)env;\n");
                sb.append("    g_owner_strings_bound_").append(ownerId).append(" = JNI_TRUE;\n");
            } else {
                sb.append("    if (thread == NULL || env == NULL) {\n");
                sb.append("        fprintf(stderr, \"[neko-bind] owner string bind missing thread/env: ")
                    .append(c(owner)).append("\\n\");\n");
                sb.append("        abort();\n");
                sb.append("    }\n");
                for (StringRef stringRef : resolution.strings) {
                    sb.append("    neko_bind_string_slot(thread, env, &").append(stringRef.cacheVar()).append(", \"")
                        .append(c(stringRef.value())).append("\");\n");
                }
                sb.append("    g_owner_strings_bound_").append(ownerId).append(" = JNI_TRUE;\n");
            }
            sb.append("}\n\n");
        }
        return sb.toString();
    }

    private boolean requiresLocalCapacity(CFunction fn) {
        for (CStatement statement : fn.body()) {
            if (statement instanceof CStatement.RawC raw) {
                String code = raw.code();
                if (code.contains("neko_new_") || code.contains("neko_call_") || code.contains("neko_get_object_array_element")
                    || code.contains("neko_set_object_array_element") || code.contains("neko_get_object_class")
                    || code.contains("NEKO_ENSURE_STRING") || code.contains("neko_string_concat")
                    || code.contains("neko_class_for_descriptor") || code.contains("neko_resolve_constant_dynamic")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String renderIcacheDirectStubs() {
        if (icacheDirectStubs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// === Inline-cache direct-call stubs ===\n");
        for (IcacheDirectStubRef stub : icacheDirectStubs.values()) {
            sb.append(renderIcacheDirectStub(stub));
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderIcacheMetas() {
        if (icacheMetas.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// === Inline-cache metadata ===\n");
        for (IcacheMetaRef meta : icacheMetas.values()) {
            sb.append("static const neko_icache_meta ").append(meta.symbol()).append(" = {\"")
                .append(c(meta.name())).append("\", \"").append(c(meta.desc())).append("\", ")
                .append(meta.translatedClassSlot() == null ? "NULL" : "&" + meta.translatedClassSlot()).append(", ")
                .append(meta.translatedStubSymbol() == null ? "NULL" : meta.translatedStubSymbol()).append(", ")
                .append(meta.isInterface() ? "JNI_TRUE" : "JNI_FALSE").append(", ")
                .append(meta.directDispatcherSymbol() == null ? "NULL" : meta.directDispatcherSymbol())
                .append("};\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderIcacheDirectStub(IcacheDirectStubRef stub) {
        StringBuilder sb = new StringBuilder();
        sb.append("static jvalue ").append(stub.symbol()).append("(void *thread, JNIEnv *env, jobject receiver, const jvalue *args) {\n");
        sb.append("    jvalue result = {0};\n");
        if (stub.returnType().getSort() != Type.VOID) {
            sb.append("    result").append(jvalueAccessor(stub.returnType())).append(" = ");
        } else {
            sb.append("    ");
        }
        sb.append(stub.binding().rawFunctionName()).append("(thread, env, receiver");
        for (int i = 0; i < stub.args().length; i++) {
            sb.append(", ");
            if (stub.args()[i].getSort() == Type.ARRAY) {
                sb.append("(jarray)");
            } else if (stub.args()[i].getSort() == Type.OBJECT) {
                sb.append("(jobject)");
            }
            sb.append("args[").append(i).append("]").append(jvalueAccessor(stub.args()[i]));
        }
        sb.append(");\n");
        sb.append("    return result;\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private boolean isPrimitiveFieldDescriptor(String desc) {
        return desc != null && desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0;
    }

    private String renderRuntimeSupport() {
        return """
typedef union {
    jint i;
    jlong j;
    jfloat f;
    jdouble d;
    jobject o;
} neko_slot;

#define PUSH_I(v) do { jint __tmp = (jint)(v); stack[sp++].i = __tmp; } while (0)
#define PUSH_L(v) do { jlong __tmp = (jlong)(v); stack[sp].j = __tmp; stack[sp + 1].j = __tmp; sp += 2; } while (0)
#define PUSH_F(v) do { jfloat __tmp = (jfloat)(v); stack[sp++].f = __tmp; } while (0)
#define PUSH_D(v) do { jdouble __tmp = (jdouble)(v); stack[sp].d = __tmp; stack[sp + 1].d = __tmp; sp += 2; } while (0)
#define PUSH_O(v) do { jobject __tmp = (jobject)(v); stack[sp++].o = __tmp; } while (0)
#define POP_I() (stack[--sp].i)
#define POP_L() (sp -= 2, stack[sp].j)
#define POP_F() (stack[--sp].f)
#define POP_D() (sp -= 2, stack[sp].d)
#define POP_O() (stack[--sp].o)

/* T3.20: the macro that did typed function-table indexing through env, plus
 * every opcode-side wrapper replaced by T3.1..T3.19 (object class / instanceof
 * checks, allocation, static-field oop reads, String UTF probes, array length
 * + region accessors, object-array allocation/get/set, primitive-array
 * allocations), is gone. The remaining bind-time helpers below expand the
 * function-table indexing inline; they are the only JNI surface left in
 * renderRuntimeSupport and only fire from the bootstrap / discovery path,
 * never from translated method bodies. */

__attribute__((visibility("hidden"))) void neko_transition_java_to_native(void *thread);
__attribute__((visibility("hidden"))) void neko_transition_native_to_java(void *thread);

/* T4.11 — the eight bind-time inline JNI wrappers
 *   neko_find_class, neko_get_method_id, neko_get_static_method_id,
 *   neko_get_static_field_id, neko_exception_clear,
 *   neko_new_global_ref, neko_delete_global_ref, neko_delete_local_ref
 * are sweep-deleted now that T4.1-T4.10 have driven the wrapper call
 * count to zero. Every former call site routes either through a
 * libjvm-internal direct read (T4.1 / T4.2c / T4.10 / T4.9) or through
 * a captured JNI function-table pointer (T4.2a / T4.2b / T4.3 / T4.4 /
 * T4.5 / T4.6 / T4.8). The forward declaration of
 * neko_exception_clear_direct stays because renderBindSupport call
 * sites need it before the actual definition (which lives after
 * neko_exception_check in renderHotSpotSupport). */
static inline __attribute__((always_inline)) void neko_exception_clear_direct(JNIEnv *env);
/* Some HotSpot helper blocks use `neko_handle_oop` before the fast-access
 * section is emitted in the final C file. Declare it here so C99 does not
 * infer an implicit int-returning prototype on first use. */
static inline void* neko_handle_oop(jobject handle);
static inline void* neko_handle_push(void *thread, void *raw_oop);

static inline void neko_set_pending_exception(void *thread, jthrowable exc) {
    void *exc_oop;
    if (thread == NULL || g_neko_off_thread_pending_exception <= 0) {
        fprintf(stderr, "[neko-direct] cannot set pending exception thread=%p pending_off=%td\\n",
            thread, g_neko_off_thread_pending_exception);
        abort();
    }
    if (exc == NULL) {
        fprintf(stderr, "[neko-direct] ATHROW null throwable requires implicit NPE construction\\n");
        abort();
    }
    exc_oop = neko_handle_oop((jobject)exc);
    if (exc_oop == NULL) {
        fprintf(stderr, "[neko-direct] ATHROW throwable handle did not resolve exc=%p\\n", (void*)exc);
        abort();
    }
    *(void**)((char*)thread + g_neko_off_thread_pending_exception) = exc_oop;
}

static inline void *neko_pending_exception_oop(void *thread) {
    if (thread == NULL || g_neko_off_thread_pending_exception <= 0) {
        fprintf(stderr, "[neko-direct] cannot read pending exception thread=%p pending_off=%td\\n",
            thread, g_neko_off_thread_pending_exception);
        abort();
    }
    return *(void**)((char*)thread + g_neko_off_thread_pending_exception);
}

static inline void neko_clear_pending_exception(void *thread) {
    if (thread == NULL || g_neko_off_thread_pending_exception <= 0) {
        fprintf(stderr, "[neko-direct] cannot clear pending exception thread=%p pending_off=%td\\n",
            thread, g_neko_off_thread_pending_exception);
        abort();
    }
    *(void**)((char*)thread + g_neko_off_thread_pending_exception) = NULL;
}

static inline jthrowable neko_take_pending_exception(void *thread) {
    void *exc_oop = neko_pending_exception_oop(thread);
    if (exc_oop == NULL) return NULL;
    jthrowable exc = (jthrowable)neko_handle_push(thread, exc_oop);
    neko_clear_pending_exception(thread);
    return exc;
}

/* Forward decl + helper: read JavaThread::_pending_exception directly. The
 * field offset is recovered by VMStructs (g_neko_off_thread_pending_exception)
 * and the JNIEnv->JavaThread distance is recovered by the patcher init
 * (g_neko_off_thread_jni_environment_for_check). When both are known,
 * neko_exception_check becomes a load + compare instead of a JNI call. The
 * translator emits one neko_exception_check after every JNI call inside an
 * impl_fn, so saving the JNI dispatch (~50 cycles) per check buys a lot in
 * tight loops (matrix multiply: ~35M checks per microbench iteration). */
extern ptrdiff_t g_neko_off_thread_pending_exception;
__attribute__((visibility("hidden"))) extern ptrdiff_t g_neko_off_thread_jni_environment_for_check;
__attribute__((visibility("hidden"))) extern void *g_neko_jni_onload_thread_reg;
__attribute__((visibility("hidden"))) extern void *g_neko_jni_functions_table;
__attribute__((visibility("hidden"))) extern ptrdiff_t g_neko_off_thread_state;
__attribute__((visibility("hidden"))) extern int32_t g_neko_thread_state_in_java;
__attribute__((visibility("hidden"))) extern int32_t g_neko_thread_state_in_native;
__attribute__((visibility("hidden"))) extern int32_t g_neko_thread_state_in_native_trans;

/* T4.0: bootstrap derivation of the JNIEnv -> JavaThread distance. The
 * previous (T3.20) version was reached lazily from the hot-path
 * neko_exception_check; that defeated CSE of the offset across multiple
 * inlined exception checks in the same impl_fn. T4.0 moves the call site
 * to neko_method_layout_init (end of JNI_OnLoad) so the resolver runs at
 * most once per process; the hot path no longer references the resolver
 * or the atomic acquire-load. We mark the function `cold` + `noinline` so
 * GCC keeps it in a separate text segment partition and never speculates
 * a cross-function inline that would force a register-allocation barrier
 * around the hot path. The body itself is unchanged: env is the address
 * of JavaThread::_jni_environment, so the JavaThread base sits at
 * `env - off` for some `off` in the JNI environment field's offset
 * within JavaThread. We scan candidate offsets, accepting the one whose
 * first slot (the C++ vtable pointer) lies within libjvm's text mapping
 * (anchored against the published JNI function-table pointer, which
 * itself lives in libjvm). The pending-exception oop slot is additionally
 * required to look like NULL or a non-low aligned pointer so a coincidental
 * vtable hit cannot wedge in a wrong offset. No JNI calls are made; an
 * unvalidated candidate is hard-rejected. */
__attribute__((cold)) __attribute__((noinline))
static jboolean neko_exception_check_resolve_env_offset(JNIEnv *env) {
    uintptr_t env_bits;
    uintptr_t fn_table_bits;
    intptr_t libjvm_window;
    ptrdiff_t off;
    /* `g_neko_off_thread_jni_environment_for_check` is updated once and never
     * reset. The eager publication wrapper in neko_method_layout_init
     * already short-circuits when the VMStructs path supplied the offset,
     * so by the time we reach this scan, the global is guaranteed to be
     * zero unless a parallel JNI_OnLoad invocation raced us (impossible:
     * System.load() serializes). Read via __atomic_load_n with RELAXED
     * to keep the cold-path symmetry with the publishing store below; no
     * fence semantics are needed because the publishing happens before
     * any dispatcher entry can race against it. */
    if (__atomic_load_n(&g_neko_off_thread_jni_environment_for_check, __ATOMIC_RELAXED) > 0) {
        return JNI_TRUE;
    }
    if (env == NULL || g_neko_jni_functions_table == NULL
        || g_neko_off_thread_pending_exception <= 0) {
        return JNI_FALSE;
    }
    env_bits = (uintptr_t)env;
    fn_table_bits = (uintptr_t)g_neko_jni_functions_table;
    /* libjvm's text and data sections sit on different mmap'd regions,
     * sometimes hundreds of megabytes apart. Use a 1 GB window from the
     * published function-table pointer; this is wide enough to cover both
     * libjvm pages while still rejecting random non-libjvm vtables. */
    libjvm_window = (intptr_t)0x40000000;  /* 1 GB on each side */
    /* `_jni_environment` is the embedded JNIEnv member of JavaThread. In
     * HotSpot 21 it sits hundreds of bytes into the struct (after the
     * Thread base, OSThread*, _stack_base, the JavaFrameAnchor, etc.), so
     * candidates below 0x100 are never plausible and accepting one would
     * land us mid-record on a random earlier field whose memory happens to
     * mimic a vtable pointer. The lower bound also excludes the struct
     * preamble (vtable, mutex pointers) where `*(thread + off)` would
     * trivially match by aliasing. */
    for (off = 0x100; off < 0x4000; off += (ptrdiff_t)sizeof(void*)) {
        uintptr_t candidate_bits = env_bits - (uintptr_t)off;
        void *thread_candidate;
        void *vtbl;
        intptr_t diff;
        void *pending;
        int32_t state;
        if (candidate_bits < (uintptr_t)0x100000ULL) {
            return JNI_FALSE;
        }
        if ((candidate_bits & (uintptr_t)0xfULL) != 0u) {
            /* JavaThread is allocated with C++ new which guarantees at
             * least 16-byte alignment on 64-bit Linux. */
            continue;
        }
        thread_candidate = (void*)candidate_bits;
        if (*(void**)((char*)thread_candidate + off) != g_neko_jni_functions_table) {
            continue;
        }
        vtbl = *(void**)thread_candidate;
        if (vtbl == NULL) {
            continue;
        }
        if (((uintptr_t)vtbl & (uintptr_t)0x7ULL) != 0u) {
            continue;
        }
        diff = (intptr_t)vtbl - (intptr_t)fn_table_bits;
        if (diff < -libjvm_window || diff > libjvm_window) {
            continue;
        }
        pending = *(void**)((char*)thread_candidate + g_neko_off_thread_pending_exception);
        if (pending != NULL
            && ((uintptr_t)pending < (uintptr_t)0x100000ULL
                || ((uintptr_t)pending & (uintptr_t)0x7ULL) != 0u)) {
            continue;
        }
        if (g_neko_off_thread_state > 0) {
            state = *(int32_t*)((char*)thread_candidate + g_neko_off_thread_state);
            /* Thread state is a small enum (HotSpot uses 0..9). A real
             * JavaThread is in `_thread_in_native` while we run inside
             * JNI_OnLoad / bind helpers, but accept any of the states the
             * patcher already published to keep this generic. */
            if (state != g_neko_thread_state_in_java
                && state != g_neko_thread_state_in_native
                && state != g_neko_thread_state_in_native_trans) {
                continue;
            }
        }
        __atomic_store_n(&g_neko_off_thread_jni_environment_for_check, off, __ATOMIC_RELEASE);
        if (getenv("NEKO_PATCH_DEBUG") != NULL) {
            fprintf(stderr,
                "[neko-direct] derived JNIEnv->JavaThread distance via memory walk:"
                " off=%td env=%p thread=%p vtbl=%p\\n",
                off, (void*)env, thread_candidate, vtbl);
        }
        return JNI_TRUE;
    }
    if (getenv("NEKO_PATCH_DEBUG") != NULL) {
        fprintf(stderr,
            "[neko-direct] memory-walk derivation failed: env=%p fn_table=%p"
            " (no JavaThread vtable found in scan window)\\n",
            (void*)env, g_neko_jni_functions_table);
    }
    return JNI_FALSE;
}

/* T4.0 hot-path collapse:
 *   load env_off (plain non-atomic global)
 *   compute thread = env - env_off
 *   load _pending_exception
 *   compare and return.
 *
 * Both `g_neko_off_thread_jni_environment_for_check` and
 * `g_neko_off_thread_pending_exception` are published EAGERLY at the end of
 * `neko_method_layout_init` (driven from `JNI_OnLoad`) before any obfuscated
 * method can dispatch through us; missing publication aborts inside layout
 * init, so by the time control reaches this inline check, both globals are
 * non-zero. The plain global loads are CSE-safe across multiple inlined
 * neko_exception_check calls in the same impl_fn — the previous T3.20
 * `__atomic_load_n(..., __ATOMIC_ACQUIRE)` form blocked CSE because acquire
 * fences are conservative compiler barriers, which is the regression that
 * pushed matrix-mul Seq from ~25 ms to ~57 ms at T3.20 commit. The cold
 * `__builtin_expect(env_off <= 0, 0)` defensive abort stays in case some
 * cleanup path tears down the offset; production runs never enter it after
 * a successful JNI_OnLoad. The resolver function is unreachable from here. */
static inline __attribute__((always_inline))
jboolean neko_exception_check(JNIEnv *env) {
    ptrdiff_t env_off = g_neko_off_thread_jni_environment_for_check;
    void *thread;
    if (__builtin_expect(env_off <= 0, 0)) {
        fprintf(stderr,
            "[neko-direct] hot-path env-offset unpublished (env_off=%td pending_off=%td"
            " functions_table=%p thread_reg=%p); T4.0 eager publication required\\n",
            env_off, g_neko_off_thread_pending_exception,
            g_neko_jni_functions_table, g_neko_jni_onload_thread_reg);
        abort();
    }
    thread = (void*)((char*)env - env_off);
    return *(void**)((char*)thread + g_neko_off_thread_pending_exception) != NULL
        ? JNI_TRUE : JNI_FALSE;
}

/* T4.9 — direct _pending_exception clear via the offset published by T4.0
 * eager publication. Drop-in for `neko_exception_clear(env)`; idempotent
 * (clearing a NULL slot is a no-op store), so the surrounding
 * `if (neko_exception_check(env)) ...` guard becomes optional and may be
 * elided. The check + write are equivalent to two acquire-loads + a store
 * + a branch — strictly cheaper than the JNI function-table indirection
 * (index 17 = ExceptionClear). Missing offsets abort because T4.0
 * publishes both before any obfuscated method dispatches. */
static inline __attribute__((always_inline))
void neko_exception_clear_direct(JNIEnv *env) {
    ptrdiff_t env_off = g_neko_off_thread_jni_environment_for_check;
    void *thread;
    if (__builtin_expect(env_off <= 0 || g_neko_off_thread_pending_exception <= 0, 0)) {
        fprintf(stderr,
            "[neko-direct] T4.9 exception clear missing offsets (env_off=%td pending_off=%td)\\n",
            env_off, g_neko_off_thread_pending_exception);
        abort();
    }
    thread = (void*)((char*)env - env_off);
    *(void**)((char*)thread + g_neko_off_thread_pending_exception) = NULL;
}

typedef jvalue (*neko_njx_dispatcher_t)(void*, JNIEnv*, void*, void*, jobject, const jvalue*);

static inline void neko_raise_implicit_exception(void *thread, JNIEnv *env, jclass cls, void *ctor_method, void *ctor_entry, neko_njx_dispatcher_t ctor_dispatcher, const char *name) {
    jobject exc;
    jvalue args[1];
    if (thread == NULL || cls == NULL || ctor_method == NULL || ctor_entry == NULL || ctor_dispatcher == NULL) {
        fprintf(stderr, "[neko-direct] implicit exception precondition failed name=%s thread=%p cls=%p method=%p entry=%p dispatch=%p\\n",
            name == NULL ? "<null>" : name, thread, (void*)cls, ctor_method, ctor_entry, (void*)ctor_dispatcher);
        abort();
    }
    exc = neko_fast_alloc_object(thread, env, cls);
    args[0].l = NULL;
    ctor_dispatcher(thread, env, ctor_method, ctor_entry, exc, args);
    if (!neko_exception_check(env)) {
        neko_set_pending_exception(thread, (jthrowable)exc);
    }
}

typedef struct {
    const char *owner;
    const char *method;
    const char *file;
} neko_shadow_frame;

#define NEKO_SHADOW_STACK_MAX 256
static __thread neko_shadow_frame g_neko_shadow_stack[NEKO_SHADOW_STACK_MAX];
static __thread uint32_t g_neko_shadow_depth = 0u;

static void neko_shadow_push(const char *owner, const char *method, const char *file) {
    if (owner == NULL || method == NULL || file == NULL) return;
    if (g_neko_shadow_depth < NEKO_SHADOW_STACK_MAX) {
        g_neko_shadow_stack[g_neko_shadow_depth].owner = owner;
        g_neko_shadow_stack[g_neko_shadow_depth].method = method;
        g_neko_shadow_stack[g_neko_shadow_depth].file = file;
        g_neko_shadow_depth++;
    }
}

static void neko_shadow_pop(void) {
    if (g_neko_shadow_depth > 0u) g_neko_shadow_depth--;
}

/* T3.20: shadow-stack StackTrace helpers retain their pre-existing JNI surface
 * but no longer reference the opcode-side wrappers (string UTF allocation,
 * NewObjectA, object-array allocation, object-array set) nor the typed
 * function-table macro; the indexing is expanded inline so
 * `Throwable.getStackTrace()` (Test 2.6 ReTrace) keeps working. */
static jstring neko_shadow_dotted_string(JNIEnv *env, const char *internal_name) {
    char buf[512];
    size_t i;
    if (internal_name == NULL) return NULL;
    for (i = 0u; i + 1u < sizeof(buf) && internal_name[i] != '\\0'; i++) {
        buf[i] = internal_name[i] == '/' ? '.' : internal_name[i];
    }
    buf[i] = '\\0';
    return g_neko_jni_new_string_utf_fn(env, buf);
}

static jobjectArray neko_shadow_stack_trace(JNIEnv *env) {
    jclass ste_cls = neko_resolve_class_mirror_with_env(env, "java/lang/StackTraceElement", NULL, NULL);
    jmethodID ste_ctor;
    jobjectArray trace;
    uint32_t depth = g_neko_shadow_depth;
    uint32_t count;
    uint32_t i;
    if (ste_cls == NULL || neko_exception_check(env)) return NULL;
    ste_ctor = neko_resolve_jmethodID(env, ste_cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    if (ste_ctor == NULL || neko_exception_check(env)) return NULL;
    count = depth == 0u ? 0u : depth;
    trace = g_neko_jni_new_object_array_fn(env, (jsize)count, ste_cls, NULL);
    if (trace == NULL || neko_exception_check(env)) return trace;
    for (i = 0u; i < count; i++) {
        neko_shadow_frame *frame = &g_neko_shadow_stack[depth - 1u - i];
        jvalue args[4];
        jobject element;
        args[0].l = neko_shadow_dotted_string(env, frame->owner);
        args[1].l = g_neko_jni_new_string_utf_fn(env, frame->method);
        args[2].l = g_neko_jni_new_string_utf_fn(env, frame->file);
        args[3].i = -1;
        if (neko_exception_check(env)) return trace;
        element = g_neko_jni_new_object_a_fn(env, ste_cls, ste_ctor, args);
        if (neko_exception_check(env) || element == NULL) return trace;
        g_neko_jni_set_object_array_element_fn(env, trace, (jsize)i, element);
        if (neko_exception_check(env)) return trace;
    }
    return trace;
}

/* T3.20: dead helpers (`neko_dotted_class_name`, `neko_load_class_noinit`,
 * `neko_public_lookup`) had no remaining callers and depended on the
 * opcode-side wrappers + the typed function-table macro that were removed in
 * this stage; they are deleted entirely. The remaining helpers
 * (`neko_class_for_descriptor`, `neko_impl_lookup`, `neko_lookup_for_*`,
 * `neko_method_type_from_descriptor`, `neko_bootstrap_parameter_array`,
 * `neko_invoke_bootstrap`) are still emitted by the translator (LDC Class /
 * MethodType, MethodHandles.lookup() intrinsic, CONDY) and must keep working;
 * each function-table call is expanded inline below. */

/* T4.1: primitive descriptor → mirror table.
 *
 * The pre-T4.1 implementation routed each primitive switch arm through a
 * `neko_find_class("java/lang/Boolean") + neko_get_static_field_id("TYPE",
 * "Ljava/lang/Class;") + JNI GetStaticObjectField (index 145) triplet —
 * three JNI function-table indices per LDC, plus dedicated allocation per
 * call site. T4.1 collapses the entire primitive surface into one
 * generic table populated at OnLoad in `neko_primitive_mirror_table_init`
 * (renderHotSpotFastAccessHelpers region):
 *   - Each of Z/B/C/S/I/J/F/D resolves its wrapper InstanceKlass via
 *     `neko_resolve_class` and records (wrapper_klass, TYPE_offset).
 *   - Hot path: read the wrapper's `Klass::_java_mirror` OopHandle,
 *     dereference twice to reach the wrapper Class oop, read TYPE through
 *     compressed-oops decode + GC barrier, push to local handle.
 * Removed function-table indices for this helper: 6=FindClass,
 * 144=GetStaticFieldID, 145=GetStaticObjectField. The L<owner>; and
 * [...] arms route through `neko_resolve_class_mirror_with_env` which
 * already uses libjvm-internal `JVM_FindClassFromBootLoader` /
 * `JVM_FindClassFromClass` symbols (not JNI function-table calls). */
typedef struct {
    void *wrapper_klass;       /* InstanceKlass* of java.lang.{Boolean..Double} */
    uint32_t type_static_offset; /* offset of static TYPE field in the wrapper's Class oop */
    char tag;                   /* descriptor leaf char for diagnostics */
    jboolean ready;             /* set after a successful per-entry init */
} neko_primitive_mirror_entry_t;

static neko_primitive_mirror_entry_t g_neko_primitive_mirror_table[8] = {
    {NULL, 0u, 'Z', JNI_FALSE},
    {NULL, 0u, 'B', JNI_FALSE},
    {NULL, 0u, 'C', JNI_FALSE},
    {NULL, 0u, 'S', JNI_FALSE},
    {NULL, 0u, 'I', JNI_FALSE},
    {NULL, 0u, 'J', JNI_FALSE},
    {NULL, 0u, 'F', JNI_FALSE},
    {NULL, 0u, 'D', JNI_FALSE}
};
static jboolean g_neko_primitive_mirror_ready = JNI_FALSE;

static jclass neko_class_for_descriptor(JNIEnv *env, const char *desc) {
    switch (desc[0]) {
        case 'Z': case 'B': case 'C': case 'S':
        case 'I': case 'J': case 'F': case 'D':
            return neko_primitive_mirror_for_char(env, desc[0]);
        case 'L': {
            const char *start = desc + 1;
            const char *semi = strchr(start, ';');
            size_t len;
            char *buf;
            jclass out;
            if (semi == NULL) {
                fprintf(stderr, "[neko-bind] malformed L-descriptor missing ';': %s\\n", desc);
                abort();
            }
            len = (size_t)(semi - start);
            buf = (char*)malloc(len + 1u);
            if (buf == NULL) {
                fprintf(stderr, "[neko-bind] L-descriptor buffer alloc failed for %s\\n", desc);
                abort();
            }
            memcpy(buf, start, len); buf[len] = '\\0';
            out = neko_resolve_class_mirror_with_env(env, buf, NULL, NULL);
            free(buf);
            return out;
        }
        case '[':
            return neko_resolve_class_mirror_with_env(env, desc, NULL, NULL);
        default:
            fprintf(stderr, "[neko-bind] unsupported descriptor char '%c' in neko_class_for_descriptor\\n",
                desc[0]);
            abort();
    }
}

/* T4.2c — see renderImplLookup() below for the actual body. Forward
 * declaration here so the in-block neko_lookup_for_jclass call site (a few
 * lines down) resolves. */
static jobject neko_impl_lookup(JNIEnv *env);

static jobject neko_lookup_for_jclass(JNIEnv *env, jclass ownerClass);

static jobject neko_lookup_for_class(JNIEnv *env, const char *owner) {
    jclass ownerClass = neko_resolve_class_mirror_with_env(env, owner, NULL, NULL);
    return neko_lookup_for_jclass(env, ownerClass);
}

static jobject neko_lookup_for_jclass(JNIEnv *env, jclass ownerClass) {
    jclass mhClass = neko_resolve_class_mirror_with_env(env, "java/lang/invoke/MethodHandles", NULL, NULL);
    jmethodID mid = neko_resolve_jmethodID_with_kind(env, mhClass, "privateLookupIn", "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;", JNI_TRUE);
    jvalue args[2];
    args[0].l = ownerClass;
    args[1].l = neko_impl_lookup(env);
    return g_neko_jni_call_static_object_method_a_fn(env, mhClass, mid, args);
}

static jobject neko_method_type_from_descriptor(JNIEnv *env, const char *desc) {
    jclass mtClass = neko_resolve_class_mirror_with_env(env, "java/lang/invoke/MethodType", NULL, NULL);
    jmethodID mid = neko_resolve_jmethodID_with_kind(env, mtClass, "fromMethodDescriptorString", "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;", JNI_TRUE);
    jvalue args[2];
    args[0].l = g_neko_jni_new_string_utf_fn(env, desc);
    args[1].l = NULL;
    return g_neko_jni_call_static_object_method_a_fn(env, mtClass, mid, args);
}

static jobjectArray neko_bootstrap_parameter_array(JNIEnv *env, const char *bsm_desc) {
    jobject mt = neko_method_type_from_descriptor(env, bsm_desc);
    jclass mtClass = neko_resolve_class_mirror_with_env(env, "java/lang/invoke/MethodType", NULL, NULL);
    jmethodID mid = neko_resolve_jmethodID(env, mtClass, "parameterArray", "()[Ljava/lang/Class;");
    return (jobjectArray)g_neko_jni_call_object_method_a_fn(env, mt, mid, NULL);
}

static jobject neko_invoke_bootstrap(JNIEnv *env, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray invoke_args) {
    jclass bsmClass = neko_resolve_class_mirror_with_env(env, bsm_owner, NULL, NULL);
    jobjectArray paramTypes = neko_bootstrap_parameter_array(env, bsm_desc);
    jclass classClass = neko_resolve_class_mirror_with_env(env, "java/lang/Class", NULL, NULL);
    jmethodID getDeclaredMethod = neko_resolve_jmethodID(env, classClass, "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
    jvalue getArgs[2];
    getArgs[0].l = g_neko_jni_new_string_utf_fn(env, bsm_name);
    getArgs[1].l = paramTypes;
    jobject method = g_neko_jni_call_object_method_a_fn(env, bsmClass, getDeclaredMethod, getArgs);

    jclass accessibleClass = neko_resolve_class_mirror_with_env(env, "java/lang/reflect/AccessibleObject", NULL, NULL);
    jmethodID setAccessible = neko_resolve_jmethodID(env, accessibleClass, "setAccessible", "(Z)V");
    jvalue accessibleArgs[1];
    accessibleArgs[0].z = JNI_TRUE;
    g_neko_jni_call_void_method_a_fn(env, method, setAccessible, accessibleArgs);

    jclass methodClass = neko_resolve_class_mirror_with_env(env, "java/lang/reflect/Method", NULL, NULL);
    jmethodID invoke = neko_resolve_jmethodID(env, methodClass, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    jvalue invokeArgs[2];
    invokeArgs[0].l = NULL;
    invokeArgs[1].l = invoke_args;
    return g_neko_jni_call_object_method_a_fn(env, method, invoke, invokeArgs);
}

static jstring neko_string_null(JNIEnv *env) {
    static jstring g_str_null = NULL;
    return NEKO_ENSURE_STRING(g_str_null, env, "null");
}

/* T3.20: the legacy StringBuilder-style concat helpers (`_concat2` /
 * `_concat_string`) became unreachable from the translator after T3.19
 * (`neko_require_fast_string_concat` covers every recipe), referenced the
 * removed typed function-table macro plus the Call*MethodA dispatch entries,
 * and are now deleted entirely.
 *
 * `neko_resolve_constant_dynamic` still feeds the LDC ConstantDynamic path
 * through bootstrap-method invocation; the array length / allocation / set /
 * get and string-UTF function-table calls are expanded inline below so the
 * helper works without resurrecting the opcode-side wrappers. */
static jobject neko_resolve_constant_dynamic(JNIEnv *env, const char *caller_owner, const char *name, const char *desc, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray static_args) {
    jobjectArray paramTypes = neko_bootstrap_parameter_array(env, bsm_desc);
    jsize paramCount = g_neko_jni_get_array_length_fn(env, (jarray)paramTypes);
    jclass objClass = neko_resolve_class_mirror_with_env(env, "java/lang/Object", NULL, NULL);
    jobjectArray invokeArgs = g_neko_jni_new_object_array_fn(env, paramCount, objClass, NULL);
    g_neko_jni_set_object_array_element_fn(env, invokeArgs, 0, neko_lookup_for_class(env, caller_owner));
    g_neko_jni_set_object_array_element_fn(env, invokeArgs, 1, g_neko_jni_new_string_utf_fn(env, name));
    g_neko_jni_set_object_array_element_fn(env, invokeArgs, 2, neko_class_for_descriptor(env, desc));
    {
        jsize static_count = g_neko_jni_get_array_length_fn(env, (jarray)static_args);
        for (jsize i = 0; i < static_count; i++) {
            jobject element = g_neko_jni_get_object_array_element_fn(env, static_args, i);
            g_neko_jni_set_object_array_element_fn(env, invokeArgs, i + 3, element);
        }
    }
    return neko_invoke_bootstrap(env, bsm_owner, bsm_name, bsm_desc, invokeArgs);
}

static int neko_primitive_kind_from_descriptor_char(char leaf) {
    switch (leaf) {
        case 'Z': return 0;
        case 'B': return 1;
        case 'C': return 2;
        case 'S': return 3;
        case 'I': return 4;
        case 'J': return 5;
        case 'F': return 6;
        case 'D': return 7;
        default: return -1;
    }
}

static jobject neko_multi_new_array(void *thread, JNIEnv *env, jint num_dims, jint *dims, const char *desc, jclass fromClass) {
    if (num_dims <= 0 || dims == NULL || desc == NULL || desc[0] != '[') {
        fprintf(stderr, "[neko-direct] MULTIANEWARRAY invalid input dims=%d desc=%s\\n",
            (int)num_dims, desc == NULL ? "<null>" : desc);
        abort();
    }
    if (dims[0] < 0) {
        fprintf(stderr, "[neko-direct] MULTIANEWARRAY negative length %d desc=%s\\n",
            (int)dims[0], desc);
        abort();
    }
    if (num_dims == 1) {
        char leaf = desc[1];
        int kind = neko_primitive_kind_from_descriptor_char(leaf);
        if (kind >= 0) {
            return (jobject)neko_fast_new_primitive_array(thread, env, dims[0], kind);
        }
        return (jobject)neko_fast_new_object_array(
            thread, env, dims[0], neko_array_klass_bits_for_descriptor(env, desc, fromClass), NULL);
    }
    jobjectArray arr = (jobjectArray)neko_fast_new_object_array(
        thread, env, dims[0], neko_array_klass_bits_for_descriptor(env, desc, fromClass), NULL);
    for (jint i = 0; i < dims[0]; i++) {
        jobject sub = neko_multi_new_array(thread, env, num_dims - 1, dims + 1, desc + 1, fromClass);
        neko_fast_aastore(thread, env, arr, i, sub);
    }
    return (jobject)arr;
}

""";
    }

    private String renderHotSpotSupport() {
        return """
typedef enum {
    NEKO_COOP_MODE_UNKNOWN = 0,
    NEKO_COOP_MODE_DISABLED = 1,
    NEKO_COOP_MODE_ZERO_BASED = 2,
    NEKO_COOP_MODE_HEAP_BASED = 3
} neko_coop_encoded_mode;

enum {
    NEKO_PRIM_Z = 0,
    NEKO_PRIM_B = 1,
    NEKO_PRIM_C = 2,
    NEKO_PRIM_S = 3,
    NEKO_PRIM_I = 4,
    NEKO_PRIM_J = 5,
    NEKO_PRIM_F = 6,
    NEKO_PRIM_D = 7,
    NEKO_PRIM_COUNT = 8
};

enum {
    NEKO_HOTSPOT_FAST_RAW_HEAP = 1ll << 16,
    NEKO_HOTSPOT_FAST_ARRAY_LAYOUT = 1ll << 18,
    NEKO_HOTSPOT_FAST_HANDLE_TAGS = 1ll << 19,
    NEKO_HOTSPOT_FAST_FIELD_HELPERS = 1ll << 20,
    NEKO_FAST_RECEIVER_KEY = 0x10ll,
    NEKO_FAST_PRIM_FIELD = 0x4ll,
    NEKO_FAST_PRIM_ARRAY = 0x8ll
};

enum {
    NEKO_EARLY_GC_BARRIER_CARDTABLE = 1,
    NEKO_EARLY_GC_BARRIER_G1 = 2,
    NEKO_EARLY_GC_BARRIER_Z = 3,
    NEKO_EARLY_GC_BARRIER_SHENANDOAH = 4
};

typedef struct {
    jboolean initialized;
    jint address_size;
    jboolean compressed_oops_enabled;
    jint compressed_oops_shift;
    jlong compressed_oops_base;
    jboolean compressed_klass_ptrs;
    jboolean use_compact_object_headers;
    jint coop_encoded_mode;
    jint primitive_array_base_offsets[NEKO_PRIM_COUNT];
    jint primitive_array_index_scales[NEKO_PRIM_COUNT];
    uintptr_t primitive_array_klass_bits[NEKO_PRIM_COUNT];
    jint array_length_offset;
    jlong fast_bits;
    jboolean is_hotspot;
    jboolean use_zgc;
    jboolean use_shenandoah_gc;
    uintptr_t z_address_offset_mask;
    uintptr_t z_pointer_load_good_mask;
    uintptr_t z_pointer_load_bad_mask;
    uintptr_t z_pointer_store_good_mask;
    size_t z_pointer_load_shift;
    jint object_alignment_in_bytes;
    /*
     * Receiver-key scaffold state is appended so existing field offsets stay
     * unchanged for T1-T4 fast paths. klass_offset_bytes is derived from the
     * object-header mark word width (4 bytes on 32-bit, 8 bytes on 64-bit).
     */
    jboolean use_compressed_klass_ptrs;
    jint klass_offset_bytes;
} neko_hotspot_state;

static neko_hotspot_state g_hotspot;
static uintptr_t g_neko_handle_sample_oop = 0;

static jlong neko_native_instance_field_offset(JNIEnv *env, jclass cls, const char *name, const char *desc);
static jlong neko_native_static_field_offset(JNIEnv *env, jclass cls, const char *name, const char *desc);
static void neko_select_oop_field_load_barrier(void);
static void neko_select_oop_array_load_barrier(void);
static void neko_select_oop_field_store_barrier(void);

static const char* neko_hotspot_primitive_name(int kind) {
    switch (kind) {
        case NEKO_PRIM_Z: return "boolean";
        case NEKO_PRIM_B: return "byte";
        case NEKO_PRIM_C: return "char";
        case NEKO_PRIM_S: return "short";
        case NEKO_PRIM_I: return "int";
        case NEKO_PRIM_J: return "long";
        case NEKO_PRIM_F: return "float";
        case NEKO_PRIM_D: return "double";
        default: return NULL;
    }
}

static const char* neko_hotspot_primitive_array_descriptor(int kind) {
    switch (kind) {
        case NEKO_PRIM_Z: return "[Z";
        case NEKO_PRIM_B: return "[B";
        case NEKO_PRIM_C: return "[C";
        case NEKO_PRIM_S: return "[S";
        case NEKO_PRIM_I: return "[I";
        case NEKO_PRIM_J: return "[J";
        case NEKO_PRIM_F: return "[F";
        case NEKO_PRIM_D: return "[D";
        default: return NULL;
    }
}

static jint neko_hotspot_primitive_scale(int kind) {
    switch (kind) {
        case NEKO_PRIM_Z:
        case NEKO_PRIM_B:
            return 1;
        case NEKO_PRIM_C:
        case NEKO_PRIM_S:
            return 2;
        case NEKO_PRIM_I:
        case NEKO_PRIM_F:
            return 4;
        case NEKO_PRIM_J:
        case NEKO_PRIM_D:
            return 8;
        default:
            return 0;
    }
}

static size_t neko_hotspot_align_up_size(size_t value, size_t alignment) {
    if (alignment == 0 || (alignment & (alignment - 1u)) != 0) {
        fprintf(stderr, "[neko-hotspot] invalid alignment %zu\\n", alignment);
        abort();
    }
    return (value + alignment - 1u) & ~(alignment - 1u);
}

static jint neko_hotspot_array_base_offset_for(const neko_hotspot_state *state, int kind) {
    size_t heap_word = sizeof(void*);
    size_t length_offset;
    size_t header_bytes;
    size_t header_words;
    if (state == NULL || state->klass_offset_bytes <= 0) return -1;
    length_offset = state->use_compressed_klass_ptrs
        ? (size_t)state->klass_offset_bytes + sizeof(uint32_t)
        : (size_t)state->klass_offset_bytes + sizeof(void*);
    header_bytes = neko_hotspot_align_up_size(length_offset + sizeof(jint), heap_word);
    header_words = header_bytes / heap_word;
    if (kind == NEKO_PRIM_J || kind == NEKO_PRIM_D) {
        size_t words_per_long = (sizeof(jlong) + heap_word - 1u) / heap_word;
        header_words = neko_hotspot_align_up_size(header_words, words_per_long);
    }
    return (jint)(header_words * heap_word);
}

static uintptr_t neko_hotspot_klass_header_bits_for(const neko_hotspot_state *state, void *klass) {
    uintptr_t base;
    int shift;
    if (state == NULL || klass == NULL) return 0;
    if (state->use_compressed_klass_ptrs) {
        if (g_neko_addr_compressed_klass_base == NULL
            || g_neko_addr_compressed_klass_shift == NULL) {
            fprintf(stderr, "[neko-hotspot] compressed Klass VMStructs unavailable\\n");
            abort();
        }
        base = (uintptr_t)(*(void**)g_neko_addr_compressed_klass_base);
        shift = *(int*)g_neko_addr_compressed_klass_shift;
        if (shift < 0 || shift > 31 || (uintptr_t)klass < base) {
            fprintf(stderr, "[neko-hotspot] invalid compressed Klass encoding klass=%p base=%p shift=%d\\n",
                klass, (void*)base, shift);
            abort();
        }
        return (uintptr_t)(((uintptr_t)klass - base) >> shift);
    }
    return (uintptr_t)klass;
}

static void neko_hotspot_abort_missing(const char *what) {
    fprintf(stderr, "[neko-hotspot] required native HotSpot layout missing: %s\\n",
        what != NULL ? what : "unknown");
    abort();
}

static void neko_hotspot_init(JNIEnv *env) {
    neko_hotspot_state state;
    jlong fastBits = 0;
    jboolean arraysOk = JNI_TRUE;
    jboolean fieldHelpersOk = JNI_FALSE;
    if (g_hotspot.initialized) return;
    memset(&state, 0, sizeof(state));
    if (env == NULL) neko_hotspot_abort_missing("JNIEnv anchor");
    if (!g_neko_native_resolution_ready) neko_hotspot_abort_missing("native resolution layout");
    if (g_neko_addr_compressed_oops_base == NULL
        || g_neko_addr_compressed_oops_shift == NULL) {
        neko_hotspot_abort_missing("CompressedOops VMStructs");
    }
    if (g_neko_addr_compressed_klass_base == NULL
        || g_neko_addr_compressed_klass_shift == NULL) {
        neko_hotspot_abort_missing("CompressedKlassPointers VMStructs");
    }

    state.is_hotspot = JNI_TRUE;
    state.address_size = (jint)sizeof(void*);
    state.compressed_oops_enabled = JNI_TRUE;
    state.compressed_oops_shift = *(int*)g_neko_addr_compressed_oops_shift;
    state.compressed_oops_base = (jlong)(uintptr_t)(*(void**)g_neko_addr_compressed_oops_base);
    if (state.compressed_oops_shift < 0 || state.compressed_oops_shift > 31) {
        neko_hotspot_abort_missing("valid CompressedOops shift");
    }
    state.coop_encoded_mode = state.compressed_oops_base == 0
        ? (state.compressed_oops_shift == 0 ? NEKO_COOP_MODE_ZERO_BASED : NEKO_COOP_MODE_ZERO_BASED)
        : NEKO_COOP_MODE_HEAP_BASED;
    state.object_alignment_in_bytes = state.compressed_oops_shift >= 3
        ? (jint)(1u << state.compressed_oops_shift)
        : 8;
    state.compressed_klass_ptrs = sizeof(void*) == 8 ? JNI_TRUE : JNI_FALSE;
    state.use_compressed_klass_ptrs = state.compressed_klass_ptrs;
    state.klass_offset_bytes = (jint)sizeof(void*);
    state.use_zgc = g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_Z ? JNI_TRUE : JNI_FALSE;
    state.use_shenandoah_gc = g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_SHENANDOAH ? JNI_TRUE : JNI_FALSE;
    neko_select_oop_field_load_barrier();
    neko_select_oop_array_load_barrier();
    neko_select_oop_field_store_barrier();

    for (int i = 0; i < NEKO_PRIM_COUNT; i++) {
        jint baseOffset;
        jint indexScale;
        if (neko_hotspot_primitive_name(i) == NULL) neko_hotspot_abort_missing("primitive kind name");
        baseOffset = neko_hotspot_array_base_offset_for(&state, i);
        indexScale = neko_hotspot_primitive_scale(i);
        state.primitive_array_base_offsets[i] = baseOffset;
        state.primitive_array_index_scales[i] = indexScale;
        if (baseOffset < 0 || indexScale <= 0) arraysOk = JNI_FALSE;
    }

    {
        jclass integerClass = neko_resolve_class_mirror_with_env(env, "java/lang/Integer", NULL, NULL);
        jlong instanceOffset;
        jlong staticOffset;
        if (integerClass == NULL) neko_hotspot_abort_missing("java/lang/Integer mirror");
        instanceOffset = neko_native_instance_field_offset(env, integerClass, "value", "I");
        staticOffset = neko_native_static_field_offset(env, integerClass, "TYPE", "Ljava/lang/Class;");
        fieldHelpersOk = (instanceOffset >= 0 && staticOffset >= 0) ? JNI_TRUE : JNI_FALSE;
        if (state.address_size == 8) {
            if (instanceOffset == 12) {
                state.use_compressed_klass_ptrs = JNI_TRUE;
                state.compressed_klass_ptrs = JNI_TRUE;
            } else if (instanceOffset >= 16) {
                state.use_compressed_klass_ptrs = JNI_FALSE;
                state.compressed_klass_ptrs = JNI_FALSE;
            }
        }
    }

    for (int i = 0; i < NEKO_PRIM_COUNT; i++) {
        state.primitive_array_base_offsets[i] = neko_hotspot_array_base_offset_for(&state, i);
    }
    state.array_length_offset = state.primitive_array_base_offsets[NEKO_PRIM_I] - (jint)sizeof(jint);
    if (state.array_length_offset < 0) arraysOk = JNI_FALSE;

    for (int i = 0; i < NEKO_PRIM_COUNT; i++) {
        const char *arrayDesc = neko_hotspot_primitive_array_descriptor(i);
        void *arrayKlass = NULL;
        if (arrayDesc == NULL) neko_hotspot_abort_missing("primitive array descriptor");
        (void)neko_resolve_class_mirror_with_env(env, arrayDesc, NULL, &arrayKlass);
        if (arrayKlass == NULL) {
            arraysOk = JNI_FALSE;
            continue;
        }
        state.primitive_array_klass_bits[i] = neko_hotspot_klass_header_bits_for(&state, arrayKlass);
    }

    fastBits |= NEKO_HOTSPOT_FAST_HANDLE_TAGS;

    if (arraysOk) fastBits |= NEKO_HOTSPOT_FAST_ARRAY_LAYOUT;
    if (fieldHelpersOk) fastBits |= NEKO_HOTSPOT_FAST_FIELD_HELPERS;
    if ((fastBits & NEKO_HOTSPOT_FAST_HANDLE_TAGS) != 0u && arraysOk && fieldHelpersOk
        && !state.use_zgc && !state.use_shenandoah_gc) {
        fastBits |= NEKO_HOTSPOT_FAST_RAW_HEAP;
    }

    if ((fastBits & NEKO_HOTSPOT_FAST_RAW_HEAP) != 0 && !state.use_compact_object_headers) {
        if (fieldHelpersOk) fastBits |= NEKO_FAST_PRIM_FIELD;
        if (arraysOk) fastBits |= NEKO_FAST_PRIM_ARRAY;
        if (state.klass_offset_bytes > 0) fastBits |= NEKO_FAST_RECEIVER_KEY;
    }

    /*
     * Compact object headers change where klass metadata lives, so any future
     * receiver-key extraction or raw heap layout shortcut must stay disabled.
     */
    if (state.use_compact_object_headers) fastBits = 0;

    state.fast_bits = fastBits;
    state.initialized = JNI_TRUE;
    g_hotspot = state;
    return;
}

""" + renderHotSpotFastAccessHelpers();
    }

    private String renderHotSpotFastAccessHelpers() {
        StringBuilder sb = new StringBuilder();
        sb.append("""

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 199901L
#define NEKO_FAST_INLINE static inline
#else
#define NEKO_FAST_INLINE static
#endif

/* T4.14 perf — `NEKO_FORCE_INLINE` is the strict inlining attribute used
 * for the few hot-path helpers that are *guaranteed* to be (a) tiny and
 * (b) called from a tight loop where missing the inline costs measurable
 * wall-clock time. We do NOT apply it to every NEKO_FAST_INLINE helper
 * because some of those bodies grow large enough that always-inlining
 * them at every call site triggers register-pressure regressions or
 * stack-frame bloat in unrelated code paths. The narrow scoping is still
 * a generic architectural change — every translated method that goes
 * through any of the marked helpers benefits, with no method/class/owner
 * specialization. */
#define NEKO_FORCE_INLINE static inline __attribute__((always_inline))

NEKO_FORCE_INLINE jboolean neko_ref_is_direct_oop(jobject ref) {
    uintptr_t raw;
    if (ref == NULL) return JNI_FALSE;
    raw = (uintptr_t)ref;
    if (g_hotspot.use_zgc) {
        uintptr_t good_mask = g_hotspot.z_pointer_load_good_mask | g_hotspot.z_pointer_store_good_mask;
        uintptr_t metadata_mask = good_mask | g_hotspot.z_pointer_load_bad_mask;
        uintptr_t valid_mask = g_hotspot.z_address_offset_mask | metadata_mask;
        return (good_mask != 0
            && g_hotspot.z_address_offset_mask != 0
            && (raw & metadata_mask) == good_mask
            && (raw & ~valid_mask) == 0) ? JNI_TRUE : JNI_FALSE;
    }
#if UINTPTR_MAX > 0xffffffffu
    if ((raw & (uintptr_t)0x7u) == 0 && raw < (uintptr_t)0x0000100000000000ULL) {
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

NEKO_FAST_INLINE void *neko_zgc_uncolor_oop(void *oop) {
    uintptr_t raw = (uintptr_t)oop;
    if (raw == 0 || !g_hotspot.use_zgc) return oop;
    if (g_hotspot.z_pointer_load_shift != 0
        && (raw & (g_hotspot.z_pointer_load_good_mask | g_hotspot.z_pointer_load_bad_mask)) != 0) {
        return (void*)(raw >> g_hotspot.z_pointer_load_shift);
    }
    if (g_hotspot.z_address_offset_mask != 0
        && (raw & (g_hotspot.z_pointer_load_good_mask | g_hotspot.z_pointer_load_bad_mask)) != 0) {
        return (void*)(raw & g_hotspot.z_address_offset_mask);
    }
    return oop;
}

NEKO_FORCE_INLINE void *neko_zgc_good_oop(void *oop) {
    uintptr_t raw = (uintptr_t)oop;
    uintptr_t good_mask;
    if (raw == 0 || !g_hotspot.use_zgc) return oop;
    if ((raw & (g_hotspot.z_pointer_load_good_mask | g_hotspot.z_pointer_load_bad_mask | g_hotspot.z_pointer_store_good_mask)) != 0) {
        return oop;
    }
    good_mask = g_hotspot.z_pointer_store_good_mask != 0 ? g_hotspot.z_pointer_store_good_mask : g_hotspot.z_pointer_load_good_mask;
    if (good_mask != 0 && g_hotspot.z_address_offset_mask != 0 && (raw & ~g_hotspot.z_address_offset_mask) == 0) {
        return (void*)(raw | good_mask);
    }
    return oop;
}

/* T4.14 perf — `neko_barrier_oop_load` is on the inner loop of every
 * AALOAD/GETFIELD-object/icache-receiver-resolution path. Force-inline so
 * the compiler can fold the trivial non-ZGC identity through callers
 * (handle_oop, fused-array helpers, fast-array-length etc.). */
NEKO_FORCE_INLINE void *neko_barrier_oop_load(void *raw_oop) {
    uintptr_t raw = (uintptr_t)raw_oop;
    if (raw == 0) return NULL;
    if (g_hotspot.use_zgc) {
        if (g_hotspot.z_pointer_load_bad_mask != 0 && (raw & g_hotspot.z_pointer_load_bad_mask) != 0) {
            fprintf(stderr, "[neko-direct] ZGC bad oop load needs runtime barrier raw=%p bad_mask=0x%llx\\n",
                raw_oop, (unsigned long long)g_hotspot.z_pointer_load_bad_mask);
            abort();
        }
        return neko_zgc_good_oop(raw_oop);
    }
    return raw_oop;
}

typedef void *(*neko_z_lrb_field_preloaded_t)(void*, void**);
typedef void *(*neko_z_lrb_array_t)(void*);
typedef void *(*neko_sh_lrb_strong_t)(void*);
typedef void *(*neko_oop_field_load_barrier_t)(void*, void*);
typedef void *(*neko_oop_array_load_barrier_t)(void*, void*);
typedef void (*neko_z_store_field_t)(void**);
typedef void (*neko_write_ref_field_pre_t)(void*, void*);
typedef void (*neko_write_ref_field_post_t)(void*, void*);
typedef void (*neko_oop_field_store_pre_barrier_t)(void*, void*, void*);
typedef void (*neko_oop_field_store_post_barrier_t)(void*, void*);

static void *neko_barrier_load_oop_field_unavailable(void *field_addr, void *raw_oop) {
    fprintf(stderr, "[neko-direct] object field load barrier not selected kind=%d raw=%p addr=%p\\n",
        g_neko_gc_barrier_kind, raw_oop, field_addr);
    abort();
}

static void *neko_barrier_load_oop_field_raw(void *field_addr, void *raw_oop) {
    (void)field_addr;
    return neko_barrier_oop_load(raw_oop);
}

static void *neko_barrier_load_oop_field_z(void *field_addr, void *raw_oop) {
    if (g_neko_barrier_load_oop_field_preloaded == NULL || field_addr == NULL) {
        fprintf(stderr, "[neko-direct] ZGC object field load barrier unavailable raw=%p addr=%p\\n",
            raw_oop, field_addr);
        abort();
    }
    return ((neko_z_lrb_field_preloaded_t)g_neko_barrier_load_oop_field_preloaded)(raw_oop, (void**)field_addr);
}

static void *neko_barrier_load_oop_field_shenandoah(void *field_addr, void *raw_oop) {
    (void)field_addr;
    if (g_neko_barrier_load_oop_field_preloaded == NULL) {
        fprintf(stderr, "[neko-direct] Shenandoah object field load barrier unavailable raw=%p\\n", raw_oop);
        abort();
    }
    return ((neko_sh_lrb_strong_t)g_neko_barrier_load_oop_field_preloaded)(raw_oop);
}

static neko_oop_field_load_barrier_t g_neko_oop_field_load_barrier = neko_barrier_load_oop_field_unavailable;

static void neko_select_oop_field_load_barrier(void) {
    if (!g_neko_gc_barrier_ready) {
        fprintf(stderr, "[neko-direct] GC barrier layout unavailable for object field load kind=%d\\n",
            g_neko_gc_barrier_kind);
        abort();
    }
    if (g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_CARDTABLE
        || g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_G1) {
        g_neko_oop_field_load_barrier = neko_barrier_load_oop_field_raw;
        return;
    }
    if (g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_Z) {
        if (g_neko_barrier_load_oop_field_preloaded == NULL) {
            fprintf(stderr, "[neko-direct] ZGC object field load barrier symbol missing\\n");
            abort();
        }
        g_neko_oop_field_load_barrier = neko_barrier_load_oop_field_z;
        return;
    }
    if (g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_SHENANDOAH) {
        if (g_neko_barrier_load_oop_field_preloaded == NULL) {
            fprintf(stderr, "[neko-direct] Shenandoah object field load barrier symbol missing\\n");
            abort();
        }
        g_neko_oop_field_load_barrier = neko_barrier_load_oop_field_shenandoah;
        return;
    }
    fprintf(stderr, "[neko-direct] unsupported GC barrier kind for object field load: %d\\n",
        g_neko_gc_barrier_kind);
    abort();
}

/* T4.14 perf — inline the GC-kind dispatch directly so the G1 / Card-Table
 * (the dominant non-moving-GC case) collapses to identity with no function-
 * pointer indirection. The function-pointer dispatcher killed every
 * cross-iteration optimization the C compiler could have done in the
 * 7M-call AALOAD inner loop because indirect calls block CSE across the
 * call site. ZGC / Shenandoah still go through their dlsym'd LRB entries.
 * Generic to all object-field load sites in every translated method. */
NEKO_FORCE_INLINE void *neko_barrier_load_oop_field(void *field_addr, void *raw_oop) {
    int kind;
    if (raw_oop == NULL) return NULL;
    kind = g_neko_gc_barrier_kind;
    if (kind == NEKO_EARLY_GC_BARRIER_CARDTABLE || kind == NEKO_EARLY_GC_BARRIER_G1) {
        return raw_oop;
    }
    return g_neko_oop_field_load_barrier(field_addr, raw_oop);
}

static void *neko_barrier_load_oop_array_unavailable(void *element_addr, void *raw_oop) {
    fprintf(stderr, "[neko-direct] object array load barrier not selected kind=%d raw=%p addr=%p\\n",
        g_neko_gc_barrier_kind, raw_oop, element_addr);
    abort();
}

static void *neko_barrier_load_oop_array_raw(void *element_addr, void *raw_oop) {
    (void)element_addr;
    return neko_barrier_oop_load(raw_oop);
}

static void *neko_barrier_load_oop_array_z(void *element_addr, void *raw_oop) {
    (void)element_addr;
    if (g_neko_barrier_load_oop_array == NULL) {
        fprintf(stderr, "[neko-direct] ZGC object array load barrier unavailable raw=%p\\n", raw_oop);
        abort();
    }
    return ((neko_z_lrb_array_t)g_neko_barrier_load_oop_array)(raw_oop);
}

static void *neko_barrier_load_oop_array_shenandoah(void *element_addr, void *raw_oop) {
    (void)element_addr;
    if (g_neko_barrier_load_oop_field_preloaded == NULL) {
        fprintf(stderr, "[neko-direct] Shenandoah object array load barrier unavailable raw=%p\\n", raw_oop);
        abort();
    }
    return ((neko_sh_lrb_strong_t)g_neko_barrier_load_oop_field_preloaded)(raw_oop);
}

static neko_oop_array_load_barrier_t g_neko_oop_array_load_barrier = neko_barrier_load_oop_array_unavailable;

static void neko_select_oop_array_load_barrier(void) {
    if (!g_neko_gc_barrier_ready) {
        fprintf(stderr, "[neko-direct] GC barrier layout unavailable for object array load kind=%d\\n",
            g_neko_gc_barrier_kind);
        abort();
    }
    if (g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_CARDTABLE
        || g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_G1) {
        g_neko_oop_array_load_barrier = neko_barrier_load_oop_array_raw;
        return;
    }
    if (g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_Z) {
        if (g_neko_barrier_load_oop_array == NULL) {
            fprintf(stderr, "[neko-direct] ZGC object array load barrier symbol missing\\n");
            abort();
        }
        g_neko_oop_array_load_barrier = neko_barrier_load_oop_array_z;
        return;
    }
    if (g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_SHENANDOAH) {
        if (g_neko_barrier_load_oop_field_preloaded == NULL) {
            fprintf(stderr, "[neko-direct] Shenandoah object array load barrier symbol missing\\n");
            abort();
        }
        g_neko_oop_array_load_barrier = neko_barrier_load_oop_array_shenandoah;
        return;
    }
    fprintf(stderr, "[neko-direct] unsupported GC barrier kind for object array load: %d\\n",
        g_neko_gc_barrier_kind);
    abort();
}

/* T4.14 perf — same kind-switched inline dispatch as the field-load
 * variant above. AALOAD object-array reads are the dominant call shape in
 * any nested-array hot loop (matrix multiply, sparse linear algebra,
 * graph traversals, etc.); collapsing the function-pointer call to an
 * identity branch for non-moving GCs is the single biggest perf
 * mechanism that applies to every translated method without requiring
 * any per-method or per-class specialization. */
NEKO_FORCE_INLINE void *neko_barrier_load_oop_array(void *element_addr, void *raw_oop) {
    int kind;
    if (raw_oop == NULL) return NULL;
    kind = g_neko_gc_barrier_kind;
    if (kind == NEKO_EARLY_GC_BARRIER_CARDTABLE || kind == NEKO_EARLY_GC_BARRIER_G1) {
        return raw_oop;
    }
    return g_neko_oop_array_load_barrier(element_addr, raw_oop);
}

/* T4.14 perf — `neko_card_mark_field` runs on every CardTable / G1
 * post-write barrier; making it inline eliminates a per-AASTORE / per-
 * PUTFIELD-object call overhead. Generic to all object-store sites. */
NEKO_FORCE_INLINE void neko_card_mark_field(void *field_addr) {
    uintptr_t card;
    if (field_addr == NULL
        || g_neko_card_table_byte_map_base == NULL
        || g_neko_card_table_shift < 0
        || g_neko_card_table_dirty_card < 0) {
        fprintf(stderr, "[neko-direct] card table field store barrier unavailable addr=%p base=%p shift=%d dirty=%d\\n",
            field_addr, g_neko_card_table_byte_map_base, g_neko_card_table_shift, g_neko_card_table_dirty_card);
        abort();
    }
    card = ((uintptr_t)field_addr) >> (unsigned)g_neko_card_table_shift;
    ((volatile int8_t*)g_neko_card_table_byte_map_base)[card] = (int8_t)g_neko_card_table_dirty_card;
}

static void neko_barrier_pre_store_oop_field_unavailable(void *thread, void *field_addr, void *old_oop) {
    fprintf(stderr, "[neko-direct] object field pre-store barrier not selected kind=%d thread=%p addr=%p old=%p\\n",
        g_neko_gc_barrier_kind, thread, field_addr, old_oop);
    abort();
}

static void neko_barrier_post_store_oop_field_unavailable(void *thread, void *field_addr) {
    fprintf(stderr, "[neko-direct] object field post-store barrier not selected kind=%d thread=%p addr=%p\\n",
        g_neko_gc_barrier_kind, thread, field_addr);
    abort();
}

static void neko_barrier_pre_store_oop_field_noop(void *thread, void *field_addr, void *old_oop) {
    (void)thread; (void)field_addr; (void)old_oop;
}

static void neko_barrier_post_store_oop_field_card(void *thread, void *field_addr) {
    (void)thread;
    neko_card_mark_field(field_addr);
}

static void neko_barrier_pre_store_oop_field_g1(void *thread, void *field_addr, void *old_oop) {
    (void)field_addr;
    if (g_neko_barrier_write_ref_field_pre != NULL && old_oop != NULL) {
        ((neko_write_ref_field_pre_t)g_neko_barrier_write_ref_field_pre)(old_oop, thread);
    }
}

static void neko_barrier_post_store_oop_field_g1(void *thread, void *field_addr) {
    if (g_neko_barrier_write_ref_field_post != NULL) {
        ((neko_write_ref_field_post_t)g_neko_barrier_write_ref_field_post)(field_addr, thread);
        return;
    }
    neko_card_mark_field(field_addr);
}

static void neko_barrier_post_store_oop_field_z(void *thread, void *field_addr) {
    (void)thread;
    if (g_neko_barrier_store_oop_field == NULL || field_addr == NULL) {
        fprintf(stderr, "[neko-direct] ZGC object field store barrier unavailable addr=%p\\n", field_addr);
        abort();
    }
    ((neko_z_store_field_t)g_neko_barrier_store_oop_field)((void**)field_addr);
}

static void neko_barrier_pre_store_oop_field_shenandoah(void *thread, void *field_addr, void *old_oop) {
    (void)field_addr;
    if (g_neko_barrier_write_ref_field_pre == NULL) {
        fprintf(stderr, "[neko-direct] Shenandoah object field store barrier unavailable addr=%p old=%p\\n",
            field_addr, old_oop);
        abort();
    }
    if (old_oop != NULL) {
        ((neko_write_ref_field_pre_t)g_neko_barrier_write_ref_field_pre)(old_oop, thread);
    }
}

static void neko_barrier_post_store_oop_field_shenandoah(void *thread, void *field_addr) {
    (void)thread;
    if (g_neko_card_table_byte_map_base != NULL) {
        neko_card_mark_field(field_addr);
    }
}

static neko_oop_field_store_pre_barrier_t g_neko_oop_field_store_pre_barrier = neko_barrier_pre_store_oop_field_unavailable;
static neko_oop_field_store_post_barrier_t g_neko_oop_field_store_post_barrier = neko_barrier_post_store_oop_field_unavailable;

static void neko_select_oop_field_store_barrier(void) {
    if (!g_neko_gc_barrier_ready) {
        fprintf(stderr, "[neko-direct] GC barrier layout unavailable for object field store kind=%d\\n",
            g_neko_gc_barrier_kind);
        abort();
    }
    if (g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_CARDTABLE) {
        g_neko_oop_field_store_pre_barrier = neko_barrier_pre_store_oop_field_noop;
        g_neko_oop_field_store_post_barrier = neko_barrier_post_store_oop_field_card;
        return;
    }
    if (g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_G1) {
        g_neko_oop_field_store_pre_barrier = neko_barrier_pre_store_oop_field_g1;
        g_neko_oop_field_store_post_barrier = neko_barrier_post_store_oop_field_g1;
        return;
    }
    if (g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_Z) {
        if (g_neko_barrier_store_oop_field == NULL) {
            fprintf(stderr, "[neko-direct] ZGC object field store barrier symbol missing\\n");
            abort();
        }
        g_neko_oop_field_store_pre_barrier = neko_barrier_pre_store_oop_field_noop;
        g_neko_oop_field_store_post_barrier = neko_barrier_post_store_oop_field_z;
        return;
    }
    if (g_neko_gc_barrier_kind == NEKO_EARLY_GC_BARRIER_SHENANDOAH) {
        if (g_neko_barrier_write_ref_field_pre == NULL) {
            fprintf(stderr, "[neko-direct] Shenandoah object field store barrier symbol missing\\n");
            abort();
        }
        g_neko_oop_field_store_pre_barrier = neko_barrier_pre_store_oop_field_shenandoah;
        g_neko_oop_field_store_post_barrier = neko_barrier_post_store_oop_field_shenandoah;
        return;
    }
    fprintf(stderr, "[neko-direct] unsupported GC barrier kind for object field store: %d\\n",
        g_neko_gc_barrier_kind);
    abort();
}

/* T4.14 perf — kind-switched inline dispatch for the store-side barriers.
 * CardTable: pre = noop, post = card-mark (one byte write).
 * G1: pre = SATB enqueue when old != NULL, post = card-mark or post-entry.
 * Both run a tight, predictable code path that the C compiler can fully
 * inline; the prior function-pointer dispatcher prevented the inliner
 * from reaching the actual write logic. ZGC / Shenandoah retain their
 * dlsym'd entries because their barriers genuinely require runtime calls.
 * Generic to every PUTFIELD-object / AASTORE / PUTSTATIC-object site in
 * every translated method. */
NEKO_FORCE_INLINE void neko_barrier_pre_store_oop_field(void *thread, void *field_addr, void *old_oop) {
    int kind = g_neko_gc_barrier_kind;
    if (kind == NEKO_EARLY_GC_BARRIER_CARDTABLE) {
        (void)thread; (void)field_addr; (void)old_oop;
        return;
    }
    if (kind == NEKO_EARLY_GC_BARRIER_G1) {
        if (g_neko_barrier_write_ref_field_pre != NULL && old_oop != NULL) {
            ((neko_write_ref_field_pre_t)g_neko_barrier_write_ref_field_pre)(old_oop, thread);
        }
        return;
    }
    g_neko_oop_field_store_pre_barrier(thread, field_addr, old_oop);
}

NEKO_FORCE_INLINE void neko_barrier_post_store_oop_field(void *thread, void *field_addr) {
    int kind = g_neko_gc_barrier_kind;
    if (kind == NEKO_EARLY_GC_BARRIER_CARDTABLE) {
        (void)thread;
        neko_card_mark_field(field_addr);
        return;
    }
    if (kind == NEKO_EARLY_GC_BARRIER_G1) {
        if (g_neko_barrier_write_ref_field_post != NULL) {
            ((neko_write_ref_field_post_t)g_neko_barrier_write_ref_field_post)(field_addr, thread);
            return;
        }
        neko_card_mark_field(field_addr);
        return;
    }
    g_neko_oop_field_store_post_barrier(thread, field_addr);
}

/* T4.14 perf — `neko_handle_oop` is called once per fused-array helper
 * call (≥7M times in matrix-multiply Seq). Kept at NEKO_FAST_INLINE
 * (not NEKO_FORCE_INLINE) because aggressive inlining of this helper
 * across `neko_fast_monitor_enter` exposes a register-allocator/stack
 * alignment regression that crashes the Runtime1 monitor stub. The
 * inner-loop helpers below (`neko_load_object_array_slot`, fused-array
 * helpers, etc.) still force inlining of *their* call sites; the C
 * compiler's normal `static inline` heuristics handle the monitor path
 * without breaking. */
NEKO_FAST_INLINE void* neko_handle_oop(jobject handle) {
    uintptr_t raw;
    uintptr_t slot;
    if (handle == NULL) return NULL;
    raw = (uintptr_t)handle;
    /* Direct call_stub entry can re-enter translated native code with raw
     * HotSpot oops in Java heap registers/stack slots. JNI handle slots live
     * in native stack or JNIHandleBlock memory, which is far above the zero-
     * based compressed-oops heap used by the supported JDK 21 test targets.
     * Treat low aligned references as already-unwrapped oops so nested direct
     * calls do not dereference the object mark word as a handle slot. */
    if (neko_ref_is_direct_oop(handle)) {
        return neko_zgc_good_oop((void*)raw);
    }
    slot = (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_HANDLE_TAGS) != 0 ? (raw & ~(uintptr_t)0x3u) : raw;
    return neko_barrier_oop_load(*(void**)slot);
}

NEKO_FAST_INLINE void* neko_static_base_oop(jobject staticBase) {
    uintptr_t raw;
    uintptr_t untagged;
    if (staticBase == NULL) return NULL;
    if (neko_ref_is_direct_oop(staticBase)) {
        return neko_zgc_good_oop((void*)staticBase);
    }
    raw = (uintptr_t)staticBase;
    untagged = raw & ~(uintptr_t)0x3u;
    return neko_barrier_oop_load(*(void**)untagged);
}

NEKO_FORCE_INLINE jint neko_fast_array_length(jarray arr) {
    if (g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc)
        && g_hotspot.array_length_offset >= 0
        && arr != NULL) {
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL) {
            return *(jint*)(oop + g_hotspot.array_length_offset);
        }
    }
    fprintf(stderr, "[neko-direct] ARRAYLENGTH direct path unavailable arr=%p\\n", (void*)arr);
    abort();
}

NEKO_FAST_INLINE jboolean neko_receiver_key_supported(void) {
    return g_hotspot.initialized
        && g_hotspot.use_compact_object_headers == JNI_FALSE
        && ((g_hotspot.fast_bits & NEKO_FAST_RECEIVER_KEY) != 0
            || (g_hotspot.use_zgc && g_hotspot.klass_offset_bytes > 0));
}

NEKO_FAST_INLINE uintptr_t neko_receiver_key(jobject obj) {
    char *oop;
    char *klassAddr;
    if (obj == NULL || !neko_receiver_key_supported()) return (uintptr_t)0;
    oop = (char*)neko_handle_oop(obj);
    if (oop == NULL || g_hotspot.klass_offset_bytes <= 0) return (uintptr_t)0;
    klassAddr = oop + g_hotspot.klass_offset_bytes;
    if (g_hotspot.use_compressed_klass_ptrs) {
        return (uintptr_t)(*(uint32_t*)klassAddr);
    }
    return *(uintptr_t*)klassAddr;
}

typedef jvalue (*neko_icache_direct_stub)(void *thread, JNIEnv *env, jobject receiver, const jvalue *args);

/* neko_njx_dispatcher_t was already typedef'd in the
 * NativeToJavaInvokeEmitter prelude (rendered before renderHotSpotSupport).
 * The per-shape dispatcher functions are forward-declared there too. */

typedef struct {
    const char *name;
    const char *desc;
    const jclass *translated_class_slot;
    neko_icache_direct_stub translated_stub;
    jboolean is_interface;
    /* Per-shape direct dispatcher. NULL means this site isn't wired for
     * direct invoke (the shape wasn't registered by the codegen). When
     * non-NULL, neko_icache_dispatch will route through it once we have a
     * resolved Method* + compiled-entry pair for the receiver class. */
    neko_njx_dispatcher_t direct_dispatcher;
} neko_icache_meta;

NEKO_FAST_INLINE char neko_icache_return_kind(const char *desc) {
    const char *ret = desc == NULL ? NULL : strrchr(desc, ')');
    return (ret != NULL && ret[1] != '\\0') ? ret[1] : 'V';
}

NEKO_FAST_INLINE int neko_icache_find_slot(neko_icache_site *site, uintptr_t receiverKey) {
    uint32_t i;
    if (site == NULL || receiverKey == 0) return -1;
    for (i = 0u; i < NEKO_ICACHE_PIC_SIZE; i++) {
        if (site->target_kind[i] != NEKO_ICACHE_EMPTY && site->receiver_key[i] == receiverKey) {
            return (int)i;
        }
    }
    return -1;
}

NEKO_FAST_INLINE uint32_t neko_icache_claim_slot(JNIEnv *env, neko_icache_site *site, uintptr_t receiverKey) {
    uint32_t i;
    int existing;
    if (site == NULL) return 0u;
    existing = neko_icache_find_slot(site, receiverKey);
    if (existing >= 0) return (uint32_t)existing;
    for (i = 0u; i < NEKO_ICACHE_PIC_SIZE; i++) {
        if (site->target_kind[i] == NEKO_ICACHE_EMPTY) return i;
    }
    i = (uint32_t)(site->next_slot++ & (NEKO_ICACHE_PIC_SIZE - 1u));
    if (site->cached_class[i] != NULL) g_neko_jni_delete_global_ref_fn(env, site->cached_class[i]);
    site->receiver_key[i] = 0;
    site->target[i] = NULL;
    site->target2[i] = NULL;
    site->cached_class[i] = NULL;
    site->target_kind[i] = NEKO_ICACHE_EMPTY;
    return i;
}

/* Forward decls: defined later in the methodPatcherEmitter region. */
extern jboolean g_neko_direct_invoke_ready;

/* Runtime gate for direct-NJX diagnostics. Direct invoke is mandatory;
 * NEKO_DIRECT_DEBUG=1 enables the final one-line stats report. */
static int g_neko_njx_enabled_cached = 1;
static int g_neko_njx_enabled_initialized = 0;
static int g_neko_njx_debug_cached = 0;
static volatile uint64_t g_neko_njx_dispatch_count = 0;
static volatile uint64_t g_neko_njx_resolve_fail_count = 0;
static volatile int g_neko_njx_stats_printed = 0;
NEKO_FAST_INLINE int neko_njx_debug(void) { return g_neko_njx_debug_cached; }
NEKO_FAST_INLINE int neko_njx_enabled(void) {
    if (__builtin_expect(!g_neko_njx_enabled_initialized, 0)) {
        g_neko_njx_enabled_cached = 1;
        const char *d = getenv("NEKO_DIRECT_DEBUG");
        g_neko_njx_debug_cached = (d != NULL && d[0] != '\\0' && d[0] != '0') ? 1 : 0;
        g_neko_njx_enabled_initialized = 1;
    }
    return g_neko_njx_enabled_cached;
}

NEKO_FAST_INLINE void neko_njx_note_dispatch(void) {
    if (__builtin_expect(neko_njx_debug(), 0)) {
        __atomic_fetch_add(&g_neko_njx_dispatch_count, 1, __ATOMIC_RELAXED);
    }
}

NEKO_FAST_INLINE void neko_njx_note_resolve_fail(void) {
    if (__builtin_expect(neko_njx_debug(), 0)) {
        __atomic_fetch_add(&g_neko_njx_resolve_fail_count, 1, __ATOMIC_RELAXED);
    }
}

#define NEKO_DIRECT_LOG(fmt, ...) do { } while (0)

NEKO_FAST_INLINE void neko_icache_store_direct(JNIEnv *env, neko_icache_site *site, uintptr_t receiverKey, jclass cachedClass, void *target) {
    uint32_t slot;
    if (site == NULL) return;
    slot = neko_icache_claim_slot(env, site, receiverKey);
    if (site->cached_class[slot] != NULL && site->cached_class[slot] != cachedClass) g_neko_jni_delete_global_ref_fn(env, site->cached_class[slot]);
    site->cached_class[slot] = cachedClass;
    site->receiver_key[slot] = receiverKey;
    site->target[slot] = target;
    site->target2[slot] = NULL;
    site->target_kind[slot] = NEKO_ICACHE_DIRECT_C;
}

/* Cache a resolved (Method*, _from_compiled_entry) pair for the receiver
 * class. Subsequent dispatches to the same class skip the JNI GetMethodID
 * and invoke the per-shape dispatcher (from meta) directly with the cached
 * entry pointer. */
NEKO_FAST_INLINE void neko_icache_store_direct_njx(JNIEnv *env, neko_icache_site *site, uintptr_t receiverKey, jclass cachedClass, void *method_ptr, void *compiled_entry) {
    uint32_t slot;
    if (site == NULL) return;
    slot = neko_icache_claim_slot(env, site, receiverKey);
    if (site->cached_class[slot] != NULL && site->cached_class[slot] != cachedClass) g_neko_jni_delete_global_ref_fn(env, site->cached_class[slot]);
    site->cached_class[slot] = cachedClass;
    site->receiver_key[slot] = receiverKey;
    site->target[slot] = method_ptr;
    site->target2[slot] = compiled_entry;
    site->target_kind[slot] = NEKO_ICACHE_DIRECT_NJX;
}

NEKO_FAST_INLINE jboolean neko_icache_note_miss(JNIEnv *env, neko_icache_site *site) {
    if (site == NULL) return JNI_FALSE;
    if (site->miss_count < (uint16_t)0xFFFFu) site->miss_count++;
    (void)env;
    return JNI_FALSE;
}

/* Forward decls for direct-NJX helpers — actual definitions live in the
 * NativeToJavaInvokeEmitter prelude/body regions. */
static int neko_njx_resolve_method_entry(void *method, void **out_method, void **out_entry);
static int neko_njx_resolve_entry(jmethodID mid, void **out_method, void **out_entry);

static jvalue neko_icache_dispatch(
    void *thread,
    JNIEnv *env,
    neko_icache_site *site,
    const neko_icache_meta *meta,
    jobject receiver,
    jmethodID declared_mid,
    const jvalue *args
) {
    jvalue result = {0};
    uintptr_t receiverKey;
    void *receiverKlass;
    void *__receiver_jni_slot = NULL;
    jobject receiver_jni;
    if (env == NULL || receiver == NULL || declared_mid == NULL) return result;
    if (meta == NULL || meta->name == NULL || meta->desc == NULL || meta->direct_dispatcher == NULL) {
        fprintf(stderr, "[neko-direct] virtual dispatch metadata incomplete receiver=%p site=%p\\n",
            receiver, (void*)site);
        abort();
    }
    receiverKlass = neko_object_handle_klass(receiver);
    if (receiverKlass == NULL) {
        fprintf(stderr, "[neko-direct] receiver Klass unavailable for %s%s receiver=%p\\n",
            meta->name, meta->desc, receiver);
        abort();
    }
    receiver_jni = receiver;
    if (neko_ref_is_direct_oop(receiver)) {
        __receiver_jni_slot = (void*)receiver;
        receiver_jni = (jobject)&__receiver_jni_slot;
    }
    if (site != NULL && neko_receiver_key_supported()) {
        int cacheSlot;
        receiverKey = neko_receiver_key(receiver);
        if (receiverKey != 0) {
            cacheSlot = neko_icache_find_slot(site, receiverKey);
            if (cacheSlot >= 0) {
                if (site->target_kind[cacheSlot] == NEKO_ICACHE_DIRECT_C && site->target[cacheSlot] != NULL) {
                    return ((neko_icache_direct_stub)site->target[cacheSlot])(thread, env, receiver_jni, args);
                }
                if (site->target_kind[cacheSlot] == NEKO_ICACHE_DIRECT_NJX && site->target[cacheSlot] != NULL && site->target2[cacheSlot] != NULL
                    && neko_njx_enabled()) {
                    return meta->direct_dispatcher(thread, env, site->target[cacheSlot], site->target2[cacheSlot], receiver, args);
                }
            }
            if (!neko_icache_note_miss(env, site)) {
                jclass translatedClass = (meta->translated_class_slot != NULL) ? *meta->translated_class_slot : NULL;
                if (translatedClass != NULL && meta->translated_stub != NULL) {
                    void *translatedKlass = neko_class_mirror_to_klass(translatedClass);
                    if (translatedKlass == receiverKlass) {
                        neko_icache_store_direct(env, site, receiverKey, NULL, (void*)meta->translated_stub);
                        return meta->translated_stub(thread, env, receiver_jni, args);
                    }
                }
                /* Resolve the concrete target through HotSpot metadata, not
                 * JNI GetMethodID. This is required for interface-declared
                 * JDK targets such as ExecutorService.shutdown(): decoding a
                 * JNI jmethodID as a native Method* cell can return without
                 * executing the concrete method body. */
                jclass exactMirror = (jclass)neko_klass_java_mirror_handle(thread, receiverKlass);
                if (exactMirror == NULL) {
                    fprintf(stderr, "[neko-direct] receiver mirror unavailable for %s%s klass=%p\\n",
                        meta->name, meta->desc, receiverKlass);
                    abort();
                }
                neko_link_class_methods(env, exactMirror, "<virtual>", meta->name, meta->desc);
                void *exactMethod = neko_resolve_method(receiverKlass, meta->name, meta->desc);
                g_neko_jni_delete_local_ref_fn(env, exactMirror);
                if (neko_njx_enabled() && g_neko_direct_invoke_ready) {
                    void *m_ptr = NULL, *m_entry = NULL;
                    if (neko_njx_resolve_method_entry(exactMethod, &m_ptr, &m_entry)) {
                        neko_icache_store_direct_njx(env, site, receiverKey, NULL, m_ptr, m_entry);
                        result = meta->direct_dispatcher(thread, env, m_ptr, m_entry, receiver, args);
                        return result;
                    }
                    neko_njx_note_resolve_fail();
                    fprintf(stderr, "[neko-direct] resolve-failed %s%s method=%p\\n",
                        meta->name, meta->desc, exactMethod);
                    abort();
                }
                fprintf(stderr, "[neko-direct] direct invoke unavailable for %s%s\\n", meta->name, meta->desc);
                abort();
            }
        }
    }
    neko_njx_note_resolve_fail();
    fprintf(stderr, "[neko-direct] unresolved virtual dispatch %s%s declared_mid=%p receiver=%p site=%p\\n",
        meta->name, meta->desc, declared_mid, receiver, (void*)site);
    abort();
    return result;
}

__attribute__((used)) static void neko_njx_dump_stats_at_exit(void) {
    uint64_t hits;
    uint64_t fails;
    if (!neko_njx_debug()) return;
    hits = __atomic_load_n(&g_neko_njx_dispatch_count, __ATOMIC_RELAXED);
    fails = __atomic_load_n(&g_neko_njx_resolve_fail_count, __ATOMIC_RELAXED);
    if (hits == 0 && fails == 0) return;
    if (!__sync_bool_compare_and_swap(&g_neko_njx_stats_printed, 0, 1)) return;
    fprintf(stderr, "[neko-direct] stats: dispatched=%llu resolve_failed=%llu\\n",
        (unsigned long long)hits, (unsigned long long)fails);
}
__attribute__((constructor)) static void neko_njx_register_atexit(void) {
    atexit(neko_njx_dump_stats_at_exit);
}

""");
        appendPrimitiveFieldHelpers(sb, 'Z', "jboolean", "boolean");
        appendPrimitiveFieldHelpers(sb, 'B', "jbyte", "byte");
        appendPrimitiveFieldHelpers(sb, 'C', "jchar", "char");
        appendPrimitiveFieldHelpers(sb, 'S', "jshort", "short");
        appendPrimitiveFieldHelpers(sb, 'I', "jint", "int");
        appendPrimitiveFieldHelpers(sb, 'J', "jlong", "long");
        appendPrimitiveFieldHelpers(sb, 'F', "jfloat", "float");
        appendPrimitiveFieldHelpers(sb, 'D', "jdouble", "double");
        appendPrimitiveArrayHelpers(sb, "z", "jboolean", "boolean", "NEKO_PRIM_Z");
        appendPrimitiveArrayHelpers(sb, "b", "jbyte", "byte", "NEKO_PRIM_B");
        appendPrimitiveArrayHelpers(sb, "c", "jchar", "char", "NEKO_PRIM_C");
        appendPrimitiveArrayHelpers(sb, "s", "jshort", "short", "NEKO_PRIM_S");
        appendPrimitiveArrayHelpers(sb, "i", "jint", "int", "NEKO_PRIM_I");
        appendPrimitiveArrayHelpers(sb, "l", "jlong", "long", "NEKO_PRIM_J");
        appendPrimitiveArrayHelpers(sb, "f", "jfloat", "float", "NEKO_PRIM_F");
        appendPrimitiveArrayHelpers(sb, "d", "jdouble", "double", "NEKO_PRIM_D");
        return sb.toString();
    }

    /**
     * Emit fast paths for AALOAD / AASTORE that avoid the per-element JNI
     * GetObjectArrayElement / SetObjectArrayElement round-trip. The slow JNI
     * path allocates a fresh local-ref handle for every load — for a tight
     * matrix-multiply inner loop this is the single biggest cost (millions of
     * handle allocations chained into JNIHandleBlock _next blocks).
     *
     * Fast path: read the element oop directly from the array's narrow-oop /
     * wide-oop slot and push it into the active JNIHandleBlock via the same
     * inlined neko_handle_push the dispatcher uses for ref args. Falls back
     * to the JNI version if VMStructs didn't recover all the bits we need
     * (compressed-oops shift, primitive-array layout — used as a stand-in for
     * object-array base since both are 16 bytes on 64-bit hotspot with
     * compressed klass pointers, the only configuration that ships today).
     *
     * Emitted AFTER methodPatcherEmitter so the inline neko_handle_push is
     * already in scope.
     */
    public String renderObjectArrayFastHelpers() {
        StringBuilder sb = new StringBuilder();
        appendObjectArrayHelpers(sb);
        return sb.toString();
    }

    private void appendObjectArrayHelpers(StringBuilder sb) {
        sb.append("""
static uintptr_t g_neko_string_klass_bits = 0;
static uintptr_t g_neko_byte_array_klass_bits = 0;
static jboolean g_neko_fast_string_alloc_ready = JNI_FALSE;

/* TLAB-NULL fix: NJX-cached java.lang.String.concat(String) Method* + entry
 * pointer used as a non-JNI fallback when neko_fast_tlab_alloc cannot
 * allocate the String/byte[] pair (TLAB retired by HotSpot's slow allocator
 * during the previous JVM_NewArray refill attempt). NJX call_stub runs
 * Java bytecode under HotSpot's normal _thread_in_java semantics, so HotSpot
 * itself handles TLAB initialization / refill — no JNI is involved. */
static void *g_neko_string_concat_method = NULL;
static void *g_neko_string_concat_entry = NULL;
static jboolean g_neko_string_concat_ready = JNI_FALSE;

/* TLAB-NULL fix for NEW: NJX-cached jdk.internal.misc.Unsafe instance +
 * allocateInstance(Class) Method* + entry pointer. Same rationale as the
 * String.concat fallback — when neko_fast_tlab_alloc fails for instance
 * allocation, route through Unsafe.allocateInstance which lets HotSpot
 * own the TLAB lifecycle. theInternalUnsafe is captured as a global ref
 * via the T4.8 captured NewGlobalRef pointer so it survives across
 * impl_fn frames. */
static jobject g_neko_unsafe_instance_global = NULL;
static void *g_neko_unsafe_allocate_instance_method = NULL;
static void *g_neko_unsafe_allocate_instance_entry = NULL;
static jboolean g_neko_unsafe_allocate_instance_ready = JNI_FALSE;

NEKO_FAST_INLINE jobject neko_direct_oop_to_handle(void *thread, void *raw_oop);

/* T4.7 — `neko_fast_string_runtime_init` deleted. The probe used JNI
 * function-table indices 167 (NewStringUTF) and 176 (NewByteArray) to
 * derive `g_neko_string_klass_bits` / `g_neko_byte_array_klass_bits`
 * from a freshly allocated empty String + byte[] pair. The VMStructs
 * path `neko_ensure_string_alloc_bits` (rendered earlier in this file)
 * is authoritative: it resolves `java/lang/String` via libjvm-internal
 * `JVM_FindClassFromBootLoader`, reads its Klass bits, and sets the
 * byte-array bits from `g_hotspot.primitive_array_klass_bits[NEKO_PRIM_B]`.
 * `neko_method_layout_init` now drives that path instead of the probe;
 * any missing prerequisite aborts there. */

NEKO_FAST_INLINE size_t neko_align_object_bytes(size_t bytes) {
    size_t alignment = (size_t)(g_hotspot.object_alignment_in_bytes > 0 ? g_hotspot.object_alignment_in_bytes : 8);
    return (bytes + alignment - 1u) & ~(alignment - 1u);
}

NEKO_FAST_INLINE void *neko_fast_tlab_alloc(void *thread, size_t bytes) {
    char *tlab;
    char *top;
    char *end;
    char *new_top;
    size_t aligned;
    if (!g_neko_tlab_alloc_ready || thread == NULL || bytes == 0) return NULL;
    aligned = neko_align_object_bytes(bytes);
    tlab = (char*)thread + g_neko_off_thread_tlab;
    top = *(char**)(tlab + g_neko_off_tlab_top);
    end = *(char**)(tlab + g_neko_off_tlab_end);
    if (top == NULL || end == NULL || top > end || aligned > (size_t)(end - top)) return NULL;
    new_top = top + aligned;
    *(char**)(tlab + g_neko_off_tlab_top) = new_top;
    memset(top, 0, aligned);
    return top;
}

NEKO_FAST_INLINE void neko_init_oop_header(char *oop, uintptr_t klass_bits) {
    *(uintptr_t*)oop = (uintptr_t)1u;
    if (g_hotspot.use_compressed_klass_ptrs) {
        *(uint32_t*)(oop + g_hotspot.klass_offset_bytes) = (uint32_t)klass_bits;
    } else {
        *(uintptr_t*)(oop + g_hotspot.klass_offset_bytes) = klass_bits;
    }
}

/* T4.14 perf — `neko_decode_narrow_oop` participates in every compressed-
 * oops field/array load. Force-inline so the shift+add compresses into
 * 2 instructions per call site. */
NEKO_FORCE_INLINE void* neko_decode_narrow_oop(uint32_t narrow) {
    if (narrow == 0) return NULL;
    return neko_barrier_oop_load((void*)((uintptr_t)((uintptr_t)narrow << g_hotspot.compressed_oops_shift)
                   + (uintptr_t)g_hotspot.compressed_oops_base));
}

NEKO_FAST_INLINE uint32_t neko_encode_narrow_oop(void *oop) {
    if (oop == NULL) return 0u;
    return (uint32_t)(((uintptr_t)oop - (uintptr_t)g_hotspot.compressed_oops_base) >> g_hotspot.compressed_oops_shift);
}

NEKO_FAST_INLINE void neko_store_oop_raw(char *oop, jlong offset, void *value) {
    value = neko_zgc_good_oop(value);
    if (g_hotspot.compressed_oops_enabled) {
        *(uint32_t*)(oop + offset) = neko_encode_narrow_oop(value);
    } else {
        *(void**)(oop + offset) = value;
    }
}

NEKO_FAST_INLINE char *neko_string_value_oop(char *str_oop, jlong valueOffset) {
    if (str_oop == NULL || valueOffset <= 0) return NULL;
    if (g_hotspot.compressed_oops_enabled) {
        return (char*)neko_decode_narrow_oop(*(uint32_t*)(str_oop + valueOffset));
    }
    return (char*)neko_barrier_oop_load(*(void**)(str_oop + valueOffset));
}

NEKO_FAST_INLINE void neko_put_utf16_unit(uint8_t *dst, size_t index, uint16_t value) {
#if defined(__BYTE_ORDER__) && defined(__ORDER_BIG_ENDIAN__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
    dst[index * 2u] = (uint8_t)(value >> 8);
    dst[index * 2u + 1u] = (uint8_t)(value & 0xffu);
#else
    dst[index * 2u] = (uint8_t)(value & 0xffu);
    dst[index * 2u + 1u] = (uint8_t)(value >> 8);
#endif
}

NEKO_FAST_INLINE void neko_copy_string_payload_to_utf16(
    uint8_t *dst,
    size_t dst_unit_offset,
    const uint8_t *src,
    size_t src_bytes,
    jbyte src_coder
) {
    if (src_coder == 0) {
        for (size_t i = 0; i < src_bytes; i++) {
            neko_put_utf16_unit(dst, dst_unit_offset + i, (uint16_t)src[i]);
        }
    } else {
        memcpy(dst + (dst_unit_offset * 2u), src, src_bytes);
    }
}

NEKO_FAST_INLINE jobject neko_fast_string_concat(
    void *thread,
    JNIEnv *env,
    jstring left,
    jstring right,
    jlong valueOffset,
    jlong coderOffset
) {
    size_t base = (size_t)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B];
    char *left_oop;
    char *right_oop;
    char *left_value;
    char *right_value;
    jbyte left_coder;
    jbyte right_coder;
    jbyte result_coder;
    jint left_bytes_i;
    jint right_bytes_i;
    size_t left_bytes;
    size_t right_bytes;
    size_t left_units;
    size_t right_units;
    size_t total_units;
    size_t payload_bytes;
    char *array_oop;
    char *string_oop;
    uint8_t *payload;
    size_t array_bytes;
    size_t string_bytes;
    size_t ref_size = g_hotspot.compressed_oops_enabled ? 4u : sizeof(void*);
    if (!g_neko_fast_string_alloc_ready
        || (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) == 0
        || g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] <= 0
        || valueOffset <= 0
        || coderOffset <= 0
        || left == NULL
        || right == NULL) {
        return NULL;
    }
    left_oop = (char*)neko_handle_oop((jobject)left);
    right_oop = (char*)neko_handle_oop((jobject)right);
    if (left_oop == NULL || right_oop == NULL) return NULL;
    left_coder = *(jbyte*)(left_oop + coderOffset);
    right_coder = *(jbyte*)(right_oop + coderOffset);
    if ((left_coder != 0 && left_coder != 1) || (right_coder != 0 && right_coder != 1)) return NULL;
    left_value = neko_string_value_oop(left_oop, valueOffset);
    right_value = neko_string_value_oop(right_oop, valueOffset);
    if (left_value == NULL || right_value == NULL) return NULL;
    left_bytes_i = *(jint*)(left_value + base - 4u);
    right_bytes_i = *(jint*)(right_value + base - 4u);
    if (left_bytes_i < 0 || right_bytes_i < 0) return NULL;
    left_bytes = (size_t)left_bytes_i;
    right_bytes = (size_t)right_bytes_i;
    if ((left_coder == 1 && (left_bytes & 1u) != 0) || (right_coder == 1 && (right_bytes & 1u) != 0)) return NULL;
    left_units = left_coder == 0 ? left_bytes : left_bytes / 2u;
    right_units = right_coder == 0 ? right_bytes : right_bytes / 2u;
    if (left_units > (size_t)INT32_MAX - right_units) return NULL;
    total_units = left_units + right_units;
    result_coder = (left_coder == 0 && right_coder == 0) ? 0 : 1;
    if (result_coder == 1 && total_units > ((size_t)INT32_MAX / 2u)) return NULL;
    payload_bytes = result_coder == 0 ? total_units : total_units * 2u;
    array_bytes = base + payload_bytes;
    array_oop = (char*)neko_fast_tlab_alloc(thread, array_bytes);
    if (array_oop == NULL && env != NULL) {
        neko_refill_tlab_with_slow_byte_array(env, array_bytes > (size_t)INT32_MAX ? INT32_MAX : (jint)array_bytes);
        array_oop = (char*)neko_fast_tlab_alloc(thread, array_bytes);
    }
    if (array_oop == NULL) return NULL;
    neko_init_oop_header(array_oop, g_neko_byte_array_klass_bits);
    *(jint*)(array_oop + base - 4u) = (jint)payload_bytes;
    payload = (uint8_t*)array_oop + base;
    if (result_coder == 0) {
        if (left_bytes > 0) memcpy(payload, left_value + base, left_bytes);
        if (right_bytes > 0) memcpy(payload + left_bytes, right_value + base, right_bytes);
    } else {
        neko_copy_string_payload_to_utf16(payload, 0, (const uint8_t*)left_value + base, left_bytes, left_coder);
        neko_copy_string_payload_to_utf16(payload, left_units, (const uint8_t*)right_value + base, right_bytes, right_coder);
    }

    string_bytes = (size_t)valueOffset + ref_size;
    if ((size_t)coderOffset + 1u > string_bytes) string_bytes = (size_t)coderOffset + 1u;
    string_oop = (char*)neko_fast_tlab_alloc(thread, string_bytes);
    if (string_oop == NULL && env != NULL) {
        neko_refill_tlab_with_slow_byte_array(env, string_bytes > (size_t)INT32_MAX ? INT32_MAX : (jint)string_bytes);
        string_oop = (char*)neko_fast_tlab_alloc(thread, string_bytes);
    }
    if (string_oop == NULL) return NULL;
    neko_init_oop_header(string_oop, g_neko_string_klass_bits);
    neko_store_oop_raw(string_oop, valueOffset, array_oop);
    *(jbyte*)(string_oop + coderOffset) = result_coder;
    return neko_direct_oop_to_handle(thread, string_oop);
}

NEKO_FAST_INLINE jobject neko_require_fast_string_concat(
    void *thread,
    JNIEnv *env,
    jstring left,
    jstring right,
    jlong valueOffset,
    jlong coderOffset
) {
    jobject result = neko_fast_string_concat(thread, env, left, right, valueOffset, coderOffset);
    if (result != NULL) return result;
    /* TLAB-NULL fix: fast path failed because HotSpot's slow allocator
     * retired our TLAB (the JVM_NewArray refill path leaves _top=NULL on
     * exhausted TLABs in some HotSpot 21 configurations). Fall back to a
     * direct Java String.concat invocation through the NJX call_stub —
     * this is NOT a JNI fallback (no JNI function-table call, no
     * _thread_in_native ↔ _thread_in_java state ping-pong). HotSpot
     * itself owns the TLAB lifecycle inside Java code, so the
     * Java-side concat handles TLAB init / refill correctly. */
    if (g_neko_string_concat_ready && left != NULL && right != NULL) {
        jvalue concat_arg;
        jvalue concat_result;
        concat_arg.l = right;
        concat_result = neko_njx_V_L_L(thread, env,
            g_neko_string_concat_method, g_neko_string_concat_entry,
            left, &concat_arg);
        if (concat_result.l != NULL) return concat_result.l;
    }
    fprintf(stderr, "[neko-direct] native String concat unavailable left=%p right=%p valueOffset=%lld coderOffset=%lld string_concat_ready=%d\\n",
        (void*)left, (void*)right, (long long)valueOffset, (long long)coderOffset, (int)g_neko_string_concat_ready);
    abort();
}

NEKO_FAST_INLINE jobject neko_direct_oop_to_handle(void *thread, void *raw_oop) {
    if (raw_oop == NULL) return NULL;
    raw_oop = neko_zgc_good_oop(raw_oop);
    if (g_neko_handle_push_ready && thread != NULL) {
        void *block = *(void**)((char*)thread + g_neko_off_thread_active_handles);
        if (block != NULL) {
            int32_t *top_ptr = (int32_t*)((char*)block + g_neko_off_jnih_block_top);
            int32_t top = *top_ptr;
            if (top < g_neko_jnih_block_capacity) {
                void **handles = (void**)((char*)block + g_neko_off_jnih_block_handles);
                handles[top] = raw_oop;
                *top_ptr = top + 1;
                if (g_neko_method_layout.off_jnih_block_next > 0) {
                    ptrdiff_t off_last = g_neko_method_layout.off_jnih_block_next + 8;
                    *(void**)((char*)block + off_last) = block;
                }
                return (jobject)&handles[top];
            }
            if (g_neko_method_layout.sizeof_JNIHandleBlock > 0 && g_neko_method_layout.off_jnih_block_next > 0) {
                void *new_block = calloc(1, g_neko_method_layout.sizeof_JNIHandleBlock);
                if (new_block != NULL) {
                    void **new_handles = (void**)((char*)new_block + g_neko_off_jnih_block_handles);
                    new_handles[0] = raw_oop;
                    *(int32_t*)((char*)new_block + g_neko_off_jnih_block_top) = 1;
                    *(void**)((char*)new_block + g_neko_method_layout.off_jnih_block_next) = block;
                    {
                        ptrdiff_t off_last = g_neko_method_layout.off_jnih_block_next + 8;
                        *(void**)((char*)new_block + off_last) = new_block;
                    }
                    *(void**)((char*)thread + g_neko_off_thread_active_handles) = new_block;
                    return (jobject)&new_handles[0];
                }
            }
        }
    }
    fprintf(stderr, "[neko-direct] JNIHandleBlock direct slot unavailable thread=%p raw=%p\\n", thread, raw_oop);
    abort();
}

NEKO_FAST_INLINE jobjectArray neko_fast_new_object_array(void *thread, JNIEnv *env, jint len, uintptr_t klass_bits, jobject init) {
    if (len < 0) {
        fprintf(stderr, "[neko-direct] negative object array length %d\\n", (int)len);
        abort();
    }
    if (klass_bits != 0
        && g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) != 0 || g_hotspot.use_zgc)
        && g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] >= 0
        && !g_hotspot.use_compact_object_headers
        && g_neko_tlab_alloc_ready) {
        size_t ref_size = g_hotspot.compressed_oops_enabled ? 4u : sizeof(void*);
        size_t base = (size_t)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I];
        size_t bytes = base + ((size_t)len * ref_size);
        char *array_oop = (char*)neko_fast_tlab_alloc(thread, bytes);
        if (array_oop == NULL && env != NULL) {
            neko_refill_tlab_with_slow_byte_array(env, bytes > (size_t)INT32_MAX ? INT32_MAX : (jint)bytes);
            array_oop = (char*)neko_fast_tlab_alloc(thread, bytes);
        }
        if (array_oop != NULL) {
            neko_init_oop_header(array_oop, klass_bits);
            *(jint*)(array_oop + base - 4u) = len;
            memset(array_oop + base, 0, ((size_t)len * ref_size));
            if (init != NULL) {
                void *init_oop = neko_handle_oop(init);
                for (jint i = 0; i < len; i++) {
                    neko_store_oop_raw(array_oop, (jlong)(base + ((size_t)i * ref_size)), init_oop);
                }
            }
            return (jobjectArray)neko_direct_oop_to_handle(thread, array_oop);
        }
    }
    fprintf(stderr, "[neko-direct] object array allocation direct path unavailable len=%d klass=0x%llx thread=%p init=%d raw=%d zgc=%d coh=%d tlab=%d base=%d\\n",
        (int)len, (unsigned long long)klass_bits, thread,
        (int)g_hotspot.initialized,
        (int)((g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) != 0),
        (int)g_hotspot.use_zgc,
        (int)g_hotspot.use_compact_object_headers,
        (int)g_neko_tlab_alloc_ready,
        (int)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I]);
    abort();
}

NEKO_FAST_INLINE jarray neko_fast_new_primitive_array(void *thread, JNIEnv *env, jint len, int kind) {
    if (len < 0) {
        fprintf(stderr, "[neko-direct] negative primitive array length %d kind=%d\\n", (int)len, kind);
        abort();
    }
    if (kind >= 0 && kind < NEKO_PRIM_COUNT
        && g_hotspot.primitive_array_klass_bits[kind] != 0
        && g_hotspot.primitive_array_base_offsets[kind] >= 0
        && g_hotspot.primitive_array_index_scales[kind] > 0
        && g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) != 0 || g_hotspot.use_zgc)
        && !g_hotspot.use_compact_object_headers
        && g_neko_tlab_alloc_ready) {
        size_t base = (size_t)g_hotspot.primitive_array_base_offsets[kind];
        size_t scale = (size_t)g_hotspot.primitive_array_index_scales[kind];
        size_t bytes = base + ((size_t)len * scale);
        char *array_oop = (char*)neko_fast_tlab_alloc(thread, bytes);
        if (array_oop == NULL && env != NULL) {
            neko_refill_tlab_with_slow_byte_array(env, bytes > (size_t)INT32_MAX ? INT32_MAX : (jint)bytes);
            array_oop = (char*)neko_fast_tlab_alloc(thread, bytes);
        }
        if (array_oop != NULL) {
            neko_init_oop_header(array_oop, g_hotspot.primitive_array_klass_bits[kind]);
            *(jint*)(array_oop + base - 4u) = len;
            memset(array_oop + base, 0, ((size_t)len * scale));
            return (jarray)neko_direct_oop_to_handle(thread, array_oop);
        }
    }
    fprintf(stderr, "[neko-direct] primitive array allocation direct path unavailable len=%d kind=%d thread=%p init=%d klass=0x%llx base=%d scale=%d raw=%d zgc=%d coh=%d tlab=%d\\n",
        (int)len, kind, thread,
        (int)g_hotspot.initialized,
        (unsigned long long)((kind >= 0 && kind < NEKO_PRIM_COUNT) ? g_hotspot.primitive_array_klass_bits[kind] : 0),
        (int)((kind >= 0 && kind < NEKO_PRIM_COUNT) ? g_hotspot.primitive_array_base_offsets[kind] : -1),
        (int)((kind >= 0 && kind < NEKO_PRIM_COUNT) ? g_hotspot.primitive_array_index_scales[kind] : 0),
        (int)((g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) != 0),
        (int)g_hotspot.use_zgc,
        (int)g_hotspot.use_compact_object_headers,
        (int)g_neko_tlab_alloc_ready);
    abort();
}

NEKO_FAST_INLINE jobject neko_fast_alloc_object(void *thread, JNIEnv *env, jclass cls) {
    void *klass;
    jint layout_helper;
    size_t bytes;
    char *oop;
    uintptr_t klass_bits;
    if (thread == NULL || cls == NULL) {
        fprintf(stderr, "[neko-direct] NEW direct allocation missing thread/class thread=%p cls=%p\\n",
            thread, (void*)cls);
        abort();
    }
    if (!g_hotspot.initialized
        || (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) == 0
        || g_hotspot.use_compact_object_headers
        || g_hotspot.klass_offset_bytes <= 0
        || !g_neko_tlab_alloc_ready) {
        fprintf(stderr, "[neko-direct] NEW direct allocation layout unavailable thread=%p cls=%p\\n",
            thread, (void*)cls);
        abort();
    }
    if (g_neko_method_layout.off_klass_layout_helper < 0) {
        fprintf(stderr, "[neko-direct] Klass::_layout_helper offset unavailable for NEW\\n");
        abort();
    }
    klass = neko_class_mirror_to_klass(cls);
    if (klass == NULL) {
        fprintf(stderr, "[neko-direct] NEW class mirror did not resolve to Klass* cls=%p\\n", (void*)cls);
        abort();
    }
    layout_helper = *(jint*)((char*)klass + g_neko_method_layout.off_klass_layout_helper);
    if (layout_helper <= 0) {
        fprintf(stderr, "[neko-direct] NEW target is not an instance klass cls=%p klass=%p layout=%d\\n",
            (void*)cls, klass, (int)layout_helper);
        abort();
    }
    bytes = (size_t)layout_helper * sizeof(void*);
    klass_bits = neko_klass_header_bits(klass);
    if (klass_bits == 0 || bytes == 0) {
        fprintf(stderr, "[neko-direct] NEW klass bits/size unavailable cls=%p klass=%p bits=0x%llx bytes=%zu\\n",
            (void*)cls, klass, (unsigned long long)klass_bits, bytes);
        abort();
    }
    oop = (char*)neko_fast_tlab_alloc(thread, bytes);
    if (oop == NULL && env != NULL) {
        neko_refill_tlab_with_slow_byte_array(env, bytes > (size_t)INT32_MAX ? INT32_MAX : (jint)bytes);
        oop = (char*)neko_fast_tlab_alloc(thread, bytes);
    }
    if (oop == NULL) {
        /* TLAB-NULL fix: HotSpot's slow allocator retired our TLAB during
         * the previous JVM_NewArray refill; _top is now NULL. Fall back to
         * Unsafe.allocateInstance via NJX call_stub — runs Java bytecode
         * under HotSpot's normal _thread_in_java mode where TLAB lifecycle
         * is JVM-managed. NOT a JNI fallback (no JNI function-table call,
         * no thread-state ping-pong). */
        if (g_neko_unsafe_allocate_instance_ready
            && g_neko_unsafe_instance_global != NULL
            && cls != NULL && env != NULL) {
            jvalue alloc_arg;
            jvalue alloc_result;
            alloc_arg.l = cls;
            alloc_result = neko_njx_V_L_L(thread, env,
                g_neko_unsafe_allocate_instance_method,
                g_neko_unsafe_allocate_instance_entry,
                g_neko_unsafe_instance_global, &alloc_arg);
            if (alloc_result.l != NULL) return alloc_result.l;
        }
        fprintf(stderr, "[neko-direct] NEW TLAB allocation failed cls=%p klass=%p bytes=%zu unsafe_ready=%d\\n",
            (void*)cls, klass, bytes, (int)g_neko_unsafe_allocate_instance_ready);
        abort();
    }
    neko_init_oop_header(oop, klass_bits);
    return neko_direct_oop_to_handle(thread, oop);
}

NEKO_FAST_INLINE void *neko_raw_oop_klass(char *oop) {
    uintptr_t bits;
    if (oop == NULL) return NULL;
    if (!g_hotspot.initialized || g_hotspot.klass_offset_bytes <= 0) {
        fprintf(stderr, "[neko-direct] object klass layout unavailable oop=%p\\n", oop);
        abort();
    }
    if (g_hotspot.use_compressed_klass_ptrs) {
        bits = (uintptr_t)(*(uint32_t*)(oop + g_hotspot.klass_offset_bytes));
    } else {
        bits = *(uintptr_t*)(oop + g_hotspot.klass_offset_bytes);
    }
    return neko_decode_klass_header_bits(bits);
}

NEKO_FAST_INLINE void *neko_objarray_element_klass(char *array_oop) {
    void *array_klass;
    if (g_neko_method_layout.off_objarrayklass_element_klass < 0) {
        fprintf(stderr, "[neko-direct] ObjArrayKlass::_element_klass offset unavailable\\n");
        abort();
    }
    array_klass = neko_raw_oop_klass(array_oop);
    if (array_klass == NULL) return NULL;
    return *(void**)((char*)array_klass + g_neko_method_layout.off_objarrayklass_element_klass);
}

NEKO_FAST_INLINE jboolean neko_klass_is_subtype_of(void *sub_klass, void *super_klass) {
    void *klass;
    if (sub_klass == NULL || super_klass == NULL) return JNI_FALSE;
    if (sub_klass == super_klass) return JNI_TRUE;
    if (g_neko_method_layout.off_klass_super < 0
        || g_neko_method_layout.off_klass_secondary_super_cache < 0
        || g_neko_method_layout.off_klass_secondary_supers < 0
        || g_neko_method_layout.off_array_length < 0
        || g_neko_method_layout.off_array_data < 0) {
        fprintf(stderr, "[neko-direct] Klass subtype-check layout unavailable\\n");
        abort();
    }
    klass = sub_klass;
    for (int depth = 0; klass != NULL && depth < 256; depth++) {
        if (klass == super_klass) return JNI_TRUE;
        klass = *(void**)((char*)klass + g_neko_method_layout.off_klass_super);
    }
    {
        void *cached = *(void**)((char*)sub_klass + g_neko_method_layout.off_klass_secondary_super_cache);
        if (cached == super_klass) return JNI_TRUE;
    }
    {
        void *secondary = *(void**)((char*)sub_klass + g_neko_method_layout.off_klass_secondary_supers);
        if (secondary != NULL) {
            int len = *(int*)((char*)secondary + g_neko_method_layout.off_array_length);
            void **data = (void**)((char*)secondary + g_neko_method_layout.off_array_data);
            for (int i = 0; i < len; i++) {
                if (data[i] == super_klass) return JNI_TRUE;
            }
        }
    }
    return JNI_FALSE;
}

NEKO_FAST_INLINE jboolean neko_fast_is_instance_of(JNIEnv *env, jobject obj, jclass cls) {
    void *value_oop;
    void *value_klass;
    void *target_klass;
    (void)env;
    if (obj == NULL) return JNI_FALSE;
    if (cls == NULL) {
        fprintf(stderr, "[neko-direct] INSTANCEOF/CHECKCAST missing target class\\n");
        abort();
    }
    value_oop = neko_handle_oop(obj);
    if (value_oop == NULL) {
        fprintf(stderr, "[neko-direct] INSTANCEOF/CHECKCAST object handle did not resolve obj=%p\\n", (void*)obj);
        abort();
    }
    value_klass = neko_raw_oop_klass((char*)value_oop);
    target_klass = neko_class_mirror_to_klass(cls);
    if (target_klass == NULL) {
        fprintf(stderr, "[neko-direct] INSTANCEOF/CHECKCAST target mirror did not map to Klass* cls=%p\\n", (void*)cls);
        abort();
    }
    return neko_klass_is_subtype_of(value_klass, target_klass);
}

NEKO_FAST_INLINE jclass neko_fast_get_object_class(void *thread, jobject obj) {
    void *value_oop;
    void *value_klass;
    if (thread == NULL) {
        fprintf(stderr, "[neko-direct] getClass missing JavaThread\\n");
        abort();
    }
    if (obj == NULL) {
        fprintf(stderr, "[neko-direct] getClass called with null object\\n");
        abort();
    }
    value_oop = neko_handle_oop(obj);
    if (value_oop == NULL) {
        fprintf(stderr, "[neko-direct] getClass object handle did not resolve obj=%p\\n", (void*)obj);
        abort();
    }
    value_klass = neko_raw_oop_klass((char*)value_oop);
    if (value_klass == NULL) {
        fprintf(stderr, "[neko-direct] getClass object Klass* unavailable obj=%p\\n", (void*)obj);
        abort();
    }
    return (jclass)neko_klass_java_mirror_handle(thread, value_klass);
}

#define NEKO_MONITOR_RECORD_BYTES 64u

typedef struct {
    unsigned char basic_object_lock[NEKO_MONITOR_RECORD_BYTES];
    jobject handle;
} neko_monitor_record;

NEKO_FAST_INLINE void neko_call_runtime1_monitorenter(void *entry, void *thread, void *lock_record, void *obj_oop) {
#if defined(__x86_64__)
    __asm__ __volatile__(
        "pushq %%r15\\n\\t"
        "movq %[entry], %%r11\\n\\t"
        "movq %[thread], %%r15\\n\\t"
        "subq $24, %%rsp\\n\\t"
        "movq %[lock_record], (%%rsp)\\n\\t"
        "movq %[obj_oop], 8(%%rsp)\\n\\t"
        "call *%%r11\\n\\t"
        "addq $24, %%rsp\\n\\t"
        "popq %%r15\\n\\t"
        :
        : [entry] "r" (entry),
          [thread] "r" (thread),
          [lock_record] "r" (lock_record),
          [obj_oop] "r" (obj_oop)
        : "memory", "cc",
          "rax", "rcx", "rdx", "rsi", "rdi", "r8", "r9", "r10", "r11",
          "xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7",
          "xmm8", "xmm9", "xmm10", "xmm11", "xmm12", "xmm13", "xmm14", "xmm15");
#else
    (void)entry;
    (void)thread;
    (void)lock_record;
    (void)obj_oop;
    fprintf(stderr, "[neko-direct] Runtime1 monitor stubs unsupported on this architecture\\n");
    abort();
#endif
}

NEKO_FAST_INLINE void neko_call_runtime1_monitorexit(void *entry, void *thread, void *lock_record) {
#if defined(__x86_64__)
    __asm__ __volatile__(
        "pushq %%r15\\n\\t"
        "movq %[entry], %%r11\\n\\t"
        "movq %[thread], %%r15\\n\\t"
        "subq $8, %%rsp\\n\\t"
        "movq %[lock_record], (%%rsp)\\n\\t"
        "call *%%r11\\n\\t"
        "addq $8, %%rsp\\n\\t"
        "popq %%r15\\n\\t"
        :
        : [entry] "r" (entry),
          [thread] "r" (thread),
          [lock_record] "r" (lock_record)
        : "memory", "cc",
          "rax", "rcx", "rdx", "rsi", "rdi", "r8", "r9", "r10", "r11",
          "xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7",
          "xmm8", "xmm9", "xmm10", "xmm11", "xmm12", "xmm13", "xmm14", "xmm15");
#else
    (void)entry;
    (void)thread;
    (void)lock_record;
    fprintf(stderr, "[neko-direct] Runtime1 monitor stubs unsupported on this architecture\\n");
    abort();
#endif
}

NEKO_FAST_INLINE void *neko_monitor_record_lock_addr(neko_monitor_record *rec) {
    return (void*)(rec->basic_object_lock + g_neko_off_basicobjectlock_lock);
}

NEKO_FAST_INLINE void neko_prepare_monitor_record(neko_monitor_record *rec, jobject obj, void *raw_oop) {
    void *lock_addr;
    uintptr_t mark_bits;
    if (rec == NULL || raw_oop == NULL) {
        fprintf(stderr, "[neko-direct] monitor record/object unavailable rec=%p obj=%p\\n", (void*)rec, raw_oop);
        abort();
    }
    if (!g_neko_monitor_stub_ready
        || g_neko_sizeof_basicobjectlock == 0
        || g_neko_sizeof_basicobjectlock > NEKO_MONITOR_RECORD_BYTES
        || g_neko_off_basicobjectlock_lock < 0
        || g_neko_off_basicobjectlock_obj < 0
        || g_neko_off_basiclock_displaced_header < 0
        || g_hotspot.klass_offset_bytes <= 0
        || g_hotspot.use_compact_object_headers) {
        fprintf(stderr, "[neko-direct] monitor layout unavailable ready=%d enter=%p exit=%p bol_size=%zu lock=%td obj=%td disp=%td klass_off=%d coh=%d\\n",
            (int)g_neko_monitor_stub_ready,
            g_neko_runtime1_monitorenter_entry,
            g_neko_runtime1_monitorexit_entry,
            g_neko_sizeof_basicobjectlock,
            g_neko_off_basicobjectlock_lock,
            g_neko_off_basicobjectlock_obj,
            g_neko_off_basiclock_displaced_header,
            (int)g_hotspot.klass_offset_bytes,
            (int)g_hotspot.use_compact_object_headers);
        abort();
    }
    memset(rec->basic_object_lock, 0, sizeof(rec->basic_object_lock));
    rec->handle = obj;
    *(void**)(rec->basic_object_lock + g_neko_off_basicobjectlock_obj) = raw_oop;
    lock_addr = neko_monitor_record_lock_addr(rec);
    mark_bits = (*(uintptr_t*)raw_oop) | (uintptr_t)1u;
    *(uintptr_t*)((char*)lock_addr + g_neko_off_basiclock_displaced_header) = mark_bits;
}

NEKO_FAST_INLINE void neko_fast_monitor_enter(void *thread, jobject obj, neko_monitor_record *rec) {
    void *raw_oop;
    if (thread == NULL) {
        fprintf(stderr, "[neko-direct] MONITORENTER missing JavaThread\\n");
        abort();
    }
    if (obj == NULL) {
        fprintf(stderr, "[neko-direct] MONITORENTER null object\\n");
        abort();
    }
    raw_oop = neko_handle_oop(obj);
    if (raw_oop == NULL) {
        fprintf(stderr, "[neko-direct] MONITORENTER handle did not resolve obj=%p\\n", (void*)obj);
        abort();
    }
    neko_prepare_monitor_record(rec, obj, raw_oop);
    neko_call_runtime1_monitorenter(g_neko_runtime1_monitorenter_entry, thread, rec->basic_object_lock, raw_oop);
}

NEKO_FAST_INLINE void neko_fast_monitor_exit(void *thread, jobject obj, neko_monitor_record *rec) {
    void *raw_oop;
    void *entered_oop;
    if (thread == NULL) {
        fprintf(stderr, "[neko-direct] MONITOREXIT missing JavaThread\\n");
        abort();
    }
    if (rec == NULL || rec->handle == NULL) {
        fprintf(stderr, "[neko-direct] MONITOREXIT missing active monitor record\\n");
        abort();
    }
    raw_oop = neko_handle_oop(obj);
    entered_oop = neko_handle_oop(rec->handle);
    if (raw_oop == NULL || entered_oop == NULL || raw_oop != entered_oop) {
        fprintf(stderr, "[neko-direct] MONITOREXIT monitor object mismatch obj=%p entered=%p raw=%p entered_raw=%p\\n",
            (void*)obj, (void*)rec->handle, raw_oop, entered_oop);
        abort();
    }
    *(void**)(rec->basic_object_lock + g_neko_off_basicobjectlock_obj) = raw_oop;
    neko_call_runtime1_monitorexit(g_neko_runtime1_monitorexit_entry, thread, rec->basic_object_lock);
    memset(rec->basic_object_lock, 0, sizeof(rec->basic_object_lock));
    rec->handle = NULL;
}

NEKO_FAST_INLINE void neko_array_store_check(char *array_oop, jobject val) {
    void *value_oop;
    void *value_klass;
    void *element_klass;
    if (val == NULL) return;
    value_oop = neko_handle_oop(val);
    if (value_oop == NULL) {
        fprintf(stderr, "[neko-direct] AASTORE value handle did not resolve val=%p\\n", (void*)val);
        abort();
    }
    element_klass = neko_objarray_element_klass(array_oop);
    value_klass = neko_raw_oop_klass((char*)value_oop);
    if (!neko_klass_is_subtype_of(value_klass, element_klass)) {
        fprintf(stderr, "[neko-direct] AASTORE array-store check failed value_klass=%p element_klass=%p\\n",
            value_klass, element_klass);
        abort();
    }
}

/* T4.14 perf — `neko_load_object_array_slot` is the inner-most oop slot
 * load used by every fused AALOAD+XALOAD helper, every neko_fast_aaload,
 * and every neko_fast_aastore old-value read. Force-inline so the C
 * compiler can fold the compressed-oops decode + identity barrier into
 * 3-4 instructions per call site. */
NEKO_FORCE_INLINE void *neko_load_object_array_slot(char *array_oop, size_t base, jint idx, size_t ref_size) {
    void *raw_oop;
    char *addr = array_oop + base + ((size_t)idx * ref_size);
    if (g_hotspot.compressed_oops_enabled) {
        uint32_t narrow = *(uint32_t*)addr;
        raw_oop = narrow == 0u ? NULL : (void*)((uintptr_t)((uintptr_t)narrow << g_hotspot.compressed_oops_shift)
                   + (uintptr_t)g_hotspot.compressed_oops_base);
    } else {
        raw_oop = *(void**)addr;
    }
    return neko_barrier_load_oop_array(addr, raw_oop);
}

NEKO_FAST_INLINE void neko_fast_aastore(void *thread, JNIEnv *env, jobjectArray arr, jint idx, jobject val) {
    (void)env;
    char *__debug_oop = NULL;
    jint __debug_len = -1;
    if (g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc)
        && g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] >= 0
        && arr != NULL) {
        char *oop = (char*)neko_handle_oop((jobject)arr);
        __debug_oop = oop;
        if (oop != NULL) {
            size_t ref_size = g_hotspot.compressed_oops_enabled ? 4u : sizeof(void*);
            size_t base = (size_t)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I];
            jint arrayLen = *(jint*)(oop + base - 4u);
            __debug_len = arrayLen;
            if (idx >= 0 && idx < arrayLen) {
                char *element_addr = oop + base + ((size_t)idx * ref_size);
                void *old_oop = neko_load_object_array_slot(oop, base, idx, ref_size);
                void *value_oop = val == NULL ? NULL : neko_handle_oop(val);
                neko_array_store_check(oop, val);
                neko_barrier_pre_store_oop_field(thread, element_addr, old_oop);
                neko_store_oop_raw(oop, (jlong)(base + ((size_t)idx * ref_size)), value_oop);
                neko_barrier_post_store_oop_field(thread, element_addr);
                return;
            }
        }
    }
    fprintf(stderr, "[neko-direct] AASTORE direct path unavailable arr=%p idx=%d oop=%p len=%d baseI=%d use_zgc=%d fast=0x%llx\\n",
        (void*)arr, (int)idx, __debug_oop, (int)__debug_len,
        (int)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I],
        (int)g_hotspot.use_zgc,
        (unsigned long long)g_hotspot.fast_bits);
    abort();
}

/* T4.14 perf — used by every fused AALOAD+XALOAD inner load; inline
 * mandate so its bounds re-check (caller already validated idx1) is
 * dead-code-eliminated and the compressed-oops + barrier path collapses
 * into the caller's hot loop. */
NEKO_FORCE_INLINE char *neko_inner_oop_from_outer(char *outer_oop, jint idx1, jint outer_len) {
    if (idx1 < 0 || idx1 >= outer_len) return NULL;
    return (char*)neko_load_object_array_slot(
        outer_oop,
        (size_t)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I],
        idx1,
        g_hotspot.compressed_oops_enabled ? 4u : sizeof(void*));
}

NEKO_FAST_INLINE jint neko_fast_string_length(JNIEnv *env, jstring str, jlong valueOffset, jlong coderOffset) {
    (void)env;
    if (g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc)
        && g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] >= 0
        && valueOffset > 0
        && coderOffset > 0
        && str != NULL) {
        char *str_oop = (char*)neko_handle_oop((jobject)str);
        if (str_oop != NULL) {
            char *value_oop;
            if (g_hotspot.compressed_oops_enabled) {
                value_oop = (char*)neko_decode_narrow_oop(*(uint32_t*)(str_oop + valueOffset));
            } else {
                value_oop = (char*)neko_barrier_oop_load(*(void**)(str_oop + valueOffset));
            }
            if (value_oop != NULL) {
                jint value_len = *(jint*)(value_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] - 4);
                jbyte coder = *(jbyte*)(str_oop + coderOffset);
                return value_len >> (coder & 1);
            }
        }
    }
    abort();
}

NEKO_FAST_INLINE jobject neko_fast_aaload(void *thread, JNIEnv *env, jobjectArray arr, jint idx) {
    (void)env;
    if (g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc)
        && g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] >= 0
        && arr != NULL) {
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL) {
            jint arrayLen = *(jint*)(oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] - 4);
            if (idx >= 0 && idx < arrayLen) {
                void *element_oop;
                element_oop = neko_load_object_array_slot(
                    oop,
                    (size_t)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I],
                    idx,
                    g_hotspot.compressed_oops_enabled ? 4u : sizeof(void*));
                return neko_direct_oop_to_handle(thread, element_oop);
            }
        }
    }
    {
        void *dbg_block = thread != NULL && g_neko_off_thread_active_handles > 0 ? *(void**)((char*)thread + g_neko_off_thread_active_handles) : NULL;
        int32_t dbg_top = dbg_block != NULL ? *(int32_t*)((char*)dbg_block + g_neko_off_jnih_block_top) : -1;
        void *dbg_oop = arr != NULL ? neko_handle_oop((jobject)arr) : NULL;
        jint dbg_len = dbg_oop != NULL ? *(jint*)((char*)dbg_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] - 4) : -1;
        fprintf(stderr, "[neko-direct] AALOAD direct path unavailable arr=%p idx=%d thread=%p init=%d bits=0x%x base=%d push=%d block=%p top=%d oop=%p len=%d\\n",
            (void*)arr, (int)idx, thread, (int)g_hotspot.initialized, (unsigned)g_hotspot.fast_bits,
            (int)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I], (int)g_neko_handle_push_ready,
            dbg_block, (int)dbg_top, dbg_oop, (int)dbg_len);
    }
    abort();
}

""");
        appendFusedAALoadHelpers(sb);
        appendObjectFieldFastHelpers(sb);
    }

    /**
     * T4.1 — Primitive descriptor → mirror table population + read.
     *
     * Emitted AFTER `renderBindSupport()` because the init function reuses
     * the bind-time `neko_resolve_class_with_env` and `neko_resolve_field`
     * resolvers (and their `neko_field_resolution_t` typedef) defined there.
     * The hot-path read uses the `g_hotspot.compressed_oops_enabled` flag and
     * the `neko_decode_narrow_oop` / `neko_barrier_load_oop_field` helpers
     * from `renderHotSpotSupport` (already emitted earlier).
     *
     * The table itself (`g_neko_primitive_mirror_table`) is declared in
     * `renderRuntimeSupport` so the inline `neko_class_for_descriptor` switch
     * arms can reference it without cross-block extern hoops. The init
     * function resolves the wrapper InstanceKlass for each primitive via
     * `neko_resolve_class_with_env` and the static `TYPE` field's offset via
     * `neko_resolve_field`. `neko_primitive_mirror_for_char` dereferences the
     * wrapper Klass's `_java_mirror` OopHandle to reach the wrapper Class oop,
     * reads TYPE through compressed-oops decode plus the active GC's load
     * barrier, and pushes the resulting primitive mirror oop into the calling
     * thread's local handle block. No JNI function-table indices are consumed;
     * failure of any per-entry derivation aborts (no skip-on-error fallback).
     */
    private String renderPrimitiveMirrorSupport() {
        return """
static void neko_primitive_mirror_table_init(JNIEnv *env) {
    static const char * const wrapper_names[8] = {
        "java/lang/Boolean",
        "java/lang/Byte",
        "java/lang/Character",
        "java/lang/Short",
        "java/lang/Integer",
        "java/lang/Long",
        "java/lang/Float",
        "java/lang/Double"
    };
    int kind;
    if (g_neko_primitive_mirror_ready) return;
    if (env == NULL) {
        fprintf(stderr, "[neko-bind] T4.1 primitive mirror init missing JNIEnv\\n");
        abort();
    }
    if (g_neko_method_layout.off_klass_java_mirror < 0) {
        fprintf(stderr, "[neko-bind] T4.1 primitive mirror init missing Klass::_java_mirror offset\\n");
        abort();
    }
    if (!g_neko_native_resolution_ready) {
        fprintf(stderr, "[neko-bind] T4.1 primitive mirror init requires native_resolution_ready\\n");
        abort();
    }
    for (kind = 0; kind < 8; kind++) {
        const char *name = wrapper_names[kind];
        void *klass;
        neko_field_resolution_t type_field;
        klass = neko_resolve_class_with_env(env, name, NULL);
        if (klass == NULL) {
            fprintf(stderr, "[neko-bind] T4.1 missing wrapper-class mirror for %s (kind=%d tag=%c)\\n",
                name, kind, g_neko_primitive_mirror_table[kind].tag);
            abort();
        }
        type_field = neko_resolve_field(klass, "TYPE", "Ljava/lang/Class;", JNI_TRUE);
        if (!type_field.found || !type_field.is_static || type_field.offset == 0u) {
            fprintf(stderr, "[neko-bind] T4.1 missing TYPE static field on %s (found=%d static=%d off=%u)\\n",
                name, (int)type_field.found, (int)type_field.is_static, type_field.offset);
            abort();
        }
        g_neko_primitive_mirror_table[kind].wrapper_klass = klass;
        g_neko_primitive_mirror_table[kind].type_static_offset = type_field.offset;
        g_neko_primitive_mirror_table[kind].ready = JNI_TRUE;
    }
    g_neko_primitive_mirror_ready = JNI_TRUE;
    if (getenv("NEKO_PATCH_DEBUG") != NULL) {
        fprintf(stderr, "[neko-bind] T4.1 primitive mirror table populated:");
        for (kind = 0; kind < 8; kind++) {
            fprintf(stderr, " %c=(klass=%p,off=%u)",
                g_neko_primitive_mirror_table[kind].tag,
                g_neko_primitive_mirror_table[kind].wrapper_klass,
                (unsigned)g_neko_primitive_mirror_table[kind].type_static_offset);
        }
        fprintf(stderr, "\\n");
    }
}

NEKO_FAST_INLINE jclass neko_primitive_mirror_for_char(JNIEnv *env, char tag) {
    int kind = neko_primitive_kind_from_descriptor_char(tag);
    neko_primitive_mirror_entry_t *entry;
    void *thread;
    void *mirror_handle_addr;
    void *mirror_oop_handle;
    void *wrapper_class_oop;
    char *field_addr;
    void *type_oop;
    if (kind < 0 || kind >= 8) {
        fprintf(stderr, "[neko-bind] T4.1 primitive mirror requested for non-primitive tag '%c'\\n", tag);
        abort();
    }
    entry = &g_neko_primitive_mirror_table[kind];
    if (!g_neko_primitive_mirror_ready || !entry->ready
        || entry->wrapper_klass == NULL || entry->type_static_offset == 0u) {
        fprintf(stderr, "[neko-bind] T4.1 primitive mirror table not ready for tag '%c' (table_ready=%d entry_ready=%d klass=%p off=%u)\\n",
            tag, (int)g_neko_primitive_mirror_ready, (int)entry->ready,
            entry->wrapper_klass, (unsigned)entry->type_static_offset);
        abort();
    }
    /* Klass::_java_mirror lives at the wrapper Klass; reading via the OopHandle
     * indirection picks up GC relocation transparently across all collectors
     * (the GC updates the OopHandle slot value, not just the underlying oop). */
    mirror_handle_addr = (void*)((char*)entry->wrapper_klass + g_neko_method_layout.off_klass_java_mirror);
    mirror_oop_handle = *(void**)mirror_handle_addr;
    if (mirror_oop_handle == NULL) {
        fprintf(stderr, "[neko-bind] T4.1 wrapper Klass::_java_mirror OopHandle empty for tag '%c'\\n", tag);
        abort();
    }
    wrapper_class_oop = *(void**)mirror_oop_handle;
    if (wrapper_class_oop == NULL) {
        fprintf(stderr, "[neko-bind] T4.1 wrapper Class oop NULL for tag '%c'\\n", tag);
        abort();
    }
    /* Read TYPE static field (compressed oop or full oop) and apply the active
     * GC's load barrier. The field is statically declared `Class<X>` so its
     * value is always either NULL or a Class oop; here it must be the
     * primitive's mirror, which is created very early in JVM bootstrap. */
    field_addr = (char*)wrapper_class_oop + entry->type_static_offset;
    if (g_hotspot.compressed_oops_enabled) {
        type_oop = neko_decode_narrow_oop(*(uint32_t*)field_addr);
    } else {
        type_oop = *(void**)field_addr;
    }
    type_oop = neko_barrier_load_oop_field(field_addr, type_oop);
    if (type_oop == NULL) {
        fprintf(stderr, "[neko-bind] T4.1 primitive TYPE oop NULL for tag '%c' (wrapper_class_oop=%p offset=%u)\\n",
            tag, wrapper_class_oop, (unsigned)entry->type_static_offset);
        abort();
    }
    thread = neko_jni_env_to_thread(env);
    if (thread == NULL) {
        fprintf(stderr, "[neko-bind] T4.1 thread unavailable for primitive mirror handle '%c'\\n", tag);
        abort();
    }
    return (jclass)neko_handle_push(thread, type_oop);
}

""";
    }

    /**
     * Fast paths for object field reads (GETFIELD / GETSTATIC of L-types).
     * Skips the per-call JNI GetObjectField / GetStaticObjectField round-trip
     * by reading the field's narrow / wide oop slot directly off the receiver
     * (or static-base mirror) and inline-pushing the raw oop into the active
     * JNIHandleBlock — same trick the AALOAD fast path uses.
     *
     * Object writes use the same direct offset metadata, then run the
     * selected field store barrier for the active GC.
     */
    private void appendObjectFieldFastHelpers(StringBuilder sb) {
        sb.append("""
NEKO_FAST_INLINE jobject neko_fast_get_object_field(void *thread, JNIEnv *env, jobject obj, jfieldID fid, jlong offset) {
    (void)env; (void)fid;
    if (g_hotspot.initialized
        && offset > 0
        && obj != NULL) {
        char *receiver_oop = (char*)neko_handle_oop(obj);
        if (receiver_oop != NULL) {
            void *element_oop;
            char *field_addr = receiver_oop + offset;
            if (g_hotspot.compressed_oops_enabled) {
                element_oop = neko_decode_narrow_oop(*(uint32_t*)field_addr);
            } else {
                element_oop = *(void**)field_addr;
            }
            element_oop = neko_barrier_load_oop_field(field_addr, element_oop);
            return neko_direct_oop_to_handle(thread, element_oop);
        }
    }
    fprintf(stderr, "[neko-direct] object GETFIELD direct path unavailable obj=%p offset=%lld thread=%p\\n", (void*)obj, (long long)offset, thread);
    abort();
}

NEKO_FAST_INLINE jobject neko_fast_get_static_object_field(void *thread, JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {
    (void)env; (void)cls; (void)fid;
    if (g_hotspot.initialized
        && offset > 0
        && staticBase != NULL) {
        char *base_oop = (char*)neko_static_base_oop((jobject)cls);
        if (base_oop == NULL) base_oop = (char*)neko_static_base_oop(staticBase);
        if (base_oop != NULL) {
            void *element_oop;
            char *field_addr = base_oop + offset;
            if (g_hotspot.compressed_oops_enabled) {
                element_oop = neko_decode_narrow_oop(*(uint32_t*)field_addr);
            } else {
                element_oop = *(void**)field_addr;
            }
            element_oop = neko_barrier_load_oop_field(field_addr, element_oop);
            return neko_direct_oop_to_handle(thread, element_oop);
        }
    }
    {
        void *dbg_block = thread != NULL ? *(void**)((char*)thread + g_neko_off_thread_active_handles) : NULL;
        int32_t dbg_top = dbg_block != NULL ? *(int32_t*)((char*)dbg_block + g_neko_off_jnih_block_top) : -1;
        char *dbg_cls_oop = (char*)neko_static_base_oop((jobject)cls);
        char *dbg_base_oop = (char*)neko_static_base_oop(staticBase);
        uint32_t dbg_narrow = dbg_cls_oop != NULL && offset > 0 ? *(uint32_t*)(dbg_cls_oop + offset) : 0;
        void *dbg_wide = dbg_cls_oop != NULL && offset > 0 ? *(void**)(dbg_cls_oop + offset) : NULL;
        fprintf(stderr, "[neko-direct] object GETSTATIC direct path unavailable cls=%p base=%p offset=%lld thread=%p init=%d push=%d block=%p top=%d cap=%d cls_oop=%p base_oop=%p narrow=0x%x wide=%p compressed=%d\\n",
            (void*)cls, (void*)staticBase, (long long)offset, thread, (int)g_hotspot.initialized, (int)g_neko_handle_push_ready,
            dbg_block, (int)dbg_top, (int)g_neko_jnih_block_capacity, (void*)dbg_cls_oop, (void*)dbg_base_oop,
            (unsigned)dbg_narrow, dbg_wide, (int)g_hotspot.compressed_oops_enabled);
    }
    abort();
}

NEKO_FAST_INLINE void neko_fast_set_object_field(void *thread, JNIEnv *env, jobject obj, jfieldID fid, jlong offset, jobject val) {
    (void)env; (void)fid;
    if (g_hotspot.initialized
        && offset > 0
        && obj != NULL) {
        char *receiver_oop = (char*)neko_handle_oop(obj);
        if (receiver_oop != NULL) {
            void *old_oop;
            void *value_oop = val == NULL ? NULL : neko_handle_oop(val);
            char *field_addr = receiver_oop + offset;
            if (g_hotspot.compressed_oops_enabled) {
                old_oop = neko_decode_narrow_oop(*(uint32_t*)field_addr);
            } else {
                old_oop = *(void**)field_addr;
            }
            neko_barrier_pre_store_oop_field(thread, field_addr, old_oop);
            neko_store_oop_raw(receiver_oop, offset, value_oop);
            neko_barrier_post_store_oop_field(thread, field_addr);
            return;
        }
    }
    fprintf(stderr, "[neko-direct] object PUTFIELD direct path unavailable obj=%p offset=%lld thread=%p\\n", (void*)obj, (long long)offset, thread);
    abort();
}

NEKO_FAST_INLINE void neko_fast_set_static_object_field(void *thread, JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, jobject val) {
    (void)env; (void)cls; (void)fid;
    if (g_hotspot.initialized
        && offset > 0
        && staticBase != NULL) {
        char *base_oop = (char*)neko_static_base_oop((jobject)cls);
        if (base_oop == NULL) base_oop = (char*)neko_static_base_oop(staticBase);
        if (base_oop != NULL) {
            void *old_oop;
            void *value_oop = val == NULL ? NULL : neko_handle_oop(val);
            char *field_addr = base_oop + offset;
            if (g_hotspot.compressed_oops_enabled) {
                old_oop = neko_decode_narrow_oop(*(uint32_t*)field_addr);
            } else {
                old_oop = *(void**)field_addr;
            }
            neko_barrier_pre_store_oop_field(thread, field_addr, old_oop);
            neko_store_oop_raw(base_oop, offset, value_oop);
            neko_barrier_post_store_oop_field(thread, field_addr);
            return;
        }
    }
    fprintf(stderr, "[neko-direct] object PUTSTATIC direct path unavailable cls=%p base=%p offset=%lld thread=%p\\n", (void*)cls, (void*)staticBase, (long long)offset, thread);
    abort();
}

NEKO_FAST_INLINE jlong neko_fast_atomic_long_add_and_get(JNIEnv *env, jobject obj, jlong delta, jlong offset) {
    (void)env;
    if (g_hotspot.initialized && offset > 0 && obj != NULL) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) {
            return __atomic_add_fetch((jlong*)(oop + offset), delta, __ATOMIC_SEQ_CST);
        }
    }
    fprintf(stderr, "[neko-direct] AtomicLong.addAndGet direct path unavailable obj=%p offset=%lld\\n", (void*)obj, (long long)offset);
    abort();
}

NEKO_FAST_INLINE jint neko_fast_atomic_int_add_and_get(JNIEnv *env, jobject obj, jint delta, jlong offset) {
    (void)env;
    if (g_hotspot.initialized && offset > 0 && obj != NULL) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) {
            return __atomic_add_fetch((jint*)(oop + offset), delta, __ATOMIC_SEQ_CST);
        }
    }
    fprintf(stderr, "[neko-direct] AtomicInteger.addAndGet direct path unavailable obj=%p offset=%lld\\n", (void*)obj, (long long)offset);
    abort();
}

""");
    }

    /**
     * Fused AALOAD + primitive *ALOAD helper: skips the JNIHandle allocation
     * for the intermediate inner-array reference, since the inner oop is only
     * used immediately within this single inline function.
     *
     * Hot path for matrix-style nested arrays (a[i][k]) — eliminates 100% of
     * per-iteration handle allocs in the inner loop.
     */
    private void appendFusedAALoadHelpers(StringBuilder sb) {
        sb.append("""
#define NEKO_FAST_ARRAY_OK 0
#define NEKO_FAST_ARRAY_OUTER_NULL 1
#define NEKO_FAST_ARRAY_OUTER_BOUNDS 2
#define NEKO_FAST_ARRAY_INNER_NULL 3
#define NEKO_FAST_ARRAY_INNER_BOUNDS 4

""");
        appendFusedAALoadPrim(sb, "b", "jbyte",  "NEKO_PRIM_B", "byte",   "jbyteArray");
        appendFusedAALoadPrim(sb, "c", "jchar",  "NEKO_PRIM_C", "char",   "jcharArray");
        appendFusedAALoadPrim(sb, "s", "jshort", "NEKO_PRIM_S", "short",  "jshortArray");
        appendFusedAALoadPrim(sb, "i", "jint",   "NEKO_PRIM_I", "int",    "jintArray");
        appendFusedAALoadPrim(sb, "l", "jlong",  "NEKO_PRIM_J", "long",   "jlongArray");
        appendFusedAALoadPrim(sb, "f", "jfloat", "NEKO_PRIM_F", "float",  "jfloatArray");
        appendFusedAALoadPrim(sb, "d", "jdouble","NEKO_PRIM_D", "double", "jdoubleArray");
        sb.append("""
NEKO_FORCE_INLINE jobject neko_fast_aaload_aaload(void *thread, JNIEnv *env, jobjectArray outer, jint idx1, jint idx2, int *reason) {
    (void)env;
    if (reason != NULL) *reason = NEKO_FAST_ARRAY_OK;
    if (!g_hotspot.initialized
        || ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) == 0 && !g_hotspot.use_zgc)
        || g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] < 0
        || thread == NULL) {
        fprintf(stderr, "[neko-direct] AALOAD+AALOAD layout unavailable outer=%p idx1=%d idx2=%d thread=%p\\n", (void*)outer, (int)idx1, (int)idx2, thread);
        abort();
    }
    if (outer == NULL) { if (reason != NULL) *reason = NEKO_FAST_ARRAY_OUTER_NULL; return NULL; }
    char *outer_oop = (char*)neko_handle_oop((jobject)outer);
    if (outer_oop == NULL) {
        fprintf(stderr, "[neko-direct] AALOAD+AALOAD outer handle unresolved outer=%p\\n", (void*)outer);
        abort();
    }
    jint outer_len = *(jint*)(outer_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] - 4);
    if (idx1 < 0 || idx1 >= outer_len) { if (reason != NULL) *reason = NEKO_FAST_ARRAY_OUTER_BOUNDS; return NULL; }
    char *inner_oop = neko_inner_oop_from_outer(outer_oop, idx1, outer_len);
    if (inner_oop == NULL) { if (reason != NULL) *reason = NEKO_FAST_ARRAY_INNER_NULL; return NULL; }
    jint inner_len = *(jint*)(inner_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] - 4);
    if (idx2 < 0 || idx2 >= inner_len) { if (reason != NULL) *reason = NEKO_FAST_ARRAY_INNER_BOUNDS; return NULL; }
    void *element_oop;
    element_oop = neko_load_object_array_slot(
        inner_oop,
        (size_t)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I],
        idx2,
        g_hotspot.compressed_oops_enabled ? 4u : sizeof(void*));
    return neko_direct_oop_to_handle(thread, element_oop);
}

NEKO_FAST_INLINE void neko_raise_fast_array_reason(void *thread, JNIEnv *env, int reason,
    jclass npe_cls, void *npe_method, void *npe_entry, neko_njx_dispatcher_t npe_dispatcher,
    jclass aioobe_cls, void *aioobe_method, void *aioobe_entry, neko_njx_dispatcher_t aioobe_dispatcher) {
    if (reason == NEKO_FAST_ARRAY_OUTER_NULL || reason == NEKO_FAST_ARRAY_INNER_NULL) {
        neko_raise_implicit_exception(thread, env, npe_cls, npe_method, npe_entry, npe_dispatcher, "java/lang/NullPointerException");
    } else if (reason == NEKO_FAST_ARRAY_OUTER_BOUNDS || reason == NEKO_FAST_ARRAY_INNER_BOUNDS) {
        neko_raise_implicit_exception(thread, env, aioobe_cls, aioobe_method, aioobe_entry, aioobe_dispatcher, "java/lang/ArrayIndexOutOfBoundsException");
    } else {
        fprintf(stderr, "[neko-direct] unexpected fast array failure reason=%d\\n", reason);
        abort();
    }
}

""");
    }

    private void appendFusedAALoadPrim(
        StringBuilder sb, String prefix, String cType, String elemKind, String wrapperStem, String jArrayType
    ) {
        sb.append("NEKO_FORCE_INLINE ").append(cType).append(" neko_fast_aaload_").append(prefix)
            .append("aload(void *thread, JNIEnv *env, jobjectArray outer, jint idx1, jint idx2, int *reason) {\n")
            .append("    (void)thread;\n")
            .append("    (void)env;\n")
            .append("    if (reason != NULL) *reason = NEKO_FAST_ARRAY_OK;\n")
            .append("    if (!g_hotspot.initialized\n")
            .append("        || ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) == 0 && !g_hotspot.use_zgc)\n")
            .append("        || g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] < 0) {\n")
            .append("        fprintf(stderr, \"[neko-direct] AALOAD+").append(prefix).append("ALOAD layout unavailable outer=%p idx1=%d idx2=%d\\n\", (void*)outer, (int)idx1, (int)idx2); abort();\n")
            .append("    }\n")
            .append("    if (outer == NULL) { if (reason != NULL) *reason = NEKO_FAST_ARRAY_OUTER_NULL; return (").append(cType).append(")0; }\n")
            .append("    char *outer_oop = (char*)neko_handle_oop((jobject)outer);\n")
            .append("    if (outer_oop == NULL) { fprintf(stderr, \"[neko-direct] AALOAD+").append(prefix).append("ALOAD outer handle unresolved outer=%p\\n\", (void*)outer); abort(); }\n")
            .append("    jint outer_len = *(jint*)(outer_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] - 4);\n")
            .append("    if (idx1 < 0 || idx1 >= outer_len) { if (reason != NULL) *reason = NEKO_FAST_ARRAY_OUTER_BOUNDS; return (").append(cType).append(")0; }\n")
            .append("    char *inner_oop = neko_inner_oop_from_outer(outer_oop, idx1, outer_len);\n")
            .append("    if (inner_oop == NULL) { if (reason != NULL) *reason = NEKO_FAST_ARRAY_INNER_NULL; return (").append(cType).append(")0; }\n")
            .append("    jint inner_len = *(jint*)(inner_oop + g_hotspot.primitive_array_base_offsets[").append(elemKind).append("] - 4);\n")
            .append("    if (idx2 < 0 || idx2 >= inner_len) { if (reason != NULL) *reason = NEKO_FAST_ARRAY_INNER_BOUNDS; return (").append(cType).append(")0; }\n")
            .append("    char *addr = inner_oop + g_hotspot.primitive_array_base_offsets[").append(elemKind).append("] + ((jlong)idx2 * g_hotspot.primitive_array_index_scales[").append(elemKind).append("]);\n")
            .append("    return *(").append(cType).append("*)addr;\n")
            .append("}\n\n");
    }

    private void appendPrimitiveFieldHelpers(StringBuilder sb, char desc, String cType, String wrapperStem) {
        sb.append("NEKO_FAST_INLINE ").append(cType).append(" neko_fast_get_").append(desc)
            .append("_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, const char *owner, const char *name) {\n")
            .append("    (void)env; (void)fid;\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_handle_oop(obj);\n")
            .append("        if (oop != NULL) return *((volatile ").append(cType).append("*)(oop + offset));\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] missing primitive instance field direct metadata %s.%s kind=").append(desc).append(" offset=%lld obj=%p\\n\", owner, name, (long long)offset, (void*)obj); abort();\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE void neko_fast_set_").append(desc)
            .append("_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, ").append(cType).append(" value, const char *owner, const char *name) {\n")
            .append("    (void)env; (void)fid;\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_handle_oop(obj);\n")
            .append("        if (oop != NULL) { *((volatile ").append(cType).append("*)(oop + offset)) = value; return; }\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] missing primitive instance field direct metadata %s.%s kind=").append(desc).append(" offset=%lld obj=%p\\n\", owner, name, (long long)offset, (void*)obj); abort();\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE ").append(cType).append(" neko_fast_get_static_").append(desc)
            .append("_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {\n")
            .append("    (void)env; (void)cls; (void)fid;\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_static_base_oop((jobject)cls);\n")
            .append("        if (oop == NULL) oop = (char*)neko_static_base_oop(staticBase);\n")
            .append("        if (oop != NULL) return *((volatile ").append(cType).append("*)(oop + offset));\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] missing primitive static field direct metadata kind=").append(desc).append(" offset=%lld base=%p\\n\", (long long)offset, (void*)staticBase); abort();\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE void neko_fast_set_static_").append(desc)
            .append("_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, ").append(cType).append(" value) {\n")
            .append("    (void)env; (void)cls; (void)fid;\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_static_base_oop((jobject)cls);\n")
            .append("        if (oop == NULL) oop = (char*)neko_static_base_oop(staticBase);\n")
            .append("        if (oop != NULL) { *((volatile ").append(cType).append("*)(oop + offset)) = value; return; }\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] missing primitive static field direct metadata kind=").append(desc).append(" offset=%lld base=%p\\n\", (long long)offset, (void*)staticBase); abort();\n")
            .append("}\n\n");
    }

    private void appendPrimitiveArrayHelpers(StringBuilder sb, String prefix, String cType, String wrapperStem, String kindConstant) {
        sb.append("NEKO_FAST_INLINE ").append(cType).append(" neko_fast_").append(prefix)
            .append("aload(jarray arr, jint idx) {\n")
            .append("    if (g_hotspot.initialized && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc) && arr != NULL) {\n")
            .append("        char *oop = (char*)neko_handle_oop((jobject)arr);\n")
            .append("        if (oop != NULL) {\n")
            .append("            jint arrayLen = *(jint*)(oop + g_hotspot.array_length_offset);\n")
            .append("            if (idx >= 0 && idx < arrayLen) {\n")
            .append("                char *addr = oop + g_hotspot.primitive_array_base_offsets[").append(kindConstant).append("] + ((jlong)idx * g_hotspot.primitive_array_index_scales[").append(kindConstant).append("]);\n")
            .append("                return *(").append(cType).append("*)addr;\n")
            .append("            }\n")
            .append("        }\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] ").append(prefix).append("ALOAD direct path unavailable arr=%p idx=%d\\n\", (void*)arr, (int)idx); abort();\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE void neko_fast_").append(prefix)
            .append("astore(jarray arr, jint idx, ").append(cType).append(" value) {\n")
            .append("    if (g_hotspot.initialized && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc) && arr != NULL) {\n")
            .append("        char *oop = (char*)neko_handle_oop((jobject)arr);\n")
            .append("        if (oop != NULL) {\n")
            .append("            jint arrayLen = *(jint*)(oop + g_hotspot.array_length_offset);\n")
            .append("            if (idx >= 0 && idx < arrayLen) {\n")
            .append("                char *addr = oop + g_hotspot.primitive_array_base_offsets[").append(kindConstant).append("] + ((jlong)idx * g_hotspot.primitive_array_index_scales[").append(kindConstant).append("]);\n")
            .append("                *(").append(cType).append("*)addr = value;\n")
            .append("                return;\n")
            .append("            }\n")
            .append("        }\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] ").append(prefix).append("ASTORE direct path unavailable arr=%p idx=%d\\n\", (void*)arr, (int)idx); abort();\n")
            .append("}\n\n");
    }

    private String cTypeForArray(String prefix) {
        return switch (prefix) {
            case "z" -> "jbooleanArray";
            case "b" -> "jbyteArray";
            case "c" -> "jcharArray";
            case "s" -> "jshortArray";
            case "i" -> "jintArray";
            case "l" -> "jlongArray";
            case "f" -> "jfloatArray";
            case "d" -> "jdoubleArray";
            default -> "jarray";
        };
    }

    private String jniType(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> "void";
            case Type.BOOLEAN -> "jboolean";
            case Type.CHAR -> "jchar";
            case Type.BYTE -> "jbyte";
            case Type.SHORT -> "jshort";
            case Type.INT -> "jint";
            case Type.FLOAT -> "jfloat";
            case Type.LONG -> "jlong";
            case Type.DOUBLE -> "jdouble";
            case Type.ARRAY -> "jarray";
            default -> "jobject";
        };
    }

    private String c(String s) {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\f", "\\f");
    }

    private String objectArrayDescriptor(String elementInternalName) {
        if (elementInternalName.startsWith("[")) {
            return "[" + elementInternalName;
        }
        return "[L" + elementInternalName + ";";
    }

    private String renderParam(CVariable variable) {
        if ("env".equals(variable.name())) {
            return "JNIEnv *env";
        }
        if ("thread".equals(variable.name())) {
            return "void *thread";
        }
        return variable.declaration();
    }

    private record MethodRef(String owner, String name, String desc, boolean isStatic) {}

    private record FieldRef(String owner, String name, String desc, boolean isStatic) {}

    private record StringRef(String cacheVar, String value) {}

    private static String primitiveClassSlotKey(String desc) {
        return "#primitive/" + desc;
    }

        private String jvalueAccessor(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> ".z";
            case Type.BYTE -> ".b";
            case Type.CHAR -> ".c";
            case Type.SHORT -> ".s";
            case Type.INT -> ".i";
            case Type.FLOAT -> ".f";
            case Type.LONG -> ".j";
            case Type.DOUBLE -> ".d";
            default -> ".l";
        };
    }

    private record IcacheSiteRef(int ownerId, int methodId, int siteIndex, String bindingOwner, String methodKey) {
        private String symbol() {
            return "neko_icache_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    private record IcacheDirectStubRef(
        int ownerId,
        int methodId,
        int siteIndex,
        NativeMethodBinding binding,
        Type[] args,
        Type returnType
    ) {
        private String symbol() {
            return "neko_icache_stub_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    private record IcacheMetaRef(
        int ownerId,
        int methodId,
        int siteIndex,
        String name,
        String desc,
        boolean isInterface,
        String translatedClassSlot,
        String translatedStubSymbol,
        String directDispatcherSymbol
    ) {
        private String symbol() {
            return "neko_icache_meta_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    private static final class OwnerResolution {
        private final Set<String> classes = new LinkedHashSet<>();
        private final Set<String> primitiveClasses = new LinkedHashSet<>();
        private final Set<MethodRef> methods = new LinkedHashSet<>();
        private final Set<FieldRef> fields = new LinkedHashSet<>();
        private final Set<StringRef> strings = new LinkedHashSet<>();
    }
}
