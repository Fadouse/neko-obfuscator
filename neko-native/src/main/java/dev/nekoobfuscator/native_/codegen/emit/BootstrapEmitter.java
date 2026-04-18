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

typedef struct NekoVmSymbols {
#define NEKO_VM_SYMBOL_FIELD(name) void* name;
    NEKO_REQUIRED_VM_SYMBOLS(NEKO_VM_SYMBOL_FIELD)
#undef NEKO_VM_SYMBOL_FIELD
} NekoVmSymbols;

typedef struct NekoVmLayout {
    int java_spec_version;
    size_t method_size;
    size_t instance_klass_size;
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
    ptrdiff_t off_klass_layout_helper;
    ptrdiff_t off_klass_name;
    ptrdiff_t off_klass_java_mirror;
    ptrdiff_t off_class_klass;
    ptrdiff_t off_instance_klass_constants;
    ptrdiff_t off_instance_klass_fields;
    ptrdiff_t off_instance_klass_java_fields_count;
    ptrdiff_t off_instance_klass_init_state;
    ptrdiff_t off_instance_klass_java_mirror;
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
    jboolean has_narrow_oop_base;
    jboolean has_narrow_oop_shift;
    jboolean has_narrow_klass_base;
    jboolean has_narrow_klass_shift;
    jboolean wave4a_disabled;
    jboolean use_compact_object_headers;
} NekoVmLayout;

NekoVmSymbols g_neko_vm_symbols = {0};
NekoVmLayout g_neko_vm_layout = {0};

static JavaVM *g_neko_java_vm = NULL;
static jvmtiEnv *g_neko_jvmti = NULL;
static jrawMonitorID g_neko_manifest_lock = NULL;
static uint32_t g_neko_manifest_match_count = 0u;
static int g_neko_debug_enabled = -1;
static int g_neko_flag_patch_path_logged = 0;
static const char *g_neko_wave4a_unavailable_reason = "uninitialized";
static int g_neko_wave4a_handle_caveat_logged = 0;
static jboolean g_neko_use_compact_object_headers = JNI_FALSE;

#if defined(_WIN32)
static HMODULE g_neko_libjvm_handle = NULL;
#else
static void *g_neko_libjvm_handle = NULL;
#endif

static int neko_debug_enabled(void) {
    if (g_neko_debug_enabled < 0) {
        const char *value = getenv("NEKO_NATIVE_DEBUG");
        g_neko_debug_enabled = (value != NULL && value[0] != '\\0') ? 1 : 0;
    }
    return g_neko_debug_enabled;
}

static void neko_vlog(FILE *stream, const char *fmt, va_list args) {
    fputs("neko: ", stream);
    vfprintf(stream, fmt, args);
    fputc('\\n', stream);
    fflush(stream);
}

static void neko_debug_log(const char *fmt, ...) {
    va_list args;
    if (!neko_debug_enabled()) return;
    va_start(args, fmt);
    neko_vlog(stderr, fmt, args);
    va_end(args);
}

static void neko_native_debug_log(const char *fmt, ...) {
    va_list args;
    if (!neko_debug_enabled()) return;
    va_start(args, fmt);
    fputs("[neko] ", stderr);
    vfprintf(stderr, fmt, args);
    fputc('\\n', stderr);
    fflush(stderr);
    va_end(args);
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
        neko_native_debug_log("%s=%td (strategy=%c)", label, offset, strategy);
    } else {
        neko_native_debug_log("%s unavailable (strategy=%c)", label, strategy);
    }
}

static void neko_derive_wave2_layout_offsets(JNIEnv *env);
static void neko_resolve_prepared_class_field_sites(JNIEnv *env, jclass klass, const char *owner_internal);
static jboolean neko_prewarm_ldc_sites(JNIEnv *env);
static void neko_log_wave2_ready(void);

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

