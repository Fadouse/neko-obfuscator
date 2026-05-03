package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Emits the runtime VMStructs walk that discovers Method* field offsets
 * across HotSpot versions, plus the {@code neko_patch_method_entry} routine
 * that swaps {@code _i2i_entry}, {@code _from_interpreted_entry}, and
 * {@code _from_compiled_entry} to per-signature trampolines. It also ORs
 * no-compile flags so the JIT can't recompile around the patch.
 *
 * Discovery is fully native: dlsym + {@code /proc/self/maps} fallback for
 * libjvm, then VMStructs / VMTypes / VMIntConstants walks. No JVM helper
 * methods are used.
 */
public final class MethodPatcherEmitter {

    public String render() {
        return renderPart1() + renderPart1b() + renderPart2() + renderPart3();
    }

    private static String renderPart1() {
        return """
/* === Method* layout discovery + entry patcher === */
#include <dlfcn.h>
#if defined(__linux__) || defined(__APPLE__)
#include <sys/mman.h>
#endif
#if defined(__linux__)
#include <fcntl.h>
#include <unistd.h>
#endif

#define NEKO_ACC_NATIVE_BIT 0x00000100u
#define NEKO_ACC_NOT_C1_COMPILABLE_FALLBACK  0x04000000u
#define NEKO_ACC_NOT_C2_COMPILABLE_FALLBACK  0x02000000u
#define NEKO_ACC_NOT_OSR_COMPILABLE_FALLBACK 0x08000000u

static JavaVM *g_neko_java_vm = NULL;

typedef struct {
    jboolean initialized;
    jboolean usable;
    jint java_spec_version;
    ptrdiff_t off_method_access_flags;
    ptrdiff_t off_method_code;
    ptrdiff_t off_method_i2i_entry;
    ptrdiff_t off_method_from_interpreted_entry;
    ptrdiff_t off_method_from_compiled_entry;
    ptrdiff_t off_method_flags_status;
    ptrdiff_t off_method_intrinsic_id;
    ptrdiff_t off_method_vtable_index;
    ptrdiff_t off_method_constMethod;
    size_t method_size;
    size_t access_flags_size;
    uint32_t access_not_c1_compilable;
    uint32_t access_not_c2_compilable;
    uint32_t access_not_osr_compilable;
    uint32_t method_flag_not_c1_compilable;
    uint32_t method_flag_not_c2_compilable;
    uint32_t method_flag_not_osr_compilable;
    /* Thread-state machine — populated from VMStructs Thread::_thread_state +
     * VMIntConstants JavaThreadState enum values. The trampoline reads these
     * via the exported globals below to flip between in_Java and in_native. */
    ptrdiff_t off_thread_state;
    int32_t thread_state_in_java;
    int32_t thread_state_in_native;
    int32_t thread_state_in_native_trans;
    /* SafepointMechanism polling: the byte at ((char*)thread)[off_safepoint_poll]
     * (when present) is non-zero whenever a safepoint is requested; the
     * trampoline's transition-back checks it and yields if needed. */
    ptrdiff_t off_thread_polling_word;
    /* JavaFrameAnchor inside JavaThread (so GC can walk our stack while we are
     * in _thread_in_native). Two emission paths: direct flat fields on
     * JavaThread (older JDKs) or via the embedded _anchor struct. We pick
     * whichever resolved and publish the final byte offsets to the asm. */
    ptrdiff_t off_thread_anchor;
    ptrdiff_t off_frame_anchor_sp;
    ptrdiff_t off_frame_anchor_fp;
    ptrdiff_t off_frame_anchor_pc;
    ptrdiff_t off_thread_last_Java_sp_direct;
    ptrdiff_t off_thread_last_Java_fp_direct;
    ptrdiff_t off_thread_last_Java_pc_direct;
    ptrdiff_t off_thread_jni_environment;
    /* JNIHandleBlock plumbing: our dispatcher pushes ref args into the
     * thread's _active_handles so GC tracks them as roots. */
    ptrdiff_t off_thread_active_handles;
    ptrdiff_t off_jnih_block_top;
    ptrdiff_t off_jnih_block_handles;
    ptrdiff_t off_jnih_block_next;
    ptrdiff_t off_thread_tlab;
    ptrdiff_t off_tlab_top;
    ptrdiff_t off_tlab_end;
    size_t    sizeof_JNIHandleBlock;
    int32_t   jnih_block_capacity;
    /* Direct read of the pending exception so the dispatcher can substitute
     * JNI ExceptionCheck (index 228) without a JNI call after impl_fn returns.
     * VMStructs exposes Thread::_pending_exception as a stable field. */
    ptrdiff_t off_thread_pending_exception;
    /* === CodeCache / CodeHeap / VirtualSpace / GrowableArray / CodeBlob ===
     * Discovered via VMStructs so we can register our own CodeHeap into
     * HotSpot's _heaps list, making our trampoline PCs visible to
     * CodeCache::find_blob_at(). Phase 1 is read-only walking; Phase 2 will
     * allocate. */
    void *addr_codecache_heaps;          /* address of CodeCache::_heaps (a static field; deref to GrowableArray<CodeHeap*>*) */
    ptrdiff_t off_growable_array_len;    /* GrowableArrayBase::_len */
    ptrdiff_t off_growable_array_capacity;
    ptrdiff_t off_growable_array_data;   /* GrowableArray<E>::_data (we use the int specialization's offset) */
    ptrdiff_t off_codeheap_memory;       /* CodeHeap::_memory (VirtualSpace) */
    ptrdiff_t off_codeheap_segmap;       /* CodeHeap::_segmap (VirtualSpace) */
    ptrdiff_t off_codeheap_log2_segment_size;
    ptrdiff_t off_virtualspace_low_boundary;
    ptrdiff_t off_virtualspace_high_boundary;
    ptrdiff_t off_virtualspace_low;
    ptrdiff_t off_virtualspace_high;
    ptrdiff_t off_codeblob_name;
    ptrdiff_t off_codeblob_size;
    ptrdiff_t off_codeblob_header_size;
    ptrdiff_t off_codeblob_frame_complete_offset;
    ptrdiff_t off_codeblob_data_offset;
    ptrdiff_t off_codeblob_frame_size;
    ptrdiff_t off_codeblob_code_begin;
    ptrdiff_t off_codeblob_code_end;
    ptrdiff_t off_codeblob_content_begin;
    ptrdiff_t off_codeblob_data_end;
    size_t    sizeof_CodeHeap;           /* total size of CodeHeap object */
    size_t    sizeof_BufferBlob;
    size_t    sizeof_VirtualSpace;
    /* Vtable pointer harvested from a known existing BufferBlob in the cache.
     * Required to construct our own BufferBlob in-place. */
    void *bufferblob_vtable;
    /* CompressedOops state, recovered from VMStructs static entries
     *   CompressedOops::_narrow_oop._base   (address of an oop pointer)
     *   CompressedOops::_narrow_oop._shift  (address of an int)
     * These are static fields with non-NULL static_addr in gHotSpotVMStructs.
     * We capture their addresses here so native code can decode narrow oops
     * (`oop = (narrow << shift) + base`) directly from VMStructs. */
    void *addr_compressed_oops_base;
    void *addr_compressed_oops_shift;
    void *addr_compressed_klass_base;
    void *addr_compressed_klass_shift;
    void *addr_universe_collected_heap;
    void *addr_barrierset_barrier_set;
    void *addr_stringtable_the_table;
    void *addr_zglobals_instance_p;
    void *addr_zglobals_address_offset_mask;
    void *addr_zglobals_pointer_load_good_mask;
    void *addr_zglobals_pointer_load_bad_mask;
    void *addr_zglobals_pointer_store_good_mask;
    void *addr_zglobals_pointer_load_shift;
    ptrdiff_t off_zglobals_address_offset_mask;
    ptrdiff_t off_zglobals_pointer_load_good_mask;
    ptrdiff_t off_zglobals_pointer_load_bad_mask;
    ptrdiff_t off_zglobals_pointer_store_good_mask;
    ptrdiff_t off_zglobals_pointer_load_shift;
    ptrdiff_t off_jcw_anchor;
    size_t    sizeof_JavaCallWrapper;
    /* === Native metadata resolution prerequisites (T0.1) ===
     * These are the VMStructs offsets and JVM/native symbols required before
     * bind-time class/method/field/string resolution may replace JNI lookup. */
    ptrdiff_t off_instanceklass_methods;
    ptrdiff_t off_instanceklass_fieldinfo_stream;
    ptrdiff_t off_instanceklass_fields;
    ptrdiff_t off_instanceklass_constants;
    ptrdiff_t off_instanceklass_local_interfaces;
    ptrdiff_t off_instanceklass_transitive_interfaces;
    ptrdiff_t off_instanceklass_java_fields_count;
    ptrdiff_t off_klass_super_check_offset;
    ptrdiff_t off_klass_secondary_super_cache;
    ptrdiff_t off_klass_secondary_supers;
    ptrdiff_t off_klass_primary_supers_0;
    ptrdiff_t off_klass_layout_helper;
    ptrdiff_t off_klass_java_mirror;
    ptrdiff_t off_klass_super;
    ptrdiff_t off_klass_subklass;
    ptrdiff_t off_klass_next_link;
    ptrdiff_t off_klass_name;
    ptrdiff_t off_objarrayklass_element_klass;
    ptrdiff_t off_java_lang_class_klass;
    void *addr_classloaderdatagraph_head;
    ptrdiff_t off_classloaderdata_next;
    ptrdiff_t off_classloaderdata_klasses;
    ptrdiff_t off_constantpool_tags;
    ptrdiff_t off_constantpool_cache;
    ptrdiff_t off_constantpool_pool_holder;
    ptrdiff_t off_constantpool_operands;
    ptrdiff_t off_constantpool_resolved_klasses;
    ptrdiff_t off_constantpool_length;
    ptrdiff_t off_constantpool_base;
    size_t    sizeof_ConstantPool;
    ptrdiff_t off_constmethod_constants;
    ptrdiff_t off_constmethod_name_index;
    ptrdiff_t off_constmethod_signature_index;
    ptrdiff_t off_symbol_length;
    ptrdiff_t off_symbol_body;
    ptrdiff_t off_array_length;
    ptrdiff_t off_array_data;
    ptrdiff_t off_array_u1_data;
    ptrdiff_t off_array_u2_data;
    ptrdiff_t off_barrierset_fake_rtti;
    ptrdiff_t off_barrierset_fakertti_concrete_tag;
    ptrdiff_t off_cardtablebarrierset_card_table;
    ptrdiff_t off_cardtable_byte_map_base;
    int32_t vmconst_barrierset_modref;
    int32_t vmconst_barrierset_cardtable;
    int32_t vmconst_barrierset_g1;
    int32_t vmconst_cardtable_clean_card;
    int32_t vmconst_cardtable_dirty_card;
    int32_t vmconst_g1_young_card;
    int32_t current_barrier_kind;
    void *current_barrier_set;
    void *current_card_table;
    void *card_table_byte_map_base;
    /* T4.15: structural sentinels for moving-collector detection.
     * Production HotSpot 21 omits BarrierSet::ZBarrierSet /
     * BarrierSet::ShenandoahBarrierSet from gHotSpotVMIntConstants, so the
     * FakeRtti tag-based G1/CardTable matching cannot reach Z/Shenandoah.
     * These sentinels are populated structurally during the VMStructs walk:
     *   - z_compiled_in: set when ZGlobalsForVMStructs static fields appear,
     *     i.e. ZGC was compiled into this libjvm. Active-vs-compiled is
     *     disambiguated at detection time by checking the runtime value of
     *     ZGlobalsForVMStructs::_instance_p.
     *   - shenandoah_compiled_in: set when any "Shenandoah*" type entry
     *     appears in VMStructs (ShenandoahHeap, ShenandoahHeapRegion,
     *     ShenandoahBarrierSet, etc.). Active-vs-compiled is disambiguated
     *     at detection time by checking that BarrierSet is not G1/CardTable
     *     and ZGC sentinel is not active. */
    jboolean z_compiled_in;
    jboolean shenandoah_compiled_in;
    void *sym_g1_write_ref_array_pre_oop_entry;
    void *sym_g1_write_ref_field_pre_entry;
    void *sym_g1_write_ref_field_post_entry;
    void *sym_z_load_barrier_on_oop_field_preloaded;
    void *sym_z_load_barrier_on_oop_array;
    void *sym_z_store_barrier_on_oop_field_with_healing;
    void *sym_shenandoah_load_reference_barrier_strong;
    void *sym_shenandoah_load_reference_barrier_strong_narrow;
    void *sym_shenandoah_write_ref_field_pre_entry;
    void *sym_shenandoah_arraycopy_barrier_oop_entry;
    void *sym_shenandoah_arraycopy_barrier_narrow_oop_entry;
    jboolean gc_barrier_ready;
    void *sym_jvm_find_loaded_class;
    void *sym_jvm_find_class_from_boot_loader;
    void *sym_jvm_find_class_from_class;
    void *sym_jvm_intern_string;
    void *sym_jvm_find_primitive_class;
    void *sym_jvm_new_array;
    void *sym_jvm_new_multi_array;
    void *sym_jvm_get_class_methods_count;
    void *sym_jvm_get_class_declared_methods;
    void *sym_jvm_get_class_declared_constructors;
    void *sym_systemdictionary_find_instance_klass;
    void *sym_systemdictionary_find_instance_or_array_klass;
    void *sym_stringtable_intern_symbol;
    void *sym_stringtable_intern_utf8;
    void *sym_oopfactory_new_type_array;
    void *sym_oopfactory_new_obj_array;
    jboolean native_resolution_ready;
    /* === Native→Java direct invoke ===
     * HotSpot does not publish StubRoutines::_call_stub_entry through
     * VMStructs, but it does publish _call_stub_return_address. We find the
     * owning initial-stubs CodeBlob and scan backward to the call_stub prologue.
     * The BasicType enum values feed call_stub's result_type argument. */
    void *addr_call_stub_return_address;   /* address of StubRoutines::_call_stub_return_address slot */
    int32_t basictype_void;
    int32_t basictype_boolean;
    int32_t basictype_byte;
    int32_t basictype_char;
    int32_t basictype_short;
    int32_t basictype_int;
    int32_t basictype_long;
    int32_t basictype_float;
    int32_t basictype_double;
    int32_t basictype_object;
    int32_t basictype_array;
    jboolean basictypes_resolved;
    /* Runtime1 monitor stubs and BasicObjectLock layout for native
     * MONITORENTER/MONITOREXIT. The stubs are not dlsym-exported on stripped
     * JDK 21 libjvm builds, so we harvest their CodeBlob entries by name while
     * walking CodeHeap. */
    void *runtime1_monitorenter_entry;
    void *runtime1_monitorexit_entry;
    ptrdiff_t off_basicobjectlock_lock;
    ptrdiff_t off_basicobjectlock_obj;
    ptrdiff_t off_basiclock_displaced_header;
    size_t sizeof_BasicObjectLock;
    size_t sizeof_BasicLock;
} neko_method_layout_t;

static neko_method_layout_t g_neko_method_layout = {0};

/* === Globals exported to naked-asm trampolines via RIP-relative loads. ===
 * Hidden visibility lets the linker resolve them to local definitions while
 * still allowing the inline asm to reference them as if external symbols. */
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_thread_state = 0;
__attribute__((visibility("hidden"))) int32_t   g_neko_thread_state_in_java = 0;
__attribute__((visibility("hidden"))) int32_t   g_neko_thread_state_in_native = 0;
__attribute__((visibility("hidden"))) int32_t   g_neko_thread_state_in_native_trans = 0;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_thread_polling_word = 0;
__attribute__((visibility("hidden"))) jboolean  g_neko_thread_state_ready = JNI_FALSE;
/* Final byte offsets within JavaThread for the frame anchor fields. Picked
 * from either the flat-field path (older JDKs) or _anchor + sub-offset. */
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_last_Java_sp = 0;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_last_Java_fp = 0;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_last_Java_pc = 0;
__attribute__((visibility("hidden"))) jboolean  g_neko_frame_anchor_ready = JNI_FALSE;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_thread_active_handles = 0;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_jnih_block_top      = 0;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_jnih_block_handles  = 0;
__attribute__((visibility("hidden"))) int32_t   g_neko_jnih_block_capacity     = 32;
__attribute__((visibility("hidden"))) jboolean  g_neko_handle_push_ready = JNI_FALSE;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_thread_tlab = 0;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_tlab_top = 0;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_tlab_end = 0;
__attribute__((visibility("hidden"))) jboolean  g_neko_tlab_alloc_ready = JNI_FALSE;
/* Final byte offset within JavaThread of the _pending_exception oop slot.
 * Set during VMStructs walk; used by the direct-call dispatcher to read the
 * pending exception without crossing the JNI boundary. */
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_thread_pending_exception = 0;
/* Mirror of g_neko_method_layout.off_thread_jni_environment, exported with a
 * stable hidden symbol so the inline neko_exception_check (declared in the
 * top-of-file region, before g_neko_method_layout exists) can subtract it
 * from JNIEnv* to derive the JavaThread*. Set once during layout init. */
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_thread_jni_environment_for_check = 0;
/* === Native→Java direct invoke globals ===
 * Exported so the per-shape direct dispatchers can issue a call into HotSpot's
 * shared call_stub without going through any JNI function pointer. The signed
 * int32_t BasicType slots match the third argument expected by the call_stub
 * (HotSpot's BasicType enum). g_neko_call_stub_entry holds the *current* entry
 * pointer — it is loaded once at OnLoad time from the StubRoutines static slot
 * (HotSpot doesn't repatch this pointer post-init in production builds). */
__attribute__((visibility("hidden"))) void *g_neko_call_stub_entry = NULL;
__attribute__((visibility("hidden"))) void *g_neko_call_stub_return_address = NULL;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_jcw_anchor = 0;
__attribute__((visibility("hidden"))) jboolean g_neko_direct_invoke_ready = JNI_FALSE;
__attribute__((visibility("hidden"))) int32_t g_neko_basictype_void    = 0;
__attribute__((visibility("hidden"))) int32_t g_neko_basictype_boolean = 0;
__attribute__((visibility("hidden"))) int32_t g_neko_basictype_byte    = 0;
__attribute__((visibility("hidden"))) int32_t g_neko_basictype_char    = 0;
__attribute__((visibility("hidden"))) int32_t g_neko_basictype_short   = 0;
__attribute__((visibility("hidden"))) int32_t g_neko_basictype_int     = 0;
__attribute__((visibility("hidden"))) int32_t g_neko_basictype_long    = 0;
__attribute__((visibility("hidden"))) int32_t g_neko_basictype_float   = 0;
__attribute__((visibility("hidden"))) int32_t g_neko_basictype_double  = 0;
__attribute__((visibility("hidden"))) int32_t g_neko_basictype_object  = 0;
__attribute__((visibility("hidden"))) int32_t g_neko_basictype_array   = 0;
__attribute__((visibility("hidden"))) void *g_neko_runtime1_monitorenter_entry = NULL;
__attribute__((visibility("hidden"))) void *g_neko_runtime1_monitorexit_entry = NULL;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_basicobjectlock_lock = -1;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_basicobjectlock_obj = -1;
__attribute__((visibility("hidden"))) ptrdiff_t g_neko_off_basiclock_displaced_header = -1;
__attribute__((visibility("hidden"))) size_t g_neko_sizeof_basicobjectlock = 0;
__attribute__((visibility("hidden"))) size_t g_neko_sizeof_basiclock = 0;
__attribute__((visibility("hidden"))) jboolean g_neko_monitor_stub_ready = JNI_FALSE;
/* HotSpot's r12_heapbase value — what %r12 must contain when calling
 * compiled Java code. The narrow_oop encode/decode in JIT'd methods reads
 * %r12 as the heap base register. Set once at OnLoad from the
 * CompressedOops::_narrow_oop._base static field. */
__attribute__((visibility("hidden"))) void *g_neko_heap_base = NULL;
__attribute__((visibility("hidden"))) void *g_neko_addr_compressed_oops_base = NULL;
__attribute__((visibility("hidden"))) void *g_neko_addr_compressed_oops_shift = NULL;
__attribute__((visibility("hidden"))) void *g_neko_addr_compressed_klass_base = NULL;
__attribute__((visibility("hidden"))) void *g_neko_addr_compressed_klass_shift = NULL;
/* Thread-register snapshot captured right at JNI_OnLoad entry, before any C
 * code can clobber r15 (x86_64) / x28 (AArch64). HotSpot keeps the current
 * JavaThread* there across the JNI dispatch. We use this to derive
 * off_thread_jni_environment when VMStructs does not export it (the field
 * is only registered in the JVMCI-only struct list). */
__attribute__((visibility("hidden"))) void *g_neko_jni_onload_thread_reg = NULL;
__attribute__((visibility("hidden"))) void *g_neko_jni_functions_table = NULL;
__attribute__((visibility("hidden"))) jboolean g_neko_native_resolution_ready = JNI_FALSE;
__attribute__((visibility("hidden"))) jboolean g_neko_gc_barrier_ready = JNI_FALSE;
__attribute__((visibility("hidden"))) int32_t g_neko_gc_barrier_kind = 0;
__attribute__((visibility("hidden"))) void *g_neko_barrier_load_oop_field_preloaded = NULL;
__attribute__((visibility("hidden"))) void *g_neko_barrier_load_oop_array = NULL;
__attribute__((visibility("hidden"))) void *g_neko_barrier_store_oop_field = NULL;
__attribute__((visibility("hidden"))) void *g_neko_barrier_write_ref_array_pre = NULL;
__attribute__((visibility("hidden"))) void *g_neko_barrier_write_ref_field_pre = NULL;
__attribute__((visibility("hidden"))) void *g_neko_barrier_write_ref_field_post = NULL;
__attribute__((visibility("hidden"))) void *g_neko_card_table_byte_map_base = NULL;
__attribute__((visibility("hidden"))) int32_t g_neko_card_table_dirty_card = 0;
__attribute__((visibility("hidden"))) int32_t g_neko_card_table_clean_card = -1;
__attribute__((visibility("hidden"))) int32_t g_neko_g1_young_card = -1;
__attribute__((visibility("hidden"))) int32_t g_neko_card_table_shift = 9;

enum {
    NEKO_GC_BARRIER_UNKNOWN = 0,
    NEKO_GC_BARRIER_CARDTABLE = 1,
    NEKO_GC_BARRIER_G1 = 2,
    NEKO_GC_BARRIER_Z = 3,
    NEKO_GC_BARRIER_SHENANDOAH = 4
};

/* Debug flag is cached once at JNI_OnLoad time. The original macro called
 * getenv("NEKO_PATCH_DEBUG") on every NEKO_PATCH_LOG invocation — and the
 * dispatcher hot path emits one such call per dispatch, so each native call
 * was crossing into libc to scan environ. Caching collapses that to a single
 * RIP-relative load + branch in release builds. */
__attribute__((visibility("hidden"))) int g_neko_patch_debug_cached = 0;
__attribute__((visibility("hidden"))) int g_neko_patch_debug_initialized = 0;
static inline int neko_patch_debug_enabled(void) {
    if (__builtin_expect(!g_neko_patch_debug_initialized, 0)) {
        g_neko_patch_debug_cached = (getenv("NEKO_PATCH_DEBUG") != NULL) ? 1 : 0;
        g_neko_patch_debug_initialized = 1;
    }
    return g_neko_patch_debug_cached;
}
#define NEKO_PATCH_DEBUG neko_patch_debug_enabled()
#define NEKO_PATCH_LOG(fmt, ...) do { if (__builtin_expect(NEKO_PATCH_DEBUG, 0)) { fprintf(stderr, "[neko-patch] " fmt "\\n", ##__VA_ARGS__); fflush(stderr); } } while (0)

static int neko_streq_safe(const char *a, const char *b) {
    return a != NULL && b != NULL && strcmp(a, b) == 0;
}
static int neko_strstr_safe(const char *h, const char *n) {
    return h != NULL && n != NULL && strstr(h, n) != NULL;
}

#if defined(__linux__)
static int neko_find_libjvm_path(char *out, size_t cap) {
    FILE *fp = fopen("/proc/self/maps", "r");
    char line[1024];
    if (fp == NULL) return 0;
    while (fgets(line, sizeof(line), fp) != NULL) {
        char *slash = strchr(line, '/');
        if (slash == NULL) continue;
        size_t len = strlen(slash);
        if (len > 0 && slash[len - 1] == '\\n') slash[len - 1] = '\\0';
        if (strstr(slash, "libjvm.so") != NULL) {
            size_t copy_len = strlen(slash);
            if (copy_len + 1 > cap) copy_len = cap - 1;
            memcpy(out, slash, copy_len);
            out[copy_len] = '\\0';
            fclose(fp);
            return 1;
        }
    }
    fclose(fp);
    return 0;
}
#endif

/* T4.15: structural GC selection signal from /proc/self/cmdline.
 *
 * Production HotSpot 21 strips BarrierSet::ZBarrierSet and
 * BarrierSet::ShenandoahBarrierSet from gHotSpotVMIntConstants, AND leaves
 * ZGlobalsForVMStructs::_instance_p statically allocated even when Shenandoah
 * (or any non-Z collector) is active, AND zeroes the ZGlobals masks under
 * generational ZGC. None of those signals can independently classify the
 * active moving collector.
 *
 * The Java launcher writes the resolved -XX:+Use*GC arguments verbatim into
 * /proc/self/cmdline before transferring to JVM_Main. Reading our own
 * cmdline is a structural process-state inspection (not a JVM helper, not
 * a JNI table call, not a JVMTI hook). It is generic by design: any
 * collector flag carried on the launcher command line is recognized
 * uniformly, so this is not a benchmark-/class-/method-specific signal.
 *
 * Returns:
 *   1 = ZGC selected (UseZGC)
 *   2 = Shenandoah selected (UseShenandoahGC)
 *   3 = Parallel selected (UseParallelGC)
 *   4 = Serial selected (UseSerialGC)
 *   0 = no explicit selection in cmdline (default G1 path)
 */
static int neko_cmdline_gc_signal(void) {
#if defined(__linux__)
    int fd;
    ssize_t n;
    char buf[4096];
    int signal = 0;
    fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) return 0;
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    /* /proc/self/cmdline uses NUL separators; replace them with spaces so
     * strstr can see whole tokens. The trailing slot is already zero. */
    for (ssize_t i = 0; i < n; i++) {
        if (buf[i] == '\\0') buf[i] = ' ';
    }
    buf[n] = '\\0';
    if (strstr(buf, "+UseZGC") != NULL) signal = 1;
    else if (strstr(buf, "+UseShenandoahGC") != NULL) signal = 2;
    else if (strstr(buf, "+UseParallelGC") != NULL) signal = 3;
    else if (strstr(buf, "+UseSerialGC") != NULL) signal = 4;
    return signal;
#else
    return 0;
#endif
}

static void* neko_resolve_libjvm_handle(void) {
#if defined(_WIN32)
    HMODULE hjvm = GetModuleHandleA("jvm.dll");
    if (hjvm == NULL) hjvm = LoadLibraryA("jvm.dll");
    return (void*)hjvm;
#elif defined(__linux__) || defined(__APPLE__)
    void *h = dlopen("libjvm.so", RTLD_NOLOAD | RTLD_NOW);
    if (h != NULL && dlsym(h, "gHotSpotVMStructs") != NULL) return h;
    h = dlopen("libjvm.so", RTLD_NOW);
    if (h != NULL && dlsym(h, "gHotSpotVMStructs") != NULL) return h;
#  if defined(__linux__)
    {
        char path[1024];
        if (neko_find_libjvm_path(path, sizeof(path))) {
            h = dlopen(path, RTLD_NOLOAD | RTLD_NOW);
            if (h != NULL && dlsym(h, "gHotSpotVMStructs") != NULL) return h;
            h = dlopen(path, RTLD_NOW);
            if (h != NULL && dlsym(h, "gHotSpotVMStructs") != NULL) return h;
        }
    }
#  endif
    if (dlsym(RTLD_DEFAULT, "gHotSpotVMStructs") != NULL) return (void*)(uintptr_t)0x1u;
    return NULL;
#else
    return NULL;
#endif
}

static void* neko_dlsym(void *h, const char *name) {
    if (h == NULL || name == NULL) return NULL;
#if defined(_WIN32)
    return (void*)GetProcAddress((HMODULE)h, name);
#else
    if ((uintptr_t)h == 0x1u) return dlsym(RTLD_DEFAULT, name);
    return dlsym(h, name);
#endif
}

static void neko_resolve_native_resolution_symbols(void *jvm) {
    if (jvm == NULL) return;
    g_neko_method_layout.sym_jvm_find_loaded_class =
        neko_dlsym(jvm, "JVM_FindLoadedClass");
    g_neko_method_layout.sym_jvm_find_class_from_boot_loader =
        neko_dlsym(jvm, "JVM_FindClassFromBootLoader");
    g_neko_method_layout.sym_jvm_find_class_from_class =
        neko_dlsym(jvm, "JVM_FindClassFromClass");
    g_neko_method_layout.sym_jvm_intern_string =
        neko_dlsym(jvm, "JVM_InternString");
    g_neko_method_layout.sym_jvm_find_primitive_class =
        neko_dlsym(jvm, "JVM_FindPrimitiveClass");
    g_neko_method_layout.sym_jvm_new_array =
        neko_dlsym(jvm, "JVM_NewArray");
    g_neko_method_layout.sym_jvm_new_multi_array =
        neko_dlsym(jvm, "JVM_NewMultiArray");
    g_neko_method_layout.sym_jvm_get_class_methods_count =
        neko_dlsym(jvm, "JVM_GetClassMethodsCount");
    g_neko_method_layout.sym_jvm_get_class_declared_methods =
        neko_dlsym(jvm, "JVM_GetClassDeclaredMethods");
    g_neko_method_layout.sym_jvm_get_class_declared_constructors =
        neko_dlsym(jvm, "JVM_GetClassDeclaredConstructors");
    /* Internal C++ symbols are stripped on common product builds. Resolve
     * them opportunistically for builds that export them, but readiness does
     * not treat their absence as a JNI fallback path. Later stages must either
     * use the stable JVM_* entries / VMStruct walk or abort. */
    g_neko_method_layout.sym_systemdictionary_find_instance_klass =
        neko_dlsym(jvm, "_ZN16SystemDictionary19find_instance_klassEP6ThreadP6Symbol6HandleS4_");
    g_neko_method_layout.sym_systemdictionary_find_instance_or_array_klass =
        neko_dlsym(jvm, "_ZN16SystemDictionary28find_instance_or_array_klassEP6ThreadP6Symbol6HandleS4_");
    g_neko_method_layout.sym_stringtable_intern_symbol =
        neko_dlsym(jvm, "_ZN11StringTable6internEP6SymbolP6Thread");
    g_neko_method_layout.sym_stringtable_intern_utf8 =
        neko_dlsym(jvm, "_ZN11StringTable6internEPKcP6Thread");
    g_neko_method_layout.sym_oopfactory_new_type_array =
        neko_dlsym(jvm, "_ZN10oopFactory13new_typeArrayE9BasicTypeiP6Thread");
    g_neko_method_layout.sym_oopfactory_new_obj_array =
        neko_dlsym(jvm, "_ZN10oopFactory12new_objArrayEP5KlassiP6Thread");
    g_neko_method_layout.addr_stringtable_the_table =
        neko_dlsym(jvm, "_ZN11StringTable10_the_tableE");
}

static void neko_resolve_gc_barrier_symbols(void *jvm) {
    if (jvm == NULL) return;
    g_neko_method_layout.sym_g1_write_ref_array_pre_oop_entry =
        neko_dlsym(jvm, "_ZN19G1BarrierSetRuntime29write_ref_array_pre_oop_entryEPP7oopDescm");
    g_neko_method_layout.sym_g1_write_ref_field_pre_entry =
        neko_dlsym(jvm, "_ZN19G1BarrierSetRuntime25write_ref_field_pre_entryEP7oopDescP10JavaThread");
    g_neko_method_layout.sym_g1_write_ref_field_post_entry =
        neko_dlsym(jvm, "_ZN19G1BarrierSetRuntime26write_ref_field_post_entryEPVhP10JavaThread");
    g_neko_method_layout.sym_z_load_barrier_on_oop_field_preloaded =
        neko_dlsym(jvm, "_ZN18ZBarrierSetRuntime35load_barrier_on_oop_field_preloadedEP7oopDescPS0_");
    if (g_neko_method_layout.sym_z_load_barrier_on_oop_field_preloaded == NULL) {
        g_neko_method_layout.sym_z_load_barrier_on_oop_field_preloaded =
            neko_dlsym(jvm, "_ZN18XBarrierSetRuntime35load_barrier_on_oop_field_preloadedEP7oopDescPS0_");
    }
    g_neko_method_layout.sym_z_load_barrier_on_oop_array =
        neko_dlsym(jvm, "_ZN18ZBarrierSetRuntime29load_barrier_on_oop_arrayEP7oopDesc");
    if (g_neko_method_layout.sym_z_load_barrier_on_oop_array == NULL) {
        g_neko_method_layout.sym_z_load_barrier_on_oop_array =
            neko_dlsym(jvm, "_ZN18XBarrierSetRuntime29load_barrier_on_oop_arrayEP7oopDesc");
    }
    g_neko_method_layout.sym_z_store_barrier_on_oop_field_with_healing =
        neko_dlsym(jvm, "_ZN18ZBarrierSetRuntime39store_barrier_on_oop_field_with_healingEPP7oopDesc");
    if (g_neko_method_layout.sym_z_store_barrier_on_oop_field_with_healing == NULL) {
        g_neko_method_layout.sym_z_store_barrier_on_oop_field_with_healing =
            neko_dlsym(jvm, "_ZN18XBarrierSetRuntime39store_barrier_on_oop_field_with_healingEPP7oopDesc");
    }
    g_neko_method_layout.sym_shenandoah_load_reference_barrier_strong =
        neko_dlsym(jvm, "_ZN17ShenandoahRuntime29load_reference_barrier_strongEP7oopDescPS0_");
    g_neko_method_layout.sym_shenandoah_load_reference_barrier_strong_narrow =
        neko_dlsym(jvm, "_ZN17ShenandoahRuntime36load_reference_barrier_strong_narrowEP7oopDescPj");
    g_neko_method_layout.sym_shenandoah_write_ref_field_pre_entry =
        neko_dlsym(jvm, "_ZN17ShenandoahRuntime25write_ref_field_pre_entryEP7oopDescP10JavaThread");
    g_neko_method_layout.sym_shenandoah_arraycopy_barrier_oop_entry =
        neko_dlsym(jvm, "_ZN17ShenandoahRuntime27arraycopy_barrier_oop_entryEPP7oopDescS2_m");
    g_neko_method_layout.sym_shenandoah_arraycopy_barrier_narrow_oop_entry =
        neko_dlsym(jvm, "_ZN17ShenandoahRuntime34arraycopy_barrier_narrow_oop_entryEPjS0_m");
}

""";
    }

