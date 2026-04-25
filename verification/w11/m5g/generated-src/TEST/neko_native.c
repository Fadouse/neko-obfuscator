#if defined(__linux__)
#define _GNU_SOURCE
#endif
#include "neko_native.h"
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <math.h>

#if defined(_WIN32)
#include <windows.h>
#include <psapi.h>
#else
#include <dlfcn.h>
#endif
#if defined(__linux__)
#include <link.h>
#endif
#if defined(__APPLE__)
#include <mach-o/dyld.h>
#endif

// === Global resolution caches ===
typedef struct neko_icache_site {
    uintptr_t receiver_key;
    void* target;
    uint8_t target_kind;
    uint8_t _pad0;
    uint16_t miss_count;
    uint32_t _pad1;
    jclass cached_class;
} neko_icache_site;

#define NEKO_ICACHE_EMPTY 0u
#define NEKO_ICACHE_DIRECT_C 1u
#define NEKO_ICACHE_NONVIRT_MID 2u
#define NEKO_ICACHE_MEGA 3u
#define NEKO_ICACHE_MEGA_THRESHOLD 16u

static jclass g_cls_0 = NULL;   // pack/tests/basics/cross/Abst1
static jclass g_cls_1 = NULL;   // pack/tests/basics/cross/Top
static jclass g_cls_2 = NULL;   // pack/tests/basics/inner/Exec$Inner
static jclass g_cls_3 = NULL;   // pack/tests/basics/inner/Exec
static jclass g_cls_4 = NULL;   // pack/tests/basics/overwirte/Sub
static jclass g_cls_5 = NULL;   // pack/tests/basics/runable/Task
static jclass g_cls_6 = NULL;   // pack/tests/basics/runable/Exec
static jclass g_cls_7 = NULL;   // pack/tests/basics/sub/flo
static jclass g_cls_8 = NULL;   // pack/tests/bench/Calc
static jclass g_cls_9 = NULL;   // pack/tests/reflects/counter/Countee
static jclass g_cls_10 = NULL;   // pack/tests/reflects/field/FObject
static jmethodID g_mid_0 = NULL;   // pack/tests/basics/inner/Exec.addF()V/V
static jmethodID g_mid_1 = NULL;   // pack/tests/basics/runable/Exec.doAdd()V/V
static jboolean g_owner_bound_0 = JNI_FALSE;   // pack/tests/basics/cross/Abst1
static jboolean g_owner_bound_1 = JNI_FALSE;   // pack/tests/basics/cross/Top
static jboolean g_owner_bound_2 = JNI_FALSE;   // pack/tests/basics/inner/Exec$Inner
static jboolean g_owner_bound_3 = JNI_FALSE;   // pack/tests/basics/inner/Exec
static jboolean g_owner_bound_4 = JNI_FALSE;   // pack/tests/basics/overwirte/Sub
static jboolean g_owner_bound_5 = JNI_FALSE;   // pack/tests/basics/runable/Task
static jboolean g_owner_bound_6 = JNI_FALSE;   // pack/tests/basics/sub/flo
static jboolean g_owner_bound_7 = JNI_FALSE;   // pack/tests/bench/Calc
static jboolean g_owner_bound_8 = JNI_FALSE;   // pack/tests/reflects/counter/Countee
static jboolean g_owner_bound_9 = JNI_FALSE;   // pack/tests/reflects/field/FObject
static neko_icache_site neko_icache_2_0_0 = {0};   // pack/tests/basics/inner/Exec$Inner :: pack/tests/basics/inner/Exec$Inner#doAdd()V [site 0]
static neko_icache_site neko_icache_5_1_0 = {0};   // pack/tests/basics/runable/Task :: pack/tests/basics/runable/Task#lambda$run$0(Lpack/tests/basics/runable/Exec;)V [site 0]

#define NEKO_ENSURE_CLASS(slot, env, name) ((slot) != NULL ? (slot) : ((slot) = (jclass)neko_new_global_ref((env), neko_find_class((env), (name)))))
#define NEKO_ENSURE_STRING(slot, env, utf) ((slot) != NULL ? (slot) : ((slot) = (jstring)neko_new_global_ref((env), neko_new_string_utf((env), (utf)))))
#define NEKO_ENSURE_METHOD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_method_id((env), (cls), (name), (desc))))
#define NEKO_ENSURE_STATIC_METHOD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_static_method_id((env), (cls), (name), (desc))))
#define NEKO_ENSURE_FIELD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_field_id((env), (cls), (name), (desc))))
#define NEKO_ENSURE_STATIC_FIELD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_static_field_id((env), (cls), (name), (desc))))

#define NEKO_MANIFEST_FLAG_STATIC 0x01u
#define NEKO_MANIFEST_FLAG_LEAF_ONLY 0x02u
#define NEKO_PATCH_STATE_NONE 0u
#define NEKO_PATCH_STATE_APPLIED 1u
#define NEKO_PATCH_STATE_FAILED 2u
#define NEKO_FIELD_SITE_UNRESOLVED ((ptrdiff_t)-1)
#define NEKO_FIELD_SITE_FAILED ((ptrdiff_t)-2)
#define NEKO_LDC_KIND_STRING 1u
#define NEKO_LDC_KIND_CLASS 2u
#define NEKO_LDC_KIND_METHOD_HANDLE 3u
#define NEKO_LDC_KIND_METHOD_TYPE 4u
#define NEKO_MANIFEST_STORAGE_COUNT 14u

#define NEKO_SIGNATURE_STORAGE_COUNT 5u
#define NEKO_SIGNATURE_MAX_ARGS 2u

typedef struct {
    uint8_t return_kind;
    uint8_t arg_count;
    uint8_t arg_kinds[NEKO_SIGNATURE_MAX_ARGS];
} NekoSignatureDescriptor;

typedef struct NekoManifestFieldSite {
    uint32_t owner_class_index;
    const char* owner_internal;
    const char* field_name;
    const char* field_desc;
    uint8_t is_static;
    uint8_t is_reference;
    uint8_t is_volatile;
    uint8_t _pad0;
    void* cached_klass;
    ptrdiff_t field_offset_cookie;
    ptrdiff_t resolved_offset;
} NekoManifestFieldSite;

typedef struct NekoManifestInvokeSite {
    const char* owner_internal;
    const char* method_name;
    const char* method_desc;
    uint8_t opcode;
    uint8_t is_interface;
    uint16_t signature_id;
    void* resolved_method;
} NekoManifestInvokeSite;

typedef struct NekoManifestLdcSite {
    uint32_t site_id;
    uint32_t owner_class_index;
    uint8_t kind;
    uint8_t _pad0;
    uint16_t _pad1;
    const uint8_t* raw_constant_utf8;
    size_t raw_constant_utf8_len;
    void* cached_klass;
    void* resolved_cache_handle;
} NekoManifestLdcSite;

typedef struct {
    const char* owner_internal;
    const char* method_name;
    const char* method_desc;
    uint32_t owner_hash;
    uint32_t name_desc_hash;
    uint16_t flags;
    uint16_t reserved;
    uint32_t signature_id;
    void* impl_fn;
    void* method_star;
    NekoManifestFieldSite* field_sites;
    uint32_t field_site_count;
    uint32_t _pad_field_sites;
    NekoManifestLdcSite* ldc_sites;
    uint32_t ldc_site_count;
    uint32_t _pad_ldc_sites;
} NekoManifestMethod;

_Static_assert(sizeof(NekoManifestMethod) == 88, "unexpected NekoManifestMethod size");
_Static_assert(offsetof(NekoManifestMethod, flags) == 32, "unexpected NekoManifestMethod::flags offset");
_Static_assert(offsetof(NekoManifestMethod, signature_id) == 36, "unexpected NekoManifestMethod::signature_id offset");
_Static_assert(offsetof(NekoManifestMethod, impl_fn) == 40, "unexpected NekoManifestMethod::impl_fn offset");
_Static_assert(offsetof(NekoManifestMethod, method_star) == 48, "unexpected NekoManifestMethod::method_star offset");

static const NekoSignatureDescriptor g_neko_signature_descriptors[NEKO_SIGNATURE_STORAGE_COUNT] = {
    { 'I', 2u, {'I', 'I' } },
    { 'V', 0u, {0u, 0u } },
    { 'L', 1u, {'I', 0u } },
    { 'V', 1u, {'L', 0u } },
    { 'V', 1u, {'I', 0u } }
};
const uint32_t g_neko_signature_descriptor_count = 5u;

extern void neko_sig_0_i2i(void);
extern void neko_sig_0_c2i(void);
extern void neko_sig_1_i2i(void);
extern void neko_sig_1_c2i(void);
extern void neko_sig_2_i2i(void);
extern void neko_sig_2_c2i(void);
extern void neko_sig_3_i2i(void);
extern void neko_sig_3_c2i(void);
extern void neko_sig_4_i2i(void);
extern void neko_sig_4_c2i(void);
static int neko_patch_method(const NekoManifestMethod *entry, void *method_star);
static void neko_patch_discovered_methods(void);
static void neko_resolve_discovered_invoke_sites(const char *owner_internal, const char *name, const char *desc, void *method_star);
static jboolean neko_method_is_redefined_stale(void *method_star);
static jboolean neko_manifest_method_active(uint32_t index);
static void neko_raise_cached_pending(void *thread, jthrowable cached);

static NekoManifestFieldSite g_neko_field_sites_2[5] = {
    { 2u, "pack/tests/basics/inner/Exec$Inner", "this$0", "Lpack/tests/basics/inner/Exec;", 0u, 1u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED },
    { 2u, "pack/tests/basics/inner/Exec$Inner", "this$0", "Lpack/tests/basics/inner/Exec;", 0u, 1u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED },
    { 3u, "pack/tests/basics/inner/Exec", "fuss", "I", 0u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED },
    { 2u, "pack/tests/basics/inner/Exec$Inner", "i", "I", 0u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED },
    { 3u, "pack/tests/basics/inner/Exec", "fuss", "I", 0u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED }
};
static NekoManifestFieldSite g_neko_field_sites_3[2] = {
    { 3u, "pack/tests/basics/inner/Exec", "fuss", "I", 0u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED },
    { 3u, "pack/tests/basics/inner/Exec", "fuss", "I", 0u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED }
};
static const uint8_t g_neko_utf8_4_0[] = {0x50, 0x41, 0x53, 0x53};
static const uint8_t g_neko_utf8_4_1[] = {0x46, 0x41, 0x49, 0x4C};
static NekoManifestLdcSite g_neko_ldc_sites_4[2] = {
    { 0u, 0u, NEKO_LDC_KIND_STRING, 0u, 0u, g_neko_utf8_4_0, 4u, NULL, NULL },
    { 1u, 0u, NEKO_LDC_KIND_STRING, 0u, 0u, g_neko_utf8_4_1, 4u, NULL, NULL }
};
static NekoManifestFieldSite g_neko_field_sites_5[3] = {
    { 6u, "pack/tests/basics/runable/Exec", "i", "I", 1u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED },
    { 6u, "pack/tests/basics/runable/Exec", "i", "I", 1u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED },
    { 6u, "pack/tests/basics/runable/Exec", "i", "I", 1u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED }
};
static NekoManifestFieldSite g_neko_field_sites_7[2] = {
    { 8u, "pack/tests/bench/Calc", "count", "I", 1u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED },
    { 8u, "pack/tests/bench/Calc", "count", "I", 1u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED }
};
static NekoManifestFieldSite g_neko_field_sites_8[2] = {
    { 8u, "pack/tests/bench/Calc", "count", "I", 1u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED },
    { 8u, "pack/tests/bench/Calc", "count", "I", 1u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED }
};
static NekoManifestFieldSite g_neko_field_sites_13[2] = {
    { 10u, "pack/tests/reflects/field/FObject", "i", "I", 0u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED },
    { 10u, "pack/tests/reflects/field/FObject", "i", "I", 0u, 0u, 0u, 0u, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED }
};

static const char* const g_neko_manifest_owners[11] = {
    "pack/tests/basics/cross/Abst1",
    "pack/tests/basics/cross/Top",
    "pack/tests/basics/inner/Exec$Inner",
    "pack/tests/basics/inner/Exec",
    "pack/tests/basics/overwirte/Sub",
    "pack/tests/basics/runable/Task",
    "pack/tests/basics/runable/Exec",
    "pack/tests/basics/sub/flo",
    "pack/tests/bench/Calc",
    "pack/tests/reflects/counter/Countee",
    "pack/tests/reflects/field/FObject"
};
static NekoManifestInvokeSite* const g_neko_manifest_invoke_sites[1] = {
    NULL
};

__attribute__((visibility("hidden"))) const NekoManifestMethod g_neko_manifest_methods[NEKO_MANIFEST_STORAGE_COUNT] = {
    { "pack/tests/basics/cross/Abst1", "mul", "(II)I", 0x72F66A77u, 0x6E5750D5u, 0x0000u, 0u, 0u, (void*)&neko_impl_0, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/basics/cross/Top", "add", "(II)I", 0x8C74266Bu, 0xBF9610D8u, 0x0000u, 0u, 0u, (void*)&neko_impl_1, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/basics/inner/Exec$Inner", "doAdd", "()V", 0x7262A03Du, 0x9CE9992Cu, 0x0000u, 0u, 1u, (void*)&neko_impl_2, NULL, g_neko_field_sites_2, 5u, 0u, NULL, 0u, 0u },
    { "pack/tests/basics/inner/Exec", "addF", "()V", 0xF3DB4BA5u, 0x336CFA79u, 0x0000u, 0u, 1u, (void*)&neko_impl_3, NULL, g_neko_field_sites_3, 2u, 0u, NULL, 0u, 0u },
    { "pack/tests/basics/overwirte/Sub", "face", "(I)Ljava/lang/String;", 0xFB3B66C9u, 0x4A145CAEu, 0x0000u, 0u, 2u, (void*)&neko_impl_4, NULL, NULL, 0u, 0u, g_neko_ldc_sites_4, 2u, 0u },
    { "pack/tests/basics/runable/Task", "lambda$run$0", "(Lpack/tests/basics/runable/Exec;)V", 0xE89CEA96u, 0x61D9F674u, 0x0003u, 0u, 3u, (void*)&neko_impl_5, NULL, g_neko_field_sites_5, 3u, 0u, NULL, 0u, 0u },
    { "pack/tests/basics/sub/flo", "solve", "(II)I", 0x14848AADu, 0x00F0A844u, 0x0000u, 0u, 0u, (void*)&neko_impl_6, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/bench/Calc", "call", "(I)V", 0x422E6F31u, 0x9C7FA9B7u, 0x0003u, 0u, 4u, (void*)&neko_impl_7, NULL, g_neko_field_sites_7, 2u, 0u, NULL, 0u, 0u },
    { "pack/tests/bench/Calc", "runAdd", "()V", 0x422E6F31u, 0xD0BB9C3Au, 0x0003u, 0u, 1u, (void*)&neko_impl_8, NULL, g_neko_field_sites_8, 2u, 0u, NULL, 0u, 0u },
    { "pack/tests/reflects/counter/Countee", "mpuli", "()V", 0x95AA8A28u, 0x2BDE3A87u, 0x0000u, 0u, 1u, (void*)&neko_impl_9, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/reflects/counter/Countee", "mprot", "()V", 0x95AA8A28u, 0xAF9820D6u, 0x0000u, 0u, 1u, (void*)&neko_impl_10, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/reflects/counter/Countee", "mpackp", "()V", 0x95AA8A28u, 0xD1783280u, 0x0000u, 0u, 1u, (void*)&neko_impl_11, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/reflects/counter/Countee", "mpriv", "()V", 0x95AA8A28u, 0xA81F3CC2u, 0x0000u, 0u, 1u, (void*)&neko_impl_12, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/reflects/field/FObject", "add", "()V", 0xC3E90C68u, 0x4C23FD7Bu, 0x0002u, 0u, 1u, (void*)&neko_impl_13, NULL, g_neko_field_sites_13, 2u, 0u, NULL, 0u, 0u }
};
__attribute__((visibility("hidden"))) const uint32_t g_neko_manifest_method_count = 14u;
__attribute__((visibility("hidden"))) void* g_neko_manifest_method_stars[NEKO_MANIFEST_STORAGE_COUNT] = { NULL };

static uint8_t g_neko_manifest_patch_states[NEKO_MANIFEST_STORAGE_COUNT] = { 0u };
static uint32_t g_neko_manifest_patch_count = 0u;

static const uint32_t g_neko_manifest_owner_count = 11u;
static const uint32_t g_neko_manifest_field_site_count = 16u;
static const uint32_t g_neko_manifest_invoke_site_count = 0u;
static const uint32_t g_neko_manifest_ldc_string_site_count = 2u;
static const uint32_t g_neko_manifest_ldc_class_site_count = 0u;

static void* const g_neko_signature_i2i_stubs[NEKO_SIGNATURE_STORAGE_COUNT] = {
    (void*)&neko_sig_0_i2i,
    (void*)&neko_sig_1_i2i,
    (void*)&neko_sig_2_i2i,
    (void*)&neko_sig_3_i2i,
    (void*)&neko_sig_4_i2i
};
static void* const g_neko_signature_c2i_stubs[NEKO_SIGNATURE_STORAGE_COUNT] = {
    (void*)&neko_sig_0_c2i,
    (void*)&neko_sig_1_c2i,
    (void*)&neko_sig_2_c2i,
    (void*)&neko_sig_3_c2i,
    (void*)&neko_sig_4_c2i
};

#define NEKO_STRING_INTERN_SLOT_COUNT 2u
#define NEKO_STRING_INTERN_BUCKET_COUNT 4u

typedef struct NekoStringInternEntry {
    uint32_t coder;            /* 0 LATIN1 / 1 UTF16 (JDK 9+); 1 synthetic UTF16-BE for JDK 8 */
    uint32_t char_length;      /* logical Java char count */
    uint32_t payload_length;   /* bytes backing the key */
    uint32_t slot_index;       /* index into boot CLD handle cell pool */
    const uint8_t* payload;    /* pointer to MUTF-8-derived key bytes or String byte[] contents */
    void* root_cell;           /* stable oop cell published via boot ClassLoaderData::_handles */
    struct NekoStringInternEntry* next;
} NekoStringInternEntry;

static NekoStringInternEntry* g_neko_string_intern_buckets[NEKO_STRING_INTERN_BUCKET_COUNT];
static NekoStringInternEntry g_neko_string_intern_entries[NEKO_STRING_INTERN_SLOT_COUNT];
static uint32_t g_neko_string_intern_filled = 0;

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
    ptrdiff_t off_method_flags;
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
    uint32_t method_flag_is_old;
    uint32_t method_flag_is_obsolete;
    uint32_t method_flag_is_deleted;
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
#define NEKO_THROW_AND_RETURN(env_expr, throwable_expr, ret_expr)     do {         (void)neko_throw_cached((env_expr), (throwable_expr));         return ret_expr;     } while (0)
#define NEKO_THROW_AND_RETURN_VOID(env_expr, throwable_expr)     do {         (void)neko_throw_cached((env_expr), (throwable_expr));         return;     } while (0)
typedef struct Klass Klass;
typedef void* oop;
static inline void* neko_decode_klass_pointer(u4 narrow);
static void* neko_class_klass_pointer(jclass klass_obj);
static jclass neko_load_class_noinit_with_loader(JNIEnv *env, const char *internalName, jobject loader);
static jboolean neko_ldc_site_matches_loaded_class(JNIEnv *env, NekoManifestLdcSite *site, jclass candidate, const char *signature);
__attribute__((visibility("default"))) oop neko_rt_mirror_from_klass_nosafepoint(Klass *k);
__attribute__((visibility("default"))) oop neko_rt_static_base_from_holder_nosafepoint(Klass *holder);
__attribute__((visibility("default"))) oop neko_rt_try_alloc_instance_fast_nosafepoint(Klass *ik, size_t instance_size_bytes);
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

#define NEKO_JNI_FN_PTR(env, idx, ret, ...) ((ret (*)(JNIEnv*, ##__VA_ARGS__))(*((void***)(env)))[idx])

static inline jclass neko_find_class(JNIEnv *env, const char *name) { return NEKO_JNI_FN_PTR(env, 6, jclass, const char*)(env, name); }
static inline jclass neko_get_object_class(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 31, jclass, jobject)(env, obj); }
static inline jboolean neko_is_instance_of(JNIEnv *env, jobject obj, jclass clazz) { return NEKO_JNI_FN_PTR(env, 32, jboolean, jobject, jclass)(env, obj, clazz); }
static inline jmethodID neko_get_method_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 33, jmethodID, jclass, const char*, const char*)(env, c, n, s); }
static inline jmethodID neko_get_static_method_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 113, jmethodID, jclass, const char*, const char*)(env, c, n, s); }
static inline jfieldID neko_get_field_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 94, jfieldID, jclass, const char*, const char*)(env, c, n, s); }
static inline jfieldID neko_get_static_field_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 144, jfieldID, jclass, const char*, const char*)(env, c, n, s); }
static inline jobject neko_to_reflected_field(JNIEnv *env, jclass cls, jfieldID fid, jboolean isStatic) { return NEKO_JNI_FN_PTR(env, 12, jobject, jclass, jfieldID, jboolean)(env, cls, fid, isStatic); }
static inline jint neko_throw(JNIEnv *env, jthrowable exc) { return NEKO_JNI_FN_PTR(env, 13, jint, jthrowable)(env, exc); }
static inline jint neko_ensure_local_capacity(JNIEnv *env, jint capacity) { return NEKO_JNI_FN_PTR(env, 26, jint, jint)(env, capacity); }
static inline void neko_delete_global_ref(JNIEnv *env, jobject obj) { NEKO_JNI_FN_PTR(env, 22, void, jobject)(env, obj); }
static inline jobject neko_new_global_ref(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 21, jobject, jobject)(env, obj); }
static inline void neko_delete_local_ref(JNIEnv *env, jobject obj) { NEKO_JNI_FN_PTR(env, 23, void, jobject)(env, obj); }
static inline jboolean neko_is_same_object(JNIEnv *env, jobject a, jobject b) { return NEKO_JNI_FN_PTR(env, 24, jboolean, jobject, jobject)(env, a, b); }
static inline jobject neko_new_weak_global_ref(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 226, jobject, jobject)(env, obj); }
static inline void neko_delete_weak_global_ref(JNIEnv *env, jobject obj) { NEKO_JNI_FN_PTR(env, 227, void, jobject)(env, obj); }
static inline jobject neko_alloc_object(JNIEnv *env, jclass cls) { return NEKO_JNI_FN_PTR(env, 27, jobject, jclass)(env, cls); }
static inline jobject neko_new_object_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 30, jobject, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jobject neko_call_object_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 36, jobject, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jboolean neko_call_boolean_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 39, jboolean, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jbyte neko_call_byte_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 42, jbyte, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jchar neko_call_char_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 45, jchar, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jshort neko_call_short_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 48, jshort, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jint neko_call_int_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 51, jint, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jlong neko_call_long_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 54, jlong, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jfloat neko_call_float_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 57, jfloat, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jdouble neko_call_double_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 60, jdouble, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline void neko_call_void_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 63, void, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jobject neko_call_nonvirtual_object_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 66, jobject, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jboolean neko_call_nonvirtual_boolean_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 69, jboolean, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jbyte neko_call_nonvirtual_byte_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 72, jbyte, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jchar neko_call_nonvirtual_char_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 75, jchar, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jshort neko_call_nonvirtual_short_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 78, jshort, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jint neko_call_nonvirtual_int_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 81, jint, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jlong neko_call_nonvirtual_long_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 84, jlong, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jfloat neko_call_nonvirtual_float_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 87, jfloat, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jdouble neko_call_nonvirtual_double_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 90, jdouble, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline void neko_call_nonvirtual_void_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 93, void, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jobject neko_get_object_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 95, jobject, jobject, jfieldID)(env, obj, fid); }
static inline jboolean neko_get_boolean_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 96, jboolean, jobject, jfieldID)(env, obj, fid); }
static inline jbyte neko_get_byte_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 97, jbyte, jobject, jfieldID)(env, obj, fid); }
static inline jchar neko_get_char_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 98, jchar, jobject, jfieldID)(env, obj, fid); }
static inline jshort neko_get_short_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 99, jshort, jobject, jfieldID)(env, obj, fid); }
static inline jint neko_get_int_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 100, jint, jobject, jfieldID)(env, obj, fid); }
static inline jlong neko_get_long_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 101, jlong, jobject, jfieldID)(env, obj, fid); }
static inline jfloat neko_get_float_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 102, jfloat, jobject, jfieldID)(env, obj, fid); }
static inline jdouble neko_get_double_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 103, jdouble, jobject, jfieldID)(env, obj, fid); }
static inline void neko_set_object_field(JNIEnv *env, jobject obj, jfieldID fid, jobject val) { NEKO_JNI_FN_PTR(env, 104, void, jobject, jfieldID, jobject)(env, obj, fid, val); }
static inline void neko_set_boolean_field(JNIEnv *env, jobject obj, jfieldID fid, jboolean val) { NEKO_JNI_FN_PTR(env, 105, void, jobject, jfieldID, jboolean)(env, obj, fid, val); }
static inline void neko_set_byte_field(JNIEnv *env, jobject obj, jfieldID fid, jbyte val) { NEKO_JNI_FN_PTR(env, 106, void, jobject, jfieldID, jbyte)(env, obj, fid, val); }
static inline void neko_set_char_field(JNIEnv *env, jobject obj, jfieldID fid, jchar val) { NEKO_JNI_FN_PTR(env, 107, void, jobject, jfieldID, jchar)(env, obj, fid, val); }
static inline void neko_set_short_field(JNIEnv *env, jobject obj, jfieldID fid, jshort val) { NEKO_JNI_FN_PTR(env, 108, void, jobject, jfieldID, jshort)(env, obj, fid, val); }
static inline void neko_set_int_field(JNIEnv *env, jobject obj, jfieldID fid, jint val) { NEKO_JNI_FN_PTR(env, 109, void, jobject, jfieldID, jint)(env, obj, fid, val); }
static inline void neko_set_long_field(JNIEnv *env, jobject obj, jfieldID fid, jlong val) { NEKO_JNI_FN_PTR(env, 110, void, jobject, jfieldID, jlong)(env, obj, fid, val); }
static inline void neko_set_float_field(JNIEnv *env, jobject obj, jfieldID fid, jfloat val) { NEKO_JNI_FN_PTR(env, 111, void, jobject, jfieldID, jfloat)(env, obj, fid, val); }
static inline void neko_set_double_field(JNIEnv *env, jobject obj, jfieldID fid, jdouble val) { NEKO_JNI_FN_PTR(env, 112, void, jobject, jfieldID, jdouble)(env, obj, fid, val); }
static inline jobject neko_call_static_object_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 116, jobject, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jboolean neko_call_static_boolean_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 119, jboolean, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jbyte neko_call_static_byte_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 122, jbyte, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jchar neko_call_static_char_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 125, jchar, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jshort neko_call_static_short_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 128, jshort, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jint neko_call_static_int_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 131, jint, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jlong neko_call_static_long_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 134, jlong, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jfloat neko_call_static_float_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 137, jfloat, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jdouble neko_call_static_double_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 140, jdouble, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline void neko_call_static_void_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 143, void, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jobject neko_get_static_object_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 145, jobject, jclass, jfieldID)(env, cls, fid); }
static inline jboolean neko_get_static_boolean_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 146, jboolean, jclass, jfieldID)(env, cls, fid); }
static inline jbyte neko_get_static_byte_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 147, jbyte, jclass, jfieldID)(env, cls, fid); }
static inline jchar neko_get_static_char_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 148, jchar, jclass, jfieldID)(env, cls, fid); }
static inline jshort neko_get_static_short_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 149, jshort, jclass, jfieldID)(env, cls, fid); }
static inline jint neko_get_static_int_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 150, jint, jclass, jfieldID)(env, cls, fid); }
static inline jlong neko_get_static_long_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 151, jlong, jclass, jfieldID)(env, cls, fid); }
static inline jfloat neko_get_static_float_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 152, jfloat, jclass, jfieldID)(env, cls, fid); }
static inline jdouble neko_get_static_double_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 153, jdouble, jclass, jfieldID)(env, cls, fid); }
static inline void neko_set_static_object_field(JNIEnv *env, jclass cls, jfieldID fid, jobject val) { NEKO_JNI_FN_PTR(env, 154, void, jclass, jfieldID, jobject)(env, cls, fid, val); }
static inline void neko_set_static_boolean_field(JNIEnv *env, jclass cls, jfieldID fid, jboolean val) { NEKO_JNI_FN_PTR(env, 155, void, jclass, jfieldID, jboolean)(env, cls, fid, val); }
static inline void neko_set_static_byte_field(JNIEnv *env, jclass cls, jfieldID fid, jbyte val) { NEKO_JNI_FN_PTR(env, 156, void, jclass, jfieldID, jbyte)(env, cls, fid, val); }
static inline void neko_set_static_char_field(JNIEnv *env, jclass cls, jfieldID fid, jchar val) { NEKO_JNI_FN_PTR(env, 157, void, jclass, jfieldID, jchar)(env, cls, fid, val); }
static inline void neko_set_static_short_field(JNIEnv *env, jclass cls, jfieldID fid, jshort val) { NEKO_JNI_FN_PTR(env, 158, void, jclass, jfieldID, jshort)(env, cls, fid, val); }
static inline void neko_set_static_int_field(JNIEnv *env, jclass cls, jfieldID fid, jint val) { NEKO_JNI_FN_PTR(env, 159, void, jclass, jfieldID, jint)(env, cls, fid, val); }
static inline void neko_set_static_long_field(JNIEnv *env, jclass cls, jfieldID fid, jlong val) { NEKO_JNI_FN_PTR(env, 160, void, jclass, jfieldID, jlong)(env, cls, fid, val); }
static inline void neko_set_static_float_field(JNIEnv *env, jclass cls, jfieldID fid, jfloat val) { NEKO_JNI_FN_PTR(env, 161, void, jclass, jfieldID, jfloat)(env, cls, fid, val); }
static inline void neko_set_static_double_field(JNIEnv *env, jclass cls, jfieldID fid, jdouble val) { NEKO_JNI_FN_PTR(env, 162, void, jclass, jfieldID, jdouble)(env, cls, fid, val); }
static inline jsize neko_get_string_length(JNIEnv *env, jstring str) { return NEKO_JNI_FN_PTR(env, 164, jsize, jstring)(env, str); }
static inline jstring neko_new_string_utf(JNIEnv *env, const char *utf) { return NEKO_JNI_FN_PTR(env, 167, jstring, const char*)(env, utf); }
static inline const char* neko_get_string_utf_chars(JNIEnv *env, jstring str) { return NEKO_JNI_FN_PTR(env, 169, const char*, jstring, jboolean*)(env, str, NULL); }
static inline void neko_release_string_utf_chars(JNIEnv *env, jstring str, const char *chars) { NEKO_JNI_FN_PTR(env, 170, void, jstring, const char*)(env, str, chars); }
static inline jsize neko_get_array_length(JNIEnv *env, jarray arr) { return NEKO_JNI_FN_PTR(env, 171, jsize, jarray)(env, arr); }
static inline jobjectArray neko_new_object_array(JNIEnv *env, jsize len, jclass cls, jobject init) { return NEKO_JNI_FN_PTR(env, 172, jobjectArray, jsize, jclass, jobject)(env, len, cls, init); }
static inline jobject neko_get_object_array_element(JNIEnv *env, jobjectArray arr, jsize index) { return NEKO_JNI_FN_PTR(env, 173, jobject, jobjectArray, jsize)(env, arr, index); }
static inline void neko_set_object_array_element(JNIEnv *env, jobjectArray arr, jsize index, jobject val) { NEKO_JNI_FN_PTR(env, 174, void, jobjectArray, jsize, jobject)(env, arr, index, val); }
static inline jbooleanArray neko_new_boolean_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 175, jbooleanArray, jsize)(env, len); }
static inline jbyteArray neko_new_byte_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 176, jbyteArray, jsize)(env, len); }
static inline jcharArray neko_new_char_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 177, jcharArray, jsize)(env, len); }
static inline jshortArray neko_new_short_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 178, jshortArray, jsize)(env, len); }
static inline jintArray neko_new_int_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 179, jintArray, jsize)(env, len); }
static inline jlongArray neko_new_long_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 180, jlongArray, jsize)(env, len); }
static inline jfloatArray neko_new_float_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 181, jfloatArray, jsize)(env, len); }
static inline jdoubleArray neko_new_double_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 182, jdoubleArray, jsize)(env, len); }
static inline void neko_get_boolean_array_region(JNIEnv *env, jbooleanArray arr, jsize start, jsize len, jboolean *buf) { NEKO_JNI_FN_PTR(env, 199, void, jbooleanArray, jsize, jsize, jboolean*)(env, arr, start, len, buf); }
static inline void neko_get_byte_array_region(JNIEnv *env, jbyteArray arr, jsize start, jsize len, jbyte *buf) { NEKO_JNI_FN_PTR(env, 200, void, jbyteArray, jsize, jsize, jbyte*)(env, arr, start, len, buf); }
static inline void neko_get_char_array_region(JNIEnv *env, jcharArray arr, jsize start, jsize len, jchar *buf) { NEKO_JNI_FN_PTR(env, 201, void, jcharArray, jsize, jsize, jchar*)(env, arr, start, len, buf); }
static inline void neko_get_short_array_region(JNIEnv *env, jshortArray arr, jsize start, jsize len, jshort *buf) { NEKO_JNI_FN_PTR(env, 202, void, jshortArray, jsize, jsize, jshort*)(env, arr, start, len, buf); }
static inline void neko_get_int_array_region(JNIEnv *env, jintArray arr, jsize start, jsize len, jint *buf) { NEKO_JNI_FN_PTR(env, 203, void, jintArray, jsize, jsize, jint*)(env, arr, start, len, buf); }
static inline void neko_get_long_array_region(JNIEnv *env, jlongArray arr, jsize start, jsize len, jlong *buf) { NEKO_JNI_FN_PTR(env, 204, void, jlongArray, jsize, jsize, jlong*)(env, arr, start, len, buf); }
static inline void neko_get_float_array_region(JNIEnv *env, jfloatArray arr, jsize start, jsize len, jfloat *buf) { NEKO_JNI_FN_PTR(env, 205, void, jfloatArray, jsize, jsize, jfloat*)(env, arr, start, len, buf); }
static inline void neko_get_double_array_region(JNIEnv *env, jdoubleArray arr, jsize start, jsize len, jdouble *buf) { NEKO_JNI_FN_PTR(env, 206, void, jdoubleArray, jsize, jsize, jdouble*)(env, arr, start, len, buf); }
static inline void neko_set_boolean_array_region(JNIEnv *env, jbooleanArray arr, jsize start, jsize len, const jboolean *buf) { NEKO_JNI_FN_PTR(env, 207, void, jbooleanArray, jsize, jsize, const jboolean*)(env, arr, start, len, buf); }
static inline void neko_set_byte_array_region(JNIEnv *env, jbyteArray arr, jsize start, jsize len, const jbyte *buf) { NEKO_JNI_FN_PTR(env, 208, void, jbyteArray, jsize, jsize, const jbyte*)(env, arr, start, len, buf); }
static inline void neko_set_char_array_region(JNIEnv *env, jcharArray arr, jsize start, jsize len, const jchar *buf) { NEKO_JNI_FN_PTR(env, 209, void, jcharArray, jsize, jsize, const jchar*)(env, arr, start, len, buf); }
static inline void neko_set_short_array_region(JNIEnv *env, jshortArray arr, jsize start, jsize len, const jshort *buf) { NEKO_JNI_FN_PTR(env, 210, void, jshortArray, jsize, jsize, const jshort*)(env, arr, start, len, buf); }
static inline void neko_set_int_array_region(JNIEnv *env, jintArray arr, jsize start, jsize len, const jint *buf) { NEKO_JNI_FN_PTR(env, 211, void, jintArray, jsize, jsize, const jint*)(env, arr, start, len, buf); }
static inline void neko_set_long_array_region(JNIEnv *env, jlongArray arr, jsize start, jsize len, const jlong *buf) { NEKO_JNI_FN_PTR(env, 212, void, jlongArray, jsize, jsize, const jlong*)(env, arr, start, len, buf); }
static inline void neko_set_float_array_region(JNIEnv *env, jfloatArray arr, jsize start, jsize len, const jfloat *buf) { NEKO_JNI_FN_PTR(env, 213, void, jfloatArray, jsize, jsize, const jfloat*)(env, arr, start, len, buf); }
static inline void neko_set_double_array_region(JNIEnv *env, jdoubleArray arr, jsize start, jsize len, const jdouble *buf) { NEKO_JNI_FN_PTR(env, 214, void, jdoubleArray, jsize, jsize, const jdouble*)(env, arr, start, len, buf); }
static inline jint neko_monitor_enter(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 217, jint, jobject)(env, obj); }
static inline jint neko_monitor_exit(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 218, jint, jobject)(env, obj); }

static char* neko_dotted_class_name(const char *internalName) {
    size_t len = strlen(internalName);
    char *out = (char*)malloc(len + 1u);
    if (out == NULL) return NULL;
    for (size_t i = 0; i < len; i++) out[i] = internalName[i] == '/' ? '.' : internalName[i];
    out[len] = '\0';
    return out;
}

static jclass neko_load_class_noinit(JNIEnv *env, const char *internalName) {
    jclass clClass = neko_find_class(env, "java/lang/ClassLoader");
    jmethodID getSystem = neko_get_static_method_id(env, clClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject loader = neko_call_static_object_method_a(env, clClass, getSystem, NULL);
    jclass klass = neko_load_class_noinit_with_loader(env, internalName, loader);
    if (loader != NULL) neko_delete_local_ref(env, loader);
    if (clClass != NULL) neko_delete_local_ref(env, clClass);
    return klass;
}

static jclass neko_load_class_noinit_with_loader(JNIEnv *env, const char *internalName, jobject loader) {
    char *dotted = neko_dotted_class_name(internalName);
    jclass classClass;
    jmethodID forName;
    jvalue args[3];
    jstring binaryName;
    jclass klass;
    if (dotted == NULL) return NULL;
    classClass = neko_find_class(env, "java/lang/Class");
    forName = neko_get_static_method_id(env, classClass, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    binaryName = neko_new_string_utf(env, dotted);
    args[0].l = binaryName;
    args[1].z = JNI_FALSE;
    args[2].l = loader;
    klass = (jclass)neko_call_static_object_method_a(env, classClass, forName, args);
    if (binaryName != NULL) neko_delete_local_ref(env, binaryName);
    if (classClass != NULL) neko_delete_local_ref(env, classClass);
    free(dotted);
    return klass;
}

static jobject neko_box_boolean(JNIEnv *env, jboolean v) {
    static jclass g_box_boolean_cls = NULL;
    static jmethodID g_box_boolean_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_boolean_cls, env, "java/lang/Boolean");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_boolean_mid, env, cls, "valueOf", "(Z)Ljava/lang/Boolean;");
    jvalue args[1]; args[0].z = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_byte(JNIEnv *env, jbyte v) {
    static jclass g_box_byte_cls = NULL;
    static jmethodID g_box_byte_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_byte_cls, env, "java/lang/Byte");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_byte_mid, env, cls, "valueOf", "(B)Ljava/lang/Byte;");
    jvalue args[1]; args[0].b = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_char(JNIEnv *env, jchar v) {
    static jclass g_box_char_cls = NULL;
    static jmethodID g_box_char_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_char_cls, env, "java/lang/Character");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_char_mid, env, cls, "valueOf", "(C)Ljava/lang/Character;");
    jvalue args[1]; args[0].c = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_short(JNIEnv *env, jshort v) {
    static jclass g_box_short_cls = NULL;
    static jmethodID g_box_short_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_short_cls, env, "java/lang/Short");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_short_mid, env, cls, "valueOf", "(S)Ljava/lang/Short;");
    jvalue args[1]; args[0].s = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_int(JNIEnv *env, jint v) {
    static jclass g_box_int_cls = NULL;
    static jmethodID g_box_int_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_int_cls, env, "java/lang/Integer");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_int_mid, env, cls, "valueOf", "(I)Ljava/lang/Integer;");
    jvalue args[1]; args[0].i = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_long(JNIEnv *env, jlong v) {
    static jclass g_box_long_cls = NULL;
    static jmethodID g_box_long_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_long_cls, env, "java/lang/Long");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_long_mid, env, cls, "valueOf", "(J)Ljava/lang/Long;");
    jvalue args[1]; args[0].j = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_float(JNIEnv *env, jfloat v) {
    static jclass g_box_float_cls = NULL;
    static jmethodID g_box_float_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_float_cls, env, "java/lang/Float");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_float_mid, env, cls, "valueOf", "(F)Ljava/lang/Float;");
    jvalue args[1]; args[0].f = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_double(JNIEnv *env, jdouble v) {
    static jclass g_box_double_cls = NULL;
    static jmethodID g_box_double_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_double_cls, env, "java/lang/Double");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_double_mid, env, cls, "valueOf", "(D)Ljava/lang/Double;");
    jvalue args[1]; args[0].d = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jboolean neko_unbox_boolean(JNIEnv *env, jobject obj) {
    static jclass g_unbox_boolean_cls = NULL;
    static jmethodID g_unbox_boolean_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_boolean_cls, env, "java/lang/Boolean");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_boolean_mid, env, cls, "booleanValue", "()Z");
    return neko_call_boolean_method_a(env, obj, mid, NULL);
}
static jbyte neko_unbox_byte(JNIEnv *env, jobject obj) {
    static jclass g_unbox_byte_cls = NULL;
    static jmethodID g_unbox_byte_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_byte_cls, env, "java/lang/Byte");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_byte_mid, env, cls, "byteValue", "()B");
    return neko_call_byte_method_a(env, obj, mid, NULL);
}
static jchar neko_unbox_char(JNIEnv *env, jobject obj) {
    static jclass g_unbox_char_cls = NULL;
    static jmethodID g_unbox_char_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_char_cls, env, "java/lang/Character");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_char_mid, env, cls, "charValue", "()C");
    return neko_call_char_method_a(env, obj, mid, NULL);
}
static jshort neko_unbox_short(JNIEnv *env, jobject obj) {
    static jclass g_unbox_short_cls = NULL;
    static jmethodID g_unbox_short_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_short_cls, env, "java/lang/Short");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_short_mid, env, cls, "shortValue", "()S");
    return neko_call_short_method_a(env, obj, mid, NULL);
}
static jint neko_unbox_int(JNIEnv *env, jobject obj) {
    static jclass g_unbox_int_cls = NULL;
    static jmethodID g_unbox_int_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_int_cls, env, "java/lang/Integer");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_int_mid, env, cls, "intValue", "()I");
    return neko_call_int_method_a(env, obj, mid, NULL);
}
static jlong neko_unbox_long(JNIEnv *env, jobject obj) {
    static jclass g_unbox_long_cls = NULL;
    static jmethodID g_unbox_long_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_long_cls, env, "java/lang/Long");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_long_mid, env, cls, "longValue", "()J");
    return neko_call_long_method_a(env, obj, mid, NULL);
}
static jfloat neko_unbox_float(JNIEnv *env, jobject obj) {
    static jclass g_unbox_float_cls = NULL;
    static jmethodID g_unbox_float_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_float_cls, env, "java/lang/Float");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_float_mid, env, cls, "floatValue", "()F");
    return neko_call_float_method_a(env, obj, mid, NULL);
}
static jdouble neko_unbox_double(JNIEnv *env, jobject obj) {
    static jclass g_unbox_double_cls = NULL;
    static jmethodID g_unbox_double_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_double_cls, env, "java/lang/Double");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_double_mid, env, cls, "doubleValue", "()D");
    return neko_call_double_method_a(env, obj, mid, NULL);
}

static jclass neko_class_for_descriptor(JNIEnv *env, const char *desc) {
    switch (desc[0]) {
        case 'Z': { jclass c = neko_find_class(env, "java/lang/Boolean"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'B': { jclass c = neko_find_class(env, "java/lang/Byte"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'C': { jclass c = neko_find_class(env, "java/lang/Character"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'S': { jclass c = neko_find_class(env, "java/lang/Short"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'I': { jclass c = neko_find_class(env, "java/lang/Integer"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'J': { jclass c = neko_find_class(env, "java/lang/Long"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'F': { jclass c = neko_find_class(env, "java/lang/Float"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'D': { jclass c = neko_find_class(env, "java/lang/Double"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'L': {
            const char *start = desc + 1;
            const char *semi = strchr(start, ';');
            size_t len = (size_t)(semi - start);
            char *buf = (char*)malloc(len + 1u);
            memcpy(buf, start, len); buf[len] = '\0';
            jclass out = neko_find_class(env, buf);
            free(buf);
            return out;
        }
        case '[':
            return neko_find_class(env, desc);
        default:
            return NULL;
    }
}

typedef struct {
    jlong id;
    jobject mh;
} neko_indy_entry;

static neko_indy_entry g_indy_table[4096];
static jint g_indy_count = 0;

static jobject neko_get_indy_mh(jlong site_id) {
    for (jint i = 0; i < g_indy_count; i++) {
        if (g_indy_table[i].id == site_id) return g_indy_table[i].mh;
    }
    return NULL;
}

static jobject neko_put_indy_mh(JNIEnv *env, jlong site_id, jobject mh) {
    jobject gref = mh == NULL ? NULL : neko_new_global_ref(env, mh);
    for (jint i = 0; i < g_indy_count; i++) {
        if (g_indy_table[i].id == site_id) {
            g_indy_table[i].mh = gref;
            return gref;
        }
    }
    if (g_indy_count < (jint)(sizeof(g_indy_table) / sizeof(g_indy_table[0]))) {
        g_indy_table[g_indy_count].id = site_id;
        g_indy_table[g_indy_count].mh = gref;
        g_indy_count++;
    }
    return gref;
}

static jobject neko_public_lookup(JNIEnv *env) {
    jclass mhClass = neko_find_class(env, "java/lang/invoke/MethodHandles");
    jmethodID mid = neko_get_static_method_id(env, mhClass, "publicLookup", "()Ljava/lang/invoke/MethodHandles$Lookup;");
    return neko_call_static_object_method_a(env, mhClass, mid, NULL);
}

static jobject neko_impl_lookup(JNIEnv *env) {
    jclass lookupClass = neko_find_class(env, "java/lang/invoke/MethodHandles$Lookup");
    jfieldID fid = neko_get_static_field_id(env, lookupClass, "IMPL_LOOKUP", "Ljava/lang/invoke/MethodHandles$Lookup;");
    return neko_get_static_object_field(env, lookupClass, fid);
}

static jobject neko_lookup_for_class(JNIEnv *env, const char *owner) {
    jclass mhClass = neko_find_class(env, "java/lang/invoke/MethodHandles");
    jmethodID mid = neko_get_static_method_id(env, mhClass, "privateLookupIn", "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;");
    jvalue args[2];
    args[0].l = neko_find_class(env, owner);
    args[1].l = neko_impl_lookup(env);
    return neko_call_static_object_method_a(env, mhClass, mid, args);
}

static jobject neko_method_type_from_descriptor(JNIEnv *env, const char *desc) {
    jclass mtClass = neko_find_class(env, "java/lang/invoke/MethodType");
    jmethodID mid = neko_get_static_method_id(env, mtClass, "fromMethodDescriptorString", "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
    jvalue args[2];
    args[0].l = neko_new_string_utf(env, desc);
    args[1].l = NULL;
    return neko_call_static_object_method_a(env, mtClass, mid, args);
}

static jobjectArray neko_bootstrap_parameter_array(JNIEnv *env, const char *bsm_desc) {
    jobject mt = neko_method_type_from_descriptor(env, bsm_desc);
    jclass mtClass = neko_find_class(env, "java/lang/invoke/MethodType");
    jmethodID mid = neko_get_method_id(env, mtClass, "parameterArray", "()[Ljava/lang/Class;");
    return (jobjectArray)neko_call_object_method_a(env, mt, mid, NULL);
}

static jobject neko_invoke_bootstrap(JNIEnv *env, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray invoke_args) {
    jclass bsmClass = neko_find_class(env, bsm_owner);
    jobjectArray paramTypes = neko_bootstrap_parameter_array(env, bsm_desc);
    jclass classClass = neko_find_class(env, "java/lang/Class");
    jmethodID getDeclaredMethod = neko_get_method_id(env, classClass, "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
    jvalue getArgs[2];
    getArgs[0].l = neko_new_string_utf(env, bsm_name);
    getArgs[1].l = paramTypes;
    jobject method = neko_call_object_method_a(env, bsmClass, getDeclaredMethod, getArgs);

    jclass accessibleClass = neko_find_class(env, "java/lang/reflect/AccessibleObject");
    jmethodID setAccessible = neko_get_method_id(env, accessibleClass, "setAccessible", "(Z)V");
    jvalue accessibleArgs[1];
    accessibleArgs[0].z = JNI_TRUE;
    neko_call_void_method_a(env, method, setAccessible, accessibleArgs);

    jclass methodClass = neko_find_class(env, "java/lang/reflect/Method");
    jmethodID invoke = neko_get_method_id(env, methodClass, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    jvalue invokeArgs[2];
    invokeArgs[0].l = NULL;
    invokeArgs[1].l = invoke_args;
    return neko_call_object_method_a(env, method, invoke, invokeArgs);
}

static jobject neko_method_handle_from_parts(JNIEnv *env, jint tag, const char *owner, const char *name, const char *desc, jboolean isInterface) {
    (void)isInterface;
    jobject lookup = neko_lookup_for_class(env, owner);
    jclass lookupClass = neko_find_class(env, "java/lang/invoke/MethodHandles$Lookup");
    jclass ownerClass = neko_find_class(env, owner);
    jstring nameString = neko_new_string_utf(env, name);

    switch (tag) {
        case 1: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findGetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 2: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findStaticGetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 3: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findSetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 4: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findStaticSetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 5: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 6: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 7: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findSpecial", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[4]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt; args[3].l = ownerClass;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 8: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findConstructor", "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[2]; args[0].l = ownerClass; args[1].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 9: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        default:
            return NULL;
    }
}

static jobject neko_call_mh(JNIEnv *env, jobject mh, jobjectArray args) {
    jclass mhClass = neko_find_class(env, "java/lang/invoke/MethodHandle");
    jmethodID mid = neko_get_method_id(env, mhClass, "invokeWithArguments", "([Ljava/lang/Object;)Ljava/lang/Object;");
    jvalue callArgs[1];
    callArgs[0].l = args;
    return neko_call_object_method_a(env, mh, mid, callArgs);
}

static jstring neko_string_null(JNIEnv *env) {
    static jstring g_str_null = NULL;
    return NEKO_ENSURE_STRING(g_str_null, env, "null");
}

static jstring neko_string_concat2(JNIEnv *env, jobject left, jobject right) {
    static jclass g_str_cls = NULL;
    static jmethodID g_str_value_of = NULL;
    static jmethodID g_str_concat = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_str_cls, env, "java/lang/String");
    jmethodID valueOf = NEKO_ENSURE_STATIC_METHOD_ID(g_str_value_of, env, cls, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
    jmethodID concat = NEKO_ENSURE_METHOD_ID(g_str_concat, env, cls, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
    jvalue valueOfArgs[1];
    valueOfArgs[0].l = left;
    jstring lhs = (jstring)neko_call_static_object_method_a(env, cls, valueOf, valueOfArgs);
    valueOfArgs[0].l = right;
    jstring rhs = (jstring)neko_call_static_object_method_a(env, cls, valueOf, valueOfArgs);
    jvalue concatArgs[1];
    concatArgs[0].l = rhs;
    return (jstring)neko_call_object_method_a(env, lhs, concat, concatArgs);
}

static jstring neko_string_concat_string(JNIEnv *env, jobject left, jstring right) {
    static jclass g_str_cls2 = NULL;
    static jmethodID g_str_value_of2 = NULL;
    static jmethodID g_str_concat2 = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_str_cls2, env, "java/lang/String");
    jmethodID valueOf = NEKO_ENSURE_STATIC_METHOD_ID(g_str_value_of2, env, cls, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
    jmethodID concat = NEKO_ENSURE_METHOD_ID(g_str_concat2, env, cls, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring lhs;
    if (left == NULL) {
        lhs = neko_string_null(env);
    } else {
        lhs = (jstring)left;
    }
    jvalue concatArgs[1];
    concatArgs[0].l = right == NULL ? neko_string_null(env) : right;
    return (jstring)neko_call_object_method_a(env, lhs, concat, concatArgs);
}

static jobject neko_resolve_indy(JNIEnv *env, jlong site_id, const char *caller_owner, const char *indy_name, const char *indy_desc, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray static_args) {
    jobject cached = neko_get_indy_mh(site_id);
    if (cached != NULL) return cached;

    jobjectArray paramTypes = neko_bootstrap_parameter_array(env, bsm_desc);
    jsize paramCount = neko_get_array_length(env, (jarray)paramTypes);
    jclass objClass = neko_find_class(env, "java/lang/Object");
    jobjectArray invokeArgs = neko_new_object_array(env, paramCount, objClass, NULL);
    neko_set_object_array_element(env, invokeArgs, 0, neko_lookup_for_class(env, caller_owner));
    neko_set_object_array_element(env, invokeArgs, 1, neko_new_string_utf(env, indy_name));
    neko_set_object_array_element(env, invokeArgs, 2, neko_method_type_from_descriptor(env, indy_desc));
    for (jsize i = 0; i < neko_get_array_length(env, (jarray)static_args); i++) {
        neko_set_object_array_element(env, invokeArgs, i + 3, neko_get_object_array_element(env, static_args, i));
    }

    jobject bootstrapResult = neko_invoke_bootstrap(env, bsm_owner, bsm_name, bsm_desc, invokeArgs);
    jclass callSiteClass = neko_find_class(env, "java/lang/invoke/CallSite");
    jobject mh = bootstrapResult;
    if (bootstrapResult != NULL && neko_is_instance_of(env, bootstrapResult, callSiteClass)) {
        jmethodID dynamicInvoker = neko_get_method_id(env, callSiteClass, "dynamicInvoker", "()Ljava/lang/invoke/MethodHandle;");
        mh = neko_call_object_method_a(env, bootstrapResult, dynamicInvoker, NULL);
    }
    return neko_put_indy_mh(env, site_id, mh);
}

static jobject neko_resolve_constant_dynamic(JNIEnv *env, const char *caller_owner, const char *name, const char *desc, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray static_args) {
    jobjectArray paramTypes = neko_bootstrap_parameter_array(env, bsm_desc);
    jsize paramCount = neko_get_array_length(env, (jarray)paramTypes);
    jclass objClass = neko_find_class(env, "java/lang/Object");
    jobjectArray invokeArgs = neko_new_object_array(env, paramCount, objClass, NULL);
    neko_set_object_array_element(env, invokeArgs, 0, neko_lookup_for_class(env, caller_owner));
    neko_set_object_array_element(env, invokeArgs, 1, neko_new_string_utf(env, name));
    neko_set_object_array_element(env, invokeArgs, 2, neko_class_for_descriptor(env, desc));
    for (jsize i = 0; i < neko_get_array_length(env, (jarray)static_args); i++) {
        neko_set_object_array_element(env, invokeArgs, i + 3, neko_get_object_array_element(env, static_args, i));
    }
    return neko_invoke_bootstrap(env, bsm_owner, bsm_name, bsm_desc, invokeArgs);
}

static jobject neko_multi_new_array(JNIEnv *env, jint num_dims, jint *dims, const char *desc) {
    if (num_dims <= 0) return NULL;
    if (dims[0] < 0) { (void)neko_throw_cached(env, g_neko_throw_nase); return NULL; }
    if (num_dims == 1) {
        char leaf = desc[1];
        switch (leaf) {
            case 'Z': return (jobject)neko_new_boolean_array(env, dims[0]);
            case 'B': return (jobject)neko_new_byte_array(env, dims[0]);
            case 'C': return (jobject)neko_new_char_array(env, dims[0]);
            case 'S': return (jobject)neko_new_short_array(env, dims[0]);
            case 'I': return (jobject)neko_new_int_array(env, dims[0]);
            case 'J': return (jobject)neko_new_long_array(env, dims[0]);
            case 'F': return (jobject)neko_new_float_array(env, dims[0]);
            case 'D': return (jobject)neko_new_double_array(env, dims[0]);
            case 'L':
            case '[': {
                jclass elemClass = neko_class_for_descriptor(env, desc + 1);
                return (jobject)neko_new_object_array(env, dims[0], elemClass, NULL);
            }
            default:
                return NULL;
        }
    }
    jclass topElemClass = neko_class_for_descriptor(env, desc + 1);
    jobjectArray arr = (jobjectArray)neko_new_object_array(env, dims[0], topElemClass, NULL);
    if (arr == NULL) return NULL;
    for (jint i = 0; i < dims[0]; i++) {
        jobject sub = neko_multi_new_array(env, num_dims - 1, dims + 1, desc + 1);
        if (sub == NULL) return NULL;
        neko_set_object_array_element(env, arr, i, sub);
        if (neko_pending_exception(neko_get_current_thread()) != NULL) return NULL;
    }
    return (jobject)arr;
}

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
    NEKO_HOTSPOT_FAST_HANDLE_TAGS = 1ll << 19,
    NEKO_FAST_RECEIVER_KEY = 0x10ll,
    NEKO_FAST_PRIM_FIELD = 0x4ll,
    NEKO_FAST_PRIM_ARRAY = 0x8ll
};

typedef struct {
    jboolean initialized;
    jlong fast_bits;
    jboolean use_compact_object_headers;
    jint klass_offset_bytes;
    jboolean use_compressed_klass_ptrs;
    jint primitive_array_base_offsets[NEKO_PRIM_COUNT];
    jint primitive_array_index_scales[NEKO_PRIM_COUNT];
} neko_hotspot_state;

static const neko_hotspot_state g_hotspot = {0};


#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 199901L
#define NEKO_FAST_INLINE static inline
#else
#define NEKO_FAST_INLINE static
#endif

NEKO_FAST_INLINE void* neko_handle_oop(jobject handle) {
    uintptr_t raw_handle;
    void *volatile *slot;
    if (handle == NULL) return NULL;
    raw_handle = (uintptr_t)handle;
    if (g_neko_vm_layout.java_spec_version >= 21 && (raw_handle & 0x3u) == 0x2u) raw_handle -= 2u;
    slot = (void *volatile *)(uintptr_t)raw_handle;
    return slot == NULL ? NULL : *slot;
}

NEKO_FAST_INLINE jint neko_fast_array_length(JNIEnv *env, jarray arr) {
    return (jint)neko_get_array_length(env, arr);
}

NEKO_FAST_INLINE jboolean neko_receiver_key_supported(void) {
    return g_hotspot.initialized
        && g_hotspot.use_compact_object_headers == JNI_FALSE
        && (g_hotspot.fast_bits & NEKO_FAST_RECEIVER_KEY) != 0;
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

NEKO_FAST_INLINE void* neko_receiver_klass(jobject obj) {
    char *oop;
    char *klassAddr;
    uintptr_t narrow;
    if (obj == NULL || !neko_receiver_key_supported()) return NULL;
    oop = (char*)neko_handle_oop(obj);
    if (oop == NULL || g_hotspot.klass_offset_bytes <= 0) return NULL;
    klassAddr = oop + g_hotspot.klass_offset_bytes;
    if (g_hotspot.use_compressed_klass_ptrs) {
        narrow = (uintptr_t)(*(uint32_t*)klassAddr);
        return narrow == 0u ? NULL : neko_decode_klass_pointer((u4)narrow);
    }
    return *(void**)klassAddr;
}

static void* neko_vtable_offset_for(void *klass, void *resolved_method) {
    uint16_t vtable_index;
    uint8_t *entry;
    if (klass == NULL || resolved_method == NULL) return NULL;
    if (g_neko_vm_layout.off_instance_klass_vtable_start < 0 || g_neko_vm_layout.off_method_vtable_index < 0) return NULL;
    if (g_neko_vm_layout.vtable_entry_size < sizeof(void*)) return NULL;
    vtable_index = *(uint16_t*)((uint8_t*)resolved_method + g_neko_vm_layout.off_method_vtable_index);
    if (vtable_index == (uint16_t)0xFFFFu) return NULL;
    entry = (uint8_t*)klass + g_neko_vm_layout.off_instance_klass_vtable_start + ((size_t)vtable_index * g_neko_vm_layout.vtable_entry_size);
    return __atomic_load_n((void**)entry, __ATOMIC_ACQUIRE);
}

static void* neko_itable_offset_for(void *klass, void *interface_klass, void *resolved_method) {
    (void)klass;
    (void)interface_klass;
    return resolved_method;
}

static jboolean neko_vtable_inline_supported(jobject receiver, void *resolved_method, jboolean is_interface) {
    void *receiver_klass;
    void *holder_klass;
    void *inline_method;
    if (is_interface || receiver == NULL || resolved_method == NULL) return JNI_FALSE;
    receiver_klass = neko_receiver_klass(receiver);
    if (receiver_klass == NULL) return JNI_FALSE;
    holder_klass = neko_method_holder_klass(resolved_method);
    if (holder_klass == NULL || holder_klass != receiver_klass) return JNI_FALSE;
    inline_method = neko_vtable_offset_for(receiver_klass, resolved_method);
    if (inline_method != resolved_method) return JNI_FALSE;
    NEKO_TRACE(1, "[nk] neko_vtable_inline=%p method=%p", receiver_klass, resolved_method);
    return JNI_TRUE;
}

static jboolean neko_itable_inline_supported(jobject receiver, jclass translated_class, void *resolved_method, void *interface_klass) {
    void *receiver_klass;
    void *translated_klass;
    void *inline_method;
    if (receiver == NULL || translated_class == NULL || resolved_method == NULL) return JNI_FALSE;
    receiver_klass = neko_receiver_klass(receiver);
    if (receiver_klass == NULL) return JNI_FALSE;
    translated_klass = neko_class_klass_pointer(translated_class);
    if (translated_klass == NULL || translated_klass != receiver_klass) return JNI_FALSE;
    inline_method = neko_itable_offset_for(receiver_klass, interface_klass, resolved_method);
    if (inline_method != resolved_method) return JNI_FALSE;
    NEKO_TRACE(1, "[nk] neko_vtable_inline=%p itable_method=%p", receiver_klass, resolved_method);
    return JNI_TRUE;
}

typedef jvalue (*neko_icache_direct_stub)(JNIEnv *env, jobject receiver, const jvalue *args);

typedef struct {
    const char *name;
    const char *desc;
    const jclass *translated_class_slot;
    neko_icache_direct_stub translated_stub;
    jboolean is_interface;
} neko_icache_meta;

NEKO_FAST_INLINE char neko_icache_return_kind(const char *desc) {
    const char *ret = desc == NULL ? NULL : strrchr(desc, ')');
    return (ret != NULL && ret[1] != ' ') ? ret[1] : 'V';
}

static jvalue neko_icache_call_virtual(JNIEnv *env, jobject receiver, jmethodID mid, const jvalue *args, const char *desc) {
    jvalue result = {0};
    switch (neko_icache_return_kind(desc)) {
        case 'V': neko_call_void_method_a(env, receiver, mid, args); break;
        case 'Z': result.z = neko_call_boolean_method_a(env, receiver, mid, args); break;
        case 'B': result.b = neko_call_byte_method_a(env, receiver, mid, args); break;
        case 'C': result.c = neko_call_char_method_a(env, receiver, mid, args); break;
        case 'S': result.s = neko_call_short_method_a(env, receiver, mid, args); break;
        case 'I': result.i = neko_call_int_method_a(env, receiver, mid, args); break;
        case 'J': result.j = neko_call_long_method_a(env, receiver, mid, args); break;
        case 'F': result.f = neko_call_float_method_a(env, receiver, mid, args); break;
        case 'D': result.d = neko_call_double_method_a(env, receiver, mid, args); break;
        default: result.l = neko_call_object_method_a(env, receiver, mid, args); break;
    }
    return result;
}

static jvalue neko_icache_call_nonvirtual(JNIEnv *env, jobject receiver, jclass klass, jmethodID mid, const jvalue *args, const char *desc) {
    jvalue result = {0};
    switch (neko_icache_return_kind(desc)) {
        case 'V': neko_call_nonvirtual_void_method_a(env, receiver, klass, mid, args); break;
        case 'Z': result.z = neko_call_nonvirtual_boolean_method_a(env, receiver, klass, mid, args); break;
        case 'B': result.b = neko_call_nonvirtual_byte_method_a(env, receiver, klass, mid, args); break;
        case 'C': result.c = neko_call_nonvirtual_char_method_a(env, receiver, klass, mid, args); break;
        case 'S': result.s = neko_call_nonvirtual_short_method_a(env, receiver, klass, mid, args); break;
        case 'I': result.i = neko_call_nonvirtual_int_method_a(env, receiver, klass, mid, args); break;
        case 'J': result.j = neko_call_nonvirtual_long_method_a(env, receiver, klass, mid, args); break;
        case 'F': result.f = neko_call_nonvirtual_float_method_a(env, receiver, klass, mid, args); break;
        case 'D': result.d = neko_call_nonvirtual_double_method_a(env, receiver, klass, mid, args); break;
        default: result.l = neko_call_nonvirtual_object_method_a(env, receiver, klass, mid, args); break;
    }
    return result;
}

NEKO_FAST_INLINE void neko_icache_replace_class(JNIEnv *env, neko_icache_site *site, jclass cachedClass) {
    if (site == NULL) return;
    if (site->cached_class != NULL) neko_delete_global_ref(env, site->cached_class);
    site->cached_class = cachedClass;
}

NEKO_FAST_INLINE void neko_icache_store_direct(JNIEnv *env, neko_icache_site *site, uintptr_t receiverKey, jclass cachedClass, void *target) {
    if (site == NULL) return;
    neko_icache_replace_class(env, site, cachedClass);
    site->receiver_key = receiverKey;
    site->target = target;
    site->target_kind = NEKO_ICACHE_DIRECT_C;
}

NEKO_FAST_INLINE void neko_icache_store_nonvirt(JNIEnv *env, neko_icache_site *site, uintptr_t receiverKey, jclass cachedClass, jmethodID mid) {
    if (site == NULL) return;
    neko_icache_replace_class(env, site, cachedClass);
    site->receiver_key = receiverKey;
    site->target = (void*)mid;
    site->target_kind = NEKO_ICACHE_NONVIRT_MID;
}

NEKO_FAST_INLINE jboolean neko_icache_note_miss(JNIEnv *env, neko_icache_site *site) {
    if (site == NULL) return JNI_FALSE;
    if (site->miss_count < (uint16_t)0xFFFFu) site->miss_count++;
    if (site->miss_count < NEKO_ICACHE_MEGA_THRESHOLD) return JNI_FALSE;
    neko_icache_replace_class(env, site, NULL);
    site->receiver_key = (uintptr_t)0;
    site->target = NULL;
    site->target_kind = NEKO_ICACHE_MEGA;
    return JNI_TRUE;
}

static jvalue neko_icache_dispatch(
    JNIEnv *env,
    neko_icache_site *site,
    const neko_icache_meta *meta,
    jobject receiver,
    jmethodID fallback_mid,
    const jvalue *args
) {
    jvalue result = {0};
    uintptr_t receiverKey;
    if (env == NULL || receiver == NULL || fallback_mid == NULL) return result;
    neko_maybe_rescan_cld_liveness();
    if (site != NULL && neko_receiver_key_supported()) {
        receiverKey = neko_receiver_key(receiver);
        if (receiverKey != 0 && site->target_kind != NEKO_ICACHE_MEGA) {
            if (receiverKey == site->receiver_key) {
                if (site->target_kind == NEKO_ICACHE_DIRECT_C && site->target != NULL) {
                    return ((neko_icache_direct_stub)site->target)(env, receiver, args);
                }
                if (site->target_kind == NEKO_ICACHE_NONVIRT_MID && site->cached_class != NULL && site->target != NULL) {
                    return neko_icache_call_nonvirtual(env, receiver, site->cached_class, (jmethodID)site->target, args, meta != NULL ? meta->desc : NULL);
                }
            }
            if (!neko_icache_note_miss(env, site)) {
                jclass exactClass = neko_get_object_class(env, receiver);
                if (exactClass != NULL) {
                    jclass translatedClass = (meta != NULL && meta->translated_class_slot != NULL) ? *meta->translated_class_slot : NULL;
                    if (translatedClass != NULL && meta != NULL && meta->translated_stub != NULL && neko_is_same_object(env, exactClass, translatedClass)) {
                        jclass cachedExactClass = (jclass)neko_new_global_ref(env, exactClass);
                        neko_icache_store_direct(env, site, receiverKey, cachedExactClass, (void*)meta->translated_stub);
                        neko_delete_local_ref(env, exactClass);
                        return meta->translated_stub(env, receiver, args);
                    }
                    jmethodID exactMid = neko_get_method_id(env, exactClass, meta != NULL ? meta->name : NULL, meta != NULL ? meta->desc : NULL);
                    if (exactMid != NULL) {
                        jclass cachedExactClass = (jclass)neko_new_global_ref(env, exactClass);
                        if (cachedExactClass != NULL) {
                            neko_icache_store_nonvirt(env, site, receiverKey, cachedExactClass, exactMid);
                        }
                        result = neko_icache_call_nonvirtual(env, receiver, cachedExactClass != NULL ? cachedExactClass : exactClass, exactMid, args, meta != NULL ? meta->desc : NULL);
                        neko_delete_local_ref(env, exactClass);
                        return result;
                    }
                    neko_delete_local_ref(env, exactClass);
                }
            }
        }
    }
    return neko_icache_call_virtual(env, receiver, fallback_mid, args, meta != NULL ? meta->desc : NULL);
}

NEKO_FAST_INLINE jboolean neko_fast_get_Z_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) return *(jboolean*)(oop + offset);
    }
    return neko_get_boolean_field(env, obj, fid);
}

NEKO_FAST_INLINE void neko_fast_set_Z_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, jboolean value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) { *(jboolean*)(oop + offset) = value; return; }
    }
    neko_set_boolean_field(env, obj, fid, value);
}

NEKO_FAST_INLINE jboolean neko_fast_get_static_Z_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) return *(jboolean*)(oop + offset);
    }
    return neko_get_static_boolean_field(env, cls, fid);
}

NEKO_FAST_INLINE void neko_fast_set_static_Z_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, jboolean value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) { *(jboolean*)(oop + offset) = value; return; }
    }
    neko_set_static_boolean_field(env, cls, fid, value);
}

NEKO_FAST_INLINE jbyte neko_fast_get_B_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) return *(jbyte*)(oop + offset);
    }
    return neko_get_byte_field(env, obj, fid);
}

NEKO_FAST_INLINE void neko_fast_set_B_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, jbyte value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) { *(jbyte*)(oop + offset) = value; return; }
    }
    neko_set_byte_field(env, obj, fid, value);
}

NEKO_FAST_INLINE jbyte neko_fast_get_static_B_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) return *(jbyte*)(oop + offset);
    }
    return neko_get_static_byte_field(env, cls, fid);
}

NEKO_FAST_INLINE void neko_fast_set_static_B_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, jbyte value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) { *(jbyte*)(oop + offset) = value; return; }
    }
    neko_set_static_byte_field(env, cls, fid, value);
}

NEKO_FAST_INLINE jchar neko_fast_get_C_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) return *(jchar*)(oop + offset);
    }
    return neko_get_char_field(env, obj, fid);
}

NEKO_FAST_INLINE void neko_fast_set_C_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, jchar value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) { *(jchar*)(oop + offset) = value; return; }
    }
    neko_set_char_field(env, obj, fid, value);
}

NEKO_FAST_INLINE jchar neko_fast_get_static_C_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) return *(jchar*)(oop + offset);
    }
    return neko_get_static_char_field(env, cls, fid);
}

NEKO_FAST_INLINE void neko_fast_set_static_C_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, jchar value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) { *(jchar*)(oop + offset) = value; return; }
    }
    neko_set_static_char_field(env, cls, fid, value);
}

NEKO_FAST_INLINE jshort neko_fast_get_S_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) return *(jshort*)(oop + offset);
    }
    return neko_get_short_field(env, obj, fid);
}

NEKO_FAST_INLINE void neko_fast_set_S_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, jshort value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) { *(jshort*)(oop + offset) = value; return; }
    }
    neko_set_short_field(env, obj, fid, value);
}

NEKO_FAST_INLINE jshort neko_fast_get_static_S_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) return *(jshort*)(oop + offset);
    }
    return neko_get_static_short_field(env, cls, fid);
}

NEKO_FAST_INLINE void neko_fast_set_static_S_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, jshort value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) { *(jshort*)(oop + offset) = value; return; }
    }
    neko_set_static_short_field(env, cls, fid, value);
}

NEKO_FAST_INLINE jint neko_fast_get_I_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) return *(jint*)(oop + offset);
    }
    return neko_get_int_field(env, obj, fid);
}

NEKO_FAST_INLINE void neko_fast_set_I_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, jint value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) { *(jint*)(oop + offset) = value; return; }
    }
    neko_set_int_field(env, obj, fid, value);
}

NEKO_FAST_INLINE jint neko_fast_get_static_I_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) return *(jint*)(oop + offset);
    }
    return neko_get_static_int_field(env, cls, fid);
}

NEKO_FAST_INLINE void neko_fast_set_static_I_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, jint value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) { *(jint*)(oop + offset) = value; return; }
    }
    neko_set_static_int_field(env, cls, fid, value);
}

NEKO_FAST_INLINE jlong neko_fast_get_J_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) return *(jlong*)(oop + offset);
    }
    return neko_get_long_field(env, obj, fid);
}

NEKO_FAST_INLINE void neko_fast_set_J_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, jlong value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) { *(jlong*)(oop + offset) = value; return; }
    }
    neko_set_long_field(env, obj, fid, value);
}

NEKO_FAST_INLINE jlong neko_fast_get_static_J_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) return *(jlong*)(oop + offset);
    }
    return neko_get_static_long_field(env, cls, fid);
}

NEKO_FAST_INLINE void neko_fast_set_static_J_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, jlong value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) { *(jlong*)(oop + offset) = value; return; }
    }
    neko_set_static_long_field(env, cls, fid, value);
}

NEKO_FAST_INLINE jfloat neko_fast_get_F_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) return *(jfloat*)(oop + offset);
    }
    return neko_get_float_field(env, obj, fid);
}

NEKO_FAST_INLINE void neko_fast_set_F_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, jfloat value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) { *(jfloat*)(oop + offset) = value; return; }
    }
    neko_set_float_field(env, obj, fid, value);
}

NEKO_FAST_INLINE jfloat neko_fast_get_static_F_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) return *(jfloat*)(oop + offset);
    }
    return neko_get_static_float_field(env, cls, fid);
}

NEKO_FAST_INLINE void neko_fast_set_static_F_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, jfloat value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) { *(jfloat*)(oop + offset) = value; return; }
    }
    neko_set_static_float_field(env, cls, fid, value);
}

NEKO_FAST_INLINE jdouble neko_fast_get_D_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) return *(jdouble*)(oop + offset);
    }
    return neko_get_double_field(env, obj, fid);
}

NEKO_FAST_INLINE void neko_fast_set_D_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, jdouble value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) { *(jdouble*)(oop + offset) = value; return; }
    }
    neko_set_double_field(env, obj, fid, value);
}

NEKO_FAST_INLINE jdouble neko_fast_get_static_D_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) return *(jdouble*)(oop + offset);
    }
    return neko_get_static_double_field(env, cls, fid);
}

NEKO_FAST_INLINE void neko_fast_set_static_D_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, jdouble value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {
        char *oop = (char*)neko_handle_oop(staticBase);
        if (oop != NULL) { *(jdouble*)(oop + offset) = value; return; }
    }
    neko_set_static_double_field(env, cls, fid, value);
}

NEKO_FAST_INLINE jboolean neko_fast_zaload(JNIEnv *env, jarray arr, jint idx) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_Z] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_Z]);
            return *(jboolean*)addr;
        }
    }
    { jboolean value = (jboolean)0;
        neko_get_boolean_array_region(env, (jbooleanArray)arr, idx, 1, &value);
        return value;
    }
}

NEKO_FAST_INLINE void neko_fast_zastore(JNIEnv *env, jarray arr, jint idx, jboolean value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_Z] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_Z]);
            *(jboolean*)addr = value;
            return;
        }
    }
    neko_set_boolean_array_region(env, (jbooleanArray)arr, idx, 1, &value);
}

NEKO_FAST_INLINE jbyte neko_fast_baload(JNIEnv *env, jarray arr, jint idx) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_B]);
            return *(jbyte*)addr;
        }
    }
    { jbyte value = (jbyte)0;
        neko_get_byte_array_region(env, (jbyteArray)arr, idx, 1, &value);
        return value;
    }
}

NEKO_FAST_INLINE void neko_fast_bastore(JNIEnv *env, jarray arr, jint idx, jbyte value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_B]);
            *(jbyte*)addr = value;
            return;
        }
    }
    neko_set_byte_array_region(env, (jbyteArray)arr, idx, 1, &value);
}

NEKO_FAST_INLINE jchar neko_fast_caload(JNIEnv *env, jarray arr, jint idx) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_C] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_C]);
            return *(jchar*)addr;
        }
    }
    { jchar value = (jchar)0;
        neko_get_char_array_region(env, (jcharArray)arr, idx, 1, &value);
        return value;
    }
}

NEKO_FAST_INLINE void neko_fast_castore(JNIEnv *env, jarray arr, jint idx, jchar value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_C] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_C]);
            *(jchar*)addr = value;
            return;
        }
    }
    neko_set_char_array_region(env, (jcharArray)arr, idx, 1, &value);
}

NEKO_FAST_INLINE jshort neko_fast_saload(JNIEnv *env, jarray arr, jint idx) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_S] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_S]);
            return *(jshort*)addr;
        }
    }
    { jshort value = (jshort)0;
        neko_get_short_array_region(env, (jshortArray)arr, idx, 1, &value);
        return value;
    }
}

NEKO_FAST_INLINE void neko_fast_sastore(JNIEnv *env, jarray arr, jint idx, jshort value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_S] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_S]);
            *(jshort*)addr = value;
            return;
        }
    }
    neko_set_short_array_region(env, (jshortArray)arr, idx, 1, &value);
}

NEKO_FAST_INLINE jint neko_fast_iaload(JNIEnv *env, jarray arr, jint idx) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_I]);
            return *(jint*)addr;
        }
    }
    { jint value = (jint)0;
        neko_get_int_array_region(env, (jintArray)arr, idx, 1, &value);
        return value;
    }
}

NEKO_FAST_INLINE void neko_fast_iastore(JNIEnv *env, jarray arr, jint idx, jint value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_I]);
            *(jint*)addr = value;
            return;
        }
    }
    neko_set_int_array_region(env, (jintArray)arr, idx, 1, &value);
}

NEKO_FAST_INLINE jlong neko_fast_laload(JNIEnv *env, jarray arr, jint idx) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_J] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_J]);
            return *(jlong*)addr;
        }
    }
    { jlong value = (jlong)0;
        neko_get_long_array_region(env, (jlongArray)arr, idx, 1, &value);
        return value;
    }
}

NEKO_FAST_INLINE void neko_fast_lastore(JNIEnv *env, jarray arr, jint idx, jlong value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_J] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_J]);
            *(jlong*)addr = value;
            return;
        }
    }
    neko_set_long_array_region(env, (jlongArray)arr, idx, 1, &value);
}

NEKO_FAST_INLINE jfloat neko_fast_faload(JNIEnv *env, jarray arr, jint idx) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_F] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_F]);
            return *(jfloat*)addr;
        }
    }
    { jfloat value = (jfloat)0;
        neko_get_float_array_region(env, (jfloatArray)arr, idx, 1, &value);
        return value;
    }
}

NEKO_FAST_INLINE void neko_fast_fastore(JNIEnv *env, jarray arr, jint idx, jfloat value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_F] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_F]);
            *(jfloat*)addr = value;
            return;
        }
    }
    neko_set_float_array_region(env, (jfloatArray)arr, idx, 1, &value);
}

NEKO_FAST_INLINE jdouble neko_fast_daload(JNIEnv *env, jarray arr, jint idx) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_D] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_D]);
            return *(jdouble*)addr;
        }
    }
    { jdouble value = (jdouble)0;
        neko_get_double_array_region(env, (jdoubleArray)arr, idx, 1, &value);
        return value;
    }
}

NEKO_FAST_INLINE void neko_fast_dastore(JNIEnv *env, jarray arr, jint idx, jdouble value) {
    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0) {
        jint arrayLen = neko_fast_array_length(env, arr);
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL && idx >= 0 && idx < arrayLen) {
            char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_D] + ((jlong)idx * g_hotspot.primitive_array_index_scales[NEKO_PRIM_D]);
            *(jdouble*)addr = value;
            return;
        }
    }
    neko_set_double_array_region(env, (jdoubleArray)arr, idx, 1, &value);
}

// === Signature dispatch helpers ===
typedef int32_t (*neko_sig_0_static_fn)(int32_t, int32_t);
__attribute__((visibility("hidden"))) int32_t neko_sig_0_dispatch_static(const NekoManifestMethod* entry, int32_t p0, int32_t p1) {
    return ((neko_sig_0_static_fn)entry->impl_fn)(p0, p1);
}

typedef int32_t (*neko_sig_0_instance_fn)(void*, int32_t, int32_t);
__attribute__((visibility("hidden"))) int32_t neko_sig_0_dispatch_instance(const NekoManifestMethod* entry, void* _this, int32_t p0, int32_t p1) {
    return ((neko_sig_0_instance_fn)entry->impl_fn)(_this, p0, p1);
}

typedef void (*neko_sig_1_static_fn)(void);
__attribute__((visibility("hidden"))) void neko_sig_1_dispatch_static(const NekoManifestMethod* entry) {
    ((neko_sig_1_static_fn)entry->impl_fn)();
    return;
}

typedef void (*neko_sig_1_instance_fn)(void*);
__attribute__((visibility("hidden"))) void neko_sig_1_dispatch_instance(const NekoManifestMethod* entry, void* _this) {
    ((neko_sig_1_instance_fn)entry->impl_fn)(_this);
    return;
}

typedef void* (*neko_sig_2_static_fn)(int32_t);
__attribute__((visibility("hidden"))) void* neko_sig_2_dispatch_static(const NekoManifestMethod* entry, int32_t p0) {
    return ((neko_sig_2_static_fn)entry->impl_fn)(p0);
}

typedef void* (*neko_sig_2_instance_fn)(void*, int32_t);
__attribute__((visibility("hidden"))) void* neko_sig_2_dispatch_instance(const NekoManifestMethod* entry, void* _this, int32_t p0) {
    return ((neko_sig_2_instance_fn)entry->impl_fn)(_this, p0);
}

typedef void (*neko_sig_3_static_fn)(void*);
__attribute__((visibility("hidden"))) void neko_sig_3_dispatch_static(const NekoManifestMethod* entry, void* p0) {
    ((neko_sig_3_static_fn)entry->impl_fn)(p0);
    return;
}

typedef void (*neko_sig_3_instance_fn)(void*, void*);
__attribute__((visibility("hidden"))) void neko_sig_3_dispatch_instance(const NekoManifestMethod* entry, void* _this, void* p0) {
    ((neko_sig_3_instance_fn)entry->impl_fn)(_this, p0);
    return;
}

typedef void (*neko_sig_4_static_fn)(int32_t);
__attribute__((visibility("hidden"))) void neko_sig_4_dispatch_static(const NekoManifestMethod* entry, int32_t p0) {
    ((neko_sig_4_static_fn)entry->impl_fn)(p0);
    return;
}

typedef void (*neko_sig_4_instance_fn)(void*, int32_t);
__attribute__((visibility("hidden"))) void neko_sig_4_dispatch_instance(const NekoManifestMethod* entry, void* _this, int32_t p0) {
    ((neko_sig_4_instance_fn)entry->impl_fn)(_this, p0);
    return;
}


#define NEKO_REQUIRED_VM_SYMBOL_COUNT 24u
#define NEKO_REQUIRED_VM_SYMBOLS(X)     X(gHotSpotVMStructs)     X(gHotSpotVMStructEntryTypeNameOffset)     X(gHotSpotVMStructEntryFieldNameOffset)     X(gHotSpotVMStructEntryTypeStringOffset)     X(gHotSpotVMStructEntryIsStaticOffset)     X(gHotSpotVMStructEntryOffsetOffset)     X(gHotSpotVMStructEntryAddressOffset)     X(gHotSpotVMStructEntryArrayStride)     X(gHotSpotVMTypes)     X(gHotSpotVMTypeEntryTypeNameOffset)     X(gHotSpotVMTypeEntrySuperclassNameOffset)     X(gHotSpotVMTypeEntryIsOopTypeOffset)     X(gHotSpotVMTypeEntryIsIntegerTypeOffset)     X(gHotSpotVMTypeEntryIsUnsignedOffset)     X(gHotSpotVMTypeEntrySizeOffset)     X(gHotSpotVMTypeEntryArrayStride)     X(gHotSpotVMIntConstants)     X(gHotSpotVMIntConstantEntryNameOffset)     X(gHotSpotVMIntConstantEntryValueOffset)     X(gHotSpotVMIntConstantEntryArrayStride)     X(gHotSpotVMLongConstants)     X(gHotSpotVMLongConstantEntryNameOffset)     X(gHotSpotVMLongConstantEntryValueOffset)     X(gHotSpotVMLongConstantEntryArrayStride)

#define JVM_CONSTANT_Utf8 1u
#define JVM_ACC_STATIC 0x0008u

typedef struct NekoVmSymbols {
#define NEKO_VM_SYMBOL_FIELD(name) void* name;
    NEKO_REQUIRED_VM_SYMBOLS(NEKO_VM_SYMBOL_FIELD)
#undef NEKO_VM_SYMBOL_FIELD
} NekoVmSymbols;

NekoVmSymbols g_neko_vm_symbols = {0};
NekoVmLayout g_neko_vm_layout = {0};

static JavaVM *g_neko_java_vm = NULL;
static uint32_t g_neko_manifest_match_count = 0u;
static volatile uint8_t g_neko_loader_ready = 0u;
static int g_neko_flag_patch_path_logged = 0;
static const char *g_neko_wave4a_unavailable_reason = "uninitialized";
static int g_neko_wave4a_handle_caveat_logged = 0;
static jboolean g_neko_use_compact_object_headers = JNI_FALSE;
enum {
    NEKO_STRING_ROOT_BACKEND_BOOT_CLD = 1,
    NEKO_STRING_ROOT_BACKEND_FALLBACK_REGENERATE = 2
};

typedef struct NekoChunkedHandleListChunk {
    oop data[32];
    uint32_t size;
    struct NekoChunkedHandleListChunk* next;
} NekoChunkedHandleListChunk;

static uint8_t g_neko_string_root_backend = NEKO_STRING_ROOT_BACKEND_FALLBACK_REGENERATE;
static uint8_t g_neko_boot_cld_root_chain_logged = 0u;
static NekoChunkedHandleListChunk* g_neko_string_root_chunk_head = NULL;

#if defined(_WIN32)
static HMODULE g_neko_libjvm_handle = NULL;
#else
static void *g_neko_libjvm_handle = NULL;
#endif

static int neko_debug_enabled(void) {
#ifdef NEKO_DEBUG_ENABLED
    return neko_debug_level;
#else
    return 0;
#endif
}

static int neko_debug_level_at_least(int level) {
    return neko_debug_enabled() >= level;
}

static int neko_parse_debug_level_text(const char *value) {
#ifdef NEKO_DEBUG_ENABLED
    char *end = NULL;
    long parsed;
    if (value == NULL || value[0] == ' ') return 0;
    if (strcmp(value, "true") == 0 || strcmp(value, "yes") == 0 || strcmp(value, "on") == 0) return 1;
    if (strcmp(value, "false") == 0 || strcmp(value, "no") == 0 || strcmp(value, "off") == 0) return 0;
    parsed = strtol(value, &end, 10);
    if (end == value || parsed <= 0) return 0;
    return parsed > 9 ? 9 : (int)parsed;
#else
    (void)value;
    return 0;
#endif
}

static void neko_init_debug_level_from_env(void) {
#ifdef NEKO_DEBUG_ENABLED
    const char *value = getenv("NEKO_DEBUG");
    if (value == NULL || value[0] == ' ') value = getenv("NEKO_NATIVE_DEBUG");
    neko_debug_level = neko_parse_debug_level_text(value);
#else
#endif
}

static void neko_vlog(FILE *stream, const char *fmt, va_list args) {
    fputs("neko: ", stream);
    vfprintf(stream, fmt, args);
    fputc('\n', stream);
    fflush(stream);
}

static void neko_debug_log(const char *fmt, ...) {
#ifdef NEKO_DEBUG_ENABLED
    va_list args;
    if (!neko_debug_enabled()) return;
    va_start(args, fmt);
    neko_vlog(stderr, fmt, args);
    va_end(args);
#else
    (void)fmt;
#endif
}

static void neko_native_debug_log(const char *fmt, ...) {
#ifdef NEKO_DEBUG_ENABLED
    va_list args;
    if (!neko_debug_enabled()) return;
    va_start(args, fmt);
    fputs("[nk] n ", stderr);
    vfprintf(stderr, fmt, args);
    fputc('\n', stderr);
    fflush(stderr);
    va_end(args);
#else
    (void)fmt;
#endif
}

static void neko_native_trace_log(int level, const char *fmt, ...) {
#ifdef NEKO_DEBUG_ENABLED
    va_list args;
    if (!neko_debug_level_at_least(level)) return;
    va_start(args, fmt);
    fputs("[nk] t ", stderr);
    vfprintf(stderr, fmt, args);
    fputc('\n', stderr);
    fflush(stderr);
    va_end(args);
#else
    (void)level;
    (void)fmt;
#endif
}

static void neko_error_log(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    neko_vlog(stderr, fmt, args);
    va_end(args);
}

static jboolean neko_streq(const char *a, const char *b) {
    return a != NULL && b != NULL && strcmp(a, b) == 0;
}

static jboolean neko_contains(const char *haystack, const char *needle) {
    return haystack != NULL && needle != NULL && strstr(haystack, needle) != NULL;
}

static jboolean neko_ends_with(const char *value, const char *suffix) {
    size_t value_len;
    size_t suffix_len;
    if (value == NULL || suffix == NULL) return JNI_FALSE;
    value_len = strlen(value);
    suffix_len = strlen(suffix);
    if (suffix_len > value_len) return JNI_FALSE;
    return memcmp(value + (value_len - suffix_len), suffix, suffix_len) == 0 ? JNI_TRUE : JNI_FALSE;
}

static uint32_t neko_fnv1a32_update_byte(uint32_t hash, uint8_t value) {
    return (hash ^ value) * 16777619u;
}

static uint32_t neko_fnv1a32_update_string(uint32_t hash, const char *value) {
    const unsigned char *cur = (const unsigned char*)value;
    if (value == NULL) return hash;
    while (*cur != '\0') {
        hash = neko_fnv1a32_update_byte(hash, *cur++);
    }
    return hash;
}

static uint32_t neko_fnv1a32(const char *value) {
    return neko_fnv1a32_update_string(2166136261u, value);
}

static uint32_t neko_fnv1a32_pair(const char *first, const char *second) {
    uint32_t hash = 2166136261u;
    hash = neko_fnv1a32_update_string(hash, first);
    hash = neko_fnv1a32_update_byte(hash, 0u);
    hash = neko_fnv1a32_update_string(hash, second);
    return hash;
}

static const void* neko_symbol_pointer(void *symbol_address) {
    return symbol_address == NULL ? NULL : *(const void* const*)symbol_address;
}

static int neko_symbol_int(void *symbol_address) {
    return symbol_address == NULL ? 0 : *(const int*)symbol_address;
}

static int64_t neko_symbol_int64(void *symbol_address) {
    return symbol_address == NULL ? 0 : *(const int64_t*)symbol_address;
}

static ptrdiff_t neko_align_up_ptrdiff(ptrdiff_t value, ptrdiff_t alignment) {
    ptrdiff_t remainder;
    if (alignment <= 0) return value;
    remainder = value % alignment;
    return remainder == 0 ? value : value + (alignment - remainder);
}

static ptrdiff_t neko_compose_nested_offset(ptrdiff_t base_offset, ptrdiff_t nested_offset) {
    if (base_offset < 0 || nested_offset < 0) return -1;
    return base_offset + nested_offset;
}

static ptrdiff_t neko_known_pointer_field_offset(ptrdiff_t base_offset, size_t pointer_index) {
    if (base_offset < 0) return -1;
    return base_offset + (ptrdiff_t)(pointer_index * sizeof(uintptr_t));
}

static void neko_log_offset_strategy(const char *label, ptrdiff_t offset, char strategy) {
    if (offset >= 0) {
        NEKO_TRACE(1, "[nk] o %s=%td s=%c", label, offset, strategy);
    } else {
        NEKO_TRACE(1, "[nk] o %s u s=%c", label, strategy);
    }
}

static void neko_derive_wave2_layout_offsets(JNIEnv *env);
static void neko_resolve_prepared_class_field_sites(JNIEnv *env, jclass klass, const char *owner_internal, void *owner_klass);
static jboolean neko_prewarm_ldc_sites(JNIEnv *env);
static void neko_publish_prepared_ldc_class_site(JNIEnv *env, jclass klass, const char *signature, void *prepared_klass);
static bool neko_read_symbol_bytes(void* sym, const uint8_t** bytes_out, uint16_t* len_out);
static bool neko_cp_utf8_symbol(void* cp, int idx, void** sym_out);
static bool neko_field_walk_legacy(void* ik, uint32_t java_fields_count, const char* target_name, uint32_t target_name_len, const char* target_desc, uint32_t target_desc_len, bool want_static, int32_t* offset_out);
static bool neko_read_u5(const uint8_t* buf, int limit, int* p, uint32_t* out);
static bool neko_field_walk_fis21(void* ik, const char* target_name, uint32_t target_name_len, const char* target_desc, uint32_t target_desc_len, bool want_static, int32_t* offset_out);
static bool neko_resolve_field_offset(void* klass, const char* target_name, uint32_t target_name_len, const char* target_desc, uint32_t target_desc_len, bool want_static, int32_t* offset_out);
static void neko_resolve_string_intern_layout(void);
static void neko_derive_bootstrap_wellknown_layout(void);
static void* neko_boot_cld_head(void);
static void* neko_create_ldc_string_oop(NekoManifestLdcSite *site, uint32_t *coder_out, uint32_t *char_length_out, uint8_t **key_bytes_out, uint32_t *key_payload_bytes_out);
static void neko_resolve_ldc_string(NekoManifestLdcSite *site);
static void neko_string_intern_prewarm_and_publish(JNIEnv *env);
static void* neko_load_oop_from_cell(const void *cell);
static void neko_store_oop_to_cell(void *cell, void *raw_oop);
static void neko_log_wave2_ready(void);
static jboolean neko_parse_vm_layout_strict(JNIEnv *env);
static jboolean neko_capture_wellknown_klasses(void);
static jclass g_neko_wave2_class_cls;
static jclass g_neko_wave2_field_cls;
static jclass g_neko_wave2_unsafe_cls;
static jobject g_neko_wave2_unsafe_singleton;

#if defined(_WIN32)
#define NEKO_GLOBAL_DELETE(slot) do { if ((slot) != NULL) { neko_delete_global_ref(env, (jobject)(slot)); (slot) = NULL; } } while (0)
#define NEKO_CLOSE_LIBJVM_IF_OWNED() ((void)0)
#elif defined(__APPLE__)
#define NEKO_GLOBAL_DELETE(slot) do { if ((slot) != NULL) { neko_delete_global_ref(env, (jobject)(slot)); (slot) = NULL; } } while (0)
#define NEKO_CLOSE_LIBJVM_IF_OWNED() do { if (g_neko_libjvm_handle != NULL && g_neko_libjvm_handle != RTLD_DEFAULT) { dlclose(g_neko_libjvm_handle); g_neko_libjvm_handle = NULL; } } while (0)
#else
#define NEKO_GLOBAL_DELETE(slot) do { if ((slot) != NULL) { neko_delete_global_ref(env, (jobject)(slot)); (slot) = NULL; } } while (0)
#define NEKO_CLOSE_LIBJVM_IF_OWNED() do { if (g_neko_libjvm_handle != NULL) { dlclose(g_neko_libjvm_handle); g_neko_libjvm_handle = NULL; } } while (0)
#endif

static void neko_manifest_teardown(JNIEnv *env) {
    NekoChunkedHandleListChunk *chunk = g_neko_string_root_chunk_head;
    if (env == NULL) return;
    g_neko_string_root_chunk_head = NULL;
    while (chunk != NULL) {
        NekoChunkedHandleListChunk *next = chunk->next;
        free(chunk);
        chunk = next;
    }
    NEKO_GLOBAL_DELETE(g_neko_throw_npe);
    NEKO_GLOBAL_DELETE(g_neko_throw_aioobe);
    NEKO_GLOBAL_DELETE(g_neko_throw_cce);
    NEKO_GLOBAL_DELETE(g_neko_throw_ae);
    NEKO_GLOBAL_DELETE(g_neko_throw_le);
    NEKO_GLOBAL_DELETE(g_neko_throw_oom);
    NEKO_GLOBAL_DELETE(g_neko_throw_imse);
    NEKO_GLOBAL_DELETE(g_neko_throw_ase);
    NEKO_GLOBAL_DELETE(g_neko_throw_nase);
    NEKO_GLOBAL_DELETE(g_neko_throw_bme);
    NEKO_GLOBAL_DELETE(g_neko_throw_loader_linkage);
    NEKO_GLOBAL_DELETE(g_neko_wave2_class_cls);
    NEKO_GLOBAL_DELETE(g_neko_wave2_field_cls);
    NEKO_GLOBAL_DELETE(g_neko_wave2_unsafe_cls);
    NEKO_GLOBAL_DELETE(g_neko_wave2_unsafe_singleton);
    NEKO_CLOSE_LIBJVM_IF_OWNED();
}

#undef NEKO_CLOSE_LIBJVM_IF_OWNED
#undef NEKO_GLOBAL_DELETE

static void neko_derive_thread_tlab_top_offset(void) {
    ptrdiff_t start_source = -1;
    g_neko_vm_layout.off_thread_tlab_top = -1;
    g_neko_vm_layout.thread_tlab_top_strategy = 'D';
    if (g_neko_vm_layout.off_thread_tlab_top_direct >= 0) {
        g_neko_vm_layout.off_thread_tlab_top = g_neko_vm_layout.off_thread_tlab_top_direct;
        g_neko_vm_layout.thread_tlab_top_strategy = 'A';
    } else if (g_neko_vm_layout.off_thread_tlab >= 0 && g_neko_vm_layout.off_tlab_top >= 0) {
        g_neko_vm_layout.off_thread_tlab_top = neko_compose_nested_offset(g_neko_vm_layout.off_thread_tlab, g_neko_vm_layout.off_tlab_top);
        g_neko_vm_layout.thread_tlab_top_strategy = 'B';
    } else {
        if (g_neko_vm_layout.off_thread_tlab_start_direct >= 0) {
            start_source = g_neko_vm_layout.off_thread_tlab_start_direct;
        } else if (g_neko_vm_layout.off_thread_tlab >= 0 && g_neko_vm_layout.off_tlab_start >= 0) {
            start_source = neko_compose_nested_offset(g_neko_vm_layout.off_thread_tlab, g_neko_vm_layout.off_tlab_start);
        }
        if (start_source >= 0) {
            g_neko_vm_layout.off_thread_tlab_top = neko_known_pointer_field_offset(start_source, 1u);
            g_neko_vm_layout.thread_tlab_top_strategy = 'C';
        }
    }
    neko_log_offset_strategy("thread_tlab_top_offset", g_neko_vm_layout.off_thread_tlab_top, g_neko_vm_layout.thread_tlab_top_strategy);
}

static void neko_derive_thread_tlab_start_offset(void) {
    g_neko_vm_layout.off_thread_tlab_start = -1;
    g_neko_vm_layout.thread_tlab_start_strategy = 'D';
    if (g_neko_vm_layout.off_thread_tlab_start_direct >= 0) {
        g_neko_vm_layout.off_thread_tlab_start = g_neko_vm_layout.off_thread_tlab_start_direct;
        g_neko_vm_layout.thread_tlab_start_strategy = 'A';
    } else if (g_neko_vm_layout.off_thread_tlab >= 0 && g_neko_vm_layout.off_tlab_start >= 0) {
        g_neko_vm_layout.off_thread_tlab_start = neko_compose_nested_offset(g_neko_vm_layout.off_thread_tlab, g_neko_vm_layout.off_tlab_start);
        g_neko_vm_layout.thread_tlab_start_strategy = 'B';
    } else if (g_neko_vm_layout.off_thread_tlab_top >= 0) {
        g_neko_vm_layout.off_thread_tlab_start = g_neko_vm_layout.off_thread_tlab_top - (ptrdiff_t)sizeof(uintptr_t);
        g_neko_vm_layout.thread_tlab_start_strategy = 'C';
    }
    neko_log_offset_strategy("thread_tlab_start_offset", g_neko_vm_layout.off_thread_tlab_start, g_neko_vm_layout.thread_tlab_start_strategy);
}

static void neko_derive_thread_tlab_pf_top_offset(void) {
    g_neko_vm_layout.off_thread_tlab_pf_top = -1;
    g_neko_vm_layout.thread_tlab_pf_top_strategy = 'D';
    if (g_neko_vm_layout.off_thread_tlab_pf_top_direct >= 0) {
        g_neko_vm_layout.off_thread_tlab_pf_top = g_neko_vm_layout.off_thread_tlab_pf_top_direct;
        g_neko_vm_layout.thread_tlab_pf_top_strategy = 'A';
    } else if (g_neko_vm_layout.off_thread_tlab >= 0 && g_neko_vm_layout.off_tlab_pf_top >= 0) {
        g_neko_vm_layout.off_thread_tlab_pf_top = neko_compose_nested_offset(g_neko_vm_layout.off_thread_tlab, g_neko_vm_layout.off_tlab_pf_top);
        g_neko_vm_layout.thread_tlab_pf_top_strategy = 'B';
    } else if (g_neko_vm_layout.off_thread_tlab_top >= 0) {
        g_neko_vm_layout.off_thread_tlab_pf_top = g_neko_vm_layout.off_thread_tlab_top + (ptrdiff_t)sizeof(uintptr_t);
        g_neko_vm_layout.thread_tlab_pf_top_strategy = 'C';
    }
    neko_log_offset_strategy("thread_tlab_pf_top_offset", g_neko_vm_layout.off_thread_tlab_pf_top, g_neko_vm_layout.thread_tlab_pf_top_strategy);
}

static void neko_derive_thread_tlab_end_offset(void) {
    g_neko_vm_layout.off_thread_tlab_end = -1;
    g_neko_vm_layout.thread_tlab_end_strategy = 'D';
    if (g_neko_vm_layout.off_thread_tlab_end_direct >= 0) {
        g_neko_vm_layout.off_thread_tlab_end = g_neko_vm_layout.off_thread_tlab_end_direct;
        g_neko_vm_layout.thread_tlab_end_strategy = 'A';
    } else if (g_neko_vm_layout.off_thread_tlab >= 0 && g_neko_vm_layout.off_tlab_end >= 0) {
        g_neko_vm_layout.off_thread_tlab_end = neko_compose_nested_offset(g_neko_vm_layout.off_thread_tlab, g_neko_vm_layout.off_tlab_end);
        g_neko_vm_layout.thread_tlab_end_strategy = 'B';
    } else if (g_neko_vm_layout.off_thread_tlab_pf_top >= 0) {
        g_neko_vm_layout.off_thread_tlab_end = g_neko_vm_layout.off_thread_tlab_pf_top + (ptrdiff_t)sizeof(uintptr_t);
        g_neko_vm_layout.thread_tlab_end_strategy = 'C';
    } else if (g_neko_vm_layout.off_thread_tlab_top >= 0) {
        g_neko_vm_layout.off_thread_tlab_end = g_neko_vm_layout.off_thread_tlab_top + (ptrdiff_t)(2u * sizeof(uintptr_t));
        g_neko_vm_layout.thread_tlab_end_strategy = 'C';
    }
    neko_log_offset_strategy("thread_tlab_end_offset", g_neko_vm_layout.off_thread_tlab_end, g_neko_vm_layout.thread_tlab_end_strategy);
}

static void neko_derive_thread_exception_oop_offset(void) {
    g_neko_vm_layout.thread_exception_oop_strategy = 'D';
    if (g_neko_vm_layout.off_thread_exception_oop >= 0) {
        g_neko_vm_layout.thread_exception_oop_strategy = 'A';
    } else if (g_neko_vm_layout.off_thread_exception_pc >= 0) {
        g_neko_vm_layout.off_thread_exception_oop = g_neko_vm_layout.off_thread_exception_pc - (ptrdiff_t)sizeof(uintptr_t);
        g_neko_vm_layout.thread_exception_oop_strategy = 'B';
    }
    neko_log_offset_strategy("thread_exception_oop_offset", g_neko_vm_layout.off_thread_exception_oop, g_neko_vm_layout.thread_exception_oop_strategy);
}

static void neko_derive_thread_exception_pc_offset(void) {
    g_neko_vm_layout.thread_exception_pc_strategy = 'D';
    if (g_neko_vm_layout.off_thread_exception_pc >= 0) {
        g_neko_vm_layout.thread_exception_pc_strategy = 'A';
    } else if (g_neko_vm_layout.off_thread_exception_oop >= 0) {
        g_neko_vm_layout.off_thread_exception_pc = g_neko_vm_layout.off_thread_exception_oop + (ptrdiff_t)sizeof(uintptr_t);
        g_neko_vm_layout.thread_exception_pc_strategy = 'B';
    }
    neko_log_offset_strategy("thread_exception_pc_offset", g_neko_vm_layout.off_thread_exception_pc, g_neko_vm_layout.thread_exception_pc_strategy);
}

static void neko_derive_java_thread_anchor_offset(void) {
    g_neko_vm_layout.java_thread_anchor_strategy = g_neko_vm_layout.off_java_thread_anchor >= 0 ? 'A' : 'D';
    neko_log_offset_strategy("java_thread_anchor_offset", g_neko_vm_layout.off_java_thread_anchor, g_neko_vm_layout.java_thread_anchor_strategy);
}

static void neko_derive_java_thread_jni_environment_offset(void) {
    ptrdiff_t anchor_size;
    g_neko_vm_layout.java_thread_jni_environment_strategy = 'D';
    if (g_neko_vm_layout.off_java_thread_jni_environment >= 0) {
        g_neko_vm_layout.java_thread_jni_environment_strategy = 'A';
    } else if (g_neko_vm_layout.off_java_thread_anchor >= 0 && g_neko_vm_layout.java_frame_anchor_size > 0u) {
        anchor_size = neko_align_up_ptrdiff((ptrdiff_t)g_neko_vm_layout.java_frame_anchor_size, (ptrdiff_t)sizeof(uintptr_t));
        g_neko_vm_layout.off_java_thread_jni_environment = g_neko_vm_layout.off_java_thread_anchor + anchor_size + (ptrdiff_t)sizeof(uintptr_t);
        g_neko_vm_layout.java_thread_jni_environment_strategy = 'B';
    } else if (g_neko_vm_layout.off_java_thread_anchor >= 0) {
        g_neko_vm_layout.off_java_thread_jni_environment = g_neko_vm_layout.off_java_thread_anchor + (ptrdiff_t)(3u * sizeof(uintptr_t));
        g_neko_vm_layout.java_thread_jni_environment_strategy = 'C';
    }
    neko_log_offset_strategy("java_thread_jni_environment_offset", g_neko_vm_layout.off_java_thread_jni_environment, g_neko_vm_layout.java_thread_jni_environment_strategy);
}

static void neko_derive_instance_klass_java_mirror_offset(void) {
    g_neko_vm_layout.instance_klass_java_mirror_strategy = 'D';
    if (g_neko_vm_layout.off_instance_klass_java_mirror >= 0) {
        g_neko_vm_layout.instance_klass_java_mirror_strategy = 'A';
    } else if (g_neko_vm_layout.off_klass_java_mirror >= 0) {
        g_neko_vm_layout.off_instance_klass_java_mirror = g_neko_vm_layout.off_klass_java_mirror;
        g_neko_vm_layout.instance_klass_java_mirror_strategy = 'B';
    }
    neko_log_offset_strategy("instance_klass_java_mirror_offset", g_neko_vm_layout.off_instance_klass_java_mirror, g_neko_vm_layout.instance_klass_java_mirror_strategy);
}

static void neko_derive_thread_thread_state_offset(void) {
    g_neko_vm_layout.thread_thread_state_strategy = g_neko_vm_layout.off_thread_thread_state >= 0 ? 'A' : 'D';
    neko_log_offset_strategy("thread_thread_state_offset", g_neko_vm_layout.off_thread_thread_state, g_neko_vm_layout.thread_thread_state_strategy);
}

static void neko_derive_java_thread_last_Java_sp_offset(void) {
    g_neko_vm_layout.java_thread_last_Java_sp_strategy = 'D';
    if (g_neko_vm_layout.off_java_thread_last_Java_sp >= 0) {
        g_neko_vm_layout.java_thread_last_Java_sp_strategy = 'A';
    } else if (g_neko_vm_layout.off_java_thread_anchor >= 0 && g_neko_vm_layout.off_java_frame_anchor_sp >= 0) {
        g_neko_vm_layout.off_java_thread_last_Java_sp = neko_compose_nested_offset(g_neko_vm_layout.off_java_thread_anchor, g_neko_vm_layout.off_java_frame_anchor_sp);
        g_neko_vm_layout.java_thread_last_Java_sp_strategy = 'B';
    }
    neko_log_offset_strategy("java_thread_last_Java_sp_offset", g_neko_vm_layout.off_java_thread_last_Java_sp, g_neko_vm_layout.java_thread_last_Java_sp_strategy);
}

static void neko_derive_java_thread_last_Java_fp_offset(void) {
    g_neko_vm_layout.java_thread_last_Java_fp_strategy = 'D';
    if (g_neko_vm_layout.off_java_thread_last_Java_fp >= 0) {
        g_neko_vm_layout.java_thread_last_Java_fp_strategy = 'A';
    } else if (g_neko_vm_layout.off_java_thread_anchor >= 0 && g_neko_vm_layout.off_java_frame_anchor_fp >= 0) {
        g_neko_vm_layout.off_java_thread_last_Java_fp = neko_compose_nested_offset(g_neko_vm_layout.off_java_thread_anchor, g_neko_vm_layout.off_java_frame_anchor_fp);
        g_neko_vm_layout.java_thread_last_Java_fp_strategy = 'B';
    }
    neko_log_offset_strategy("java_thread_last_Java_fp_offset", g_neko_vm_layout.off_java_thread_last_Java_fp, g_neko_vm_layout.java_thread_last_Java_fp_strategy);
}

static void neko_derive_java_thread_last_Java_pc_offset(void) {
    g_neko_vm_layout.java_thread_last_Java_pc_strategy = 'D';
    if (g_neko_vm_layout.off_java_thread_last_Java_pc >= 0) {
        g_neko_vm_layout.java_thread_last_Java_pc_strategy = 'A';
    } else if (g_neko_vm_layout.off_java_thread_anchor >= 0 && g_neko_vm_layout.off_java_frame_anchor_pc >= 0) {
        g_neko_vm_layout.off_java_thread_last_Java_pc = neko_compose_nested_offset(g_neko_vm_layout.off_java_thread_anchor, g_neko_vm_layout.off_java_frame_anchor_pc);
        g_neko_vm_layout.java_thread_last_Java_pc_strategy = 'B';
    }
    neko_log_offset_strategy("java_thread_last_Java_pc_offset", g_neko_vm_layout.off_java_thread_last_Java_pc, g_neko_vm_layout.java_thread_last_Java_pc_strategy);
}

static void neko_derive_oophandle_obj_offset(void) {
    g_neko_vm_layout.oophandle_obj_strategy = 'D';
    if (g_neko_vm_layout.java_spec_version < 9) {
        g_neko_vm_layout.off_oophandle_obj = 0;
        g_neko_vm_layout.oophandle_obj_strategy = 'N';
    } else if (g_neko_vm_layout.off_oophandle_obj >= 0) {
        g_neko_vm_layout.oophandle_obj_strategy = 'A';
    } else {
        g_neko_vm_layout.off_oophandle_obj = 0;
        g_neko_vm_layout.oophandle_obj_strategy = 'B';
    }
    neko_log_offset_strategy("oophandle_obj_offset", g_neko_vm_layout.off_oophandle_obj, g_neko_vm_layout.oophandle_obj_strategy);
}

static void neko_log_instance_klass_static_field_offsets(void) {
    neko_log_offset_strategy(
        "instance_klass_static_field_size_offset",
        g_neko_vm_layout.off_instance_klass_static_field_size,
        g_neko_vm_layout.off_instance_klass_static_field_size >= 0 ? 'A' : 'D'
    );
    neko_log_offset_strategy(
        "instance_klass_static_oop_field_count_offset",
        g_neko_vm_layout.off_instance_klass_static_oop_field_count,
        g_neko_vm_layout.off_instance_klass_static_oop_field_count >= 0 ? 'A' : 'D'
    );
}

static void neko_derive_cld_handles_offset(void) {
    ptrdiff_t candidate = -1;
    g_neko_vm_layout.off_cld_handles = -1;
    if (g_neko_vm_layout.off_cld_klasses < 0) {
        return;
    }
    candidate = g_neko_vm_layout.java_spec_version <= 8
        ? g_neko_vm_layout.off_cld_klasses + (ptrdiff_t)sizeof(uintptr_t)
        : g_neko_vm_layout.off_cld_klasses - (ptrdiff_t)sizeof(uintptr_t);
    if (candidate < 0) {
        return;
    }
    g_neko_vm_layout.off_cld_handles = candidate;
}

static void neko_log_strict_vm_layout_ok(void) {
#ifdef NEKO_DEBUG_ENABLED
    if (!neko_debug_enabled()) return;
    neko_native_debug_log(
        "strict_vm_layout_ok=1 next_link=%td cld_klasses=%td cld_handles=%td klass_mirror=%td",
        g_neko_vm_layout.off_klass_next_link,
        g_neko_vm_layout.off_cld_klasses,
        g_neko_vm_layout.off_cld_handles,
        g_neko_vm_layout.off_klass_java_mirror
    );
#endif
}

static const char* neko_validate_wave4a_layout(void) {
    if (g_neko_vm_layout.off_thread_thread_state < 0) return "JavaThread::_thread_state";
    if (g_neko_vm_layout.off_java_thread_last_Java_sp < 0) return "JavaThread::_anchor._last_Java_sp";
    if (g_neko_vm_layout.off_java_thread_last_Java_fp < 0) return "JavaThread::_anchor._last_Java_fp";
    if (g_neko_vm_layout.off_java_thread_last_Java_pc < 0) return "JavaThread::_anchor._last_Java_pc";
    if (g_neko_vm_layout.off_klass_java_mirror < 0) return "Klass::_java_mirror";
    if (g_neko_vm_layout.thread_state_in_java < 0) return "_thread_in_Java";
    if (g_neko_vm_layout.thread_state_in_vm < 0) return "_thread_in_vm";
    return NULL;
}

static void neko_configure_wave4a_layout(void) {
    const char *missing;
    g_neko_vm_layout.wave4a_disabled = JNI_TRUE;
    g_neko_wave4a_unavailable_reason = "uninitialized";
    neko_derive_instance_klass_java_mirror_offset();
    neko_derive_thread_thread_state_offset();
    neko_derive_java_thread_last_Java_sp_offset();
    neko_derive_java_thread_last_Java_fp_offset();
    neko_derive_java_thread_last_Java_pc_offset();
    neko_derive_oophandle_obj_offset();
    missing = neko_validate_wave4a_layout();
    if (missing != NULL) {
        g_neko_wave4a_unavailable_reason = missing;
        return;
    }
    g_neko_vm_layout.wave4a_disabled = JNI_FALSE;
    g_neko_wave4a_unavailable_reason = NULL;
}


static inline u8 neko_align_up_u8(u8 value, u8 alignment) {
    u8 mask;
    if (alignment == 0u) return value;
    mask = alignment - 1u;
    return (value + mask) & ~mask;
}

typedef struct {
    int start_pc;
    int end_pc;
    int handler_pc;
    void *catch_klass;
} neko_exception_handler_entry;


static inline JNIEnv* neko_current_env(void) {
    JNIEnv *env = NULL;
    jint env_status;
    if (g_neko_java_vm == NULL) return NULL;
    env_status = (*g_neko_java_vm)->GetEnv(g_neko_java_vm, (void**)&env, JNI_VERSION_1_6);
    if (env_status != JNI_OK || env == NULL) return NULL;
    return env;
}

static inline void* neko_decode_heap_oop(u4 narrow) {
    if (narrow == 0u) return NULL;
    return (void*)(g_neko_vm_layout.narrow_oop_base + ((uintptr_t)narrow << g_neko_vm_layout.narrow_oop_shift));
}

static inline u4 neko_encode_heap_oop(void *wide) {
    uintptr_t value;
    if (wide == NULL) return 0u;
    value = (uintptr_t)wide;
    if (value < g_neko_vm_layout.narrow_oop_base) return 0u;
    return (u4)((value - g_neko_vm_layout.narrow_oop_base) >> g_neko_vm_layout.narrow_oop_shift);
}

static inline void* neko_decode_klass_pointer(u4 narrow) {
    if (narrow == 0u) return NULL;
    return (void*)(g_neko_vm_layout.narrow_klass_base + ((uintptr_t)narrow << g_neko_vm_layout.narrow_klass_shift));
}

static inline u4 neko_encode_klass_pointer(void *wide) {
    uintptr_t value;
    if (wide == NULL) return 0u;
    value = (uintptr_t)wide;
    if (value < g_neko_vm_layout.narrow_klass_base) return 0u;
    return (u4)((value - g_neko_vm_layout.narrow_klass_base) >> g_neko_vm_layout.narrow_klass_shift);
}

__attribute__((visibility("default"))) void* neko_get_current_thread(void) {
    JNIEnv *env = neko_current_env();
    if (env == NULL || g_neko_vm_layout.off_java_thread_jni_environment < 0) return NULL;
    return (void*)((uint8_t*)env - g_neko_vm_layout.off_java_thread_jni_environment);
}

static inline void* neko_pending_exception(void *thread) {
    if (thread == NULL || g_neko_vm_layout.off_thread_pending_exception < 0) return NULL;
    return *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_pending_exception);
}

static inline void neko_set_pending_exception(void *thread, void *oop) {
    if (thread == NULL || g_neko_vm_layout.off_thread_pending_exception < 0) return;
    *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_pending_exception) = oop;
    if (g_neko_vm_layout.off_thread_exception_oop >= 0) {
        *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_exception_oop) = oop;
    }
    if (g_neko_vm_layout.off_thread_exception_pc >= 0) {
        *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_exception_pc) = NULL;
    }
}

static inline void neko_clear_pending_exception(void *thread) {
    if (thread == NULL || g_neko_vm_layout.off_thread_pending_exception < 0) return;
    *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_pending_exception) = NULL;
    if (g_neko_vm_layout.off_thread_exception_oop >= 0) {
        *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_exception_oop) = NULL;
    }
    if (g_neko_vm_layout.off_thread_exception_pc >= 0) {
        *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_exception_pc) = NULL;
    }
}

__attribute__((visibility("default"))) void neko_raise_athrow(void *thread, void *exception_oop) {
    neko_set_pending_exception(thread, exception_oop);
}

__attribute__((visibility("default"))) void* neko_tlab_alloc_slow(void *thread, size_t size) {
    JNIEnv *env;
    (void)size;
    if (thread == NULL) return NULL;
    env = neko_current_env();
    (void)neko_throw_cached(env, g_neko_throw_oom);
    return NULL;
}

static inline void* neko_tlab_alloc(void *thread, size_t size) {
    u8 aligned_size;
    u8 top;
    u8 end;
    if (thread == NULL || g_neko_vm_layout.off_thread_tlab_top < 0 || g_neko_vm_layout.off_thread_tlab_end < 0) {
        return NULL;
    }
    aligned_size = neko_align_up_u8((u8)size, 8u);
    top = *(u8*)((uint8_t*)thread + g_neko_vm_layout.off_thread_tlab_top);
    end = *(u8*)((uint8_t*)thread + g_neko_vm_layout.off_thread_tlab_end);
    if (top + aligned_size <= end) {
        *(u8*)((uint8_t*)thread + g_neko_vm_layout.off_thread_tlab_top) = top + aligned_size;
        return (void*)(uintptr_t)top;
    }
    return neko_tlab_alloc_slow(thread, (size_t)aligned_size);
}

static inline int neko_exception_handler_dispatch(int pc, const void *table, size_t n) {
    const neko_exception_handler_entry *entries = (const neko_exception_handler_entry*)table;
    if (entries == NULL) return -1;
    for (size_t i = 0; i < n; i++) {
        if (pc >= entries[i].start_pc && pc < entries[i].end_pc) {
            return entries[i].handler_pc;
        }
    }
    return -1;
}


static void neko_log_runtime_helpers_ready(void) {
    const char *tlab_ready = (g_neko_vm_layout.off_thread_tlab_top >= 0 && g_neko_vm_layout.off_thread_tlab_end >= 0) ? "yes" : "no";
    const char *except_ready = g_neko_vm_layout.off_thread_pending_exception >= 0 ? "yes" : "no";
    neko_native_debug_log(
        "w1 os=%d ks=%d tl=%s ex=%s",
        g_neko_vm_layout.narrow_oop_shift,
        g_neko_vm_layout.narrow_klass_shift,
        tlab_ready,
        except_ready
    );
}

static void neko_derive_method_flags_status_offset(void) {
    g_neko_vm_layout.off_method_flags = -1;
    g_neko_vm_layout.off_method_flags_status = -1;
    g_neko_vm_layout.method_flags_status_strategy = 'D';
    if (g_neko_vm_layout.off_method_flags_direct > 0) {
        g_neko_vm_layout.off_method_flags_status = g_neko_vm_layout.off_method_flags_direct;
        g_neko_vm_layout.method_flags_status_strategy = 'A';
    } else if (g_neko_vm_layout.off_method_intrinsic_id > 0) {
        g_neko_vm_layout.off_method_flags_status = g_neko_vm_layout.off_method_intrinsic_id - (ptrdiff_t)4;
        g_neko_vm_layout.method_flags_status_strategy = 'B';
    } else if (g_neko_vm_layout.off_method_vtable_index > 0) {
        g_neko_vm_layout.off_method_flags_status = g_neko_vm_layout.off_method_vtable_index + neko_align_up_ptrdiff((ptrdiff_t)sizeof(uint16_t), (ptrdiff_t)4);
        g_neko_vm_layout.method_flags_status_strategy = 'C';
    }
    if (g_neko_vm_layout.java_spec_version >= 21 && g_neko_vm_layout.off_method_flags_status < 0) {
        neko_error_log("failed to derive MethodFlags::_status offset for jdk%d, refusing native patch path", g_neko_vm_layout.java_spec_version);
    }
    g_neko_vm_layout.off_method_flags = g_neko_vm_layout.off_method_flags_status;
    NEKO_TRACE(1, "[nk] mf off=%u s=%c", g_neko_vm_layout.off_method_flags_status >= 0 ? (uint32_t)g_neko_vm_layout.off_method_flags_status : 0u, g_neko_vm_layout.method_flags_status_strategy);
}

static void neko_reset_vm_layout(void) {
    memset(&g_neko_vm_layout, 0, sizeof(g_neko_vm_layout));
    g_neko_vm_layout.off_method_const_method = -1;
    g_neko_vm_layout.off_method_access_flags = -1;
    g_neko_vm_layout.off_method_code = -1;
    g_neko_vm_layout.off_method_i2i_entry = -1;
    g_neko_vm_layout.off_method_from_interpreted_entry = -1;
    g_neko_vm_layout.off_method_from_compiled_entry = -1;
    g_neko_vm_layout.off_method_vtable_index = -1;
    g_neko_vm_layout.off_method_intrinsic_id = -1;
    g_neko_vm_layout.off_method_flags = -1;
    g_neko_vm_layout.off_method_flags_direct = -1;
    g_neko_vm_layout.off_method_flags_status = -1;
    g_neko_vm_layout.off_const_method_constants = -1;
    g_neko_vm_layout.off_const_method_max_stack = -1;
    g_neko_vm_layout.off_const_method_max_locals = -1;
    g_neko_vm_layout.off_const_method_size_of_parameters = -1;
    g_neko_vm_layout.off_const_method_method_idnum = -1;
    g_neko_vm_layout.off_const_method_flags_bits = -1;
    g_neko_vm_layout.off_const_method_name_index = -1;
    g_neko_vm_layout.off_const_method_signature_index = -1;
    g_neko_vm_layout.off_constant_pool_holder = -1;
    g_neko_vm_layout.off_constant_pool_tags = -1;
    g_neko_vm_layout.off_constant_pool_length = -1;
    g_neko_vm_layout.off_klass_layout_helper = -1;
    g_neko_vm_layout.off_klass_name = -1;
    g_neko_vm_layout.off_klass_next_link = -1;
    g_neko_vm_layout.off_klass_java_mirror = -1;
    g_neko_vm_layout.off_class_klass = -1;
    g_neko_vm_layout.off_instance_klass_constants = -1;
    g_neko_vm_layout.off_instance_klass_methods = -1;
    g_neko_vm_layout.off_instance_klass_fields = -1;
    g_neko_vm_layout.off_instance_klass_fieldinfo_stream = -1;
    g_neko_vm_layout.off_instance_klass_java_fields_count = -1;
    g_neko_vm_layout.off_instance_klass_init_state = -1;
    g_neko_vm_layout.off_instance_klass_java_mirror = -1;
    g_neko_vm_layout.off_instance_klass_static_field_size = -1;
    g_neko_vm_layout.off_instance_klass_static_oop_field_count = -1;
    g_neko_vm_layout.off_instance_klass_vtable_start = -1;
    g_neko_vm_layout.off_instance_klass_itable_start = -1;
    g_neko_vm_layout.off_symbol_length = -1;
    g_neko_vm_layout.off_symbol_body = -1;
    g_neko_vm_layout.off_string_value = -1;
    g_neko_vm_layout.off_string_coder = -1;
    g_neko_vm_layout.off_string_hash = -1;
    g_neko_vm_layout.off_loader_loaded_field = -1;
    g_neko_vm_layout.off_cldg_head = 0u;
    g_neko_vm_layout.off_cld_next = -1;
    g_neko_vm_layout.off_cld_class_loader = -1;
    g_neko_vm_layout.off_cld_klasses = -1;
    g_neko_vm_layout.off_cld_handles = -1;
    g_neko_vm_layout.cld_class_loader_is_oophandle = JNI_FALSE;
    g_neko_vm_layout.off_array_base_byte = -1;
    g_neko_vm_layout.off_array_scale_byte = -1;
    g_neko_vm_layout.off_array_base_char = -1;
    g_neko_vm_layout.off_array_scale_char = -1;
    g_neko_vm_layout.off_thread_tlab = -1;
    g_neko_vm_layout.off_thread_pending_exception = -1;
    g_neko_vm_layout.off_thread_thread_state = -1;
    g_neko_vm_layout.off_tlab_start = -1;
    g_neko_vm_layout.off_tlab_top = -1;
    g_neko_vm_layout.off_tlab_pf_top = -1;
    g_neko_vm_layout.off_tlab_end = -1;
    g_neko_vm_layout.off_thread_tlab_start_direct = -1;
    g_neko_vm_layout.off_thread_tlab_top_direct = -1;
    g_neko_vm_layout.off_thread_tlab_pf_top_direct = -1;
    g_neko_vm_layout.off_thread_tlab_end_direct = -1;
    g_neko_vm_layout.off_thread_tlab_start = -1;
    g_neko_vm_layout.off_thread_tlab_top = -1;
    g_neko_vm_layout.off_thread_tlab_pf_top = -1;
    g_neko_vm_layout.off_thread_tlab_end = -1;
    g_neko_vm_layout.off_thread_exception_oop = -1;
    g_neko_vm_layout.off_thread_exception_pc = -1;
    g_neko_vm_layout.off_java_thread_anchor = -1;
    g_neko_vm_layout.off_java_thread_last_Java_sp = -1;
    g_neko_vm_layout.off_java_thread_last_Java_fp = -1;
    g_neko_vm_layout.off_java_thread_last_Java_pc = -1;
    g_neko_vm_layout.off_java_frame_anchor_sp = -1;
    g_neko_vm_layout.off_java_frame_anchor_fp = -1;
    g_neko_vm_layout.off_java_frame_anchor_pc = -1;
    g_neko_vm_layout.off_java_thread_jni_environment = -1;
    g_neko_vm_layout.off_oophandle_obj = -1;
    g_neko_vm_layout.narrow_oop_shift = -1;
    g_neko_vm_layout.narrow_klass_shift = -1;
    g_neko_vm_layout.thread_state_in_java = -1;
    g_neko_vm_layout.thread_state_in_vm = -1;
    g_neko_vm_layout.instance_klass_fields_strategy = 'D';
    g_neko_vm_layout.instance_klass_java_mirror_strategy = 'D';
    g_neko_vm_layout.method_flags_status_strategy = 'D';
    g_neko_vm_layout.string_value_strategy = 'D';
    g_neko_vm_layout.string_coder_strategy = 'D';
    g_neko_vm_layout.class_klass_strategy = 'D';
    g_neko_vm_layout.array_base_byte_strategy = 'D';
    g_neko_vm_layout.array_scale_byte_strategy = 'D';
    g_neko_vm_layout.array_base_char_strategy = 'D';
    g_neko_vm_layout.array_scale_char_strategy = 'D';
    g_neko_vm_layout.thread_tlab_start_strategy = 'D';
    g_neko_vm_layout.thread_tlab_top_strategy = 'D';
    g_neko_vm_layout.thread_tlab_pf_top_strategy = 'D';
    g_neko_vm_layout.thread_tlab_end_strategy = 'D';
    g_neko_vm_layout.thread_exception_oop_strategy = 'D';
    g_neko_vm_layout.thread_exception_pc_strategy = 'D';
    g_neko_vm_layout.thread_thread_state_strategy = 'D';
    g_neko_vm_layout.java_thread_anchor_strategy = 'D';
    g_neko_vm_layout.java_thread_last_Java_sp_strategy = 'D';
    g_neko_vm_layout.java_thread_last_Java_fp_strategy = 'D';
    g_neko_vm_layout.java_thread_last_Java_pc_strategy = 'D';
    g_neko_vm_layout.java_thread_jni_environment_strategy = 'D';
    g_neko_vm_layout.oophandle_obj_strategy = 'D';
    g_neko_vm_layout.wave4a_disabled = JNI_TRUE;
    g_neko_vm_layout.use_compact_object_headers = g_neko_use_compact_object_headers;
    g_neko_wave4a_unavailable_reason = "uninitialized";
    g_neko_flag_patch_path_logged = 0;
    g_neko_vm_layout.allocate_instance_fn = NULL;
    g_neko_vm_layout.java_thread_current_fn = NULL;
}

#if defined(_WIN32)
static HMODULE neko_resolve_libjvm_handle(void) {
    HMODULE modules[1024];
    DWORD needed = 0;
    HMODULE module = GetModuleHandleW(L"jvm.dll");
    if (module != NULL) {
        return module;
    }
    if (!EnumProcessModules(GetCurrentProcess(), modules, (DWORD)sizeof(modules), &needed)) {
        return NULL;
    }
    for (DWORD i = 0; i < needed / (DWORD)sizeof(HMODULE); i++) {
        char base_name[MAX_PATH];
        if (GetModuleBaseNameA(GetCurrentProcess(), modules[i], base_name, (DWORD)sizeof(base_name)) != 0 && _stricmp(base_name, "jvm.dll") == 0) {
            return modules[i];
        }
    }
    return NULL;
}

static void* neko_resolve_symbol_address(const char *name) {
    return g_neko_libjvm_handle == NULL ? NULL : (void*)GetProcAddress(g_neko_libjvm_handle, name);
}
#elif defined(__linux__)
typedef struct {
    const char *symbol_name;
    void *handle;
} neko_linux_module_search;

static int neko_linux_module_search_cb(struct dl_phdr_info *info, size_t size, void *data) {
    const char *path;
    void *handle;
    (void)size;
    if (info == NULL || data == NULL) return 0;
    path = info->dlpi_name;
    if (path == NULL || path[0] == '\0') return 0;
    handle = dlopen(path, RTLD_NOLOAD | RTLD_NOW);
    if (handle == NULL) return 0;
    if (dlsym(handle, ((neko_linux_module_search*)data)->symbol_name) != NULL) {
        ((neko_linux_module_search*)data)->handle = handle;
        return 1;
    }
    dlclose(handle);
    return 0;
}

static void* neko_resolve_libjvm_handle(void) {
    void *handle = dlopen(NULL, RTLD_NOW);
    if (handle != NULL && dlsym(handle, "gHotSpotVMStructs") != NULL) {
        return handle;
    }
    handle = dlopen("libjvm.so", RTLD_NOLOAD | RTLD_NOW);
    if (handle != NULL && dlsym(handle, "gHotSpotVMStructs") != NULL) {
        return handle;
    }
    {
        neko_linux_module_search search = { "gHotSpotVMStructs", NULL };
        dl_iterate_phdr(neko_linux_module_search_cb, &search);
        if (search.handle != NULL) {
            return search.handle;
        }
    }
    return NULL;
}

static void* neko_resolve_symbol_address(const char *name) {
    return g_neko_libjvm_handle == NULL ? NULL : dlsym(g_neko_libjvm_handle, name);
}
#elif defined(__APPLE__)
static void* neko_resolve_libjvm_handle(void) {
    if (dlsym(RTLD_DEFAULT, "gHotSpotVMStructs") != NULL) {
        return RTLD_DEFAULT;
    }
    for (uint32_t i = 0; i < _dyld_image_count(); i++) {
        const char *path = _dyld_get_image_name(i);
        void *handle;
        if (path == NULL || path[0] == '\0') continue;
        handle = dlopen(path, RTLD_NOLOAD | RTLD_NOW);
        if (handle == NULL) continue;
        if (dlsym(handle, "gHotSpotVMStructs") != NULL) {
            return handle;
        }
        dlclose(handle);
    }
    return NULL;
}

static void* neko_resolve_symbol_address(const char *name) {
    if (g_neko_libjvm_handle == RTLD_DEFAULT) {
        return dlsym(RTLD_DEFAULT, name);
    }
    return g_neko_libjvm_handle == NULL ? NULL : dlsym(g_neko_libjvm_handle, name);
}
#else
static void* neko_resolve_libjvm_handle(void) {
    return dlopen("libjvm.so", RTLD_NOW);
}

static void* neko_resolve_symbol_address(const char *name) {
    return g_neko_libjvm_handle == NULL ? NULL : dlsym(g_neko_libjvm_handle, name);
}
#endif

static void* neko_resolve_libjvm_symbol(const char *name) {
    if (name == NULL || name[0] == ' ') return NULL;
    if (g_neko_libjvm_handle == NULL) {
        g_neko_libjvm_handle = neko_resolve_libjvm_handle();
    }
    return neko_resolve_symbol_address(name);
}


static void neko_resolve_optional_vm_flags(void) {
    void *symbol = neko_resolve_symbol_address("UseCompactObjectHeaders");
    g_neko_use_compact_object_headers = (symbol != NULL && *(const uint8_t*)symbol != 0u) ? JNI_TRUE : JNI_FALSE;
}

static void* neko_resolve_first_symbol(const char* const* names, size_t count) {
    for (size_t i = 0; i < count; i++) {
        void *symbol = neko_resolve_libjvm_symbol(names[i]);
        if (symbol != NULL) {
            return symbol;
        }
    }
    return NULL;
}

static void neko_resolve_strict_optional_symbols(void) {
    /* DD-5 Oracle 9: no synthetic exception allocation uses HotSpot private
     * allocate_instance symbols. Those symbols are not exported on supported
     * OpenJDK 21/22 builds and are not portable across the target matrix.
     * Keep this block empty unless a future feature adds a separately-approved,
     * optional ABI-risk resolver. */
}

static jboolean neko_resolve_vm_symbols(void) {
    uint32_t resolved = 0u;
    memset(&g_neko_vm_symbols, 0, sizeof(g_neko_vm_symbols));
    g_neko_libjvm_handle = neko_resolve_libjvm_handle();
    if (g_neko_libjvm_handle == NULL) {
        neko_error_log("failed to resolve libjvm handle, falling back to throw body");
        return JNI_FALSE;
    }
#define NEKO_RESOLVE_REQUIRED_SYMBOL(name)     do {         g_neko_vm_symbols.name = neko_resolve_symbol_address(#name);         if (g_neko_vm_symbols.name == NULL) {             neko_error_log("required libjvm symbol %s not found, falling back to throw body", #name);             return JNI_FALSE;         }         resolved++;     } while (0);
    NEKO_REQUIRED_VM_SYMBOLS(NEKO_RESOLVE_REQUIRED_SYMBOL);
#undef NEKO_RESOLVE_REQUIRED_SYMBOL
    neko_resolve_strict_optional_symbols();
    NEKO_TRACE(0, "[nk] lj %u/%u", resolved, NEKO_REQUIRED_VM_SYMBOL_COUNT);
    return JNI_TRUE;
}

static int neko_parse_java_spec_version_text(const char *value) {
    if (value == NULL || value[0] == '\0') return 0;
    if (value[0] == '1' && value[1] == '.' && value[2] != '\0') {
        return (int)strtol(value + 2, NULL, 10);
    }
    return (int)strtol(value, NULL, 10);
}

static int neko_detect_java_spec_version(JNIEnv *env) {
    (void)env;
    if (g_neko_vm_layout.java_spec_version > 0) {
        return g_neko_vm_layout.java_spec_version;
    }
    if (g_neko_vm_layout.off_instance_klass_fieldinfo_stream >= 0) {
        return 21;
    }
    if (g_neko_vm_layout.cld_class_loader_is_oophandle) {
        return 11;
    }
    return 8;
}

static void neko_mark_loader_loaded(void) {
    Klass *loader_klass = (Klass*)g_neko_vm_layout.klass_neko_native_loader;
    oop mirror_oop = NULL;

    /* Native authority: once JNI_OnLoad reached here, translated entrypoints may proceed. */
    __atomic_store_n(&g_neko_loader_ready, 1u, __ATOMIC_RELEASE);

    /* Best-effort mirror sync only; Java itself will also execute loaded = true after System.load returns. */
    if (loader_klass == NULL || g_neko_vm_layout.off_loader_loaded_field < 0) return;
    mirror_oop = neko_resolve_mirror_oop_from_klass(&g_neko_vm_layout, loader_klass);
    if (mirror_oop == NULL) {
        neko_error_log("strict bootstrap failed to resolve NekoNativeLoader mirror");
        return;
    }
    neko_native_debug_log(
        "loader_mark ready=1 klass=%p mirror=%p off=%td",
        loader_klass,
        mirror_oop,
        g_neko_vm_layout.off_loader_loaded_field
    );
    *(volatile uint8_t*)((uint8_t*)mirror_oop + g_neko_vm_layout.off_loader_loaded_field) = 1u;
}

static inline jboolean neko_loader_ready(void) {
    return __atomic_load_n(&g_neko_loader_ready, __ATOMIC_ACQUIRE) != 0u ? JNI_TRUE : JNI_FALSE;
}


static void neko_capture_vm_constant(const char *name, int64_t value) {
    if (name == NULL) return;
    if (neko_streq(name, "JVM_ACC_NOT_C1_COMPILABLE")) {
        g_neko_vm_layout.access_not_c1_compilable = (uint32_t)value;
        return;
    }
    if (neko_streq(name, "JVM_ACC_NOT_C2_COMPILABLE")) {
        g_neko_vm_layout.access_not_c2_compilable = (uint32_t)value;
        return;
    }
    if (neko_streq(name, "JVM_ACC_NOT_OSR_COMPILABLE")) {
        g_neko_vm_layout.access_not_osr_compilable = (uint32_t)value;
        return;
    }
    if (neko_streq(name, "MethodFlags::_misc_is_not_c1_compilable") || neko_contains(name, "not_c1_compilable")) {
        g_neko_vm_layout.method_flag_not_c1_compilable = (uint32_t)value;
        return;
    }
    if (neko_streq(name, "MethodFlags::_misc_is_not_c2_compilable") || neko_contains(name, "not_c2_compilable")) {
        g_neko_vm_layout.method_flag_not_c2_compilable = (uint32_t)value;
        return;
    }
    if (neko_streq(name, "MethodFlags::_misc_is_not_c1_osr_compilable") || neko_contains(name, "not_c1_osr_compilable")) {
        g_neko_vm_layout.method_flag_not_c1_osr_compilable = (uint32_t)value;
        return;
    }
    if (neko_streq(name, "MethodFlags::_misc_is_not_c2_osr_compilable") || neko_contains(name, "not_c2_osr_compilable")) {
        g_neko_vm_layout.method_flag_not_c2_osr_compilable = (uint32_t)value;
        return;
    }
    if (neko_streq(name, "MethodFlags::_misc_dont_inline") || neko_contains(name, "dont_inline")) {
        g_neko_vm_layout.method_flag_dont_inline = (uint32_t)value;
        return;
    }
    if (neko_streq(name, "MethodFlags::_misc_is_old") || neko_contains(name, "is_old")) {
        g_neko_vm_layout.method_flag_is_old = (uint32_t)value;
        return;
    }
    if (neko_streq(name, "MethodFlags::_misc_is_obsolete") || neko_contains(name, "is_obsolete")) {
        g_neko_vm_layout.method_flag_is_obsolete = (uint32_t)value;
        return;
    }
    if (neko_streq(name, "MethodFlags::_misc_is_deleted") || neko_contains(name, "is_deleted")) {
        g_neko_vm_layout.method_flag_is_deleted = (uint32_t)value;
        return;
    }
    if (neko_streq(name, "_thread_in_Java") || neko_ends_with(name, "::_thread_in_Java")) {
        g_neko_vm_layout.thread_state_in_java = (int)value;
        return;
    }
    if (neko_streq(name, "_thread_in_vm") || neko_ends_with(name, "::_thread_in_vm")) {
        g_neko_vm_layout.thread_state_in_vm = (int)value;
        return;
    }
    if (neko_streq(name, "JAVA_SPEC_VERSION") || neko_contains(name, "JAVA_SPEC_VERSION")) {
        g_neko_vm_layout.java_spec_version = (int)value;
    }
}

static const char* neko_validate_vm_layout(void) {
    if (g_neko_vm_layout.java_spec_version <= 0) return "JAVA_SPEC_VERSION";
    if (g_neko_vm_layout.method_size == 0) return "sizeof(Method)";
    if (g_neko_vm_layout.instance_klass_size == 0) return "sizeof(InstanceKlass)";
    if (g_neko_vm_layout.constant_pool_size == 0) return "sizeof(ConstantPool)";
    if (g_neko_vm_layout.java_spec_version <= 17 && g_neko_vm_layout.access_flags_size == 0) return "sizeof(AccessFlags)";
    if (g_neko_vm_layout.off_method_const_method < 0) return "Method::_constMethod";
    if (g_neko_vm_layout.off_method_access_flags < 0) return "Method::_access_flags";
    if (g_neko_vm_layout.off_method_code < 0) return "Method::_code";
    if (g_neko_vm_layout.off_method_i2i_entry < 0) return "Method::_i2i_entry";
    if (g_neko_vm_layout.off_method_from_interpreted_entry < 0) return "Method::_from_interpreted_entry";
    if (g_neko_vm_layout.off_method_from_compiled_entry < 0) return "Method::_from_compiled_entry";
    if (g_neko_vm_layout.off_const_method_constants < 0) return "ConstMethod::_constants";
    if (g_neko_vm_layout.off_const_method_max_stack < 0) return "ConstMethod::_max_stack";
    if (g_neko_vm_layout.off_const_method_max_locals < 0) return "ConstMethod::_max_locals";
    if (g_neko_vm_layout.off_const_method_size_of_parameters < 0) return "ConstMethod::_size_of_parameters";
    if (g_neko_vm_layout.off_const_method_method_idnum < 0) return "ConstMethod::_method_idnum";
    if (g_neko_vm_layout.off_const_method_name_index < 0) return "ConstMethod::_name_index";
    if (g_neko_vm_layout.off_const_method_signature_index < 0) return "ConstMethod::_signature_index";
    if (g_neko_vm_layout.off_constant_pool_holder < 0) return "ConstantPool::_pool_holder";
    if (g_neko_vm_layout.off_constant_pool_tags < 0) return "ConstantPool::_tags";
    if (g_neko_vm_layout.off_constant_pool_length < 0) return "ConstantPool::_length";
    if (g_neko_vm_layout.off_klass_layout_helper < 0) return "Klass::_layout_helper";
    if (g_neko_vm_layout.off_klass_name < 0) return "Klass::_name";
    if (g_neko_vm_layout.off_klass_next_link < 0) return "Klass::_next_link";
    if (g_neko_vm_layout.off_klass_java_mirror < 0) return "Klass::_java_mirror";
    if (g_neko_vm_layout.off_instance_klass_constants < 0) return "InstanceKlass::_constants";
    if (g_neko_vm_layout.off_instance_klass_methods < 0) return "InstanceKlass::_methods";
    if (g_neko_vm_layout.off_instance_klass_fields < 0) return "InstanceKlass::_fields";
    if (g_neko_vm_layout.java_spec_version >= 21 && g_neko_vm_layout.off_instance_klass_fieldinfo_stream < 0) return "InstanceKlass::_fieldinfo_stream";
    if (g_neko_vm_layout.off_symbol_length < 0) return "Symbol::_length";
    if (g_neko_vm_layout.off_symbol_body < 0) return "Symbol::_body";
    if (g_neko_vm_layout.off_instance_klass_init_state < 0) return "InstanceKlass::_init_state";
    if (g_neko_vm_layout.off_string_value < 0) return "java_lang_String::_value";
    if (g_neko_vm_layout.java_spec_version >= 9 && g_neko_vm_layout.off_string_coder < 0) return "java_lang_String::_coder";
    if (g_neko_vm_layout.off_cldg_head == 0u) return "ClassLoaderDataGraph::_head";
    if (g_neko_vm_layout.off_cld_next < 0) return "ClassLoaderData::_next";
    if (g_neko_vm_layout.off_cld_class_loader < 0) return "ClassLoaderData::_class_loader";
    if (g_neko_vm_layout.off_cld_klasses < 0) return "ClassLoaderData::_klasses";
    if (g_neko_vm_layout.off_array_base_byte < 0) return "byte[] base offset";
    if (g_neko_vm_layout.off_array_scale_byte < 0) return "byte[] index scale";
    if (g_neko_vm_layout.off_array_base_char < 0) return "char[] base offset";
    if (g_neko_vm_layout.off_array_scale_char < 0) return "char[] index scale";
    if (g_neko_vm_layout.off_thread_tlab_top < 0) return "JavaThread::_tlab._top";
    if (g_neko_vm_layout.off_thread_tlab_end < 0) return "JavaThread::_tlab._end";
    if (g_neko_vm_layout.off_thread_pending_exception < 0) return "Thread::_pending_exception";
    if (g_neko_vm_layout.off_java_thread_jni_environment < 0) return "JavaThread::_jni_environment";
    if (!g_neko_vm_layout.has_narrow_oop_base) return "CompressedOops::_narrow_oop._base";
    if (!g_neko_vm_layout.has_narrow_oop_shift) return "CompressedOops::_narrow_oop._shift";
    if (!g_neko_vm_layout.has_narrow_klass_base) return "CompressedKlassPointers::_narrow_klass._base";
    if (!g_neko_vm_layout.has_narrow_klass_shift) return "CompressedKlassPointers::_narrow_klass._shift";
    return NULL;
}


static jboolean neko_parse_vm_layout(JNIEnv *env) {
    const uint8_t *vmstructs;
    const uint8_t *vmtypes;
    const uint8_t *int_constants;
    const uint8_t *long_constants;
    int struct_type_off;
    int struct_field_off;
    int struct_type_string_off;
    int struct_is_static_off;
    int struct_offset_off;
    int struct_address_off;
    int struct_stride;
    int type_name_off;
    int type_size_off;
    int type_stride;
    int int_name_off;
    int int_value_off;
    int int_stride;
    int long_name_off;
    int long_value_off;
    int long_stride;
    const char *missing;
    neko_reset_vm_layout();
    neko_resolve_optional_vm_flags();
    g_neko_vm_layout.use_compact_object_headers = g_neko_use_compact_object_headers;
    vmstructs = (const uint8_t*)neko_symbol_pointer(g_neko_vm_symbols.gHotSpotVMStructs);
    vmtypes = (const uint8_t*)neko_symbol_pointer(g_neko_vm_symbols.gHotSpotVMTypes);
    int_constants = (const uint8_t*)neko_symbol_pointer(g_neko_vm_symbols.gHotSpotVMIntConstants);
    long_constants = (const uint8_t*)neko_symbol_pointer(g_neko_vm_symbols.gHotSpotVMLongConstants);
    if (vmstructs == NULL || vmtypes == NULL || int_constants == NULL || long_constants == NULL) {
        neko_error_log("VMStructs table roots missing, falling back to throw body");
        return JNI_FALSE;
    }
    struct_type_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMStructEntryTypeNameOffset);
    struct_field_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMStructEntryFieldNameOffset);
    struct_type_string_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMStructEntryTypeStringOffset);
    struct_is_static_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMStructEntryIsStaticOffset);
    struct_offset_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMStructEntryOffsetOffset);
    struct_address_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMStructEntryAddressOffset);
    struct_stride = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMStructEntryArrayStride);
    type_name_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMTypeEntryTypeNameOffset);
    type_size_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMTypeEntrySizeOffset);
    type_stride = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMTypeEntryArrayStride);
    int_name_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMIntConstantEntryNameOffset);
    int_value_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMIntConstantEntryValueOffset);
    int_stride = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMIntConstantEntryArrayStride);
    long_name_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMLongConstantEntryNameOffset);
    long_value_off = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMLongConstantEntryValueOffset);
    long_stride = neko_symbol_int(g_neko_vm_symbols.gHotSpotVMLongConstantEntryArrayStride);
    for (const uint8_t *entry = vmstructs; ; entry += struct_stride) {
        const char *type_name = *(const char* const*)(entry + struct_type_off);
        const char *field_name = *(const char* const*)(entry + struct_field_off);
        const char *type_string = *(const char* const*)(entry + struct_type_string_off);
        int is_static = *(const int*)(entry + struct_is_static_off);
        uintptr_t offset = *(const uintptr_t*)(entry + struct_offset_off);
        void *address = *(void* const*)(entry + struct_address_off);
        if (type_name == NULL && field_name == NULL) break;
        if (neko_streq(type_name, "Method")) {
            if (neko_streq(field_name, "_constMethod")) g_neko_vm_layout.off_method_const_method = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_access_flags")) g_neko_vm_layout.off_method_access_flags = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_code")) g_neko_vm_layout.off_method_code = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_i2i_entry")) g_neko_vm_layout.off_method_i2i_entry = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_from_interpreted_entry")) g_neko_vm_layout.off_method_from_interpreted_entry = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_from_compiled_entry")) g_neko_vm_layout.off_method_from_compiled_entry = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_vtable_index")) g_neko_vm_layout.off_method_vtable_index = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_intrinsic_id")) g_neko_vm_layout.off_method_intrinsic_id = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_flags") || neko_streq(field_name, "_flags._status") || neko_streq(field_name, "_flags._flags")) g_neko_vm_layout.off_method_flags_direct = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "ConstMethod")) {
            if (neko_streq(field_name, "_constants")) g_neko_vm_layout.off_const_method_constants = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_max_stack")) g_neko_vm_layout.off_const_method_max_stack = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_max_locals")) g_neko_vm_layout.off_const_method_max_locals = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_size_of_parameters")) g_neko_vm_layout.off_const_method_size_of_parameters = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_method_idnum")) g_neko_vm_layout.off_const_method_method_idnum = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_flags._flags")) g_neko_vm_layout.off_const_method_flags_bits = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_name_index")) g_neko_vm_layout.off_const_method_name_index = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_signature_index")) g_neko_vm_layout.off_const_method_signature_index = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "ConstantPool")) {
            if (neko_streq(field_name, "_pool_holder")) {
                g_neko_vm_layout.off_constant_pool_holder = (ptrdiff_t)offset;
                g_neko_vm_layout.constant_pool_holder_is_narrow = type_string != NULL && strstr(type_string, "narrowKlass") != NULL;
            } else if (neko_streq(field_name, "_tags")) {
                g_neko_vm_layout.off_constant_pool_tags = (ptrdiff_t)offset;
            } else if (neko_streq(field_name, "_length")) {
                g_neko_vm_layout.off_constant_pool_length = (ptrdiff_t)offset;
            }
        } else if (neko_streq(type_name, "ClassLoaderDataGraph")) {
            if (neko_streq(field_name, "_head") && is_static && address != NULL) {
                g_neko_vm_layout.off_cldg_head = (uintptr_t)address;
            }
        } else if (neko_streq(type_name, "ClassLoaderData")) {
            if (neko_streq(field_name, "_next")) {
                g_neko_vm_layout.off_cld_next = (ptrdiff_t)offset;
            } else if (neko_streq(field_name, "_class_loader")) {
                g_neko_vm_layout.off_cld_class_loader = (ptrdiff_t)offset;
                g_neko_vm_layout.cld_class_loader_is_oophandle = (type_string != NULL && strstr(type_string, "OopHandle") != NULL) ? JNI_TRUE : JNI_FALSE;
            } else if (neko_streq(field_name, "_klasses")) {
                g_neko_vm_layout.off_cld_klasses = (ptrdiff_t)offset;
            }
        } else if (neko_streq(type_name, "Klass")) {
            if (neko_streq(field_name, "_layout_helper")) g_neko_vm_layout.off_klass_layout_helper = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_name")) g_neko_vm_layout.off_klass_name = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_next_link")) g_neko_vm_layout.off_klass_next_link = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_java_mirror")) g_neko_vm_layout.off_klass_java_mirror = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "java_lang_Class")) {
            if (neko_streq(field_name, "_klass")) g_neko_vm_layout.off_class_klass = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "InstanceKlass")) {
            if (neko_streq(field_name, "_constants")) g_neko_vm_layout.off_instance_klass_constants = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_methods")) g_neko_vm_layout.off_instance_klass_methods = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_fields") || neko_streq(field_name, "_fieldinfo_stream")) g_neko_vm_layout.off_instance_klass_fields = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_java_fields_count")) g_neko_vm_layout.off_instance_klass_java_fields_count = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_init_state")) g_neko_vm_layout.off_instance_klass_init_state = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_java_mirror")) g_neko_vm_layout.off_instance_klass_java_mirror = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_static_field_size")) g_neko_vm_layout.off_instance_klass_static_field_size = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_static_oop_field_count")) g_neko_vm_layout.off_instance_klass_static_oop_field_count = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_vtable_start")) g_neko_vm_layout.off_instance_klass_vtable_start = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_itable_start")) g_neko_vm_layout.off_instance_klass_itable_start = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "Symbol")) {
            if (neko_streq(field_name, "_length")) g_neko_vm_layout.off_symbol_length = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_body")) g_neko_vm_layout.off_symbol_body = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "java_lang_String")) {
            if (neko_streq(field_name, "_value")) g_neko_vm_layout.off_string_value = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_coder")) g_neko_vm_layout.off_string_coder = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "JavaFrameAnchor")) {
            if (neko_streq(field_name, "_last_Java_sp")) g_neko_vm_layout.off_java_frame_anchor_sp = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_last_Java_fp")) g_neko_vm_layout.off_java_frame_anchor_fp = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_last_Java_pc")) g_neko_vm_layout.off_java_frame_anchor_pc = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "OopHandle")) {
            if (neko_streq(field_name, "_obj")) g_neko_vm_layout.off_oophandle_obj = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "ThreadLocalAllocBuffer")) {
            if (neko_streq(field_name, "_start")) g_neko_vm_layout.off_tlab_start = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_top")) g_neko_vm_layout.off_tlab_top = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_pf_top")) g_neko_vm_layout.off_tlab_pf_top = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_end")) g_neko_vm_layout.off_tlab_end = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "Thread") || neko_streq(type_name, "JavaThread")) {
            if (neko_streq(field_name, "_tlab")) g_neko_vm_layout.off_thread_tlab = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_tlab._start") || neko_streq(field_name, "_tlab_start")) g_neko_vm_layout.off_thread_tlab_start_direct = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_tlab._top") || neko_streq(field_name, "_tlab_top")) g_neko_vm_layout.off_thread_tlab_top_direct = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_tlab._pf_top") || neko_streq(field_name, "_tlab_pf_top")) g_neko_vm_layout.off_thread_tlab_pf_top_direct = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_tlab._end") || neko_streq(field_name, "_tlab_end")) g_neko_vm_layout.off_thread_tlab_end_direct = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_pending_exception")) g_neko_vm_layout.off_thread_pending_exception = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_exception_oop")) g_neko_vm_layout.off_thread_exception_oop = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_exception_pc")) g_neko_vm_layout.off_thread_exception_pc = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_thread_state")) g_neko_vm_layout.off_thread_thread_state = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_anchor")) g_neko_vm_layout.off_java_thread_anchor = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_anchor._last_Java_sp") || neko_streq(field_name, "_last_Java_sp")) g_neko_vm_layout.off_java_thread_last_Java_sp = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_anchor._last_Java_fp") || neko_streq(field_name, "_last_Java_fp")) g_neko_vm_layout.off_java_thread_last_Java_fp = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_anchor._last_Java_pc") || neko_streq(field_name, "_last_Java_pc")) g_neko_vm_layout.off_java_thread_last_Java_pc = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_jni_environment")) g_neko_vm_layout.off_java_thread_jni_environment = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "ThreadShadow")) {
            if (neko_streq(field_name, "_pending_exception")) g_neko_vm_layout.off_thread_pending_exception = (ptrdiff_t)offset;
        }
        if (neko_streq(type_name, "InstanceKlass") && neko_streq(field_name, "_fieldinfo_stream")) {
            g_neko_vm_layout.off_instance_klass_fieldinfo_stream = (ptrdiff_t)offset;
        }
        if (address != NULL && is_static) {
            if ((neko_streq(type_name, "Universe") || neko_streq(type_name, "CompressedOops")) && neko_streq(field_name, "_narrow_oop._base")) {
                g_neko_vm_layout.narrow_oop_base = *(uintptr_t*)address;
                g_neko_vm_layout.has_narrow_oop_base = JNI_TRUE;
            } else if ((neko_streq(type_name, "Universe") || neko_streq(type_name, "CompressedOops")) && neko_streq(field_name, "_narrow_oop._shift")) {
                g_neko_vm_layout.narrow_oop_shift = *(int*)address;
                g_neko_vm_layout.has_narrow_oop_shift = JNI_TRUE;
            } else if ((neko_streq(type_name, "Universe") || neko_streq(type_name, "CompressedKlassPointers")) && neko_streq(field_name, "_narrow_klass._base")) {
                g_neko_vm_layout.narrow_klass_base = *(uintptr_t*)address;
                g_neko_vm_layout.has_narrow_klass_base = JNI_TRUE;
            } else if ((neko_streq(type_name, "Universe") || neko_streq(type_name, "CompressedKlassPointers")) && neko_streq(field_name, "_narrow_klass._shift")) {
                g_neko_vm_layout.narrow_klass_shift = *(int*)address;
                g_neko_vm_layout.has_narrow_klass_shift = JNI_TRUE;
            }
        }
        (void)type_string;
    }
    for (const uint8_t *entry = vmtypes; ; entry += type_stride) {
        const char *type_name = *(const char* const*)(entry + type_name_off);
        size_t type_size;
        if (type_name == NULL) break;
        type_size = (size_t)*(const uint64_t*)(entry + type_size_off);
        if (neko_streq(type_name, "Method")) g_neko_vm_layout.method_size = type_size;
        else if (neko_streq(type_name, "InstanceKlass")) g_neko_vm_layout.instance_klass_size = type_size;
        else if (neko_streq(type_name, "vtableEntry")) g_neko_vm_layout.vtable_entry_size = type_size;
        else if (neko_streq(type_name, "itableOffsetEntry")) g_neko_vm_layout.itable_offset_entry_size = type_size;
        else if (neko_streq(type_name, "itableMethodEntry")) g_neko_vm_layout.itable_method_entry_size = type_size;
        else if (neko_streq(type_name, "ConstantPool")) g_neko_vm_layout.constant_pool_size = type_size;
        else if (neko_streq(type_name, "AccessFlags")) g_neko_vm_layout.access_flags_size = type_size;
        else if (neko_streq(type_name, "MethodFlags")) g_neko_vm_layout.method_flags_size = type_size;
        else if (neko_streq(type_name, "JavaFrameAnchor")) g_neko_vm_layout.java_frame_anchor_size = type_size;
    }
    for (const uint8_t *entry = int_constants; ; entry += int_stride) {
        const char *name = *(const char* const*)(entry + int_name_off);
        if (name == NULL) break;
        neko_capture_vm_constant(name, *(const int32_t*)(entry + int_value_off));
    }
    for (const uint8_t *entry = long_constants; ; entry += long_stride) {
        const char *name = *(const char* const*)(entry + long_name_off);
        if (name == NULL) break;
        neko_capture_vm_constant(name, *(const int64_t*)(entry + long_value_off));
    }
    if (g_neko_vm_layout.java_spec_version <= 0) {
        g_neko_vm_layout.java_spec_version = neko_detect_java_spec_version(env);
    }
    if (g_neko_vm_layout.off_klass_java_mirror < 0 && g_neko_vm_layout.off_instance_klass_java_mirror >= 0) {
        g_neko_vm_layout.off_klass_java_mirror = g_neko_vm_layout.off_instance_klass_java_mirror;
    }
    neko_log_instance_klass_static_field_offsets();
    neko_derive_method_flags_status_offset();
    neko_derive_thread_tlab_top_offset();
    neko_derive_thread_tlab_start_offset();
    neko_derive_thread_tlab_pf_top_offset();
    neko_derive_thread_tlab_end_offset();
    neko_derive_thread_exception_oop_offset();
    neko_derive_thread_exception_pc_offset();
    neko_derive_java_thread_anchor_offset();
    neko_derive_java_thread_jni_environment_offset();
    neko_derive_cld_handles_offset();
    neko_derive_bootstrap_wellknown_layout();
    missing = neko_validate_vm_layout();
    if (missing != NULL) {
        neko_error_log("vm layout missing %s", missing);
        return JNI_FALSE;
    }
    neko_configure_wave4a_layout();
    NEKO_TRACE(0, "[nk] vm j=%d", g_neko_vm_layout.java_spec_version);
    return JNI_TRUE;
}

static jboolean neko_parse_vm_layout_strict(JNIEnv *env) {
    if (!neko_parse_vm_layout(env)) {
        return JNI_FALSE;
    }
    if (g_neko_vm_layout.off_klass_next_link < 0) {
        neko_error_log("strict bootstrap requires Klass::_next_link VMStructs exposure");
        return JNI_FALSE;
    }
    neko_log_strict_vm_layout_ok();
    return JNI_TRUE;
}

static void neko_manifest_lock_enter(void) {
    return;
}

static void neko_manifest_lock_exit(void) {
    return;
}

static void neko_manifest_lock_acquire(void) {
    neko_manifest_lock_enter();
}

static void neko_manifest_lock_release(void) {
    neko_manifest_lock_exit();
}

static void neko_record_manifest_match(uint32_t index, void *method_star) {
    const NekoManifestMethod *entry;
    if (method_star == NULL || index >= g_neko_manifest_method_count) return;
    entry = &g_neko_manifest_methods[index];
    neko_manifest_lock_enter();
    if (g_neko_manifest_method_stars[index] == NULL) {
        g_neko_manifest_method_stars[index] = method_star;
        g_neko_manifest_match_count++;
        NEKO_TRACE(1, "[nk] mm %s.%s%s %p", entry->owner_internal, entry->method_name, entry->method_desc, method_star);
    } else {
        g_neko_manifest_method_stars[index] = method_star;
    }
    if (g_neko_manifest_patch_states[index] == NEKO_PATCH_STATE_NONE) {
        (void)neko_patch_method(entry, method_star);
    }
    neko_manifest_lock_exit();
}

static jboolean neko_manifest_has_owner(const char *owner_internal, uint32_t owner_hash) {
    (void)owner_hash;
    for (uint32_t i = 0; i < g_neko_manifest_owner_count; i++) {
        const char *owner = g_neko_manifest_owners[i];
        if (owner != NULL && strcmp(owner, owner_internal) == 0) {
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

static void neko_resolve_discovered_invoke_sites(const char *owner_internal, const char *name, const char *desc, void *method_star) {
    if (owner_internal == NULL || name == NULL || desc == NULL || method_star == NULL) return;
    for (uint32_t i = 0; i < g_neko_manifest_invoke_site_count; i++) {
        NekoManifestInvokeSite *site = g_neko_manifest_invoke_sites[i];
        if (site == NULL) continue;
        if (site->owner_internal == NULL || strcmp(site->owner_internal, owner_internal) != 0) continue;
        if (strcmp(site->method_name, name) != 0 || strcmp(site->method_desc, desc) != 0) continue;
        if (__atomic_load_n(&site->resolved_method, __ATOMIC_ACQUIRE) != NULL) continue;
        __atomic_store_n(&site->resolved_method, method_star, __ATOMIC_RELEASE);
        NEKO_TRACE(1, "[nk] ri %s.%s%s %p", owner_internal, name, desc, method_star);
    }
}

static char* neko_internal_name_from_signature(const char *signature) {
    size_t length;
    char *value;
    if (signature == NULL || signature[0] != 'L') return NULL;
    length = strlen(signature);
    if (length < 2 || signature[length - 1] != ';') return NULL;
    value = (char*)malloc(length - 1u);
    if (value == NULL) return NULL;
    memcpy(value, signature + 1, length - 2u);
    value[length - 2u] = '\0';
    return value;
}

static jboolean neko_symbol_equals_literal(void *sym, const char *literal, uint16_t literal_len) {
    const uint8_t *body = NULL;
    uint16_t sym_len = 0;
    if (sym == NULL || literal == NULL) return JNI_FALSE;
    if (!neko_read_symbol_bytes(sym, &body, &sym_len)) return JNI_FALSE;
    if (sym_len != literal_len) return JNI_FALSE;
    return literal_len == 0u || memcmp(body, literal, literal_len) == 0 ? JNI_TRUE : JNI_FALSE;
}

static inline Klass* neko_cld_first_klass(void *cld) {
    if (cld == NULL || g_neko_vm_layout.off_cld_klasses < 0) return NULL;
    return *(Klass**)((uint8_t*)cld + g_neko_vm_layout.off_cld_klasses);
}

static inline Klass* neko_klass_next_link(Klass *klass) {
    if (klass == NULL || g_neko_vm_layout.off_klass_next_link < 0) return NULL;
    return *(Klass**)((uint8_t*)klass + g_neko_vm_layout.off_klass_next_link);
}

static Klass* neko_find_klass_by_name_in_cld(void *cld, const char *internal_name, uint16_t internal_name_len) {
    if (g_neko_vm_layout.off_klass_name < 0) return NULL;
    for (Klass *klass = neko_cld_first_klass(cld); klass != NULL; klass = neko_klass_next_link(klass)) {
        void *name_sym = *(void**)((uint8_t*)klass + g_neko_vm_layout.off_klass_name);
        if (neko_symbol_equals_literal(name_sym, internal_name, internal_name_len)) {
            return klass;
        }
    }
    return NULL;
}

static Klass* neko_find_klass_by_name_in_cld_graph(const char *internal_name, uint16_t internal_name_len) {
    void *cld = neko_boot_cld_head();
    if (internal_name == NULL || g_neko_vm_layout.off_cld_next < 0) return NULL;
    while (cld != NULL) {
        Klass *klass = neko_find_klass_by_name_in_cld(cld, internal_name, internal_name_len);
        if (klass != NULL) {
            return klass;
        }
        cld = *(void**)((uint8_t*)cld + g_neko_vm_layout.off_cld_next);
    }
    return NULL;
}

static jboolean neko_cldg_contains_klass(Klass *target_klass) {
    void *cld = neko_boot_cld_head();
    if (target_klass == NULL || g_neko_vm_layout.off_cld_next < 0) return JNI_FALSE;
    while (cld != NULL) {
        for (Klass *klass = neko_cld_first_klass(cld); klass != NULL; klass = neko_klass_next_link(klass)) {
            if (klass == target_klass) return JNI_TRUE;
        }
        cld = *(void**)((uint8_t*)cld + g_neko_vm_layout.off_cld_next);
    }
    return JNI_FALSE;
}

static uint32_t g_neko_class_unload_observed_count = 0u;
static uint32_t g_neko_redefine_detected_count = 0u;
static uint32_t g_neko_cld_liveness_rescan_counter = 0u;
static void* g_neko_seen_clds[4096];
static uint32_t g_neko_seen_cld_count = 0u;

static jboolean neko_method_is_redefined_stale(void *method_star) {
    uint32_t flags;
    uint32_t stale_mask;
    if (method_star == NULL) return JNI_FALSE;
    if (g_neko_vm_layout.java_spec_version >= 21) {
        if (g_neko_vm_layout.off_method_flags < 0) return JNI_FALSE;
        /* JDK 21+ MethodFlags::_status bits from hotspot/share/oops/methodFlags.hpp:
         * is_old=1<<2, is_obsolete=1<<3, is_deleted=1<<4. VMStructs constants are used when exposed. */
        stale_mask = (g_neko_vm_layout.method_flag_is_old != 0u ? g_neko_vm_layout.method_flag_is_old : (1u << 2))
            | (g_neko_vm_layout.method_flag_is_obsolete != 0u ? g_neko_vm_layout.method_flag_is_obsolete : (1u << 3))
            | (g_neko_vm_layout.method_flag_is_deleted != 0u ? g_neko_vm_layout.method_flag_is_deleted : (1u << 4));
        flags = __atomic_load_n((uint32_t*)((uint8_t*)method_star + g_neko_vm_layout.off_method_flags), __ATOMIC_ACQUIRE);
        return (flags & stale_mask) != 0u ? JNI_TRUE : JNI_FALSE;
    }
    if (g_neko_vm_layout.off_method_access_flags < 0) return JNI_FALSE;
    /* JDK 8-20 AccessFlags bits from hotspot/share/utilities/accessFlags.hpp:
     * JVM_ACC_IS_OLD=0x00010000, JVM_ACC_IS_OBSOLETE=0x00020000, JVM_ACC_IS_DELETED=0x00008000. */
    stale_mask = 0x00010000u | 0x00020000u | 0x00008000u;
    flags = __atomic_load_n((uint32_t*)((uint8_t*)method_star + g_neko_vm_layout.off_method_access_flags), __ATOMIC_ACQUIRE);
    return (flags & stale_mask) != 0u ? JNI_TRUE : JNI_FALSE;
}

static void neko_clear_invoke_sites_for_redefined_method(const NekoManifestMethod *entry) {
    if (entry == NULL) return;
    for (uint32_t invoke_index = 0; invoke_index < g_neko_manifest_invoke_site_count; invoke_index++) {
        NekoManifestInvokeSite *site = g_neko_manifest_invoke_sites[invoke_index];
        if (site == NULL || site->owner_internal == NULL || site->method_name == NULL || site->method_desc == NULL) continue;
        if (entry->owner_internal == NULL || entry->method_name == NULL || entry->method_desc == NULL) continue;
        if (strcmp(site->owner_internal, entry->owner_internal) != 0) continue;
        if (strcmp(site->method_name, entry->method_name) != 0) continue;
        if (strcmp(site->method_desc, entry->method_desc) != 0) continue;
        __atomic_store_n(&site->resolved_method, NULL, __ATOMIC_RELEASE);
    }
}

static void neko_note_redefine_detected(const NekoManifestMethod *entry) {
    uint32_t count = __atomic_add_fetch(&g_neko_redefine_detected_count, 1u, __ATOMIC_ACQ_REL);
    NEKO_TRACE(0, "[nk] neko_redefine_detected=%u %s.%s%s", count,
        entry != NULL && entry->owner_internal != NULL ? entry->owner_internal : "<null>",
        entry != NULL && entry->method_name != NULL ? entry->method_name : "<null>",
        entry != NULL && entry->method_desc != NULL ? entry->method_desc : "<null>");
}

static void neko_invalidate_manifest_method_index(uint32_t index, jboolean redefine) {
    NekoManifestMethod *entry;
    uint8_t previous_state;
    if (index >= g_neko_manifest_method_count) return;
    entry = (NekoManifestMethod*)&g_neko_manifest_methods[index];
    previous_state = __atomic_exchange_n(&g_neko_manifest_patch_states[index], NEKO_PATCH_STATE_FAILED, __ATOMIC_ACQ_REL);
    __atomic_store_n(&g_neko_manifest_method_stars[index], NULL, __ATOMIC_RELEASE);
    neko_clear_invoke_sites_for_redefined_method(entry);
    if (redefine && previous_state == NEKO_PATCH_STATE_APPLIED) {
        neko_note_redefine_detected(entry);
    }
}

static jboolean neko_manifest_method_active(uint32_t index) {
    void *method_star;
    if (index >= g_neko_manifest_method_count) return JNI_FALSE;
    if (__atomic_load_n(&g_neko_manifest_patch_states[index], __ATOMIC_ACQUIRE) != NEKO_PATCH_STATE_APPLIED) return JNI_FALSE;
    method_star = __atomic_load_n(&g_neko_manifest_method_stars[index], __ATOMIC_ACQUIRE);
    if (method_star == NULL) return JNI_FALSE;
    if (neko_method_is_redefined_stale(method_star)) {
        neko_invalidate_manifest_method_index(index, JNI_TRUE);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static void neko_invalidate_redefined_manifest_methods(void) {
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        void *method_star = __atomic_load_n(&g_neko_manifest_method_stars[i], __ATOMIC_ACQUIRE);
        if (method_star == NULL) continue;
        if (!neko_method_is_redefined_stale(method_star)) continue;
        neko_invalidate_manifest_method_index(i, JNI_TRUE);
    }
}

static void neko_note_class_unload_observed(const NekoManifestMethod *entry) {
    uint32_t count = __atomic_add_fetch(&g_neko_class_unload_observed_count, 1u, __ATOMIC_ACQ_REL);
    if (entry != NULL) {
        NEKO_TRACE(0, "[nk] cul %s.%s%s", entry->owner_internal == NULL ? "<null>" : entry->owner_internal, entry->method_name == NULL ? "<null>" : entry->method_name, entry->method_desc == NULL ? "<null>" : entry->method_desc);
    }
    NEKO_TRACE(0, "[nk] neko_class_unload_observed=%u", count);
}

static jboolean neko_invalidate_cached_field_klass_if_dead(NekoManifestFieldSite *site) {
    Klass *cached_klass;
    if (site == NULL) return JNI_FALSE;
    cached_klass = (Klass*)__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);
    if (cached_klass == NULL || neko_cldg_contains_klass(cached_klass)) return JNI_FALSE;
    __atomic_store_n(&site->cached_klass, NULL, __ATOMIC_RELEASE);
    __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_UNRESOLVED, __ATOMIC_RELEASE);
    site->field_offset_cookie = NEKO_FIELD_SITE_UNRESOLVED;
    return JNI_TRUE;
}

static jboolean neko_invalidate_cached_ldc_klass_if_dead(NekoManifestLdcSite *site) {
    Klass *cached_klass;
    if (site == NULL || site->kind != NEKO_LDC_KIND_CLASS) return JNI_FALSE;
    cached_klass = (Klass*)__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);
    if (cached_klass == NULL || neko_cldg_contains_klass(cached_klass)) return JNI_FALSE;
    __atomic_store_n(&site->cached_klass, NULL, __ATOMIC_RELEASE);
    return JNI_TRUE;
}

static uint32_t neko_collect_live_clds(void **out, uint32_t capacity) {
    void *cld = neko_boot_cld_head();
    uint32_t count = 0u;
    if (out == NULL || capacity == 0u || g_neko_vm_layout.off_cld_next < 0) return 0u;
    while (cld != NULL && count < capacity) {
        out[count++] = cld;
        cld = *(void**)((uint8_t*)cld + g_neko_vm_layout.off_cld_next);
    }
    return count;
}

static jboolean neko_cld_snapshot_contains(void *const *snapshot, uint32_t count, void *cld) {
    for (uint32_t i = 0; i < count; i++) {
        if (snapshot[i] == cld) return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean neko_refresh_cld_snapshot_and_detect_unload(void) {
    void *live_clds[4096];
    uint32_t live_count = neko_collect_live_clds(live_clds, 4096u);
    jboolean unloaded = JNI_FALSE;
    if (g_neko_seen_cld_count != 0u) {
        for (uint32_t i = 0; i < g_neko_seen_cld_count; i++) {
            if (!neko_cld_snapshot_contains(live_clds, live_count, g_neko_seen_clds[i])) {
                unloaded = JNI_TRUE;
                break;
            }
        }
    }
    for (uint32_t i = 0; i < live_count; i++) {
        g_neko_seen_clds[i] = live_clds[i];
    }
    g_neko_seen_cld_count = live_count;
    return unloaded;
}

static void neko_invalidate_dead_cld_entries(void) {
    jboolean unloaded_cld_seen;
    if (g_neko_vm_layout.off_cldg_head == 0u || g_neko_vm_layout.off_cld_next < 0 || g_neko_vm_layout.off_cld_klasses < 0) return;
    unloaded_cld_seen = neko_refresh_cld_snapshot_and_detect_unload();
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        NekoManifestMethod *entry = (NekoManifestMethod*)&g_neko_manifest_methods[i];
        jboolean observed = JNI_FALSE;
        if (entry->owner_internal != NULL && g_neko_manifest_patch_states[i] == NEKO_PATCH_STATE_APPLIED) {
            size_t owner_len = strlen(entry->owner_internal);
            if (owner_len < 65536u && neko_find_klass_by_name_in_cld_graph(entry->owner_internal, (uint16_t)owner_len) == NULL) {
                g_neko_manifest_patch_states[i] = NEKO_PATCH_STATE_FAILED;
                g_neko_manifest_method_stars[i] = NULL;
                observed = JNI_TRUE;
            }
        }
        for (uint32_t site_index = 0; site_index < entry->field_site_count; site_index++) {
            if (neko_invalidate_cached_field_klass_if_dead(&entry->field_sites[site_index])) observed = JNI_TRUE;
        }
        for (uint32_t site_index = 0; site_index < entry->ldc_site_count; site_index++) {
            if (neko_invalidate_cached_ldc_klass_if_dead(&entry->ldc_sites[site_index])) observed = JNI_TRUE;
        }
        if (observed) {
            for (uint32_t invoke_index = 0; invoke_index < g_neko_manifest_invoke_site_count; invoke_index++) {
                NekoManifestInvokeSite *site = g_neko_manifest_invoke_sites[invoke_index];
                if (site == NULL || site->owner_internal == NULL || entry->owner_internal == NULL) continue;
                if (strcmp(site->owner_internal, entry->owner_internal) != 0) continue;
                __atomic_store_n(&site->resolved_method, NULL, __ATOMIC_RELEASE);
            }
            neko_note_class_unload_observed(entry);
        }
    }
    if (unloaded_cld_seen) {
        neko_note_class_unload_observed(NULL);
    }
}

static void neko_maybe_rescan_cld_liveness(void) {
    uint32_t tick = __atomic_add_fetch(&g_neko_cld_liveness_rescan_counter, 1u, __ATOMIC_RELAXED);
    if ((tick & 1023u) != 0u) return;
    neko_manifest_lock_enter();
    neko_invalidate_dead_cld_entries();
    neko_invalidate_redefined_manifest_methods();
    neko_manifest_lock_exit();
}

/* W1: Derive off_class_klass by resolving the java.lang.Class mirror for a known Klass,
 * then scanning the mirror object for the hidden Klass* back-pointer. On JDK 8 the mirror
 * is read from a direct oop field; on JDK 9+ it is resolved through Klass::_java_mirror
 * (OopHandle -> oop storage slot -> wide oop). */
static void neko_derive_class_klass_offset_from_mirror(void *known_klass) {
    void *mirror_oop;
    if (known_klass == NULL || g_neko_vm_layout.off_class_klass >= 0) return;
    if (g_neko_vm_layout.off_klass_java_mirror < 0) return;
    mirror_oop = neko_resolve_mirror_oop_from_klass(&g_neko_vm_layout, (Klass*)known_klass);
    if (mirror_oop == NULL) return;
    /* Step 3: scan the mirror oop (Java object) for a native pointer == known_klass.
     * _klass is stored at a fixed offset, typically 8-128 bytes from object base. */
    for (ptrdiff_t off = 8; off < 128; off += sizeof(void*)) {
        void *candidate;
        /* bounds-safe: use __builtin_expect to hint branch prediction */
        candidate = *(void* volatile *)((uint8_t*)mirror_oop + off);
        if (candidate == known_klass) {
            g_neko_vm_layout.off_class_klass = off;
            neko_native_debug_log("class_klass_offset_scan=%td", off);
            return;
        }
    }
}

static uint32_t neko_count_cld_klasses(void *cld, uint32_t cap) {
    uint32_t count = 0u;
    for (Klass *klass = neko_cld_first_klass(cld); klass != NULL; klass = neko_klass_next_link(klass)) {
        if (++count == cap) break;
    }
    return count;
}

static void neko_derive_string_layout_from_klass(void *string_klass) {
    int32_t offset = -1;
    if (string_klass == NULL) return;
    if (g_neko_vm_layout.off_string_value < 0) {
        if (g_neko_vm_layout.java_spec_version >= 9) {
            if (!neko_resolve_field_offset(string_klass, "value", 5u, "[B", 2u, false, &offset)) {
                (void)neko_resolve_field_offset(string_klass, "value", 5u, "[C", 2u, false, &offset);
            }
        } else if (!neko_resolve_field_offset(string_klass, "value", 5u, "[C", 2u, false, &offset)) {
            (void)neko_resolve_field_offset(string_klass, "value", 5u, "[B", 2u, false, &offset);
        }
        if (offset >= 0) {
            g_neko_vm_layout.off_string_value = offset;
        }
    }
    offset = -1;
    if (g_neko_vm_layout.java_spec_version >= 9 && g_neko_vm_layout.off_string_coder < 0) {
        if (neko_resolve_field_offset(string_klass, "coder", 5u, "B", 1u, false, &offset)) {
            g_neko_vm_layout.off_string_coder = offset;
        }
    }
    offset = -1;
    if (g_neko_vm_layout.off_string_hash < 0) {
        if (neko_resolve_field_offset(string_klass, "hash", 4u, "I", 1u, false, &offset)) {
            g_neko_vm_layout.off_string_hash = offset;
        }
    }
}

static void neko_derive_array_layout_from_klass(void *array_klass, ptrdiff_t *base_out, ptrdiff_t *scale_out) {
    uint32_t lh;
    uint32_t header_bytes;
    uint32_t log2_elem;
    if (array_klass == NULL || base_out == NULL || scale_out == NULL || g_neko_vm_layout.off_klass_layout_helper < 0) return;
    lh = *(uint32_t*)((uint8_t*)array_klass + g_neko_vm_layout.off_klass_layout_helper);
    if (((int32_t)lh) >= 0) return;
    header_bytes = neko_lh_header_size(lh);
    log2_elem = neko_lh_log2_element(lh);
    *base_out = (ptrdiff_t)header_bytes;
    *scale_out = (ptrdiff_t)(1u << log2_elem);
}

static void neko_derive_bootstrap_wellknown_layout(void) {
    void *boot_cld;
    void *string_klass;
    void *array_byte_klass;
    void *array_char_klass;
    void *seed_klass;
    boot_cld = neko_find_boot_class_loader_data();
    if (boot_cld == NULL) return;
    string_klass = g_neko_vm_layout.klass_java_lang_String;
    if (string_klass == NULL) {
        string_klass = neko_find_klass_by_name_in_cld(boot_cld, "java/lang/String", (uint16_t)(sizeof("java/lang/String") - 1u));
    }
    array_byte_klass = g_neko_vm_layout.klass_array_byte;
    if (array_byte_klass == NULL) {
        array_byte_klass = neko_find_klass_by_name_in_cld(boot_cld, "[B", (uint16_t)(sizeof("[B") - 1u));
    }
    array_char_klass = g_neko_vm_layout.klass_array_char;
    if (array_char_klass == NULL) {
        array_char_klass = neko_find_klass_by_name_in_cld(boot_cld, "[C", (uint16_t)(sizeof("[C") - 1u));
    }
    seed_klass = string_klass != NULL ? string_klass : (void*)neko_cld_first_klass(boot_cld);
    if (g_neko_vm_layout.off_class_klass < 0) {
        neko_derive_class_klass_offset_from_mirror(seed_klass);
    }
    neko_derive_string_layout_from_klass(string_klass);
    if (g_neko_vm_layout.off_array_base_byte < 0 || g_neko_vm_layout.off_array_scale_byte < 0) {
        ptrdiff_t base = -1;
        ptrdiff_t scale = -1;
        neko_derive_array_layout_from_klass(array_byte_klass, &base, &scale);
        if (base >= 0) g_neko_vm_layout.off_array_base_byte = base;
        if (scale >= 0) g_neko_vm_layout.off_array_scale_byte = scale;
    }
    if (g_neko_vm_layout.off_array_base_char < 0 || g_neko_vm_layout.off_array_scale_char < 0) {
        ptrdiff_t base = -1;
        ptrdiff_t scale = -1;
        neko_derive_array_layout_from_klass(array_char_klass, &base, &scale);
        if (base >= 0) g_neko_vm_layout.off_array_base_char = base;
        if (scale >= 0) g_neko_vm_layout.off_array_scale_char = scale;
    }
}

static jboolean neko_capture_wellknown_klasses(void) {
    void *boot_cld;
#ifdef NEKO_DEBUG_ENABLED
    NEKO_TRACE(0, "[nk] cap enter");
#endif
    if (g_neko_vm_layout.off_klass_next_link < 0) {
        neko_error_log("strict bootstrap requires Klass::_next_link VMStructs exposure");
#ifdef NEKO_DEBUG_ENABLED
        NEKO_TRACE(0, "[nk] cap fail reason=missing_next_link");
#endif
        return JNI_FALSE;
    }
    boot_cld = neko_find_boot_class_loader_data();
    if (boot_cld == NULL) {
        neko_error_log("strict bootstrap failed to locate boot ClassLoaderData");
#ifdef NEKO_DEBUG_ENABLED
        NEKO_TRACE(0, "[nk] cap fail reason=missing_boot_cld");
#endif
        return JNI_FALSE;
    }
#ifdef NEKO_DEBUG_ENABLED
    if (neko_debug_enabled()) {
        uint32_t klass_count = 0u;
        void *first_klass = neko_cld_first_klass(boot_cld);
        for (Klass *k = (Klass*)first_klass; k != NULL; k = neko_klass_next_link(k)) klass_count++;
        neko_native_debug_log("boot_cld=%p first_klass=%p klass_count=%u klass_name_off=%td sym_len_off=%td sym_body_off=%td",
            boot_cld, first_klass, klass_count,
            g_neko_vm_layout.off_klass_name, g_neko_vm_layout.off_symbol_length, g_neko_vm_layout.off_symbol_body);
    }
#endif
    g_neko_vm_layout.klass_java_lang_String = neko_find_klass_by_name_in_cld(boot_cld, "java/lang/String", (uint16_t)(sizeof("java/lang/String") - 1u));
    g_neko_vm_layout.klass_array_byte = neko_find_klass_by_name_in_cld(boot_cld, "[B", (uint16_t)(sizeof("[B") - 1u));
    g_neko_vm_layout.klass_array_char = neko_find_klass_by_name_in_cld(boot_cld, "[C", (uint16_t)(sizeof("[C") - 1u));
    g_neko_vm_layout.klass_neko_native_loader = neko_find_klass_by_name_in_cld_graph("dev/nekoobfuscator/runtime/NekoNativeLoader", (uint16_t)(sizeof("dev/nekoobfuscator/runtime/NekoNativeLoader") - 1u));
    g_neko_vm_layout.klass_exc_npe = neko_find_klass_by_name_in_cld_graph("java/lang/NullPointerException", (uint16_t)(sizeof("java/lang/NullPointerException") - 1u));
    g_neko_vm_layout.klass_exc_aioobe = neko_find_klass_by_name_in_cld_graph("java/lang/ArrayIndexOutOfBoundsException", (uint16_t)(sizeof("java/lang/ArrayIndexOutOfBoundsException") - 1u));
    g_neko_vm_layout.klass_exc_cce = neko_find_klass_by_name_in_cld_graph("java/lang/ClassCastException", (uint16_t)(sizeof("java/lang/ClassCastException") - 1u));
    g_neko_vm_layout.klass_exc_ae = neko_find_klass_by_name_in_cld_graph("java/lang/ArithmeticException", (uint16_t)(sizeof("java/lang/ArithmeticException") - 1u));
    g_neko_vm_layout.klass_exc_le = neko_find_klass_by_name_in_cld_graph("java/lang/LinkageError", (uint16_t)(sizeof("java/lang/LinkageError") - 1u));
    g_neko_vm_layout.klass_exc_oom = neko_find_klass_by_name_in_cld_graph("java/lang/OutOfMemoryError", (uint16_t)(sizeof("java/lang/OutOfMemoryError") - 1u));
    g_neko_vm_layout.klass_exc_imse = neko_find_klass_by_name_in_cld_graph("java/lang/IllegalMonitorStateException", (uint16_t)(sizeof("java/lang/IllegalMonitorStateException") - 1u));
    g_neko_vm_layout.klass_exc_ase = neko_find_klass_by_name_in_cld_graph("java/lang/ArrayStoreException", (uint16_t)(sizeof("java/lang/ArrayStoreException") - 1u));
    g_neko_vm_layout.klass_exc_nase = neko_find_klass_by_name_in_cld_graph("java/lang/NegativeArraySizeException", (uint16_t)(sizeof("java/lang/NegativeArraySizeException") - 1u));
    if (g_neko_vm_layout.klass_neko_native_loader != NULL) {
        int32_t loaded_offset = -1;
        if (!neko_resolve_field_offset(g_neko_vm_layout.klass_neko_native_loader, "loaded", 6u, "Z", 1u, true, &loaded_offset)) {
            neko_error_log("strict bootstrap failed to resolve NekoNativeLoader.loaded offset");
#ifdef NEKO_DEBUG_ENABLED
            NEKO_TRACE(0, "[nk] cap fail reason=loader_loaded_offset");
#endif
            return JNI_FALSE;
        }
        g_neko_vm_layout.off_loader_loaded_field = (ptrdiff_t)loaded_offset;
    }
#ifdef NEKO_DEBUG_ENABLED
    if (neko_debug_enabled()) {
        neko_native_debug_log(
            "boot_well_known_scan_ok=%d string=%p byte=%p char=%p loader=%p",
            (g_neko_vm_layout.klass_java_lang_String != NULL
             && g_neko_vm_layout.klass_array_byte != NULL
             && g_neko_vm_layout.klass_array_char != NULL
             && g_neko_vm_layout.klass_neko_native_loader != NULL) ? 1 : 0,
            g_neko_vm_layout.klass_java_lang_String,
            g_neko_vm_layout.klass_array_byte,
            g_neko_vm_layout.klass_array_char,
            g_neko_vm_layout.klass_neko_native_loader
        );
    }
#endif
    if (g_neko_vm_layout.klass_java_lang_String == NULL
        || g_neko_vm_layout.klass_array_byte == NULL
        || g_neko_vm_layout.klass_array_char == NULL
        || g_neko_vm_layout.klass_neko_native_loader == NULL) {
        neko_error_log("strict bootstrap failed to capture required well-known klasses");
#ifdef NEKO_DEBUG_ENABLED
        NEKO_TRACE(0, "[nk] cap fail reason=missing_required_klasses");
#endif
        return JNI_FALSE;
    }
#ifdef NEKO_DEBUG_ENABLED
    NEKO_TRACE(0, "[nk] cap ok str=%p arr_b=%p arr_c=%p", g_neko_vm_layout.klass_java_lang_String, g_neko_vm_layout.klass_array_byte, g_neko_vm_layout.klass_array_char);
#endif
    return JNI_TRUE;
}

static bool neko_read_symbol_bytes(void* sym, const uint8_t** bytes_out, uint16_t* len_out) {
    uint16_t len;
    const uint8_t *body;
    if (sym == NULL || bytes_out == NULL || len_out == NULL) return false;
    if (g_neko_vm_layout.off_symbol_length < 0 || g_neko_vm_layout.off_symbol_body < 0) return false;
    len = *(const uint16_t*)((const uint8_t*)sym + g_neko_vm_layout.off_symbol_length);
    body = (const uint8_t*)sym + g_neko_vm_layout.off_symbol_body;
    *bytes_out = body;
    *len_out = len;
    return true;
}

static bool neko_cp_utf8_symbol(void* cp, int idx, void** sym_out) {
    void *tags;
    int cp_length;
    int tags_length;
    const uint8_t *tags_data;
    if (sym_out != NULL) *sym_out = NULL;
    if (cp == NULL || sym_out == NULL) return false;
    if (g_neko_vm_layout.constant_pool_size == 0u || g_neko_vm_layout.off_constant_pool_tags < 0 || g_neko_vm_layout.off_constant_pool_length < 0) return false;
    cp_length = *(const int*)((const uint8_t*)cp + g_neko_vm_layout.off_constant_pool_length);
    if (idx <= 0 || idx >= cp_length) return false;
    tags = *(void**)((const uint8_t*)cp + g_neko_vm_layout.off_constant_pool_tags);
    if (tags == NULL) return false;
    tags_length = *(const int*)((const uint8_t*)tags + 0);
    if (idx >= tags_length) return false;
    tags_data = (const uint8_t*)tags + sizeof(int);
    if (tags_data[idx] != JVM_CONSTANT_Utf8) return false;
    *sym_out = *(void**)((uint8_t*)cp + g_neko_vm_layout.constant_pool_size + (size_t)idx * sizeof(void*));
    return *sym_out != NULL;
}

static bool neko_cp_utf8_matches(void* cp, uint16_t idx, const char* target, uint32_t target_len) {
    void *sym = NULL;
    const uint8_t *bytes = NULL;
    uint16_t length = 0;
    if (!neko_cp_utf8_symbol(cp, idx, &sym)) return false;
    if (!neko_read_symbol_bytes(sym, &bytes, &length)) return false;
    return (uint32_t)length == target_len && (target_len == 0u || memcmp(bytes, target, target_len) == 0);
}

static bool neko_field_walk_legacy(void* ik, uint32_t java_fields_count, const char* target_name, uint32_t target_name_len, const char* target_desc, uint32_t target_desc_len, bool want_static, int32_t* offset_out) {
    void *fields;
    void *cp;
    int fields_len;
    const uint16_t *fields_data;
    if (offset_out != NULL) *offset_out = -1;
    if (ik == NULL || target_name == NULL || target_desc == NULL || offset_out == NULL) return false;
    if (g_neko_vm_layout.off_instance_klass_fields < 0 || g_neko_vm_layout.off_instance_klass_constants < 0) return false;
    fields = *(void**)((uint8_t*)ik + g_neko_vm_layout.off_instance_klass_fields);
    cp = *(void**)((uint8_t*)ik + g_neko_vm_layout.off_instance_klass_constants);
    if (fields == NULL || cp == NULL) return false;
    fields_len = *(const int*)((const uint8_t*)fields + 0);
    if (fields_len < 0 || java_fields_count > (uint32_t)(fields_len / 6)) return false;
    fields_data = (const uint16_t*)((const uint8_t*)fields + sizeof(int));
    for (uint32_t i = 0; i < java_fields_count; i++) {
        const uint16_t *tuple = fields_data + (i * 6u);
        uint16_t access = tuple[0];
        uint16_t name_index = tuple[1];
        uint16_t signature_index = tuple[2];
        uint16_t low = tuple[4];
        uint16_t high = tuple[5];
        bool is_offset_set = ((low & 0x1u) != 0u) || ((low & 0x3u) == 0x1u);
        bool is_static = (access & JVM_ACC_STATIC) != 0u;
        uint32_t raw_offset;
        if (!is_offset_set || is_static != want_static) continue;
        if (!neko_cp_utf8_matches(cp, name_index, target_name, target_name_len)) continue;
        if (!neko_cp_utf8_matches(cp, signature_index, target_desc, target_desc_len)) continue;
        raw_offset = (((uint32_t)high << 16) | (uint32_t)low) >> 2;
        *offset_out = (int32_t)raw_offset;
        return true;
    }
    return false;
}

static bool neko_read_u5(const uint8_t* buf, int limit, int* p, uint32_t* out) {
    if (*p >= limit) return false;
    uint32_t b0 = buf[*p];
    if (b0 == 0) return false;
    uint32_t sum = b0 - 1;
    (*p)++;
    if (b0 <= 191) {
        *out = sum;
        return true;
    }
    uint32_t shift = 6;
    for (int n = 1; n <= 4; n++, shift += 6) {
        uint32_t b;
        if (*p >= limit) return false;
        b = buf[*p];
        if (b == 0) return false;
        sum += (b - 1) << shift;
        (*p)++;
        if (b <= 191 || n == 4) {
            *out = sum;
            return true;
        }
    }
    return false;
}

static bool neko_field_walk_fis21(void* ik, const char* target_name, uint32_t target_name_len, const char* target_desc, uint32_t target_desc_len, bool want_static, int32_t* offset_out) {
    void *fis;
    void *cp;
    int fis_len;
    int p = 0;
    const uint8_t *fis_data;
    uint32_t num_java_fields = 0;
    uint32_t num_injected_fields = 0;
    if (offset_out != NULL) *offset_out = -1;
    if (ik == NULL || target_name == NULL || target_desc == NULL || offset_out == NULL) return false;
    if (g_neko_vm_layout.off_instance_klass_fieldinfo_stream < 0 || g_neko_vm_layout.off_instance_klass_constants < 0) return false;
    fis = *(void**)((uint8_t*)ik + g_neko_vm_layout.off_instance_klass_fieldinfo_stream);
    cp = *(void**)((uint8_t*)ik + g_neko_vm_layout.off_instance_klass_constants);
    if (fis == NULL || cp == NULL) return false;
    fis_len = *(const int*)((const uint8_t*)fis + 0);
    if (fis_len <= 0) return false;
    fis_data = (const uint8_t*)fis + sizeof(int);
    if (!neko_read_u5(fis_data, fis_len, &p, &num_java_fields)) return false;
    if (!neko_read_u5(fis_data, fis_len, &p, &num_injected_fields)) return false;
    for (uint32_t i = 0; i < num_java_fields; i++) {
        uint32_t name_index;
        uint32_t signature_index;
        uint32_t offset;
        uint32_t access_flags;
        uint32_t field_flags;
        uint32_t unused;
        bool is_static;
        if (!neko_read_u5(fis_data, fis_len, &p, &name_index)) return false;
        if (!neko_read_u5(fis_data, fis_len, &p, &signature_index)) return false;
        if (!neko_read_u5(fis_data, fis_len, &p, &offset)) return false;
        if (!neko_read_u5(fis_data, fis_len, &p, &access_flags)) return false;
        if (!neko_read_u5(fis_data, fis_len, &p, &field_flags)) return false;
        if ((field_flags & 1u) != 0u && !neko_read_u5(fis_data, fis_len, &p, &unused)) return false;
        if ((field_flags & 4u) != 0u && !neko_read_u5(fis_data, fis_len, &p, &unused)) return false;
        if ((field_flags & 16u) != 0u && !neko_read_u5(fis_data, fis_len, &p, &unused)) return false;
        is_static = (access_flags & JVM_ACC_STATIC) != 0u;
        if (is_static != want_static) continue;
        if (!neko_cp_utf8_matches(cp, (uint16_t)name_index, target_name, target_name_len)) continue;
        if (!neko_cp_utf8_matches(cp, (uint16_t)signature_index, target_desc, target_desc_len)) continue;
        *offset_out = (int32_t)offset;
        return true;
    }
    (void)num_injected_fields;
    return false;
}

static bool neko_resolve_field_offset(void* klass, const char* target_name, uint32_t target_name_len, const char* target_desc, uint32_t target_desc_len, bool want_static, int32_t* offset_out) {
    uint32_t java_fields_count;
    if (offset_out != NULL) *offset_out = -1;
    if (klass == NULL || target_name == NULL || target_desc == NULL || offset_out == NULL) return false;
    if (g_neko_vm_layout.off_instance_klass_fieldinfo_stream >= 0) {
        return neko_field_walk_fis21(klass, target_name, target_name_len, target_desc, target_desc_len, want_static, offset_out);
    }
    if (g_neko_vm_layout.off_instance_klass_java_fields_count < 0) return false;
    java_fields_count = (uint32_t)*(const uint16_t*)((const uint8_t*)klass + g_neko_vm_layout.off_instance_klass_java_fields_count);
    return neko_field_walk_legacy(klass, java_fields_count, target_name, target_name_len, target_desc, target_desc_len, want_static, offset_out);
}

static inline void* neko_instance_klass_methods_array(void *klass) {
    if (klass == NULL || g_neko_vm_layout.off_instance_klass_methods < 0) return NULL;
    return *(void**)((uint8_t*)klass + g_neko_vm_layout.off_instance_klass_methods);
}

static inline int32_t neko_array_length(void *array) {
    int32_t length;
    if (array == NULL) return 0;
    length = *(int32_t*)((uint8_t*)array + 0);
    if (length < 0 || length > 1000000) return 0;
    return length;
}

static inline void* neko_method_array_at(void *methods, int32_t index) {
    ptrdiff_t data_offset;
    if (methods == NULL || index < 0) return NULL;
    data_offset = neko_align_up_ptrdiff((ptrdiff_t)sizeof(int32_t), (ptrdiff_t)sizeof(void*));
    return *(void**)((uint8_t*)methods + data_offset + ((ptrdiff_t)index * (ptrdiff_t)sizeof(void*)));
}

static bool neko_method_matches_manifest_entry(void *method_star, const NekoManifestMethod *entry, uint16_t name_len, uint16_t desc_len) {
    void *const_method;
    void *constant_pool;
    uint16_t name_index;
    uint16_t signature_index;
    if (method_star == NULL || entry == NULL) return false;
    if (g_neko_vm_layout.off_method_const_method < 0 || g_neko_vm_layout.off_const_method_constants < 0) return false;
    if (g_neko_vm_layout.off_const_method_name_index < 0 || g_neko_vm_layout.off_const_method_signature_index < 0) return false;
    const_method = *(void**)((uint8_t*)method_star + g_neko_vm_layout.off_method_const_method);
    if (const_method == NULL) return false;
    constant_pool = *(void**)((uint8_t*)const_method + g_neko_vm_layout.off_const_method_constants);
    if (constant_pool == NULL) return false;
    name_index = *(uint16_t*)((uint8_t*)const_method + g_neko_vm_layout.off_const_method_name_index);
    signature_index = *(uint16_t*)((uint8_t*)const_method + g_neko_vm_layout.off_const_method_signature_index);
    if (!neko_cp_utf8_matches(constant_pool, name_index, entry->method_name, name_len)) return false;
    return neko_cp_utf8_matches(constant_pool, signature_index, entry->method_desc, desc_len);
}

static void neko_bootstrap_owner_discovery(void) {
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        const NekoManifestMethod *entry = &g_neko_manifest_methods[i];
        size_t owner_len;
        size_t name_len;
        size_t desc_len;
        Klass *owner_klass;
        void *methods;
        int32_t method_count;
        jboolean matched = JNI_FALSE;
        if (entry->owner_internal == NULL || entry->method_name == NULL || entry->method_desc == NULL) continue;
        owner_len = strlen(entry->owner_internal);
        if (owner_len >= 65536u) continue;
        name_len = strlen(entry->method_name);
        desc_len = strlen(entry->method_desc);
        if (name_len >= 65536u || desc_len >= 65536u) continue;
        owner_klass = neko_find_klass_by_name_in_cld_graph(entry->owner_internal, (uint16_t)owner_len);
        if (owner_klass == NULL) {
            NEKO_TRACE(1, "[nk] dx owner_miss idx=%u name=%s", i, entry->owner_internal);
            continue;
        }
        methods = neko_instance_klass_methods_array(owner_klass);
        method_count = neko_array_length(methods);
        for (int32_t method_index = 0; method_index < method_count; method_index++) {
            void *method_star = neko_method_array_at(methods, method_index);
            if (!neko_method_matches_manifest_entry(method_star, entry, (uint16_t)name_len, (uint16_t)desc_len)) continue;
            neko_record_manifest_match(i, method_star);
            matched = JNI_TRUE;
            break;
        }
        if (!matched) {
            NEKO_TRACE(1, "[nk] dx method_miss idx=%u %s.%s%s", i, entry->owner_internal, entry->method_name, entry->method_desc);
        }
    }
    (void)neko_refresh_cld_snapshot_and_detect_unload();
}

static void neko_resolve_string_intern_layout(void) {
    int32_t offset = -1;
    g_neko_vm_layout.off_string_hash = -1;
    if (neko_resolve_field_offset(g_neko_vm_layout.klass_java_lang_String, "hash", 4u, "I", 1u, false, &offset)) {
        g_neko_vm_layout.off_string_hash = offset;
    } else {
        NEKO_TRACE(1, "[nk] si unresolved java/lang/String.hash");
    }
}

static void* neko_load_oop_from_cell(const void *cell) {
    if (cell == NULL) return NULL;
    if (g_neko_vm_layout.narrow_oop_shift > 0 || g_neko_vm_layout.narrow_oop_base != 0u) {
        u4 narrow = __atomic_load_n((const u4*)cell, __ATOMIC_ACQUIRE);
        return neko_decode_heap_oop(narrow);
    }
    return __atomic_load_n((void* const*)cell, __ATOMIC_ACQUIRE);
}

static void neko_store_oop_to_cell(void *cell, void *raw_oop) {
    if (cell == NULL) return;
    if (g_neko_vm_layout.narrow_oop_shift > 0 || g_neko_vm_layout.narrow_oop_base != 0u) {
        u4 narrow = neko_encode_heap_oop(raw_oop);
        __atomic_store_n((u4*)cell, narrow, __ATOMIC_RELEASE);
        return;
    }
    __atomic_store_n((void**)cell, raw_oop, __ATOMIC_RELEASE);
}

static void neko_log_boot_cld_root_chain_result(int ok, const char *fallback) {
#ifdef NEKO_DEBUG_ENABLED
    if (!neko_debug_enabled()) return;
    if (ok != 0) {
        if (g_neko_boot_cld_root_chain_logged == 0u) {
            g_neko_boot_cld_root_chain_logged = 1u;
            neko_native_debug_log("boot_cld_root_chain_ok=1");
        }
        return;
    }
    neko_native_debug_log("boot_cld_root_chain_ok=0 fallback=%s", fallback == NULL ? "unknown" : fallback);
#else
    (void)ok;
    (void)fallback;
#endif
}

static void neko_reset_string_intern_entries(void) {
    memset(g_neko_string_intern_buckets, 0, sizeof(g_neko_string_intern_buckets));
    memset(g_neko_string_intern_entries, 0, sizeof(g_neko_string_intern_entries));
    g_neko_string_intern_filled = 0u;
}

static void* neko_boot_cld_head(void) {
    if (g_neko_vm_layout.off_cldg_head == 0u) return NULL;
    return *(void**)(uintptr_t)g_neko_vm_layout.off_cldg_head;
}

static jboolean neko_is_boot_cld_loader_null(void *loader_field) {
    if (loader_field == NULL) return JNI_TRUE;
    if (g_neko_vm_layout.cld_class_loader_is_oophandle) {
        void *handle = *(void**)loader_field;
        if (handle == NULL) return JNI_TRUE;
        if (g_neko_vm_layout.off_oophandle_obj < 0) return JNI_FALSE;
        return *(void**)((uint8_t*)handle + g_neko_vm_layout.off_oophandle_obj) == NULL ? JNI_TRUE : JNI_FALSE;
    }
    return *(void**)loader_field == NULL ? JNI_TRUE : JNI_FALSE;
}

static void* neko_find_boot_class_loader_data(void) {
    void *current = neko_boot_cld_head();
    void *best = NULL;
    uint32_t best_score = 0u;
    if (current == NULL || g_neko_vm_layout.off_cld_next < 0 || g_neko_vm_layout.off_cld_class_loader < 0) return NULL;
    while (current != NULL) {
        void *loader_field = (uint8_t*)current + g_neko_vm_layout.off_cld_class_loader;
        if (neko_is_boot_cld_loader_null(loader_field)) {
            uint32_t score = neko_count_cld_klasses(current, 4096u);
            if (g_neko_vm_layout.off_klass_name >= 0) {
                if (neko_find_klass_by_name_in_cld(current, "java/lang/String", (uint16_t)(sizeof("java/lang/String") - 1u)) != NULL) score += 100000u;
                if (neko_find_klass_by_name_in_cld(current, "[B", (uint16_t)(sizeof("[B") - 1u)) != NULL) score += 10000u;
                if (neko_find_klass_by_name_in_cld(current, "[C", (uint16_t)(sizeof("[C") - 1u)) != NULL) score += 10000u;
                if (neko_find_klass_by_name_in_cld(current, "java/lang/Object", (uint16_t)(sizeof("java/lang/Object") - 1u)) != NULL) score += 1000u;
            }
            if (best == NULL || score > best_score) {
                best = current;
                best_score = score;
            }
        }
        current = *(void**)((uint8_t*)current + g_neko_vm_layout.off_cld_next);
    }
    return best;
}

static NekoChunkedHandleListChunk* neko_alloc_string_root_chunks(uint32_t root_count) {
    uint32_t chunk_count = root_count == 0u ? 0u : (root_count + 31u) / 32u;
    NekoChunkedHandleListChunk *head = NULL;
    NekoChunkedHandleListChunk *prev = NULL;
    if (chunk_count == 0u) return NULL;
    for (uint32_t i = 0; i < chunk_count; i++) {
        NekoChunkedHandleListChunk *chunk = (NekoChunkedHandleListChunk*)malloc(sizeof(NekoChunkedHandleListChunk));
        if (chunk == NULL) {
            while (head != NULL) {
                NekoChunkedHandleListChunk *next = head->next;
                free(head);
                head = next;
            }
            return NULL;
        }
        memset(chunk->data, 0, sizeof(chunk->data));
        chunk->size = 32u;
        chunk->next = NULL;
        if (head == NULL) {
            head = chunk;
        } else {
            prev->next = chunk;
        }
        prev = chunk;
    }
    return head;
}

static void* neko_nth_string_root_cell(NekoChunkedHandleListChunk *head, uint32_t index) {
    uint32_t chunk_index = index / 32u;
    uint32_t slot_index = index % 32u;
    NekoChunkedHandleListChunk *chunk = head;
    for (uint32_t i = 0; chunk != NULL && i < chunk_index; i++) {
        chunk = chunk->next;
    }
    return chunk == NULL ? NULL : (void*)&chunk->data[slot_index];
}

static jboolean neko_self_check_string_root_chain(NekoChunkedHandleListChunk *head, uint32_t root_count) {
    jobject probe = NULL;
    void *probe_oop = NULL;
    void *round_trip = NULL;
    uint32_t checks = root_count == 0u ? 0u : (root_count < 4u ? root_count : 4u);
    JNIEnv *env = neko_current_env();
    if (head == NULL || root_count == 0u || env == NULL) return JNI_FALSE;
    probe = neko_new_string_utf(env, "neko-w1-root-check");
    if (probe == NULL) {
        return JNI_FALSE;
    }
    probe_oop = neko_handle_oop(probe);
    for (uint32_t i = 0; i < checks; i++) {
        void *cell = neko_nth_string_root_cell(head, i);
        if (cell == NULL) {
            neko_delete_local_ref(env, probe);
            return JNI_FALSE;
        }
        neko_store_oop_to_cell(cell, probe_oop);
        round_trip = neko_load_oop_from_cell(cell);
        if (round_trip != probe_oop) {
            neko_delete_local_ref(env, probe);
            return JNI_FALSE;
        }
        neko_store_oop_to_cell(cell, NULL);
    }
    neko_delete_local_ref(env, probe);
    return JNI_TRUE;
}

static jboolean neko_publish_string_root_chain(void *boot_cld, NekoChunkedHandleListChunk *head) {
    void **handles_head;
    NekoChunkedHandleListChunk *tail;
    if (boot_cld == NULL || head == NULL || g_neko_vm_layout.off_cld_handles < 0) return JNI_FALSE;
    handles_head = (void**)((uint8_t*)boot_cld + g_neko_vm_layout.off_cld_handles);
    tail = head;
    while (tail->next != NULL) {
        tail = tail->next;
    }
    for (;;) {
        void *expected = __atomic_load_n(handles_head, __ATOMIC_ACQUIRE);
        tail->next = (NekoChunkedHandleListChunk*)expected;
        if (__atomic_compare_exchange_n(handles_head, &expected, head, JNI_FALSE, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
            return JNI_TRUE;
        }
    }
}

static void neko_assign_string_root_cells(NekoChunkedHandleListChunk *head) {
    uint32_t slot = 0u;
    for (uint32_t method_index = 0; method_index < g_neko_manifest_method_count; method_index++) {
        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[method_index];
        for (uint32_t site_index = 0; site_index < method->ldc_site_count; site_index++) {
            NekoManifestLdcSite *site = &method->ldc_sites[site_index];
            NekoStringInternEntry *entry;
            void *string_oop;
            if (site->kind != NEKO_LDC_KIND_STRING) continue;
            neko_resolve_ldc_string(site);
            entry = (NekoStringInternEntry*)site->resolved_cache_handle;
            if (entry == NULL) continue;
            if (entry->root_cell != NULL) continue;
            entry->root_cell = neko_nth_string_root_cell(head, slot++);
            if (entry->root_cell == NULL) continue;
            string_oop = neko_create_ldc_string_oop(site, NULL, NULL, NULL, NULL);
            if (string_oop != NULL) {
                neko_store_oop_to_cell(entry->root_cell, string_oop);
            }
        }
    }
}

static void neko_string_intern_prewarm_and_publish(JNIEnv *env) {
    void *boot_cld;
    NekoChunkedHandleListChunk *chunk_head;
    if (env == NULL) return;
    if (g_neko_vm_layout.klass_java_lang_String == NULL
        || g_neko_vm_layout.klass_array_byte == NULL
        || g_neko_vm_layout.klass_array_char == NULL) {
        neko_log_boot_cld_root_chain_result(0, "missing_klasses");
        return;
    }
    if (NEKO_STRING_INTERN_SLOT_COUNT == 0u) {
        g_neko_string_root_backend = NEKO_STRING_ROOT_BACKEND_BOOT_CLD;
        return;
    }
    neko_reset_string_intern_entries();
    neko_manifest_lock_acquire();
    boot_cld = neko_find_boot_class_loader_data();
    if (boot_cld == NULL || g_neko_vm_layout.off_cld_handles < 0) {
        g_neko_string_root_backend = NEKO_STRING_ROOT_BACKEND_FALLBACK_REGENERATE;
        neko_log_boot_cld_root_chain_result(0, "boot_cld_or_handles");
        neko_manifest_lock_release();
        return;
    }
    chunk_head = neko_alloc_string_root_chunks(NEKO_STRING_INTERN_SLOT_COUNT);
    if (chunk_head == NULL) {
        g_neko_string_root_backend = NEKO_STRING_ROOT_BACKEND_FALLBACK_REGENERATE;
        neko_log_boot_cld_root_chain_result(0, "alloc_chunks");
        neko_manifest_lock_release();
        return;
    }
    if (!neko_self_check_string_root_chain(chunk_head, NEKO_STRING_INTERN_SLOT_COUNT)) {
        g_neko_string_root_backend = NEKO_STRING_ROOT_BACKEND_FALLBACK_REGENERATE;
        neko_log_boot_cld_root_chain_result(0, "self_check");
        neko_manifest_lock_release();
        return;
    }
    if (!neko_publish_string_root_chain(boot_cld, chunk_head)) {
        g_neko_string_root_backend = NEKO_STRING_ROOT_BACKEND_FALLBACK_REGENERATE;
        neko_log_boot_cld_root_chain_result(0, "publish_chain");
        neko_manifest_lock_release();
        return;
    }
    g_neko_string_root_chunk_head = chunk_head;
    g_neko_string_root_backend = NEKO_STRING_ROOT_BACKEND_BOOT_CLD;
    neko_assign_string_root_cells(chunk_head);
    neko_manifest_lock_release();
    neko_log_boot_cld_root_chain_result(1, NULL);
}

static void neko_publish_prepared_ldc_class_site(JNIEnv *env, jclass klass, const char *signature, void *prepared_klass) {
    if (env == NULL || klass == NULL || signature == NULL || prepared_klass == NULL) return;
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];
        for (uint32_t site_index = 0; site_index < method->ldc_site_count; site_index++) {
            NekoManifestLdcSite *site = &method->ldc_sites[site_index];
            if (site->kind != NEKO_LDC_KIND_CLASS) continue;
            if (__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE) != NULL) continue;
            if (!neko_ldc_site_matches_loaded_class(env, site, klass, signature)) continue;
            __atomic_store_n(&site->cached_klass, prepared_klass, __ATOMIC_RELEASE);
            NEKO_TRACE(0, "[nk] ldc-cls bind %s %p", signature, prepared_klass);
        }
    }
}

static inline void* neko_method_holder_klass(void *method_star) {
    void *const_method;
    void *constant_pool;
    if (method_star == NULL || g_neko_vm_layout.off_method_const_method < 0 || g_neko_vm_layout.off_const_method_constants < 0 || g_neko_vm_layout.off_constant_pool_holder < 0) return NULL;
    const_method = *(void**)((uint8_t*)method_star + g_neko_vm_layout.off_method_const_method);
    if (const_method == NULL) return NULL;
    constant_pool = *(void**)((uint8_t*)const_method + g_neko_vm_layout.off_const_method_constants);
    if (constant_pool == NULL) return NULL;
    if (g_neko_vm_layout.constant_pool_holder_is_narrow) {
        return neko_decode_klass_pointer(*(u4*)((uint8_t*)constant_pool + g_neko_vm_layout.off_constant_pool_holder));
    }
    return *(void**)((uint8_t*)constant_pool + g_neko_vm_layout.off_constant_pool_holder);
}

static jthrowable neko_make_global_throwable(JNIEnv *env,
                                             const char *class_name,
                                             const char *message) {
    jclass cls = NULL;
    jmethodID init = NULL;
    jstring text = NULL;
    jobject local = NULL;
    jobject global = NULL;

    if (env == NULL || class_name == NULL) return NULL;

    cls = (*env)->FindClass(env, class_name);
    if (cls == NULL) goto cleanup;

    if (message != NULL) {
        jvalue args[1];
        init = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;)V");
        if (init == NULL) goto cleanup;
        text = (*env)->NewStringUTF(env, message);
        if (text == NULL) goto cleanup;
        args[0].l = text;
        local = (*env)->NewObjectA(env, cls, init, args);
    } else {
        init = (*env)->GetMethodID(env, cls, "<init>", "()V");
        if (init == NULL) goto cleanup;
        local = (*env)->NewObjectA(env, cls, init, NULL);
    }

    if (local == NULL) goto cleanup;
    global = (*env)->NewGlobalRef(env, local);

cleanup:
    if (local != NULL) (*env)->DeleteLocalRef(env, local);
    if (text != NULL) (*env)->DeleteLocalRef(env, text);
    if (cls != NULL) (*env)->DeleteLocalRef(env, cls);
    return (jthrowable)global;
}

static jboolean neko_init_throwable_cache(JNIEnv *env) {
    if (env == NULL) return JNI_FALSE;

    g_neko_throw_npe = neko_make_global_throwable(env, "java/lang/NullPointerException", NULL);
    g_neko_throw_aioobe = neko_make_global_throwable(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
    g_neko_throw_cce = neko_make_global_throwable(env, "java/lang/ClassCastException", NULL);
    g_neko_throw_ae = neko_make_global_throwable(env, "java/lang/ArithmeticException", "/ by zero");
    g_neko_throw_le = neko_make_global_throwable(env, "java/lang/LinkageError", NULL);
    g_neko_throw_oom = neko_make_global_throwable(env, "java/lang/OutOfMemoryError", NULL);
    g_neko_throw_imse = neko_make_global_throwable(env, "java/lang/IllegalMonitorStateException", NULL);
    g_neko_throw_ase = neko_make_global_throwable(env, "java/lang/ArrayStoreException", NULL);
    g_neko_throw_nase = neko_make_global_throwable(env, "java/lang/NegativeArraySizeException", NULL);
    g_neko_throw_bme = neko_make_global_throwable(env, "java/lang/BootstrapMethodError", NULL);
    g_neko_throw_loader_linkage = neko_make_global_throwable(
        env,
        "java/lang/LinkageError",
        "please check your native library load correctly"
    );

    return g_neko_throw_npe != NULL
        && g_neko_throw_aioobe != NULL
        && g_neko_throw_cce != NULL
        && g_neko_throw_ae != NULL
        && g_neko_throw_le != NULL
        && g_neko_throw_oom != NULL
        && g_neko_throw_imse != NULL
        && g_neko_throw_ase != NULL
        && g_neko_throw_nase != NULL
        && g_neko_throw_bme != NULL
        && g_neko_throw_loader_linkage != NULL
        ? JNI_TRUE : JNI_FALSE;
}

static jint neko_throw_cached(JNIEnv *env, jthrowable cached) {
    if (env == NULL || cached == NULL) return JNI_ERR;
    return (*env)->Throw(env, cached);
}

static void neko_raise_cached_pending(void *thread, jthrowable cached) {
    uintptr_t cell;
    void *oop;
    if (thread == NULL || cached == NULL) return;
    cell = ((uintptr_t)cached) & ~(uintptr_t)(sizeof(void*) - 1u);
    oop = neko_load_oop_from_cell((const void*)cell);
    if (oop != NULL) neko_set_pending_exception(thread, oop);
}

static inline jthrowable neko_cached_throwable_for_kind(uint32_t kind) {
    switch (kind) {
        case NEKO_THROW_NPE: return g_neko_throw_npe;
        case NEKO_THROW_AIOOBE: return g_neko_throw_aioobe;
        case NEKO_THROW_CCE: return g_neko_throw_cce;
        case NEKO_THROW_AE: return g_neko_throw_ae;
        case NEKO_THROW_LE: return g_neko_throw_le;
        case NEKO_THROW_OOM: return g_neko_throw_oom;
        case NEKO_THROW_IMSE: return g_neko_throw_imse;
        case NEKO_THROW_ASE: return g_neko_throw_ase;
        case NEKO_THROW_NASE: return g_neko_throw_nase;
        case NEKO_THROW_BME: return g_neko_throw_bme;
        default: return g_neko_throw_le;
    }
}


static void neko_raise_bound_resolution_error(JNIEnv *env, const char *errorClass, const char *message) {
    (void)errorClass;
    (void)message;
    (void)neko_throw_cached(env, g_neko_throw_le);
}

static void neko_bind_log_failure(JNIEnv *env, const char *errorClass, const char *message) {
    neko_raise_bound_resolution_error(env, errorClass, message);
}

static void neko_bind_class_slot(JNIEnv *env, jclass *slot, const char *owner) {
    jclass localClass;
    jobject globalRef;
    char message[256];
    if (env == NULL || slot == NULL || *slot != NULL || owner == NULL) return;
    localClass = neko_find_class(env, owner);
    if (localClass == NULL) {
        snprintf(message, sizeof(message), "Bind-time class resolution failed: %s", owner);
        neko_bind_log_failure(env, "java/lang/NoClassDefFoundError", message);
        if (localClass != NULL) neko_delete_local_ref(env, localClass);
        return;
    }
    globalRef = neko_new_global_ref(env, localClass);
    neko_delete_local_ref(env, localClass);
    if (globalRef == NULL) {
        snprintf(message, sizeof(message), "Bind-time class global-ref failed: %s", owner);
        neko_bind_log_failure(env, "java/lang/NoClassDefFoundError", message);
        return;
    }
    *slot = (jclass)globalRef;
}

static void neko_bind_method_slot(JNIEnv *env, jmethodID *slot, jclass cls, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    char message[320];
    if (env == NULL || slot == NULL || *slot != NULL || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    *slot = isStatic ? neko_get_static_method_id(env, cls, name, desc) : neko_get_method_id(env, cls, name, desc);
    if (*slot == NULL) {
        snprintf(message, sizeof(message), "Bind-time %s method resolution failed: %s.%s%s", isStatic ? "static" : "instance", owner, name, desc);
        neko_bind_log_failure(env, "java/lang/NoSuchMethodError", message);
        *slot = NULL;
    }
}

static void neko_bind_field_slot(JNIEnv *env, jfieldID *slot, jclass cls, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    char message[320];
    if (env == NULL || slot == NULL || *slot != NULL || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    *slot = isStatic ? neko_get_static_field_id(env, cls, name, desc) : neko_get_field_id(env, cls, name, desc);
    if (*slot == NULL) {
        snprintf(message, sizeof(message), "Bind-time %s field resolution failed: %s.%s:%s", isStatic ? "static" : "instance", owner, name, desc);
        neko_bind_log_failure(env, "java/lang/NoSuchFieldError", message);
        *slot = NULL;
    }
}

static void neko_bind_string_slot(JNIEnv *env, jstring *slot, const char *utf) {
    jstring localString;
    jobject globalRef;
    char message[256];
    if (env == NULL || slot == NULL || *slot != NULL || utf == NULL) return;
    localString = neko_new_string_utf(env, utf);
    if (localString == NULL) {
        snprintf(message, sizeof(message), "Bind-time string resolution failed: %s", utf);
        neko_bind_log_failure(env, "java/lang/IllegalStateException", message);
        if (localString != NULL) neko_delete_local_ref(env, localString);
        return;
    }
    globalRef = neko_new_global_ref(env, localString);
    neko_delete_local_ref(env, localString);
    if (globalRef == NULL) {
        snprintf(message, sizeof(message), "Bind-time string global-ref failed: %s", utf);
        neko_bind_log_failure(env, "java/lang/IllegalStateException", message);
        return;
    }
    *slot = (jstring)globalRef;
}

static jclass neko_bound_class(JNIEnv *env, jclass slot, const char *owner) {
    char message[256];
    if (slot != NULL) return slot;
    snprintf(message, sizeof(message), "Unresolved bound class: %s", owner == NULL ? "<null>" : owner);
    neko_raise_bound_resolution_error(env, "java/lang/NoClassDefFoundError", message);
    return NULL;
}

static jmethodID neko_bound_method(JNIEnv *env, jmethodID slot, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    char message[320];
    if (slot != NULL) return slot;
    snprintf(message, sizeof(message), "Unresolved bound %s method: %s.%s%s", isStatic ? "static" : "instance", owner == NULL ? "<null>" : owner, name == NULL ? "<null>" : name, desc == NULL ? "<null>" : desc);
    neko_raise_bound_resolution_error(env, "java/lang/NoSuchMethodError", message);
    return NULL;
}

static jfieldID neko_bound_field(JNIEnv *env, jfieldID slot, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    char message[320];
    if (slot != NULL) return slot;
    snprintf(message, sizeof(message), "Unresolved bound %s field: %s.%s:%s", isStatic ? "static" : "instance", owner == NULL ? "<null>" : owner, name == NULL ? "<null>" : name, desc == NULL ? "<null>" : desc);
    neko_raise_bound_resolution_error(env, "java/lang/NoSuchFieldError", message);
    return NULL;
}

static jstring neko_bound_string(JNIEnv *env, jstring slot, const char *utf) {
    char message[256];
    if (slot != NULL) return slot;
    snprintf(message, sizeof(message), "Unresolved bound string: %s", utf == NULL ? "<null>" : utf);
    neko_raise_bound_resolution_error(env, "java/lang/IllegalStateException", message);
    return NULL;
}

// === Bind-time owner resolution ===
static void neko_bind_owner_0(JNIEnv *env, jclass self_class) {
    if (env == NULL || g_owner_bound_0) return;
    (void)self_class;
    g_owner_bound_0 = JNI_TRUE;
}

static void neko_bind_owner_1(JNIEnv *env, jclass self_class) {
    if (env == NULL || g_owner_bound_1) return;
    (void)self_class;
    g_owner_bound_1 = JNI_TRUE;
}

static void neko_bind_owner_2(JNIEnv *env, jclass self_class) {
    if (env == NULL || g_owner_bound_2) return;
    (void)self_class;
    g_owner_bound_2 = JNI_TRUE;
    neko_bind_class_slot(env, &g_cls_3, "pack/tests/basics/inner/Exec");
    neko_bind_method_slot(env, &g_mid_0, g_cls_3, "pack/tests/basics/inner/Exec", "addF", "()V", JNI_FALSE);
}

static void neko_bind_owner_3(JNIEnv *env, jclass self_class) {
    if (env == NULL || g_owner_bound_3) return;
    (void)self_class;
    g_owner_bound_3 = JNI_TRUE;
}

static void neko_bind_owner_4(JNIEnv *env, jclass self_class) {
    if (env == NULL || g_owner_bound_4) return;
    (void)self_class;
    g_owner_bound_4 = JNI_TRUE;
}

static void neko_bind_owner_5(JNIEnv *env, jclass self_class) {
    if (env == NULL || g_owner_bound_5) return;
    (void)self_class;
    g_owner_bound_5 = JNI_TRUE;
    neko_bind_class_slot(env, &g_cls_6, "pack/tests/basics/runable/Exec");
    neko_bind_method_slot(env, &g_mid_1, g_cls_6, "pack/tests/basics/runable/Exec", "doAdd", "()V", JNI_FALSE);
}

static void neko_bind_owner_6(JNIEnv *env, jclass self_class) {
    if (env == NULL || g_owner_bound_6) return;
    (void)self_class;
    g_owner_bound_6 = JNI_TRUE;
}

static void neko_bind_owner_7(JNIEnv *env, jclass self_class) {
    if (env == NULL || g_owner_bound_7) return;
    (void)self_class;
    g_owner_bound_7 = JNI_TRUE;
}

static void neko_bind_owner_8(JNIEnv *env, jclass self_class) {
    if (env == NULL || g_owner_bound_8) return;
    (void)self_class;
    g_owner_bound_8 = JNI_TRUE;
}

static void neko_bind_owner_9(JNIEnv *env, jclass self_class) {
    if (env == NULL || g_owner_bound_9) return;
    (void)self_class;
    g_owner_bound_9 = JNI_TRUE;
}

// === Wave 2 field/LDC support ===
static jclass g_neko_wave2_class_cls = NULL;
static jclass g_neko_wave2_field_cls = NULL;
static jclass g_neko_wave2_unsafe_cls = NULL;
static jobject g_neko_wave2_unsafe_singleton = NULL;
static jmethodID g_neko_wave2_class_get_declared_field = NULL;
static jmethodID g_neko_wave2_unsafe_object_field_offset = NULL;
static jmethodID g_neko_wave2_unsafe_static_field_offset = NULL;
static jmethodID g_neko_wave2_unsafe_array_base_offset = NULL;
static jmethodID g_neko_wave2_unsafe_array_index_scale = NULL;

static char* neko_wave2_copy_bytes(const uint8_t *bytes, size_t len) {
    char *copy = (char*)malloc(len + 1u);
    if (copy == NULL) return NULL;
    if (len != 0u && bytes != NULL) memcpy(copy, bytes, len);
    copy[len] = '\0';
    return copy;
}

static void neko_wave2_capture_pending(JNIEnv *env, void *thread, const char *fallback_class, const char *fallback_message) {
    (void)thread;
    (void)fallback_message;
    if (fallback_class == NULL) { (void)neko_throw_cached(env, g_neko_throw_le); return; }
    if (strcmp(fallback_class, "java/lang/NullPointerException") == 0) { (void)neko_throw_cached(env, g_neko_throw_npe); return; }
    if (strcmp(fallback_class, "java/lang/ArrayIndexOutOfBoundsException") == 0) { (void)neko_throw_cached(env, g_neko_throw_aioobe); return; }
    if (strcmp(fallback_class, "java/lang/ClassCastException") == 0) { (void)neko_throw_cached(env, g_neko_throw_cce); return; }
    if (strcmp(fallback_class, "java/lang/ArithmeticException") == 0) { (void)neko_throw_cached(env, g_neko_throw_ae); return; }
    if (strcmp(fallback_class, "java/lang/OutOfMemoryError") == 0) { (void)neko_throw_cached(env, g_neko_throw_oom); return; }
    if (strcmp(fallback_class, "java/lang/IllegalMonitorStateException") == 0) { (void)neko_throw_cached(env, g_neko_throw_imse); return; }
    if (strcmp(fallback_class, "java/lang/ArrayStoreException") == 0) { (void)neko_throw_cached(env, g_neko_throw_ase); return; }
    if (strcmp(fallback_class, "java/lang/NegativeArraySizeException") == 0) { (void)neko_throw_cached(env, g_neko_throw_nase); return; }
    if (strcmp(fallback_class, "java/lang/BootstrapMethodError") == 0) { (void)neko_throw_cached(env, g_neko_throw_bme); return; }
    (void)neko_throw_cached(env, g_neko_throw_le);
}

static void neko_raise_null_pointer_exception(void *thread) {
    JNIEnv *env = neko_current_env();
    (void)thread;
    (void)neko_throw_cached(env, g_neko_throw_npe);
}

static jobject neko_wave2_unsafe(JNIEnv *env) {
    const char *unsafe_name = g_neko_vm_layout.java_spec_version >= 9 ? "jdk/internal/misc/Unsafe" : "sun/misc/Unsafe";
    if (env == NULL) return NULL;
    if (g_neko_wave2_unsafe_singleton != NULL) return g_neko_wave2_unsafe_singleton;
    if (g_neko_wave2_unsafe_cls == NULL) {
        jclass local = neko_find_class(env, unsafe_name);
        if (local == NULL) return NULL;
        g_neko_wave2_unsafe_cls = (jclass)neko_new_global_ref(env, local);
        neko_delete_local_ref(env, local);
    }
    if (g_neko_wave2_unsafe_cls == NULL) return NULL;
    {
        jfieldID fid = neko_get_static_field_id(env, g_neko_wave2_unsafe_cls, "theUnsafe", g_neko_vm_layout.java_spec_version >= 9 ? "Ljdk/internal/misc/Unsafe;" : "Lsun/misc/Unsafe;");
        jobject local;
        if (fid == NULL) return NULL;
        local = neko_get_static_object_field(env, g_neko_wave2_unsafe_cls, fid);
        if (local == NULL) return NULL;
        g_neko_wave2_unsafe_singleton = neko_new_global_ref(env, local);
        neko_delete_local_ref(env, local);
    }
    return g_neko_wave2_unsafe_singleton;
}

static jobject neko_wave2_declared_field(JNIEnv *env, jclass owner, const char *field_name) {
    jvalue args[1];
    if (env == NULL || owner == NULL || field_name == NULL) return NULL;
    g_neko_wave2_class_cls = NEKO_ENSURE_CLASS(g_neko_wave2_class_cls, env, "java/lang/Class");
    g_neko_wave2_class_get_declared_field = NEKO_ENSURE_METHOD_ID(g_neko_wave2_class_get_declared_field, env, g_neko_wave2_class_cls, "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
    if (g_neko_wave2_class_get_declared_field == NULL) return NULL;
    args[0].l = neko_new_string_utf(env, field_name);
    return neko_call_object_method_a(env, owner, g_neko_wave2_class_get_declared_field, args);
}

static ptrdiff_t neko_wave2_reflected_field_offset(JNIEnv *env, jobject reflected_field, jboolean is_static) {
    jobject unsafe = neko_wave2_unsafe(env);
    jvalue args[1];
    if (unsafe == NULL || reflected_field == NULL) return -1;
    g_neko_wave2_unsafe_object_field_offset = NEKO_ENSURE_METHOD_ID(g_neko_wave2_unsafe_object_field_offset, env, g_neko_wave2_unsafe_cls, "objectFieldOffset", "(Ljava/lang/reflect/Field;)J");
    g_neko_wave2_unsafe_static_field_offset = NEKO_ENSURE_METHOD_ID(g_neko_wave2_unsafe_static_field_offset, env, g_neko_wave2_unsafe_cls, "staticFieldOffset", "(Ljava/lang/reflect/Field;)J");
    args[0].l = reflected_field;
    return (ptrdiff_t)neko_call_long_method_a(env, unsafe, is_static ? g_neko_wave2_unsafe_static_field_offset : g_neko_wave2_unsafe_object_field_offset, args);
}

static ptrdiff_t neko_wave2_object_field_offset_by_name(JNIEnv *env, const char *owner_internal, const char *field_name) {
    jclass owner = NULL;
    jobject reflected = NULL;
    ptrdiff_t offset = -1;
    if (env == NULL || owner_internal == NULL || field_name == NULL) return -1;
    owner = neko_find_class(env, owner_internal);
    if (owner == NULL) goto cleanup;
    reflected = neko_wave2_declared_field(env, owner, field_name);
    if (reflected == NULL) goto cleanup;
    offset = neko_wave2_reflected_field_offset(env, reflected, JNI_FALSE);
cleanup:
    if (reflected != NULL) neko_delete_local_ref(env, reflected);
    if (owner != NULL) neko_delete_local_ref(env, owner);
    return offset;
}

static ptrdiff_t neko_wave2_array_metric(JNIEnv *env, const char *descriptor, jboolean want_base) {
    jobject unsafe = neko_wave2_unsafe(env);
    jclass array_class = NULL;
    jvalue args[1];
    ptrdiff_t value = -1;
    if (env == NULL || descriptor == NULL || unsafe == NULL) return -1;
    g_neko_wave2_unsafe_array_base_offset = NEKO_ENSURE_METHOD_ID(g_neko_wave2_unsafe_array_base_offset, env, g_neko_wave2_unsafe_cls, "arrayBaseOffset", "(Ljava/lang/Class;)I");
    g_neko_wave2_unsafe_array_index_scale = NEKO_ENSURE_METHOD_ID(g_neko_wave2_unsafe_array_index_scale, env, g_neko_wave2_unsafe_cls, "arrayIndexScale", "(Ljava/lang/Class;)I");
    array_class = neko_class_for_descriptor(env, descriptor);
    if (array_class == NULL) goto cleanup;
    args[0].l = array_class;
    value = want_base ? (ptrdiff_t)neko_call_int_method_a(env, unsafe, g_neko_wave2_unsafe_array_base_offset, args) : (ptrdiff_t)neko_call_int_method_a(env, unsafe, g_neko_wave2_unsafe_array_index_scale, args);
cleanup:
    if (array_class != NULL) neko_delete_local_ref(env, array_class);
    return value;
}

static void neko_derive_wave2_layout_offsets(JNIEnv *env) {
    g_neko_vm_layout.instance_klass_fields_strategy = g_neko_vm_layout.off_instance_klass_fields >= 0 ? 'A' : 'C';
    if (g_neko_vm_layout.off_string_value >= 0) { g_neko_vm_layout.string_value_strategy = 'A'; } else { g_neko_vm_layout.off_string_value = neko_wave2_object_field_offset_by_name(env, "java/lang/String", "value"); g_neko_vm_layout.string_value_strategy = g_neko_vm_layout.off_string_value >= 0 ? 'B' : 'C'; }
    if (g_neko_vm_layout.java_spec_version < 9) { g_neko_vm_layout.off_string_coder = -1; g_neko_vm_layout.string_coder_strategy = 'N'; } else if (g_neko_vm_layout.off_string_coder >= 0) { g_neko_vm_layout.string_coder_strategy = 'A'; } else { g_neko_vm_layout.off_string_coder = neko_wave2_object_field_offset_by_name(env, "java/lang/String", "coder"); g_neko_vm_layout.string_coder_strategy = g_neko_vm_layout.off_string_coder >= 0 ? 'B' : 'C'; }
    g_neko_vm_layout.class_klass_strategy = g_neko_vm_layout.off_class_klass >= 0 ? 'A' : 'B';
    if (g_neko_vm_layout.off_array_base_byte >= 0) { g_neko_vm_layout.array_base_byte_strategy = 'A'; } else { g_neko_vm_layout.off_array_base_byte = neko_wave2_array_metric(env, "[B", JNI_TRUE); g_neko_vm_layout.array_base_byte_strategy = g_neko_vm_layout.off_array_base_byte >= 0 ? 'B' : 'C'; }
    if (g_neko_vm_layout.off_array_scale_byte >= 0) { g_neko_vm_layout.array_scale_byte_strategy = 'A'; } else { g_neko_vm_layout.off_array_scale_byte = neko_wave2_array_metric(env, "[B", JNI_FALSE); g_neko_vm_layout.array_scale_byte_strategy = g_neko_vm_layout.off_array_scale_byte >= 0 ? 'B' : 'C'; }
    if (g_neko_vm_layout.off_array_base_char >= 0) { g_neko_vm_layout.array_base_char_strategy = 'A'; } else { g_neko_vm_layout.off_array_base_char = neko_wave2_array_metric(env, "[C", JNI_TRUE); g_neko_vm_layout.array_base_char_strategy = g_neko_vm_layout.off_array_base_char >= 0 ? 'B' : 'C'; }
    if (g_neko_vm_layout.off_array_scale_char >= 0) { g_neko_vm_layout.array_scale_char_strategy = 'A'; } else { g_neko_vm_layout.off_array_scale_char = neko_wave2_array_metric(env, "[C", JNI_FALSE); g_neko_vm_layout.array_scale_char_strategy = g_neko_vm_layout.off_array_scale_char >= 0 ? 'B' : 'C'; }
    neko_log_offset_strategy("instance_klass_fields_offset", g_neko_vm_layout.off_instance_klass_fields, g_neko_vm_layout.instance_klass_fields_strategy);
    neko_log_offset_strategy("string_value_offset", g_neko_vm_layout.off_string_value, g_neko_vm_layout.string_value_strategy);
    neko_log_offset_strategy("string_coder_offset", g_neko_vm_layout.off_string_coder, g_neko_vm_layout.string_coder_strategy);
    neko_log_offset_strategy("class_klass_offset", g_neko_vm_layout.off_class_klass, g_neko_vm_layout.class_klass_strategy);
    neko_log_offset_strategy("array_base_byte", g_neko_vm_layout.off_array_base_byte, g_neko_vm_layout.array_base_byte_strategy);
    neko_log_offset_strategy("array_scale_byte", g_neko_vm_layout.off_array_scale_byte, g_neko_vm_layout.array_scale_byte_strategy);
    neko_log_offset_strategy("array_base_char", g_neko_vm_layout.off_array_base_char, g_neko_vm_layout.array_base_char_strategy);
    neko_log_offset_strategy("array_scale_char", g_neko_vm_layout.off_array_scale_char, g_neko_vm_layout.array_scale_char_strategy);
}

static inline jboolean neko_uses_compressed_oops(void) {
    return g_neko_vm_layout.narrow_oop_shift > 0 || g_neko_vm_layout.narrow_oop_base != 0u;
}

static inline jboolean neko_uses_compressed_klass_pointers(void) {
    return g_neko_vm_layout.narrow_klass_shift > 0 || g_neko_vm_layout.narrow_klass_base != 0u;
}

static inline void* neko_load_heap_oop_at(void *base, ptrdiff_t offset, jboolean is_volatile) {
    if (base == NULL || offset < 0) return NULL;
    if (neko_uses_compressed_oops()) {
        u4 narrow = is_volatile ? __atomic_load_n((u4*)((uint8_t*)base + offset), __ATOMIC_SEQ_CST) : *(u4*)((uint8_t*)base + offset);
        return neko_decode_heap_oop(narrow);
    }
    return is_volatile ? __atomic_load_n((void**)((uint8_t*)base + offset), __ATOMIC_SEQ_CST) : *(void**)((uint8_t*)base + offset);
}

typedef struct Klass Klass;
typedef void* oop;
static inline Klass* neko_site_owner_klass(const NekoManifestFieldSite *site) {
    return site == NULL ? NULL : (Klass*)__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);
}

static inline void *neko_site_static_base(const NekoManifestFieldSite *site) {
    Klass *owner_klass = site == NULL ? NULL : neko_site_owner_klass(site);
    return neko_resolve_mirror_oop_from_klass(&g_neko_vm_layout, owner_klass);
}

static jboolean neko_field_site_access_flags(const NekoManifestFieldSite *site, uint32_t *access_flags_out) {
    Klass *owner_klass = site == NULL ? NULL : neko_site_owner_klass(site);
    uint32_t name_len;
    uint32_t desc_len;
    void *cp;
    if (access_flags_out != NULL) *access_flags_out = 0u;
    if (site == NULL || owner_klass == NULL || site->field_name == NULL || site->field_desc == NULL || access_flags_out == NULL) return JNI_FALSE;
    name_len = (uint32_t)strlen(site->field_name);
    desc_len = (uint32_t)strlen(site->field_desc);
    if (g_neko_vm_layout.off_instance_klass_constants < 0) return JNI_FALSE;
    cp = *(void**)((uint8_t*)owner_klass + g_neko_vm_layout.off_instance_klass_constants);
    if (cp == NULL) return JNI_FALSE;
    if (g_neko_vm_layout.off_instance_klass_fieldinfo_stream >= 0) {
        void *fis = *(void**)((uint8_t*)owner_klass + g_neko_vm_layout.off_instance_klass_fieldinfo_stream);
        int fis_len = fis == NULL ? 0 : *(const int*)((const uint8_t*)fis + 0);
        int p = 0;
        const uint8_t *fis_data = fis == NULL ? NULL : (const uint8_t*)fis + sizeof(int);
        uint32_t num_java_fields = 0u;
        uint32_t ignored = 0u;
        if (fis_data == NULL || fis_len <= 0) return JNI_FALSE;
        if (!neko_read_u5(fis_data, fis_len, &p, &num_java_fields)) return JNI_FALSE;
        if (!neko_read_u5(fis_data, fis_len, &p, &ignored)) return JNI_FALSE;
        for (uint32_t i = 0; i < num_java_fields; i++) {
            uint32_t name_index;
            uint32_t signature_index;
            uint32_t offset;
            uint32_t access_flags;
            uint32_t field_flags;
            uint32_t unused;
            jboolean is_static;
            if (!neko_read_u5(fis_data, fis_len, &p, &name_index)) return JNI_FALSE;
            if (!neko_read_u5(fis_data, fis_len, &p, &signature_index)) return JNI_FALSE;
            if (!neko_read_u5(fis_data, fis_len, &p, &offset)) return JNI_FALSE;
            if (!neko_read_u5(fis_data, fis_len, &p, &access_flags)) return JNI_FALSE;
            if (!neko_read_u5(fis_data, fis_len, &p, &field_flags)) return JNI_FALSE;
            if ((field_flags & 1u) != 0u && !neko_read_u5(fis_data, fis_len, &p, &unused)) return JNI_FALSE;
            if ((field_flags & 4u) != 0u && !neko_read_u5(fis_data, fis_len, &p, &unused)) return JNI_FALSE;
            if ((field_flags & 16u) != 0u && !neko_read_u5(fis_data, fis_len, &p, &unused)) return JNI_FALSE;
            is_static = (access_flags & JVM_ACC_STATIC) != 0u ? JNI_TRUE : JNI_FALSE;
            if (is_static != (site->is_static ? JNI_TRUE : JNI_FALSE)) continue;
            if (!neko_cp_utf8_matches(cp, (uint16_t)name_index, site->field_name, name_len)) continue;
            if (!neko_cp_utf8_matches(cp, (uint16_t)signature_index, site->field_desc, desc_len)) continue;
            *access_flags_out = access_flags;
            (void)offset;
            return JNI_TRUE;
        }
        return JNI_FALSE;
    }
    if (g_neko_vm_layout.off_instance_klass_fields >= 0 && g_neko_vm_layout.off_instance_klass_java_fields_count >= 0) {
        void *fields = *(void**)((uint8_t*)owner_klass + g_neko_vm_layout.off_instance_klass_fields);
        uint32_t java_fields_count = (uint32_t)*(const uint16_t*)((const uint8_t*)owner_klass + g_neko_vm_layout.off_instance_klass_java_fields_count);
        int fields_len = fields == NULL ? 0 : *(const int*)((const uint8_t*)fields + 0);
        const uint16_t *fields_data = fields == NULL ? NULL : (const uint16_t*)((const uint8_t*)fields + sizeof(int));
        if (fields_data == NULL || fields_len < 0 || java_fields_count > (uint32_t)(fields_len / 6)) return JNI_FALSE;
        for (uint32_t i = 0; i < java_fields_count; i++) {
            const uint16_t *tuple = fields_data + (i * 6u);
            uint32_t access_flags = tuple[0];
            jboolean is_static = (access_flags & JVM_ACC_STATIC) != 0u ? JNI_TRUE : JNI_FALSE;
            if (is_static != (site->is_static ? JNI_TRUE : JNI_FALSE)) continue;
            if (!neko_cp_utf8_matches(cp, tuple[1], site->field_name, name_len)) continue;
            if (!neko_cp_utf8_matches(cp, tuple[2], site->field_desc, desc_len)) continue;
            *access_flags_out = access_flags;
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

static jboolean neko_resolve_field_site_with_klass(JNIEnv *env, void *thread, NekoManifestFieldSite *site, Klass *owner_klass) {
    int32_t resolved_offset;
    ptrdiff_t offset;
    uint32_t access_flags = 0u;
    if (env == NULL || site == NULL || owner_klass == NULL || site->field_name == NULL || site->field_desc == NULL) return JNI_FALSE;
    if (!neko_resolve_field_offset(owner_klass, site->field_name, (uint32_t)strlen(site->field_name), site->field_desc, (uint32_t)strlen(site->field_desc), site->is_static ? true : false, &resolved_offset)) {
        __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE);
        neko_wave2_capture_pending(env, thread, "java/lang/NoSuchFieldError", site->field_name);
        return JNI_FALSE;
    }
    offset = (ptrdiff_t)resolved_offset;
    if (site->is_static) {
        site->field_offset_cookie = offset;
        neko_native_trace_log(2, "sfb %s.%s:%s klass=%p mir=%p off=%td", site->owner_internal, site->field_name, site->field_desc, owner_klass, neko_site_static_base(site), offset);
    }
    site->is_volatile = (neko_field_site_access_flags(site, &access_flags) && ((access_flags & 0x0040u) != 0u)) ? 1u : 0u;
    __atomic_store_n(&site->resolved_offset, offset, __ATOMIC_RELEASE);
    neko_native_debug_log("fsr %s.%s:%s off=%td v=%u", site->owner_internal, site->field_name, site->field_desc, offset, (unsigned)site->is_volatile);
    return JNI_TRUE;
}

static jboolean neko_ensure_field_site_resolved(void *thread, NekoManifestFieldSite *site) {
    ptrdiff_t current = site == NULL ? NEKO_FIELD_SITE_FAILED : __atomic_load_n(&site->resolved_offset, __ATOMIC_ACQUIRE);
    JNIEnv *env;
    Klass *owner_klass;
    size_t owner_len;
    if (site == NULL) return JNI_FALSE;
    neko_maybe_rescan_cld_liveness();
    if (current >= 0) return JNI_TRUE;
    if (current == NEKO_FIELD_SITE_FAILED) {
        (void)thread;
        (void)neko_throw_cached(neko_current_env(), g_neko_throw_le);
        return JNI_FALSE;
    }
    env = neko_current_env();
    if (env == NULL) return JNI_FALSE;
    owner_klass = neko_site_owner_klass(site);
    if (owner_klass == NULL) {
        if (site->owner_internal == NULL) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, "java/lang/NoClassDefFoundError", "<null>"); return JNI_FALSE; }
        owner_len = strlen(site->owner_internal);
        if (owner_len >= 65536u) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, "java/lang/NoClassDefFoundError", site->owner_internal); return JNI_FALSE; }
        owner_klass = neko_find_klass_by_name_in_cld_graph(site->owner_internal, (uint16_t)owner_len);
        if (owner_klass == NULL) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, "java/lang/NoClassDefFoundError", site->owner_internal); return JNI_FALSE; }
        __atomic_store_n(&site->cached_klass, owner_klass, __ATOMIC_RELEASE);
    }
    return neko_resolve_field_site_with_klass(env, thread, site, owner_klass);
}

static void neko_resolve_prepared_class_field_sites(JNIEnv *env, jclass klass, const char *owner_internal, void *owner_klass) {
    void *thread = neko_get_current_thread();
    jboolean cached_klass_logged = JNI_FALSE;
    if (env == NULL || klass == NULL || owner_internal == NULL) return;
    (void)klass;
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];
        for (uint32_t site_index = 0; site_index < method->field_site_count; site_index++) {
            NekoManifestFieldSite *site = &method->field_sites[site_index];
            if (site->owner_internal == NULL || strcmp(site->owner_internal, owner_internal) != 0) continue;
            if (owner_klass != NULL && __atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE) == NULL) {
                __atomic_store_n(&site->cached_klass, owner_klass, __ATOMIC_RELEASE);
                if (!cached_klass_logged) {
                    cached_klass_logged = JNI_TRUE;
                    neko_native_debug_log("ck %s -> %p", owner_internal, owner_klass);
                }
            }
            if (__atomic_load_n(&site->resolved_offset, __ATOMIC_ACQUIRE) == NEKO_FIELD_SITE_UNRESOLVED) (void)neko_resolve_field_site_with_klass(env, thread, site, (Klass*)owner_klass);
        }
    }
}

static uint32_t neko_count_cached_static_field_bases(void) {
    uint32_t count = 0u;
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];
        for (uint32_t site_index = 0; site_index < method->field_site_count; site_index++) {
            NekoManifestFieldSite *site = &method->field_sites[site_index];
            if (site->is_static && neko_site_static_base(site) != NULL) count++;
        }
    }
    return count;
}

__attribute__((visibility("default"))) void* neko_field_site_static_base(void *thread, NekoManifestFieldSite *site) {
    oop mirror;
    JNIEnv *env;
    if (!neko_ensure_field_site_resolved(thread, site)) return NULL;
    env = neko_current_env();
    mirror = neko_site_static_base(site);
    if (mirror == NULL) {
        if (env != NULL && neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, "java/lang/IllegalStateException", "failed to resolve static field mirror");
        return NULL;
    }
    neko_native_debug_log("sfm %s.%s:%s klass=%p mir=%p off=%td", site->owner_internal, site->field_name, site->field_desc, neko_site_owner_klass(site), mirror, site->field_offset_cookie);
    neko_native_trace_log(2, "sfr %s.%s:%s klass=%p mir=%p off=%td addr=%p", site->owner_internal, site->field_name, site->field_desc, neko_site_owner_klass(site), mirror, site->field_offset_cookie, (void*)((char*)mirror + site->field_offset_cookie));
    return mirror;
}

static inline void *neko_static_mirror(const NekoManifestFieldSite *site) {
    return neko_site_static_base(site);
}

static inline int32_t *neko_static_i32_addr(const NekoManifestFieldSite *site) {
    void *mirror = neko_static_mirror(site);
    return (int32_t *)((char *)mirror + (ptrdiff_t)site->field_offset_cookie);
}

static inline int32_t neko_getstatic_i32(const NekoManifestFieldSite *site) {
    return *neko_static_i32_addr(site);
}

static inline void neko_putstatic_i32(const NekoManifestFieldSite *site, int32_t v) {
    *neko_static_i32_addr(site) = v;
}

static inline int64_t *neko_static_i64_addr(const NekoManifestFieldSite *site) {
    void *mirror = neko_static_mirror(site);
    return (int64_t *)((char *)mirror + (ptrdiff_t)site->field_offset_cookie);
}

static inline int64_t neko_getstatic_i64(const NekoManifestFieldSite *site) {
    return *neko_static_i64_addr(site);
}

static inline void neko_putstatic_i64(const NekoManifestFieldSite *site, int64_t v) {
    *neko_static_i64_addr(site) = v;
}

static inline float *neko_static_f32_addr(const NekoManifestFieldSite *site) {
    void *mirror = neko_static_mirror(site);
    return (float *)((char *)mirror + (ptrdiff_t)site->field_offset_cookie);
}

static inline float neko_getstatic_f32(const NekoManifestFieldSite *site) {
    return *neko_static_f32_addr(site);
}

static inline void neko_putstatic_f32(const NekoManifestFieldSite *site, float v) {
    *neko_static_f32_addr(site) = v;
}

static inline double *neko_static_f64_addr(const NekoManifestFieldSite *site) {
    void *mirror = neko_static_mirror(site);
    return (double *)((char *)mirror + (ptrdiff_t)site->field_offset_cookie);
}

static inline double neko_getstatic_f64(const NekoManifestFieldSite *site) {
    return *neko_static_f64_addr(site);
}

static inline void neko_putstatic_f64(const NekoManifestFieldSite *site, double v) {
    *neko_static_f64_addr(site) = v;
}

static inline void* neko_field_read_oop(void *base, const NekoManifestFieldSite *site) {
    return neko_load_heap_oop_at(base, site == NULL ? -1 : site->resolved_offset, site != NULL && site->is_volatile ? JNI_TRUE : JNI_FALSE);
}

static inline jboolean neko_field_read_Z(void *base, const NekoManifestFieldSite *site) {
    jboolean *slot = (jboolean*)((uint8_t*)base + site->resolved_offset);
    return site->is_volatile ? __atomic_load_n(slot, __ATOMIC_SEQ_CST) : *slot;
}

static inline void neko_field_write_Z(void *base, const NekoManifestFieldSite *site, jboolean value) {
    jboolean *slot = (jboolean*)((uint8_t*)base + site->resolved_offset);
    if (site->is_volatile) { __atomic_store_n(slot, value, __ATOMIC_SEQ_CST); } else { *slot = value; }
}

static inline jbyte neko_field_read_B(void *base, const NekoManifestFieldSite *site) {
    jbyte *slot = (jbyte*)((uint8_t*)base + site->resolved_offset);
    return site->is_volatile ? __atomic_load_n(slot, __ATOMIC_SEQ_CST) : *slot;
}

static inline void neko_field_write_B(void *base, const NekoManifestFieldSite *site, jbyte value) {
    jbyte *slot = (jbyte*)((uint8_t*)base + site->resolved_offset);
    if (site->is_volatile) { __atomic_store_n(slot, value, __ATOMIC_SEQ_CST); } else { *slot = value; }
}

static inline jchar neko_field_read_C(void *base, const NekoManifestFieldSite *site) {
    jchar *slot = (jchar*)((uint8_t*)base + site->resolved_offset);
    return site->is_volatile ? __atomic_load_n(slot, __ATOMIC_SEQ_CST) : *slot;
}

static inline void neko_field_write_C(void *base, const NekoManifestFieldSite *site, jchar value) {
    jchar *slot = (jchar*)((uint8_t*)base + site->resolved_offset);
    if (site->is_volatile) { __atomic_store_n(slot, value, __ATOMIC_SEQ_CST); } else { *slot = value; }
}

static inline jshort neko_field_read_S(void *base, const NekoManifestFieldSite *site) {
    jshort *slot = (jshort*)((uint8_t*)base + site->resolved_offset);
    return site->is_volatile ? __atomic_load_n(slot, __ATOMIC_SEQ_CST) : *slot;
}

static inline void neko_field_write_S(void *base, const NekoManifestFieldSite *site, jshort value) {
    jshort *slot = (jshort*)((uint8_t*)base + site->resolved_offset);
    if (site->is_volatile) { __atomic_store_n(slot, value, __ATOMIC_SEQ_CST); } else { *slot = value; }
}

static inline jint neko_field_read_I(void *base, const NekoManifestFieldSite *site) {
    jint *slot = (jint*)((uint8_t*)base + site->resolved_offset);
    return site->is_volatile ? __atomic_load_n(slot, __ATOMIC_SEQ_CST) : *slot;
}

static inline void neko_field_write_I(void *base, const NekoManifestFieldSite *site, jint value) {
    jint *slot = (jint*)((uint8_t*)base + site->resolved_offset);
    if (site->is_volatile) { __atomic_store_n(slot, value, __ATOMIC_SEQ_CST); } else { *slot = value; }
}

static inline jlong neko_field_read_J(void *base, const NekoManifestFieldSite *site) {
    jlong *slot = (jlong*)((uint8_t*)base + site->resolved_offset);
    return site->is_volatile ? __atomic_load_n(slot, __ATOMIC_SEQ_CST) : *slot;
}

static inline void neko_field_write_J(void *base, const NekoManifestFieldSite *site, jlong value) {
    jlong *slot = (jlong*)((uint8_t*)base + site->resolved_offset);
    if (site->is_volatile) { __atomic_store_n(slot, value, __ATOMIC_SEQ_CST); } else { *slot = value; }
}

static inline jfloat neko_field_read_F(void *base, const NekoManifestFieldSite *site) {
    jfloat *slot = (jfloat*)((uint8_t*)base + site->resolved_offset);
    if (site->is_volatile) { uint32_t bits = __atomic_load_n((uint32_t*)slot, __ATOMIC_SEQ_CST); jfloat value; __builtin_memcpy(&value, &bits, sizeof(bits)); return value; }
    return *slot;
}

static inline void neko_field_write_F(void *base, const NekoManifestFieldSite *site, jfloat value) {
    jfloat *slot = (jfloat*)((uint8_t*)base + site->resolved_offset);
    if (site->is_volatile) { uint32_t bits; __builtin_memcpy(&bits, &value, sizeof(bits)); __atomic_store_n((uint32_t*)slot, bits, __ATOMIC_SEQ_CST); } else { *slot = value; }
}

static inline jdouble neko_field_read_D(void *base, const NekoManifestFieldSite *site) {
    jdouble *slot = (jdouble*)((uint8_t*)base + site->resolved_offset);
    if (site->is_volatile) { uint64_t bits = __atomic_load_n((uint64_t*)slot, __ATOMIC_SEQ_CST); jdouble value; __builtin_memcpy(&value, &bits, sizeof(bits)); return value; }
    return *slot;
}

static inline void neko_field_write_D(void *base, const NekoManifestFieldSite *site, jdouble value) {
    jdouble *slot = (jdouble*)((uint8_t*)base + site->resolved_offset);
    if (site->is_volatile) { uint64_t bits; __builtin_memcpy(&bits, &value, sizeof(bits)); __atomic_store_n((uint64_t*)slot, bits, __ATOMIC_SEQ_CST); } else { *slot = value; }
}

#define NEKO_MAX_INLINE_UTF16 1024
#define NEKO_MAX_INLINE_PAYLOAD 2048

static jboolean neko_mutf8_decode_unit(const uint8_t *bytes, size_t len, size_t *cursor, uint16_t *out) {
    uint8_t b0;
    if (bytes == NULL || cursor == NULL || out == NULL || *cursor >= len) return JNI_FALSE;
    b0 = bytes[(*cursor)++];
    if ((b0 & 0x80u) == 0u) {
        if (b0 == 0u) return JNI_FALSE;
        *out = (uint16_t)b0;
        return JNI_TRUE;
    }
    if ((b0 & 0xE0u) == 0xC0u) {
        uint8_t b1;
        uint16_t value;
        if (*cursor >= len) return JNI_FALSE;
        b1 = bytes[(*cursor)++];
        if ((b1 & 0xC0u) != 0x80u) return JNI_FALSE;
        value = (uint16_t)(((uint16_t)(b0 & 0x1Fu) << 6) | (uint16_t)(b1 & 0x3Fu));
        if (value == 0u && (b0 != 0xC0u || b1 != 0x80u)) return JNI_FALSE;
        *out = value;
        return JNI_TRUE;
    }
    if ((b0 & 0xF0u) == 0xE0u) {
        uint8_t b1;
        uint8_t b2;
        if ((*cursor + 1u) >= len) return JNI_FALSE;
        b1 = bytes[(*cursor)++];
        b2 = bytes[(*cursor)++];
        if ((b1 & 0xC0u) != 0x80u || (b2 & 0xC0u) != 0x80u) return JNI_FALSE;
        *out = (uint16_t)((((uint16_t)(b0 & 0x0Fu)) << 12)
            | (((uint16_t)(b1 & 0x3Fu)) << 6)
            | (uint16_t)(b2 & 0x3Fu));
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean neko_decode_mutf8_to_utf16(const uint8_t *bytes, size_t len, uint16_t **utf16_out, int32_t *utf16_len_out, int32_t *heap_alloc_out) {
    size_t cursor = 0u;
    size_t count = 0u;
    uint16_t *utf16;
    int32_t heap_alloc = 0;
    if (utf16_out == NULL || *utf16_out == NULL || utf16_len_out == NULL || heap_alloc_out == NULL) return JNI_FALSE;
    while (cursor < len) {
        uint16_t code_unit;
        if (!neko_mutf8_decode_unit(bytes, len, &cursor, &code_unit)) return JNI_FALSE;
        count++;
        if (count > 0x7fffffffU) return JNI_FALSE;
    }
    utf16 = *utf16_out;
    if (count > NEKO_MAX_INLINE_UTF16) {
        utf16 = (uint16_t*)malloc((count == 0u ? 1u : count) * sizeof(uint16_t));
        if (utf16 == NULL) return JNI_FALSE;
        heap_alloc = 1;
    }
    cursor = 0u;
    count = 0u;
    while (cursor < len) {
        uint16_t code_unit;
        if (!neko_mutf8_decode_unit(bytes, len, &cursor, &code_unit)) {
            if (heap_alloc) free(utf16);
            return JNI_FALSE;
        }
        utf16[count++] = code_unit;
    }
    *utf16_out = utf16;
    *utf16_len_out = (int32_t)count;
    *heap_alloc_out = heap_alloc;
    return JNI_TRUE;
}

static uint32_t neko_string_intern_hash(uint32_t coder, uint32_t char_length, const uint8_t *payload, uint32_t payload_length) {
    uint32_t hash = 2166136261u;
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)(coder & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((coder >> 8) & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((coder >> 16) & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((coder >> 24) & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)(char_length & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((char_length >> 8) & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((char_length >> 16) & 0xFFu));
    hash = neko_fnv1a32_update_byte(hash, (uint8_t)((char_length >> 24) & 0xFFu));
    for (uint32_t i = 0; i < payload_length; i++) {
        hash = neko_fnv1a32_update_byte(hash, payload[i]);
    }
    return hash;
}

static void* neko_create_ldc_string_oop(NekoManifestLdcSite* site, uint32_t *coder_out, uint32_t *char_length_out, uint8_t **key_bytes_out, uint32_t *key_payload_bytes_out) {
    uint16_t utf16_buf[NEKO_MAX_INLINE_UTF16];
    uint16_t* utf16 = utf16_buf;
    int32_t utf16_len = 0;
    int32_t heap_alloc = 0;
    int32_t is_jdk8;
    int32_t coder;
    void* array_klass;
    int32_t array_len;
    void* inner_array;
    uint32_t lh_arr;
    uint32_t header_bytes;
    uint8_t* array_base;
    uint32_t lh_str;
    size_t string_size;
    void* string_oop;
    uint32_t key_payload_bytes;
    uint8_t key_stackbuf[NEKO_MAX_INLINE_PAYLOAD];
    uint8_t* key_bytes = key_stackbuf;
    uint8_t* key_heap = NULL;
    if (coder_out != NULL) *coder_out = 0u;
    if (char_length_out != NULL) *char_length_out = 0u;
    if (key_bytes_out != NULL) *key_bytes_out = NULL;
    if (key_payload_bytes_out != NULL) *key_payload_bytes_out = 0u;
    if (site == NULL) return NULL;
    if (g_neko_vm_layout.klass_java_lang_String == NULL) return NULL;
    if (g_neko_vm_layout.off_string_value < 0) return NULL;
    if (!neko_decode_mutf8_to_utf16(site->raw_constant_utf8, site->raw_constant_utf8_len, &utf16, &utf16_len, &heap_alloc)) {
        return NULL;
    }
    is_jdk8 = (g_neko_vm_layout.off_string_coder < 0);
    if (is_jdk8) {
        coder = 1;
    } else {
        coder = 0;
        for (int32_t i = 0; i < utf16_len; i++) {
            if (utf16[i] > 0x00FFu) {
                coder = 1;
                break;
            }
        }
    }
    array_klass = is_jdk8
        ? g_neko_vm_layout.klass_array_char
        : g_neko_vm_layout.klass_array_byte;
    if (array_klass == NULL) {
        if (heap_alloc) free(utf16);
        return NULL;
    }
    array_len = is_jdk8
        ? utf16_len
        : (coder == 0 ? utf16_len : utf16_len * 2);
    inner_array = neko_rt_try_alloc_array_fast_nosafepoint(array_klass, array_len);
    if (inner_array == NULL) {
        if (heap_alloc) free(utf16);
        return NULL;
    }
    lh_arr = *(uint32_t*)((uint8_t*)array_klass + g_neko_vm_layout.off_klass_layout_helper);
    header_bytes = neko_lh_header_size(lh_arr);
    array_base = (uint8_t*)inner_array + header_bytes;
    if (is_jdk8) {
        for (int32_t i = 0; i < utf16_len; i++) {
            *(uint16_t*)(array_base + i * 2) = utf16[i];
        }
    } else if (coder == 0) {
        for (int32_t i = 0; i < utf16_len; i++) {
            array_base[i] = (uint8_t)utf16[i];
        }
    } else {
        for (int32_t i = 0; i < utf16_len; i++) {
            array_base[2 * i] = (uint8_t)(utf16[i] >> 8);
            array_base[2 * i + 1] = (uint8_t)(utf16[i] & 0xFFu);
        }
    }
    lh_str = *(uint32_t*)((uint8_t*)g_neko_vm_layout.klass_java_lang_String + g_neko_vm_layout.off_klass_layout_helper);
    string_size = neko_lh_instance_size(lh_str);
    string_oop = neko_rt_try_alloc_instance_fast_nosafepoint((Klass*)g_neko_vm_layout.klass_java_lang_String, string_size);
    if (string_oop == NULL) {
        if (heap_alloc) free(utf16);
        return NULL;
    }
    neko_store_heap_oop_at_unpublished(string_oop, g_neko_vm_layout.off_string_value, inner_array);
    if (!is_jdk8) {
        *(uint8_t*)((uint8_t*)string_oop + g_neko_vm_layout.off_string_coder) = (uint8_t)coder;
    }
    if (g_neko_vm_layout.off_string_hash >= 0) {
        *(int32_t*)((uint8_t*)string_oop + g_neko_vm_layout.off_string_hash) = 0;
    }
    key_payload_bytes = is_jdk8
        ? (uint32_t)(utf16_len * 2)
        : (coder == 0 ? (uint32_t)utf16_len : (uint32_t)utf16_len * 2u);
    if ((size_t)key_payload_bytes > sizeof(key_stackbuf)) {
        key_heap = (uint8_t*)malloc(key_payload_bytes);
        if (key_heap == NULL) {
            if (heap_alloc) free(utf16);
            return NULL;
        }
        key_bytes = key_heap;
    }
    if (is_jdk8) {
        for (int32_t i = 0; i < utf16_len; i++) {
            key_bytes[2 * i] = (uint8_t)(utf16[i] >> 8);
            key_bytes[2 * i + 1] = (uint8_t)(utf16[i] & 0xFFu);
        }
    } else if (key_payload_bytes != 0u) {
        memcpy(key_bytes, array_base, key_payload_bytes);
    }
    if (coder_out != NULL) *coder_out = (uint32_t)coder;
    if (char_length_out != NULL) *char_length_out = (uint32_t)utf16_len;
    if (key_payload_bytes_out != NULL) *key_payload_bytes_out = key_payload_bytes;
    if (key_bytes_out != NULL) {
        if (key_payload_bytes == 0u) {
            *key_bytes_out = NULL;
        } else {
            uint8_t *stored = (uint8_t*)malloc(key_payload_bytes);
            if (stored == NULL) {
                if (key_heap) free(key_heap);
                if (heap_alloc) free(utf16);
                return NULL;
            }
            memcpy(stored, key_bytes, key_payload_bytes);
            *key_bytes_out = stored;
        }
    }
    if (key_heap) free(key_heap);
    if (heap_alloc) free(utf16);
    return string_oop;
}

static void neko_resolve_ldc_string(NekoManifestLdcSite* site) {
    uint32_t coder = 0u;
    uint32_t char_length = 0u;
    uint8_t *key_bytes = NULL;
    uint32_t key_payload_bytes = 0u;
    uint32_t h;
    uint32_t bucket_idx;
    NekoStringInternEntry* existing;
    uint32_t slot;
    NekoStringInternEntry* entry;
    uint8_t* stored_payload;
    size_t stored_payload_size;
    void *string_oop;
    if (site == NULL) return;
    if (site->resolved_cache_handle != NULL) {
        return;
    }
    string_oop = neko_create_ldc_string_oop(site, &coder, &char_length, &key_bytes, &key_payload_bytes);
    if (string_oop == NULL) {
        return;
    }
    h = neko_string_intern_hash((uint32_t)coder, char_length, key_bytes, key_payload_bytes);
    bucket_idx = h % NEKO_STRING_INTERN_BUCKET_COUNT;
    existing = g_neko_string_intern_buckets[bucket_idx];
    while (existing != NULL) {
        if (existing->coder == (uint32_t)coder
            && existing->char_length == char_length
            && existing->payload_length == key_payload_bytes
            && (key_payload_bytes == 0u || memcmp(existing->payload, key_bytes, key_payload_bytes) == 0)) {
            if (key_bytes != NULL) free(key_bytes);
            site->resolved_cache_handle = existing;
            return;
        }
        existing = existing->next;
    }
    if (g_neko_string_intern_filled >= NEKO_STRING_INTERN_SLOT_COUNT) {
        if (key_bytes != NULL) free(key_bytes);
        return;
    }
    stored_payload_size = key_payload_bytes == 0u ? 1u : (size_t)key_payload_bytes;
    stored_payload = (uint8_t*)malloc(stored_payload_size);
    if (stored_payload == NULL) {
        if (key_bytes != NULL) free(key_bytes);
        return;
    }
    if (key_payload_bytes != 0u) {
        memcpy(stored_payload, key_bytes, key_payload_bytes);
    }
    slot = g_neko_string_intern_filled;
    entry = &g_neko_string_intern_entries[slot];
    entry->coder = (uint32_t)coder;
    entry->char_length = char_length;
    entry->payload_length = key_payload_bytes;
    entry->slot_index = slot;
    entry->payload = stored_payload;
    entry->root_cell = NULL;
    entry->next = g_neko_string_intern_buckets[bucket_idx];
    g_neko_string_intern_buckets[bucket_idx] = entry;
    g_neko_string_intern_filled = slot + 1u;
    if (entry->root_cell != NULL) {
        neko_store_oop_to_cell(entry->root_cell, string_oop);
    }
    site->resolved_cache_handle = entry;
    if (key_bytes != NULL) free(key_bytes);
}

static inline Klass* neko_ldc_site_owner_klass(const NekoManifestLdcSite *site) {
    return site == NULL ? NULL : (Klass*)__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);
}

static jboolean neko_ldc_site_signature_equals(const NekoManifestLdcSite *site, const char *signature) {
    size_t signature_len;
    if (site == NULL || signature == NULL || site->kind != NEKO_LDC_KIND_CLASS || site->raw_constant_utf8 == NULL) return JNI_FALSE;
    signature_len = strlen(signature);
    if (signature_len != site->raw_constant_utf8_len) return JNI_FALSE;
    return memcmp(site->raw_constant_utf8, signature, signature_len) == 0 ? JNI_TRUE : JNI_FALSE;
}

static jboolean neko_ldc_site_matches_loaded_class(JNIEnv *env, NekoManifestLdcSite *site, jclass candidate, const char *signature) {
    (void)env;
    (void)candidate;
    return neko_ldc_site_signature_equals(site, signature);
}

static void* neko_class_klass_pointer(jclass klass_obj) {
    if (klass_obj == NULL || g_neko_vm_layout.off_class_klass < 0) return NULL;
    return *(void**)((uint8_t*)neko_handle_oop((jobject)klass_obj) + g_neko_vm_layout.off_class_klass);
}

static char* neko_ldc_site_binary_name(const NekoManifestLdcSite *site) {
    if (site == NULL || site->raw_constant_utf8 == NULL) return NULL;
    if (site->raw_constant_utf8_len == 0u) return neko_wave2_copy_bytes(site->raw_constant_utf8, 0u);
    if (site->raw_constant_utf8[0] == '[') return neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);
    if (site->raw_constant_utf8_len >= 2u && site->raw_constant_utf8[0] == 'L' && site->raw_constant_utf8[site->raw_constant_utf8_len - 1u] == ';') {
        char *binary = (char*)malloc(site->raw_constant_utf8_len - 1u);
        if (binary == NULL) return NULL;
        memcpy(binary, site->raw_constant_utf8 + 1, site->raw_constant_utf8_len - 2u);
        binary[site->raw_constant_utf8_len - 2u] = '\0';
        return binary;
    }
    return neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);
}

static jboolean neko_ensure_ldc_class_site_resolved(void *thread, NekoManifestLdcSite *site) {
    void *cached_klass;
    JNIEnv *env;
    char *binary_name = NULL;
    jclass klass_obj = NULL;
    void *resolved_klass = NULL;
    if (site == NULL || site->kind != NEKO_LDC_KIND_CLASS) return JNI_FALSE;
    cached_klass = __atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);
    if (cached_klass != NULL) return JNI_TRUE;
    env = neko_current_env();
    if (env == NULL) return JNI_FALSE;
    binary_name = neko_ldc_site_binary_name(site);
    if (binary_name == NULL) return JNI_FALSE;
    resolved_klass = neko_find_klass_by_name_in_cld_graph(binary_name, (uint16_t)strlen(binary_name));
    if (resolved_klass == NULL) klass_obj = neko_load_class_noinit(env, binary_name);
    free(binary_name);
    if (klass_obj != NULL) resolved_klass = neko_class_klass_pointer(klass_obj);
    if (resolved_klass != NULL) { __atomic_store_n(&site->cached_klass, resolved_klass, __ATOMIC_RELEASE); if (klass_obj != NULL) neko_delete_local_ref(env, klass_obj); return JNI_TRUE; }
    if (klass_obj != NULL) neko_delete_local_ref(env, klass_obj);
    {
        char *message = neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);
        if (message != NULL) free(message);
        (void)neko_throw_cached(env, g_neko_throw_le);
    }
    return JNI_FALSE;
}

__attribute__((visibility("default"))) void* neko_ldc_class_site_oop(void *thread, NekoManifestLdcSite *site) {
    neko_maybe_rescan_cld_liveness();
    if (!neko_ensure_ldc_class_site_resolved(thread, site)) return NULL;
    return neko_rt_mirror_from_klass_nosafepoint(neko_ldc_site_owner_klass(site));
}

static jobject neko_ldc_cache_global(JNIEnv *env, NekoManifestLdcSite *site, jobject local) {
    jobject global_ref;
    void *cached;
    if (env == NULL || site == NULL || local == NULL) return NULL;
    cached = __atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE);
    if (cached != NULL) return (jobject)cached;
    global_ref = neko_new_global_ref(env, local);
    if (global_ref == NULL) { (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }
    cached = NULL;
    if (__atomic_compare_exchange_n(&site->resolved_cache_handle, &cached, global_ref, JNI_FALSE, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return global_ref;
    neko_delete_global_ref(env, global_ref);
    return (jobject)cached;
}

static void* neko_ldc_method_type_site_oop(JNIEnv *env, NekoManifestLdcSite *site) {
    char *descriptor;
    jobject local;
    if (site == NULL || site->kind != NEKO_LDC_KIND_METHOD_TYPE) { (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }
    if (__atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE) != NULL) return site->resolved_cache_handle;
    descriptor = neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);
    if (descriptor == NULL) { (void)neko_throw_cached(env, g_neko_throw_oom); return NULL; }
    local = neko_method_type_from_descriptor(env, descriptor);
    free(descriptor);
    if (local == NULL) { if (neko_pending_exception(neko_get_current_thread()) == NULL) (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }
    return neko_ldc_cache_global(env, site, local);
}

static void* neko_ldc_method_handle_site_oop(JNIEnv *env, NekoManifestLdcSite *site) {
    char *parts;
    char *cursor;
    char *next;
    char *tag_text;
    char *owner;
    char *name;
    char *desc;
    char *interface_text;
    jobject local;
    if (site == NULL || site->kind != NEKO_LDC_KIND_METHOD_HANDLE) { (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }
    if (__atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE) != NULL) return site->resolved_cache_handle;
    parts = neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);
    if (parts == NULL) { (void)neko_throw_cached(env, g_neko_throw_oom); return NULL; }
    cursor = parts;
    tag_text = cursor; next = strchr(cursor, '\n'); if (next == NULL) { free(parts); (void)neko_throw_cached(env, g_neko_throw_le); return NULL; } *next = '\0'; cursor = next + 1;
    owner = cursor; next = strchr(cursor, '\n'); if (next == NULL) { free(parts); (void)neko_throw_cached(env, g_neko_throw_le); return NULL; } *next = '\0'; cursor = next + 1;
    name = cursor; next = strchr(cursor, '\n'); if (next == NULL) { free(parts); (void)neko_throw_cached(env, g_neko_throw_le); return NULL; } *next = '\0'; cursor = next + 1;
    desc = cursor; next = strchr(cursor, '\n'); if (next == NULL) { free(parts); (void)neko_throw_cached(env, g_neko_throw_le); return NULL; } *next = '\0'; cursor = next + 1;
    interface_text = cursor;
    if (tag_text == NULL || owner == NULL || name == NULL || desc == NULL || interface_text == NULL) { free(parts); (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }
    local = neko_method_handle_from_parts(env, (jint)strtol(tag_text, NULL, 10), owner, name, desc, strcmp(interface_text, "1") == 0 ? JNI_TRUE : JNI_FALSE);
    free(parts);
    if (local == NULL) { if (neko_pending_exception(neko_get_current_thread()) == NULL) (void)neko_throw_cached(env, g_neko_throw_le); return NULL; }
    return neko_ldc_cache_global(env, site, local);
}

static inline void* neko_ldc_string_site_oop(JNIEnv *env, NekoManifestLdcSite *site) {
    NekoStringInternEntry* entry;
    void* value;
    (void)env;
    if (site == NULL) return NULL;
    if (g_neko_string_root_backend != NEKO_STRING_ROOT_BACKEND_BOOT_CLD) {
        value = neko_create_ldc_string_oop(site, NULL, NULL, NULL, NULL);
#ifdef NEKO_DEBUG_ENABLED
        if (neko_debug_enabled()) neko_native_debug_log("neko_ldc_string_site_oop=idx=%u ptr=%p", site->site_id, value);
#endif
        return value;
    }
    entry = (NekoStringInternEntry*)site->resolved_cache_handle;
    if (entry == NULL) return NULL;
    if (entry->root_cell == NULL) return NULL;
    value = neko_load_oop_from_cell(entry->root_cell);
    if (value == NULL && entry->root_cell != NULL) {
        void *new_str = neko_create_ldc_string_oop(site, entry, NULL, NULL, NULL);
        if (new_str != NULL) {
            if (__sync_bool_compare_and_swap((void**)entry->root_cell, NULL, new_str)) {
                value = new_str;
            } else {
                value = neko_load_oop_from_cell(entry->root_cell);
            }
        }
    }
#ifdef NEKO_DEBUG_ENABLED
    if (neko_debug_enabled()) neko_native_debug_log("neko_ldc_string_site_oop=idx=%u ptr=%p", site->site_id, value);
#endif
    return value;
}

static void* neko_resolve_ldc_site_oop(void *thread, NekoManifestLdcSite *site) {
    if (site == NULL) return NULL;
    if (site->kind == NEKO_LDC_KIND_CLASS) {
        return neko_ldc_class_site_oop(thread, site);
    }
    return neko_ldc_string_site_oop(neko_current_env(), site);
}

static jboolean neko_prewarm_ldc_sites(JNIEnv *env) {
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];
        for (uint32_t site_index = 0; site_index < method->ldc_site_count; site_index++) {
            NekoManifestLdcSite *site = &method->ldc_sites[site_index];
            void *cached = __atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE);
            void *created;
            if (site->kind == NEKO_LDC_KIND_CLASS) {
                const char *signature = neko_wave2_copy_bytes(site->raw_constant_utf8, site->raw_constant_utf8_len);
                if (__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE) == NULL) {
                    if (!neko_ensure_ldc_class_site_resolved(neko_get_current_thread(), site)) {
                        if (neko_pending_exception(neko_get_current_thread()) != NULL) { neko_clear_pending_exception(neko_get_current_thread()); }
                    }
                }
                if (__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE) == NULL) NEKO_TRACE(0, "[nk] ldc-cls prewarm miss idx=%u sig=\"%s\"", site->site_id, signature == NULL ? "" : signature);
                if (signature != NULL) free((void*)signature);
                continue;
            }
            if (site->kind == NEKO_LDC_KIND_STRING) continue;
            if (cached != NULL) continue;
        }
    }
    return JNI_TRUE;
}

static void neko_log_wave2_ready(void) {
    neko_native_debug_log("w2 fs=%u ls=%u lc=%u", g_neko_manifest_field_site_count, g_neko_manifest_ldc_string_site_count, g_neko_manifest_ldc_class_site_count);
}

// === Wave 3 invoke/static-field support ===
static inline void* neko_method_compiled_entry(void *method_star) {
    if (method_star == NULL || g_neko_vm_layout.off_method_from_compiled_entry < 0) return NULL;
    return __atomic_load_n((void**)((uint8_t*)method_star + g_neko_vm_layout.off_method_from_compiled_entry), __ATOMIC_ACQUIRE);
}

__attribute__((visibility("default"))) void* neko_resolve_invoke_site(NekoManifestInvokeSite *site) {
    void *resolved;
    if (site == NULL) return NULL;
    resolved = __atomic_load_n(&site->resolved_method, __ATOMIC_ACQUIRE);
    if (resolved != NULL) {
        if (!neko_method_is_redefined_stale(resolved)) return resolved;
        __atomic_store_n(&site->resolved_method, NULL, __ATOMIC_RELEASE);
        resolved = NULL;
    }
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        const NekoManifestMethod *method = &g_neko_manifest_methods[i];
        void *method_star;
        if (!neko_manifest_method_active(i)) continue;
        if (method->owner_internal == NULL || site->owner_internal == NULL) continue;
        if (strcmp(method->owner_internal, site->owner_internal) != 0) continue;
        if (strcmp(method->method_name, site->method_name) != 0) continue;
        if (strcmp(method->method_desc, site->method_desc) != 0) continue;
        method_star = __atomic_load_n(&g_neko_manifest_method_stars[i], __ATOMIC_ACQUIRE);
        if (method_star == NULL) continue;
        __atomic_store_n(&site->resolved_method, method_star, __ATOMIC_RELEASE);
        return method_star;
    }
    return NULL;
}

static void neko_log_wave3_ready(void) {
    neko_native_debug_log("w3 is=%u sb=%u", g_neko_manifest_invoke_site_count, neko_count_cached_static_field_bases());
}

// === Wave 4a VM-entry / no-safepoint support ===
#if defined(_MSC_VER)
#define NEKO_THREAD_LOCAL __declspec(thread)
#else
#define NEKO_THREAD_LOCAL __thread
#endif

typedef struct JavaThread JavaThread;
typedef struct Klass Klass;
typedef void* oop;
typedef struct NekoHandleScope NekoHandleScope;
typedef oop* NekoHandle;

typedef struct NekoRtCtx {
    JavaThread* thread;
    void* saved_java_sp;
    void* saved_java_fp;
    void* saved_java_pc;
    uint32_t flags;
    int prior_thread_state;
    NekoHandleScope* top_scope;
} NekoRtCtx;

struct NekoHandleScope {
    NekoHandleScope* prev;
    oop* slots;
    size_t count;
    size_t capacity;
    JavaThread* owner_thread;
};

static NEKO_THREAD_LOCAL NekoRtCtx* g_neko_rt_tls_ctx = NULL;
static NEKO_THREAD_LOCAL NekoHandleScope* g_neko_rt_tls_scope_top = NULL;

static inline jboolean neko_wave4a_enabled(void) {
    return g_neko_vm_layout.wave4a_disabled ? JNI_FALSE : JNI_TRUE;
}

static inline uint32_t* neko_thread_state_slot(JavaThread *thread) {
    if (thread == NULL || g_neko_vm_layout.off_thread_thread_state < 0) return NULL;
    return (uint32_t*)((uint8_t*)thread + g_neko_vm_layout.off_thread_thread_state);
}

static inline jboolean neko_rt_thread_is_in_vm(JavaThread *thread) {
    uint32_t *slot = neko_thread_state_slot(thread);
    if (slot == NULL || g_neko_vm_layout.thread_state_in_vm < 0) return JNI_FALSE;
    return __atomic_load_n(slot, __ATOMIC_ACQUIRE) == (uint32_t)g_neko_vm_layout.thread_state_in_vm ? JNI_TRUE : JNI_FALSE;
}

static void neko_rt_close_scope_chain(NekoHandleScope *scope) {
    while (scope != NULL) {
        NekoHandleScope *next = scope->prev;
        free(scope->slots);
        free(scope);
        scope = next;
    }
}

static void neko_rt_close_scopes_for_ctx(NekoRtCtx *ctx) {
    NekoHandleScope *scope;
    if (ctx == NULL) return;
    scope = ctx->top_scope;
    ctx->top_scope = NULL;
    if (g_neko_rt_tls_ctx == ctx) {
        g_neko_rt_tls_scope_top = NULL;
    }
    neko_rt_close_scope_chain(scope);
}


static void neko_log_wave4a_status(void) {
    if (g_neko_vm_layout.wave4a_disabled) {
        neko_native_debug_log("w4x %s", g_neko_wave4a_unavailable_reason == NULL ? "u" : g_neko_wave4a_unavailable_reason);
        return;
    }
    neko_native_debug_log(
        "w4r tso=%td ao=%td+%td mo=%td hoo=%td tf=%s",
        g_neko_vm_layout.off_thread_thread_state,
        g_neko_vm_layout.off_java_thread_anchor,
        g_neko_vm_layout.off_java_frame_anchor_sp,
        g_neko_vm_layout.off_klass_java_mirror,
        g_neko_vm_layout.off_oophandle_obj,
        (g_neko_vm_layout.off_thread_tlab_top >= 0 && g_neko_vm_layout.off_thread_tlab_end >= 0 && g_neko_vm_layout.use_compact_object_headers == JNI_FALSE) ? "yes" : "no"
    );
}

__attribute__((visibility("default"))) void neko_rt_ctx_init(NekoRtCtx *ctx, JavaThread *thread, void *java_sp, void *java_fp, void *java_pc, uint32_t flags) {
    uint32_t *thread_state;
    if (ctx == NULL) return;
    memset(ctx, 0, sizeof(*ctx));
    ctx->thread = thread;
    ctx->saved_java_sp = java_sp;
    ctx->saved_java_fp = java_fp;
    ctx->saved_java_pc = java_pc;
    ctx->flags = flags;
    ctx->prior_thread_state = -1;
    thread_state = neko_thread_state_slot(thread);
    if (thread_state != NULL) {
        ctx->prior_thread_state = (int)__atomic_load_n(thread_state, __ATOMIC_ACQUIRE);
    }
}

__attribute__((visibility("default"))) void neko_rt_enter_vm(NekoRtCtx *ctx) {
    uint32_t *thread_state;
    if (ctx == NULL || ctx->thread == NULL || !neko_wave4a_enabled()) return;
    thread_state = neko_thread_state_slot(ctx->thread);
    if (thread_state == NULL || g_neko_vm_layout.thread_state_in_vm < 0) return;
    ctx->prior_thread_state = (int)__atomic_load_n(thread_state, __ATOMIC_ACQUIRE);
    if (g_neko_vm_layout.off_java_thread_last_Java_fp >= 0) {
        *(void**)((uint8_t*)ctx->thread + g_neko_vm_layout.off_java_thread_last_Java_fp) = ctx->saved_java_fp;
    }
    if (g_neko_vm_layout.off_java_thread_last_Java_pc >= 0) {
        *(void**)((uint8_t*)ctx->thread + g_neko_vm_layout.off_java_thread_last_Java_pc) = ctx->saved_java_pc;
    }
    if (g_neko_vm_layout.off_java_thread_last_Java_sp >= 0) {
        __atomic_store_n((void**)((uint8_t*)ctx->thread + g_neko_vm_layout.off_java_thread_last_Java_sp), ctx->saved_java_sp, __ATOMIC_RELEASE);
    }
    __atomic_store_n(thread_state, (uint32_t)g_neko_vm_layout.thread_state_in_vm, __ATOMIC_RELEASE);
    g_neko_rt_tls_ctx = ctx;
    g_neko_rt_tls_scope_top = ctx->top_scope;
}

__attribute__((visibility("default"))) void neko_rt_leave_vm(NekoRtCtx *ctx) {
    uint32_t *thread_state;
    void *pending;
    if (ctx == NULL || ctx->thread == NULL || !neko_wave4a_enabled()) return;
    thread_state = neko_thread_state_slot(ctx->thread);
    if (thread_state == NULL || g_neko_vm_layout.thread_state_in_java < 0) return;
    pending = neko_pending_exception(ctx->thread);
    (void)pending;
    neko_rt_close_scopes_for_ctx(ctx);
    __atomic_store_n(thread_state, (uint32_t)g_neko_vm_layout.thread_state_in_java, __ATOMIC_RELEASE);
    (void)__atomic_load_n(thread_state, __ATOMIC_ACQUIRE);
    if (g_neko_vm_layout.off_java_thread_last_Java_sp >= 0) {
        __atomic_store_n((void**)((uint8_t*)ctx->thread + g_neko_vm_layout.off_java_thread_last_Java_sp), NULL, __ATOMIC_RELEASE);
    }
    if (g_neko_vm_layout.off_java_thread_last_Java_fp >= 0) {
        *(void**)((uint8_t*)ctx->thread + g_neko_vm_layout.off_java_thread_last_Java_fp) = NULL;
    }
    if (g_neko_vm_layout.off_java_thread_last_Java_pc >= 0) {
        *(void**)((uint8_t*)ctx->thread + g_neko_vm_layout.off_java_thread_last_Java_pc) = NULL;
    }
    if (g_neko_rt_tls_ctx == ctx) {
        g_neko_rt_tls_ctx = NULL;
        g_neko_rt_tls_scope_top = NULL;
    }
}


__attribute__((visibility("default"))) NekoHandleScope* neko_rt_handles_open(NekoRtCtx *ctx, size_t reserve) {
    NekoHandleScope *scope;
    size_t capacity = reserve == 0u ? 8u : reserve;
    if (ctx == NULL || ctx->thread == NULL || !neko_wave4a_enabled()) return NULL;
    if (!neko_rt_thread_is_in_vm(ctx->thread)) return NULL;
    scope = (NekoHandleScope*)malloc(sizeof(NekoHandleScope));
    if (scope == NULL) return NULL;
    memset(scope, 0, sizeof(*scope));
    scope->slots = (oop*)calloc(capacity, sizeof(oop));
    if (scope->slots == NULL) {
        free(scope);
        return NULL;
    }
    scope->prev = ctx->top_scope;
    scope->capacity = capacity;
    scope->owner_thread = ctx->thread;
    ctx->top_scope = scope;
    g_neko_rt_tls_ctx = ctx;
    g_neko_rt_tls_scope_top = scope;
    if (!g_neko_wave4a_handle_caveat_logged) {
        g_neko_wave4a_handle_caveat_logged = 1;
        neko_native_debug_log("w4h mb");
    }
    return scope;
}

__attribute__((visibility("default"))) void neko_rt_handles_close(NekoHandleScope *scope) {
    NekoHandleScope *prev = NULL;
    NekoHandleScope *cur = g_neko_rt_tls_scope_top;
    if (scope == NULL) return;
    while (cur != NULL && cur != scope) {
        prev = cur;
        cur = cur->prev;
    }
    if (cur == scope) {
        if (prev == NULL) g_neko_rt_tls_scope_top = scope->prev;
        else prev->prev = scope->prev;
    }
    if (g_neko_rt_tls_ctx != NULL && g_neko_rt_tls_ctx->top_scope == scope) {
        g_neko_rt_tls_ctx->top_scope = scope->prev;
    }
    free(scope->slots);
    free(scope);
}

__attribute__((visibility("default"))) NekoHandle neko_rt_handle_from_oop(NekoHandleScope *scope, oop raw) {
    oop *grown;
    size_t new_capacity;
    if (scope == NULL || raw == NULL) return NULL;
    if (scope->count >= scope->capacity) {
        new_capacity = scope->capacity == 0u ? 8u : scope->capacity * 2u;
        grown = (oop*)realloc(scope->slots, new_capacity * sizeof(oop));
        if (grown == NULL) return NULL;
        memset(grown + scope->capacity, 0, (new_capacity - scope->capacity) * sizeof(oop));
        scope->slots = grown;
        scope->capacity = new_capacity;
    }
    scope->slots[scope->count] = raw;
    scope->count++;
    return &scope->slots[scope->count - 1u];
}

__attribute__((visibility("default"))) oop neko_rt_oop_from_handle(NekoHandle h) {
    return h == NULL ? NULL : *h;
}


static inline const void* neko_resolve_mirror_storage_slot_from_klass(const NekoVmLayout *layout, Klass *klass) {
    const uint8_t *oop_handle_addr;
    void *storage_slot;
    if (layout == NULL || klass == NULL || layout->off_klass_java_mirror < 0) return NULL;
    if (layout->java_spec_version < 9) return NULL;
    if (layout->off_oophandle_obj < 0) return NULL;

    oop_handle_addr = (const uint8_t*)klass + layout->off_klass_java_mirror;
    storage_slot = __atomic_load_n((void* const*)((const uint8_t*)oop_handle_addr + layout->off_oophandle_obj), __ATOMIC_ACQUIRE);
    return storage_slot;
}

static inline oop neko_resolve_mirror_oop_from_klass(const NekoVmLayout *layout, Klass *klass) {
    const void *cell;
    const void *storage_slot;
    if (layout == NULL || klass == NULL || layout->off_klass_java_mirror < 0) return NULL;

    if (layout->java_spec_version >= 9) {
        storage_slot = neko_resolve_mirror_storage_slot_from_klass(layout, klass);
        if (storage_slot == NULL) return NULL;
        return __atomic_load_n((void* const*)storage_slot, __ATOMIC_ACQUIRE);
    }

    cell = (const uint8_t*)klass + layout->off_klass_java_mirror;
    return (oop)neko_load_oop_from_cell(cell);
}

__attribute__((visibility("default"))) oop neko_rt_mirror_from_klass_nosafepoint(Klass *k) {
    if (k == NULL || !neko_wave4a_enabled()) return NULL;
    return neko_resolve_mirror_oop_from_klass(&g_neko_vm_layout, k);
}

__attribute__((visibility("default"))) oop neko_rt_static_base_from_holder_nosafepoint(Klass *holder) {
    return neko_rt_mirror_from_klass_nosafepoint(holder);
}

__attribute__((visibility("default"))) oop neko_rt_try_alloc_instance_fast_nosafepoint(Klass *ik, size_t instance_size_bytes) {
    JavaThread *thread;
    void **top_ptr;
    void **end_ptr;
    void *expected;
    char *cur_top;
    char *cur_end;
    char *new_top;
    char *allocated;
    u8 aligned_size;
    if (ik == NULL || instance_size_bytes == 0u || !neko_wave4a_enabled()) return NULL;
    if (g_neko_vm_layout.use_compact_object_headers) return NULL;
    thread = (JavaThread*)neko_get_current_thread();
    if (thread == NULL || g_neko_vm_layout.off_thread_tlab_top < 0 || g_neko_vm_layout.off_thread_tlab_end < 0) return NULL;
    aligned_size = neko_align_up_u8((u8)instance_size_bytes, 8u);
    top_ptr = (void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_tlab_top);
    end_ptr = (void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_tlab_end);
    cur_end = (char*)__atomic_load_n(end_ptr, __ATOMIC_ACQUIRE);
    expected = __atomic_load_n(top_ptr, __ATOMIC_RELAXED);
    for (;;) {
        cur_top = (char*)expected;
        new_top = cur_top + aligned_size;
        if (new_top > cur_end) return NULL;
        if (__atomic_compare_exchange_n(top_ptr, &expected, (void*)new_top, JNI_FALSE, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) {
            allocated = cur_top;
            break;
        }
    }
    memset(allocated, 0, (size_t)aligned_size);
    *(uintptr_t*)allocated = (uintptr_t)1u;
    if (neko_uses_compressed_klass_pointers()) {
        *(u4*)(allocated + sizeof(uintptr_t)) = neko_encode_klass_pointer(ik);
    } else {
        *(void**)(allocated + sizeof(uintptr_t)) = ik;
    }
    return (oop)allocated;
}

static inline uint32_t neko_lh_header_size(uint32_t lh) {
    return (lh >> 16) & 0xFFu;
}

static inline uint32_t neko_lh_log2_element(uint32_t lh) {
    return lh & 0x3Fu;   /* array element log2 size */
}

static inline size_t neko_lh_instance_size(uint32_t lh) {
    return (size_t)(lh & ~(uint32_t)1u);  /* instance_size for non-array Klass */
}

static inline bool neko_lh_is_array(uint32_t lh) {
    return ((int32_t)lh) < 0;   /* layout_helper encodes arrays with sign bit in HotSpot */
}

static void* neko_rt_try_alloc_array_fast_nosafepoint(void* array_klass, int32_t length) {
    JavaThread *thread;
    void **top_ptr;
    void **end_ptr;
    void *expected;
    uint32_t lh;
    uint32_t header_bytes;
    uint32_t log2_elem;
    size_t payload;
    size_t total;
    size_t align;
    size_t aligned;
    char *cur_top;
    char *cur_end;
    char *new_top;
    char *allocated;
    if (array_klass == NULL || length < 0 || !neko_wave4a_enabled()) return NULL;
    if (g_neko_vm_layout.use_compact_object_headers) return NULL;
    lh = *(uint32_t*)((uint8_t*)array_klass + g_neko_vm_layout.off_klass_layout_helper);
    if (!neko_lh_is_array(lh)) return NULL;
    header_bytes = neko_lh_header_size(lh);
    log2_elem = neko_lh_log2_element(lh);
    payload = ((size_t)(uint32_t)length) << log2_elem;
    total = (size_t)header_bytes + payload;
    align = sizeof(void*);
    aligned = (total + (align - 1u)) & ~(align - 1u);
    thread = (JavaThread*)neko_get_current_thread();
    if (thread == NULL || g_neko_vm_layout.off_thread_tlab_top < 0 || g_neko_vm_layout.off_thread_tlab_end < 0) return NULL;
    top_ptr = (void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_tlab_top);
    end_ptr = (void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_tlab_end);
    cur_end = (char*)__atomic_load_n(end_ptr, __ATOMIC_ACQUIRE);
    expected = __atomic_load_n(top_ptr, __ATOMIC_RELAXED);
    for (;;) {
        cur_top = (char*)expected;
        new_top = cur_top + aligned;
        if (new_top > cur_end) return NULL;
        if (__atomic_compare_exchange_n(top_ptr, &expected, (void*)new_top, JNI_FALSE, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) {
            allocated = cur_top;
            break;
        }
    }
    memset(allocated, 0, aligned);
    *(uintptr_t*)allocated = (uintptr_t)1u;
    if (neko_uses_compressed_klass_pointers()) {
        *(u4*)(allocated + sizeof(uintptr_t)) = neko_encode_klass_pointer(array_klass);
    } else {
        *(void**)(allocated + sizeof(uintptr_t)) = array_klass;
    }
    *(int32_t*)(allocated + ((ptrdiff_t)header_bytes - 4)) = length;
    return allocated;
}

static inline void neko_store_heap_oop_at_unpublished(void* base, int32_t offset, void* raw_oop) {
    if (neko_uses_compressed_oops()) {
        u4 narrow = neko_encode_heap_oop(raw_oop);
        *(u4*)((uint8_t*)base + offset) = narrow;
    } else {
        *(void**)((uint8_t*)base + offset) = raw_oop;
    }
}

static inline void* neko_load_heap_oop_from_published(void* base, int32_t offset) {
    if (neko_uses_compressed_oops()) {
        u4 narrow = *(u4*)((uint8_t*)base + offset);
        return neko_decode_heap_oop(narrow);
    }
    return *(void**)((uint8_t*)base + offset);
}

static inline int32_t neko_object_array_element_offset(void* array_klass, int32_t index) {
    uint32_t lh = *(uint32_t*)((uint8_t*)array_klass + g_neko_vm_layout.off_klass_layout_helper);
    uint32_t header_bytes = neko_lh_header_size(lh);
    uint32_t log2_elem = neko_lh_log2_element(lh);
    return (int32_t)(header_bytes + ((uint32_t)index << log2_elem));
}

#undef NEKO_THREAD_LOCAL

static jboolean neko_atomic_or_bits(void *address, size_t width, uint32_t mask) {
    if (address == NULL || mask == 0u) return JNI_FALSE;
    switch (width) {
        case 2:
            __atomic_fetch_or((uint16_t*)address, (uint16_t)mask, __ATOMIC_RELAXED);
            return JNI_TRUE;
        case 4:
            __atomic_fetch_or((uint32_t*)address, (uint32_t)mask, __ATOMIC_RELAXED);
            return JNI_TRUE;
        default:
            return JNI_FALSE;
    }
}

static void neko_log_flag_patch_path_once(const char *path_name) {
    if (g_neko_flag_patch_path_logged) return;
    NEKO_TRACE(1, "[nk] fp %s j=%d", path_name, g_neko_vm_layout.java_spec_version);
    g_neko_flag_patch_path_logged = 1;
}

static jboolean neko_apply_access_flags_path(void *method_star) {
    uint32_t access_mask;
    size_t access_width;
    if (method_star == NULL) return JNI_FALSE;
    access_mask = (g_neko_vm_layout.access_not_c1_compilable != 0u ? g_neko_vm_layout.access_not_c1_compilable : 0x04000000u)
        | (g_neko_vm_layout.access_not_c2_compilable != 0u ? g_neko_vm_layout.access_not_c2_compilable : 0x02000000u)
        | (g_neko_vm_layout.access_not_osr_compilable != 0u ? g_neko_vm_layout.access_not_osr_compilable : 0x08000000u);
    access_width = g_neko_vm_layout.access_flags_size == 0 ? (size_t)4 : g_neko_vm_layout.access_flags_size;
    neko_log_flag_patch_path_once("AccessFlags");
    return neko_atomic_or_bits(
        (uint8_t*)method_star + g_neko_vm_layout.off_method_access_flags,
        access_width,
        access_mask
    );
}

static jboolean neko_apply_method_flags_path(void *method_star) {
    const uint32_t required_mask = (1u << 8) | (1u << 9) | (1u << 10);
    const uint32_t patch_mask = required_mask | (1u << 12);
    uint32_t *status_ptr;
    uint32_t status_value;
    if (method_star == NULL) return JNI_FALSE;
    if (g_neko_vm_layout.off_method_flags_status < 0) return JNI_FALSE;
    neko_log_flag_patch_path_once("MethodFlags");
    status_ptr = (uint32_t*)((uint8_t*)method_star + g_neko_vm_layout.off_method_flags_status);
    __atomic_fetch_or(status_ptr, patch_mask, __ATOMIC_SEQ_CST);
    status_value = __atomic_load_n(status_ptr, __ATOMIC_SEQ_CST);
    if ((status_value & required_mask) != required_mask) {
        neko_error_log(
            "MethodFlags readback mismatch (status=0x%08x, offset=%td)",
            status_value,
            g_neko_vm_layout.off_method_flags_status
        );
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean neko_apply_no_compile_flags(void *method_star) {
    if (g_neko_vm_layout.java_spec_version >= 21) {
        return neko_apply_method_flags_path(method_star);
    }
    return neko_apply_access_flags_path(method_star);
}

static int neko_patch_method(const NekoManifestMethod *entry, void *method_star) {
    uintptr_t index;
    void *compiled_code;
    void *stub_i2i;
    void *stub_c2i;
    if (entry == NULL || method_star == NULL) return -1;
    index = (uintptr_t)(entry - g_neko_manifest_methods);
    if (index >= g_neko_manifest_method_count) return -1;
    if (g_neko_manifest_patch_states[index] == NEKO_PATCH_STATE_APPLIED) return 0;
    if (g_neko_manifest_patch_states[index] == NEKO_PATCH_STATE_FAILED) return -1;

    compiled_code = __atomic_load_n((void**)((uint8_t*)method_star + g_neko_vm_layout.off_method_code), __ATOMIC_ACQUIRE);
    if (compiled_code != NULL) {
        g_neko_manifest_patch_states[index] = NEKO_PATCH_STATE_FAILED;
    NEKO_TRACE(1, "[nk] pc %s.%s%s", entry->owner_internal, entry->method_name, entry->method_desc);
        return -1;
    }
    if (!neko_apply_no_compile_flags(method_star)) {
        g_neko_manifest_patch_states[index] = NEKO_PATCH_STATE_FAILED;
        neko_error_log("failed to set no-compile bits for %s.%s%s", entry->owner_internal, entry->method_name, entry->method_desc);
        return -1;
    }
    if (entry->signature_id >= g_neko_signature_descriptor_count) {
        g_neko_manifest_patch_states[index] = NEKO_PATCH_STATE_FAILED;
        neko_error_log("signature_id %u out of range for %s.%s%s", entry->signature_id, entry->owner_internal, entry->method_name, entry->method_desc);
        return -1;
    }

    stub_i2i = g_neko_signature_i2i_stubs[entry->signature_id];
    stub_c2i = g_neko_signature_c2i_stubs[entry->signature_id];
    if (stub_i2i == NULL || stub_c2i == NULL) {
        g_neko_manifest_patch_states[index] = NEKO_PATCH_STATE_FAILED;
        neko_error_log("missing stub for signature %u (%s.%s%s)", entry->signature_id, entry->owner_internal, entry->method_name, entry->method_desc);
        return -1;
    }

    __atomic_store_n((void**)((uint8_t*)method_star + g_neko_vm_layout.off_method_i2i_entry), stub_i2i, __ATOMIC_RELEASE);
    __atomic_store_n((void**)((uint8_t*)method_star + g_neko_vm_layout.off_method_from_interpreted_entry), stub_i2i, __ATOMIC_RELEASE);
    __atomic_store_n((void**)((uint8_t*)method_star + g_neko_vm_layout.off_method_from_compiled_entry), stub_c2i, __ATOMIC_RELEASE);

    g_neko_manifest_patch_states[index] = NEKO_PATCH_STATE_APPLIED;
    g_neko_manifest_patch_count++;
    NEKO_TRACE(1, "[nk] pp %s.%s%s s=%u", entry->owner_internal, entry->method_name, entry->method_desc, entry->signature_id);
    return 0;
}

static void neko_patch_discovered_methods(void) {
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        if (g_neko_manifest_method_stars[i] == NULL) continue;
        if (g_neko_manifest_patch_states[i] != NEKO_PATCH_STATE_NONE) continue;
        (void)neko_patch_method(&g_neko_manifest_methods[i], g_neko_manifest_method_stars[i]);
    }
}

__attribute__((visibility("hidden"))) u4 neko_encode_heap_oop_runtime(void *wide) {
    return neko_encode_heap_oop(wide);
}

// === Inline-cache metadata ===
static const neko_icache_meta neko_icache_meta_2_0_0 = {"addF", "()V", NULL, NULL, JNI_FALSE};
static const neko_icache_meta neko_icache_meta_5_1_0 = {"doAdd", "()V", NULL, NULL, JNI_FALSE};

__attribute__((used, visibility("default"))) int32_t neko_impl_0(void* _this, int32_t p0, int32_t p1) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return 0;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(0u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return 0;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 0, "(II)I");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[11];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
    locals[1].i = p0;
    locals[2].i = p1;
L0: ;
    PUSH_I(locals[1].i);
    PUSH_I(locals[2].i);
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a * b); }
    return POP_I();
L1: ;
__neko_exception_exit: ;
    return 0;
}

__attribute__((used, visibility("default"))) int32_t neko_impl_1(void* _this, int32_t p0, int32_t p1) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return 0;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(1u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return 0;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 1, "(II)I");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[11];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
    locals[1].i = p0;
    locals[2].i = p1;
L0: ;
    PUSH_I(locals[1].i);
    PUSH_I(locals[2].i);
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }
    return POP_I();
L1: ;
__neko_exception_exit: ;
    return 0;
}

__attribute__((used, visibility("default"))) void neko_impl_2(void* _this) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(2u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 2, "()V");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[9];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
L0: ;
    PUSH_O(locals[0].o);
    { NekoManifestFieldSite *__site = &g_neko_field_sites_2[0]; void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; PUSH_O(neko_field_read_oop(__recv, __site)); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
    { jvalue * __args = NULL; jobject __recv = POP_O(); if (__recv == NULL) { (void)neko_throw_cached(env, g_neko_throw_npe); goto __neko_exception_exit; } jmethodID mid = neko_bound_method(env, g_mid_0, "pack/tests/basics/inner/Exec", "addF", "()V", JNI_FALSE); if (mid == NULL) goto __neko_exception_exit; { neko_icache_dispatch(env, &neko_icache_2_0_0, &neko_icache_meta_2_0_0, __recv, mid, __args); } if (neko_pending_exception(thread) != NULL) goto __neko_exception_exit; }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
L1: ;
    PUSH_O(locals[0].o);
    { NekoManifestFieldSite *__site = &g_neko_field_sites_2[1]; void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; PUSH_O(neko_field_read_oop(__recv, __site)); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
    stack[sp] = stack[sp-1]; sp++;
    { NekoManifestFieldSite *__site = &g_neko_field_sites_2[2]; void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; PUSH_I(neko_field_read_I(__recv, __site)); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
    PUSH_O(locals[0].o);
    { NekoManifestFieldSite *__site = &g_neko_field_sites_2[3]; void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; PUSH_I(neko_field_read_I(__recv, __site)); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }
    { NekoManifestFieldSite *__site = &g_neko_field_sites_2[4]; jint val = POP_I(); void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; neko_field_write_I(__recv, __site, val); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
L2: ;
    return;
L3: ;
__neko_exception_exit: ;
    return;
}

__attribute__((used, visibility("default"))) void neko_impl_3(void* _this) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(3u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 3, "()V");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[9];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
L0: ;
    PUSH_O(locals[0].o);
    stack[sp] = stack[sp-1]; sp++;
    { NekoManifestFieldSite *__site = &g_neko_field_sites_3[0]; void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; PUSH_I(neko_field_read_I(__recv, __site)); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
    PUSH_I(2);
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }
    { NekoManifestFieldSite *__site = &g_neko_field_sites_3[1]; jint val = POP_I(); void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; neko_field_write_I(__recv, __site, val); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
L1: ;
    return;
L2: ;
__neko_exception_exit: ;
    return;
}

__attribute__((used, visibility("default"))) void* neko_impl_4(void* _this, int32_t p0) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return NULL;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(4u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return NULL;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 4, "(I)Ljava/lang/String;");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[10];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
    locals[1].i = p0;
L0: ;
    PUSH_I(locals[1].i);
    PUSH_I(1);
    { jint b = POP_I(); jint a = POP_I(); if (a != b) goto L2; }
L1: ;
    { void *__ldc = neko_ldc_string_site_oop(env, &g_neko_ldc_sites_4[0]); if (neko_pending_exception(thread) != NULL) goto __neko_exception_exit; PUSH_O(__ldc); }
    return (void*)POP_O();
L2: ;
    { void *__ldc = neko_ldc_string_site_oop(env, &g_neko_ldc_sites_4[1]); if (neko_pending_exception(thread) != NULL) goto __neko_exception_exit; PUSH_O(__ldc); }
    return (void*)POP_O();
L3: ;
__neko_exception_exit: ;
    return NULL;
}

__attribute__((used, visibility("default"))) void neko_impl_5(void* p0) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(5u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 5, "(Lpack/tests/basics/runable/Exec;)V");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[10];
    memset(locals, 0, sizeof(locals));
    locals[0].o = p0;
L0: ;
    { NekoManifestFieldSite *__site = &g_neko_field_sites_5[0]; void *__base = neko_field_site_static_base(thread, __site); if (__base == NULL) { if (neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, "java/lang/IllegalStateException", "failed to resolve static field base"); goto __neko_exception_exit; } int32_t __cur = neko_getstatic_i32(__site); { void *m = neko_static_mirror(__site); int32_t *addr = neko_static_i32_addr(__site); NEKO_TRACE(2, "[nk] sfg c=%d sig=\"%s\" site=%p mir=%p off=%ld addr=%p val=%d\n", 5, "(Lpack/tests/basics/runable/Exec;)V", (void*)__site, m, (long)__site->field_offset_cookie, (void*)addr, __cur); } PUSH_I(((int32_t)__cur)); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
    locals[1].i = POP_I();
L1: ;
    PUSH_O(locals[0].o);
    { jvalue * __args = NULL; jobject __recv = POP_O(); if (__recv == NULL) { (void)neko_throw_cached(env, g_neko_throw_npe); goto __neko_exception_exit; } jmethodID mid = neko_bound_method(env, g_mid_1, "pack/tests/basics/runable/Exec", "doAdd", "()V", JNI_FALSE); if (mid == NULL) goto __neko_exception_exit; { neko_icache_dispatch(env, &neko_icache_5_1_0, &neko_icache_meta_5_1_0, __recv, mid, __args); } if (neko_pending_exception(thread) != NULL) goto __neko_exception_exit; }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
L2: ;
    { NekoManifestFieldSite *__site = &g_neko_field_sites_5[1]; void *__base = neko_field_site_static_base(thread, __site); if (__base == NULL) { if (neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, "java/lang/IllegalStateException", "failed to resolve static field base"); goto __neko_exception_exit; } int32_t __cur = neko_getstatic_i32(__site); { void *m = neko_static_mirror(__site); int32_t *addr = neko_static_i32_addr(__site); NEKO_TRACE(2, "[nk] sfg c=%d sig=\"%s\" site=%p mir=%p off=%ld addr=%p val=%d\n", 5, "(Lpack/tests/basics/runable/Exec;)V", (void*)__site, m, (long)__site->field_offset_cookie, (void*)addr, __cur); } PUSH_I(((int32_t)__cur)); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
    PUSH_I(locals[1].i);
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }
    { NekoManifestFieldSite *__site = &g_neko_field_sites_5[2]; jint val = POP_I(); void *__base = neko_field_site_static_base(thread, __site); if (__base == NULL) { if (neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, "java/lang/IllegalStateException", "failed to resolve static field base"); goto __neko_exception_exit; } int32_t __nv = ((int32_t)val); { void *m = neko_static_mirror(__site); int32_t *addr = neko_static_i32_addr(__site); int32_t oldv = *addr; NEKO_TRACE(2, "[nk] sfp c=%d sig=\"%s\" site=%p mir=%p off=%ld addr=%p old=%d new=%d\n", 5, "(Lpack/tests/basics/runable/Exec;)V", (void*)__site, m, (long)__site->field_offset_cookie, (void*)addr, oldv, __nv); neko_putstatic_i32(__site, __nv); NEKO_TRACE(2, "[nk] sfd c=%d sig=\"%s\" addr=%p now=%d\n", 5, "(Lpack/tests/basics/runable/Exec;)V", (void*)addr, *addr); { uint8_t *p = (uint8_t*)addr - 8; NEKO_TRACE(2, "[nk] sfbx %02x %02x %02x %02x %02x %02x %02x %02x | %02x %02x %02x %02x %02x %02x %02x %02x\n", p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], p[11], p[12], p[13], p[14], p[15]); } } }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
L3: ;
    return;
L4: ;
__neko_exception_exit: ;
    return;
}

__attribute__((used, visibility("default"))) int32_t neko_impl_6(void* _this, int32_t p0, int32_t p1) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return 0;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(6u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return 0;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 6, "(II)I");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[11];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
    locals[1].i = p0;
    locals[2].i = p1;
L0: ;
    PUSH_I(locals[1].i);
    PUSH_I(locals[2].i);
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }
    return POP_I();
L1: ;
__neko_exception_exit: ;
    return 0;
}

__attribute__((used, visibility("default"))) void neko_impl_7(int32_t p0) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(7u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 7, "(I)V");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[9];
    memset(locals, 0, sizeof(locals));
    locals[0].i = p0;
L0: ;
    PUSH_I(locals[0].i);
    if (POP_I() != 0) goto L2;
L1: ;
    { NekoManifestFieldSite *__site = &g_neko_field_sites_7[0]; void *__base = neko_field_site_static_base(thread, __site); if (__base == NULL) { if (neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, "java/lang/IllegalStateException", "failed to resolve static field base"); goto __neko_exception_exit; } int32_t __cur = neko_getstatic_i32(__site); { void *m = neko_static_mirror(__site); int32_t *addr = neko_static_i32_addr(__site); NEKO_TRACE(2, "[nk] sfg c=%d sig=\"%s\" site=%p mir=%p off=%ld addr=%p val=%d\n", 7, "(I)V", (void*)__site, m, (long)__site->field_offset_cookie, (void*)addr, __cur); } PUSH_I(((int32_t)__cur)); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
    PUSH_I(1);
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }
    { NekoManifestFieldSite *__site = &g_neko_field_sites_7[1]; jint val = POP_I(); void *__base = neko_field_site_static_base(thread, __site); if (__base == NULL) { if (neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, "java/lang/IllegalStateException", "failed to resolve static field base"); goto __neko_exception_exit; } int32_t __nv = ((int32_t)val); { void *m = neko_static_mirror(__site); int32_t *addr = neko_static_i32_addr(__site); int32_t oldv = *addr; NEKO_TRACE(2, "[nk] sfp c=%d sig=\"%s\" site=%p mir=%p off=%ld addr=%p old=%d new=%d\n", 7, "(I)V", (void*)__site, m, (long)__site->field_offset_cookie, (void*)addr, oldv, __nv); neko_putstatic_i32(__site, __nv); NEKO_TRACE(2, "[nk] sfd c=%d sig=\"%s\" addr=%p now=%d\n", 7, "(I)V", (void*)addr, *addr); { uint8_t *p = (uint8_t*)addr - 8; NEKO_TRACE(2, "[nk] sfbx %02x %02x %02x %02x %02x %02x %02x %02x | %02x %02x %02x %02x %02x %02x %02x %02x\n", p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], p[11], p[12], p[13], p[14], p[15]); } } }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
    goto L3;
L2: ;
    PUSH_I(locals[0].i);
    PUSH_I(1);
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a - b); }
    { jint arg0 = POP_I(); NEKO_TRACE(2, "[nk] di c=%d t=%d sig=\"%s\"\n", 7, 7, "(I)V"); if (!neko_manifest_method_active(7u)) { neko_raise_cached_pending(thread, g_neko_throw_loader_linkage); goto __neko_exception_exit; } neko_impl_7(arg0); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
L3: ;
    return;
L4: ;
__neko_exception_exit: ;
    return;
}

__attribute__((used, visibility("default"))) void neko_impl_8(void) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(8u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 8, "()V");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[10];
    memset(locals, 0, sizeof(locals));
L0: ;
    PUSH_D(0.0);
    locals[0].d = POP_D();
L1: ;
    PUSH_D(locals[0].d);
    PUSH_D(100.1);
    { jdouble b = POP_D(); jdouble a = POP_D(); PUSH_I(a > b ? 1 : (a < b ? -1 : (a == b ? 0 : 1))); }
    if (POP_I() >= 0) goto L3;
L2: ;
    PUSH_D(locals[0].d);
    PUSH_D(0.99);
    { jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(a + b); }
    locals[0].d = POP_D();
    goto L1;
L3: ;
    { NekoManifestFieldSite *__site = &g_neko_field_sites_8[0]; void *__base = neko_field_site_static_base(thread, __site); if (__base == NULL) { if (neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, "java/lang/IllegalStateException", "failed to resolve static field base"); goto __neko_exception_exit; } int32_t __cur = neko_getstatic_i32(__site); { void *m = neko_static_mirror(__site); int32_t *addr = neko_static_i32_addr(__site); NEKO_TRACE(2, "[nk] sfg c=%d sig=\"%s\" site=%p mir=%p off=%ld addr=%p val=%d\n", 8, "()V", (void*)__site, m, (long)__site->field_offset_cookie, (void*)addr, __cur); } PUSH_I(((int32_t)__cur)); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
    PUSH_I(1);
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }
    { NekoManifestFieldSite *__site = &g_neko_field_sites_8[1]; jint val = POP_I(); void *__base = neko_field_site_static_base(thread, __site); if (__base == NULL) { if (neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, "java/lang/IllegalStateException", "failed to resolve static field base"); goto __neko_exception_exit; } int32_t __nv = ((int32_t)val); { void *m = neko_static_mirror(__site); int32_t *addr = neko_static_i32_addr(__site); int32_t oldv = *addr; NEKO_TRACE(2, "[nk] sfp c=%d sig=\"%s\" site=%p mir=%p off=%ld addr=%p old=%d new=%d\n", 8, "()V", (void*)__site, m, (long)__site->field_offset_cookie, (void*)addr, oldv, __nv); neko_putstatic_i32(__site, __nv); NEKO_TRACE(2, "[nk] sfd c=%d sig=\"%s\" addr=%p now=%d\n", 8, "()V", (void*)addr, *addr); { uint8_t *p = (uint8_t*)addr - 8; NEKO_TRACE(2, "[nk] sfbx %02x %02x %02x %02x %02x %02x %02x %02x | %02x %02x %02x %02x %02x %02x %02x %02x\n", p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], p[11], p[12], p[13], p[14], p[15]); } } }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
L4: ;
    return;
L5: ;
__neko_exception_exit: ;
    return;
}

__attribute__((used, visibility("default"))) void neko_impl_9(void* _this) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(9u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 9, "()V");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[9];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
L0: ;
    return;
L1: ;
__neko_exception_exit: ;
    return;
}

__attribute__((used, visibility("default"))) void neko_impl_10(void* _this) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(10u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 10, "()V");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[9];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
L0: ;
    return;
L1: ;
__neko_exception_exit: ;
    return;
}

__attribute__((used, visibility("default"))) void neko_impl_11(void* _this) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(11u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 11, "()V");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[9];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
L0: ;
    return;
L1: ;
__neko_exception_exit: ;
    return;
}

__attribute__((used, visibility("default"))) void neko_impl_12(void* _this) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(12u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 12, "()V");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[9];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
L0: ;
    return;
L1: ;
__neko_exception_exit: ;
    return;
}

__attribute__((used, visibility("default"))) void neko_impl_13(void* _this) {
    JNIEnv *env = NULL;
    if (!neko_loader_ready()) {
        env = neko_current_env();
        (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
        return;
    }
    neko_maybe_rescan_cld_liveness();
    env = neko_current_env();
    void *thread = neko_get_current_thread();
    if (!neko_manifest_method_active(13u)) {
        neko_raise_cached_pending(thread, g_neko_throw_loader_linkage);
        return;
    }
    NEKO_TRACE(2, "[nk] e idx=%d sig=\"%s\"\n", 13, "()V");
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[9];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
L0: ;
    PUSH_O(locals[0].o);
    stack[sp] = stack[sp-1]; sp++;
    { NekoManifestFieldSite *__site = &g_neko_field_sites_13[0]; void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; PUSH_I(neko_field_read_I(__recv, __site)); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
    PUSH_I(3);
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }
    { NekoManifestFieldSite *__site = &g_neko_field_sites_13[1]; jint val = POP_I(); void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; neko_field_write_I(__recv, __site, val); }
    if (neko_pending_exception(thread) != NULL) { goto __neko_exception_exit; }
L1: ;
    return;
L2: ;
__neko_exception_exit: ;
    return;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    jint env_status;
    (void)reserved;
    g_neko_java_vm = vm;
    neko_init_debug_level_from_env();
    neko_native_debug_log("onload enter");
    env_status = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
    if (env_status != JNI_OK || env == NULL) {
        neko_error_log("GetEnv(JNI_VERSION_1_6) failed, falling back to throw body");
        return JNI_VERSION_1_6;
    }
    if (!neko_init_throwable_cache(env)) {
        neko_error_log("failed to initialize bootstrap throwable cache");
        return JNI_ERR;
    }
    neko_native_debug_log(
        "throwable_cache_ok=%d npe=%p le=%p loader_le=%p",
        (g_neko_throw_npe != NULL && g_neko_throw_le != NULL && g_neko_throw_loader_linkage != NULL) ? 1 : 0,
        g_neko_throw_npe,
        g_neko_throw_le,
        g_neko_throw_loader_linkage
    );
    if (!neko_resolve_vm_symbols()) {
        return JNI_VERSION_1_6;
    }
    if (!neko_parse_vm_layout_strict(env)) {
        return JNI_VERSION_1_6;
    }
    if (!neko_capture_wellknown_klasses()) {
        return JNI_VERSION_1_6;
    }
    neko_mark_loader_loaded();
    NEKO_TRACE(0, "[nk] ol mark_loader_loaded ok str=%p", g_neko_vm_layout.klass_java_lang_String);
    neko_log_runtime_helpers_ready();
    neko_log_wave4a_status();
    neko_resolve_string_intern_layout();
    NEKO_TRACE(0, "[nk] ol resolve_string_intern_layout ok hash_off=%td", g_neko_vm_layout.off_string_hash);
    neko_string_intern_prewarm_and_publish(env);
    NEKO_TRACE(0, "[nk] ol prewarm ok backend=%d", (int)g_neko_string_root_backend);
    neko_bootstrap_owner_discovery();
    neko_patch_discovered_methods();
    NEKO_TRACE(0, "[nk] dm %u/%u", g_neko_manifest_match_count, g_neko_manifest_method_count);
    NEKO_TRACE(0, "[nk] dp %u/%u", g_neko_manifest_patch_count, g_neko_manifest_method_count);
    neko_log_wave2_ready();
    neko_log_wave3_ready();
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    jint env_status;
    (void)reserved;
    if (vm == NULL) return;
    env_status = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
    if (env_status != JNI_OK || env == NULL) return;
    neko_manifest_teardown(env);
}