static const char* neko_validate_wave4a_layout(void) {
    if (g_neko_vm_layout.off_thread_thread_state < 0) return "JavaThread::_thread_state";
    if (g_neko_vm_layout.off_java_thread_last_Java_sp < 0) return "JavaThread::_anchor._last_Java_sp";
    if (g_neko_vm_layout.off_java_thread_last_Java_fp < 0) return "JavaThread::_anchor._last_Java_fp";
    if (g_neko_vm_layout.off_java_thread_last_Java_pc < 0) return "JavaThread::_anchor._last_Java_pc";
    if (g_neko_vm_layout.off_instance_klass_java_mirror < 0) return "InstanceKlass::_java_mirror";
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
    neko_native_debug_log(
        "method_flags_status_offset=%u (strategy=%c)",
        g_neko_vm_layout.off_method_flags_status >= 0 ? (uint32_t)g_neko_vm_layout.off_method_flags_status : 0u,
        g_neko_vm_layout.method_flags_status_strategy
    );
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
    g_neko_vm_layout.off_klass_layout_helper = -1;
    g_neko_vm_layout.off_klass_name = -1;
    g_neko_vm_layout.off_klass_java_mirror = -1;
    g_neko_vm_layout.off_class_klass = -1;
    g_neko_vm_layout.off_instance_klass_constants = -1;
    g_neko_vm_layout.off_instance_klass_fields = -1;
    g_neko_vm_layout.off_instance_klass_java_fields_count = -1;
    g_neko_vm_layout.off_instance_klass_init_state = -1;
    g_neko_vm_layout.off_instance_klass_java_mirror = -1;
    g_neko_vm_layout.off_string_value = -1;
    g_neko_vm_layout.off_string_coder = -1;
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

""");
        sb.append("""

static void neko_resolve_optional_vm_flags(void) {
    void *symbol = neko_resolve_symbol_address("UseCompactObjectHeaders");
    g_neko_use_compact_object_headers = (symbol != NULL && *(const uint8_t*)symbol != 0u) ? JNI_TRUE : JNI_FALSE;
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
    neko_debug_log("libjvm resolved (%u/%u symbols)", resolved, NEKO_REQUIRED_VM_SYMBOL_COUNT);
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
    jclass system_class = NULL;
    jmethodID get_property = NULL;
    jstring key = NULL;
    jstring value = NULL;
    const char *chars = NULL;
    int version = 0;
    if (env == NULL) return 0;
    system_class = neko_find_class(env, "java/lang/System");
    if (system_class == NULL) goto cleanup;
    get_property = neko_get_static_method_id(env, system_class, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    if (get_property == NULL) goto cleanup;
    key = neko_new_string_utf(env, "java.specification.version");
    if (key == NULL) goto cleanup;
    {
        jvalue args[1];
        args[0].l = key;
        value = (jstring)neko_call_static_object_method_a(env, system_class, get_property, args);
    }
    if (value == NULL || neko_exception_check(env)) goto cleanup;
    chars = neko_get_string_utf_chars(env, value);
    if (chars == NULL) goto cleanup;
    version = neko_parse_java_spec_version_text(chars);
cleanup:
    if (chars != NULL) {
        neko_release_string_utf_chars(env, value, chars);
    }
    if (value != NULL) {
        neko_delete_local_ref(env, value);
    }
    if (key != NULL) {
        neko_delete_local_ref(env, key);
    }
    if (system_class != NULL) {
        neko_delete_local_ref(env, system_class);
    }
    if (neko_exception_check(env)) {
        neko_exception_clear(env);
    }
    return version;
}

static void neko_mark_loader_loaded(JNIEnv *env) {
    jclass loader_class;
    jfieldID loaded_field;
    if (env == NULL) return;
    loader_class = neko_find_class(env, "dev/nekoobfuscator/runtime/NekoNativeLoader");
    if (loader_class == NULL) {
        if (neko_exception_check(env)) {
            neko_exception_clear(env);
        }
        return;
    }
    loaded_field = neko_get_static_field_id(env, loader_class, "loaded", "Z");
    if (loaded_field != NULL) {
        neko_set_static_boolean_field(env, loader_class, loaded_field, JNI_TRUE);
    }
    if (neko_exception_check(env)) {
        neko_exception_clear(env);
    }
    neko_delete_local_ref(env, loader_class);
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
    if (g_neko_vm_layout.off_klass_layout_helper < 0) return "Klass::_layout_helper";
    if (g_neko_vm_layout.off_klass_name < 0) return "Klass::_name";
    if (g_neko_vm_layout.off_klass_java_mirror < 0) return "Klass::_java_mirror";
    if (g_neko_vm_layout.off_instance_klass_constants < 0) return "InstanceKlass::_constants";
    if (g_neko_vm_layout.off_instance_klass_fields < 0) return "InstanceKlass::_fields";
    if (g_neko_vm_layout.off_instance_klass_init_state < 0) return "InstanceKlass::_init_state";
    if (g_neko_vm_layout.off_string_value < 0) return "java_lang_String::_value";
    if (g_neko_vm_layout.java_spec_version >= 9 && g_neko_vm_layout.off_string_coder < 0) return "java_lang_String::_coder";
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
        } else if (neko_streq(type_name, "Klass")) {
            if (neko_streq(field_name, "_layout_helper")) g_neko_vm_layout.off_klass_layout_helper = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_name")) g_neko_vm_layout.off_klass_name = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_java_mirror")) g_neko_vm_layout.off_klass_java_mirror = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "java_lang_Class")) {
            if (neko_streq(field_name, "_klass")) g_neko_vm_layout.off_class_klass = (ptrdiff_t)offset;
        } else if (neko_streq(type_name, "InstanceKlass")) {
            if (neko_streq(field_name, "_constants")) g_neko_vm_layout.off_instance_klass_constants = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_fields") || neko_streq(field_name, "_fieldinfo_stream")) g_neko_vm_layout.off_instance_klass_fields = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_java_fields_count")) g_neko_vm_layout.off_instance_klass_java_fields_count = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_init_state")) g_neko_vm_layout.off_instance_klass_init_state = (ptrdiff_t)offset;
            else if (neko_streq(field_name, "_java_mirror")) g_neko_vm_layout.off_instance_klass_java_mirror = (ptrdiff_t)offset;
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
    neko_derive_wave2_layout_offsets(env);
    neko_derive_method_flags_status_offset();
    neko_derive_thread_tlab_top_offset();
    neko_derive_thread_tlab_start_offset();
    neko_derive_thread_tlab_pf_top_offset();
    neko_derive_thread_tlab_end_offset();
    neko_derive_thread_exception_oop_offset();
    neko_derive_thread_exception_pc_offset();
    neko_derive_java_thread_anchor_offset();
    neko_derive_java_thread_jni_environment_offset();
    missing = neko_validate_vm_layout();
    if (missing != NULL) {
        neko_error_log("VMStructs layout incomplete, field %s not found, falling back to throw body", missing);
        return JNI_FALSE;
    }
    neko_configure_wave4a_layout();
    neko_debug_log("VMStructs layout parsed (java_spec_version=%d)", g_neko_vm_layout.java_spec_version);
    return JNI_TRUE;
}

