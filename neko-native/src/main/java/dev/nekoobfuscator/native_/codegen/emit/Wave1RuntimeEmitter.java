package dev.nekoobfuscator.native_.codegen.emit;

public final class Wave1RuntimeEmitter {
    public String renderBootstrapRuntimeSupport() {
        StringBuilder sb = new StringBuilder();
        sb.append("""

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

""");
        sb.append("""

static inline JNIEnv* neko_current_env(void) {
    JNIEnv *env = NULL;
    jint env_status;
    if (g_neko_java_vm == NULL) return NULL;
    env_status = (*g_neko_java_vm)->GetEnv(g_neko_java_vm, (void**)&env, JNI_VERSION_1_6);
    if (env_status != JNI_OK || env == NULL) return NULL;
    return env;
}

static inline void neko_sleep_millis(jlong millis) {
    struct timespec req;
    struct timespec rem;
    if (millis <= 0) return;
    req.tv_sec = (time_t)(millis / 1000);
    req.tv_nsec = (long)((millis % 1000) * 1000000L);
    while (nanosleep(&req, &rem) != 0 && errno == EINTR) {
        req = rem;
    }
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
    if (g_neko_vm_layout.off_thread_pending_exception == g_neko_vm_layout.off_java_thread_jni_environment) return;
    *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_pending_exception) = oop;
    if (g_neko_vm_layout.off_thread_exception_oop >= 0
        && g_neko_vm_layout.off_thread_exception_oop != g_neko_vm_layout.off_java_thread_jni_environment) {
        *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_exception_oop) = oop;
    }
    if (g_neko_vm_layout.off_thread_exception_pc >= 0
        && g_neko_vm_layout.off_thread_exception_pc != g_neko_vm_layout.off_java_thread_jni_environment) {
        *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_exception_pc) = NULL;
    }
}

static inline void neko_clear_pending_exception(void *thread) {
    if (thread == NULL || g_neko_vm_layout.off_thread_pending_exception < 0) return;
    if (g_neko_vm_layout.off_thread_pending_exception == g_neko_vm_layout.off_java_thread_jni_environment) return;
    *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_pending_exception) = NULL;
    if (g_neko_vm_layout.off_thread_exception_oop >= 0
        && g_neko_vm_layout.off_thread_exception_oop != g_neko_vm_layout.off_java_thread_jni_environment) {
        *(void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_exception_oop) = NULL;
    }
    if (g_neko_vm_layout.off_thread_exception_pc >= 0
        && g_neko_vm_layout.off_thread_exception_pc != g_neko_vm_layout.off_java_thread_jni_environment) {
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

""");
        sb.append("""

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
""");
        return sb.toString();
    }

    public String renderExceptionBridgeSupport() {
        return """

#ifndef NEKO_FAST_INLINE
#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 199901L
#define NEKO_FAST_INLINE static inline
#else
#define NEKO_FAST_INLINE static
#endif
#endif

NEKO_FAST_INLINE JNIEnv* neko_env_from_thread(void *thread) {
    if (thread == NULL || g_neko_vm_layout.off_java_thread_jni_environment < 0) return NULL;
    return (JNIEnv*)((uint8_t*)thread + g_neko_vm_layout.off_java_thread_jni_environment);
}

NEKO_FAST_INLINE jboolean neko_exception_bridge_ready(void *thread) {
    void *anchor_sp;
    if (thread == NULL) return JNI_FALSE;
    if (g_neko_vm_layout.off_java_thread_last_Java_sp < 0) return JNI_FALSE;
    anchor_sp = __atomic_load_n((void**)((uint8_t*)thread + g_neko_vm_layout.off_java_thread_last_Java_sp), __ATOMIC_ACQUIRE);
    return anchor_sp != NULL ? JNI_TRUE : JNI_FALSE;
}

static jint neko_raise_cached_with_trace(void *thread, jthrowable cached, jboolean fill_trace_now) {
    JNIEnv *env;
    if (thread == NULL || cached == NULL) {
        return neko_raise_cached_pending(thread, cached);
    }
    if (!neko_exception_bridge_ready(thread)) {
        return neko_raise_cached_pending(thread, cached);
    }
    env = neko_env_from_thread(thread);
    if (env == NULL) {
        return neko_raise_cached_pending(thread, cached);
    }
    if (fill_trace_now == JNI_TRUE && g_neko_JVM_FillInStackTrace != NULL) {
        g_neko_JVM_FillInStackTrace(env, (jobject)cached);
    }
    return (*env)->Throw(env, cached);
}
""";
    }

