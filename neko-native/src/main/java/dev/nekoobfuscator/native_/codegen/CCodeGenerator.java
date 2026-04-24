package dev.nekoobfuscator.native_.codegen;

import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.native_.codegen.emit.BootstrapEmitter;
import dev.nekoobfuscator.native_.codegen.emit.CEmissionContext;
import dev.nekoobfuscator.native_.codegen.emit.AssemblyStubEmitter;
import dev.nekoobfuscator.native_.codegen.emit.EntryPatchEmitter;
import dev.nekoobfuscator.native_.codegen.emit.ImplBodyEmitter;
import dev.nekoobfuscator.native_.codegen.emit.JniOnLoadEmitter;
import dev.nekoobfuscator.native_.codegen.emit.ManifestEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave1RuntimeEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave2FieldLdcEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave3InvokeStaticEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave4aRuntimeApiEmitter;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.Type;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CCodeGenerator {
    public static final int MANIFEST_FLAGS_OFFSET = 32;
    public static final int MANIFEST_SIGNATURE_ID_OFFSET = 36;
    public static final int MANIFEST_IMPL_FN_OFFSET = 40;
    public static final int MANIFEST_METHOD_STAR_OFFSET = 48;
    public static final int MANIFEST_ENTRY_SIZE = 88;

    @SuppressWarnings("unused")
    private final SymbolTableGenerator symbols;
    private final CEmissionContext ctx;
    private final ManifestEmitter manifestEmitter;
    private final Wave1RuntimeEmitter wave1RuntimeEmitter;
    private final Wave2FieldLdcEmitter wave2FieldLdcEmitter;
    private final Wave3InvokeStaticEmitter wave3InvokeStaticEmitter;
    private final Wave4aRuntimeApiEmitter wave4aRuntimeApiEmitter;
    private final EntryPatchEmitter entryPatchEmitter;
    private final AssemblyStubEmitter assemblyStubEmitter;
    private final ImplBodyEmitter implBodyEmitter;
    private final JniOnLoadEmitter jniOnLoadEmitter;
    private final BootstrapEmitter bootstrapEmitter;
    private final LinkedHashMap<String, Integer> classSlotIndex;
    private final LinkedHashMap<String, Integer> methodSlotIndex;
    private final LinkedHashMap<String, Integer> fieldSlotIndex;
    private final LinkedHashMap<String, Integer> ownerBindIndex;
    private final LinkedHashMap<String, OwnerResolution> ownerResolutions;
    private final LinkedHashMap<String, Integer> icacheMethodIndex;
    private final LinkedHashMap<String, IcacheSiteRef> icacheSites;
    private final LinkedHashMap<String, IcacheDirectStubRef> icacheDirectStubs;
    private final LinkedHashMap<String, IcacheMetaRef> icacheMetas;
    private final LinkedHashSet<String> manifestOwnerInternals;

    public CCodeGenerator(long masterSeed) {
        this.ctx = new CEmissionContext(masterSeed);
        this.manifestEmitter = new ManifestEmitter(this, ctx);
        this.wave1RuntimeEmitter = new Wave1RuntimeEmitter();
        this.wave2FieldLdcEmitter = new Wave2FieldLdcEmitter();
        this.wave3InvokeStaticEmitter = new Wave3InvokeStaticEmitter(this, ctx);
        this.wave4aRuntimeApiEmitter = new Wave4aRuntimeApiEmitter();
        this.entryPatchEmitter = new EntryPatchEmitter();
        this.assemblyStubEmitter = new AssemblyStubEmitter(ctx);
        this.implBodyEmitter = new ImplBodyEmitter();
        this.jniOnLoadEmitter = new JniOnLoadEmitter();
        this.bootstrapEmitter = new BootstrapEmitter(wave1RuntimeEmitter);
        this.symbols = ctx.symbols();
        this.classSlotIndex = ctx.classSlotIndex();
        this.methodSlotIndex = ctx.methodSlotIndex();
        this.fieldSlotIndex = ctx.fieldSlotIndex();
        this.ownerBindIndex = ctx.ownerBindIndex();
        this.ownerResolutions = ctx.ownerResolutions();
        this.icacheMethodIndex = ctx.icacheMethodIndex();
        this.icacheSites = ctx.icacheSites();
        this.icacheDirectStubs = ctx.icacheDirectStubs();
        this.icacheMetas = ctx.icacheMetas();
        this.manifestOwnerInternals = ctx.manifestOwnerInternals();
    }

    public void configureStringCacheCount(int stringCacheCount) {
        ctx.setStringCacheCount(stringCacheCount);
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

    public String methodSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mid_" + internMethod(owner, name, desc, isStatic);
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
        manifestOwnerInternals.add(ownerInternalName);
        internClass(ownerInternalName);
    }

    public int registerManifestMethod(String bindingKey) {
        return manifestEmitter.registerManifestMethod(bindingKey);
    }

    public String reserveManifestFieldSite(String bindingKey, String bindingOwner, String owner, String name, String desc, boolean isStatic) {
        return manifestEmitter.reserveManifestFieldSite(bindingKey, bindingOwner, owner, name, desc, isStatic);
    }

    public String reserveManifestInvokeSite(String bindingKey, String bindingOwner, String owner, String name, String desc, int opcode) {
        return manifestEmitter.reserveManifestInvokeSite(bindingKey, bindingOwner, owner, name, desc, opcode);
    }

    public String reserveManifestStringLdcSite(String bindingKey, String bindingOwner, String literal) {
        return manifestEmitter.reserveManifestStringLdcSite(bindingKey, bindingOwner, literal);
    }

    public String reserveManifestClassLdcSite(String bindingKey, String bindingOwner, String descriptor) {
        return manifestEmitter.reserveManifestClassLdcSite(bindingKey, bindingOwner, descriptor);
    }

    public String reserveManifestMethodTypeLdcSite(String bindingKey, String bindingOwner, String descriptor) {
        return manifestEmitter.reserveManifestMethodTypeLdcSite(bindingKey, bindingOwner, descriptor);
    }

    public String reserveManifestMethodHandleLdcSite(
        String bindingKey,
        String bindingOwner,
        int tag,
        String owner,
        String name,
        String desc,
        boolean isInterface
    ) {
        return manifestEmitter.reserveManifestMethodHandleLdcSite(bindingKey, bindingOwner, tag, owner, name, desc, isInterface);
    }

    public void registerOwnerClassReference(String bindingOwner, String classOwner) {
        registerBindingOwner(bindingOwner);
        ownerResolutions.get(bindingOwner).classes.add(classOwner);
        manifestOwnerInternals.add(classOwner);
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

    public String reserveInvokeCacheMeta(
        String bindingOwner,
        String methodKey,
        int siteIndex,
        String name,
        String desc,
        boolean isInterface,
        String translatedClassSlot,
        String translatedStubSymbol
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
            translatedStubSymbol
        )).symbol();
    }

    public String generateHeader(List<NativeMethodBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("#ifndef NEKO_NATIVE_H\n");
        sb.append("#define NEKO_NATIVE_H\n\n");
        sb.append("#include <jni.h>\n");
        sb.append("#include <stdint.h>\n\n");
        for (NativeMethodBinding binding : bindings) {
            sb.append(assemblyStubEmitter.renderPrototype(binding)).append(";\n");
        }
        sb.append("\n#endif\n");
        return sb.toString();
    }

    public List<GeneratedSource> generateAdditionalSources(List<NativeMethodBinding> bindings) {
        return assemblyStubEmitter.generateAdditionalSources(bindings);
    }

    public List<SignatureInfo> signatureInfos(List<NativeMethodBinding> bindings) {
        return assemblyStubEmitter.signatureInfos(bindings);
    }

    public String generateSource(List<CFunction> functions, List<NativeMethodBinding> bindings) {
        SignaturePlan signaturePlan = assemblyStubEmitter.buildSignaturePlan(bindings);
        StringBuilder body = new StringBuilder();
        for (CFunction function : functions) {
            body.append(implBodyEmitter.renderFunction(function)).append("\n");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#if defined(__linux__)\n");
        sb.append("#define _GNU_SOURCE\n");
        sb.append("#endif\n");
        sb.append("#include \"neko_native.h\"\n");
        sb.append("#include <stdint.h>\n");
        sb.append("#include <stddef.h>\n");
        sb.append("#include <stdbool.h>\n");
        sb.append("#include <stdio.h>\n");
        sb.append("#include <stdlib.h>\n");
        sb.append("#include <string.h>\n");
        sb.append("#include <stdarg.h>\n");
        sb.append("#include <math.h>\n\n");
        sb.append("#if defined(_WIN32)\n");
        sb.append("#include <windows.h>\n");
        sb.append("#include <psapi.h>\n");
        sb.append("#else\n");
        sb.append("#include <dlfcn.h>\n");
        sb.append("#endif\n");
        sb.append("#if defined(__linux__)\n");
        sb.append("#include <link.h>\n");
        sb.append("#endif\n");
        sb.append("#if defined(__APPLE__)\n");
        sb.append("#include <mach-o/dyld.h>\n");
        sb.append("#endif\n\n");
        sb.append(wave3InvokeStaticEmitter.renderResolutionCaches());
        sb.append(manifestEmitter.renderManifestSupport(bindings, signaturePlan));
        sb.append(renderEarlyForwardDecls());
        sb.append(wave1RuntimeEmitter.renderRuntimeSupport());
        sb.append(wave1RuntimeEmitter.renderHotSpotSupport());
        sb.append(assemblyStubEmitter.renderSignatureDispatchSupport(signaturePlan));
        sb.append(bootstrapEmitter.renderBootstrapSupport());
        sb.append(wave3InvokeStaticEmitter.renderBindSupport());
        sb.append(wave3InvokeStaticEmitter.renderBindOwnerFunctions());
        sb.append(wave2FieldLdcEmitter.renderWave2Support());
        sb.append(wave3InvokeStaticEmitter.renderWave3Support());
        sb.append(wave4aRuntimeApiEmitter.renderWave4ASupport());
        sb.append(entryPatchEmitter.renderEntryPatchSupport());
        sb.append(wave4aRuntimeApiEmitter.renderObjectReturnSupport());
        sb.append(wave3InvokeStaticEmitter.renderIcacheDirectStubs());
        sb.append(wave3InvokeStaticEmitter.renderIcacheMetas());
        sb.append(body);
        sb.append(jniOnLoadEmitter.renderJniOnLoad());
        return sb.toString();
    }

    private String renderEarlyForwardDecls() {
        return """
            /* ---------- Neko early forward declarations (auto-generated) ---------- */
            typedef uint32_t u4;
            typedef uint64_t u8;

            #ifdef NEKO_DEBUG_ENABLED
            static int neko_debug_level = 0;
            #define NEKO_TRACE(level, ...) do { if (neko_debug_level >= (level)) fprintf(stderr, __VA_ARGS__); } while(0)
            #else
            #define NEKO_TRACE(level, ...) ((void)0)
            #endif

            typedef struct NekoVmLayout {
                int java_spec_version;
                size_t method_size;
                size_t instance_klass_size;
                size_t vtable_entry_size;
                size_t itable_offset_entry_size;
                size_t itable_method_entry_size;
                size_t access_flags_size;
                size_t method_flags_size;
                size_t java_frame_anchor_size;
                ptrdiff_t off_method_const_method;
                ptrdiff_t off_method_access_flags;
                ptrdiff_t off_method_code;
                ptrdiff_t off_method_i2i_entry;
                ptrdiff_t off_method_from_interpreted_entry;
                ptrdiff_t off_method_from_compiled_entry;
                ptrdiff_t off_method_vtable_index;
                ptrdiff_t off_method_intrinsic_id;
                ptrdiff_t off_method_flags_direct;
                ptrdiff_t off_method_flags_status;
                ptrdiff_t off_const_method_constants;
                ptrdiff_t off_const_method_max_stack;
                ptrdiff_t off_const_method_max_locals;
                ptrdiff_t off_const_method_size_of_parameters;
                ptrdiff_t off_const_method_method_idnum;
                ptrdiff_t off_const_method_flags_bits;
                ptrdiff_t off_const_method_name_index;
                ptrdiff_t off_const_method_signature_index;
                ptrdiff_t off_constant_pool_holder;
                ptrdiff_t off_klass_layout_helper;
                ptrdiff_t off_klass_name;
                ptrdiff_t off_klass_next_link;
                ptrdiff_t off_klass_java_mirror;
                ptrdiff_t off_class_klass;
                ptrdiff_t off_instance_klass_constants;
                ptrdiff_t off_instance_klass_methods;
                ptrdiff_t off_instance_klass_fields;
                ptrdiff_t off_instance_klass_java_fields_count;
                ptrdiff_t off_instance_klass_init_state;
                ptrdiff_t off_instance_klass_java_mirror;
                ptrdiff_t off_instance_klass_static_field_size;
                ptrdiff_t off_instance_klass_static_oop_field_count;
                ptrdiff_t off_instance_klass_vtable_start;
                ptrdiff_t off_instance_klass_itable_start;
                ptrdiff_t off_string_value;
                ptrdiff_t off_string_coder;
                ptrdiff_t off_array_base_byte;
                ptrdiff_t off_array_scale_byte;
                ptrdiff_t off_array_base_char;
                ptrdiff_t off_array_scale_char;
                ptrdiff_t off_thread_tlab;
                ptrdiff_t off_thread_pending_exception;
                ptrdiff_t off_thread_thread_state;
                ptrdiff_t off_tlab_start;
                ptrdiff_t off_tlab_top;
                ptrdiff_t off_tlab_pf_top;
                ptrdiff_t off_tlab_end;
                ptrdiff_t off_thread_tlab_start_direct;
                ptrdiff_t off_thread_tlab_top_direct;
                ptrdiff_t off_thread_tlab_pf_top_direct;
                ptrdiff_t off_thread_tlab_end_direct;
                ptrdiff_t off_thread_tlab_start;
                ptrdiff_t off_thread_tlab_top;
                ptrdiff_t off_thread_tlab_pf_top;
                ptrdiff_t off_thread_tlab_end;
                ptrdiff_t off_thread_exception_oop;
                ptrdiff_t off_thread_exception_pc;
                ptrdiff_t off_java_thread_anchor;
                ptrdiff_t off_java_thread_last_Java_sp;
                ptrdiff_t off_java_thread_last_Java_fp;
                ptrdiff_t off_java_thread_last_Java_pc;
                ptrdiff_t off_java_frame_anchor_sp;
                ptrdiff_t off_java_frame_anchor_fp;
                ptrdiff_t off_java_frame_anchor_pc;
                ptrdiff_t off_java_thread_jni_environment;
                ptrdiff_t off_oophandle_obj;
                uint32_t access_not_c1_compilable;
                uint32_t access_not_c2_compilable;
                uint32_t access_not_osr_compilable;
                uint32_t method_flag_not_c1_compilable;
                uint32_t method_flag_not_c2_compilable;
                uint32_t method_flag_not_c1_osr_compilable;
                uint32_t method_flag_not_c2_osr_compilable;
                uint32_t method_flag_dont_inline;
                uintptr_t narrow_oop_base;
                int narrow_oop_shift;
                uintptr_t narrow_klass_base;
                int narrow_klass_shift;
                int thread_state_in_java;
                int thread_state_in_vm;
                char instance_klass_fields_strategy;
                char instance_klass_java_mirror_strategy;
                char method_flags_status_strategy;
                char string_value_strategy;
                char string_coder_strategy;
                char class_klass_strategy;
                char array_base_byte_strategy;
                char array_scale_byte_strategy;
                char array_base_char_strategy;
                char array_scale_char_strategy;
                char thread_tlab_start_strategy;
                char thread_tlab_top_strategy;
                char thread_tlab_pf_top_strategy;
                char thread_tlab_end_strategy;
                char thread_exception_oop_strategy;
                char thread_exception_pc_strategy;
                char thread_thread_state_strategy;
                char java_thread_anchor_strategy;
                char java_thread_last_Java_sp_strategy;
                char java_thread_last_Java_fp_strategy;
                char java_thread_last_Java_pc_strategy;
                char java_thread_jni_environment_strategy;
                char oophandle_obj_strategy;
                jboolean constant_pool_holder_is_narrow;
                jboolean has_narrow_oop_base;
                jboolean has_narrow_oop_shift;
                jboolean has_narrow_klass_base;
                jboolean has_narrow_klass_shift;
                jboolean wave4a_disabled;
                jboolean use_compact_object_headers;
                /* Wave 4b-4a strict-nojni STRING intern infrastructure */
                size_t constant_pool_size;
                ptrdiff_t off_constant_pool_tags;
                ptrdiff_t off_constant_pool_length;
                ptrdiff_t off_symbol_length;
                ptrdiff_t off_symbol_body;
                ptrdiff_t off_instance_klass_fieldinfo_stream;
                ptrdiff_t off_string_hash;
                ptrdiff_t off_loader_loaded_field;
                uintptr_t off_cldg_head;
                ptrdiff_t off_cld_next;
                ptrdiff_t off_cld_class_loader;
                ptrdiff_t off_cld_klasses;
                ptrdiff_t off_cld_handles;
                jboolean cld_class_loader_is_oophandle;
                void* klass_java_lang_String;
                void* klass_array_char;
                void* klass_array_byte;
                void* klass_neko_native_loader;
                void* klass_exc_npe;
                void* klass_exc_aioobe;
                void* klass_exc_cce;
                void* klass_exc_ae;
                void* klass_exc_le;
                void* klass_exc_oom;
                void* klass_exc_imse;
                void* klass_exc_ase;
                void* klass_exc_nase;
                /* W0 DD-6: dlsym'd function pointers (optional symbols) */
                void* allocate_instance_fn;
                void* java_thread_current_fn;
            } NekoVmLayout;
            extern NekoVmLayout g_neko_vm_layout;
            static jthrowable g_neko_throw_npe = NULL;
            static jthrowable g_neko_throw_aioobe = NULL;
            static jthrowable g_neko_throw_cce = NULL;
            static jthrowable g_neko_throw_ae = NULL;
            static jthrowable g_neko_throw_le = NULL;
            static jthrowable g_neko_throw_oom = NULL;
            static jthrowable g_neko_throw_imse = NULL;
            static jthrowable g_neko_throw_ase = NULL;
            static jthrowable g_neko_throw_nase = NULL;
            static jthrowable g_neko_throw_bme = NULL;
            static jthrowable g_neko_throw_loader_linkage = NULL;
            #define NEKO_THROW_NPE    1u
            #define NEKO_THROW_AIOOBE 2u
            #define NEKO_THROW_CCE    3u
            #define NEKO_THROW_AE     4u
            #define NEKO_THROW_LE     5u
            #define NEKO_THROW_OOM    6u
            #define NEKO_THROW_IMSE   7u
            #define NEKO_THROW_ASE    8u
            #define NEKO_THROW_NASE   9u
            #define NEKO_THROW_BME    10u
            #define NEKO_THROW_AND_RETURN(env_expr, throwable_expr, ret_expr) \
                do { \
                    (void)neko_throw_cached((env_expr), (throwable_expr)); \
                    return ret_expr; \
                } while (0)
            #define NEKO_THROW_AND_RETURN_VOID(env_expr, throwable_expr) \
                do { \
                    (void)neko_throw_cached((env_expr), (throwable_expr)); \
                    return; \
                } while (0)
            typedef struct Klass Klass;
            typedef void* oop;
            static inline void* neko_decode_klass_pointer(u4 narrow);
            static void* neko_class_klass_pointer(jclass klass_obj);
            static jclass neko_load_class_noinit_with_loader(JNIEnv *env, const char *internalName, jobject loader);
            static jboolean neko_ldc_site_matches_loaded_class(JNIEnv *env, NekoManifestLdcSite *site, jclass candidate, const char *signature);
            __attribute__((visibility(\"default\"))) oop neko_rt_mirror_from_klass_nosafepoint(Klass *k);
            __attribute__((visibility(\"default\"))) oop neko_rt_static_base_from_holder_nosafepoint(Klass *holder);
            __attribute__((visibility(\"default\"))) oop neko_rt_try_alloc_instance_fast_nosafepoint(Klass *ik, size_t instance_size_bytes);
            static void* neko_rt_try_alloc_array_fast_nosafepoint(void* array_klass, int32_t length);
            static inline uint32_t neko_lh_header_size(uint32_t lh);
            static inline uint32_t neko_lh_log2_element(uint32_t lh);
            static inline size_t neko_lh_instance_size(uint32_t lh);
            __attribute__((visibility("default"))) void* neko_get_current_thread(void);
            static inline void* neko_pending_exception(void *thread);
            static inline void neko_set_pending_exception(void *thread, void *oop);
            static inline void neko_clear_pending_exception(void *thread);
            __attribute__((visibility("default"))) void neko_raise_athrow(void *thread, void *exception_oop);
            static inline void neko_store_heap_oop_at_unpublished(void* base, int32_t offset, void* raw_oop);
            static inline void* neko_load_heap_oop_from_published(void* base, int32_t offset);
            static inline int32_t neko_object_array_element_offset(void* array_klass, int32_t index);
            static jint neko_throw_cached(JNIEnv *env, jthrowable cached);
            static jboolean neko_init_throwable_cache(JNIEnv *env);
            static void neko_bootstrap_owner_discovery(void);
            static void neko_maybe_rescan_cld_liveness(void);
            static inline oop neko_resolve_mirror_oop_from_klass(const NekoVmLayout *layout, Klass *klass);
            static void* neko_find_boot_class_loader_data(void);
            static inline void* neko_method_holder_klass(void *method_star);
            static ptrdiff_t neko_wave2_object_field_offset_by_name(JNIEnv *env, const char *owner_internal, const char *field_name);
            static void neko_derive_wave2_layout_offsets(JNIEnv *env);
            static inline void* neko_load_heap_oop_at(void *base, ptrdiff_t offset, jboolean is_volatile);
            static void neko_derive_class_klass_offset_from_mirror(void *known_klass);
            /* ---------------------------------------------------------------------- */

            """;
    }

    public record GeneratedSource(String fileName, String content) {}

    public record SignatureInfo(int id, String key) {}

    public enum ArgLocationKind {
        GP_REG,
        FP_REG,
        STACK,
        INTERPRETER_STACK
    }

    public record ArgLocation(ArgLocationKind kind, int index) {}

    public record CallingLayout(List<ArgLocation> locations, int stackSlotCount, int gpRegisterCount) {}

    public record DispatchPlan(
        List<Character> logicalArgKinds,
        CallingLayout sourceLayout,
        CallingLayout destLayout,
        int frameBytes,
        int entrySaveOffset,
        int retSaveOffset,
        int gpSpillBaseOffset
    ) {}

    public record SignatureShape(int id, String key, char returnKind, List<Character> argKinds) {}

    public record SignaturePlan(
        List<SignatureShape> signatures,
        List<Integer> bindingSignatureIds,
        int maxArgCount,
        Map<String, Integer> signatureIdsByKey
    ) {}

    public record MethodRef(String owner, String name, String desc, boolean isStatic) {}

    public record FieldRef(String owner, String name, String desc, boolean isStatic) {}

    public record StringRef(String cacheVar, String value) {}

    public enum LdcKind {
        STRING("NEKO_LDC_KIND_STRING"),
        CLASS("NEKO_LDC_KIND_CLASS"),
        METHOD_HANDLE("NEKO_LDC_KIND_METHOD_HANDLE"),
        METHOD_TYPE("NEKO_LDC_KIND_METHOD_TYPE");

        private final String constant;

        LdcKind(String constant) {
            this.constant = constant;
        }

        public String constant() {
            return constant;
        }
    }

    public record Utf8BlobRef(int methodId, int siteIndex, byte[] bytes) {
        public String symbol() {
            return "g_neko_utf8_" + methodId + '_' + siteIndex;
        }
    }

    public record ManifestFieldSiteRef(
        int methodId,
        int siteIndex,
        int ownerClassIndex,
        String owner,
        String name,
        String desc,
        boolean isStatic,
        boolean isReference
    ) {
        public String arrayElementExpression() {
            return "&" + "g_neko_field_sites_" + methodId + '[' + siteIndex + ']';
        }
    }

    public record ManifestInvokeSiteRef(
        int methodId,
        int siteIndex,
        String owner,
        String name,
        String desc,
        int opcode,
        String signatureKey
    ) {}

    public record ManifestLdcSiteRef(
        int methodId,
        int siteIndex,
        int ownerClassIndex,
        String ownerInternal,
        LdcKind kind,
        String rawConstant,
        Utf8BlobRef blob
    ) {
        public String arrayElementExpression() {
            return "&" + "g_neko_ldc_sites_" + methodId + '[' + siteIndex + ']';
        }
    }

    public record IcacheSiteRef(int ownerId, int methodId, int siteIndex, String bindingOwner, String methodKey) {
        private String symbol() {
            return "neko_icache_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    public record IcacheDirectStubRef(
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

    public record IcacheMetaRef(
        int ownerId,
        int methodId,
        int siteIndex,
        String name,
        String desc,
        boolean isInterface,
        String translatedClassSlot,
        String translatedStubSymbol
    ) {
        private String symbol() {
            return "neko_icache_meta_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    public static final class OwnerResolution {
        private final Set<String> classes = new LinkedHashSet<>();
        private final Set<MethodRef> methods = new LinkedHashSet<>();
        private final Set<FieldRef> fields = new LinkedHashSet<>();
        private final Set<StringRef> strings = new LinkedHashSet<>();

        public Set<String> classes() {
            return classes;
        }

        public Set<MethodRef> methods() {
            return methods;
        }

        public Set<FieldRef> fields() {
            return fields;
        }

        public Set<StringRef> strings() {
            return strings;
        }
    }
}
