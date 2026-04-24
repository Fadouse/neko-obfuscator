package dev.nekoobfuscator.native_.codegen.emit;

public final class BootstrapEmitter {
    private final Wave1RuntimeEmitter wave1RuntimeEmitter;

    public BootstrapEmitter(Wave1RuntimeEmitter wave1RuntimeEmitter) {
        this.wave1RuntimeEmitter = wave1RuntimeEmitter;
    }

    public String renderBootstrapSupport() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
#define NEKO_REQUIRED_VM_SYMBOL_COUNT 24u
#define NEKO_REQUIRED_VM_SYMBOLS(X) \
    X(gHotSpotVMStructs) \
    X(gHotSpotVMStructEntryTypeNameOffset) \
    X(gHotSpotVMStructEntryFieldNameOffset) \
    X(gHotSpotVMStructEntryTypeStringOffset) \
    X(gHotSpotVMStructEntryIsStaticOffset) \
    X(gHotSpotVMStructEntryOffsetOffset) \
    X(gHotSpotVMStructEntryAddressOffset) \
    X(gHotSpotVMStructEntryArrayStride) \
    X(gHotSpotVMTypes) \
    X(gHotSpotVMTypeEntryTypeNameOffset) \
    X(gHotSpotVMTypeEntrySuperclassNameOffset) \
    X(gHotSpotVMTypeEntryIsOopTypeOffset) \
    X(gHotSpotVMTypeEntryIsIntegerTypeOffset) \
    X(gHotSpotVMTypeEntryIsUnsignedOffset) \
    X(gHotSpotVMTypeEntrySizeOffset) \
    X(gHotSpotVMTypeEntryArrayStride) \
    X(gHotSpotVMIntConstants) \
    X(gHotSpotVMIntConstantEntryNameOffset) \
    X(gHotSpotVMIntConstantEntryValueOffset) \
    X(gHotSpotVMIntConstantEntryArrayStride) \
    X(gHotSpotVMLongConstants) \
    X(gHotSpotVMLongConstantEntryNameOffset) \
    X(gHotSpotVMLongConstantEntryValueOffset) \
    X(gHotSpotVMLongConstantEntryArrayStride)

typedef uint32_t u4;
typedef uint64_t u8;

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
#ifdef NEKO_DEBUG_ENABLED
static int neko_debug_level = 0;
#define NEKO_TRACE(level, ...) do { if (neko_debug_level >= (level)) fprintf(stderr, __VA_ARGS__); } while(0)
#else
#define NEKO_TRACE(level, ...) ((void)0)
#endif
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
    if (value == NULL || value[0] == '\0') return 0;
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
    if (value == NULL || value[0] == '\0') value = getenv("NEKO_NATIVE_DEBUG");
    neko_debug_level = neko_parse_debug_level_text(value);
#else
#endif
}

static void neko_vlog(FILE *stream, const char *fmt, va_list args) {
    fputs("neko: ", stream);
    vfprintf(stream, fmt, args);
    fputc('\\n', stream);
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
    fputc('\\n', stderr);
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
    fputc('\\n', stderr);
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
    while (*cur != '\\0') {
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
static void* neko_class_klass_pointer(jclass klass_obj);
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

""");
        sb.append(wave1RuntimeEmitter.renderBootstrapRuntimeSupport());
        sb.append("""

static void neko_derive_method_flags_status_offset(void) {
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
    if (path == NULL || path[0] == '\\0') return 0;
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
        if (path == NULL || path[0] == '\\0') continue;
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
    if (name == NULL || name[0] == '\0') return NULL;
    if (g_neko_libjvm_handle == NULL) {
        g_neko_libjvm_handle = neko_resolve_libjvm_handle();
    }
    return neko_resolve_symbol_address(name);
}

""");
        sb.append("""

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
#define NEKO_RESOLVE_REQUIRED_SYMBOL(name) \
    do { \
        g_neko_vm_symbols.name = neko_resolve_symbol_address(#name); \
        if (g_neko_vm_symbols.name == NULL) { \
            neko_error_log("required libjvm symbol %s not found, falling back to throw body", #name); \
            return JNI_FALSE; \
        } \
        resolved++; \
    } while (0);
    NEKO_REQUIRED_VM_SYMBOLS(NEKO_RESOLVE_REQUIRED_SYMBOL);
#undef NEKO_RESOLVE_REQUIRED_SYMBOL
    neko_resolve_strict_optional_symbols();
    NEKO_TRACE(0, "[nk] lj %u/%u", resolved, NEKO_REQUIRED_VM_SYMBOL_COUNT);
    return JNI_TRUE;
}

static int neko_parse_java_spec_version_text(const char *value) {
    if (value == NULL || value[0] == '\\0') return 0;
    if (value[0] == '1' && value[1] == '.' && value[2] != '\\0') {
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

""");
        sb.append("""

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

""");
        sb.append("""

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

""");
        sb.append(renderBootstrapDiscoverySupport());
        sb.append("""

""");
        return sb.toString();
    }

    private String renderBootstrapDiscoverySupport() {
        return """
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
    value[length - 2u] = '\\0';
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

""";
    }
}