    public String renderRuntimeSupport() {
        return """
typedef union {
    jint i;
    jlong j;
    jfloat f;
    jdouble d;
    jobject o;
} neko_slot;

static inline jobject neko_stable_object_for_stack(jobject ref);

#define PUSH_I(v) do { jint __tmp = (jint)(v); stack[sp++].i = __tmp; } while (0)
#define PUSH_L(v) do { jlong __tmp = (jlong)(v); stack[sp].j = __tmp; stack[sp + 1].j = __tmp; sp += 2; } while (0)
#define PUSH_F(v) do { jfloat __tmp = (jfloat)(v); stack[sp++].f = __tmp; } while (0)
#define PUSH_D(v) do { jdouble __tmp = (jdouble)(v); stack[sp].d = __tmp; stack[sp + 1].d = __tmp; sp += 2; } while (0)
#define PUSH_O(v) do { jobject __tmp = (jobject)(v); stack[sp++].o = neko_stable_object_for_stack(__tmp); } while (0)
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
static inline jobject neko_new_local_ref(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 25, jobject, jobject)(env, obj); }
static inline jthrowable neko_exception_occurred(JNIEnv *env) { return NEKO_JNI_FN_PTR(env, 15, jthrowable)(env); }
static inline void neko_exception_clear(JNIEnv *env) { NEKO_JNI_FN_PTR(env, 17, void)(env); }
static inline jint neko_push_local_frame(JNIEnv *env, jint capacity) { return NEKO_JNI_FN_PTR(env, 19, jint, jint)(env, capacity); }
static inline jobject neko_pop_local_frame(JNIEnv *env, jobject result) { return NEKO_JNI_FN_PTR(env, 20, jobject, jobject)(env, result); }
static inline JNIEnv* neko_current_env(void);

typedef struct {
    uintptr_t raw_oop;
    jobject global_ref;
} neko_ref_cache_entry;

#define NEKO_REF_CACHE_CAPACITY (1u << 21)
static neko_ref_cache_entry g_neko_ref_cache[NEKO_REF_CACHE_CAPACITY];

static jclass g_neko_rt_cls_stack_trace_element = NULL;
static jmethodID g_neko_rt_mid_stack_trace_element_init = NULL;
static jclass g_neko_rt_cls_boolean = NULL;
static jclass g_neko_rt_cls_byte = NULL;
static jclass g_neko_rt_cls_character = NULL;
static jclass g_neko_rt_cls_short = NULL;
static jclass g_neko_rt_cls_integer = NULL;
static jclass g_neko_rt_cls_long = NULL;
static jclass g_neko_rt_cls_float = NULL;
static jclass g_neko_rt_cls_double = NULL;
static jclass g_neko_rt_cls_string = NULL;
static jclass g_neko_rt_cls_method_handle = NULL;
static jmethodID g_neko_rt_mid_boolean_value_of = NULL;
static jmethodID g_neko_rt_mid_byte_value_of = NULL;
static jmethodID g_neko_rt_mid_character_value_of = NULL;
static jmethodID g_neko_rt_mid_short_value_of = NULL;
static jmethodID g_neko_rt_mid_integer_value_of = NULL;
static jmethodID g_neko_rt_mid_long_value_of = NULL;
static jmethodID g_neko_rt_mid_float_value_of = NULL;
static jmethodID g_neko_rt_mid_double_value_of = NULL;
static jmethodID g_neko_rt_mid_boolean_unbox = NULL;
static jmethodID g_neko_rt_mid_byte_unbox = NULL;
static jmethodID g_neko_rt_mid_character_unbox = NULL;
static jmethodID g_neko_rt_mid_short_unbox = NULL;
static jmethodID g_neko_rt_mid_integer_unbox = NULL;
static jmethodID g_neko_rt_mid_long_unbox = NULL;
static jmethodID g_neko_rt_mid_float_unbox = NULL;
static jmethodID g_neko_rt_mid_double_unbox = NULL;
static jmethodID g_neko_rt_mid_string_value_of_object = NULL;
static jmethodID g_neko_rt_mid_string_concat = NULL;
static jmethodID g_neko_rt_mid_method_handle_invoke_with_arguments = NULL;
static jstring g_neko_rt_str_null = NULL;

static inline uint32_t neko_ref_cache_hash(uintptr_t raw) {
    raw ^= raw >> 33;
    raw *= (uintptr_t)0xff51afd7ed558ccdULL;
    raw ^= raw >> 33;
    return (uint32_t)raw;
}

static jobject neko_ref_cache_lookup(jobject raw_ref) {
    uintptr_t raw = (uintptr_t)raw_ref;
    uint32_t start;
    if (raw == 0u) return NULL;
    start = neko_ref_cache_hash(raw) & (NEKO_REF_CACHE_CAPACITY - 1u);
    for (uint32_t i = 0; i < 64u; i++) {
        uint32_t pos = (start + i) & (NEKO_REF_CACHE_CAPACITY - 1u);
        uintptr_t key = __atomic_load_n(&g_neko_ref_cache[pos].raw_oop, __ATOMIC_ACQUIRE);
        if (key == raw) return __atomic_load_n(&g_neko_ref_cache[pos].global_ref, __ATOMIC_ACQUIRE);
        if (key == 0u) return NULL;
    }
    return NULL;
}

static void neko_ref_cache_store_owned_global(JNIEnv *env, jobject global, void *raw_oop) {
    uintptr_t raw = (uintptr_t)raw_oop;
    uint32_t start;
    if (env == NULL || global == NULL || raw == 0u) {
        if (env != NULL && global != NULL) neko_delete_global_ref(env, global);
        return;
    }
    if (neko_ref_cache_lookup((jobject)raw) != NULL) {
        neko_delete_global_ref(env, global);
        return;
    }
    start = neko_ref_cache_hash(raw) & (NEKO_REF_CACHE_CAPACITY - 1u);
    for (uint32_t i = 0; i < 64u; i++) {
        uint32_t pos = (start + i) & (NEKO_REF_CACHE_CAPACITY - 1u);
        uintptr_t key = __atomic_load_n(&g_neko_ref_cache[pos].raw_oop, __ATOMIC_ACQUIRE);
        if (key == raw) {
            neko_delete_global_ref(env, global);
            return;
        }
        if (key == 0u) {
            uintptr_t expected = 0u;
            if (__atomic_compare_exchange_n(&g_neko_ref_cache[pos].raw_oop, &expected, raw, JNI_FALSE, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
                __atomic_store_n(&g_neko_ref_cache[pos].global_ref, global, __ATOMIC_RELEASE);
                return;
            }
        }
    }
    neko_delete_global_ref(env, global);
}

static void neko_ref_cache_store_alias(void *raw_oop, jobject global) {
    uintptr_t raw = (uintptr_t)raw_oop;
    uint32_t start;
    if (global == NULL || raw == 0u) return;
    if (neko_ref_cache_lookup((jobject)raw) != NULL) return;
    start = neko_ref_cache_hash(raw) & (NEKO_REF_CACHE_CAPACITY - 1u);
    for (uint32_t i = 0; i < 64u; i++) {
        uint32_t pos = (start + i) & (NEKO_REF_CACHE_CAPACITY - 1u);
        uintptr_t key = __atomic_load_n(&g_neko_ref_cache[pos].raw_oop, __ATOMIC_ACQUIRE);
        if (key == raw) return;
        if (key == 0u) {
            uintptr_t expected = 0u;
            if (__atomic_compare_exchange_n(&g_neko_ref_cache[pos].raw_oop, &expected, raw, JNI_FALSE, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
                __atomic_store_n(&g_neko_ref_cache[pos].global_ref, global, __ATOMIC_RELEASE);
                return;
            }
        }
    }
}

static void neko_ref_cache_store(JNIEnv *env, jobject ref, void *raw_oop) {
    jobject global;
    if (env == NULL || ref == NULL || raw_oop == NULL) return;
    if (neko_ref_cache_lookup((jobject)raw_oop) != NULL) return;
    global = neko_new_global_ref(env, ref);
    if (global == NULL) return;
    neko_ref_cache_store_owned_global(env, global, raw_oop);
}

static jobject neko_ref_cache_promote_raw(JNIEnv *env, void *raw_oop) {
    void *slot;
    jobject cached;
    jobject global;
    if (env == NULL || raw_oop == NULL) return NULL;
    cached = neko_ref_cache_lookup((jobject)raw_oop);
    if (cached != NULL) return cached;
    slot = raw_oop;
    global = neko_new_global_ref(env, (jobject)&slot);
    if (global == NULL) return NULL;
    neko_ref_cache_store_owned_global(env, global, raw_oop);
    return global;
}

static inline void *volatile *neko_decode_jni_global_ref_slot(jobject global_ref, int jdk_feature) {
    uintptr_t cell;
    uintptr_t tag;
    if (global_ref == NULL) return NULL;
    cell = (uintptr_t)global_ref;
    tag = cell & (uintptr_t)0x3u;
    if (jdk_feature >= 21 && tag != 0u) cell -= tag;
    return (void *volatile *)cell;
}

static inline void* neko_decode_oop_from_jni_handle(jobject ref) {
    void *volatile *slot = neko_decode_jni_global_ref_slot(ref, g_neko_vm_layout.java_spec_version);
    uintptr_t value;
    if (slot == NULL) return NULL;
    value = (uintptr_t)__atomic_load_n(slot, __ATOMIC_ACQUIRE);
    if (value == 0u) return NULL;
    if ((g_neko_vm_layout.narrow_oop_shift > 0 || g_neko_vm_layout.narrow_oop_base != 0u)
        && value <= (uintptr_t)UINT32_MAX) {
        return (void*)(g_neko_vm_layout.narrow_oop_base + ((uintptr_t)((u4)value) << g_neko_vm_layout.narrow_oop_shift));
    }
    return (void*)value;
}

static inline jboolean neko_plausible_oop_value(void *oop) {
    uintptr_t value = (uintptr_t)oop;
    uintptr_t base;
    uintptr_t delta;
    uintptr_t shifted;
    if (value < (uintptr_t)4096u) return JNI_FALSE;
    if ((value & (uintptr_t)0x7u) != 0u) return JNI_FALSE;
    if (g_neko_vm_layout.heap_low != 0u && g_neko_vm_layout.heap_high > g_neko_vm_layout.heap_low) {
        return value >= g_neko_vm_layout.heap_low && value < g_neko_vm_layout.heap_high ? JNI_TRUE : JNI_FALSE;
    }
    if (g_neko_vm_layout.narrow_oop_shift > 0 || g_neko_vm_layout.narrow_oop_base != 0u) {
        base = g_neko_vm_layout.narrow_oop_base;
        if (value < base) return JNI_FALSE;
        delta = value - base;
        if (g_neko_vm_layout.narrow_oop_shift > 0) {
            uintptr_t align_mask = (((uintptr_t)1u) << g_neko_vm_layout.narrow_oop_shift) - 1u;
            if ((delta & align_mask) != 0u) return JNI_FALSE;
            shifted = delta >> g_neko_vm_layout.narrow_oop_shift;
        } else {
            shifted = delta;
        }
        return shifted <= (uintptr_t)UINT32_MAX ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_TRUE;
}

static inline void* neko_oop_from_direct_handle(jobject ref) {
    void *oop;
    if (ref == NULL) return NULL;
    oop = neko_decode_oop_from_jni_handle(ref);
    return neko_plausible_oop_value(oop) ? oop : NULL;
}

static inline void* neko_oop_from_direct_narrow(jobject ref) {
    uintptr_t value = (uintptr_t)ref;
    if (value == 0u) return NULL;
    if ((g_neko_vm_layout.narrow_oop_shift > 0 || g_neko_vm_layout.narrow_oop_base != 0u)
        && value <= (uintptr_t)UINT32_MAX) {
        return (void*)(g_neko_vm_layout.narrow_oop_base + ((uintptr_t)((u4)value) << g_neko_vm_layout.narrow_oop_shift));
    }
    return NULL;
}

static inline void* neko_oop_from_bound_global_ref(jobject ref) {
    void *oop;
    if (ref == NULL) return NULL;
    oop = neko_decode_oop_from_jni_handle(ref);
    if (oop != NULL) neko_ref_cache_store_alias(oop, ref);
    return oop;
}

static inline void* neko_oop_from_jni_ref(jobject ref) {
    JNIEnv *env;
    jobject global = NULL;
    jobject stable_ref;
    void *oop;
    if (ref == NULL) return NULL;
    env = neko_current_env();
    if (env != NULL) global = neko_new_global_ref(env, ref);
    stable_ref = global != NULL ? global : ref;
    oop = neko_decode_oop_from_jni_handle(stable_ref);
    if (global != NULL) {
        neko_ref_cache_store_owned_global(env, global, oop);
    } else if (env == NULL) {
        neko_ref_cache_store(env, ref, oop);
    }
    return oop;
}
static inline jobject neko_stable_ref_from_jni_ref(jobject ref) {
    JNIEnv *env;
    jobject global;
    void *oop;
    if (ref == NULL) return NULL;
    env = neko_current_env();
    if (env == NULL) return ref;
    global = neko_new_global_ref(env, ref);
    if (global == NULL) return NULL;
    oop = neko_decode_oop_from_jni_handle(global);
    if (oop != NULL) {
        neko_ref_cache_store_owned_global(env, global, oop);
        neko_ref_cache_store_alias(oop, global);
    }
    return global;
}
static inline jboolean neko_probably_raw_oop_ref(jobject ref) {
    return neko_plausible_oop_value((void*)ref);
}
static inline jobject neko_jni_ref_for_call(jobject ref, oop *slot) {
    jobject cached;
    jobject stable;
    void *narrow;
    if (ref == NULL || slot == NULL) return ref;
    narrow = neko_oop_from_direct_narrow(ref);
    if (narrow != NULL) ref = (jobject)narrow;
    if (!neko_probably_raw_oop_ref(ref)) return ref;
    cached = neko_ref_cache_lookup(ref);
    if (cached != NULL) {
        void *current = neko_decode_oop_from_jni_handle(cached);
        if (current == (void*)ref) {
            neko_ref_cache_store_alias(current, cached);
            return cached;
        }
    }
    stable = neko_stable_object_for_stack(ref);
    if (stable != NULL && !neko_probably_raw_oop_ref(stable)) return stable;
    *slot = (oop)ref;
    return (jobject)slot;
}
static inline jobject neko_stable_object_for_stack(jobject ref) {
    JNIEnv *env;
    oop slot;
    jobject local;
    void *narrow;
    if (ref == NULL) return NULL;
    narrow = neko_oop_from_direct_narrow(ref);
    if (narrow != NULL) ref = (jobject)narrow;
    if (!neko_probably_raw_oop_ref(ref)) return ref;
    env = neko_current_env();
    if (env == NULL) return ref;
    slot = (oop)ref;
    local = neko_new_local_ref(env, (jobject)&slot);
    return local != NULL ? local : ref;
}
static inline jobject neko_oop_for_direct(jobject ref) {
    jobject cached;
    void *narrow;
    void *decoded;
    if (ref == NULL) return NULL;
    narrow = neko_oop_from_direct_narrow(ref);
    if (narrow != NULL) return (jobject)narrow;
    if (neko_probably_raw_oop_ref(ref)) {
        cached = neko_ref_cache_lookup(ref);
        if (cached != NULL) {
            void *current = neko_decode_oop_from_jni_handle(cached);
            if (current == (void*)ref) {
                neko_ref_cache_store_alias(current, cached);
                return (jobject)current;
            }
        }
        return ref;
    }
    decoded = neko_oop_from_direct_handle(ref);
    if (decoded != NULL) return (jobject)decoded;
    return (jobject)neko_oop_from_jni_ref(ref);
}
static inline void neko_sync_jni_exception(JNIEnv *env) {
    void *thread;
    jthrowable pending;
    void *oop;
    if (env == NULL) return;
    pending = neko_exception_occurred(env);
    if (pending == NULL) return;
    thread = neko_get_current_thread();
    oop = neko_oop_from_jni_ref((jobject)pending);
    if (thread != NULL && neko_pending_exception(thread) == NULL && oop != NULL) neko_set_pending_exception(thread, oop);
}
static inline jboolean neko_is_same_object(JNIEnv *env, jobject a, jobject b) { return NEKO_JNI_FN_PTR(env, 24, jboolean, jobject, jobject)(env, a, b); }
static inline jobject neko_new_weak_global_ref(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 226, jobject, jobject)(env, obj); }
static inline void neko_delete_weak_global_ref(JNIEnv *env, jobject obj) { NEKO_JNI_FN_PTR(env, 227, void, jobject)(env, obj); }
static inline jobject neko_alloc_object(JNIEnv *env, jclass cls) { return NEKO_JNI_FN_PTR(env, 27, jobject, jclass)(env, cls); }
static inline jobject neko_new_object_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 30, jobject, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jobject neko_call_object_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { jobject r = NEKO_JNI_FN_PTR(env, 36, jobject, jobject, jmethodID, const jvalue*)(env, obj, mid, args); neko_sync_jni_exception(env); return r; }
static inline jboolean neko_call_boolean_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 39, jboolean, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jbyte neko_call_byte_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 42, jbyte, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jchar neko_call_char_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 45, jchar, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jshort neko_call_short_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 48, jshort, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jint neko_call_int_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 51, jint, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jlong neko_call_long_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 54, jlong, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jfloat neko_call_float_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 57, jfloat, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jdouble neko_call_double_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 60, jdouble, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline void neko_call_void_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 63, void, jobject, jmethodID, const jvalue*)(env, obj, mid, args); neko_sync_jni_exception(env); }
static inline jobject neko_call_nonvirtual_object_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { jobject r = NEKO_JNI_FN_PTR(env, 66, jobject, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); neko_sync_jni_exception(env); return r; }
static inline jboolean neko_call_nonvirtual_boolean_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 69, jboolean, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jbyte neko_call_nonvirtual_byte_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 72, jbyte, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jchar neko_call_nonvirtual_char_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 75, jchar, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jshort neko_call_nonvirtual_short_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 78, jshort, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jint neko_call_nonvirtual_int_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 81, jint, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jlong neko_call_nonvirtual_long_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 84, jlong, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jfloat neko_call_nonvirtual_float_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 87, jfloat, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jdouble neko_call_nonvirtual_double_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 90, jdouble, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline void neko_call_nonvirtual_void_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 93, void, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); neko_sync_jni_exception(env); }
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
static inline jobject neko_call_static_object_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { jobject r = NEKO_JNI_FN_PTR(env, 116, jobject, jclass, jmethodID, const jvalue*)(env, cls, mid, args); neko_sync_jni_exception(env); return r; }
static inline jboolean neko_call_static_boolean_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 119, jboolean, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jbyte neko_call_static_byte_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 122, jbyte, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jchar neko_call_static_char_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 125, jchar, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jshort neko_call_static_short_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 128, jshort, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jint neko_call_static_int_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 131, jint, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jlong neko_call_static_long_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 134, jlong, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jfloat neko_call_static_float_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 137, jfloat, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jdouble neko_call_static_double_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 140, jdouble, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline void neko_call_static_void_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 143, void, jclass, jmethodID, const jvalue*)(env, cls, mid, args); neko_sync_jni_exception(env); }
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

typedef struct {
    const char *owner;
    const char *method;
} neko_native_frame;

#define NEKO_NATIVE_FRAME_STACK_MAX 256
static _Thread_local neko_native_frame g_neko_native_frames[NEKO_NATIVE_FRAME_STACK_MAX];
static _Thread_local uint8_t g_neko_native_local_frame_pushed[NEKO_NATIVE_FRAME_STACK_MAX];
static _Thread_local int g_neko_native_frame_depth = 0;

static inline void neko_native_frame_push(const char *owner, const char *method) {
    int depth = g_neko_native_frame_depth;
    JNIEnv *env;
    uint8_t local_frame_pushed = 0u;
    if (depth < 0) depth = 0;
    if (depth < NEKO_NATIVE_FRAME_STACK_MAX) {
        env = neko_current_env();
        if (env != NULL && neko_push_local_frame(env, 65536) == 0) {
            local_frame_pushed = 1u;
        }
        g_neko_native_frames[depth].owner = owner;
        g_neko_native_frames[depth].method = method;
        g_neko_native_local_frame_pushed[depth] = local_frame_pushed;
    }
    g_neko_native_frame_depth = depth + 1;
}

static inline void neko_native_frame_pop(void) {
    if (g_neko_native_frame_depth > 0) {
        int depth = g_neko_native_frame_depth - 1;
        if (depth >= 0 && depth < NEKO_NATIVE_FRAME_STACK_MAX) {
            if (g_neko_native_local_frame_pushed[depth] != 0u) {
                JNIEnv *env = neko_current_env();
                if (env != NULL) (void)neko_pop_local_frame(env, NULL);
            }
            g_neko_native_local_frame_pushed[depth] = 0u;
        }
        g_neko_native_frame_depth = depth;
    }
}

static jstring neko_native_frame_owner_string(JNIEnv *env, const char *owner) {
    char dotted[512];
    size_t i;
    if (owner == NULL) return neko_new_string_utf(env, "");
    for (i = 0; owner[i] != '\\0' && i + 1u < sizeof(dotted); i++) {
        dotted[i] = owner[i] == '/' ? '.' : owner[i];
    }
    dotted[i] = '\\0';
    return neko_new_string_utf(env, dotted);
}

static jobjectArray neko_native_stack_trace_array(JNIEnv *env) {
    int depth;
    int count;
    jclass ste_cls;
    jmethodID ctor;
    jobjectArray array;
    if (env == NULL) return NULL;
    ste_cls = g_neko_rt_cls_stack_trace_element;
    if (ste_cls == NULL) return NULL;
    ctor = g_neko_rt_mid_stack_trace_element_init;
    if (ctor == NULL) return NULL;
    depth = g_neko_native_frame_depth;
    if (depth < 0) depth = 0;
    count = depth > NEKO_NATIVE_FRAME_STACK_MAX ? NEKO_NATIVE_FRAME_STACK_MAX : depth;
    array = neko_new_object_array(env, count, ste_cls, NULL);
    if (array == NULL) return NULL;
    for (int i = 0; i < count; i++) {
        int frame_index = depth - 1 - i;
        const char *owner = frame_index >= 0 && frame_index < NEKO_NATIVE_FRAME_STACK_MAX ? g_neko_native_frames[frame_index].owner : NULL;
        const char *method = frame_index >= 0 && frame_index < NEKO_NATIVE_FRAME_STACK_MAX ? g_neko_native_frames[frame_index].method : NULL;
        jvalue args[4];
        jobject element;
        args[0].l = neko_native_frame_owner_string(env, owner);
        args[1].l = neko_new_string_utf(env, method == NULL ? "" : method);
        args[2].l = NULL;
        args[3].i = -1;
        element = neko_new_object_a(env, ste_cls, ctor, args);
        neko_sync_jni_exception(env);
        if (element == NULL || neko_exception_occurred(env) != NULL) return array;
        neko_set_object_array_element(env, array, i, element);
        neko_sync_jni_exception(env);
        if (neko_exception_occurred(env) != NULL) return array;
    }
    return array;
}

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
    out[len] = '\\0';
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
    jclass cls = g_neko_rt_cls_boolean;
    jmethodID mid = g_neko_rt_mid_boolean_value_of;
    jvalue args[1]; args[0].z = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_byte(JNIEnv *env, jbyte v) {
    jclass cls = g_neko_rt_cls_byte;
    jmethodID mid = g_neko_rt_mid_byte_value_of;
    jvalue args[1]; args[0].b = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_char(JNIEnv *env, jchar v) {
    jclass cls = g_neko_rt_cls_character;
    jmethodID mid = g_neko_rt_mid_character_value_of;
    jvalue args[1]; args[0].c = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_short(JNIEnv *env, jshort v) {
    jclass cls = g_neko_rt_cls_short;
    jmethodID mid = g_neko_rt_mid_short_value_of;
    jvalue args[1]; args[0].s = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_int(JNIEnv *env, jint v) {
    jclass cls = g_neko_rt_cls_integer;
    jmethodID mid = g_neko_rt_mid_integer_value_of;
    jvalue args[1]; args[0].i = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_long(JNIEnv *env, jlong v) {
    jclass cls = g_neko_rt_cls_long;
    jmethodID mid = g_neko_rt_mid_long_value_of;
    jvalue args[1]; args[0].j = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_float(JNIEnv *env, jfloat v) {
    jclass cls = g_neko_rt_cls_float;
    jmethodID mid = g_neko_rt_mid_float_value_of;
    jvalue args[1]; args[0].f = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_double(JNIEnv *env, jdouble v) {
    jclass cls = g_neko_rt_cls_double;
    jmethodID mid = g_neko_rt_mid_double_value_of;
    jvalue args[1]; args[0].d = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jboolean neko_unbox_boolean(JNIEnv *env, jobject obj) {
    jmethodID mid = g_neko_rt_mid_boolean_unbox;
    return neko_call_boolean_method_a(env, obj, mid, NULL);
}
static jbyte neko_unbox_byte(JNIEnv *env, jobject obj) {
    jmethodID mid = g_neko_rt_mid_byte_unbox;
    return neko_call_byte_method_a(env, obj, mid, NULL);
}
static jchar neko_unbox_char(JNIEnv *env, jobject obj) {
    jmethodID mid = g_neko_rt_mid_character_unbox;
    return neko_call_char_method_a(env, obj, mid, NULL);
}
static jshort neko_unbox_short(JNIEnv *env, jobject obj) {
    jmethodID mid = g_neko_rt_mid_short_unbox;
    return neko_call_short_method_a(env, obj, mid, NULL);
}
static jint neko_unbox_int(JNIEnv *env, jobject obj) {
    jmethodID mid = g_neko_rt_mid_integer_unbox;
    return neko_call_int_method_a(env, obj, mid, NULL);
}
static jlong neko_unbox_long(JNIEnv *env, jobject obj) {
    jmethodID mid = g_neko_rt_mid_long_unbox;
    return neko_call_long_method_a(env, obj, mid, NULL);
}
static jfloat neko_unbox_float(JNIEnv *env, jobject obj) {
    jmethodID mid = g_neko_rt_mid_float_unbox;
    return neko_call_float_method_a(env, obj, mid, NULL);
}
static jdouble neko_unbox_double(JNIEnv *env, jobject obj) {
    jmethodID mid = g_neko_rt_mid_double_unbox;
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
            memcpy(buf, start, len); buf[len] = '\\0';
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

static jobject neko_lookup_for_owner_class(JNIEnv *env, jclass ownerClass) {
    jclass lookupClass = neko_find_class(env, "java/lang/invoke/MethodHandles$Lookup");
    jmethodID mid = neko_get_method_id(env, lookupClass, "in", "(Ljava/lang/Class;)Ljava/lang/invoke/MethodHandles$Lookup;");
    jobject impl = neko_impl_lookup(env);
    jvalue args[1];
    if (ownerClass == NULL) return NULL;
    args[0].l = ownerClass;
    return neko_call_object_method_a(env, impl, mid, args);
}

static jobject neko_lookup_for_class(JNIEnv *env, const char *owner) {
    return neko_lookup_for_owner_class(env, neko_find_class(env, owner));
}

static jobject neko_owner_class_loader(JNIEnv *env, const char *owner) {
    jclass ownerClass;
    jclass classClass;
    jmethodID mid;
    if (env == NULL || owner == NULL) return NULL;
    ownerClass = neko_find_class(env, owner);
    if (ownerClass == NULL) return NULL;
    classClass = neko_find_class(env, "java/lang/Class");
    if (classClass == NULL) return NULL;
    mid = neko_get_method_id(env, classClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    if (mid == NULL) return NULL;
    return neko_call_object_method_a(env, ownerClass, mid, NULL);
}

static jobject neko_method_type_from_descriptor_with_loader(JNIEnv *env, const char *desc, jobject loader) {
    jclass mtClass = neko_find_class(env, "java/lang/invoke/MethodType");
    jmethodID mid = neko_get_static_method_id(env, mtClass, "fromMethodDescriptorString", "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
    jvalue args[2];
    args[0].l = neko_new_string_utf(env, desc);
    args[1].l = loader;
    return neko_call_static_object_method_a(env, mtClass, mid, args);
}

static jobject neko_method_type_from_descriptor(JNIEnv *env, const char *desc) {
    return neko_method_type_from_descriptor_with_loader(env, desc, NULL);
}

static jobject neko_method_type_from_descriptor_for_owner(JNIEnv *env, const char *owner, const char *desc) {
    jobject loader = neko_owner_class_loader(env, owner);
    return neko_method_type_from_descriptor_with_loader(env, desc, loader);
}

static jobjectArray neko_bootstrap_parameter_array_for_owner(JNIEnv *env, const char *owner, const char *bsm_desc) {
    jobject mt = neko_method_type_from_descriptor_for_owner(env, owner, bsm_desc);
    jclass mtClass = neko_find_class(env, "java/lang/invoke/MethodType");
    jmethodID mid = neko_get_method_id(env, mtClass, "parameterArray", "()[Ljava/lang/Class;");
    return (jobjectArray)neko_call_object_method_a(env, mt, mid, NULL);
}

static jobjectArray neko_bootstrap_parameter_array(JNIEnv *env, const char *bsm_desc) {
    jobject mt = neko_method_type_from_descriptor(env, bsm_desc);
    jclass mtClass = neko_find_class(env, "java/lang/invoke/MethodType");
    jmethodID mid = neko_get_method_id(env, mtClass, "parameterArray", "()[Ljava/lang/Class;");
    return (jobjectArray)neko_call_object_method_a(env, mt, mid, NULL);
}

static jobject neko_invoke_bootstrap(JNIEnv *env, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray invoke_args) {
    jclass bsmClass = neko_find_class(env, bsm_owner);
    jobjectArray paramTypes = neko_bootstrap_parameter_array_for_owner(env, bsm_owner, bsm_desc);
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
            jobject mt = neko_method_type_from_descriptor_for_owner(env, owner, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 6: {
            jobject mt = neko_method_type_from_descriptor_for_owner(env, owner, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 7: {
            jobject mt = neko_method_type_from_descriptor_for_owner(env, owner, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findSpecial", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[4]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt; args[3].l = ownerClass;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 8: {
            jobject mt = neko_method_type_from_descriptor_for_owner(env, owner, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findConstructor", "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[2]; args[0].l = ownerClass; args[1].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 9: {
            jobject mt = neko_method_type_from_descriptor_for_owner(env, owner, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        default:
            return NULL;
    }
}

static jobject neko_call_mh(JNIEnv *env, jobject mh, jobjectArray args) {
    jvalue callArgs[1];
    jmethodID mid = g_neko_rt_mid_method_handle_invoke_with_arguments;
    if (mid == NULL) return NULL;
    callArgs[0].l = args;
    return neko_call_object_method_a(env, mh, mid, callArgs);
}

static jstring neko_string_null(JNIEnv *env) {
    (void)env;
    return (jstring)neko_oop_from_bound_global_ref((jobject)g_neko_rt_str_null);
}

static jstring neko_string_concat2(JNIEnv *env, jobject left, jobject right) {
    oop left_slot = NULL;
    oop right_slot = NULL;
    jobject left_ref;
    jobject right_ref;
    jobject concat_result;
    jclass cls = g_neko_rt_cls_string;
    jmethodID valueOf = g_neko_rt_mid_string_value_of_object;
    jmethodID concat = g_neko_rt_mid_string_concat;
    jvalue valueOfArgs[1];
    if (cls == NULL || valueOf == NULL || concat == NULL) return NULL;
    left_ref = neko_jni_ref_for_call(left, &left_slot);
    right_ref = neko_jni_ref_for_call(right, &right_slot);
    valueOfArgs[0].l = left_ref;
    jstring lhs = (jstring)neko_call_static_object_method_a(env, cls, valueOf, valueOfArgs);
    neko_sync_jni_exception(env);
    valueOfArgs[0].l = right_ref;
    jstring rhs = (jstring)neko_call_static_object_method_a(env, cls, valueOf, valueOfArgs);
    neko_sync_jni_exception(env);
    jvalue concatArgs[1];
    concatArgs[0].l = rhs;
    concat_result = neko_call_object_method_a(env, lhs, concat, concatArgs);
    neko_sync_jni_exception(env);
    return (jstring)neko_oop_from_jni_ref(concat_result);
}

static jstring neko_string_concat_string(JNIEnv *env, jobject left, jstring right) {
    oop left_slot = NULL;
    oop right_slot = NULL;
    jobject right_ref;
    jobject concat_result;
    jmethodID concat = g_neko_rt_mid_string_concat;
    if (concat == NULL) return NULL;
    jstring lhs = (jstring)neko_jni_ref_for_call(left == NULL ? (jobject)neko_string_null(env) : left, &left_slot);
    jvalue concatArgs[1];
    right_ref = neko_jni_ref_for_call(right == NULL ? (jobject)neko_string_null(env) : (jobject)right, &right_slot);
    concatArgs[0].l = right_ref;
    concat_result = neko_call_object_method_a(env, lhs, concat, concatArgs);
    neko_sync_jni_exception(env);
    return (jstring)neko_oop_from_jni_ref(concat_result);
}

static jobject neko_resolve_indy(JNIEnv *env, jlong site_id, const char *caller_owner, const char *indy_name, const char *indy_desc, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray static_args) {
    jobject cached = neko_get_indy_mh(site_id);
    if (cached != NULL) return cached;

    jobjectArray paramTypes = neko_bootstrap_parameter_array_for_owner(env, bsm_owner, bsm_desc);
    jsize paramCount = neko_get_array_length(env, (jarray)paramTypes);
    jclass objClass = neko_find_class(env, "java/lang/Object");
    jobjectArray invokeArgs = neko_new_object_array(env, paramCount, objClass, NULL);
    neko_set_object_array_element(env, invokeArgs, 0, neko_lookup_for_class(env, caller_owner));
    neko_set_object_array_element(env, invokeArgs, 1, neko_new_string_utf(env, indy_name));
    neko_set_object_array_element(env, invokeArgs, 2, neko_method_type_from_descriptor_for_owner(env, caller_owner, indy_desc));
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
    jobjectArray paramTypes = neko_bootstrap_parameter_array_for_owner(env, bsm_owner, bsm_desc);
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

""";
    }