static void neko_jvmti_deallocate(jvmtiEnv *jvmti, void *ptr) {
    if (jvmti != NULL && ptr != NULL) {
        (*jvmti)->Deallocate(jvmti, (unsigned char*)ptr);
    }
}

static void neko_log_jvmti_error(jvmtiEnv *jvmti, const char *stage, jvmtiError error) {
    char *name = NULL;
    if (jvmti != NULL && (*jvmti)->GetErrorName(jvmti, error, &name) == JVMTI_ERROR_NONE && name != NULL) {
        neko_error_log("%s failed: %s (%d), falling back to throw body", stage, name, error);
        neko_jvmti_deallocate(jvmti, name);
        return;
    }
    neko_error_log("%s failed: %d, falling back to throw body", stage, error);
}

static void neko_manifest_lock_enter(void) {
    if (g_neko_jvmti != NULL && g_neko_manifest_lock != NULL) {
        (*g_neko_jvmti)->RawMonitorEnter(g_neko_jvmti, g_neko_manifest_lock);
    }
}

static void neko_manifest_lock_exit(void) {
    if (g_neko_jvmti != NULL && g_neko_manifest_lock != NULL) {
        (*g_neko_jvmti)->RawMonitorExit(g_neko_jvmti, g_neko_manifest_lock);
    }
}