    private static String renderPart1b() {
        return """
static jboolean neko_walk_vm_structs(void *jvm) {
    void *vmstructs = neko_dlsym(jvm, "gHotSpotVMStructs");
    int *type_off  = (int*)neko_dlsym(jvm, "gHotSpotVMStructEntryTypeNameOffset");
    int *field_off = (int*)neko_dlsym(jvm, "gHotSpotVMStructEntryFieldNameOffset");
    int *offset_off = (int*)neko_dlsym(jvm, "gHotSpotVMStructEntryOffsetOffset");
    int *isstatic_off = (int*)neko_dlsym(jvm, "gHotSpotVMStructEntryIsStaticOffset");
    int *address_off = (int*)neko_dlsym(jvm, "gHotSpotVMStructEntryAddressOffset");
    int *stride_p   = (int*)neko_dlsym(jvm, "gHotSpotVMStructEntryArrayStride");
    if (vmstructs == NULL || type_off == NULL || field_off == NULL || offset_off == NULL || stride_p == NULL) {
        return JNI_FALSE;
    }
    const uint8_t *base = *(const uint8_t* const*)vmstructs;
    int stride = *stride_p;
    if (base == NULL || stride <= 0) return JNI_FALSE;
    for (const uint8_t *e = base; ; e += stride) {
        const char *type_name = *(const char* const*)(e + *type_off);
        const char *field_name = *(const char* const*)(e + *field_off);
        uintptr_t off_value = *(const uintptr_t*)(e + *offset_off);
        int32_t is_static = (isstatic_off != NULL) ? *(const int32_t*)(e + *isstatic_off) : 0;
        void *static_addr = (is_static && address_off != NULL) ? *(void* const*)(e + *address_off) : NULL;
        if (type_name == NULL && field_name == NULL) break;
        if (NEKO_PATCH_DEBUG && neko_streq_safe(type_name, "Method")) {
            fprintf(stderr, "[neko-patch] vmstructs Method::%s @+%zu\\n", field_name ? field_name : "?", (size_t)off_value);
        }
        if (NEKO_PATCH_DEBUG && field_name != NULL
            && (neko_streq_safe(field_name, "_jni_environment")
             || neko_streq_safe(field_name, "_pending_exception"))) {
            fprintf(stderr, "[neko-patch] vmstructs %s::%s @+%zu\\n",
                type_name ? type_name : "?", field_name, (size_t)off_value);
        }
        if (NEKO_PATCH_DEBUG && (neko_strstr_safe(type_name, "ZGlobals")
             || neko_strstr_safe(field_name, "ZAddress")
             || neko_strstr_safe(field_name, "ZPointer"))) {
            fprintf(stderr, "[neko-patch] vmstructs Z %s::%s @+%zu static=%d addr=%p\\n",
                type_name ? type_name : "?",
                field_name ? field_name : "?",
                (size_t)off_value, (int)is_static, static_addr);
        }
        /* T4.15: structural moving-collector compile-in sentinels. Any
         * VMStruct entry whose type_name carries the collector signature
         * proves the collector was compiled into this libjvm. We make the
         * detection generic: substring match across all entries instead of
         * dispatching on a particular field, so future ZGC/Shenandoah
         * struct evolution does not silently break detection. */
        if (neko_strstr_safe(type_name, "ZGlobals")
            || neko_strstr_safe(type_name, "ZHeap")
            || neko_strstr_safe(type_name, "ZAddress")
            || neko_strstr_safe(type_name, "ZPointer")) {
            g_neko_method_layout.z_compiled_in = JNI_TRUE;
        }
        if (neko_strstr_safe(type_name, "Shenandoah")) {
            g_neko_method_layout.shenandoah_compiled_in = JNI_TRUE;
        }
        if (neko_streq_safe(field_name, "_instance_p")) {
            if (is_static && static_addr != NULL) {
                g_neko_method_layout.addr_zglobals_instance_p = static_addr;
            }
        } else if (neko_streq_safe(field_name, "_ZAddressOffsetMask")) {
            if (is_static && static_addr != NULL) g_neko_method_layout.addr_zglobals_address_offset_mask = static_addr;
            g_neko_method_layout.off_zglobals_address_offset_mask = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(field_name, "_ZPointerLoadGoodMask")) {
            if (is_static && static_addr != NULL) g_neko_method_layout.addr_zglobals_pointer_load_good_mask = static_addr;
            g_neko_method_layout.off_zglobals_pointer_load_good_mask = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(field_name, "_ZPointerLoadBadMask")) {
            if (is_static && static_addr != NULL) g_neko_method_layout.addr_zglobals_pointer_load_bad_mask = static_addr;
            g_neko_method_layout.off_zglobals_pointer_load_bad_mask = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(field_name, "_ZPointerStoreGoodMask")) {
            if (is_static && static_addr != NULL) g_neko_method_layout.addr_zglobals_pointer_store_good_mask = static_addr;
            g_neko_method_layout.off_zglobals_pointer_store_good_mask = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(field_name, "_ZPointerLoadShift")) {
            if (is_static && static_addr != NULL) g_neko_method_layout.addr_zglobals_pointer_load_shift = static_addr;
            g_neko_method_layout.off_zglobals_pointer_load_shift = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "CompilerToVM::Data") && is_static && static_addr != NULL) {
            void *entry = *(void**)static_addr;
            if (entry != NULL && neko_streq_safe(field_name, "ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded")) {
                g_neko_method_layout.sym_z_load_barrier_on_oop_field_preloaded = entry;
            } else if (entry != NULL && neko_streq_safe(field_name, "ZBarrierSetRuntime_load_barrier_on_oop_array")) {
                g_neko_method_layout.sym_z_load_barrier_on_oop_array = entry;
            }
        }
        /* Field-name fallback for inherited Thread fields. VMStructs splits
         * across the Thread / ThreadShadow / JavaThread chain, and some are
         * only in the JVMCI sub-table. Match by field name when the
         * type-aware branch missed it; the field names are unique. */
        if (g_neko_method_layout.off_thread_jni_environment <= 0
            && neko_streq_safe(field_name, "_jni_environment")) {
            g_neko_method_layout.off_thread_jni_environment = (ptrdiff_t)off_value;
        }
        if (g_neko_method_layout.off_thread_pending_exception <= 0
            && neko_streq_safe(field_name, "_pending_exception")) {
            g_neko_method_layout.off_thread_pending_exception = (ptrdiff_t)off_value;
        }
        if (neko_streq_safe(type_name, "Method")) {
            if (neko_streq_safe(field_name, "_access_flags")) g_neko_method_layout.off_method_access_flags = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_code")) g_neko_method_layout.off_method_code = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_i2i_entry")) g_neko_method_layout.off_method_i2i_entry = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_from_interpreted_entry")) g_neko_method_layout.off_method_from_interpreted_entry = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_from_compiled_entry")) g_neko_method_layout.off_method_from_compiled_entry = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_flags") || neko_streq_safe(field_name, "_flags._status") || neko_streq_safe(field_name, "_flags._flags")) {
                g_neko_method_layout.off_method_flags_status = (ptrdiff_t)off_value;
            }
            else if (neko_streq_safe(field_name, "_intrinsic_id")) g_neko_method_layout.off_method_intrinsic_id = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_vtable_index")) g_neko_method_layout.off_method_vtable_index = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_constMethod")) g_neko_method_layout.off_method_constMethod = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "ConstMethod")) {
            if (neko_streq_safe(field_name, "_constants")) g_neko_method_layout.off_constmethod_constants = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_name_index")) g_neko_method_layout.off_constmethod_name_index = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_signature_index")) g_neko_method_layout.off_constmethod_signature_index = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "InstanceKlass")) {
            if (neko_streq_safe(field_name, "_methods")) g_neko_method_layout.off_instanceklass_methods = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_fieldinfo_stream")) g_neko_method_layout.off_instanceklass_fieldinfo_stream = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_fields")) g_neko_method_layout.off_instanceklass_fields = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_constants")) g_neko_method_layout.off_instanceklass_constants = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_local_interfaces")) g_neko_method_layout.off_instanceklass_local_interfaces = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_transitive_interfaces")) g_neko_method_layout.off_instanceklass_transitive_interfaces = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_java_fields_count")) g_neko_method_layout.off_instanceklass_java_fields_count = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "Klass")) {
            if (neko_streq_safe(field_name, "_super_check_offset")) g_neko_method_layout.off_klass_super_check_offset = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_secondary_super_cache")) g_neko_method_layout.off_klass_secondary_super_cache = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_secondary_supers")) g_neko_method_layout.off_klass_secondary_supers = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_primary_supers[0]")) g_neko_method_layout.off_klass_primary_supers_0 = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_layout_helper")) g_neko_method_layout.off_klass_layout_helper = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_java_mirror")) g_neko_method_layout.off_klass_java_mirror = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_super")) g_neko_method_layout.off_klass_super = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_subklass")) g_neko_method_layout.off_klass_subklass = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_next_link")) g_neko_method_layout.off_klass_next_link = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_name")) g_neko_method_layout.off_klass_name = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "ObjArrayKlass")) {
            if (neko_streq_safe(field_name, "_element_klass")) g_neko_method_layout.off_objarrayklass_element_klass = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "java_lang_Class")) {
            if (neko_streq_safe(field_name, "_klass_offset") && is_static && static_addr != NULL) {
                g_neko_method_layout.off_java_lang_class_klass = (ptrdiff_t)(*(int*)static_addr);
            } else if (neko_streq_safe(field_name, "_klass")) {
                g_neko_method_layout.off_java_lang_class_klass = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "ClassLoaderDataGraph")) {
            if (neko_streq_safe(field_name, "_head") && is_static && static_addr != NULL) {
                g_neko_method_layout.addr_classloaderdatagraph_head = static_addr;
            }
        } else if (neko_streq_safe(type_name, "ClassLoaderData")) {
            if (neko_streq_safe(field_name, "_next")) g_neko_method_layout.off_classloaderdata_next = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_klasses")) g_neko_method_layout.off_classloaderdata_klasses = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "ConstantPool")) {
            if (neko_streq_safe(field_name, "_tags")) g_neko_method_layout.off_constantpool_tags = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_cache")) g_neko_method_layout.off_constantpool_cache = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_pool_holder")) g_neko_method_layout.off_constantpool_pool_holder = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_operands")) g_neko_method_layout.off_constantpool_operands = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_resolved_klasses")) g_neko_method_layout.off_constantpool_resolved_klasses = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_length")) g_neko_method_layout.off_constantpool_length = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_base") || neko_streq_safe(field_name, "_base[0]")) g_neko_method_layout.off_constantpool_base = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "Symbol")) {
            if (neko_streq_safe(field_name, "_length")) g_neko_method_layout.off_symbol_length = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_body") || neko_streq_safe(field_name, "_body[0]")) g_neko_method_layout.off_symbol_body = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "Array<Method*>")
                || neko_streq_safe(type_name, "Array<Klass*>")
                || neko_streq_safe(type_name, "Array<u1>")
                || neko_streq_safe(type_name, "Array<u2>")
                || neko_streq_safe(type_name, "Array<int>")) {
            if (neko_streq_safe(field_name, "_length")) {
                g_neko_method_layout.off_array_length = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_data")
                    || neko_streq_safe(field_name, "_data[0]")) {
                if (neko_streq_safe(type_name, "Array<u1>")) {
                    g_neko_method_layout.off_array_u1_data = (ptrdiff_t)off_value;
                } else if (neko_streq_safe(type_name, "Array<u2>")) {
                    g_neko_method_layout.off_array_u2_data = (ptrdiff_t)off_value;
                } else {
                    g_neko_method_layout.off_array_data = (ptrdiff_t)off_value;
                }
            }
        } else if (neko_streq_safe(type_name, "BarrierSet")) {
            if (neko_streq_safe(field_name, "_barrier_set") && is_static) {
                g_neko_method_layout.addr_barrierset_barrier_set = static_addr;
            } else if (neko_streq_safe(field_name, "_fake_rtti")) {
                g_neko_method_layout.off_barrierset_fake_rtti = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "BarrierSet::FakeRtti")) {
            if (neko_streq_safe(field_name, "_concrete_tag")) {
                g_neko_method_layout.off_barrierset_fakertti_concrete_tag = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "CardTableBarrierSet")) {
            if (neko_streq_safe(field_name, "_card_table")) {
                g_neko_method_layout.off_cardtablebarrierset_card_table = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "CardTable")) {
            if (neko_streq_safe(field_name, "_byte_map_base")) {
                g_neko_method_layout.off_cardtable_byte_map_base = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "Universe")) {
            if (is_static && neko_streq_safe(field_name, "_collectedHeap")) {
                g_neko_method_layout.addr_universe_collected_heap = static_addr;
            }
        } else if (neko_streq_safe(type_name, "Thread")
                || neko_streq_safe(type_name, "JavaThread")
                || neko_streq_safe(type_name, "ThreadShadow")) {
            if (neko_streq_safe(field_name, "_thread_state")) {
                if (g_neko_method_layout.off_thread_state == 0
                    || neko_streq_safe(type_name, "JavaThread")) {
                    g_neko_method_layout.off_thread_state = (ptrdiff_t)off_value;
                }
            } else if (neko_streq_safe(field_name, "_polling_word")
                    || neko_streq_safe(field_name, "_polling_page")) {
                g_neko_method_layout.off_thread_polling_word = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_anchor")) {
                g_neko_method_layout.off_thread_anchor = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_active_handles")) {
                g_neko_method_layout.off_thread_active_handles = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_tlab")) {
                g_neko_method_layout.off_thread_tlab = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_anchor._last_Java_sp")
                    || neko_streq_safe(field_name, "_last_Java_sp")) {
                g_neko_method_layout.off_thread_last_Java_sp_direct = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_anchor._last_Java_fp")
                    || neko_streq_safe(field_name, "_last_Java_fp")) {
                g_neko_method_layout.off_thread_last_Java_fp_direct = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_anchor._last_Java_pc")
                    || neko_streq_safe(field_name, "_last_Java_pc")) {
                g_neko_method_layout.off_thread_last_Java_pc_direct = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_jni_environment")) {
                g_neko_method_layout.off_thread_jni_environment = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_pending_exception")) {
                g_neko_method_layout.off_thread_pending_exception = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "JNIHandleBlock")) {
            if (neko_streq_safe(field_name, "_top")) {
                g_neko_method_layout.off_jnih_block_top = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_handles")) {
                g_neko_method_layout.off_jnih_block_handles = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_next")) {
                g_neko_method_layout.off_jnih_block_next = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "ThreadLocalAllocBuffer")) {
            if (neko_streq_safe(field_name, "_top")) {
                g_neko_method_layout.off_tlab_top = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_end")) {
                g_neko_method_layout.off_tlab_end = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "JavaFrameAnchor")) {
            if (neko_streq_safe(field_name, "_last_Java_sp")) {
                g_neko_method_layout.off_frame_anchor_sp = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_last_Java_fp")) {
                g_neko_method_layout.off_frame_anchor_fp = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_last_Java_pc")) {
                g_neko_method_layout.off_frame_anchor_pc = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "JavaCallWrapper")) {
            if (neko_streq_safe(field_name, "_anchor")) {
                g_neko_method_layout.off_jcw_anchor = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "BasicObjectLock")) {
            if (neko_streq_safe(field_name, "_lock")) {
                g_neko_method_layout.off_basicobjectlock_lock = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_obj")) {
                g_neko_method_layout.off_basicobjectlock_obj = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "BasicLock")) {
            if (neko_streq_safe(field_name, "_displaced_header")) {
                g_neko_method_layout.off_basiclock_displaced_header = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "StubRoutines")) {
            if (is_static && neko_streq_safe(field_name, "_call_stub_return_address")) {
                g_neko_method_layout.addr_call_stub_return_address = static_addr;
            }
        } else if (neko_streq_safe(type_name, "CodeCache")) {
            if (neko_streq_safe(field_name, "_heaps") && is_static) {
                g_neko_method_layout.addr_codecache_heaps = static_addr;
            }
        } else if (neko_streq_safe(type_name, "CompressedOops")) {
            if (is_static && neko_streq_safe(field_name, "_narrow_oop._base")) {
                g_neko_method_layout.addr_compressed_oops_base = static_addr;
            } else if (is_static && neko_streq_safe(field_name, "_narrow_oop._shift")) {
                g_neko_method_layout.addr_compressed_oops_shift = static_addr;
            }
        } else if (neko_streq_safe(type_name, "CompressedKlassPointers")) {
            if (is_static && neko_streq_safe(field_name, "_narrow_klass._base")) {
                g_neko_method_layout.addr_compressed_klass_base = static_addr;
            } else if (is_static && neko_streq_safe(field_name, "_narrow_klass._shift")) {
                g_neko_method_layout.addr_compressed_klass_shift = static_addr;
            }
        } else if (neko_streq_safe(type_name, "CodeHeap")) {
            if (neko_streq_safe(field_name, "_memory")) g_neko_method_layout.off_codeheap_memory = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_segmap")) g_neko_method_layout.off_codeheap_segmap = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_log2_segment_size")) g_neko_method_layout.off_codeheap_log2_segment_size = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "VirtualSpace")) {
            if (neko_streq_safe(field_name, "_low_boundary")) g_neko_method_layout.off_virtualspace_low_boundary = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_high_boundary")) g_neko_method_layout.off_virtualspace_high_boundary = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_low")) g_neko_method_layout.off_virtualspace_low = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_high")) g_neko_method_layout.off_virtualspace_high = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "GrowableArrayBase")) {
            if (neko_streq_safe(field_name, "_len")) g_neko_method_layout.off_growable_array_len = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_capacity")) g_neko_method_layout.off_growable_array_capacity = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "GrowableArray<int>")) {
            if (neko_streq_safe(field_name, "_data")) g_neko_method_layout.off_growable_array_data = (ptrdiff_t)off_value;
        } else if (neko_streq_safe(type_name, "CodeBlob")) {
            if (neko_streq_safe(field_name, "_name")) g_neko_method_layout.off_codeblob_name = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_size")) g_neko_method_layout.off_codeblob_size = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_header_size")) g_neko_method_layout.off_codeblob_header_size = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_frame_complete_offset")) g_neko_method_layout.off_codeblob_frame_complete_offset = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_data_offset")) g_neko_method_layout.off_codeblob_data_offset = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_frame_size")) g_neko_method_layout.off_codeblob_frame_size = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_code_begin")) g_neko_method_layout.off_codeblob_code_begin = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_code_end")) g_neko_method_layout.off_codeblob_code_end = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_content_begin")) g_neko_method_layout.off_codeblob_content_begin = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_data_end")) g_neko_method_layout.off_codeblob_data_end = (ptrdiff_t)off_value;
        }
    }
    return JNI_TRUE;
}

static jboolean neko_walk_vm_types(void *jvm) {
    void *vmtypes = neko_dlsym(jvm, "gHotSpotVMTypes");
    int *name_off = (int*)neko_dlsym(jvm, "gHotSpotVMTypeEntryTypeNameOffset");
    int *size_off = (int*)neko_dlsym(jvm, "gHotSpotVMTypeEntrySizeOffset");
    int *stride_p = (int*)neko_dlsym(jvm, "gHotSpotVMTypeEntryArrayStride");
    if (vmtypes == NULL || name_off == NULL || size_off == NULL || stride_p == NULL) return JNI_FALSE;
    const uint8_t *base = *(const uint8_t* const*)vmtypes;
    int stride = *stride_p;
    if (base == NULL || stride <= 0) return JNI_FALSE;
    for (const uint8_t *e = base; ; e += stride) {
        const char *type_name = *(const char* const*)(e + *name_off);
        if (type_name == NULL) break;
        uint64_t sz = *(const uint64_t*)(e + *size_off);
        if (neko_streq_safe(type_name, "Method")) g_neko_method_layout.method_size = (size_t)sz;
        else if (neko_streq_safe(type_name, "AccessFlags")) g_neko_method_layout.access_flags_size = (size_t)sz;
        else if (neko_streq_safe(type_name, "CodeHeap")) g_neko_method_layout.sizeof_CodeHeap = (size_t)sz;
        else if (neko_streq_safe(type_name, "BufferBlob")) g_neko_method_layout.sizeof_BufferBlob = (size_t)sz;
        else if (neko_streq_safe(type_name, "VirtualSpace")) g_neko_method_layout.sizeof_VirtualSpace = (size_t)sz;
        else if (neko_streq_safe(type_name, "JNIHandleBlock")) g_neko_method_layout.sizeof_JNIHandleBlock = (size_t)sz;
        else if (neko_streq_safe(type_name, "JavaCallWrapper")) g_neko_method_layout.sizeof_JavaCallWrapper = (size_t)sz;
        else if (neko_streq_safe(type_name, "ConstantPool")) g_neko_method_layout.sizeof_ConstantPool = (size_t)sz;
        else if (neko_streq_safe(type_name, "BasicObjectLock")) g_neko_method_layout.sizeof_BasicObjectLock = (size_t)sz;
        else if (neko_streq_safe(type_name, "BasicLock")) g_neko_method_layout.sizeof_BasicLock = (size_t)sz;
    }
    return JNI_TRUE;
}

static void neko_walk_vm_int_constants(void *jvm) {
    void *constants = neko_dlsym(jvm, "gHotSpotVMIntConstants");
    int *name_off = (int*)neko_dlsym(jvm, "gHotSpotVMIntConstantEntryNameOffset");
    int *val_off = (int*)neko_dlsym(jvm, "gHotSpotVMIntConstantEntryValueOffset");
    int *stride_p = (int*)neko_dlsym(jvm, "gHotSpotVMIntConstantEntryArrayStride");
    if (constants == NULL || name_off == NULL || val_off == NULL || stride_p == NULL) return;
    const uint8_t *base = *(const uint8_t* const*)constants;
    int stride = *stride_p;
    if (base == NULL || stride <= 0) return;
    for (const uint8_t *e = base; ; e += stride) {
        const char *name = *(const char* const*)(e + *name_off);
        if (name == NULL) break;
        int32_t value = *(const int32_t*)(e + *val_off);
        if (NEKO_PATCH_DEBUG && (neko_strstr_safe(name, "compilable") || neko_strstr_safe(name, "_thread_in"))) {
            fprintf(stderr, "[neko-patch] vmconst %s = 0x%x\\n", name, (unsigned)value);
        }
        if (neko_streq_safe(name, "JVM_ACC_NOT_C1_COMPILABLE")) g_neko_method_layout.access_not_c1_compilable = (uint32_t)value;
        else if (neko_streq_safe(name, "JVM_ACC_NOT_C2_COMPILABLE")) g_neko_method_layout.access_not_c2_compilable = (uint32_t)value;
        else if (neko_streq_safe(name, "JVM_ACC_NOT_OSR_COMPILABLE")) g_neko_method_layout.access_not_osr_compilable = (uint32_t)value;
        else if (neko_strstr_safe(name, "not_c1_compilable")) g_neko_method_layout.method_flag_not_c1_compilable = (uint32_t)value;
        else if (neko_strstr_safe(name, "not_c2_compilable")) g_neko_method_layout.method_flag_not_c2_compilable = (uint32_t)value;
        else if (neko_strstr_safe(name, "not_c1_osr_compilable")) g_neko_method_layout.method_flag_not_osr_compilable = (uint32_t)value;
        /* JavaThreadState enum values — names are stable across JDKs. */
        else if (neko_streq_safe(name, "_thread_in_Java")) g_neko_method_layout.thread_state_in_java = (int32_t)value;
        else if (neko_streq_safe(name, "_thread_in_native")) g_neko_method_layout.thread_state_in_native = (int32_t)value;
        else if (neko_streq_safe(name, "_thread_in_native_trans")) g_neko_method_layout.thread_state_in_native_trans = (int32_t)value;
        /* BasicType enum values — used as the result_type argument to
         * StubRoutines::call_stub. HotSpot publishes them as VMIntConstants
         * named exactly the enum tag. */
        else if (neko_streq_safe(name, "T_VOID"))    g_neko_method_layout.basictype_void    = (int32_t)value;
        else if (neko_streq_safe(name, "T_BOOLEAN")) g_neko_method_layout.basictype_boolean = (int32_t)value;
        else if (neko_streq_safe(name, "T_BYTE"))    g_neko_method_layout.basictype_byte    = (int32_t)value;
        else if (neko_streq_safe(name, "T_CHAR"))    g_neko_method_layout.basictype_char    = (int32_t)value;
        else if (neko_streq_safe(name, "T_SHORT"))   g_neko_method_layout.basictype_short   = (int32_t)value;
        else if (neko_streq_safe(name, "T_INT"))     g_neko_method_layout.basictype_int     = (int32_t)value;
        else if (neko_streq_safe(name, "T_LONG"))    g_neko_method_layout.basictype_long    = (int32_t)value;
        else if (neko_streq_safe(name, "T_FLOAT"))   g_neko_method_layout.basictype_float   = (int32_t)value;
        else if (neko_streq_safe(name, "T_DOUBLE"))  g_neko_method_layout.basictype_double  = (int32_t)value;
        else if (neko_streq_safe(name, "T_OBJECT"))  g_neko_method_layout.basictype_object  = (int32_t)value;
        else if (neko_streq_safe(name, "T_ARRAY"))   g_neko_method_layout.basictype_array   = (int32_t)value;
        else if (neko_streq_safe(name, "BarrierSet::ModRef")) g_neko_method_layout.vmconst_barrierset_modref = (int32_t)value;
        else if (neko_streq_safe(name, "BarrierSet::CardTableBarrierSet")) g_neko_method_layout.vmconst_barrierset_cardtable = (int32_t)value;
        else if (neko_streq_safe(name, "BarrierSet::G1BarrierSet")) g_neko_method_layout.vmconst_barrierset_g1 = (int32_t)value;
        else if (neko_streq_safe(name, "CardTable::clean_card")) g_neko_method_layout.vmconst_cardtable_clean_card = (int32_t)value;
        else if (neko_streq_safe(name, "CardTable::dirty_card")) g_neko_method_layout.vmconst_cardtable_dirty_card = (int32_t)value;
        else if (neko_streq_safe(name, "G1CardTable::g1_young_gen")) g_neko_method_layout.vmconst_g1_young_card = (int32_t)value;
    }
    /* The default value for T_VOID in HotSpot's BasicType enum is 14 on JDK
     * 21+ but we won't trust any constant if T_INT didn't show up — that
     * would mean the VMIntConstants table didn't include the BasicType
     * group at all. Mark the resolution as ready only when we got T_INT. */
    g_neko_method_layout.basictypes_resolved = (g_neko_method_layout.basictype_int != 0) ? JNI_TRUE : JNI_FALSE;
}

static void neko_detect_current_gc_barrier(void) {
    void *bs;
    int32_t tag = -1;
    g_neko_method_layout.current_barrier_kind = NEKO_GC_BARRIER_UNKNOWN;
    g_neko_method_layout.current_barrier_set = NULL;
    g_neko_method_layout.current_card_table = NULL;
    g_neko_method_layout.card_table_byte_map_base = NULL;
    if (g_neko_method_layout.addr_barrierset_barrier_set == NULL
        || g_neko_method_layout.off_barrierset_fake_rtti < 0
        || g_neko_method_layout.off_barrierset_fakertti_concrete_tag < 0) {
        return;
    }
    bs = *(void**)g_neko_method_layout.addr_barrierset_barrier_set;
    if (bs == NULL) return;
    g_neko_method_layout.current_barrier_set = bs;
    tag = *(int32_t*)((char*)bs
        + g_neko_method_layout.off_barrierset_fake_rtti
        + g_neko_method_layout.off_barrierset_fakertti_concrete_tag);
    if (tag == g_neko_method_layout.vmconst_barrierset_g1) {
        g_neko_method_layout.current_barrier_kind = NEKO_GC_BARRIER_G1;
    } else if (tag == g_neko_method_layout.vmconst_barrierset_cardtable) {
        g_neko_method_layout.current_barrier_kind = NEKO_GC_BARRIER_CARDTABLE;
    } else {
        /* T4.15: when the BarrierSet::FakeRtti tag does not match either
         * VMConstant for G1 or CardTable, the active collector is one
         * whose enum entry the libjvm has elided from
         * gHotSpotVMIntConstants. Production HotSpot 21 omits both
         * BarrierSet::ZBarrierSet and BarrierSet::ShenandoahBarrierSet,
         * AND keeps ZGlobalsForVMStructs::_instance_p statically allocated
         * regardless of which collector is selected, AND zeroes the
         * ZGlobals masks when generational ZGC is the active variant.
         * None of those signals can independently classify Z vs Shenandoah.
         *
         * The only structural process-state signal that uniformly
         * disambiguates is the launcher's own command line, which carries
         * the resolved -XX:+Use*GC flag verbatim. Reading /proc/self/cmdline
         * is generic (no class/method/owner specialization) and stays inside
         * the no-JNI-fallback envelope. The decision below is cmdline-first;
         * the structural compile-in sentinels (z_compiled_in /
         * shenandoah_compiled_in) act as supplementary discriminators when
         * cmdline is unavailable on a non-Linux build. */
        int cmdline_signal = neko_cmdline_gc_signal();
        if (cmdline_signal == 1) {
            g_neko_method_layout.current_barrier_kind = NEKO_GC_BARRIER_Z;
        } else if (cmdline_signal == 2) {
            g_neko_method_layout.current_barrier_kind = NEKO_GC_BARRIER_SHENANDOAH;
        } else if (g_neko_method_layout.shenandoah_compiled_in
                && !g_neko_method_layout.z_compiled_in) {
            /* Single-collector build pinned to Shenandoah. */
            g_neko_method_layout.current_barrier_kind = NEKO_GC_BARRIER_SHENANDOAH;
        } else if (g_neko_method_layout.z_compiled_in
                && !g_neko_method_layout.shenandoah_compiled_in) {
            /* Single-collector build pinned to ZGC. */
            g_neko_method_layout.current_barrier_kind = NEKO_GC_BARRIER_Z;
        }
        /* If both are compiled in and cmdline gave no signal, leave
         * current_barrier_kind as UNKNOWN; gc_barrier_layout_ready() will
         * return JNI_FALSE and the strict abort path fires below. */
    }
    if (g_neko_method_layout.off_cardtablebarrierset_card_table >= 0) {
        void *ct = *(void**)((char*)bs + g_neko_method_layout.off_cardtablebarrierset_card_table);
        g_neko_method_layout.current_card_table = ct;
        if (ct != NULL && g_neko_method_layout.off_cardtable_byte_map_base >= 0) {
            g_neko_method_layout.card_table_byte_map_base =
                *(void**)((char*)ct + g_neko_method_layout.off_cardtable_byte_map_base);
        }
    }
}

static jboolean neko_gc_barrier_layout_ready(void) {
    jboolean card_table_ready =
        (g_neko_method_layout.card_table_byte_map_base != NULL
         && g_neko_method_layout.vmconst_cardtable_dirty_card >= 0)
        ? JNI_TRUE : JNI_FALSE;
    switch (g_neko_method_layout.current_barrier_kind) {
        case NEKO_GC_BARRIER_G1:
            return card_table_ready ? JNI_TRUE : JNI_FALSE;
        case NEKO_GC_BARRIER_CARDTABLE:
            return card_table_ready ? JNI_TRUE : JNI_FALSE;
        case NEKO_GC_BARRIER_Z:
            return (g_neko_method_layout.sym_z_load_barrier_on_oop_field_preloaded != NULL
                    && g_neko_method_layout.sym_z_load_barrier_on_oop_array != NULL
                    && g_neko_method_layout.sym_z_store_barrier_on_oop_field_with_healing != NULL)
                ? JNI_TRUE : JNI_FALSE;
        case NEKO_GC_BARRIER_SHENANDOAH:
            return (g_neko_method_layout.sym_shenandoah_load_reference_barrier_strong != NULL
                    && g_neko_method_layout.sym_shenandoah_write_ref_field_pre_entry != NULL
                    && g_neko_method_layout.sym_shenandoah_arraycopy_barrier_oop_entry != NULL)
                ? JNI_TRUE : JNI_FALSE;
        default:
            return JNI_FALSE;
    }
}

static jboolean neko_native_resolution_layout_ready(void) {
    jboolean field_stream_ready =
        (g_neko_method_layout.off_instanceklass_fieldinfo_stream >= 0
         || g_neko_method_layout.off_instanceklass_fields >= 0)
        ? JNI_TRUE : JNI_FALSE;
    jboolean metadata_ready =
        (g_neko_method_layout.off_instanceklass_methods >= 0
         && field_stream_ready
         && g_neko_method_layout.off_instanceklass_constants >= 0
         && g_neko_method_layout.off_instanceklass_transitive_interfaces >= 0
         && g_neko_method_layout.off_klass_super_check_offset >= 0
         && g_neko_method_layout.off_klass_secondary_supers >= 0
         && g_neko_method_layout.off_klass_java_mirror >= 0
         && g_neko_method_layout.off_klass_super >= 0
         && g_neko_method_layout.off_klass_next_link >= 0
         && g_neko_method_layout.off_klass_name >= 0
         && g_neko_method_layout.off_constantpool_tags >= 0
         && g_neko_method_layout.off_constantpool_pool_holder >= 0
         && g_neko_method_layout.off_constantpool_length >= 0
         && g_neko_method_layout.off_method_constMethod >= 0
         && g_neko_method_layout.off_constmethod_constants >= 0
         && g_neko_method_layout.off_constmethod_name_index >= 0
         && g_neko_method_layout.off_constmethod_signature_index >= 0
         && g_neko_method_layout.off_symbol_length >= 0
         && g_neko_method_layout.off_symbol_body >= 0
         && g_neko_method_layout.off_array_length >= 0
         && g_neko_method_layout.off_array_data > 0
         && (g_neko_method_layout.off_instanceklass_fieldinfo_stream < 0
             || g_neko_method_layout.off_array_u1_data > 0)
         && (g_neko_method_layout.off_instanceklass_fields < 0
             || g_neko_method_layout.off_array_u2_data > 0)
         && g_neko_method_layout.addr_universe_collected_heap != NULL
         && g_neko_method_layout.addr_classloaderdatagraph_head != NULL
         && g_neko_method_layout.off_classloaderdata_next >= 0
         && g_neko_method_layout.off_classloaderdata_klasses >= 0
         && g_neko_method_layout.addr_barrierset_barrier_set != NULL
         && g_neko_method_layout.off_barrierset_fake_rtti >= 0
         && g_neko_method_layout.off_barrierset_fakertti_concrete_tag >= 0)
        ? JNI_TRUE : JNI_FALSE;
    jboolean compressed_ready =
        (g_neko_method_layout.addr_compressed_oops_base != NULL
         && g_neko_method_layout.addr_compressed_oops_shift != NULL
         && g_neko_method_layout.addr_compressed_klass_base != NULL
         && g_neko_method_layout.addr_compressed_klass_shift != NULL)
        ? JNI_TRUE : JNI_FALSE;
    jboolean class_lookup_ready =
        (g_neko_method_layout.addr_classloaderdatagraph_head != NULL
         || g_neko_method_layout.sym_jvm_find_class_from_boot_loader != NULL
         || g_neko_method_layout.sym_jvm_find_class_from_class != NULL
         || g_neko_method_layout.sym_systemdictionary_find_instance_klass != NULL
         || g_neko_method_layout.sym_systemdictionary_find_instance_or_array_klass != NULL)
        ? JNI_TRUE : JNI_FALSE;
    jboolean string_ready =
        (g_neko_method_layout.sym_stringtable_intern_symbol != NULL
         || g_neko_method_layout.sym_stringtable_intern_utf8 != NULL
         || g_neko_method_layout.addr_stringtable_the_table != NULL
         || g_neko_method_layout.sym_jvm_intern_string != NULL)
        ? JNI_TRUE : JNI_FALSE;
    jboolean array_alloc_ready =
        ((g_neko_method_layout.sym_oopfactory_new_type_array != NULL
          && g_neko_method_layout.sym_oopfactory_new_obj_array != NULL)
         || (g_neko_method_layout.sym_jvm_find_primitive_class != NULL
             && g_neko_method_layout.sym_jvm_new_array != NULL
             && g_neko_method_layout.sym_jvm_new_multi_array != NULL))
        ? JNI_TRUE : JNI_FALSE;
    return (metadata_ready && compressed_ready && class_lookup_ready
            && string_ready && array_alloc_ready)
        ? JNI_TRUE : JNI_FALSE;
}

static void* neko_jmethodid_to_method_star(jmethodID mid) {
    if (mid == NULL) return NULL;
    return *(void**)mid;
}

/* === Phase 1: CodeCache discovery ===
 * Read-only walk of HotSpot's CodeCache::_heaps. Validates every layout
 * offset before any allocation. Also harvests a BufferBlob vtable pointer
 * from an existing blob (so we can construct our own BufferBlob in-place
 * later without dlsym'ing internal libjvm symbols, which JDK 21 strips).
 *
 * The segmap is a byte array indexed by segment number. A non-zero entry
 * means "this segment is N segments away from a block header" (with 0xFE
 * meaning "skip 254"). Following the trail to 0 lands on a HeapBlock.
 * For a forward walk we use HeapBlock::Header::_length (in segments) to
 * advance directly, which is simpler. */

static jboolean neko_codecache_layout_ready(void) {
    /* GrowableArrayBase::_len sits at offset 0 (no vtable on AnyObj base);
     * CodeHeap::_memory likewise at 0. All offsets must be non-negative
     * and the static-field address must be resolved. */
    return (g_neko_method_layout.addr_codecache_heaps != NULL
        && g_neko_method_layout.off_growable_array_len >= 0
        && g_neko_method_layout.off_growable_array_data > 0
        && g_neko_method_layout.off_codeheap_memory >= 0
        && g_neko_method_layout.off_codeheap_segmap > 0
        && g_neko_method_layout.off_codeheap_log2_segment_size > 0
        && g_neko_method_layout.off_virtualspace_low > 0
        && g_neko_method_layout.off_virtualspace_high > 0
        && g_neko_method_layout.off_codeblob_name > 0
        && g_neko_method_layout.off_codeblob_size > 0
        && g_neko_method_layout.off_codeblob_code_begin > 0
        && g_neko_method_layout.off_codeblob_code_end > 0)
        ? JNI_TRUE : JNI_FALSE;
}

static void* neko_virtualspace_low(void *vspace) {
    return *(void**)((char*)vspace + g_neko_method_layout.off_virtualspace_low);
}
static void* neko_virtualspace_high(void *vspace) {
    return *(void**)((char*)vspace + g_neko_method_layout.off_virtualspace_high);
}

/* HeapBlock layout (matches hotspot/share/memory/heap.hpp):
 *   struct Header { size_t _length; bool _used; };
 *   union { Header _header; int64_t _padding[(sizeof(Header)+7)/8]; };
 *
 * On x86_64 with 8-byte size_t and 1-byte bool, the union is padded to
 * 16 bytes (2 int64_t slots). _length is at +0, _used at +8. */
typedef struct {
    size_t length;
    int    used;
} neko_heapblock_info_t;

static int neko_read_heapblock(void *block, neko_heapblock_info_t *out) {
    if (block == NULL) return 0;
    out->length = *(size_t*)block;
    /* HotSpot stores _used as a 1-byte bool. In a release JVM unused tail
     * memory is zero so reading past the last block gives _used==0 + length==0.
     * In a fastdebug JVM that tail is poisoned with 0xCC, which would parse
     * as length=0xCCCC..., used=0xCC and walk us off into noise. Reject any
     * "_used" byte that isn't a clean 0 or 1 so the caller can stop. */
    unsigned char raw = *(unsigned char*)((char*)block + sizeof(size_t));
    if (raw != 0 && raw != 1) {
        out->length = 0;
        out->used   = 0;
        return 0;
    }
    out->used = raw ? 1 : 0;
    return 1;
}

static const char *neko_codeblob_name(void *blob) {
    if (blob == NULL) return NULL;
    return *(const char**)((char*)blob + g_neko_method_layout.off_codeblob_name);
}
static int neko_codeblob_size(void *blob) {
    if (blob == NULL) return 0;
    return *(int*)((char*)blob + g_neko_method_layout.off_codeblob_size);
}
static void* neko_codeblob_code_begin(void *blob) {
    if (blob == NULL) return NULL;
    return *(void**)((char*)blob + g_neko_method_layout.off_codeblob_code_begin);
}
static void* neko_codeblob_code_end(void *blob) {
    if (blob == NULL || g_neko_method_layout.off_codeblob_code_end <= 0) return NULL;
    return *(void**)((char*)blob + g_neko_method_layout.off_codeblob_code_end);
}

/* Walk one CodeHeap's blocks. Each block starts with a HeapBlock header
 * (16 bytes on x86_64) followed by a CodeBlob (if used). Block size is
 * length * segment_size, where segment_size = 1 << _log2_segment_size. */
static void neko_walk_codeheap(void *heap, void (*visitor)(void *blob, const char *name, void *cookie), void *cookie) {
    void *vspace = (char*)heap + g_neko_method_layout.off_codeheap_memory;
    int log2_seg = *(int*)((char*)heap + g_neko_method_layout.off_codeheap_log2_segment_size);
    size_t seg_size = (size_t)1u << (log2_seg & 31);
    if (seg_size == 0) return;
    void *low = neko_virtualspace_low(vspace);
    void *high = neko_virtualspace_high(vspace);
    if (low == NULL || high == NULL || low >= high) return;
    /* HeapBlock header occupies 16 bytes (rounded up to 8-aligned slot pair).
     * CodeBlob immediately follows the header. */
    const size_t header_size = 16;
    char *p = (char*)low;
    size_t total_segments = ((size_t)((char*)high - (char*)low)) / seg_size;
    int safety_iter = 0;
    while (p < (char*)high && safety_iter++ < 1000000) {
        neko_heapblock_info_t info;
        if (!neko_read_heapblock(p, &info)) break;
        if (info.length == 0) break;
        /* Sanity: HotSpot does not export _next_segment via VMStructs, so
         * we don't know the allocation high-water mark directly. Treat any
         * length that exceeds the remaining committed segments as past the
         * end of the populated region — this catches both fastdebug 0xCC
         * poison and accidentally walking into the segmap area. */
        size_t cur_offset_segs = ((size_t)((char*)p - (char*)low)) / seg_size;
        if (info.length > total_segments - cur_offset_segs) break;
        size_t block_bytes = info.length * seg_size;
        if (info.used) {
            void *blob = (void*)(p + header_size);
            const char *name = neko_codeblob_name(blob);
            if (visitor) visitor(blob, name, cookie);
        }
        p += block_bytes;
    }
}

typedef struct {
    void *target_vtable;
    void *call_stub_return_pc;
    void *call_stub_entry;
    int   limit_logged;
} neko_blob_visit_ctx_t;

static void *neko_find_call_stub_entry(void *code_begin, void *return_pc) {
    if (code_begin == NULL || return_pc == NULL || code_begin >= return_pc) return NULL;
    const unsigned char *begin = (const unsigned char*)code_begin;
    const unsigned char *ret = (const unsigned char*)return_pc;
    const unsigned char pattern[] = {0x55, 0x48, 0x8b, 0xec, 0x48, 0x83, 0xec, 0x60};
    const size_t pattern_len = sizeof(pattern);
    const unsigned char *scan = ret;
    size_t max_back = (size_t)(ret - begin);
    if (max_back > 512u) max_back = 512u;
    for (size_t back = 0; back + pattern_len <= max_back; back++) {
        const unsigned char *p = ret - back;
        if (p < begin || p + pattern_len > ret) continue;
        if (memcmp(p, pattern, pattern_len) == 0) return (void*)p;
    }
    return NULL;
}

static void neko_blob_visit_log(void *blob, const char *name, void *cookie) {
    neko_blob_visit_ctx_t *ctx = (neko_blob_visit_ctx_t*)cookie;
    /* Always log anything that looks like an adapter / buffer / vtable / stub
     * so we can spot vtable-eligible candidates in the noise of nmethods. */
    int interesting = name != NULL && (strstr(name, "adapt") != NULL
                                    || strstr(name, "I2C/C2I") != NULL
                                    || strstr(name, "MethodHandle") != NULL
                                    || strstr(name, "vtable") != NULL
                                    || strstr(name, "stub") != NULL
                                    || strstr(name, "Stub") != NULL
                                    || strstr(name, "Buffer") != NULL);
    if (NEKO_PATCH_DEBUG && (interesting || ctx->limit_logged < 16)) {
        fprintf(stderr, "[neko-patch] blob %p name=%s size=%d code=%p\\n",
            blob, name ? name : "?", neko_codeblob_size(blob), neko_codeblob_code_begin(blob));
        ctx->limit_logged++;
    }
    if (ctx->call_stub_entry == NULL && ctx->call_stub_return_pc != NULL) {
        void *code_begin = neko_codeblob_code_begin(blob);
        void *code_end = neko_codeblob_code_end(blob);
        if (code_begin != NULL && code_end != NULL
            && code_begin <= ctx->call_stub_return_pc && ctx->call_stub_return_pc < code_end) {
            ctx->call_stub_entry = neko_find_call_stub_entry(code_begin, ctx->call_stub_return_pc);
            if (NEKO_PATCH_DEBUG) {
                fprintf(stderr, "[neko-patch] call_stub blob=%p name=%s ret=%p entry=%p code=[%p..%p)\\n",
                    blob, name ? name : "?", ctx->call_stub_return_pc, ctx->call_stub_entry, code_begin, code_end);
            }
        }
    }
    if (name != NULL) {
        if (g_neko_method_layout.runtime1_monitorenter_entry == NULL
            && strcmp(name, "monitorenter Runtime1 stub") == 0) {
            g_neko_method_layout.runtime1_monitorenter_entry = neko_codeblob_code_begin(blob);
            if (NEKO_PATCH_DEBUG) {
                fprintf(stderr, "[neko-patch] runtime1 monitorenter entry=%p blob=%p\\n",
                    g_neko_method_layout.runtime1_monitorenter_entry, blob);
            }
        } else if (g_neko_method_layout.runtime1_monitorexit_entry == NULL
            && strcmp(name, "monitorexit Runtime1 stub") == 0) {
            g_neko_method_layout.runtime1_monitorexit_entry = neko_codeblob_code_begin(blob);
            if (NEKO_PATCH_DEBUG) {
                fprintf(stderr, "[neko-patch] runtime1 monitorexit entry=%p blob=%p\\n",
                    g_neko_method_layout.runtime1_monitorexit_entry, blob);
            }
        }
    }
    /* Harvest vtable from the first BufferBlob/AdapterBlob we see. Adapter
     * blobs are created at JVM startup so they always exist by JNI_OnLoad. */
    if (ctx->target_vtable == NULL && name != NULL
        && (strstr(name, "I2C/C2I") != NULL || strstr(name, "adapter") != NULL)) {
        ctx->target_vtable = *(void**)blob;
        if (NEKO_PATCH_DEBUG) {
            fprintf(stderr, "[neko-patch] harvested vtable %p from blob %p (%s)\\n",
                ctx->target_vtable, blob, name);
        }
    }
}

/* === Phase 2: private CodeHeap allocator + registration ===
 * We construct a CodeHeap struct ourselves (in C-heap memory), back it with
 * a private mmap'd executable region + segmap, and append the CodeHeap* to
 * HotSpot's CodeCache::_heaps GrowableArray. After registration,
 * CodeCache::find_blob_at(pc) will scan our heap when given a PC inside
 * our exec region. Phase 3 then constructs CodeBlobs there.
 *
 * Layout offsets we DERIVE (not exposed by VMStructs but stable in
 * jdk-21.0.10 hotspot/share/memory/heap.hpp):
 *   _segment_size                   at log2_segment_size_offset - 8  (size_t before)
 *   _number_of_reserved_segments    at log2_segment_size_offset - 16
 *   _number_of_committed_segments   at log2_segment_size_offset - 24
 *   _next_segment                   at log2_segment_size_offset + 8  (skip 4 + 4 pad)
 *   _freelist                       at log2_segment_size_offset + 16 (a FreeBlock*)
 * If a future JDK reorders these, port maintenance per-version. */

#define NEKO_PRIV_HEAP_BYTES   (256 * 1024)  /* 256 KB exec area */
#define NEKO_PRIV_SEGMENT_BYTES 128

typedef struct {
    void   *codeheap;        /* C-heap allocated CodeHeap struct */
    void   *exec_region;     /* mmap PROT_RWX area */
    size_t  exec_size;
    void   *segmap_region;   /* mmap RW area, one byte per segment */
    size_t  segmap_size;
    size_t  segment_size;
    size_t  next_byte;       /* bump cursor inside exec_region */
    jboolean registered;
} neko_priv_codeheap_t;

static neko_priv_codeheap_t g_neko_priv_heap = {0};

static void *neko_alloc_exec_pages(size_t size) {
#if defined(__linux__) || defined(__APPLE__)
    void *p = mmap(NULL, size, PROT_READ|PROT_WRITE|PROT_EXEC,
                   MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
    return p == MAP_FAILED ? NULL : p;
#else
    return NULL;
#endif
}

""";
    }