    public String renderHotSpotSupport() {
        return """
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

NEKO_FAST_INLINE void* neko_handle_oop(jobject handle) {
    uintptr_t raw_handle;
    uintptr_t tag;
    void *volatile *slot;
    if (handle == NULL) return NULL;
    raw_handle = (uintptr_t)handle;
    tag = raw_handle & (uintptr_t)0x3u;
    if (g_neko_vm_layout.java_spec_version >= 21 && tag != 0u) raw_handle -= tag;
    slot = (void *volatile *)(uintptr_t)raw_handle;
    return slot == NULL ? NULL : *slot;
}

NEKO_FAST_INLINE void* neko_current_oop_for_fast_access(jobject ref) {
    jobject cached;
    if (ref == NULL) return NULL;
    if (neko_probably_raw_oop_ref(ref)) {
        cached = neko_ref_cache_lookup(ref);
        if (cached != NULL) {
            void *current = neko_handle_oop(cached);
            if (current == (void*)ref) return current;
        }
        return (void*)ref;
    }
    return neko_handle_oop(ref);
}

NEKO_FAST_INLINE jint neko_fast_array_length(JNIEnv *env, jarray arr) {
    oop arrSlot = NULL;
    jarray arrRef = (jarray)neko_jni_ref_for_call((jobject)arr, &arrSlot);
    return (jint)neko_get_array_length(env, arrRef);
}

NEKO_FAST_INLINE void* neko_raw_oop_klass(void *oop_ref) {
    char *oop;
    char *klass_addr;
    if (oop_ref == NULL || g_neko_vm_layout.use_compact_object_headers) return NULL;
    oop = (char*)neko_current_oop_for_fast_access((jobject)oop_ref);
    if (oop == NULL) return NULL;
    klass_addr = oop + sizeof(uintptr_t);
    if (g_neko_vm_layout.narrow_klass_shift > 0 || g_neko_vm_layout.narrow_klass_base != 0u) {
        u4 narrow = *(u4*)klass_addr;
        return narrow == 0u ? NULL : neko_decode_klass_pointer(narrow);
    }
    return *(void**)klass_addr;
}

NEKO_FAST_INLINE char* neko_raw_array_element_addr(void *array_oop_ref, jint idx) {
    char *array_oop;
    void *array_klass;
    uint32_t layout_helper;
    uint32_t header_bytes;
    uint32_t log2_elem;
    if (array_oop_ref == NULL || idx < 0) return NULL;
    array_oop = (char*)neko_current_oop_for_fast_access((jobject)array_oop_ref);
    array_klass = neko_raw_oop_klass(array_oop_ref);
    if (array_oop == NULL || array_klass == NULL || g_neko_vm_layout.off_klass_layout_helper < 0) return NULL;
    layout_helper = *(uint32_t*)((uint8_t*)array_klass + g_neko_vm_layout.off_klass_layout_helper);
    header_bytes = neko_lh_header_size(layout_helper);
    log2_elem = neko_lh_log2_element(layout_helper);
    if (header_bytes == 0u || log2_elem > 4u) return NULL;
    return array_oop + header_bytes + (((ptrdiff_t)idx) << log2_elem);
}

NEKO_FAST_INLINE jint neko_raw_array_length(void *array_oop_ref) {
    void *array_oop;
    void *array_klass;
    uint32_t layout_helper;
    uint32_t header_bytes;
    array_oop = neko_current_oop_for_fast_access((jobject)array_oop_ref);
    array_klass = neko_raw_oop_klass(array_oop_ref);
    if (array_oop == NULL || array_klass == NULL || g_neko_vm_layout.off_klass_layout_helper < 0) return 0;
    layout_helper = *(uint32_t*)((uint8_t*)array_klass + g_neko_vm_layout.off_klass_layout_helper);
    header_bytes = neko_lh_header_size(layout_helper);
    if (header_bytes < sizeof(jint)) return 0;
    return *(jint*)((char*)array_oop + ((ptrdiff_t)header_bytes - (ptrdiff_t)sizeof(jint)));
}

NEKO_FAST_INLINE jint neko_raw_string_length(void *string_oop_ref) {
    void *string_oop;
    void *value_array;
    jint array_len;
    uint8_t coder = 1u;
    string_oop = neko_current_oop_for_fast_access((jobject)string_oop_ref);
    if (string_oop == NULL || g_neko_vm_layout.off_string_value < 0) return 0;
    value_array = neko_load_heap_oop_at(string_oop, g_neko_vm_layout.off_string_value, JNI_FALSE);
    array_len = neko_raw_array_length(value_array);
    if (g_neko_vm_layout.off_string_coder >= 0) {
        coder = *(uint8_t*)((uint8_t*)string_oop + g_neko_vm_layout.off_string_coder);
    }
    return coder == 0u ? array_len : (array_len / 2);
}

NEKO_FAST_INLINE jboolean neko_receiver_key_supported(void) {
    return JNI_FALSE;
}

NEKO_FAST_INLINE uintptr_t neko_receiver_key(jobject obj) {
    char *oop;
    if (obj == NULL || !neko_receiver_key_supported()) return (uintptr_t)0;
    oop = (char*)neko_current_oop_for_fast_access(obj);
    if (oop == NULL) return (uintptr_t)0;
    return (uintptr_t)neko_raw_oop_klass(obj);
}

NEKO_FAST_INLINE void* neko_receiver_klass(jobject obj) {
    if (obj == NULL) return NULL;
    return neko_raw_oop_klass(obj);
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
    return (ret != NULL && ret[1] != '\0') ? ret[1] : 'V';
}

NEKO_FAST_INLINE jboolean neko_icache_returns_declared_object(const char *desc) {
    const char *ret = desc == NULL ? NULL : strrchr(desc, ')');
    return (ret != NULL && strcmp(ret + 1, "Ljava/lang/Object;") == 0) ? JNI_TRUE : JNI_FALSE;
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
        default: { jobject obj = neko_call_object_method_a(env, receiver, mid, args); neko_sync_jni_exception(env); result.l = (jobject)neko_oop_from_jni_ref(obj); break; }
    }
    neko_sync_jni_exception(env);
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
        default: { jobject obj = neko_call_nonvirtual_object_method_a(env, receiver, klass, mid, args); neko_sync_jni_exception(env); result.l = (jobject)neko_oop_from_jni_ref(obj); break; }
    }
    neko_sync_jni_exception(env);
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

static const jvalue* neko_prepare_jni_args_for_call(const char *desc, const jvalue *args, jvalue *prepared, oop *slots, int max_slots) {
    const char *p;
    int arg_index = 0;
    int slot_index = 0;
    if (desc == NULL || args == NULL || prepared == NULL || slots == NULL || max_slots <= 0) return args;
    p = desc;
    if (*p != '(') return args;
    p++;
    while (*p != '\\0' && *p != ')' && arg_index < max_slots) {
        char kind = *p;
        prepared[arg_index] = args[arg_index];
        if (kind == 'L') {
            while (*p != '\\0' && *p != ';') p++;
            if (slot_index < max_slots) prepared[arg_index].l = neko_jni_ref_for_call(args[arg_index].l, &slots[slot_index++]);
        } else if (kind == '[') {
            while (*p == '[') p++;
            if (*p == 'L') while (*p != '\\0' && *p != ';') p++;
            if (slot_index < max_slots) prepared[arg_index].l = neko_jni_ref_for_call(args[arg_index].l, &slots[slot_index++]);
        }
        if (*p != '\\0') p++;
        arg_index++;
    }
    return prepared;
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
    oop receiver_slot = NULL;
    oop arg_slots[16] = {0};
    jvalue prepared_args[16];
    jobject call_receiver;
    const jvalue *call_args;
    uintptr_t receiverKey;
    if (env == NULL || receiver == NULL || fallback_mid == NULL) return result;
    call_receiver = neko_jni_ref_for_call(receiver, &receiver_slot);
    call_args = neko_prepare_jni_args_for_call(meta != NULL ? meta->desc : NULL, args, prepared_args, arg_slots, 16);
    neko_maybe_rescan_cld_liveness();
    if (meta != NULL && meta->translated_class_slot != NULL && meta->translated_stub != NULL) {
        jclass translatedClass = *meta->translated_class_slot;
        void *translatedKlass = translatedClass != NULL ? neko_class_klass_pointer(translatedClass) : NULL;
        void *receiverKlass = translatedKlass != NULL ? neko_receiver_klass(receiver) : NULL;
        if (receiverKlass != NULL && receiverKlass == translatedKlass) {
            return meta->translated_stub(env, receiver, args);
        }
    }
    if (site != NULL && neko_receiver_key_supported()) {
        receiverKey = neko_receiver_key(receiver);
        if (receiverKey != 0 && site->target_kind != NEKO_ICACHE_MEGA) {
            if (receiverKey == site->receiver_key) {
                if (site->target_kind == NEKO_ICACHE_DIRECT_C && site->target != NULL) {
                    return ((neko_icache_direct_stub)site->target)(env, receiver, args);
                }
                if (site->target_kind == NEKO_ICACHE_NONVIRT_MID && site->cached_class != NULL && site->target != NULL) {
                    return neko_icache_call_nonvirtual(env, call_receiver, site->cached_class, (jmethodID)site->target, call_args, meta != NULL ? meta->desc : NULL);
                }
            }
            if (!neko_icache_note_miss(env, site)) {
                jclass exactClass = neko_get_object_class(env, call_receiver);
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
                        result = neko_icache_call_nonvirtual(env, call_receiver, cachedExactClass != NULL ? cachedExactClass : exactClass, exactMid, call_args, meta != NULL ? meta->desc : NULL);
                        neko_delete_local_ref(env, exactClass);
                        return result;
                    }
                    neko_delete_local_ref(env, exactClass);
                }
            }
        }
    }
    if (meta != NULL && meta->translated_class_slot != NULL && meta->translated_stub != NULL) {
        jclass translatedClass = *meta->translated_class_slot;
        if (translatedClass != NULL) {
            jclass exactClass = neko_get_object_class(env, call_receiver);
            if (exactClass != NULL && neko_is_same_object(env, exactClass, translatedClass)) {
                neko_delete_local_ref(env, exactClass);
                return meta->translated_stub(env, receiver, args);
            }
            if (exactClass != NULL) neko_delete_local_ref(env, exactClass);
        }
    }
    return neko_icache_call_virtual(env, call_receiver, fallback_mid, call_args, meta != NULL ? meta->desc : NULL);
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

    private void appendPrimitiveFieldHelpers(StringBuilder sb, char desc, String cType, String wrapperStem) {
        sb.append("NEKO_FAST_INLINE ").append(cType).append(" neko_fast_get_").append(desc)
            .append("_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset) {\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_handle_oop(obj);\n")
            .append("        if (oop != NULL) return *(").append(cType).append("*)(oop + offset);\n")
            .append("    }\n")
            .append("    return neko_get_").append(wrapperStem).append("_field(env, obj, fid);\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE void neko_fast_set_").append(desc)
            .append("_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, ").append(cType).append(" value) {\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_handle_oop(obj);\n")
            .append("        if (oop != NULL) { *(").append(cType).append("*)(oop + offset) = value; return; }\n")
            .append("    }\n")
            .append("    neko_set_").append(wrapperStem).append("_field(env, obj, fid, value);\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE ").append(cType).append(" neko_fast_get_static_").append(desc)
            .append("_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_handle_oop(staticBase);\n")
            .append("        if (oop != NULL) return *(").append(cType).append("*)(oop + offset);\n")
            .append("    }\n")
            .append("    return neko_get_static_").append(wrapperStem).append("_field(env, cls, fid);\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE void neko_fast_set_static_").append(desc)
            .append("_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, ").append(cType).append(" value) {\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_handle_oop(staticBase);\n")
            .append("        if (oop != NULL) { *(").append(cType).append("*)(oop + offset) = value; return; }\n")
            .append("    }\n")
            .append("    neko_set_static_").append(wrapperStem).append("_field(env, cls, fid, value);\n")
            .append("}\n\n");
    }

    private void appendPrimitiveArrayHelpers(StringBuilder sb, String prefix, String cType, String wrapperStem, String kindConstant) {
        sb.append("NEKO_FAST_INLINE ").append(cType).append(" neko_fast_").append(prefix)
            .append("aload(jarray arr, jint idx) {\n")
            .append("    char *addr = neko_raw_array_element_addr((void*)arr, idx);\n")
            .append("    if (addr == NULL) return (").append(cType).append(")0;\n")
            .append("    return *(").append(cType).append("*)addr;\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE void neko_fast_").append(prefix)
            .append("astore(jarray arr, jint idx, ").append(cType).append(" value) {\n")
            .append("    char *addr = neko_raw_array_element_addr((void*)arr, idx);\n")
            .append("    if (addr != NULL) *(").append(cType).append("*)addr = value;\n")
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
}