static void neko_record_manifest_match(uint32_t index, void *method_star) {
    const NekoManifestMethod *entry;
    if (method_star == NULL || index >= g_neko_manifest_method_count) return;
    entry = &g_neko_manifest_methods[index];
    neko_manifest_lock_enter();
    if (g_neko_manifest_method_stars[index] == NULL) {
        g_neko_manifest_method_stars[index] = method_star;
        g_neko_manifest_match_count++;
        neko_debug_log("matched Method* for %s.%s%s at %p", entry->owner_internal, entry->method_name, entry->method_desc, method_star);
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

static jboolean neko_install_class_prepare_callback(jvmtiEnv *jvmti) {
    jvmtiEventCallbacks callbacks;
    jvmtiError err;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassPrepare = &neko_class_prepare_cb;
    err = (*jvmti)->SetEventCallbacks(jvmti, &callbacks, (jint)sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
        neko_log_jvmti_error(jvmti, "JVMTI SetEventCallbacks", err);
        return JNI_FALSE;
    }
    err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, NULL);
    if (err != JVMTI_ERROR_NONE) {
        neko_log_jvmti_error(jvmti, "JVMTI SetEventNotificationMode(ClassPrepare)", err);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

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
        neko_native_debug_log("resolved invoke site %s.%s%s -> %p", owner_internal, name, desc, method_star);
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

static jboolean neko_discover_class(JNIEnv *env, jvmtiEnv *jvmti, jclass klass) {
    char *signature = NULL;
    char *owner_internal = NULL;
    jmethodID *methods = NULL;
    jint method_count = 0;
    jvmtiError err;
    uint32_t owner_hash;
    if (g_neko_manifest_method_count == 0u) return JNI_TRUE;
    err = (*jvmti)->GetClassSignature(jvmti, klass, &signature, NULL);
    if (err != JVMTI_ERROR_NONE) {
        neko_log_jvmti_error(jvmti, "JVMTI GetClassSignature", err);
        return JNI_FALSE;
    }
    owner_internal = neko_internal_name_from_signature(signature);
    if (owner_internal == NULL) {
        neko_jvmti_deallocate(jvmti, signature);
        return JNI_TRUE;
    }
    owner_hash = neko_fnv1a32(owner_internal);
    if (!neko_manifest_has_owner(owner_internal, owner_hash)) {
        free(owner_internal);
        neko_jvmti_deallocate(jvmti, signature);
        return JNI_TRUE;
    }
    err = (*jvmti)->GetClassMethods(jvmti, klass, &method_count, &methods);
    if (err != JVMTI_ERROR_NONE) {
        free(owner_internal);
        neko_jvmti_deallocate(jvmti, signature);
        neko_log_jvmti_error(jvmti, "JVMTI GetClassMethods", err);
        return JNI_FALSE;
    }
    for (jint i = 0; i < method_count; i++) {
        char *name = NULL;
        char *desc = NULL;
        uint32_t name_desc_hash;
        err = (*jvmti)->GetMethodName(jvmti, methods[i], &name, &desc, NULL);
        if (err != JVMTI_ERROR_NONE) {
            neko_jvmti_deallocate(jvmti, methods);
            free(owner_internal);
            neko_jvmti_deallocate(jvmti, signature);
            neko_log_jvmti_error(jvmti, "JVMTI GetMethodName", err);
            return JNI_FALSE;
        }
        name_desc_hash = neko_fnv1a32_pair(name, desc);
        for (uint32_t manifest_index = 0; manifest_index < g_neko_manifest_method_count; manifest_index++) {
            const NekoManifestMethod *entry = &g_neko_manifest_methods[manifest_index];
            if (entry->owner_internal == NULL) continue;
            if (entry->owner_hash != owner_hash || entry->name_desc_hash != name_desc_hash) continue;
            if (strcmp(entry->owner_internal, owner_internal) != 0) continue;
            if (strcmp(entry->method_name, name) != 0 || strcmp(entry->method_desc, desc) != 0) continue;
            neko_record_manifest_match(manifest_index, methods[i] == NULL ? NULL : *(void**)methods[i]);
        }
        neko_resolve_discovered_invoke_sites(owner_internal, name, desc, methods[i] == NULL ? NULL : *(void**)methods[i]);
        neko_jvmti_deallocate(jvmti, desc);
        neko_jvmti_deallocate(jvmti, name);
    }
    neko_jvmti_deallocate(jvmti, methods);
    neko_resolve_prepared_class_field_sites(env, klass, owner_internal);
    free(owner_internal);
    neko_jvmti_deallocate(jvmti, signature);
    (void)env;
    return JNI_TRUE;
}

static jboolean neko_discover_class_via_reflection(JNIEnv *env, jvmtiEnv *jvmti, jclass klass, const char *owner_internal) {
    static jclass g_neko_class_cls = NULL;
    static jmethodID g_neko_get_declared_methods = NULL;
    jobjectArray reflected_methods = NULL;
    uint32_t owner_hash;
    jsize method_count;
    if (env == NULL || jvmti == NULL || klass == NULL || owner_internal == NULL) return JNI_FALSE;
    reflected_methods = (jobjectArray)neko_call_object_method_a(
        env,
        klass,
        NEKO_ENSURE_METHOD_ID(
            g_neko_get_declared_methods,
            env,
            NEKO_ENSURE_CLASS(g_neko_class_cls, env, "java/lang/Class"),
            "getDeclaredMethods",
            "()[Ljava/lang/reflect/Method;"
        ),
        NULL
    );
    if (reflected_methods == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) {
            neko_exception_clear(env);
        }
        neko_error_log("reflection discovery failed for %s, falling back to throw body", owner_internal);
        return JNI_FALSE;
    }
    owner_hash = neko_fnv1a32(owner_internal);
    method_count = neko_get_array_length(env, (jarray)reflected_methods);
    for (jsize i = 0; i < method_count; i++) {
        jobject reflected = neko_get_object_array_element(env, reflected_methods, i);
        jmethodID reflected_mid;
        char *name = NULL;
        char *desc = NULL;
        uint32_t name_desc_hash;
        jvmtiError err;
        if (reflected == NULL) continue;
        reflected_mid = (*env)->FromReflectedMethod(env, reflected);
        if (reflected_mid == NULL) {
            neko_delete_local_ref(env, reflected);
            continue;
        }
        err = (*jvmti)->GetMethodName(jvmti, reflected_mid, &name, &desc, NULL);
        if (err != JVMTI_ERROR_NONE) {
            neko_delete_local_ref(env, reflected);
            neko_delete_local_ref(env, reflected_methods);
            neko_log_jvmti_error(jvmti, "JVMTI GetMethodName(reflection)", err);
            return JNI_FALSE;
        }
        name_desc_hash = neko_fnv1a32_pair(name, desc);
        for (uint32_t manifest_index = 0; manifest_index < g_neko_manifest_method_count; manifest_index++) {
            const NekoManifestMethod *entry = &g_neko_manifest_methods[manifest_index];
            if (entry->owner_internal == NULL) continue;
            if (entry->owner_hash != owner_hash || entry->name_desc_hash != name_desc_hash) continue;
            if (strcmp(entry->owner_internal, owner_internal) != 0) continue;
            if (strcmp(entry->method_name, name) != 0 || strcmp(entry->method_desc, desc) != 0) continue;
            neko_record_manifest_match(manifest_index, reflected_mid == NULL ? NULL : *(void**)reflected_mid);
        }
        neko_resolve_discovered_invoke_sites(owner_internal, name, desc, reflected_mid == NULL ? NULL : *(void**)reflected_mid);
        neko_jvmti_deallocate(jvmti, desc);
        neko_jvmti_deallocate(jvmti, name);
        neko_delete_local_ref(env, reflected);
    }
    neko_delete_local_ref(env, reflected_methods);
    return JNI_TRUE;
}

static void JNICALL neko_class_prepare_cb(jvmtiEnv *jvmti, JNIEnv *env, jthread thread, jclass klass) {
    (void)thread;
    if (!neko_discover_class(env, jvmti, klass)) {
        neko_error_log("ClassPrepare discovery failed");
    }
}

static jboolean neko_init_jvmti(JavaVM *vm, jvmtiEnv *jvmti) {
    jvmtiCapabilities capabilities;
    jvmtiError err;
    (void)vm;
    memset(&capabilities, 0, sizeof(capabilities));
    capabilities.can_get_bytecodes = 0;
    capabilities.can_generate_all_class_hook_events = 1;
    capabilities.can_generate_compiled_method_load_events = 0;
    capabilities.can_redefine_classes = 0;
    err = (*jvmti)->AddCapabilities(jvmti, &capabilities);
    if (err != JVMTI_ERROR_NONE) {
        neko_log_jvmti_error(jvmti, "JVMTI AddCapabilities", err);
        return JNI_FALSE;
    }
    err = (*jvmti)->CreateRawMonitor(jvmti, "neko_manifest_lock", &g_neko_manifest_lock);
    if (err != JVMTI_ERROR_NONE) {
        neko_log_jvmti_error(jvmti, "JVMTI CreateRawMonitor", err);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean neko_discover_loaded_classes(JNIEnv *env, jvmtiEnv *jvmti) {
    jclass *classes = NULL;
    jint class_count = 0;
    jvmtiError err = (*jvmti)->GetLoadedClasses(jvmti, &class_count, &classes);
    if (err != JVMTI_ERROR_NONE) {
        neko_log_jvmti_error(jvmti, "JVMTI GetLoadedClasses", err);
        return JNI_FALSE;
    }
    for (jint i = 0; i < class_count; i++) {
        if (!neko_discover_class(env, jvmti, classes[i])) {
            neko_jvmti_deallocate(jvmti, classes);
            return JNI_FALSE;
        }
    }
    neko_jvmti_deallocate(jvmti, classes);
    return JNI_TRUE;
}

static jboolean neko_discover_manifest_owners(JNIEnv *env, jvmtiEnv *jvmti) {
    for (uint32_t i = 0; i < g_neko_manifest_owner_count; i++) {
        const char *owner = g_neko_manifest_owners[i];
        jclass klass;
        if (owner == NULL) continue;
        klass = neko_load_class_noinit(env, owner);
        if (klass == NULL || neko_exception_check(env)) {
            if (neko_exception_check(env)) {
                neko_exception_clear(env);
            }
            neko_error_log("failed to load manifest owner %s for discovery, falling back to throw body", owner);
            return JNI_FALSE;
        }
        if (!neko_discover_class_via_reflection(env, jvmti, klass, owner)) {
            neko_delete_local_ref(env, klass);
            return JNI_FALSE;
        }
        neko_resolve_prepared_class_field_sites(env, klass, owner);
        neko_delete_local_ref(env, klass);
    }
    return JNI_TRUE;
}

""";
    }
}