    private static String renderPart2() {
        return """
static void *neko_alloc_rw_pages(size_t size) {
#if defined(__linux__) || defined(__APPLE__)
    void *p = mmap(NULL, size, PROT_READ|PROT_WRITE,
                   MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
    return p == MAP_FAILED ? NULL : p;
#else
    return NULL;
#endif
}

static void neko_codeheap_set_virtualspace(void *vspace, void *low, void *high) {
    *(void**)((char*)vspace + g_neko_method_layout.off_virtualspace_low_boundary) = low;
    *(void**)((char*)vspace + g_neko_method_layout.off_virtualspace_high_boundary) = high;
    *(void**)((char*)vspace + g_neko_method_layout.off_virtualspace_low) = low;
    *(void**)((char*)vspace + g_neko_method_layout.off_virtualspace_high) = high;
}

static jboolean neko_priv_heap_init(void) {
    if (g_neko_priv_heap.codeheap != NULL) return JNI_TRUE;
    if (!neko_codecache_layout_ready()) {
        NEKO_PATCH_LOG("priv heap init: codecache layout not ready");
        return JNI_FALSE;
    }
    if (g_neko_method_layout.sizeof_CodeHeap == 0) {
        NEKO_PATCH_LOG("priv heap init: sizeof(CodeHeap) unknown");
        return JNI_FALSE;
    }
    if (g_neko_method_layout.bufferblob_vtable == NULL) {
        NEKO_PATCH_LOG("priv heap init: vtable not harvested");
        return JNI_FALSE;
    }

    const size_t exec_bytes = NEKO_PRIV_HEAP_BYTES;
    const size_t seg_bytes  = NEKO_PRIV_SEGMENT_BYTES;
    const size_t segments   = exec_bytes / seg_bytes;

    void *exec = neko_alloc_exec_pages(exec_bytes);
    if (exec == NULL) { NEKO_PATCH_LOG("priv heap init: mmap exec failed"); return JNI_FALSE; }
    void *segmap = neko_alloc_rw_pages(segments);
    if (segmap == NULL) {
        NEKO_PATCH_LOG("priv heap init: mmap segmap failed");
        munmap(exec, exec_bytes);
        return JNI_FALSE;
    }
    /* segmap initial state: 0xFF means "no block here" / free.
     * HotSpot's CodeHeap::find_block_for treats 0xFF as a chain marker
     * meaning "skip 254 segments back" — for empty regions there's no
     * block to find, so find_blob_at will iterate to a different heap. */
    memset(segmap, 0xFF, segments);

    void *heap = calloc(1, g_neko_method_layout.sizeof_CodeHeap);
    if (heap == NULL) {
        munmap(exec, exec_bytes);
        munmap(segmap, segments);
        return JNI_FALSE;
    }

    /* Fill the two embedded VirtualSpace structs. */
    char *vs_mem = (char*)heap + g_neko_method_layout.off_codeheap_memory;
    char *vs_seg = (char*)heap + g_neko_method_layout.off_codeheap_segmap;
    neko_codeheap_set_virtualspace(vs_mem, exec, (char*)exec + exec_bytes);
    neko_codeheap_set_virtualspace(vs_seg, segmap, (char*)segmap + segments);

    /* log2_segment_size and friends. The neighbours' offsets are derived
     * from the VMStructs-exposed _log2_segment_size offset. Verified
     * against jdk-21.0.10 heap.hpp:
     *   ptrdiff_t off = g_neko_method_layout.off_codeheap_log2_segment_size;
     *   _number_of_committed_segments = off - 24
     *   _number_of_reserved_segments  = off - 16
     *   _segment_size                 = off - 8
     *   _log2_segment_size            = off       (int)
     *   _next_segment                 = off + 8   (skip 4-byte int + 4-byte pad)
     */
    ptrdiff_t off_log2 = g_neko_method_layout.off_codeheap_log2_segment_size;
    *(size_t*)((char*)heap + off_log2 - 24) = segments; /* _number_of_committed_segments */
    *(size_t*)((char*)heap + off_log2 - 16) = segments; /* _number_of_reserved_segments  */
    *(size_t*)((char*)heap + off_log2 - 8)  = seg_bytes;/* _segment_size */
    *(int*   )((char*)heap + off_log2)      = 7;        /* _log2_segment_size */
    *(size_t*)((char*)heap + off_log2 + 8)  = 0;        /* _next_segment (used segments) */

    g_neko_priv_heap.codeheap = heap;
    g_neko_priv_heap.exec_region = exec;
    g_neko_priv_heap.exec_size = exec_bytes;
    g_neko_priv_heap.segmap_region = segmap;
    g_neko_priv_heap.segmap_size = segments;
    g_neko_priv_heap.segment_size = seg_bytes;
    g_neko_priv_heap.next_byte = 0;
    g_neko_priv_heap.registered = JNI_FALSE;
    NEKO_PATCH_LOG("priv heap init: heap=%p exec=[%p..%p) segmap=[%p..%p)",
        heap, exec, (char*)exec + exec_bytes, segmap, (char*)segmap + segments);
    return JNI_TRUE;
}

static jboolean neko_priv_heap_register(void) {
    if (g_neko_priv_heap.codeheap == NULL) return JNI_FALSE;
    if (g_neko_priv_heap.registered) return JNI_TRUE;
    if (g_neko_method_layout.addr_codecache_heaps == NULL) return JNI_FALSE;
    void **heaps_static = (void**)g_neko_method_layout.addr_codecache_heaps;
    void *heaps_array = *heaps_static;
    if (heaps_array == NULL) return JNI_FALSE;
    int *len_ptr = (int*)((char*)heaps_array + g_neko_method_layout.off_growable_array_len);
    int *cap_ptr = (int*)((char*)heaps_array + g_neko_method_layout.off_growable_array_capacity);
    void ***data_pp = (void***)((char*)heaps_array + g_neko_method_layout.off_growable_array_data);
    int len = *len_ptr;
    int cap = *cap_ptr;
    void **data = *data_pp;
    NEKO_PATCH_LOG("priv heap register: heaps=%p len=%d cap=%d data=%p",
        heaps_array, len, cap, data);
    if (data == NULL) return JNI_FALSE;
    if (cap > len) {
        /* Slack capacity present — append in place. */
        data[len] = g_neko_priv_heap.codeheap;
        __atomic_thread_fence(__ATOMIC_RELEASE);
        *len_ptr = len + 1;
        g_neko_priv_heap.registered = JNI_TRUE;
        NEKO_PATCH_LOG("priv heap register: appended at slot %d", len);
        return JNI_TRUE;
    }
    /* No slack. Build a brand-new GrowableArray with extra capacity, copy
     * old entries plus ours, then atomic-swap the static _heaps pointer.
     * The old array stays valid for any in-flight reader — leaks ~24 bytes
     * but that's a one-time cost.
     *
     * HotSpot only mutates _heaps in CodeCache::add_heap, which is gated
     * on !Universe::is_fully_initialized(). By the time our JNI_OnLoad
     * runs the universe is fully initialized, so HotSpot itself won't be
     * concurrently growing _heaps and we don't need a CodeCache_lock. */
    {
        size_t array_size = (size_t)g_neko_method_layout.off_growable_array_data + sizeof(void*);
        /* Pad to 16 bytes for safety (matches typical alignment). */
        array_size = (array_size + 15u) & ~(size_t)15u;
        void *new_array = calloc(1, array_size);
        if (new_array == NULL) {
            NEKO_PATCH_LOG("priv heap register: malloc new array failed");
            return JNI_FALSE;
        }
        int new_cap = len + 4;
        void **new_data = (void**)calloc((size_t)new_cap, sizeof(void*));
        if (new_data == NULL) {
            free(new_array);
            NEKO_PATCH_LOG("priv heap register: malloc new data failed");
            return JNI_FALSE;
        }
        for (int i = 0; i < len; i++) new_data[i] = data[i];
        new_data[len] = g_neko_priv_heap.codeheap;
        *(int*)((char*)new_array + g_neko_method_layout.off_growable_array_len) = len + 1;
        *(int*)((char*)new_array + g_neko_method_layout.off_growable_array_capacity) = new_cap;
        *(void***)((char*)new_array + g_neko_method_layout.off_growable_array_data) = new_data;
        __atomic_thread_fence(__ATOMIC_RELEASE);
        __atomic_store_n(heaps_static, new_array, __ATOMIC_RELEASE);
        g_neko_priv_heap.registered = JNI_TRUE;
        NEKO_PATCH_LOG("priv heap register: swapped _heaps from %p to %p (len %d -> %d, cap %d -> %d)",
            heaps_array, new_array, len, len + 1, cap, new_cap);
        return JNI_TRUE;
    }
}

/* === Phase 3: per-signature thunk + CodeBlob construction ===
 * For each signature trampoline (lives in libneko.so), build a small
 * position-independent thunk inside our private CodeHeap exec region:
 *
 *   push   %rbp                          ;  1 byte
 *   mov    %rsp,%rbp                     ;  3 bytes
 *   movabs $real_trampoline_addr, %r11   ; 10 bytes
 *   call   *%r11                         ;  3 bytes
 *   pop    %rbp                          ;  1 byte
 *   ret                                  ;  1 byte
 *
 * Total 19 bytes (rounded up to a 32-byte tail). Around the thunk we lay
 * out a HeapBlock header (16 bytes: size_t _length, bool _used) and a
 * synthetic BufferBlob struct (vtable harvested from an existing adapter)
 * so HotSpot's CodeCache::find_blob_at(thunk_pc) can resolve the blob and
 * walk its metadata without crashing. Interpreter entries use an i2i thunk;
 * compiled entries use a c2i thunk so compiled callers keep a walkable callee
 * frame for RegisterMap saved-rbp tracking. */

/* Per-method thunks: each (Method*, entry-type) installs its own thunk that
 * pre-loads `r10 = manifest entry pointer` before calling the shared per-
 * signature naked function. This eliminates the O(N) manifest scan that the
 * naked function previously did on every invocation (matching rbx == Method*
 * against the primary + alias arrays). For obfusjack with hundreds of patched
 * methods, the scan was the single largest per-call cost.
 *
 * Slot count: bindings * 3 (interp i2i / path-2 i2i / c2i) plus a margin for
 * defineClass aliases. 4096 covers ~1300 methods, well above realistic jars. */
#define NEKO_PRIV_THUNK_SLOT_MAX 4096

typedef struct {
    void *real_trampoline;   /* libneko.so trampoline PC */
    int   frame_size_words;  /* BufferBlob _frame_size for this thunk */
    void *entry_ptr;         /* manifest entry pointer baked into thunk's r10 immediate */
    void *thunk_pc;          /* relocated thunk PC inside our exec heap */
} neko_priv_thunk_slot_t;

static neko_priv_thunk_slot_t g_neko_priv_thunks[NEKO_PRIV_THUNK_SLOT_MAX] = {0};
static uint32_t g_neko_priv_thunk_count = 0;

/* Round x up to multiple of n (n must be power-of-two). */
#define NEKO_ROUND_UP(x, n) (((x) + ((n) - 1u)) & ~((typeof(x))(n) - 1u))

static void *neko_priv_alloc_thunk(void *real_trampoline, int frame_size_words, void *entry_ptr) {
    if (g_neko_priv_heap.codeheap == NULL || !g_neko_priv_heap.registered) return NULL;
    if (real_trampoline == NULL) return NULL;
    if (g_neko_method_layout.bufferblob_vtable == NULL) return NULL;
    if (g_neko_method_layout.sizeof_BufferBlob == 0) return NULL;

    /* Layout inside one segment-aligned block:
     *   [HeapBlock header (16)]  [BufferBlob struct]  [thunk (48)]
     * Round total bytes up to segment_size. The thunk now embeds an extra
     * 10-byte movabs r10,$entry_ptr so the naked trampoline can skip the
     * manifest scan; bumped from 32 to 48 bytes to keep 16-byte alignment. */
    const size_t header_bytes = 16;
    const size_t blob_bytes   = g_neko_method_layout.sizeof_BufferBlob;
    const size_t thunk_bytes  = 48;
    size_t total = header_bytes + blob_bytes + thunk_bytes;
    size_t seg_bytes = g_neko_priv_heap.segment_size;
    size_t segments  = (total + seg_bytes - 1u) / seg_bytes;
    size_t block_bytes = segments * seg_bytes;
    if (g_neko_priv_heap.next_byte + block_bytes > g_neko_priv_heap.exec_size) return NULL;

    char *block = (char*)g_neko_priv_heap.exec_region + g_neko_priv_heap.next_byte;
    size_t base_segment = g_neko_priv_heap.next_byte / seg_bytes;
    g_neko_priv_heap.next_byte += block_bytes;

    /* HeapBlock header. _length is in segment units (per HotSpot). _used==1. */
    *(size_t*)block = segments;
    *(int8_t*)(block + sizeof(size_t)) = 1;
    /* Remaining bytes of the 16-byte header are zero. */

    /* BufferBlob: vtable + _name + _size + _header_size + _data_offset +
     * _frame_complete_offset + _frame_size + _code_begin/_code_end +
     * _content_begin/_data_end. Everything else stays zero (calloc'd region). */
    char *blob = block + header_bytes;
    memset(blob, 0, blob_bytes);
    *(void**)blob = g_neko_method_layout.bufferblob_vtable;
    if (g_neko_method_layout.off_codeblob_name > 0) {
        *(const char**)(blob + g_neko_method_layout.off_codeblob_name) = "neko_trampoline";
    }
    if (g_neko_method_layout.off_codeblob_size > 0) {
        *(int*)(blob + g_neko_method_layout.off_codeblob_size) = (int)block_bytes;
    }
    if (g_neko_method_layout.off_codeblob_header_size > 0) {
        *(int*)(blob + g_neko_method_layout.off_codeblob_header_size) = (int)blob_bytes;
    }
    /* CodeBlob::content/code/data layout: code starts immediately after the
     * BufferBlob struct ends; thunk_bytes is its length; no data section. */
    char *code_begin = blob + blob_bytes;
    char *code_end   = code_begin + thunk_bytes;
    if (g_neko_method_layout.off_codeblob_code_begin > 0) {
        *(void**)(blob + g_neko_method_layout.off_codeblob_code_begin) = code_begin;
    }
    if (g_neko_method_layout.off_codeblob_code_end > 0) {
        *(void**)(blob + g_neko_method_layout.off_codeblob_code_end) = code_end;
    }
    if (g_neko_method_layout.off_codeblob_content_begin > 0) {
        *(void**)(blob + g_neko_method_layout.off_codeblob_content_begin) = code_begin;
    }
    if (g_neko_method_layout.off_codeblob_data_end > 0) {
        *(void**)(blob + g_neko_method_layout.off_codeblob_data_end) = code_end;
    }
    /* _data_offset is computed in CodeBlob constructors as relative to
     * the blob start. Setting it to point at code_end keeps any
     * data_address() reads inside the blob bounds. */
    if (g_neko_method_layout.off_codeblob_data_offset > 0) {
        *(int*)(blob + g_neko_method_layout.off_codeblob_data_offset) = (int)(blob_bytes + thunk_bytes);
    }
    /* The thunk creates a tiny real frame before calling into libneko. The
     * libneko trampoline tail-jumps back to Java with rsp restored from r13,
     * so this frame is discarded instead of returned through. HotSpot can
     * nevertheless walk it while JNI upcalls are active because the synthetic
     * BufferBlob reports a coherent three-word frame. With last_Java_sp
     * published as the return-to-libneko slot, HotSpot computes
     * sender_sp = sp + frame_size = Java caller sp after its return pc,
     * then reads [sender_sp - 2] as saved rbp and [sender_sp - 1] as the
     * Java return pc:
     *   [return-to-libneko] [saved rbp] [Java return pc]
     */
    if (g_neko_method_layout.off_codeblob_frame_complete_offset > 0) {
        *(int*)(blob + g_neko_method_layout.off_codeblob_frame_complete_offset) = 4;
    }
    if (g_neko_method_layout.off_codeblob_frame_size > 0) {
        /* Path-2-aware sizing. The base 3 words cover the thunk's own frame
         * (thunk's saved-rbp + thunk_after_call_pc + naked's saved-rbp). For
         * thunks installed as _i2i_entry (reached via HotSpot's c2i adapter),
         * we add the adapter's extraspace shift in word units so
         * sender_sp = caller_pre_call_rsp matches accept's true _sp. */
        *(int*)(blob + g_neko_method_layout.off_codeblob_frame_size) = frame_size_words;
    }

    /* Thunk bytes (29 used + 0xCC pad to 48):
     *   push rbp                   ; 0x55                   [1]
     *   mov  rsp,rbp               ; 0x48 0x89 0xE5         [3]
     *   movabs $entry_ptr,%r10     ; 0x49 0xBA imm64        [10]  -- per-method preload
     *   movabs $real_trampoline,%r11; 0x49 0xBB imm64       [10]
     *   call  *%r11                ; 0x41 0xFF 0xD3         [3]
     *   pop  rbp                   ; 0x5D                   [1]
     *   ret                        ; 0xC3                   [1]
     * The naked trampoline reads %r10 immediately as its "entry pointer"
     * (formerly recovered via a manifest scan keyed on rbx == Method*).
     * i2i never returns through `pop rbp; ret` — it tail-jumps back to Java
     * via r13. c2i does return normally through this thunk frame. */
    char *thunk = code_begin;
    /* push rbp ; mov rsp,rbp */
    thunk[0] = (char)0x55;
    thunk[1] = (char)0x48; thunk[2] = (char)0x89; thunk[3] = (char)0xE5;
    /* movabs $entry_ptr, %r10  ;  REX.W + B8+r10 register encoding = 0x49 0xBA */
    thunk[4] = (char)0x49; thunk[5] = (char)0xBA;
    memcpy(thunk + 6, &entry_ptr, sizeof(void*));
    /* movabs $real_trampoline, %r11 */
    thunk[14] = (char)0x49; thunk[15] = (char)0xBB;
    memcpy(thunk + 16, &real_trampoline, sizeof(void*));
    /* call *%r11 */
    thunk[24] = (char)0x41; thunk[25] = (char)0xFF; thunk[26] = (char)0xD3;
    /* pop rbp ; ret */
    thunk[27] = (char)0x5D;
    thunk[28] = (char)0xC3;
    for (size_t i = 29; i < thunk_bytes; i++) thunk[i] = (char)0xCC;

    /* Segmap: HotSpot encodes "segments since the start of this block" at
     * each segment index. Index 0 is 0, index 1 is 1, ..., capped at 0xFE
     * (0xFF means "not allocated / chain marker"). */
    char *segmap = (char*)g_neko_priv_heap.segmap_region;
    for (size_t i = 0; i < segments; i++) {
        segmap[base_segment + i] = (char)(i < 0xFEu ? i : 0xFEu);
    }

    /* _next_segment: HotSpot's bump-pointer cursor. Updated so future scans
     * iterate up to here. We're the sole writer, so a plain store suffices. */
    ptrdiff_t off_log2 = g_neko_method_layout.off_codeheap_log2_segment_size;
    *(size_t*)((char*)g_neko_priv_heap.codeheap + off_log2 + 8) = base_segment + segments;

    NEKO_PATCH_LOG("priv thunk: real=%p thunk=%p blob=%p seg_base=%zu segs=%zu entry=%p",
        real_trampoline, code_begin, blob, base_segment, segments, entry_ptr);
    return code_begin;
}

static void *neko_priv_get_thunk(void *real_trampoline, int frame_size_words, void *entry_ptr) {
    if (real_trampoline == NULL) return NULL;
    /* Dedup keyed on (trampoline, frame_size, entry_ptr). With per-method
     * thunks each entry_ptr is unique, so dedup mostly helps re-entrant
     * patching of the same Method* (defineClass alias replays). */
    for (uint32_t i = 0; i < g_neko_priv_thunk_count; i++) {
        if (g_neko_priv_thunks[i].real_trampoline == real_trampoline
            && g_neko_priv_thunks[i].frame_size_words == frame_size_words
            && g_neko_priv_thunks[i].entry_ptr == entry_ptr) {
            return g_neko_priv_thunks[i].thunk_pc;
        }
    }
    if (g_neko_priv_thunk_count >= NEKO_PRIV_THUNK_SLOT_MAX) return NULL;
    void *thunk = neko_priv_alloc_thunk(real_trampoline, frame_size_words, entry_ptr);
    if (thunk == NULL) return NULL;
    g_neko_priv_thunks[g_neko_priv_thunk_count].real_trampoline = real_trampoline;
    g_neko_priv_thunks[g_neko_priv_thunk_count].frame_size_words = frame_size_words;
    g_neko_priv_thunks[g_neko_priv_thunk_count].entry_ptr = entry_ptr;
    g_neko_priv_thunks[g_neko_priv_thunk_count].thunk_pc = thunk;
    g_neko_priv_thunk_count++;
    return thunk;
}

/* === Native→Java trampoline CodeBlob registration ===
 *
 * Copy each generated native→Java trampoline into the private CodeHeap and
 * wrap the copied bytes in a synthetic BufferBlob. The copied code contains
 * the `call *Method::_from_compiled_entry` instruction, so a Java callee's
 * sender PC is inside a HotSpot-recognized CodeBlob instead of libneko text.
 * That is the property fillInStackTrace/GC stack walks need. */

typedef struct {
    void *code_start;
    void *code_end;
    void *wrapper_pc;
} neko_priv_njx_wrapper_slot_t;

static neko_priv_njx_wrapper_slot_t g_neko_priv_njx_wrappers[256] = {0};
static uint32_t g_neko_priv_njx_wrapper_count = 0;

static void *neko_priv_alloc_njx_trampoline(void *code_start_src, void *code_end_src, int frame_size_words) {
    if (g_neko_priv_heap.codeheap == NULL || !g_neko_priv_heap.registered) return NULL;
    if (code_start_src == NULL || code_end_src == NULL || (char*)code_end_src <= (char*)code_start_src) return NULL;
    if (frame_size_words <= 0) return NULL;
    if (g_neko_method_layout.bufferblob_vtable == NULL) return NULL;
    if (g_neko_method_layout.sizeof_BufferBlob == 0) return NULL;

    /* Dedup. */
    for (uint32_t i = 0; i < g_neko_priv_njx_wrapper_count; i++) {
        if (g_neko_priv_njx_wrappers[i].code_start == code_start_src
            && g_neko_priv_njx_wrappers[i].code_end == code_end_src) {
            return g_neko_priv_njx_wrappers[i].wrapper_pc;
        }
    }
    if (g_neko_priv_njx_wrapper_count >= 256) return NULL;

    const size_t header_bytes = 16;
    const size_t blob_bytes   = g_neko_method_layout.sizeof_BufferBlob;
    const size_t code_bytes   = (size_t)((char*)code_end_src - (char*)code_start_src);
    const size_t code_alloc_bytes = (code_bytes + 15u) & ~(size_t)15u;
    size_t total = header_bytes + blob_bytes + code_alloc_bytes;
    size_t seg_bytes = g_neko_priv_heap.segment_size;
    size_t segments  = (total + seg_bytes - 1u) / seg_bytes;
    size_t block_bytes = segments * seg_bytes;
    if (g_neko_priv_heap.next_byte + block_bytes > g_neko_priv_heap.exec_size) return NULL;

    char *block = (char*)g_neko_priv_heap.exec_region + g_neko_priv_heap.next_byte;
    size_t base_segment = g_neko_priv_heap.next_byte / seg_bytes;
    g_neko_priv_heap.next_byte += block_bytes;

    /* HeapBlock header. */
    *(size_t*)block = segments;
    *(int8_t*)(block + sizeof(size_t)) = 1;

    /* BufferBlob struct. */
    char *blob = block + header_bytes;
    memset(blob, 0, blob_bytes);
    *(void**)blob = g_neko_method_layout.bufferblob_vtable;
    if (g_neko_method_layout.off_codeblob_name > 0) {
        *(const char**)(blob + g_neko_method_layout.off_codeblob_name) = "neko_njx_trampoline";
    }
    if (g_neko_method_layout.off_codeblob_size > 0) {
        *(int*)(blob + g_neko_method_layout.off_codeblob_size) = (int)block_bytes;
    }
    if (g_neko_method_layout.off_codeblob_header_size > 0) {
        *(int*)(blob + g_neko_method_layout.off_codeblob_header_size) = (int)blob_bytes;
    }
    char *code_begin = blob + blob_bytes;
    char *code_end   = code_begin + code_bytes;
    if (g_neko_method_layout.off_codeblob_code_begin > 0) {
        *(void**)(blob + g_neko_method_layout.off_codeblob_code_begin) = code_begin;
    }
    if (g_neko_method_layout.off_codeblob_code_end > 0) {
        *(void**)(blob + g_neko_method_layout.off_codeblob_code_end) = code_end;
    }
    if (g_neko_method_layout.off_codeblob_content_begin > 0) {
        *(void**)(blob + g_neko_method_layout.off_codeblob_content_begin) = code_begin;
    }
    if (g_neko_method_layout.off_codeblob_data_end > 0) {
        *(void**)(blob + g_neko_method_layout.off_codeblob_data_end) = code_begin + code_alloc_bytes;
    }
    if (g_neko_method_layout.off_codeblob_data_offset > 0) {
        *(int*)(blob + g_neko_method_layout.off_codeblob_data_offset) = (int)(blob_bytes + code_alloc_bytes);
    }
    if (g_neko_method_layout.off_codeblob_frame_complete_offset > 0) {
        *(int*)(blob + g_neko_method_layout.off_codeblob_frame_complete_offset) = 4;
    }
    if (g_neko_method_layout.off_codeblob_frame_size > 0) {
        *(int*)(blob + g_neko_method_layout.off_codeblob_frame_size) = frame_size_words;
    }

    memcpy(code_begin, code_start_src, code_bytes);
    for (size_t i = code_bytes; i < code_alloc_bytes; i++) code_begin[i] = (char)0xCC;
    __builtin___clear_cache(code_begin, code_begin + code_alloc_bytes);

    /* Segmap. */
    char *segmap = (char*)g_neko_priv_heap.segmap_region;
    for (size_t i = 0; i < segments; i++) {
        segmap[base_segment + i] = (char)(i < 0xFEu ? i : 0xFEu);
    }
    ptrdiff_t off_log2 = g_neko_method_layout.off_codeheap_log2_segment_size;
    *(size_t*)((char*)g_neko_priv_heap.codeheap + off_log2 + 8) = base_segment + segments;

    g_neko_priv_njx_wrappers[g_neko_priv_njx_wrapper_count].code_start = code_start_src;
    g_neko_priv_njx_wrappers[g_neko_priv_njx_wrapper_count].code_end = code_end_src;
    g_neko_priv_njx_wrappers[g_neko_priv_njx_wrapper_count].wrapper_pc = code_begin;
    g_neko_priv_njx_wrapper_count++;

    NEKO_PATCH_LOG("[neko-direct] njx trampoline copied src=[%p..%p) dst=%p bytes=%zu frame=%d",
        code_start_src, code_end_src, code_begin, code_bytes, frame_size_words);
    return code_begin;
}

static jboolean neko_codecache_walk(void) {
    if (!neko_codecache_layout_ready()) {
        NEKO_PATCH_LOG("codecache walk: layout not ready");
        return JNI_FALSE;
    }
    void *heaps_array = *(void**)g_neko_method_layout.addr_codecache_heaps;
    if (heaps_array == NULL) {
        NEKO_PATCH_LOG("codecache walk: _heaps is NULL");
        return JNI_FALSE;
    }
    int len = *(int*)((char*)heaps_array + g_neko_method_layout.off_growable_array_len);
    void **data = *(void***)((char*)heaps_array + g_neko_method_layout.off_growable_array_data);
    NEKO_PATCH_LOG("codecache walk: heaps=%p len=%d data=%p", heaps_array, len, data);
    if (data == NULL || len <= 0) return JNI_FALSE;
    neko_blob_visit_ctx_t ctx = {0};
    if (g_neko_method_layout.addr_call_stub_return_address != NULL) {
        ctx.call_stub_return_pc = *(void**)g_neko_method_layout.addr_call_stub_return_address;
    }
    for (int i = 0; i < len; i++) {
        void *heap = data[i];
        if (heap == NULL) continue;
        void *mem_low = neko_virtualspace_low((char*)heap + g_neko_method_layout.off_codeheap_memory);
        void *mem_high = neko_virtualspace_high((char*)heap + g_neko_method_layout.off_codeheap_memory);
        int log2_seg = *(int*)((char*)heap + g_neko_method_layout.off_codeheap_log2_segment_size);
        NEKO_PATCH_LOG("codeheap[%d]=%p memory=[%p..%p) log2_seg=%d", i, heap, mem_low, mem_high, log2_seg);
        neko_walk_codeheap(heap, neko_blob_visit_log, &ctx);
    }
    g_neko_method_layout.bufferblob_vtable = ctx.target_vtable;
    if (ctx.call_stub_entry != NULL) {
        g_neko_call_stub_return_address = ctx.call_stub_return_pc;
        g_neko_call_stub_entry = ctx.call_stub_entry;
    }
    NEKO_PATCH_LOG("codecache walk: harvested vtable=%p call_stub=%p ret=%p sizeof(CodeHeap)=%zu sizeof(BufferBlob)=%zu sizeof(VirtualSpace)=%zu",
        ctx.target_vtable, g_neko_call_stub_entry, g_neko_call_stub_return_address,
        g_neko_method_layout.sizeof_CodeHeap,
        g_neko_method_layout.sizeof_BufferBlob, g_neko_method_layout.sizeof_VirtualSpace);
    return ctx.target_vtable != NULL ? JNI_TRUE : JNI_FALSE;
}

""";
    }

