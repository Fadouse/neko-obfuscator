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
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_thread_active_handles;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_jnih_block_top;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_jnih_block_handles;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_jnih_block_next;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_jnih_block_capacity;\n");
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
        sb.append("static __thread neko_handle_save_t *g_neko_active_handle_save = NULL;\n");
        sb.append("static jboolean neko_resolve_jnihandles(void *jvm);\n");
        sb.append("static void *neko_dlsym(void *h, const char *name);\n");
        sb.append("static void *neko_class_mirror_to_klass(jclass mirror);\n");
        sb.append("static inline void* neko_thread_ref_oop(void *thread, jobject ref);\n");
        sb.append("static void *neko_object_ref_klass(void *thread, jobject obj);\n");
        sb.append("static void *neko_object_handle_klass(jobject obj);\n");
        sb.append("static void *neko_try_resolve_method(void *instance_klass, const char *name_utf8, const char *sig_utf8);\n");
        sb.append("static void *neko_resolve_method(void *instance_klass, const char *name_utf8, const char *sig_utf8);\n");
        sb.append("static void neko_debug_dump_method_resolution(void *instance_klass, const char *name_utf8, const char *sig_utf8);\n");
        sb.append("static void *neko_resolve_class_with_env(JNIEnv *env, const char *utf8, jclass from_class);\n");
        sb.append("static jboolean neko_ref_is_direct_oop(jobject ref);\n");
        sb.append("static jboolean neko_thread_ref_is_live_handle(void *thread, jobject ref);\n");
        sb.append("static void neko_link_class_methods(JNIEnv *env, jclass cls, const char *owner, const char *name, const char *desc);\n");
        sb.append("static jobject neko_klass_java_mirror_handle(void *thread, void *klass);\n");
        sb.append("static uintptr_t neko_klass_header_bits(void *klass);\n");
        sb.append("static void *neko_decode_klass_header_bits(uintptr_t bits);\n");
        sb.append("static void neko_njx_init_wrappers(void);\n\n");
        sb.append("static void neko_fast_string_runtime_init(JNIEnv *env);\n\n");
        sb.append("static void neko_boxing_cache_init(JNIEnv *env);\n\n");
        sb.append("static uintptr_t neko_array_klass_bits_for_descriptor(JNIEnv *env, const char *arrayDesc, jclass fromClass);\n");
        sb.append("static jobject neko_fast_alloc_object(void *thread, JNIEnv *env, jclass cls);\n");
        sb.append("static jobjectArray neko_fast_new_object_array(void *thread, JNIEnv *env, jint len, uintptr_t klass_bits, jobject init);\n");
        sb.append("static jarray neko_fast_new_primitive_array(void *thread, JNIEnv *env, jint len, int kind);\n");
        sb.append("static void neko_fast_aastore(void *thread, JNIEnv *env, jobjectArray arr, jint idx, jobject val);\n\n");
        sb.append("static void neko_store_oop_raw(char *oop, jlong offset, void *value);\n\n");
        sb.append("static void neko_refill_tlab_with_slow_byte_array(JNIEnv *env, jint min_payload_len);\n\n");
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
            sb.append("static void* g_str_").append(i).append("_oop = NULL;\n");
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
        sb.append("static jstring neko_ensure_string_slot(jstring *slot, void **oop_slot, JNIEnv *env, const char *utf);\n");
        sb.append("static jmethodID neko_ensure_method_id_slot(jmethodID *slot, JNIEnv *env, jclass cls, const char *name, const char *desc, jboolean isStatic);\n");
        sb.append("static jfieldID neko_ensure_field_id_slot(jfieldID *slot, JNIEnv *env, jclass cls, const char *name, const char *desc, jboolean isStatic);\n");
        sb.append("static jclass neko_resolve_class_mirror_with_env(JNIEnv *env, const char *utf8, jclass from_class, void **klass_out);\n");
        sb.append("#define NEKO_ENSURE_CLASS(slot, env, name) neko_ensure_class_slot(&(slot), (env), (name))\n");
        sb.append("#define NEKO_ENSURE_STRING(slot, env, utf) neko_ensure_string_slot(&(slot), &(slot##_oop), (env), (utf))\n");
        sb.append("#define NEKO_ENSURE_METHOD_ID(slot, env, cls, name, desc) neko_ensure_method_id_slot(&(slot), (env), (cls), (name), (desc), JNI_FALSE)\n");
        sb.append("#define NEKO_ENSURE_STATIC_METHOD_ID(slot, env, cls, name, desc) neko_ensure_method_id_slot(&(slot), (env), (cls), (name), (desc), JNI_TRUE)\n");
        sb.append("#define NEKO_ENSURE_FIELD_ID(slot, env, cls, name, desc) neko_ensure_field_id_slot(&(slot), (env), (cls), (name), (desc), JNI_FALSE)\n");
        sb.append("#define NEKO_ENSURE_STATIC_FIELD_ID(slot, env, cls, name, desc) neko_ensure_field_id_slot(&(slot), (env), (cls), (name), (desc), JNI_TRUE)\n\n");
        return sb.toString();
    }

    private String renderBindSupport() {
        String part1 = """
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

static void *neko_canonical_klass_ptr(void *klass) {
    uintptr_t raw = (uintptr_t)klass;
    if (raw == 0) return NULL;
#if UINTPTR_MAX > 0xffffffffu
    raw &= ~(uintptr_t)0x7u;
#endif
    return (void*)raw;
}

static void neko_remember_class_klass(jclass mirror, void *klass) {
    neko_class_klass_cache_node *node;
    klass = neko_canonical_klass_ptr(klass);
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
    return neko_canonical_klass_ptr(klass);
}

static jobject neko_klass_java_mirror_handle(void *thread, void *klass) {
    void *mirror_handle;
    void *mirror_oop;
    klass = neko_canonical_klass_ptr(klass);
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

static jclass neko_class_mirror_handle_from_slot(void *thread, jclass slot, const char *owner) {
    void *klass;
    jclass handle;
    if (thread == NULL || slot == NULL) {
        fprintf(stderr, "[neko-bind] class slot handle missing input: %s thread=%p slot=%p\\n",
            owner == NULL ? "<null>" : owner, thread, (void*)slot);
        abort();
    }
    klass = neko_class_mirror_to_klass(slot);
    if (klass == NULL) {
        fprintf(stderr, "[neko-bind] class slot klass unavailable: %s slot=%p\\n",
            owner == NULL ? "<null>" : owner, (void*)slot);
        abort();
    }
    handle = (jclass)neko_klass_java_mirror_handle(thread, klass);
    if (handle == NULL) {
        fprintf(stderr, "[neko-bind] class slot local handle unavailable: %s klass=%p\\n",
            owner == NULL ? "<null>" : owner, klass);
        abort();
    }
    return handle;
}

static jclass neko_class_mirror_handle_from_env(JNIEnv *env, jclass slot, const char *owner) {
    void *thread = neko_jni_env_to_thread(env);
    if (thread == NULL) {
        fprintf(stderr, "[neko-bind] JavaThread unavailable for class slot handle: %s env=%p\\n",
            owner == NULL ? "<null>" : owner, (void*)env);
        abort();
    }
    return neko_class_mirror_handle_from_slot(thread, slot, owner);
}

""";
        String part2 = """
static jclass neko_resolve_class_mirror_with_env(JNIEnv *env, const char *utf8, jclass from_class, void **klass_out) {
    jclass resolved = NULL;
    jclass lookup_from_class = NULL;
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
    if (from_class != NULL) {
        lookup_from_class = neko_class_mirror_handle_from_env(env, from_class, utf8);
    }
    klass = neko_resolve_loaded_class_by_name(utf8);
    if (getenv("NEKO_PATCH_DEBUG") != NULL) {
        fprintf(stderr, "[neko-bind] class lookup start name=%s from=%p handle=%p boot_sym=%p from_sym=%p\\n",
            utf8, (void*)from_class, (void*)lookup_from_class,
            g_neko_method_layout.sym_jvm_find_class_from_boot_loader,
            g_neko_method_layout.sym_jvm_find_class_from_class);
    }
    if (g_neko_method_layout.sym_jvm_find_class_from_boot_loader != NULL) {
        resolved = ((neko_jvm_find_class_boot_t)g_neko_method_layout.sym_jvm_find_class_from_boot_loader)(env, utf8);
        if (getenv("NEKO_PATCH_DEBUG") != NULL) {
            fprintf(stderr, "[neko-bind] class lookup boot result name=%s mirror=%p\\n", utf8, (void*)resolved);
        }
    }
    if (resolved == NULL && lookup_from_class != NULL && g_neko_method_layout.sym_jvm_find_class_from_class != NULL) {
        resolved = ((neko_jvm_find_class_from_class_t)g_neko_method_layout.sym_jvm_find_class_from_class)(
            env, utf8, JNI_FALSE, lookup_from_class);
        if (getenv("NEKO_PATCH_DEBUG") != NULL) {
            fprintf(stderr, "[neko-bind] class lookup from result name=%s mirror=%p\\n", utf8, (void*)resolved);
        }
    }
    if (resolved == NULL && from_class == NULL && g_neko_method_layout.sym_jvm_find_class_from_class != NULL) {
        void *thread = neko_jni_env_to_thread(env);
        void *anchor_klass = neko_resolve_loaded_class_by_name("dev/nekoobfuscator/runtime/NekoNativeLoader");
        jclass anchor = anchor_klass != NULL ? (jclass)neko_klass_java_mirror_handle(thread, anchor_klass) : NULL;
        if (anchor != NULL) {
            resolved = ((neko_jvm_find_class_from_class_t)g_neko_method_layout.sym_jvm_find_class_from_class)(
                env, utf8, JNI_FALSE, anchor);
            if (getenv("NEKO_PATCH_DEBUG") != NULL) {
                fprintf(stderr, "[neko-bind] class lookup loader-anchor result name=%s mirror=%p anchor=%p\\n",
                    utf8, (void*)resolved, (void*)anchor);
            }
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
        if (neko_exception_check(env)) neko_exception_clear(env);
        fprintf(stderr, "[neko-bind] class initialization failed: %s\\n", owner);
        abort();
    }
}

static void neko_ensure_class_initialized_once(JNIEnv *env, jclass cls, const char *owner, volatile jboolean *slot) {
    if (slot != NULL && *slot == JNI_TRUE) return;
    neko_ensure_class_initialized(env, cls, owner);
    if (slot != NULL) *slot = JNI_TRUE;
}

static uintptr_t neko_klass_header_bits(void *klass) {
    uintptr_t base;
    int shift;
    klass = neko_canonical_klass_ptr(klass);
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

static char *neko_new_byte_array_oop_slow(JNIEnv *env, jint len, jarray *local_ref_out) {
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
        if (neko_exception_check(env)) neko_exception_clear(env);
        fprintf(stderr, "[neko-bind] JVM_FindPrimitiveClass(byte) failed\\n");
        abort();
    }
    array = ((neko_jvm_new_array_t)g_neko_method_layout.sym_jvm_new_array)(env, byte_class, len);
    if (array == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
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
    (void)neko_new_byte_array_oop_slow(env, min_payload_len, &scratch);
    (void)scratch;
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
    jobject array_handle;
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
        array_oop = neko_new_byte_array_oop_slow(env, (jint)payload_bytes, &local_array);
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
        array_handle = (jobject)neko_direct_oop_to_handle(thread, array_oop);
        neko_refill_tlab_with_slow_byte_array(env, string_bytes > (size_t)INT32_MAX ? INT32_MAX : (jint)string_bytes);
        array_oop = (char*)neko_thread_ref_oop(thread, array_handle);
        if (array_oop == NULL) {
            fprintf(stderr, "[neko-bind] rooted byte[] disappeared during string literal TLAB refill\\n");
            abort();
        }
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
        if (neko_exception_check(env)) neko_exception_clear(env);
        fprintf(stderr, "[neko-bind] JVM_InternString failed for string literal\\n");
        abort();
    }
    neko_tlab_ensure_top_at_least(thread, string_oop + neko_align_object_bytes(string_bytes));
    (void)local_array;
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

static void neko_debug_print_symbol(void *symbol) {
    uint16_t len;
    const char *body;
    if (symbol == NULL) {
        fprintf(stderr, "<null>");
        return;
    }
    if (g_neko_method_layout.off_symbol_length < 0 || g_neko_method_layout.off_symbol_body < 0) {
        fprintf(stderr, "<symbol-layout-unavailable>");
        return;
    }
    len = *(uint16_t*)((char*)symbol + g_neko_method_layout.off_symbol_length);
    body = (const char*)symbol + g_neko_method_layout.off_symbol_body;
    fwrite(body, 1, len, stderr);
}

static void neko_debug_dump_declared_methods(void *klass, const char *name_utf8, const char *sig_utf8) {
    void *methods_array;
    int method_count;
    void **method_data;
    void *klass_name;
    if (klass == NULL) return;
    if (g_neko_method_layout.off_klass_name >= 0) {
        klass_name = *(void**)((char*)klass + g_neko_method_layout.off_klass_name);
        fprintf(stderr, "[neko-debug] resolver klass=");
        neko_debug_print_symbol(klass_name);
        fprintf(stderr, " klass_ptr=%p\\n", klass);
    } else {
        fprintf(stderr, "[neko-debug] resolver klass_ptr=%p\\n", klass);
    }
    if (g_neko_method_layout.off_instanceklass_methods < 0
        || g_neko_method_layout.off_array_length < 0
        || g_neko_method_layout.off_array_data < 0
        || g_neko_method_layout.off_constmethod_constants < 0
        || g_neko_method_layout.off_constmethod_name_index < 0
        || g_neko_method_layout.off_constmethod_signature_index < 0) {
        fprintf(stderr, "[neko-debug] resolver method-layout unavailable\\n");
        return;
    }
    methods_array = *(void**)((char*)klass + g_neko_method_layout.off_instanceklass_methods);
    if (methods_array == NULL) {
        fprintf(stderr, "[neko-debug] resolver methods=NULL\\n");
        return;
    }
    method_count = *(int*)((char*)methods_array + g_neko_method_layout.off_array_length);
    method_data = (void**)((char*)methods_array + g_neko_method_layout.off_array_data);
    fprintf(stderr, "[neko-debug] resolver method_count=%d target=%s%s\\n",
        method_count, name_utf8 == NULL ? "<null>" : name_utf8, sig_utf8 == NULL ? "<null>" : sig_utf8);
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
        if (neko_symbol_equals_utf8(name_symbol, name_utf8) || neko_symbol_equals_utf8(sig_symbol, sig_utf8)) {
            fprintf(stderr, "[neko-debug] resolver method[%d]=%p ", i, method);
            neko_debug_print_symbol(name_symbol);
            fprintf(stderr, " ");
            neko_debug_print_symbol(sig_symbol);
            fprintf(stderr, "\\n");
        }
    }
}

static void neko_debug_dump_method_resolution(void *instance_klass, const char *name_utf8, const char *sig_utf8) {
    void *klass;
    if (getenv("NEKO_PATCH_DEBUG") == NULL) return;
    fprintf(stderr, "[neko-debug] resolver miss target=%s%s start_klass=%p\\n",
        name_utf8 == NULL ? "<null>" : name_utf8,
        sig_utf8 == NULL ? "<null>" : sig_utf8,
        instance_klass);
    if (instance_klass == NULL || g_neko_method_layout.off_klass_super < 0) return;
    klass = instance_klass;
    for (int depth = 0; klass != NULL && depth < 16; depth++) {
        neko_debug_dump_declared_methods(klass, name_utf8, sig_utf8);
        klass = *(void**)((char*)klass + g_neko_method_layout.off_klass_super);
    }
}

static void *neko_try_resolve_method(void *instance_klass, const char *name_utf8, const char *sig_utf8) {
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
    return NULL;
}

static void *neko_resolve_method(void *instance_klass, const char *name_utf8, const char *sig_utf8) {
    void *method = neko_try_resolve_method(instance_klass, name_utf8, sig_utf8);
    if (method != NULL) return method;
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
static jstring neko_bind_string_slot(void *thread, JNIEnv *env, jstring *slot, void **oop_slot, const char *utf);

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

static jstring neko_ensure_string_slot(jstring *slot, void **oop_slot, JNIEnv *env, const char *utf) {
    void *thread = neko_jni_env_to_thread(env);
    if (slot == NULL) {
        fprintf(stderr, "[neko-bind] string slot pointer missing for %s\\n", utf == NULL ? "<null>" : utf);
        abort();
    }
    return neko_bind_string_slot(thread, env, slot, oop_slot, utf);
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
    if (neko_exception_check(env)) {
        neko_exception_clear(env);
        fprintf(stderr, "[neko-bind] owner class native resolution left pending exception: %s\\n", owner == NULL ? "<null>" : owner);
        abort();
    }
    *slot = (jclass)neko_persistent_handle(env, self_class, owner);
    neko_remember_class_klass(*slot, resolved_klass);
}

static void neko_bind_class_slot(JNIEnv *env, jclass *slot, const char *owner) {
    neko_bind_class_slot_from(env, slot, owner, NULL);
}

static void neko_bind_class_slot_from(JNIEnv *env, jclass *slot, const char *owner, jclass from_class) {
    void *klass;
    jobject localClass;
    void *expected;
    if (env == NULL || slot == NULL || *slot != NULL || owner == NULL) return;
    localClass = neko_resolve_class_mirror_with_env(env, owner, from_class, &klass);
    if (neko_exception_check(env)) {
        neko_exception_clear(env);
        fprintf(stderr, "[neko-bind] class native resolution left pending exception: %s\\n", owner);
        abort();
    }
    expected = NULL;
    localClass = (jclass)neko_persistent_handle(env, localClass, owner);
    if (!__atomic_compare_exchange_n((void**)slot, &expected, (void*)localClass, JNI_FALSE, __ATOMIC_RELEASE, __ATOMIC_ACQUIRE)) {
        return;
    }
    neko_remember_class_klass((jclass)localClass, klass);
    if (strstr(owner, "$NekoLambda$") != NULL) {
        if (!neko_manifest_patch_defined_class(env, neko_class_mirror_handle_from_env(env, (jclass)localClass, owner))) {
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
        if (neko_exception_check(env)) neko_exception_clear(env);
        fprintf(stderr, "[neko-bind] primitive class resolution failed for descriptor %s\\n", desc);
        abort();
    }
    if (neko_exception_check(env)) {
        neko_exception_clear(env);
        fprintf(stderr, "[neko-bind] primitive class resolution left pending exception for descriptor %s\\n", desc);
        abort();
    }
    *slot = (jclass)neko_persistent_handle(env, localClass, desc);
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
        if (neko_exception_check(env)) neko_exception_clear(env);
        fprintf(stderr, "[neko-bind] method materialization failed: %s.%s%s\\n",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
        abort();
    }
    (void)members;
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
        neko_link_class_methods(env, neko_class_mirror_handle_from_env(env, cls, owner), owner, name, desc);
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

static void neko_persist_current_handle_slot(void *thread, jobject handle, const char *what) {
    neko_handle_save_t *save = g_neko_active_handle_save;
    uintptr_t raw;
    uintptr_t slot_addr;
    void *block;
    void **handles;
    uintptr_t begin;
    uintptr_t end;
    int32_t index;
    if (handle == NULL || save == NULL) return;
    if (save->thread != thread || save->block == NULL) {
        fprintf(stderr, "[neko-bind] cannot persist handle outside current dispatch: %s thread=%p save_thread=%p\\n",
            what == NULL ? "<unknown>" : what,
            thread,
            save == NULL ? NULL : save->thread);
        abort();
    }
    raw = (uintptr_t)handle;
    if (neko_ref_is_direct_oop(handle)) {
        fprintf(stderr, "[neko-bind] string cache received raw oop instead of handle: %s ref=%p\\n",
            what == NULL ? "<unknown>" : what, (void*)handle);
        abort();
    }
    if (g_neko_off_jnih_block_handles < 0 || g_neko_jnih_block_capacity <= 0) {
        fprintf(stderr, "[neko-bind] handle block layout unavailable for persistent string slot: %s\\n",
            what == NULL ? "<unknown>" : what);
        abort();
    }
    slot_addr = (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_HANDLE_TAGS) != 0 ? (raw & ~(uintptr_t)0x3u) : raw;
    if (g_neko_off_thread_active_handles <= 0 || g_neko_method_layout.off_jnih_block_next <= 0) {
        fprintf(stderr, "[neko-bind] active handle chain layout unavailable for persistent string slot: %s\\n",
            what == NULL ? "<unknown>" : what);
        abort();
    }
    block = *(void**)((char*)thread + g_neko_off_thread_active_handles);
    while (block != NULL) {
        handles = (void**)((char*)block + g_neko_off_jnih_block_handles);
        begin = (uintptr_t)handles;
        end = begin + ((uintptr_t)g_neko_jnih_block_capacity * sizeof(void*));
        if (slot_addr >= begin && slot_addr < end && ((slot_addr - begin) % sizeof(void*)) == 0) {
            index = (int32_t)((slot_addr - begin) / sizeof(void*));
            if (block == save->block && index >= save->saved_top) {
                save->saved_top = index + 1;
            }
            return;
        }
        if (block == save->block) break;
        block = *(void**)((char*)block + g_neko_method_layout.off_jnih_block_next);
    }
    fprintf(stderr, "[neko-bind] persistent string handle escaped active chain: %s handle=%p saved_block=%p\\n",
        what == NULL ? "<unknown>" : what, (void*)handle, save->block);
    abort();
}

static jstring neko_bind_string_slot(void *thread, JNIEnv *env, jstring *slot, void **oop_slot, const char *utf) {
    neko_handle_save_t local_save;
    void *string_oop;
    jstring cached;
    jstring handle;
    void *cached_ref_oop;
    jboolean restoreJavaState = JNI_FALSE;
    if (env == NULL || slot == NULL || utf == NULL) return NULL;
    if (thread == NULL) {
        fprintf(stderr, "[neko-bind] JavaThread missing while binding string: %s\\n", utf);
        abort();
    }
    cached = *slot;
    if (cached != NULL && oop_slot != NULL && *oop_slot != NULL) {
        cached_ref_oop = NULL;
        if (neko_ref_is_direct_oop((jobject)cached)) {
            cached_ref_oop = neko_zgc_good_oop((void*)cached);
        } else if (neko_thread_ref_is_live_handle(thread, (jobject)cached)) {
            cached_ref_oop = neko_thread_ref_oop(thread, (jobject)cached);
        }
        if (cached_ref_oop != NULL
            && (cached_ref_oop == *oop_slot
            || neko_zgc_uncolor_oop(cached_ref_oop) == neko_zgc_uncolor_oop(*oop_slot))) {
            return cached;
        }
    }
    if (g_neko_off_thread_state > 0
        && g_neko_thread_state_in_java != 0
        && *(int32_t*)((char*)thread + g_neko_off_thread_state) == g_neko_thread_state_in_java) {
        neko_transition_java_to_native(thread);
        restoreJavaState = JNI_TRUE;
    }
    neko_handle_save(thread, &local_save);
    string_oop = neko_intern_string(thread, env, (const uint8_t*)utf, strlen(utf));
    if (neko_exception_check(env)) {
        neko_handle_restore(&local_save);
        neko_exception_clear(env);
        fprintf(stderr, "[neko-bind] native string literal intern left pending exception: %s\\n", utf);
        abort();
    }
    neko_handle_restore(&local_save);
    if (restoreJavaState) {
        neko_transition_native_to_java(thread);
    }
    handle = (jstring)neko_direct_oop_to_handle(thread, string_oop);
    neko_persist_current_handle_slot(thread, (jobject)handle, utf);
    *slot = handle;
    if (oop_slot != NULL) {
        *oop_slot = neko_thread_ref_oop(thread, (jobject)handle);
    }
    return handle;
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

""";
        String part3 = """
static jobjectArray neko_shadow_stack_trace(void *thread, JNIEnv *env) {
    jclass elem_cls;
    jobjectArray trace;
    neko_field_resolution_t declaring_class_field;
    neko_field_resolution_t method_name_field;
    neko_field_resolution_t file_name_field;
    neko_field_resolution_t line_number_field;
    uintptr_t array_bits;
    uint32_t depth = g_neko_shadow_depth;
    uint32_t count = depth == 0u ? 1u : depth;
    if (thread == NULL || env == NULL) {
        fprintf(stderr, "[neko-direct] shadow stack trace missing thread/env thread=%p env=%p\\n", thread, (void*)env);
        abort();
    }
    elem_cls = neko_resolve_class_mirror_with_env(env, "java/lang/StackTraceElement", NULL, NULL);
    if (elem_cls == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        fprintf(stderr, "[neko-direct] StackTraceElement class resolution failed\\n");
        abort();
    }
    declaring_class_field = neko_resolve_field(neko_class_mirror_to_klass(elem_cls), "declaringClass", "Ljava/lang/String;", JNI_FALSE);
    method_name_field = neko_resolve_field(neko_class_mirror_to_klass(elem_cls), "methodName", "Ljava/lang/String;", JNI_FALSE);
    file_name_field = neko_resolve_field(neko_class_mirror_to_klass(elem_cls), "fileName", "Ljava/lang/String;", JNI_FALSE);
    line_number_field = neko_resolve_field(neko_class_mirror_to_klass(elem_cls), "lineNumber", "I", JNI_FALSE);
    if (!declaring_class_field.found || !method_name_field.found || !file_name_field.found || !line_number_field.found) {
        fprintf(stderr, "[neko-direct] StackTraceElement field metadata unavailable\\n");
        abort();
    }
    array_bits = neko_array_klass_bits_for_descriptor(env, "[Ljava/lang/StackTraceElement;", elem_cls);
    trace = neko_fast_new_object_array(thread, env, (jint)count, array_bits, NULL);
    for (uint32_t i = 0u; i < count; i++) {
        const neko_shadow_frame *frame = depth == 0u
            ? NULL
            : &g_neko_shadow_stack[depth - 1u - (i < depth ? i : depth - 1u)];
        const char *owner = frame != NULL ? frame->owner : "<native>";
        const char *method = frame != NULL ? frame->method : "<unknown>";
        const char *file = frame != NULL ? frame->file : "Unknown.java";
        void *owner_oop = neko_intern_string(thread, env, (const uint8_t*)owner, strlen(owner));
        void *method_oop = neko_intern_string(thread, env, (const uint8_t*)method, strlen(method));
        void *file_oop = neko_intern_string(thread, env, (const uint8_t*)file, strlen(file));
        jobject elem = neko_fast_alloc_object(thread, env, elem_cls);
        char *elem_oop = (char*)neko_handle_oop(elem);
        if (elem_oop == NULL || owner_oop == NULL || method_oop == NULL || file_oop == NULL || neko_exception_check(env)) {
            if (neko_exception_check(env)) neko_exception_clear(env);
            fprintf(stderr, "[neko-direct] StackTraceElement native materialization failed\\n");
            abort();
        }
        neko_store_oop_raw(elem_oop, (jlong)declaring_class_field.offset, owner_oop);
        neko_store_oop_raw(elem_oop, (jlong)method_name_field.offset, method_oop);
        neko_store_oop_raw(elem_oop, (jlong)file_name_field.offset, file_oop);
        *(jint*)(elem_oop + line_number_field.offset) = -1;
        neko_fast_aastore(thread, env, trace, (jint)i, elem);
    }
    return trace;
}

static jclass neko_bound_class(void *thread, JNIEnv *env, jclass slot, const char *owner) {
    (void)env;
    if (slot != NULL) return neko_class_mirror_handle_from_slot(thread, slot, owner);
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
        return neko_canonical_klass_ptr((void*)(base + (bits << shift)));
    }
    return neko_canonical_klass_ptr((void*)bits);
}

static void *neko_object_ref_klass(void *thread, jobject obj) {
    char *oop;
    uintptr_t bits;
    if (obj == NULL) return NULL;
    if (!g_hotspot.initialized || g_hotspot.klass_offset_bytes <= 0) {
        fprintf(stderr, "[neko-bind] object Klass offset unavailable for current-owner Class LDC\\n");
        abort();
    }
    oop = (char*)neko_thread_ref_oop(thread, obj);
    if (oop == NULL) return NULL;
    if (g_hotspot.use_compressed_klass_ptrs) {
        bits = (uintptr_t)(*(uint32_t*)(oop + g_hotspot.klass_offset_bytes));
    } else {
        bits = *(uintptr_t*)(oop + g_hotspot.klass_offset_bytes);
    }
    return neko_decode_klass_header_bits(bits);
}

static void *neko_object_handle_klass(jobject obj) {
    return neko_object_ref_klass(NULL, obj);
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
        current_klass = neko_object_ref_klass(thread, self_or_class);
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
    if (slot != NULL && slot_klass != current_klass) {
        return current_mirror;
    }
    return current_mirror;
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

static jstring neko_bound_string(void *thread, JNIEnv *env, jstring *slot, void **oop_slot, const char *utf) {
    jstring result = neko_bind_string_slot(thread, env, slot, oop_slot, utf);
    if (result != NULL) return result;
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
        return part1 + part2 + part3;
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
            if (resolution.strings.isEmpty()) {
                sb.append("    if (g_owner_strings_bound_").append(ownerId).append(") return;\n");
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
                    sb.append("    (void)neko_bind_string_slot(thread, env, &").append(stringRef.cacheVar())
                        .append(", &").append(stringRef.cacheVar()).append("_oop, \"")
                        .append(c(stringRef.value())).append("\");\n");
                }
            }
            sb.append("}\n\n");
        }
        sb.append("static void neko_bind_manifest_owner_strings(void *thread, JNIEnv *env, const char *owner) {\n");
        sb.append("    if (thread == NULL || env == NULL || owner == NULL) {\n");
        sb.append("        fprintf(stderr, \"[neko-bind] manifest owner string bind missing thread/env/owner\\n\");\n");
        sb.append("        abort();\n");
        sb.append("    }\n");
        for (Map.Entry<String, Integer> entry : ownerBindIndex.entrySet()) {
            sb.append("    if (strcmp(owner, \"").append(c(entry.getKey())).append("\") == 0) {\n");
            sb.append("        neko_bind_owner_strings_").append(entry.getValue()).append("(thread, env);\n");
            sb.append("        return;\n");
            sb.append("    }\n");
        }
        sb.append("    fprintf(stderr, \"[neko-bind] manifest owner string binder missing: %s\\n\", owner);\n");
        sb.append("    abort();\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private boolean requiresLocalCapacity(CFunction fn) {
        for (CStatement statement : fn.body()) {
            if (statement instanceof CStatement.RawC raw) {
                String code = raw.code();
                if (code.contains("neko_call_")
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

__attribute__((visibility("hidden"))) void neko_transition_java_to_native(void *thread);
__attribute__((visibility("hidden"))) void neko_transition_native_to_java(void *thread);

__attribute__((visibility("hidden"))) extern ptrdiff_t g_neko_off_thread_pending_exception;
__attribute__((visibility("hidden"))) extern ptrdiff_t g_neko_off_thread_jni_environment_for_check;

static inline jobject neko_persistent_handle(JNIEnv *env, jobject obj, const char *what) {
    uintptr_t raw;
    uintptr_t slot;
    void *oop;
    (void)env;
    if (obj == NULL) return NULL;
    raw = (uintptr_t)obj;
#if UINTPTR_MAX > 0xffffffffu
    if ((raw & (uintptr_t)0x7u) == 0 && raw < (uintptr_t)0x0000100000000000ULL) {
        return obj;
    }
#endif
    slot = raw & ~(uintptr_t)0x3u;
    oop = *(void**)slot;
    if (oop == NULL) {
        fprintf(stderr, "[neko-bind] persistent handle source unresolved: %s ref=%p\\n",
            what == NULL ? "<unknown>" : what, (void*)obj);
        abort();
    }
    return (jobject)oop;
}

static inline void neko_exception_clear(JNIEnv *env) {
    void *thread;
    if (env == NULL
        || g_neko_off_thread_pending_exception <= 0
        || g_neko_off_thread_jni_environment_for_check <= 0) {
        fprintf(stderr, "[neko-direct] pending-exception metadata unavailable for native exception clear env=%p pending_off=%td jni_off=%td\\n",
            (void*)env, g_neko_off_thread_pending_exception, g_neko_off_thread_jni_environment_for_check);
        abort();
    }
    thread = (void*)((char*)env - g_neko_off_thread_jni_environment_for_check);
    *(void**)((char*)thread + g_neko_off_thread_pending_exception) = NULL;
}
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
static inline jboolean neko_exception_check(JNIEnv *env) {
    if (env != NULL
        && g_neko_off_thread_pending_exception > 0) {
        if (g_neko_off_thread_jni_environment_for_check <= 0) {
            fprintf(stderr, "[neko-direct] JNIEnv->JavaThread offset unavailable for native exception check env=%p pending_off=%td\\n",
                (void*)env, g_neko_off_thread_pending_exception);
            abort();
        }
        void *thread = (void*)((char*)env - g_neko_off_thread_jni_environment_for_check);
        return *(void**)((char*)thread + g_neko_off_thread_pending_exception) != NULL
            ? JNI_TRUE : JNI_FALSE;
    }
    fprintf(stderr, "[neko-direct] pending-exception metadata unavailable for native exception check env=%p pending_off=%td jni_off=%td\\n",
        (void*)env, g_neko_off_thread_pending_exception, g_neko_off_thread_jni_environment_for_check);
    abort();
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

static jobjectArray neko_shadow_stack_trace(void *thread, JNIEnv *env);

static jclass neko_class_for_descriptor(JNIEnv *env, const char *desc) {
    (void)env;
    fprintf(stderr, "[neko-direct] unsupported bootstrap Class descriptor materialization: %s\\n",
        desc == NULL ? "<null>" : desc);
    abort();
}

static jobject neko_method_type_from_descriptor(JNIEnv *env, const char *desc) {
    (void)env;
    fprintf(stderr, "[neko-direct] unsupported bootstrap MethodType materialization: %s\\n",
        desc == NULL ? "<null>" : desc);
    abort();
}

static jstring neko_string_null(JNIEnv *env) {
    static jstring g_str_null = NULL;
    static void *g_str_null_oop = NULL;
    return NEKO_ENSURE_STRING(g_str_null, env, "null");
}

static jobject neko_resolve_constant_dynamic(JNIEnv *env, const char *caller_owner, const char *name, const char *desc, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray static_args) {
    (void)env;
    (void)static_args;
    fprintf(stderr, "[neko-direct] unsupported ConstantDynamic native materialization: caller=%s name=%s desc=%s bsm=%s.%s%s\\n",
        caller_owner == NULL ? "<null>" : caller_owner,
        name == NULL ? "<null>" : name,
        desc == NULL ? "<null>" : desc,
        bsm_owner == NULL ? "<null>" : bsm_owner,
        bsm_name == NULL ? "<null>" : bsm_name,
        bsm_desc == NULL ? "<null>" : bsm_desc);
    abort();
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

NEKO_FAST_INLINE jboolean neko_ref_is_direct_oop(jobject ref) {
    uintptr_t raw;
    if (ref == NULL) return JNI_FALSE;
    raw = (uintptr_t)ref;
    if (g_hotspot.use_zgc) {
        uintptr_t good_mask = g_hotspot.z_pointer_load_good_mask | g_hotspot.z_pointer_store_good_mask;
        uintptr_t metadata_mask = good_mask | g_hotspot.z_pointer_load_bad_mask;
        uintptr_t valid_mask = g_hotspot.z_address_offset_mask | metadata_mask;
        if (good_mask != 0 && g_hotspot.z_address_offset_mask != 0) {
            return ((raw & metadata_mask) == good_mask
                && (raw & ~valid_mask) == 0) ? JNI_TRUE : JNI_FALSE;
        }
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

NEKO_FAST_INLINE void *neko_zgc_good_oop(void *oop) {
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

NEKO_FAST_INLINE void *neko_barrier_oop_load(void *raw_oop) {
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

NEKO_FAST_INLINE void *neko_barrier_load_oop_field(void *field_addr, void *raw_oop) {
    if (raw_oop == NULL) return NULL;
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

NEKO_FAST_INLINE void *neko_barrier_load_oop_array(void *element_addr, void *raw_oop) {
    if (raw_oop == NULL) return NULL;
    return g_neko_oop_array_load_barrier(element_addr, raw_oop);
}

static void neko_card_mark_field(void *field_addr) {
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

NEKO_FAST_INLINE void neko_barrier_pre_store_oop_field(void *thread, void *field_addr, void *old_oop) {
    g_neko_oop_field_store_pre_barrier(thread, field_addr, old_oop);
}

NEKO_FAST_INLINE void neko_barrier_post_store_oop_field(void *thread, void *field_addr) {
    g_neko_oop_field_store_post_barrier(thread, field_addr);
}

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

NEKO_FAST_INLINE void* neko_thread_ref_oop(void *thread, jobject ref) {
    uintptr_t raw;
    uintptr_t slot;
    void *block;
    if (ref == NULL) return NULL;
    raw = (uintptr_t)ref;
    slot = (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_HANDLE_TAGS) != 0 ? (raw & ~(uintptr_t)0x3u) : raw;
    if (thread != NULL && g_neko_handle_push_ready
        && g_neko_off_thread_active_handles > 0
        && g_neko_off_jnih_block_handles >= 0
        && g_neko_jnih_block_capacity > 0) {
        block = *(void**)((char*)thread + g_neko_off_thread_active_handles);
        for (int depth = 0; block != NULL && depth < 64; depth++) {
            void **handles = (void**)((char*)block + g_neko_off_jnih_block_handles);
            uintptr_t begin = (uintptr_t)handles;
            uintptr_t end = begin + ((uintptr_t)g_neko_jnih_block_capacity * sizeof(void*));
            if (slot >= begin && slot < end && ((slot - begin) % sizeof(void*)) == 0) {
                return neko_barrier_oop_load(*(void**)slot);
            }
            if (g_neko_off_jnih_block_next <= 0) break;
            block = *(void**)((char*)block + g_neko_off_jnih_block_next);
        }
    }
    return neko_handle_oop(ref);
}

NEKO_FAST_INLINE jboolean neko_thread_ref_is_live_handle(void *thread, jobject ref) {
    uintptr_t raw;
    uintptr_t slot;
    void *block;
    if (ref == NULL || thread == NULL || !g_neko_handle_push_ready
        || g_neko_off_thread_active_handles <= 0
        || g_neko_off_jnih_block_handles < 0
        || g_neko_off_jnih_block_top < 0
        || g_neko_jnih_block_capacity <= 0) {
        return JNI_FALSE;
    }
    raw = (uintptr_t)ref;
    if (neko_ref_is_direct_oop(ref)) return JNI_FALSE;
    slot = (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_HANDLE_TAGS) != 0 ? (raw & ~(uintptr_t)0x3u) : raw;
    block = *(void**)((char*)thread + g_neko_off_thread_active_handles);
    for (int depth = 0; block != NULL && depth < 64; depth++) {
        void **handles = (void**)((char*)block + g_neko_off_jnih_block_handles);
        int32_t top = *(int32_t*)((char*)block + g_neko_off_jnih_block_top);
        if (top < 0) top = 0;
        if (top > g_neko_jnih_block_capacity) top = g_neko_jnih_block_capacity;
        uintptr_t begin = (uintptr_t)handles;
        uintptr_t end = begin + ((uintptr_t)top * sizeof(void*));
        if (slot >= begin && slot < end && ((slot - begin) % sizeof(void*)) == 0) {
            return JNI_TRUE;
        }
        if (g_neko_off_jnih_block_next <= 0) break;
        block = *(void**)((char*)block + g_neko_off_jnih_block_next);
    }
    return JNI_FALSE;
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

NEKO_FAST_INLINE jint neko_fast_array_length(jarray arr) {
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
    (void)env;
    if (site == NULL) return 0u;
    existing = neko_icache_find_slot(site, receiverKey);
    if (existing >= 0) return (uint32_t)existing;
    for (i = 0u; i < NEKO_ICACHE_PIC_SIZE; i++) {
        if (site->target_kind[i] == NEKO_ICACHE_EMPTY) return i;
    }
    i = (uint32_t)(site->next_slot++ & (NEKO_ICACHE_PIC_SIZE - 1u));
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
    (void)env;
    if (site == NULL) return;
    slot = neko_icache_claim_slot(env, site, receiverKey);
    site->cached_class[slot] = cachedClass;
    site->receiver_key[slot] = receiverKey;
    site->target[slot] = target;
    site->target2[slot] = NULL;
    site->target_kind[slot] = NEKO_ICACHE_DIRECT_C;
}

/* Cache a resolved (Method*, _from_compiled_entry) pair for the receiver
 * class. Subsequent dispatches to the same class skip metadata walking
 * and invoke the per-shape dispatcher (from meta) directly with the cached
 * entry pointer. */
NEKO_FAST_INLINE void neko_icache_store_direct_njx(JNIEnv *env, neko_icache_site *site, uintptr_t receiverKey, jclass cachedClass, void *method_ptr, void *compiled_entry) {
    uint32_t slot;
    (void)env;
    if (site == NULL) return;
    slot = neko_icache_claim_slot(env, site, receiverKey);
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
    receiverKlass = neko_object_ref_klass(thread, receiver);
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
                /* Resolve the concrete target through HotSpot metadata. This
                 * is required for interface-declared
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
                void *exactMethod = neko_try_resolve_method(receiverKlass, meta->name, meta->desc);
                if (neko_njx_enabled() && g_neko_direct_invoke_ready) {
                    void *m_ptr = NULL, *m_entry = NULL;
                    if (exactMethod == NULL) {
                        if (getenv("NEKO_PATCH_DEBUG") != NULL) {
                            jboolean __receiver_live = neko_thread_ref_is_live_handle(thread, receiver);
                            void *__receiver_oop = __receiver_live
                                ? neko_thread_ref_oop(thread, receiver)
                                : (neko_ref_is_direct_oop(receiver) ? (void*)receiver : NULL);
                            fprintf(stderr,
                                "[neko-debug] dispatch receiver target=%s%s ref=%p live=%d oop=%p klass=%p key=0x%llx\\n",
                                meta->name, meta->desc, (void*)receiver, (int)__receiver_live,
                                __receiver_oop, receiverKlass, (unsigned long long)receiverKey);
                        }
                        neko_debug_dump_method_resolution(receiverKlass, meta->name, meta->desc);
                        fprintf(stderr, "[neko-bind] native method resolution failed: %s%s\\n", meta->name, meta->desc);
                        abort();
                    }
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
     * Emit fast paths for AALOAD / AASTORE that avoid the per-element helper
     * round-trip. The old helper path allocated a fresh local-ref handle for every load — for a tight
     * matrix-multiply inner loop this is the single biggest cost (millions of
     * handle allocations chained into JNIHandleBlock _next blocks).
     *
     * Fast path: read the element oop directly from the array's narrow-oop /
     * wide-oop slot and push it into the active JNIHandleBlock via the same
     * inlined neko_handle_push the dispatcher uses for ref args. Aborts if
     * VMStructs did not recover all the bits we need
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

NEKO_FAST_INLINE jobject neko_direct_oop_to_handle(void *thread, void *raw_oop);

static void neko_fast_string_runtime_init(JNIEnv *env) {
    void *string_klass;
    void *byte_array_klass;
    if (g_neko_fast_string_alloc_ready || env == NULL) return;
    if (!g_hotspot.initialized
        || (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) == 0
        || g_hotspot.use_compact_object_headers
        || g_hotspot.klass_offset_bytes <= 0
        || !g_neko_tlab_alloc_ready) {
        return;
    }
    string_klass = neko_resolve_class_with_env(env, "java/lang/String", NULL);
    byte_array_klass = neko_resolve_class_with_env(env, "[B", NULL);
    g_neko_string_klass_bits = neko_klass_header_bits(string_klass);
    g_neko_byte_array_klass_bits = neko_klass_header_bits(byte_array_klass);
    g_neko_fast_string_alloc_ready =
        (g_neko_string_klass_bits != 0 && g_neko_byte_array_klass_bits != 0) ? JNI_TRUE : JNI_FALSE;
}

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

NEKO_FAST_INLINE void neko_tlab_ensure_top_at_least(void *thread, char *min_top) {
    char *tlab;
    char *top;
    char *end;
    if (!g_neko_tlab_alloc_ready || thread == NULL || min_top == NULL) return;
    tlab = (char*)thread + g_neko_off_thread_tlab;
    top = *(char**)(tlab + g_neko_off_tlab_top);
    end = *(char**)(tlab + g_neko_off_tlab_end);
    if (top != NULL && end != NULL && top < min_top && min_top <= end) {
        *(char**)(tlab + g_neko_off_tlab_top) = min_top;
    }
}

NEKO_FAST_INLINE void neko_init_oop_header(char *oop, uintptr_t klass_bits) {
    *(uintptr_t*)oop = (uintptr_t)1u;
    if (g_hotspot.use_compressed_klass_ptrs) {
        *(uint32_t*)(oop + g_hotspot.klass_offset_bytes) = (uint32_t)klass_bits;
    } else {
        *(uintptr_t*)(oop + g_hotspot.klass_offset_bytes) = klass_bits;
    }
}

NEKO_FAST_INLINE void* neko_decode_narrow_oop(uint32_t narrow) {
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
    jobject array_handle;
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
    left_oop = (char*)neko_thread_ref_oop(thread, (jobject)left);
    right_oop = (char*)neko_thread_ref_oop(thread, (jobject)right);
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
        array_handle = (jobject)neko_direct_oop_to_handle(thread, array_oop);
        neko_refill_tlab_with_slow_byte_array(env, string_bytes > (size_t)INT32_MAX ? INT32_MAX : (jint)string_bytes);
        array_oop = (char*)neko_thread_ref_oop(thread, array_handle);
        if (array_oop == NULL) return NULL;
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
    if (getenv("NEKO_PATCH_DEBUG") != NULL) {
        void *left_oop = left == NULL ? NULL : neko_thread_ref_oop(thread, (jobject)left);
        void *right_oop = right == NULL ? NULL : neko_thread_ref_oop(thread, (jobject)right);
        void *left_klass = left_oop == NULL ? NULL : neko_object_ref_klass(thread, (jobject)left);
        void *right_klass = right_oop == NULL ? NULL : neko_object_ref_klass(thread, (jobject)right);
        fprintf(stderr, "[neko-debug] string concat unavailable left_ref=%p left_oop=%p leftKlass=%p right_ref=%p right_oop=%p rightKlass=%p valueOffset=%lld coderOffset=%lld\\n",
            (void*)left, left_oop, left_klass, (void*)right, right_oop, right_klass,
            (long long)valueOffset, (long long)coderOffset);
    }
    fprintf(stderr, "[neko-direct] native String concat unavailable left=%p right=%p valueOffset=%lld coderOffset=%lld\\n",
        (void*)left, (void*)right, (long long)valueOffset, (long long)coderOffset);
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
        fprintf(stderr, "[neko-direct] NEW TLAB allocation failed cls=%p klass=%p bytes=%zu\\n",
            (void*)cls, klass, bytes);
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

NEKO_FAST_INLINE void *neko_load_object_array_slot(char *array_oop, size_t base, jint idx, size_t ref_size) {
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

NEKO_FAST_INLINE char *neko_inner_oop_from_outer(char *outer_oop, jint idx1, jint outer_len) {
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
     * Fast paths for object field reads (GETFIELD / GETSTATIC of L-types).
     * Skips the per-call object-field helper path
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
NEKO_FAST_INLINE jobject neko_fast_aaload_aaload(void *thread, JNIEnv *env, jobjectArray outer, jint idx1, jint idx2, int *reason) {
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
        sb.append("NEKO_FAST_INLINE ").append(cType).append(" neko_fast_aaload_").append(prefix)
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
