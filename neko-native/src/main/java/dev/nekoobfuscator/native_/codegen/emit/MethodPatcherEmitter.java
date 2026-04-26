package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Emits the runtime VMStructs walk that discovers Method* field offsets
 * across HotSpot versions, plus the {@code neko_patch_method_entry} routine
 * that swaps {@code _i2i_entry} / {@code _from_interpreted_entry} /
 * {@code _from_compiled_entry} to per-signature trampolines and ORs the
 * no-compile flags so the JIT can't recompile around the patch.
 *
 * Discovery is fully native: dlsym + {@code /proc/self/maps} fallback for
 * libjvm, then VMStructs / VMTypes / VMIntConstants walks. No JVM helper
 * methods are used.
 */
public final class MethodPatcherEmitter {

    public String render() {
        return """
/* === Method* layout discovery + entry patcher === */
#include <dlfcn.h>

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

#define NEKO_PATCH_DEBUG (getenv("NEKO_PATCH_DEBUG") != NULL)
#define NEKO_PATCH_LOG(fmt, ...) do { if (NEKO_PATCH_DEBUG) { fprintf(stderr, "[neko-patch] " fmt "\\n", ##__VA_ARGS__); fflush(stderr); } } while (0)

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

static void* neko_resolve_libjvm_handle(void) {
#if defined(_WIN32)
    HMODULE hjvm = GetModuleHandleA("jvm.dll");
    if (hjvm == NULL) hjvm = LoadLibraryA("jvm.dll");
    return (void*)hjvm;
#elif defined(__linux__) || defined(__APPLE__)
    if (dlsym(RTLD_DEFAULT, "gHotSpotVMStructs") != NULL) return (void*)(uintptr_t)0x1u;
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

static int neko_detect_java_spec_version_from_env(JNIEnv *env) {
    static jclass cls = NULL;
    static jmethodID mid = NULL;
    if (env == NULL) return 0;
    cls = NEKO_ENSURE_CLASS(cls, env, "java/lang/System");
    if (cls == NULL) return 0;
    mid = NEKO_ENSURE_STATIC_METHOD_ID(mid, env, cls, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    if (mid == NULL) return 0;
    jstring key = neko_new_string_utf(env, "java.specification.version");
    if (key == NULL) return 0;
    jvalue args[1]; args[0].l = key;
    jstring value = (jstring)neko_call_static_object_method_a(env, cls, mid, args);
    neko_delete_local_ref(env, key);
    if (value == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        return 0;
    }
    int result = 0;
    const char *chars = neko_get_string_utf_chars(env, value);
    if (chars != NULL) {
        result = (strncmp(chars, "1.", 2) == 0) ? atoi(chars + 2) : atoi(chars);
        neko_release_string_utf_chars(env, value, chars);
    }
    neko_delete_local_ref(env, value);
    return result;
}

static jboolean neko_walk_vm_structs(void *jvm) {
    void *vmstructs = neko_dlsym(jvm, "gHotSpotVMStructs");
    int *type_off  = (int*)neko_dlsym(jvm, "gHotSpotVMStructEntryTypeNameOffset");
    int *field_off = (int*)neko_dlsym(jvm, "gHotSpotVMStructEntryFieldNameOffset");
    int *offset_off = (int*)neko_dlsym(jvm, "gHotSpotVMStructEntryOffsetOffset");
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
        if (type_name == NULL && field_name == NULL) break;
        if (neko_streq_safe(type_name, "Method")) {
            if (neko_streq_safe(field_name, "_access_flags")) g_neko_method_layout.off_method_access_flags = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_code")) g_neko_method_layout.off_method_code = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_i2i_entry")) g_neko_method_layout.off_method_i2i_entry = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_from_interpreted_entry")) g_neko_method_layout.off_method_from_interpreted_entry = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_from_compiled_entry")) g_neko_method_layout.off_method_from_compiled_entry = (ptrdiff_t)off_value;
            else if (neko_streq_safe(field_name, "_flags") || neko_streq_safe(field_name, "_flags._status") || neko_streq_safe(field_name, "_flags._flags")) {
                g_neko_method_layout.off_method_flags_status = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "Thread") || neko_streq_safe(type_name, "JavaThread")) {
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
            } else if (neko_streq_safe(field_name, "_anchor._last_Java_sp")
                    || neko_streq_safe(field_name, "_last_Java_sp")) {
                g_neko_method_layout.off_thread_last_Java_sp_direct = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_anchor._last_Java_fp")
                    || neko_streq_safe(field_name, "_last_Java_fp")) {
                g_neko_method_layout.off_thread_last_Java_fp_direct = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_anchor._last_Java_pc")
                    || neko_streq_safe(field_name, "_last_Java_pc")) {
                g_neko_method_layout.off_thread_last_Java_pc_direct = (ptrdiff_t)off_value;
            }
        } else if (neko_streq_safe(type_name, "JavaFrameAnchor")) {
            if (neko_streq_safe(field_name, "_last_Java_sp")) {
                g_neko_method_layout.off_frame_anchor_sp = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_last_Java_fp")) {
                g_neko_method_layout.off_frame_anchor_fp = (ptrdiff_t)off_value;
            } else if (neko_streq_safe(field_name, "_last_Java_pc")) {
                g_neko_method_layout.off_frame_anchor_pc = (ptrdiff_t)off_value;
            }
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
    }
}

static void* neko_jmethodid_to_method_star(jmethodID mid) {
    if (mid == NULL) return NULL;
    return *(void**)mid;
}

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
    g_neko_method_layout.java_spec_version = neko_detect_java_spec_version_from_env(env);
    void *jvm = neko_resolve_libjvm_handle();
    NEKO_PATCH_LOG("layout_init: jdk=%d libjvm=%p", g_neko_method_layout.java_spec_version, jvm);
    if (jvm == NULL) return JNI_FALSE;
    if (!neko_walk_vm_structs(jvm)) { NEKO_PATCH_LOG("walk_vm_structs failed"); return JNI_FALSE; }
    (void)neko_walk_vm_types(jvm);
    neko_walk_vm_int_constants(jvm);
    if (g_neko_method_layout.access_not_c1_compilable == 0u) g_neko_method_layout.access_not_c1_compilable = NEKO_ACC_NOT_C1_COMPILABLE_FALLBACK;
    if (g_neko_method_layout.access_not_c2_compilable == 0u) g_neko_method_layout.access_not_c2_compilable = NEKO_ACC_NOT_C2_COMPILABLE_FALLBACK;
    if (g_neko_method_layout.access_not_osr_compilable == 0u) g_neko_method_layout.access_not_osr_compilable = NEKO_ACC_NOT_OSR_COMPILABLE_FALLBACK;
    if (!neko_resolve_jnihandles(jvm)) {
        NEKO_PATCH_LOG("JNIHandles symbols not resolvable; dispatch will use jobject fallback");
    }
    if (g_neko_method_layout.off_method_access_flags < 0
        || g_neko_method_layout.off_method_code < 0
        || g_neko_method_layout.off_method_i2i_entry < 0
        || g_neko_method_layout.off_method_from_interpreted_entry < 0
        || g_neko_method_layout.off_method_from_compiled_entry < 0) {
        NEKO_PATCH_LOG("missing required Method offset; patcher disabled");
        return JNI_FALSE;
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
    g_neko_method_layout.usable = JNI_TRUE;
    return JNI_TRUE;
}

static jboolean neko_apply_no_compile_flags(void *method_star) {
    if (g_neko_method_layout.java_spec_version >= 21 && g_neko_method_layout.off_method_flags_status >= 0) {
        uint32_t mask = (g_neko_method_layout.method_flag_not_c1_compilable
            | g_neko_method_layout.method_flag_not_c2_compilable
            | g_neko_method_layout.method_flag_not_osr_compilable);
        uint32_t *slot = (uint32_t*)((uint8_t*)method_star + g_neko_method_layout.off_method_flags_status);
        if (mask != 0u) __atomic_fetch_or(slot, mask, __ATOMIC_SEQ_CST);
    }
    {
        uint32_t mask = g_neko_method_layout.access_not_c1_compilable
            | g_neko_method_layout.access_not_c2_compilable
            | g_neko_method_layout.access_not_osr_compilable;
        size_t width = g_neko_method_layout.access_flags_size == 0 ? sizeof(uint32_t) : g_neko_method_layout.access_flags_size;
        void *addr = (uint8_t*)method_star + g_neko_method_layout.off_method_access_flags;
        if (width == 4) __atomic_fetch_or((uint32_t*)addr, mask, __ATOMIC_SEQ_CST);
        else if (width == 2) __atomic_fetch_or((uint16_t*)addr, (uint16_t)mask, __ATOMIC_SEQ_CST);
    }
    return JNI_TRUE;
}

/* Called from naked-asm trampoline when the polling word indicates a safepoint
 * is requested. We can't easily reach HotSpot's SafepointMechanism::block from
 * here without C++ glue, so we go through a JNI no-op (MonitorEnter on a sentinel)
 * which forces the JVM through its safepoint check. */
__attribute__((visibility("hidden"))) void neko_handle_safepoint_poll(void) {
    JNIEnv *env = NULL;
    if (g_neko_java_vm == NULL) return;
    if ((*g_neko_java_vm)->GetEnv(g_neko_java_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) return;
    /* GetVersion is the cheapest JNI call that takes us through the
     * thread-in-native -> thread-in-Java transition with safepoint poll. */
    (void)(*env)->GetVersion(env);
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
    void *i2i = g_neko_sig_table[entry->signature_id].i2i;
    void *c2i = g_neko_sig_table[entry->signature_id].c2i;
    __atomic_store_n((void**)((uint8_t*)method_star + g_neko_method_layout.off_method_i2i_entry), i2i, __ATOMIC_RELEASE);
    __atomic_store_n((void**)((uint8_t*)method_star + g_neko_method_layout.off_method_from_interpreted_entry), i2i, __ATOMIC_RELEASE);
    __atomic_store_n((void**)((uint8_t*)method_star + g_neko_method_layout.off_method_from_compiled_entry), c2i, __ATOMIC_RELEASE);
    NEKO_PATCH_LOG("patched %s.%s%s sig=%u method=%p", entry->owner_internal, entry->method_name, entry->method_desc, entry->signature_id, method_star);
    return JNI_TRUE;
}

""";
    }
}