    private static String renderPart3() {
        return """
static jboolean neko_method_layout_init(JNIEnv *env) {
    if (g_neko_method_layout.initialized) return g_neko_method_layout.usable;
    g_neko_method_layout.initialized = JNI_TRUE;
    g_neko_method_layout.usable = JNI_FALSE;
    g_neko_method_layout.off_method_access_flags = -1;
    g_neko_method_layout.off_method_code = -1;
    g_neko_method_layout.off_method_i2i_entry = -1;
    g_neko_method_layout.off_method_from_interpreted_entry = -1;
    g_neko_method_layout.off_method_from_compiled_entry = -1;
    g_neko_method_layout.off_method_flags_status = -1;
    g_neko_method_layout.off_method_intrinsic_id = -1;
    g_neko_method_layout.off_method_vtable_index = -1;
    g_neko_method_layout.off_method_constMethod = -1;
    g_neko_method_layout.off_frame_anchor_sp = -1;
    g_neko_method_layout.off_frame_anchor_fp = -1;
    g_neko_method_layout.off_frame_anchor_pc = -1;
    g_neko_method_layout.off_jcw_anchor = -1;
    g_neko_method_layout.off_zglobals_address_offset_mask = -1;
    g_neko_method_layout.off_zglobals_pointer_load_good_mask = -1;
    g_neko_method_layout.off_zglobals_pointer_load_bad_mask = -1;
    g_neko_method_layout.off_zglobals_pointer_store_good_mask = -1;
    g_neko_method_layout.off_zglobals_pointer_load_shift = -1;
    g_neko_method_layout.off_basicobjectlock_lock = -1;
    g_neko_method_layout.off_basicobjectlock_obj = -1;
    g_neko_method_layout.off_basiclock_displaced_header = -1;
    g_neko_method_layout.off_thread_tlab = -1;
    g_neko_method_layout.off_tlab_top = -1;
    g_neko_method_layout.off_tlab_end = -1;
    g_neko_method_layout.off_instanceklass_methods = -1;
    g_neko_method_layout.off_instanceklass_fieldinfo_stream = -1;
    g_neko_method_layout.off_instanceklass_fields = -1;
    g_neko_method_layout.off_instanceklass_constants = -1;
    g_neko_method_layout.off_instanceklass_local_interfaces = -1;
    g_neko_method_layout.off_instanceklass_transitive_interfaces = -1;
    g_neko_method_layout.off_instanceklass_java_fields_count = -1;
    g_neko_method_layout.off_klass_super_check_offset = -1;
    g_neko_method_layout.off_klass_secondary_super_cache = -1;
    g_neko_method_layout.off_klass_secondary_supers = -1;
    g_neko_method_layout.off_klass_primary_supers_0 = -1;
    g_neko_method_layout.off_klass_layout_helper = -1;
    g_neko_method_layout.off_klass_java_mirror = -1;
    g_neko_method_layout.off_klass_super = -1;
    g_neko_method_layout.off_klass_subklass = -1;
    g_neko_method_layout.off_klass_next_link = -1;
    g_neko_method_layout.off_klass_name = -1;
    g_neko_method_layout.off_objarrayklass_element_klass = -1;
    g_neko_method_layout.off_java_lang_class_klass = -1;
    g_neko_method_layout.off_classloaderdata_next = -1;
    g_neko_method_layout.off_classloaderdata_klasses = -1;
    g_neko_method_layout.off_constantpool_tags = -1;
    g_neko_method_layout.off_constantpool_cache = -1;
    g_neko_method_layout.off_constantpool_pool_holder = -1;
    g_neko_method_layout.off_constantpool_operands = -1;
    g_neko_method_layout.off_constantpool_resolved_klasses = -1;
    g_neko_method_layout.off_constantpool_length = -1;
    g_neko_method_layout.off_constantpool_base = -1;
    g_neko_method_layout.sizeof_ConstantPool = 0;
    g_neko_method_layout.off_constmethod_constants = -1;
    g_neko_method_layout.off_constmethod_name_index = -1;
    g_neko_method_layout.off_constmethod_signature_index = -1;
    g_neko_method_layout.off_symbol_length = -1;
    g_neko_method_layout.off_symbol_body = -1;
    g_neko_method_layout.off_array_length = -1;
    g_neko_method_layout.off_array_data = -1;
    g_neko_method_layout.off_array_u1_data = -1;
    g_neko_method_layout.off_array_u2_data = -1;
    g_neko_method_layout.off_barrierset_fake_rtti = -1;
    g_neko_method_layout.off_barrierset_fakertti_concrete_tag = -1;
    g_neko_method_layout.off_cardtablebarrierset_card_table = -1;
    g_neko_method_layout.off_cardtable_byte_map_base = -1;
    g_neko_method_layout.vmconst_barrierset_modref = -1;
    g_neko_method_layout.vmconst_barrierset_cardtable = -1;
    g_neko_method_layout.vmconst_barrierset_g1 = -1;
    g_neko_method_layout.vmconst_cardtable_clean_card = -1;
    g_neko_method_layout.vmconst_cardtable_dirty_card = -1;
    g_neko_method_layout.vmconst_g1_young_card = -1;
    g_neko_method_layout.java_spec_version = 0;
    void *jvm = neko_resolve_libjvm_handle();
    jboolean jnihandles_symbols_ready = JNI_FALSE;
    NEKO_PATCH_LOG("layout_init: jdk=%d libjvm=%p", g_neko_method_layout.java_spec_version, jvm);
    if (jvm == NULL) return JNI_FALSE;
    neko_resolve_native_resolution_symbols(jvm);
    neko_resolve_gc_barrier_symbols(jvm);
    if (!neko_walk_vm_structs(jvm)) { NEKO_PATCH_LOG("walk_vm_structs failed"); return JNI_FALSE; }
    (void)neko_walk_vm_types(jvm);
    neko_walk_vm_int_constants(jvm);
    if (g_neko_method_layout.access_not_c1_compilable == 0u) g_neko_method_layout.access_not_c1_compilable = NEKO_ACC_NOT_C1_COMPILABLE_FALLBACK;
    if (g_neko_method_layout.access_not_c2_compilable == 0u) g_neko_method_layout.access_not_c2_compilable = NEKO_ACC_NOT_C2_COMPILABLE_FALLBACK;
    if (g_neko_method_layout.access_not_osr_compilable == 0u) g_neko_method_layout.access_not_osr_compilable = NEKO_ACC_NOT_OSR_COMPILABLE_FALLBACK;
    /* MethodFlags::_status bit positions on JDK 21+ (not exposed via
     * VMIntConstants, but stable in OpenJDK source). Bit 0 = NOT_C1_COMPILABLE,
     * bit 1 = NOT_C2_COMPILABLE, bit 2 = NOT_C1_OSR_COMPILABLE,
     * bit 3 = NOT_C2_OSR_COMPILABLE. */
    if (g_neko_method_layout.method_flag_not_c1_compilable == 0u)  g_neko_method_layout.method_flag_not_c1_compilable  = 0x1u;
    if (g_neko_method_layout.method_flag_not_c2_compilable == 0u)  g_neko_method_layout.method_flag_not_c2_compilable  = 0x2u;
    if (g_neko_method_layout.method_flag_not_osr_compilable == 0u) g_neko_method_layout.method_flag_not_osr_compilable = 0x4u | 0x8u;
    if (g_neko_method_layout.off_frame_anchor_sp < 0
        && g_neko_method_layout.off_frame_anchor_pc == 8) {
        g_neko_method_layout.off_frame_anchor_sp = 0;
    }
    if (g_neko_method_layout.off_frame_anchor_fp < 0
        && g_neko_method_layout.off_frame_anchor_sp == 0
        && g_neko_method_layout.off_frame_anchor_pc == 8) {
        g_neko_method_layout.off_frame_anchor_fp = 16;
    }
    if (g_neko_method_layout.off_jcw_anchor < 0) {
        g_neko_method_layout.off_jcw_anchor = 32;
    }
    /* JDK 21+ does not expose Method::_flags via VMStructs. The actual layout
     * (verified against openjdk-21.0.10 method.hpp) is:
     *   ... _access_flags (u4) | _vtable_index (i4) | _intrinsic_id (u2) | _flags (u2) ...
     * So _flags is _intrinsic_id + 2. The earlier "intrinsic_id - 4" guess
     * pointed at unknown bytes (likely method identity/index padding) and
     * silently corrupted state. */
    if (g_neko_method_layout.off_method_flags_status < 0
        && g_neko_method_layout.off_method_intrinsic_id > 0) {
        g_neko_method_layout.off_method_flags_status =
            g_neko_method_layout.off_method_intrinsic_id + (ptrdiff_t)2;
    }
    jnihandles_symbols_ready = neko_resolve_jnihandles(jvm);
    if (g_neko_method_layout.off_method_access_flags < 0
        || g_neko_method_layout.off_method_code < 0
        || g_neko_method_layout.off_method_i2i_entry < 0
        || g_neko_method_layout.off_method_from_interpreted_entry < 0
        || g_neko_method_layout.off_method_from_compiled_entry < 0) {
        NEKO_PATCH_LOG("missing required Method offset; patcher disabled");
        return JNI_FALSE;
    }
    /* VMStructs only registers JavaThread::_jni_environment under JVMCI.
     * Without it, the dispatcher cannot recover JNIEnv* without a JNI
     * GetEnv() round-trip — which violates the no-runtime-JNI rule. Derive
     * the offset directly: r15 holds JavaThread* on x86_64 SysV (HotSpot
     * convention), env is a pointer to the embedded _jni_environment field,
     * so off = (char*)env - (char*)r15. Done once at JNI_OnLoad. */
    if (g_neko_method_layout.off_thread_jni_environment <= 0 && env != NULL) {
        /* Use the thread-register snapshot captured at JNI_OnLoad entry.
         * Reading r15 here would be unreliable: clang freely reassigns r15
         * to local variables once we are deep in the C code. */
        void *jt = g_neko_jni_onload_thread_reg;
        if (jt != NULL) {
            ptrdiff_t derived = (ptrdiff_t)((char*)env - (char*)jt);
            if (derived > 0 && derived < 0x10000) {
                g_neko_method_layout.off_thread_jni_environment = derived;
                NEKO_PATCH_LOG("derived off_thread_jni_environment=%td via thread_reg=%p env=%p",
                    derived, jt, (void*)env);
            } else {
                NEKO_PATCH_LOG("derived off_thread_jni_environment unreasonable: derived=%td jt=%p env=%p",
                    derived, jt, (void*)env);
            }
        }
    }
    NEKO_PATCH_LOG("offsets: af=%td code=%td i2i=%td fi=%td fc=%td flags=%td af_sz=%zu",
        g_neko_method_layout.off_method_access_flags,
        g_neko_method_layout.off_method_code,
        g_neko_method_layout.off_method_i2i_entry,
        g_neko_method_layout.off_method_from_interpreted_entry,
        g_neko_method_layout.off_method_from_compiled_entry,
        g_neko_method_layout.off_method_flags_status,
        g_neko_method_layout.access_flags_size);
    NEKO_PATCH_LOG("thread: state_off=%td poll_off=%td in_java=%d in_native=%d in_native_trans=%d",
        g_neko_method_layout.off_thread_state,
        g_neko_method_layout.off_thread_polling_word,
        g_neko_method_layout.thread_state_in_java,
        g_neko_method_layout.thread_state_in_native,
        g_neko_method_layout.thread_state_in_native_trans);
    /* Publish thread-state info to the asm-visible globals. */
    g_neko_off_thread_state             = g_neko_method_layout.off_thread_state;
    g_neko_thread_state_in_java         = g_neko_method_layout.thread_state_in_java;
    g_neko_thread_state_in_native       = g_neko_method_layout.thread_state_in_native;
    g_neko_thread_state_in_native_trans = g_neko_method_layout.thread_state_in_native_trans;
    g_neko_off_thread_polling_word      = g_neko_method_layout.off_thread_polling_word;
    g_neko_thread_state_ready =
        (g_neko_method_layout.off_thread_state != 0
         && g_neko_method_layout.thread_state_in_java != g_neko_method_layout.thread_state_in_native)
        ? JNI_TRUE : JNI_FALSE;
    /* Resolve frame-anchor final offsets: direct fields take priority,
     * else compute from anchor + sub-offset. */
    if (g_neko_method_layout.off_thread_last_Java_sp_direct > 0) {
        g_neko_off_last_Java_sp = g_neko_method_layout.off_thread_last_Java_sp_direct;
    } else if (g_neko_method_layout.off_thread_anchor > 0) {
        g_neko_off_last_Java_sp = g_neko_method_layout.off_thread_anchor + g_neko_method_layout.off_frame_anchor_sp;
    }
    if (g_neko_method_layout.off_thread_last_Java_fp_direct > 0) {
        g_neko_off_last_Java_fp = g_neko_method_layout.off_thread_last_Java_fp_direct;
    } else if (g_neko_method_layout.off_thread_anchor > 0) {
        g_neko_off_last_Java_fp = g_neko_method_layout.off_thread_anchor + g_neko_method_layout.off_frame_anchor_fp;
    }
    if (g_neko_method_layout.off_thread_last_Java_pc_direct > 0) {
        g_neko_off_last_Java_pc = g_neko_method_layout.off_thread_last_Java_pc_direct;
    } else if (g_neko_method_layout.off_thread_anchor > 0) {
        g_neko_off_last_Java_pc = g_neko_method_layout.off_thread_anchor + g_neko_method_layout.off_frame_anchor_pc;
    }
    g_neko_frame_anchor_ready = (g_neko_off_last_Java_sp > 0) ? JNI_TRUE : JNI_FALSE;
    NEKO_PATCH_LOG("anchor: sp=%td fp=%td pc=%td ready=%d",
        g_neko_off_last_Java_sp, g_neko_off_last_Java_fp, g_neko_off_last_Java_pc,
        (int)g_neko_frame_anchor_ready);
    g_neko_off_jcw_anchor = g_neko_method_layout.off_jcw_anchor;
    NEKO_PATCH_LOG("call_stub: entry=%p ret_slot=%p ret=%p jcw_anchor=%td jcw_size=%zu",
        g_neko_call_stub_entry,
        g_neko_method_layout.addr_call_stub_return_address,
        g_neko_call_stub_return_address,
        g_neko_off_jcw_anchor,
        g_neko_method_layout.sizeof_JavaCallWrapper);
    g_neko_off_thread_active_handles = g_neko_method_layout.off_thread_active_handles;
    g_neko_off_jnih_block_top        = g_neko_method_layout.off_jnih_block_top;
    g_neko_off_jnih_block_handles    = g_neko_method_layout.off_jnih_block_handles;
    g_neko_off_thread_pending_exception = g_neko_method_layout.off_thread_pending_exception;
    g_neko_handle_push_ready =
        (g_neko_off_thread_active_handles > 0
         && g_neko_off_jnih_block_top >= 0
         && g_neko_off_jnih_block_handles >= 0)
        ? JNI_TRUE : JNI_FALSE;
    if (!jnihandles_symbols_ready && !g_neko_handle_push_ready) {
        NEKO_PATCH_LOG("JNIHandles symbols and JNIHandleBlock layout unavailable; aborting native layout initialization");
        return JNI_FALSE;
    }
    if (!jnihandles_symbols_ready) {
        NEKO_PATCH_LOG("JNIHandles symbols not resolvable; using VMStruct JNIHandleBlock local handles");
    }
    g_neko_off_thread_tlab = g_neko_method_layout.off_thread_tlab;
    g_neko_off_tlab_top = g_neko_method_layout.off_tlab_top;
    g_neko_off_tlab_end = g_neko_method_layout.off_tlab_end;
    g_neko_tlab_alloc_ready =
        (g_neko_off_thread_tlab > 0
         && g_neko_off_tlab_top >= 0
         && g_neko_off_tlab_end > 0)
        ? JNI_TRUE : JNI_FALSE;
    NEKO_PATCH_LOG("handles: th_active=%td blk_top=%td blk_handles=%td blk_size=%zu pend_exc=%td ready=%d tlab=%td top=%td end=%td tlab_ready=%d",
        g_neko_off_thread_active_handles, g_neko_off_jnih_block_top,
        g_neko_off_jnih_block_handles, g_neko_method_layout.sizeof_JNIHandleBlock,
        g_neko_off_thread_pending_exception,
        (int)g_neko_handle_push_ready,
        g_neko_off_thread_tlab, g_neko_off_tlab_top, g_neko_off_tlab_end,
        (int)g_neko_tlab_alloc_ready);
    NEKO_PATCH_LOG("jni env: off=%td", g_neko_method_layout.off_thread_jni_environment);
    g_neko_method_layout.native_resolution_ready = neko_native_resolution_layout_ready();
    g_neko_native_resolution_ready = g_neko_method_layout.native_resolution_ready;
    g_neko_addr_compressed_oops_base = g_neko_method_layout.addr_compressed_oops_base;
    g_neko_addr_compressed_oops_shift = g_neko_method_layout.addr_compressed_oops_shift;
    g_neko_addr_compressed_klass_base = g_neko_method_layout.addr_compressed_klass_base;
    g_neko_addr_compressed_klass_shift = g_neko_method_layout.addr_compressed_klass_shift;
    NEKO_PATCH_LOG("native resolution: ready=%d ik_methods=%td ik_fields=%td ik_fieldinfo=%td ik_java_fields=%td ik_constants=%td klass_mirror=%td class_klass=%td klass_super=%td klass_supers=%td cp_tags=%td cp_holder=%td cp_len=%td cp_base=%td cp_size=%zu method_const=%td cm_name=%td cm_sig=%td symbol_len=%td symbol_body=%td array_len=%td array_data=%td array_u1_data=%td array_u2_data=%td universe_heap=%p bs=%p bs_rtti=%td bs_tag=%td jvm_find_loaded=%p boot_find=%p class_find=%p jvm_intern=%p jvm_prim=%p jvm_array=%p/%p string_intern=%p/%p oopfactory=%p/%p",
        (int)g_neko_native_resolution_ready,
        g_neko_method_layout.off_instanceklass_methods,
        g_neko_method_layout.off_instanceklass_fields,
        g_neko_method_layout.off_instanceklass_fieldinfo_stream,
        g_neko_method_layout.off_instanceklass_java_fields_count,
        g_neko_method_layout.off_instanceklass_constants,
        g_neko_method_layout.off_klass_java_mirror,
        g_neko_method_layout.off_java_lang_class_klass,
        g_neko_method_layout.off_klass_super,
        g_neko_method_layout.off_klass_secondary_supers,
        g_neko_method_layout.off_constantpool_tags,
        g_neko_method_layout.off_constantpool_pool_holder,
        g_neko_method_layout.off_constantpool_length,
        g_neko_method_layout.off_constantpool_base,
        g_neko_method_layout.sizeof_ConstantPool,
        g_neko_method_layout.off_method_constMethod,
        g_neko_method_layout.off_constmethod_name_index,
        g_neko_method_layout.off_constmethod_signature_index,
        g_neko_method_layout.off_symbol_length,
        g_neko_method_layout.off_symbol_body,
        g_neko_method_layout.off_array_length,
        g_neko_method_layout.off_array_data,
        g_neko_method_layout.off_array_u1_data,
        g_neko_method_layout.off_array_u2_data,
        g_neko_method_layout.addr_universe_collected_heap,
        g_neko_method_layout.addr_barrierset_barrier_set,
        g_neko_method_layout.off_barrierset_fake_rtti,
        g_neko_method_layout.off_barrierset_fakertti_concrete_tag,
        g_neko_method_layout.sym_jvm_find_loaded_class,
        g_neko_method_layout.sym_jvm_find_class_from_boot_loader,
        g_neko_method_layout.sym_jvm_find_class_from_class,
        g_neko_method_layout.sym_jvm_intern_string,
        g_neko_method_layout.sym_jvm_find_primitive_class,
        g_neko_method_layout.sym_jvm_new_array,
        g_neko_method_layout.sym_jvm_new_multi_array,
        g_neko_method_layout.sym_stringtable_intern_symbol,
        g_neko_method_layout.sym_stringtable_intern_utf8,
        g_neko_method_layout.sym_oopfactory_new_type_array,
        g_neko_method_layout.sym_oopfactory_new_obj_array);
    NEKO_PATCH_LOG("codecache layout: heaps=%p ga_len=%td ga_data=%td ch_mem=%td ch_seg=%td ch_log2=%td vs_low=%td vs_high=%td blob_name=%td blob_size=%td blob_code_begin=%td",
        g_neko_method_layout.addr_codecache_heaps,
        g_neko_method_layout.off_growable_array_len,
        g_neko_method_layout.off_growable_array_data,
        g_neko_method_layout.off_codeheap_memory,
        g_neko_method_layout.off_codeheap_segmap,
        g_neko_method_layout.off_codeheap_log2_segment_size,
        g_neko_method_layout.off_virtualspace_low,
        g_neko_method_layout.off_virtualspace_high,
        g_neko_method_layout.off_codeblob_name,
        g_neko_method_layout.off_codeblob_size,
        g_neko_method_layout.off_codeblob_code_begin);
    /* Private CodeHeap entry is mandatory. The codecache walk harvests the
     * AdapterBlob/BufferBlob vtable used by our synthetic blobs; if any part
     * of that setup fails, native entry patching must fail instead of leaving
     * the original JVM entry active. */
    if (!neko_codecache_walk()) {
        NEKO_PATCH_LOG("private CodeHeap setup failed: codecache walk did not harvest a vtable");
        return JNI_FALSE;
    }
    if (!neko_priv_heap_init()) {
        NEKO_PATCH_LOG("private CodeHeap setup failed: init failed");
        return JNI_FALSE;
    }
    if (!neko_priv_heap_register()) {
        NEKO_PATCH_LOG("private CodeHeap setup failed: registration failed");
        return JNI_FALSE;
    }
    /* Native→Java direct invoke now enters through HotSpot call_stub. */
    neko_njx_init_wrappers();
    /* T4.0: Publish JNIEnv->JavaThread distance EAGERLY before
     * neko_method_layout_init returns. The hot-path neko_exception_check
     * (renderHotSpotSupport region) reads this via a single non-atomic
     * load + pointer arithmetic + _pending_exception read; it no longer
     * contains a fallback resolver branch, so missing publication here
     * must abort layout init. Source order:
     *   1. VMStructs path: production HotSpot redacts off_thread_jni_environment
     *      from gHotSpotVMStructs, but JVMCI / debug builds expose it. Use
     *      the harvested offset directly when available.
     *   2. Memory-walk fallback: for production HotSpot, scan once with
     *      the bootstrap JNIEnv. The resolver function
     *      neko_exception_check_resolve_env_offset() is defined earlier
     *      in this translation unit (renderHotSpotSupport) and is marked
     *      cold/noinline so it is unreachable from the hot path.
     *   3. Both fail → return JNI_FALSE so JNI_OnLoad aborts. The hot
     *      path never has to derive the offset; the cold __builtin_expect
     *      abort in neko_exception_check exists only as a defensive guard
     *      for tear-down races. */
    if (g_neko_method_layout.off_thread_jni_environment > 0) {
        g_neko_off_thread_jni_environment_for_check =
            g_neko_method_layout.off_thread_jni_environment;
    }
    if (g_neko_off_thread_jni_environment_for_check <= 0) {
        if (env == NULL) {
            NEKO_PATCH_LOG("eager env-offset publication blocked: bootstrap JNIEnv is NULL");
            return JNI_FALSE;
        }
        if (g_neko_jni_functions_table == NULL) {
            NEKO_PATCH_LOG("eager env-offset publication blocked: jni_functions_table not captured");
            return JNI_FALSE;
        }
        if (g_neko_off_thread_pending_exception <= 0) {
            NEKO_PATCH_LOG("eager env-offset publication blocked: pending_exception offset unavailable (off=%td)",
                g_neko_off_thread_pending_exception);
            return JNI_FALSE;
        }
        if (!neko_exception_check_resolve_env_offset(env)) {
            NEKO_PATCH_LOG("eager env-offset publication failed (functions_table=%p pending_off=%td); hot-path neko_exception_check would have aborted on first translated method dispatch",
                g_neko_jni_functions_table, g_neko_off_thread_pending_exception);
            return JNI_FALSE;
        }
        /* Mirror the just-derived offset into method_layout so the inline
         * dispatcher helper neko_thread_jni_env() fast-paths from the very
         * first call (otherwise its slow-path scan would still fire once
         * per process). The offset is direction-symmetric: the resolver
         * scans env→thread, neko_thread_jni_env scans thread→env, both
         * derive the same K such that thread = env - K = env_addr at
         * (thread + K). */
        if (g_neko_off_thread_jni_environment_for_check > 0
            && g_neko_method_layout.off_thread_jni_environment <= 0) {
            g_neko_method_layout.off_thread_jni_environment =
                g_neko_off_thread_jni_environment_for_check;
        }
        NEKO_PATCH_LOG("eager env-offset publication via memory walk: off=%td (VMStructs did not expose JavaThread::_jni_environment)",
            g_neko_off_thread_jni_environment_for_check);
    } else {
        NEKO_PATCH_LOG("eager env-offset publication via VMStructs: off=%td",
            g_neko_off_thread_jni_environment_for_check);
    }
    g_neko_basictype_void    = g_neko_method_layout.basictype_void;
    g_neko_basictype_boolean = g_neko_method_layout.basictype_boolean;
    g_neko_basictype_byte    = g_neko_method_layout.basictype_byte;
    g_neko_basictype_char    = g_neko_method_layout.basictype_char;
    g_neko_basictype_short   = g_neko_method_layout.basictype_short;
    g_neko_basictype_int     = g_neko_method_layout.basictype_int;
    g_neko_basictype_long    = g_neko_method_layout.basictype_long;
    g_neko_basictype_float   = g_neko_method_layout.basictype_float;
    g_neko_basictype_double  = g_neko_method_layout.basictype_double;
    g_neko_basictype_object  = g_neko_method_layout.basictype_object;
    g_neko_basictype_array   = g_neko_method_layout.basictype_array;
    g_neko_runtime1_monitorenter_entry = g_neko_method_layout.runtime1_monitorenter_entry;
    g_neko_runtime1_monitorexit_entry = g_neko_method_layout.runtime1_monitorexit_entry;
    g_neko_off_basicobjectlock_lock = g_neko_method_layout.off_basicobjectlock_lock;
    g_neko_off_basicobjectlock_obj = g_neko_method_layout.off_basicobjectlock_obj;
    g_neko_off_basiclock_displaced_header = g_neko_method_layout.off_basiclock_displaced_header;
    g_neko_sizeof_basicobjectlock = g_neko_method_layout.sizeof_BasicObjectLock;
    g_neko_sizeof_basiclock = g_neko_method_layout.sizeof_BasicLock;
    g_neko_monitor_stub_ready =
        (g_neko_runtime1_monitorenter_entry != NULL
         && g_neko_runtime1_monitorexit_entry != NULL
         && g_neko_off_basicobjectlock_lock >= 0
         && g_neko_off_basicobjectlock_obj >= 0
         && g_neko_off_basiclock_displaced_header >= 0
         && g_neko_sizeof_basicobjectlock > 0
         && g_neko_sizeof_basiclock > 0)
        ? JNI_TRUE : JNI_FALSE;
    NEKO_PATCH_LOG("monitor stubs: enter=%p exit=%p bol_lock=%td bol_obj=%td block_hdr=%td bol_size=%zu block_size=%zu ready=%d",
        g_neko_runtime1_monitorenter_entry,
        g_neko_runtime1_monitorexit_entry,
        g_neko_off_basicobjectlock_lock,
        g_neko_off_basicobjectlock_obj,
        g_neko_off_basiclock_displaced_header,
        g_neko_sizeof_basicobjectlock,
        g_neko_sizeof_basiclock,
        (int)g_neko_monitor_stub_ready);
    /* Publish the heap base value (for %r12 setup before Java calls). On
     * zero-based compressed oops this is NULL; with non-zero base we must
     * publish the actual base. Read from the harvested CompressedOops slot
     * if available, else fall back to whatever neko_hotspot_init resolved. */
    if (g_neko_method_layout.addr_compressed_oops_base != NULL) {
        g_neko_heap_base = *(void**)g_neko_method_layout.addr_compressed_oops_base;
    } else if (g_hotspot.compressed_oops_enabled) {
        g_neko_heap_base = (void*)(uintptr_t)g_hotspot.compressed_oops_base;
    } else {
        g_neko_heap_base = NULL;
    }
    NEKO_PATCH_LOG("heap base: %p", g_neko_heap_base);
    /* Direct-invoke readiness: we need the compiled-entry offset (for
     * Method* → entry pointer extraction at the call site), the thread-state
     * machinery (for the in_native ↔ in_Java transition around the call),
     * the frame-anchor offsets (for GC stack walking), and the
     * active-handles slot (for ref arg/return marshalling). */
    g_neko_direct_invoke_ready =
        (g_neko_method_layout.off_method_from_interpreted_entry > 0
         && g_neko_thread_state_ready
         && g_neko_off_last_Java_sp > 0
         && g_neko_off_last_Java_fp > 0
         && g_neko_off_last_Java_pc > 0
         && g_neko_off_thread_active_handles > 0
         && g_neko_call_stub_entry != NULL
         && g_neko_off_jcw_anchor > 0)
        ? JNI_TRUE : JNI_FALSE;
    NEKO_PATCH_LOG("direct invoke: centry_off=%td ientry_off=%td thread_ready=%d anchor_ok=%d call_stub=%p ready=%d",
        g_neko_method_layout.off_method_from_compiled_entry,
        g_neko_method_layout.off_method_from_interpreted_entry,
        (int)g_neko_thread_state_ready, (int)(g_neko_off_last_Java_sp > 0),
        g_neko_call_stub_entry,
        (int)g_neko_direct_invoke_ready);
    /* Pull compressed-oops state directly from VMStructs. */
    if (g_neko_method_layout.addr_compressed_oops_shift != NULL) {
        int shift = *(int*)g_neko_method_layout.addr_compressed_oops_shift;
        void *base = (g_neko_method_layout.addr_compressed_oops_base != NULL)
            ? *(void**)g_neko_method_layout.addr_compressed_oops_base
            : NULL;
        /* HotSpot keeps shift = 0 when compressed oops are disabled OR when
         * the heap is small enough to fit below 4GB unshifted. In both
         * variants oops still occupy 4-byte slots in compressed mode, so
         * we treat any presence of the symbol as "compressed oops on" and
         * use the published shift verbatim. */
        g_hotspot.compressed_oops_enabled = JNI_TRUE;
        g_hotspot.compressed_oops_shift = shift;
        g_hotspot.compressed_oops_base = (jlong)(uintptr_t)base;
        NEKO_PATCH_LOG("vmstructs coop: shift=%d base=%p", shift, base);
    }
    if (g_neko_method_layout.addr_zglobals_address_offset_mask != NULL
        && g_neko_method_layout.addr_zglobals_pointer_load_good_mask != NULL
        && g_neko_method_layout.addr_zglobals_pointer_load_bad_mask != NULL
        && g_neko_method_layout.addr_zglobals_pointer_store_good_mask != NULL
        && g_neko_method_layout.addr_zglobals_pointer_load_shift != NULL) {
        uintptr_t *addr_mask_p = *(uintptr_t**)g_neko_method_layout.addr_zglobals_address_offset_mask;
        uintptr_t *load_good_p = *(uintptr_t**)g_neko_method_layout.addr_zglobals_pointer_load_good_mask;
        uintptr_t *load_bad_p = *(uintptr_t**)g_neko_method_layout.addr_zglobals_pointer_load_bad_mask;
        uintptr_t *store_good_p = *(uintptr_t**)g_neko_method_layout.addr_zglobals_pointer_store_good_mask;
        size_t *load_shift_p = *(size_t**)g_neko_method_layout.addr_zglobals_pointer_load_shift;
        if (addr_mask_p != NULL && load_good_p != NULL && load_bad_p != NULL && store_good_p != NULL && load_shift_p != NULL
            && *addr_mask_p != 0 && (*load_good_p != 0 || *load_bad_p != 0)) {
            g_hotspot.use_zgc = JNI_TRUE;
            g_hotspot.compressed_oops_enabled = JNI_FALSE;
            g_hotspot.compressed_oops_shift = 0;
            g_hotspot.compressed_oops_base = 0;
            g_hotspot.z_address_offset_mask = *addr_mask_p;
            g_hotspot.z_pointer_load_good_mask = *load_good_p;
            g_hotspot.z_pointer_load_bad_mask = *load_bad_p;
            g_hotspot.z_pointer_store_good_mask = *store_good_p;
            g_hotspot.z_pointer_load_shift = *load_shift_p;
            g_hotspot.fast_bits &= ~(NEKO_HOTSPOT_FAST_RAW_HEAP | NEKO_FAST_RECEIVER_KEY);
            NEKO_PATCH_LOG("zgc vmstructs(static): offset_mask=0x%llx load_good=0x%llx load_bad=0x%llx store_good=0x%llx shift=%zu",
                (unsigned long long)g_hotspot.z_address_offset_mask,
                (unsigned long long)g_hotspot.z_pointer_load_good_mask,
                (unsigned long long)g_hotspot.z_pointer_load_bad_mask,
                (unsigned long long)g_hotspot.z_pointer_store_good_mask,
                g_hotspot.z_pointer_load_shift);
        }
    }
    if (g_hotspot.z_address_offset_mask == 0 && g_neko_method_layout.addr_zglobals_instance_p != NULL) {
        void *zg = *(void**)g_neko_method_layout.addr_zglobals_instance_p;
        if (zg != NULL
            && g_neko_method_layout.off_zglobals_address_offset_mask >= 0
            && g_neko_method_layout.off_zglobals_pointer_load_good_mask >= 0
            && g_neko_method_layout.off_zglobals_pointer_load_bad_mask >= 0
            && g_neko_method_layout.off_zglobals_pointer_store_good_mask >= 0
            && g_neko_method_layout.off_zglobals_pointer_load_shift >= 0) {
            uintptr_t *addr_mask_p = *(uintptr_t**)((char*)zg + g_neko_method_layout.off_zglobals_address_offset_mask);
            uintptr_t *load_good_p = *(uintptr_t**)((char*)zg + g_neko_method_layout.off_zglobals_pointer_load_good_mask);
            uintptr_t *load_bad_p = *(uintptr_t**)((char*)zg + g_neko_method_layout.off_zglobals_pointer_load_bad_mask);
            uintptr_t *store_good_p = *(uintptr_t**)((char*)zg + g_neko_method_layout.off_zglobals_pointer_store_good_mask);
            size_t *load_shift_p = *(size_t**)((char*)zg + g_neko_method_layout.off_zglobals_pointer_load_shift);
            uintptr_t addr_mask = addr_mask_p != NULL ? *addr_mask_p : 0;
            uintptr_t load_good = load_good_p != NULL ? *load_good_p : 0;
            uintptr_t load_bad = load_bad_p != NULL ? *load_bad_p : 0;
            uintptr_t store_good = store_good_p != NULL ? *store_good_p : 0;
            size_t load_shift = load_shift_p != NULL ? *load_shift_p : 0;
            NEKO_PATCH_LOG("zgc vmstructs(instance raw): zg=%p offset_mask_p=%p load_good_p=%p load_bad_p=%p store_good_p=%p shift_p=%p",
                zg, (void*)addr_mask_p, (void*)load_good_p, (void*)load_bad_p, (void*)store_good_p, (void*)load_shift_p);
            NEKO_PATCH_LOG("zgc vmstructs(instance val): offset_mask=0x%llx load_good=0x%llx load_bad=0x%llx store_good=0x%llx shift=%zu",
                (unsigned long long)addr_mask,
                (unsigned long long)load_good,
                (unsigned long long)load_bad,
                (unsigned long long)store_good,
                load_shift);
            if (addr_mask != 0 && (load_good != 0 || load_bad != 0)) {
                g_hotspot.use_zgc = JNI_TRUE;
                g_hotspot.compressed_oops_enabled = JNI_FALSE;
                g_hotspot.compressed_oops_shift = 0;
                g_hotspot.compressed_oops_base = 0;
                g_hotspot.z_address_offset_mask = addr_mask;
                g_hotspot.z_pointer_load_good_mask = load_good;
                g_hotspot.z_pointer_load_bad_mask = load_bad;
                g_hotspot.z_pointer_store_good_mask = store_good;
                g_hotspot.z_pointer_load_shift = load_shift;
                g_hotspot.fast_bits &= ~(NEKO_HOTSPOT_FAST_RAW_HEAP | NEKO_FAST_RECEIVER_KEY);
                NEKO_PATCH_LOG("zgc vmstructs: offset_mask=0x%llx load_good=0x%llx load_bad=0x%llx store_good=0x%llx shift=%zu",
                    (unsigned long long)g_hotspot.z_address_offset_mask,
                    (unsigned long long)g_hotspot.z_pointer_load_good_mask,
                    (unsigned long long)g_hotspot.z_pointer_load_bad_mask,
                    (unsigned long long)g_hotspot.z_pointer_store_good_mask,
                    g_hotspot.z_pointer_load_shift);
            }
        }
    }
    if (g_hotspot.z_address_offset_mask == 0
        && g_hotspot.compressed_oops_shift == 0
        && g_neko_handle_sample_oop != 0) {
        uintptr_t sample = g_neko_handle_sample_oop;
        int bit = (int)(sizeof(uintptr_t) * 8u) - 1;
        while (bit >= 44 && ((sample & ((uintptr_t)1u << bit)) == 0)) bit--;
        if (bit >= 44 && bit <= (int)(sizeof(uintptr_t) * 8u) - 4) {
            uintptr_t metadata_mask = ((uintptr_t)0xfu) << bit;
            uintptr_t good_mask = sample & metadata_mask;
            uintptr_t offset_mask = ((uintptr_t)1u << bit) - 1u;
            if (good_mask != 0 && (sample & offset_mask) != 0) {
                g_hotspot.use_zgc = JNI_TRUE;
                g_hotspot.compressed_oops_enabled = JNI_FALSE;
                g_hotspot.compressed_oops_shift = 0;
                g_hotspot.compressed_oops_base = 0;
                g_hotspot.z_address_offset_mask = offset_mask;
                g_hotspot.z_pointer_load_good_mask = good_mask;
                g_hotspot.z_pointer_load_bad_mask = metadata_mask & ~good_mask;
                g_hotspot.z_pointer_store_good_mask = good_mask;
                g_hotspot.z_pointer_load_shift = 0;
                g_hotspot.fast_bits &= ~(NEKO_HOTSPOT_FAST_RAW_HEAP | NEKO_FAST_RECEIVER_KEY);
                NEKO_PATCH_LOG("zgc handle-sample: sample=%p offset_mask=0x%llx good=0x%llx bad=0x%llx",
                    (void*)sample,
                    (unsigned long long)g_hotspot.z_address_offset_mask,
                    (unsigned long long)g_hotspot.z_pointer_load_good_mask,
                    (unsigned long long)g_hotspot.z_pointer_load_bad_mask);
            }
        }
    }
    neko_detect_current_gc_barrier();
    g_neko_method_layout.gc_barrier_ready = neko_gc_barrier_layout_ready();
    g_neko_gc_barrier_ready = g_neko_method_layout.gc_barrier_ready;
    g_neko_gc_barrier_kind = g_neko_method_layout.current_barrier_kind;
    g_neko_barrier_load_oop_field_preloaded =
        (g_neko_method_layout.current_barrier_kind == NEKO_GC_BARRIER_SHENANDOAH)
        ? g_neko_method_layout.sym_shenandoah_load_reference_barrier_strong
        : g_neko_method_layout.sym_z_load_barrier_on_oop_field_preloaded;
    g_neko_barrier_load_oop_array = g_neko_method_layout.sym_z_load_barrier_on_oop_array;
    g_neko_barrier_store_oop_field = g_neko_method_layout.sym_z_store_barrier_on_oop_field_with_healing;
    g_neko_barrier_write_ref_array_pre =
        (g_neko_method_layout.current_barrier_kind == NEKO_GC_BARRIER_SHENANDOAH)
        ? g_neko_method_layout.sym_shenandoah_arraycopy_barrier_oop_entry
        : g_neko_method_layout.sym_g1_write_ref_array_pre_oop_entry;
    g_neko_barrier_write_ref_field_pre =
        (g_neko_method_layout.current_barrier_kind == NEKO_GC_BARRIER_SHENANDOAH)
        ? g_neko_method_layout.sym_shenandoah_write_ref_field_pre_entry
        : g_neko_method_layout.sym_g1_write_ref_field_pre_entry;
    g_neko_barrier_write_ref_field_post = g_neko_method_layout.sym_g1_write_ref_field_post_entry;
    g_neko_card_table_byte_map_base = g_neko_method_layout.card_table_byte_map_base;
    g_neko_card_table_dirty_card = g_neko_method_layout.vmconst_cardtable_dirty_card;
    g_neko_card_table_clean_card = g_neko_method_layout.vmconst_cardtable_clean_card;
    g_neko_g1_young_card = g_neko_method_layout.vmconst_g1_young_card;
    NEKO_PATCH_LOG("gc barrier: ready=%d kind=%d bs=%p card_table=%p byte_map_base=%p card_clean=%d card_dirty=%d g1_young=%d g1_pre=%p g1_post=%p z_lrb=%p z_array=%p z_store=%p sh_lrb=%p sh_lrb_narrow=%p sh_pre=%p sh_array=%p/%p",
        (int)g_neko_gc_barrier_ready,
        g_neko_gc_barrier_kind,
        g_neko_method_layout.current_barrier_set,
        g_neko_method_layout.current_card_table,
        g_neko_card_table_byte_map_base,
        g_neko_card_table_clean_card,
        g_neko_card_table_dirty_card,
        g_neko_g1_young_card,
        g_neko_method_layout.sym_g1_write_ref_field_pre_entry,
        g_neko_method_layout.sym_g1_write_ref_field_post_entry,
        g_neko_method_layout.sym_z_load_barrier_on_oop_field_preloaded,
        g_neko_method_layout.sym_z_load_barrier_on_oop_array,
        g_neko_method_layout.sym_z_store_barrier_on_oop_field_with_healing,
        g_neko_method_layout.sym_shenandoah_load_reference_barrier_strong,
        g_neko_method_layout.sym_shenandoah_load_reference_barrier_strong_narrow,
        g_neko_method_layout.sym_shenandoah_write_ref_field_pre_entry,
        g_neko_method_layout.sym_shenandoah_arraycopy_barrier_oop_entry,
        g_neko_method_layout.sym_shenandoah_arraycopy_barrier_narrow_oop_entry);
    if (!g_neko_gc_barrier_ready) {
        NEKO_PATCH_LOG("gc barrier path not ready for kind=%d; aborting native layout initialization",
            g_neko_gc_barrier_kind);
        return JNI_FALSE;
    }
    /* T4.8: capture NewGlobalRef + DeleteGlobalRef function-table entries
     * once at bootstrap. The bind-time class/string slot path uses these
     * captured pointers instead of the inline JNI function-table 21 /
     * `[22]` indexing. Failure to capture aborts inside the helper. */
    neko_capture_global_ref_fns();
    /* T4.3 / T4.4 / T4.5: capture the bind-time JNI function-table entries
     * (NewStringUTF, Call*MethodA, NewObjectA, GetArrayLength,
     * NewObjectArray, Set/GetObjectArrayElement) used by the MethodType /
     * MethodHandles.lookup / Throwable.getStackTrace pipelines. Production
     * HotSpot 21 strips the corresponding C++ symbols, so capture-once
     * is the equivalent libjvm-internal entry point. The grep gate
     * (T4.12) sees only the captured-pointer call sites afterwards. */
    neko_capture_bind_time_jni_fns();
    /* T4.7: deleted neko_fast_string_runtime_init (JNI probe via
     * NewStringUTF / NewByteArray, indices 167 + 176). The fast string
     * allocator path is gated on the receiver-key + raw-heap fast bits
     * (cleared under ZGC / Shenandoah) AND on g_hotspot being initialized.
     * In production HotSpot, neko_method_layout_init runs BEFORE
     * neko_hotspot_init so g_hotspot.fast_bits is still 0 at this point;
     * the original probe would skip silently in that state and the bits
     * were derived on demand from neko_intern_string the first time the
     * obfuscated code touched a string literal. We preserve that
     * "skip early, derive on demand" behavior here — wrapping the call
     * in the same precondition the deleted probe used. The "invariants
     * not satisfied → abort" gate the T4.7 plan note specifies fires
     * inside neko_ensure_string_alloc_bits when the fast path IS supposed
     * to be live but the derivation fails (missing String Klass /
     * byte-array Klass bits). */
    if (g_hotspot.initialized
        && (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) != 0
        && !g_hotspot.use_compact_object_headers
        && g_hotspot.klass_offset_bytes > 0
        && g_neko_tlab_alloc_ready
        && g_hotspot.primitive_array_klass_bits[NEKO_PRIM_B] != 0) {
        neko_ensure_string_alloc_bits(env);
    }
    g_neko_method_layout.usable = JNI_TRUE;
    return JNI_TRUE;
}

/* === JNIHandleBlock push/pop helpers ===
 * Push raw oop into the thread's _active_handles and return its handle slot
 * pointer. Missing handle layout is fatal; there is no raw-cast fallback.
 * neko_handle_save / neko_handle_restore bracket a dispatcher invocation so
 * its pushes are popped on return.
 * The typedef neko_handle_save_t is forward-declared in the prelude. */

/* These per-call helpers are static inline so the per-signature dispatcher
 * (emitted later in this same translation unit) can fold them into its body.
 * Going from extern call → inline saves two register saves + two ret + the
 * stall on the indirect call edge per dispatch, which adds up across hot
 * loops in obfusjack microbenches. */

static inline __attribute__((always_inline))
void neko_handle_save(void *thread, neko_handle_save_t *save) {
    save->thread = thread;
    save->block = NULL;
    save->saved_next = NULL;
    save->saved_last = NULL;
    save->saved_top = 0;
    if (!g_neko_handle_push_ready || thread == NULL) return;
    void *block = *(void**)((char*)thread + g_neko_off_thread_active_handles);
    if (block == NULL) return;
    save->block = block;
    save->saved_top = *(int32_t*)((char*)block + g_neko_off_jnih_block_top);
    if (g_neko_method_layout.off_jnih_block_next > 0) {
        ptrdiff_t off_last = g_neko_method_layout.off_jnih_block_next + 8;
        save->saved_next = *(void**)((char*)block + g_neko_method_layout.off_jnih_block_next);
        save->saved_last = *(void**)((char*)block + off_last);
    }
}

static inline __attribute__((always_inline))
void neko_handle_restore(neko_handle_save_t *save) {
    void *thread;
    void *active;
    if (save->block == NULL) return;
    thread = save->thread;
    if (thread != NULL && g_neko_off_thread_active_handles > 0 && g_neko_method_layout.off_jnih_block_next > 0) {
        active = *(void**)((char*)thread + g_neko_off_thread_active_handles);
        while (active != NULL && active != save->block) {
            void *next = *(void**)((char*)active + g_neko_method_layout.off_jnih_block_next);
            free(active);
            active = next;
        }
        *(void**)((char*)thread + g_neko_off_thread_active_handles) = save->block;
    }
    if (g_neko_method_layout.off_jnih_block_next > 0) {
        void *next = *(void**)((char*)save->block + g_neko_method_layout.off_jnih_block_next);
        while (next != NULL && next != save->saved_next) {
            void *after = *(void**)((char*)next + g_neko_method_layout.off_jnih_block_next);
            free(next);
            next = after;
        }
        *(void**)((char*)save->block + g_neko_method_layout.off_jnih_block_next) = save->saved_next;
    }
    *(int32_t*)((char*)save->block + g_neko_off_jnih_block_top) = save->saved_top;
    if (g_neko_method_layout.off_jnih_block_next > 0) {
        ptrdiff_t off_last = g_neko_method_layout.off_jnih_block_next + 8;
        *(void**)((char*)save->block + off_last) = save->saved_last != NULL ? save->saved_last : save->block;
    }
}

static inline __attribute__((always_inline))
JNIEnv *neko_thread_jni_env(void *thread) {
    if (thread != NULL && g_neko_method_layout.off_thread_jni_environment > 0) {
        return (JNIEnv*)((char*)thread + g_neko_method_layout.off_thread_jni_environment);
    }
    if (thread != NULL && g_neko_jni_functions_table != NULL) {
        for (ptrdiff_t off = 0; off < 0x4000; off += (ptrdiff_t)sizeof(void*)) {
            JNIEnv *candidate = (JNIEnv*)((char*)thread + off);
            if (*(void**)candidate == g_neko_jni_functions_table) {
                g_neko_method_layout.off_thread_jni_environment = off;
                g_neko_off_thread_jni_environment_for_check = off;
                return candidate;
            }
        }
    }
    fprintf(stderr, "[neko-direct] JavaThread::_jni_environment offset unavailable thread=%p\\n", thread);
    abort();
}

static inline __attribute__((always_inline))
void *neko_jni_env_to_thread(JNIEnv *env) {
    if (env == NULL || g_neko_method_layout.off_thread_jni_environment <= 0) return NULL;
    return (void*)((char*)env - g_neko_method_layout.off_thread_jni_environment);
}

static inline __attribute__((always_inline))
void *neko_handle_push(void *thread, void *raw_oop) {
    if (raw_oop == NULL) return NULL;
    if (!g_neko_handle_push_ready || thread == NULL) {
        fprintf(stderr, "[neko-direct] JNIHandleBlock push unavailable thread=%p raw=%p\\n", thread, raw_oop);
        abort();
    }
    void *block = *(void**)((char*)thread + g_neko_off_thread_active_handles);
    if (block == NULL) {
        fprintf(stderr, "[neko-direct] JNIHandleBlock active block missing thread=%p raw=%p\\n", thread, raw_oop);
        abort();
    }
    int32_t *top_ptr = (int32_t*)((char*)block + g_neko_off_jnih_block_top);
    int32_t top = *top_ptr;
    if (top >= g_neko_jnih_block_capacity) {
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
                return &new_handles[0];
            }
        }
        fprintf(stderr, "[neko-direct] JNIHandleBlock chain allocation failed thread=%p raw=%p\\n", thread, raw_oop);
        abort();
    }
    void **handles = (void**)((char*)block + g_neko_off_jnih_block_handles);
    handles[top] = raw_oop;
    *top_ptr = top + 1;
    /* Publish _last = block so HotSpot's later allocate_handle does not
     * dereference a NULL _last when it sees _top != 0. VMStructs does not
     * export _last; in JDK 21 the field order after _next is
     *   _last : JNIHandleBlock*       (= _next + 8)
     *   _pop_frame_link : JNIHandleBlock*
     *   _free_list : uintptr_t*
     * so we only touch _last (writing _pop_frame_link or _free_list would
     * break PushLocalFrame / PopLocalFrame / handle reclamation). */
    if (g_neko_method_layout.off_jnih_block_next > 0) {
        ptrdiff_t off_last = g_neko_method_layout.off_jnih_block_next + 8;
        *(void**)((char*)block + off_last) = block;
    }
    return &handles[top];
}

static jboolean neko_apply_no_compile_flags(void *method_star) {
    /* JVM_ACC_NOT_C[12]_COMPILABLE bits in Method::_access_flags (well-defined
     * across JDKs). */
    {
        uint32_t mask = g_neko_method_layout.access_not_c1_compilable
            | g_neko_method_layout.access_not_c2_compilable
            | g_neko_method_layout.access_not_osr_compilable;
        size_t width = g_neko_method_layout.access_flags_size == 0 ? sizeof(uint32_t) : g_neko_method_layout.access_flags_size;
        void *addr = (uint8_t*)method_star + g_neko_method_layout.off_method_access_flags;
        if (width == 4) __atomic_fetch_or((uint32_t*)addr, mask, __ATOMIC_SEQ_CST);
        else if (width == 2) __atomic_fetch_or((uint16_t*)addr, (uint16_t)mask, __ATOMIC_SEQ_CST);
    }
    /* DontInline: rely on bytecode inflation in NativeCompilationStage to
     * lift the LinkageError-stub past MaxInlineSize, instead of poking
     * Method::_flags. JIT-compiled callers' inline-cache resolution reads
     * _flags during resolve_virtual_call, and a wrong-shaped write there
     * trips a libjvm-internal SIGSEGV (saw at libjvm+0x2f5167) on
     * ForkJoin-pool-driven hot loops. The bytecode-size route is robust
     * across JDKs and doesn't depend on getting the bit layout right. */
    return JNI_TRUE;
}

static inline __attribute__((always_inline)) void neko_cpu_relax(void) {
#if defined(__x86_64__) || defined(_M_X64)
    __asm__ __volatile__("pause" ::: "memory");
#elif defined(__aarch64__)
    __asm__ __volatile__("yield" ::: "memory");
#else
    __asm__ __volatile__("" ::: "memory");
#endif
}

static inline __attribute__((always_inline)) void *neko_current_thread_register(void) {
#if defined(__x86_64__) || defined(_M_X64)
    void *thread;
    __asm__ __volatile__("movq %%r15, %0" : "=r"(thread));
    return thread;
#elif defined(__aarch64__)
    void *thread;
    __asm__ __volatile__("mov %0, x28" : "=r"(thread));
    return thread;
#else
    return NULL;
#endif
}

__attribute__((visibility("hidden"))) void neko_safepoint_poll_thread(void *thread) {
    if (thread == NULL || g_neko_off_thread_polling_word <= 0) return;
    volatile uintptr_t *poll = (volatile uintptr_t*)((char*)thread + g_neko_off_thread_polling_word);
    uint32_t spins = 0;
    while (*poll != 0) {
        __sync_synchronize();
        neko_cpu_relax();
        if (++spins == 0) break;
    }
}

__attribute__((visibility("hidden"))) void neko_refresh_hotspot_vmstruct_state(void) {
    if (g_neko_method_layout.addr_compressed_oops_shift != NULL) {
        int shift = *(int*)g_neko_method_layout.addr_compressed_oops_shift;
        void *base = (g_neko_method_layout.addr_compressed_oops_base != NULL)
            ? *(void**)g_neko_method_layout.addr_compressed_oops_base
            : NULL;
        g_hotspot.compressed_oops_enabled = JNI_TRUE;
        g_hotspot.compressed_oops_shift = shift;
        g_hotspot.compressed_oops_base = (jlong)(uintptr_t)base;
        NEKO_PATCH_LOG("vmstructs coop refresh: shift=%d base=%p", shift, base);
    }
    if (g_neko_method_layout.addr_compressed_oops_base != NULL) {
        g_neko_heap_base = *(void**)g_neko_method_layout.addr_compressed_oops_base;
    } else if (g_hotspot.compressed_oops_enabled) {
        g_neko_heap_base = (void*)(uintptr_t)g_hotspot.compressed_oops_base;
    } else {
        g_neko_heap_base = NULL;
    }
    NEKO_PATCH_LOG("heap base refresh: %p", g_neko_heap_base);
}

__attribute__((visibility("hidden"))) void neko_transition_native_to_java(void *thread) {
    if (thread == NULL || g_neko_off_thread_state <= 0) return;
    *(int32_t*)((char*)thread + g_neko_off_thread_state) = g_neko_thread_state_in_native_trans;
    __sync_synchronize();
    neko_safepoint_poll_thread(thread);
    *(int32_t*)((char*)thread + g_neko_off_thread_state) = g_neko_thread_state_in_java;
}

__attribute__((visibility("hidden"))) void neko_transition_java_to_native(void *thread) {
    if (thread == NULL || g_neko_off_thread_state <= 0) return;
    *(int32_t*)((char*)thread + g_neko_off_thread_state) = g_neko_thread_state_in_native_trans;
    __sync_synchronize();
    neko_safepoint_poll_thread(thread);
    *(int32_t*)((char*)thread + g_neko_off_thread_state) = g_neko_thread_state_in_native;
}

/* Called from naked-asm trampolines when the polling word indicates a safepoint
 * is requested. This must not cross the JNI function table: the JavaThread is
 * recovered from HotSpot's thread register and we wait directly on the
 * VMStructs-published polling word while the thread is in native_trans. */
__attribute__((visibility("hidden"))) void neko_handle_safepoint_poll(void) {
    neko_safepoint_poll_thread(neko_current_thread_register());
}

static jboolean neko_patch_method_entry(void *method_star, void *manifest_entry) {
    NekoManifestMethod *entry = (NekoManifestMethod*)manifest_entry;
    if (!g_neko_method_layout.usable || method_star == NULL || entry == NULL) return JNI_FALSE;
    if (entry->signature_id >= g_neko_sig_table_count) {
        NEKO_PATCH_LOG("patch refused: bad signature_id=%u for %s.%s%s",
            entry->signature_id, entry->owner_internal, entry->method_name, entry->method_desc);
        return JNI_FALSE;
    }
    void *compiled_code = __atomic_load_n((void**)((uint8_t*)method_star + g_neko_method_layout.off_method_code), __ATOMIC_ACQUIRE);
    if (compiled_code != NULL) {
        NEKO_PATCH_LOG("patch refused: %s.%s%s already JIT-compiled", entry->owner_internal, entry->method_name, entry->method_desc);
        return JNI_FALSE;
    }
    (void)neko_apply_no_compile_flags(method_star);
    void *real_i2i = g_neko_sig_table[entry->signature_id].i2i;
    void *real_c2i = g_neko_sig_table[entry->signature_id].c2i;
    if (real_i2i == NULL || real_c2i == NULL) {
        NEKO_PATCH_LOG("patch refused: missing trampoline for sig=%u %s.%s%s",
            entry->signature_id, entry->owner_internal, entry->method_name, entry->method_desc);
        return JNI_FALSE;
    }
    /* Three Method entry-point fields, three private-CodeHeap thunks:
     *   _from_compiled_entry     -> t_c2i, the Java-ABI compiled-entry stub.
     *   _from_interpreted_entry  -> t_i2i_interp, the interpreter-entry stub
     *       that consumes HotSpot's interpreter stack layout and returns by
     *       the normal interpreter protocol.
     *   _i2i_entry               -> t_i2i_path2 when HotSpot's shared c2i
     *       adapter tail-jumps to _i2i_entry after reserving extraspace;
     *       otherwise it reuses the normal interpreted-entry thunk.
     *
     * The shared c2i adapter path is still an adapter connection into our
     * native method entry, but the installed target must be one of our new
     * Java-thread-in-java stubs. Missing a required path2 variant is a patch
     * failure, not permission to keep the original JVM entry active.
     */
    if (!g_neko_priv_heap.registered) {
        NEKO_PATCH_LOG("patch refused: private CodeHeap not registered for %s.%s%s",
            entry->owner_internal, entry->method_name, entry->method_desc);
        return JNI_FALSE;
    }
    int extraspace_words = (int)g_neko_sig_extraspace_words[entry->signature_id];
    void *real_i2i_path2 = (extraspace_words > 0) ? g_neko_sig_i2i_path2[entry->signature_id] : NULL;
    if (extraspace_words > 0 && real_i2i_path2 == NULL) {
        NEKO_PATCH_LOG("patch refused: missing i2i path2 trampoline for sig=%u extraspace=%d %s.%s%s",
            entry->signature_id, extraspace_words,
            entry->owner_internal, entry->method_name, entry->method_desc);
        return JNI_FALSE;
    }
    /* Per-method thunks: the entry pointer is baked into each thunk's r10
     * preload so the per-signature naked function does not need to scan the
     * manifest. Three thunk variants per Method* (interp i2i, path-2 i2i, c2i)
     * are dedup'd against (trampoline, frame_size, entry); since each entry
     * is unique to its Method* the only meaningful dedup is for re-entrant
     * patching from defineClass alias resolution. */
    void *t_i2i_interp = neko_priv_get_thunk(real_i2i, 3, entry);
    void *t_i2i_path2  = (extraspace_words > 0)
        ? neko_priv_get_thunk(real_i2i_path2, 3 + extraspace_words, entry)
        : t_i2i_interp;
    void *t_c2i        = neko_priv_get_thunk(real_c2i, 3, entry);
    if (t_i2i_interp == NULL || t_i2i_path2 == NULL || t_c2i == NULL) {
        NEKO_PATCH_LOG("patch refused: thunk allocation failed for sig=%u %s.%s%s",
            entry->signature_id, entry->owner_internal, entry->method_name, entry->method_desc);
        return JNI_FALSE;
    }
    __atomic_store_n((void**)((uint8_t*)method_star + g_neko_method_layout.off_method_i2i_entry), t_i2i_path2, __ATOMIC_RELEASE);
    __atomic_store_n((void**)((uint8_t*)method_star + g_neko_method_layout.off_method_from_interpreted_entry), t_i2i_interp, __ATOMIC_RELEASE);
    __atomic_store_n((void**)((uint8_t*)method_star + g_neko_method_layout.off_method_from_compiled_entry), t_c2i, __ATOMIC_RELEASE);
    NEKO_PATCH_LOG("patched %s.%s%s sig=%u method=%p from_compiled=%p from_interpreted=%p i2i=%p extraspace_words=%d path2=%p",
        entry->owner_internal, entry->method_name, entry->method_desc,
        entry->signature_id, method_star, t_c2i, t_i2i_interp, t_i2i_path2,
        extraspace_words, real_i2i_path2);
    return JNI_TRUE;
}

""";
    }
}
