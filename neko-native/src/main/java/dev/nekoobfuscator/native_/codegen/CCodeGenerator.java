package dev.nekoobfuscator.native_.codegen;

import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CCodeGenerator {
    @SuppressWarnings("unused")
    private final SymbolTableGenerator symbols;
    private final LinkedHashMap<String, Integer> classSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> methodSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> fieldSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> ownerBindIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, OwnerResolution> ownerResolutions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> icacheMethodIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheSiteRef> icacheSites = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheDirectStubRef> icacheDirectStubs = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheMetaRef> icacheMetas = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> manifestMethodIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<ManifestFieldSiteRef>> manifestFieldSites = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<ManifestInvokeSiteRef>> manifestInvokeSites = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<ManifestLdcSiteRef>> manifestLdcSites = new LinkedHashMap<>();
    private final LinkedHashSet<String> manifestOwnerInternals = new LinkedHashSet<>();
    private final StringBuilder output = new StringBuilder();
    private static final String TEMPLATE_C_PART_1 = """
#if defined(__linux__)
#define _GNU_SOURCE
#endif
#include "neko_native.h"
#include <jvmti.h>
#include <stdint.h>
#include <stddef.h>
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
static jclass g_cls_2 = NULL;   // pack/tests/basics/inner/Exec
static jclass g_cls_3 = NULL;   // pack/tests/basics/sub/flo
static jclass g_cls_4 = NULL;   // pack/tests/reflects/counter/Countee
static jclass g_cls_5 = NULL;   // pack/tests/reflects/field/FObject

#define NEKO_ENSURE_CLASS(slot, env, name) ((slot) != NULL ? (slot) : ((slot) = (jclass)neko_new_global_ref((env), neko_find_class((env), (name)))))
#define NEKO_ENSURE_STRING(slot, env, utf) ((slot) != NULL ? (slot) : ((slot) = (jstring)neko_new_global_ref((env), neko_new_string_utf((env), (utf)))))
#define NEKO_ENSURE_METHOD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_method_id((env), (cls), (name), (desc))))
#define NEKO_ENSURE_STATIC_METHOD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_static_method_id((env), (cls), (name), (desc))))
#define NEKO_ENSURE_FIELD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_field_id((env), (cls), (name), (desc))))
#define NEKO_ENSURE_STATIC_FIELD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_static_field_id((env), (cls), (name), (desc))))

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
static inline jint neko_throw_new(JNIEnv *env, jclass cls, const char *msg) { return NEKO_JNI_FN_PTR(env, 14, jint, jclass, const char*)(env, cls, msg); }
static inline jthrowable neko_exception_occurred(JNIEnv *env) { return NEKO_JNI_FN_PTR(env, 15, jthrowable)(env); }
static inline void neko_exception_clear(JNIEnv *env) { NEKO_JNI_FN_PTR(env, 17, void)(env); }
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

""";
    private static final String TEMPLATE_C_PART_2 = """
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

""";
    private static final String TEMPLATE_C_PART_3 = """
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

""";
    private static final String TEMPLATE_C_PART_4 = """
static inline void neko_set_short_array_region(JNIEnv *env, jshortArray arr, jsize start, jsize len, const jshort *buf) { NEKO_JNI_FN_PTR(env, 210, void, jshortArray, jsize, jsize, const jshort*)(env, arr, start, len, buf); }
static inline void neko_set_int_array_region(JNIEnv *env, jintArray arr, jsize start, jsize len, const jint *buf) { NEKO_JNI_FN_PTR(env, 211, void, jintArray, jsize, jsize, const jint*)(env, arr, start, len, buf); }
static inline void neko_set_long_array_region(JNIEnv *env, jlongArray arr, jsize start, jsize len, const jlong *buf) { NEKO_JNI_FN_PTR(env, 212, void, jlongArray, jsize, jsize, const jlong*)(env, arr, start, len, buf); }
static inline void neko_set_float_array_region(JNIEnv *env, jfloatArray arr, jsize start, jsize len, const jfloat *buf) { NEKO_JNI_FN_PTR(env, 213, void, jfloatArray, jsize, jsize, const jfloat*)(env, arr, start, len, buf); }
static inline void neko_set_double_array_region(JNIEnv *env, jdoubleArray arr, jsize start, jsize len, const jdouble *buf) { NEKO_JNI_FN_PTR(env, 214, void, jdoubleArray, jsize, jsize, const jdouble*)(env, arr, start, len, buf); }
static inline jint neko_monitor_enter(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 217, jint, jobject)(env, obj); }
static inline jint neko_monitor_exit(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 218, jint, jobject)(env, obj); }
static inline jboolean neko_exception_check(JNIEnv *env) { return NEKO_JNI_FN_PTR(env, 228, jboolean)(env); }

static char* neko_dotted_class_name(const char *internalName) {
    size_t len = strlen(internalName);
    char *out = (char*)malloc(len + 1u);
    if (out == NULL) return NULL;
    for (size_t i = 0; i < len; i++) out[i] = internalName[i] == '/' ? '.' : internalName[i];
    out[len] = '\\0';
    return out;
}

static jclass neko_load_class_noinit(JNIEnv *env, const char *internalName) {
    char *dotted = neko_dotted_class_name(internalName);
    if (dotted == NULL) return NULL;
    jclass clClass = neko_find_class(env, "java/lang/ClassLoader");
    jmethodID getSystem = neko_get_static_method_id(env, clClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject loader = neko_call_static_object_method_a(env, clClass, getSystem, NULL);
    jclass classClass = neko_find_class(env, "java/lang/Class");
    jmethodID forName = neko_get_static_method_id(env, classClass, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    jvalue args[3];
    args[0].l = neko_new_string_utf(env, dotted);
    args[1].z = JNI_FALSE;
    args[2].l = loader;
    free(dotted);
    return (jclass)neko_call_static_object_method_a(env, classClass, forName, args);
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

""";
    private static final String TEMPLATE_C_PART_5 = """
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

""";
    private static final String TEMPLATE_C_PART_6 = """
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

""";
    private static final String TEMPLATE_C_PART_7 = """
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
    for (jint i = 0; i < dims[0]; i++) {
        jobject sub = neko_multi_new_array(env, num_dims - 1, dims + 1, desc + 1);
        neko_set_object_array_element(env, arr, i, sub);
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
    if (handle == NULL) return NULL;
    return (void*)handle;
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


""";
    private static final String TEMPLATE_C_PART_8 = """
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
                if (exactClass != NULL && !neko_exception_check(env)) {
                    jclass translatedClass = (meta != NULL && meta->translated_class_slot != NULL) ? *meta->translated_class_slot : NULL;
                    if (translatedClass != NULL && meta != NULL && meta->translated_stub != NULL && neko_is_same_object(env, exactClass, translatedClass)) {
                        jclass cachedExactClass = (jclass)neko_new_global_ref(env, exactClass);
                        if (neko_exception_check(env)) {
                            neko_exception_clear(env);
                            cachedExactClass = NULL;
                        }
                        neko_icache_store_direct(env, site, receiverKey, cachedExactClass, (void*)meta->translated_stub);
                        neko_delete_local_ref(env, exactClass);
                        return meta->translated_stub(env, receiver, args);
                    }
                    jmethodID exactMid = neko_get_method_id(env, exactClass, meta != NULL ? meta->name : NULL, meta != NULL ? meta->desc : NULL);
                    if (exactMid != NULL && !neko_exception_check(env)) {
                        jclass cachedExactClass = (jclass)neko_new_global_ref(env, exactClass);
                        if (neko_exception_check(env)) {
                            neko_exception_clear(env);
                            cachedExactClass = NULL;
                        }
                        if (cachedExactClass != NULL) {
                            neko_icache_store_nonvirt(env, site, receiverKey, cachedExactClass, exactMid);
                        }
                        result = neko_icache_call_nonvirtual(env, receiver, cachedExactClass != NULL ? cachedExactClass : exactClass, exactMid, args, meta != NULL ? meta->desc : NULL);
                        neko_delete_local_ref(env, exactClass);
                        return result;
                    }
                    if (neko_exception_check(env)) neko_exception_clear(env);
                    neko_delete_local_ref(env, exactClass);
                } else if (neko_exception_check(env)) {
                    neko_exception_clear(env);
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


""";
    private static final String TEMPLATE_C_PART_9 = """
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


""";
    private static final String TEMPLATE_C_PART_10 = """
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

""";
    private static final String TEMPLATE_C_PART_11 = """
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
#define NEKO_MANIFEST_STORAGE_COUNT 9u

#define NEKO_SIGNATURE_STORAGE_COUNT 2u
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
    jclass *owner_class_slot;
    uint8_t is_static;
    uint8_t is_reference;
    uint8_t is_volatile;
    uint8_t _pad0;
    void* static_base_handle;
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
    uint8_t kind;
    uint8_t _pad0;
    uint16_t _pad1;
    const uint8_t* raw_constant_utf8;
    size_t raw_constant_utf8_len;
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
    { 'V', 0u, {0u, 0u } }
};
const uint32_t g_neko_signature_descriptor_count = 2u;

extern void neko_sig_0_i2i(void);
extern void neko_sig_0_c2i(void);
extern void neko_sig_1_i2i(void);
extern void neko_sig_1_c2i(void);
static int neko_patch_method(const NekoManifestMethod *entry, void *method_star);
static void neko_patch_discovered_methods(void);
static void neko_resolve_discovered_invoke_sites(const char *owner_internal, const char *name, const char *desc, void *method_star);

static NekoManifestFieldSite g_neko_field_sites_2[2] = {
    { 2u, "pack/tests/basics/inner/Exec", "fuss", "I", &g_cls_2, 0u, 0u, 0u, 0u, NULL, NEKO_FIELD_SITE_UNRESOLVED },
    { 2u, "pack/tests/basics/inner/Exec", "fuss", "I", &g_cls_2, 0u, 0u, 0u, 0u, NULL, NEKO_FIELD_SITE_UNRESOLVED }
};
static NekoManifestFieldSite g_neko_field_sites_8[2] = {
    { 5u, "pack/tests/reflects/field/FObject", "i", "I", &g_cls_5, 0u, 0u, 0u, 0u, NULL, NEKO_FIELD_SITE_UNRESOLVED },
    { 5u, "pack/tests/reflects/field/FObject", "i", "I", &g_cls_5, 0u, 0u, 0u, 0u, NULL, NEKO_FIELD_SITE_UNRESOLVED }
};

static const char* const g_neko_manifest_owners[6] = {
    "pack/tests/basics/cross/Abst1",
    "pack/tests/basics/cross/Top",
    "pack/tests/basics/inner/Exec",
    "pack/tests/basics/sub/flo",
    "pack/tests/reflects/counter/Countee",
    "pack/tests/reflects/field/FObject"
};

""";
    private static final String TEMPLATE_C_PART_12 = """
static NekoManifestInvokeSite* const g_neko_manifest_invoke_sites[1] = {
    NULL
};

__attribute__((visibility("hidden"))) const NekoManifestMethod g_neko_manifest_methods[NEKO_MANIFEST_STORAGE_COUNT] = {
    { "pack/tests/basics/cross/Abst1", "mul", "(II)I", 0x72F66A77u, 0x6E5750D5u, 0x0000u, 0u, 0u, (void*)&neko_impl_0, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/basics/cross/Top", "add", "(II)I", 0x8C74266Bu, 0xBF9610D8u, 0x0000u, 0u, 0u, (void*)&neko_impl_1, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/basics/inner/Exec", "addF", "()V", 0xF3DB4BA5u, 0x336CFA79u, 0x0000u, 0u, 1u, (void*)&neko_impl_2, NULL, g_neko_field_sites_2, 2u, 0u, NULL, 0u, 0u },
    { "pack/tests/basics/sub/flo", "solve", "(II)I", 0x14848AADu, 0x00F0A844u, 0x0000u, 0u, 0u, (void*)&neko_impl_3, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/reflects/counter/Countee", "mpuli", "()V", 0x95AA8A28u, 0x2BDE3A87u, 0x0000u, 0u, 1u, (void*)&neko_impl_4, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/reflects/counter/Countee", "mprot", "()V", 0x95AA8A28u, 0xAF9820D6u, 0x0000u, 0u, 1u, (void*)&neko_impl_5, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/reflects/counter/Countee", "mpackp", "()V", 0x95AA8A28u, 0xD1783280u, 0x0000u, 0u, 1u, (void*)&neko_impl_6, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/reflects/counter/Countee", "mpriv", "()V", 0x95AA8A28u, 0xA81F3CC2u, 0x0000u, 0u, 1u, (void*)&neko_impl_7, NULL, NULL, 0u, 0u, NULL, 0u, 0u },
    { "pack/tests/reflects/field/FObject", "add", "()V", 0xC3E90C68u, 0x4C23FD7Bu, 0x0002u, 0u, 1u, (void*)&neko_impl_8, NULL, g_neko_field_sites_8, 2u, 0u, NULL, 0u, 0u }
};
__attribute__((visibility("hidden"))) const uint32_t g_neko_manifest_method_count = 9u;
__attribute__((visibility("hidden"))) void* g_neko_manifest_method_stars[NEKO_MANIFEST_STORAGE_COUNT] = { NULL };

static uint8_t g_neko_manifest_patch_states[NEKO_MANIFEST_STORAGE_COUNT] = { 0u };
static uint32_t g_neko_manifest_patch_count = 0u;

static const uint32_t g_neko_manifest_owner_count = 6u;
static const uint32_t g_neko_manifest_field_site_count = 4u;
static const uint32_t g_neko_manifest_invoke_site_count = 0u;
static const uint32_t g_neko_manifest_ldc_string_site_count = 0u;
static const uint32_t g_neko_manifest_ldc_class_site_count = 0u;

static void* const g_neko_signature_i2i_stubs[NEKO_SIGNATURE_STORAGE_COUNT] = {
    (void*)&neko_sig_0_i2i,
    (void*)&neko_sig_1_i2i
};
static void* const g_neko_signature_c2i_stubs[NEKO_SIGNATURE_STORAGE_COUNT] = {
    (void*)&neko_sig_0_c2i,
    (void*)&neko_sig_1_c2i
};

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


#define NEKO_REQUIRED_VM_SYMBOL_COUNT 24u
#define NEKO_REQUIRED_VM_SYMBOLS(X)     X(gHotSpotVMStructs)     X(gHotSpotVMStructEntryTypeNameOffset)     X(gHotSpotVMStructEntryFieldNameOffset)     X(gHotSpotVMStructEntryTypeStringOffset)     X(gHotSpotVMStructEntryIsStaticOffset)     X(gHotSpotVMStructEntryOffsetOffset)     X(gHotSpotVMStructEntryAddressOffset)     X(gHotSpotVMStructEntryArrayStride)     X(gHotSpotVMTypes)     X(gHotSpotVMTypeEntryTypeNameOffset)     X(gHotSpotVMTypeEntrySuperclassNameOffset)     X(gHotSpotVMTypeEntryIsOopTypeOffset)     X(gHotSpotVMTypeEntryIsIntegerTypeOffset)     X(gHotSpotVMTypeEntryIsUnsignedOffset)     X(gHotSpotVMTypeEntrySizeOffset)     X(gHotSpotVMTypeEntryArrayStride)     X(gHotSpotVMIntConstants)     X(gHotSpotVMIntConstantEntryNameOffset)     X(gHotSpotVMIntConstantEntryValueOffset)     X(gHotSpotVMIntConstantEntryArrayStride)     X(gHotSpotVMLongConstants)     X(gHotSpotVMLongConstantEntryNameOffset)     X(gHotSpotVMLongConstantEntryValueOffset)     X(gHotSpotVMLongConstantEntryArrayStride)

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

""";
    private static final String TEMPLATE_C_PART_13 = """
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

""";
    private static final String TEMPLATE_C_PART_14 = """
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

""";
    private static final String TEMPLATE_C_PART_15 = """
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

static inline void* neko_take_pending_jni_exception_oop(JNIEnv *env) {
    jthrowable pending;
    void *oop;
    if (env == NULL || !neko_exception_check(env)) return NULL;
    pending = neko_exception_occurred(env);
    if (pending == NULL) {
        neko_exception_clear(env);
        return NULL;
    }
    oop = neko_handle_oop((jobject)pending);
    neko_exception_clear(env);
    neko_delete_local_ref(env, pending);
    return oop;
}

static inline void* neko_new_exception_oop(JNIEnv *env, const char *class_name, const char *message) {
    jclass exc_class = NULL;
    jmethodID init = NULL;
    jstring text = NULL;
    jobject exc = NULL;
    void *oop = NULL;
    if (env == NULL || class_name == NULL) return NULL;
    exc_class = neko_find_class(env, class_name);
    if (exc_class == NULL || neko_exception_check(env)) goto cleanup;
    init = neko_get_method_id(env, exc_class, "<init>", "(Ljava/lang/String;)V");
    if (init == NULL || neko_exception_check(env)) goto cleanup;
    if (message != NULL) {
        text = neko_new_string_utf(env, message);
        if (text == NULL || neko_exception_check(env)) goto cleanup;
    }
    {
        jvalue args[1];
        args[0].l = text;
        exc = neko_new_object_a(env, exc_class, init, args);
    }
    if (exc == NULL || neko_exception_check(env)) goto cleanup;
    oop = neko_handle_oop(exc);
cleanup:
    if (oop == NULL) {
        oop = neko_take_pending_jni_exception_oop(env);
    }
    if (exc != NULL) {
        neko_delete_local_ref(env, exc);
    }
    if (text != NULL) {
        neko_delete_local_ref(env, text);
    }
    if (exc_class != NULL) {
        neko_delete_local_ref(env, exc_class);
    }
    return oop;
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

""";
    private static final String TEMPLATE_C_PART_16 = """
    JNIEnv *env;
    void *oom_oop;
    (void)size;
    if (thread == NULL) return NULL;
    env = neko_current_env();
    oom_oop = neko_new_exception_oop(env, "java/lang/OutOfMemoryError", "neko tlab slow path unavailable");
    if (oom_oop == NULL) {
        oom_oop = neko_take_pending_jni_exception_oop(env);
    }
    if (oom_oop != NULL) {
        neko_set_pending_exception(thread, oom_oop);
    }
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
        "runtime helpers v1 ready (oop shift=%d, klass shift=%d, tlab=%s, except=%s)",
        g_neko_vm_layout.narrow_oop_shift,
        g_neko_vm_layout.narrow_klass_shift,
        tlab_ready,
        except_ready
    );
}

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

""";
    private static final String TEMPLATE_C_PART_17 = """
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
#define NEKO_RESOLVE_REQUIRED_SYMBOL(name)     do {         g_neko_vm_symbols.name = neko_resolve_symbol_address(#name);         if (g_neko_vm_symbols.name == NULL) {             neko_error_log("required libjvm symbol %s not found, falling back to throw body", #name);             return JNI_FALSE;         }         resolved++;     } while (0);
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

""";
    private static final String TEMPLATE_C_PART_18 = """
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

""";
    private static final String TEMPLATE_C_PART_19 = """
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

""";
    private static final String TEMPLATE_C_PART_20 = """
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
    value[length - 2u] = ' ';
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

""";
    private static final String TEMPLATE_C_PART_21 = """
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

static void neko_raise_bound_resolution_error(JNIEnv *env, const char *errorClass, const char *message) {

""";
    private static final String TEMPLATE_C_PART_22 = """
    if (env == NULL || errorClass == NULL || message == NULL) return;
    if (neko_exception_check(env)) neko_exception_clear(env);
    jclass error = neko_find_class(env, errorClass);
    if (error == NULL) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        return;
    }
    neko_throw_new(env, error, message);
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

// === Wave 2 field/LDC support ===
static jclass g_neko_wave2_class_cls = NULL;
static jclass g_neko_wave2_field_cls = NULL;
static jclass g_neko_wave2_modifier_cls = NULL;
static jclass g_neko_wave2_unsafe_cls = NULL;
static jobject g_neko_wave2_unsafe_singleton = NULL;
static jmethodID g_neko_wave2_class_get_declared_field = NULL;
static jmethodID g_neko_wave2_field_get_modifiers = NULL;
static jmethodID g_neko_wave2_modifier_is_volatile = NULL;
static jmethodID g_neko_wave2_unsafe_object_field_offset = NULL;
static jmethodID g_neko_wave2_unsafe_static_field_offset = NULL;
static jmethodID g_neko_wave2_unsafe_array_base_offset = NULL;
static jmethodID g_neko_wave2_unsafe_array_index_scale = NULL;

static char* neko_wave2_copy_bytes(const uint8_t *bytes, size_t len) {
    char *copy = (char*)malloc(len + 1u);
    if (copy == NULL) return NULL;
    if (len != 0u && bytes != NULL) memcpy(copy, bytes, len);
    copy[len] = '\\0';
    return copy;
}

static void neko_wave2_capture_pending(JNIEnv *env, void *thread, const char *fallback_class, const char *fallback_message) {
    void *oop = neko_take_pending_jni_exception_oop(env);
    if (oop == NULL && fallback_class != NULL) oop = neko_new_exception_oop(env, fallback_class, fallback_message);
    if (oop != NULL) neko_set_pending_exception(thread, oop);
}

static void neko_raise_null_pointer_exception(void *thread) {
    JNIEnv *env = neko_current_env();
    void *oop = neko_new_exception_oop(env, "java/lang/NullPointerException", NULL);
    if (oop == NULL) oop = neko_take_pending_jni_exception_oop(env);
    if (oop != NULL) neko_set_pending_exception(thread, oop);
}

static jobject neko_wave2_unsafe(JNIEnv *env) {
    const char *unsafe_name = g_neko_vm_layout.java_spec_version >= 9 ? "jdk/internal/misc/Unsafe" : "sun/misc/Unsafe";
    if (env == NULL) return NULL;
    if (g_neko_wave2_unsafe_singleton != NULL) return g_neko_wave2_unsafe_singleton;
    if (g_neko_wave2_unsafe_cls == NULL) {
        jclass local = neko_find_class(env, unsafe_name);
        if (local == NULL || neko_exception_check(env)) { if (neko_exception_check(env)) neko_exception_clear(env); return NULL; }
        g_neko_wave2_unsafe_cls = (jclass)neko_new_global_ref(env, local);
        neko_delete_local_ref(env, local);
    }
    if (g_neko_wave2_unsafe_cls == NULL) return NULL;
    {
        jfieldID fid = neko_get_static_field_id(env, g_neko_wave2_unsafe_cls, "theUnsafe", g_neko_vm_layout.java_spec_version >= 9 ? "Ljdk/internal/misc/Unsafe;" : "Lsun/misc/Unsafe;");
        jobject local;
        if (fid == NULL || neko_exception_check(env)) { if (neko_exception_check(env)) neko_exception_clear(env); return NULL; }
        local = neko_get_static_object_field(env, g_neko_wave2_unsafe_cls, fid);
        if (local == NULL || neko_exception_check(env)) { if (neko_exception_check(env)) neko_exception_clear(env); return NULL; }
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

static jboolean neko_wave2_field_is_volatile(JNIEnv *env, jobject reflected_field) {
    jvalue args[1];
    if (env == NULL || reflected_field == NULL) return JNI_FALSE;
    g_neko_wave2_field_cls = NEKO_ENSURE_CLASS(g_neko_wave2_field_cls, env, "java/lang/reflect/Field");
    g_neko_wave2_modifier_cls = NEKO_ENSURE_CLASS(g_neko_wave2_modifier_cls, env, "java/lang/reflect/Modifier");
    g_neko_wave2_field_get_modifiers = NEKO_ENSURE_METHOD_ID(g_neko_wave2_field_get_modifiers, env, g_neko_wave2_field_cls, "getModifiers", "()I");
    g_neko_wave2_modifier_is_volatile = NEKO_ENSURE_STATIC_METHOD_ID(g_neko_wave2_modifier_is_volatile, env, g_neko_wave2_modifier_cls, "isVolatile", "(I)Z");
    if (g_neko_wave2_field_get_modifiers == NULL || g_neko_wave2_modifier_is_volatile == NULL) return JNI_FALSE;
    args[0].i = neko_call_int_method_a(env, reflected_field, g_neko_wave2_field_get_modifiers, NULL);
    return neko_call_static_boolean_method_a(env, g_neko_wave2_modifier_cls, g_neko_wave2_modifier_is_volatile, args);
}

static ptrdiff_t neko_wave2_object_field_offset_by_name(JNIEnv *env, const char *owner_internal, const char *field_name) {
    jclass owner = NULL;
    jobject reflected = NULL;
    ptrdiff_t offset = -1;
    if (env == NULL || owner_internal == NULL || field_name == NULL) return -1;

""";
    private static final String TEMPLATE_C_PART_23 = """
    owner = neko_find_class(env, owner_internal);
    if (owner == NULL || neko_exception_check(env)) goto cleanup;
    reflected = neko_wave2_declared_field(env, owner, field_name);
    if (reflected == NULL || neko_exception_check(env)) goto cleanup;
    offset = neko_wave2_reflected_field_offset(env, reflected, JNI_FALSE);
cleanup:
    if (neko_exception_check(env)) neko_exception_clear(env);
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
    if (array_class == NULL || neko_exception_check(env)) goto cleanup;
    args[0].l = array_class;
    value = want_base ? (ptrdiff_t)neko_call_int_method_a(env, unsafe, g_neko_wave2_unsafe_array_base_offset, args) : (ptrdiff_t)neko_call_int_method_a(env, unsafe, g_neko_wave2_unsafe_array_index_scale, args);
cleanup:
    if (neko_exception_check(env)) neko_exception_clear(env);
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

static inline void* neko_load_class_klass(void *class_mirror_oop) {
    if (class_mirror_oop == NULL || g_neko_vm_layout.off_class_klass < 0) return NULL;
    if (neko_uses_compressed_klass_pointers()) {
        return neko_decode_klass_pointer(*(u4*)((uint8_t*)class_mirror_oop + g_neko_vm_layout.off_class_klass));
    }
    return *(void**)((uint8_t*)class_mirror_oop + g_neko_vm_layout.off_class_klass);
}

static inline void* neko_load_klass_java_mirror(void *klass) {
    if (klass == NULL || g_neko_vm_layout.off_klass_java_mirror < 0) return NULL;
    if (neko_uses_compressed_oops()) {
        return neko_decode_heap_oop(*(u4*)((uint8_t*)klass + g_neko_vm_layout.off_klass_java_mirror));
    }
    return *(void**)((uint8_t*)klass + g_neko_vm_layout.off_klass_java_mirror);
}

static jboolean neko_resolve_field_site_with_class(JNIEnv *env, void *thread, NekoManifestFieldSite *site, jclass owner_class) {
    jfieldID fid;
    jobject reflected = NULL;
    ptrdiff_t offset;
    char message[320];
    if (env == NULL || site == NULL || owner_class == NULL) return JNI_FALSE;
    fid = site->is_static ? neko_get_static_field_id(env, owner_class, site->field_name, site->field_desc) : neko_get_field_id(env, owner_class, site->field_name, site->field_desc);
    if (fid == NULL || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, "java/lang/NoSuchFieldError", site->field_name); return JNI_FALSE; }
    reflected = neko_to_reflected_field(env, owner_class, fid, site->is_static ? JNI_TRUE : JNI_FALSE);
    if (reflected == NULL || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, "java/lang/NoSuchFieldError", site->field_name); return JNI_FALSE; }
    offset = neko_wave2_reflected_field_offset(env, reflected, site->is_static ? JNI_TRUE : JNI_FALSE);

""";
    private static final String TEMPLATE_C_PART_24 = """
    if (offset < 0 || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); snprintf(message, sizeof(message), "failed to resolve field offset %s.%s:%s", site->owner_internal, site->field_name, site->field_desc); neko_wave2_capture_pending(env, thread, "java/lang/IllegalStateException", message); neko_delete_local_ref(env, reflected); return JNI_FALSE; }
    site->is_volatile = neko_wave2_field_is_volatile(env, reflected) ? 1u : 0u;
    if (neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, "java/lang/IllegalStateException", "failed to inspect field modifiers"); neko_delete_local_ref(env, reflected); return JNI_FALSE; }
    __atomic_store_n(&site->resolved_offset, offset, __ATOMIC_RELEASE);
    neko_delete_local_ref(env, reflected);
    neko_native_debug_log("resolved field site %s.%s:%s -> %td%s", site->owner_internal, site->field_name, site->field_desc, offset, site->is_volatile ? " volatile" : "");
    return JNI_TRUE;
}

static jboolean neko_ensure_field_site_resolved(void *thread, NekoManifestFieldSite *site) {
    ptrdiff_t current = site == NULL ? NEKO_FIELD_SITE_FAILED : __atomic_load_n(&site->resolved_offset, __ATOMIC_ACQUIRE);
    JNIEnv *env;
    jclass owner_class;
    if (site == NULL) return JNI_FALSE;
    if (current >= 0) return JNI_TRUE;
    if (current == NEKO_FIELD_SITE_FAILED) {
        void *oop = neko_new_exception_oop(neko_current_env(), "java/lang/NoSuchFieldError", site->field_name);
        if (oop == NULL) oop = neko_take_pending_jni_exception_oop(neko_current_env());
        if (oop != NULL) neko_set_pending_exception(thread, oop);
        return JNI_FALSE;
    }
    env = neko_current_env();
    if (env == NULL) return JNI_FALSE;
    owner_class = (site->owner_class_slot != NULL) ? *site->owner_class_slot : NULL;
    if (owner_class == NULL) {
        jclass local = neko_load_class_noinit(env, site->owner_internal);
        if (local == NULL || neko_exception_check(env)) { __atomic_store_n(&site->resolved_offset, NEKO_FIELD_SITE_FAILED, __ATOMIC_RELEASE); neko_wave2_capture_pending(env, thread, "java/lang/NoClassDefFoundError", site->owner_internal); return JNI_FALSE; }
        owner_class = (jclass)neko_new_global_ref(env, local);
        neko_delete_local_ref(env, local);
        if (site->owner_class_slot != NULL && owner_class != NULL) *site->owner_class_slot = owner_class;
    }
    return neko_resolve_field_site_with_class(env, thread, site, owner_class);
}

static void neko_resolve_prepared_class_field_sites(JNIEnv *env, jclass klass, const char *owner_internal) {
    void *thread = neko_get_current_thread();
    if (env == NULL || klass == NULL || owner_internal == NULL) return;
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];
        for (uint32_t site_index = 0; site_index < method->field_site_count; site_index++) {
            NekoManifestFieldSite *site = &method->field_sites[site_index];
            if (site->owner_internal == NULL || strcmp(site->owner_internal, owner_internal) != 0) continue;
            if (site->owner_class_slot != NULL && *site->owner_class_slot == NULL) *site->owner_class_slot = (jclass)neko_new_global_ref(env, klass);
            if (__atomic_load_n(&site->resolved_offset, __ATOMIC_ACQUIRE) == NEKO_FIELD_SITE_UNRESOLVED) (void)neko_resolve_field_site_with_class(env, thread, site, klass);
        }
    }
}

static uint32_t neko_count_cached_static_field_bases(void) {
    uint32_t count = 0u;
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];
        for (uint32_t site_index = 0; site_index < method->field_site_count; site_index++) {
            NekoManifestFieldSite *site = &method->field_sites[site_index];
            if (site->is_static && __atomic_load_n(&site->static_base_handle, __ATOMIC_ACQUIRE) != NULL) count++;
        }
    }
    return count;
}

__attribute__((visibility("default"))) void* neko_field_site_static_base(void *thread, NekoManifestFieldSite *site) {
    void *cached;
    void *owner_mirror;
    void *owner_klass;
    if (!neko_ensure_field_site_resolved(thread, site)) return NULL;
    cached = __atomic_load_n(&site->static_base_handle, __ATOMIC_ACQUIRE);
    if (cached != NULL) return cached;
    if (site->owner_class_slot == NULL || *site->owner_class_slot == NULL) return NULL;
    owner_mirror = neko_handle_oop((jobject)*site->owner_class_slot);
    owner_klass = neko_load_class_klass(owner_mirror);
    cached = neko_load_klass_java_mirror(owner_klass);
    if (cached != NULL) {
        __atomic_store_n(&site->static_base_handle, cached, __ATOMIC_RELEASE);
        neko_native_debug_log("cached static base %s.%s:%s mirror=%p", site->owner_internal, site->field_name, site->field_desc, cached);
    }
    return cached;
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

""";
    private static final String TEMPLATE_C_PART_25 = """
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

__attribute__((visibility("default"))) void* neko_resolve_ldc_string(const uint8_t *utf8, size_t utf8_len) {
    JNIEnv *env = neko_current_env();
    char *copy;
    jstring local;
    jobject global;
    if (env == NULL) return NULL;
    copy = neko_wave2_copy_bytes(utf8, utf8_len);
    if (copy == NULL) return NULL;
    local = neko_new_string_utf(env, copy);
    free(copy);
    if (local == NULL || neko_exception_check(env)) return NULL;
    global = neko_new_global_ref(env, local);
    neko_delete_local_ref(env, local);
    return global;
}

static void* neko_resolve_ldc_class_handle(const uint8_t *utf8, size_t utf8_len) {
    JNIEnv *env = neko_current_env();
    char *copy;
    jclass local;
    jobject global;
    if (env == NULL) return NULL;
    copy = neko_wave2_copy_bytes(utf8, utf8_len);
    if (copy == NULL) return NULL;
    local = neko_class_for_descriptor(env, copy);
    free(copy);
    if (local == NULL || neko_exception_check(env)) return NULL;
    global = neko_new_global_ref(env, local);
    neko_delete_local_ref(env, local);
    return global;
}

static void* neko_resolve_ldc_site_oop(void *thread, NekoManifestLdcSite *site) {
    void *cached;
    if (site == NULL) return NULL;
    cached = __atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE);
    if (cached != NULL) return neko_handle_oop((jobject)cached);
    neko_wave2_capture_pending(neko_current_env(), thread, "java/lang/IllegalStateException", "unwarmed LDC site");
    return NULL;
}

static jboolean neko_prewarm_ldc_sites(JNIEnv *env) {
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        NekoManifestMethod *method = (NekoManifestMethod*)&g_neko_manifest_methods[i];
        for (uint32_t site_index = 0; site_index < method->ldc_site_count; site_index++) {
            NekoManifestLdcSite *site = &method->ldc_sites[site_index];
            void *cached = __atomic_load_n(&site->resolved_cache_handle, __ATOMIC_ACQUIRE);
            void *created;
            if (cached != NULL) continue;
            created = site->kind == NEKO_LDC_KIND_STRING ? neko_resolve_ldc_string(site->raw_constant_utf8, site->raw_constant_utf8_len) : (site->kind == NEKO_LDC_KIND_CLASS ? neko_resolve_ldc_class_handle(site->raw_constant_utf8, site->raw_constant_utf8_len) : NULL);
            if (created == NULL || neko_exception_check(env)) {
                if (neko_exception_check(env)) neko_exception_clear(env);
                neko_error_log("failed to prewarm LDC site %u kind=%u for %s.%s%s", site->site_id, site->kind, method->owner_internal, method->method_name, method->method_desc);
                return JNI_FALSE;
            }
            __atomic_store_n(&site->resolved_cache_handle, created, __ATOMIC_RELEASE);
            neko_native_debug_log("prewarmed LDC site %u kind=%u", site->site_id, site->kind);
        }
    }
    return JNI_TRUE;
}

static void neko_log_wave2_ready(void) {
    neko_native_debug_log("wave2 ready (field_sites=%u, ldc_strings=%u, ldc_classes=%u)", g_neko_manifest_field_site_count, g_neko_manifest_ldc_string_site_count, g_neko_manifest_ldc_class_site_count);
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
    if (resolved != NULL) return resolved;
    for (uint32_t i = 0; i < g_neko_manifest_method_count; i++) {
        const NekoManifestMethod *method = &g_neko_manifest_methods[i];
        void *method_star;
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
    neko_native_debug_log("wave3 ready (invoke_sites=%u, static_fields_cached=%u)", g_neko_manifest_invoke_site_count, neko_count_cached_static_field_bases());
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

""";
    private static final String TEMPLATE_C_PART_26 = """
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
        neko_native_debug_log("wave4a unavailable (%s)", g_neko_wave4a_unavailable_reason == NULL ? "unknown" : g_neko_wave4a_unavailable_reason);
        return;
    }
    neko_native_debug_log(
        "wave4a ready (thread_state_off=%td, anchor_off=%td+%td, mirror_off=%td, oophandle_obj_off=%td, tlab_fast=%s)",
        g_neko_vm_layout.off_thread_thread_state,
        g_neko_vm_layout.off_java_thread_anchor,
        g_neko_vm_layout.off_java_frame_anchor_sp,
        g_neko_vm_layout.off_instance_klass_java_mirror,
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
        neko_native_debug_log("wave4a handles are malloc-backed only (not GC-rooted yet)");
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


__attribute__((visibility("default"))) oop neko_rt_mirror_from_klass_nosafepoint(Klass *k) {
    uint8_t *field;
    if (k == NULL || !neko_wave4a_enabled() || g_neko_vm_layout.off_instance_klass_java_mirror < 0) return NULL;
    field = (uint8_t*)k + g_neko_vm_layout.off_instance_klass_java_mirror;
    if (g_neko_vm_layout.java_spec_version >= 9) {
        oop *oophandle_slot;
        if (g_neko_vm_layout.off_oophandle_obj < 0) return NULL;
        oophandle_slot = *(oop**)(field + g_neko_vm_layout.off_oophandle_obj);
        return oophandle_slot == NULL ? NULL : *oophandle_slot;
    }
    if (neko_uses_compressed_oops()) {
        return neko_decode_heap_oop(*(u4*)field);
    }
    return *(oop*)field;
}

__attribute__((visibility("default"))) oop neko_rt_static_base_from_holder_nosafepoint(Klass *holder) {
    return neko_rt_mirror_from_klass_nosafepoint(holder);
}

__attribute__((visibility("default"))) oop neko_rt_try_alloc_instance_fast_nosafepoint(Klass *ik, size_t instance_size_bytes) {

""";
    private static final String TEMPLATE_C_PART_27 = """
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
    neko_native_debug_log("flag patch via %s (jdk%d)", path_name, g_neko_vm_layout.java_spec_version);
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
        neko_debug_log("method %s.%s%s already compiled, skipping patch", entry->owner_internal, entry->method_name, entry->method_desc);
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
    neko_debug_log("patched %s.%s%s sig=%u", entry->owner_internal, entry->method_name, entry->method_desc, entry->signature_id);
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

__attribute__((used, visibility("default"))) int32_t neko_impl_0(void* _this, int32_t p0, int32_t p1) {
    JNIEnv *env = neko_current_env();
    void *thread = neko_get_current_thread();
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


""";
    private static final String TEMPLATE_C_PART_28 = """
__attribute__((used, visibility("default"))) int32_t neko_impl_1(void* _this, int32_t p0, int32_t p1) {
    JNIEnv *env = neko_current_env();
    void *thread = neko_get_current_thread();
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
    JNIEnv *env = neko_current_env();
    void *thread = neko_get_current_thread();
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[9];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
L0: ;
    PUSH_O(locals[0].o);
    stack[sp] = stack[sp-1]; sp++;
    { NekoManifestFieldSite *__site = &g_neko_field_sites_2[0]; void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; PUSH_I(neko_field_read_I(__recv, __site)); }
    if (neko_exception_check(env)) { jthrowable __exc = neko_exception_occurred(env); neko_exception_clear(env); neko_throw(env, __exc); goto __neko_exception_exit; }
    PUSH_I(2);
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }
    { NekoManifestFieldSite *__site = &g_neko_field_sites_2[1]; jint val = POP_I(); void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; neko_field_write_I(__recv, __site, val); }
    if (neko_exception_check(env)) { jthrowable __exc = neko_exception_occurred(env); neko_exception_clear(env); neko_throw(env, __exc); goto __neko_exception_exit; }
L1: ;
    return;
L2: ;
__neko_exception_exit: ;
    return;
}

__attribute__((used, visibility("default"))) int32_t neko_impl_3(void* _this, int32_t p0, int32_t p1) {
    JNIEnv *env = neko_current_env();
    void *thread = neko_get_current_thread();
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

__attribute__((used, visibility("default"))) void neko_impl_4(void* _this) {
    JNIEnv *env = neko_current_env();
    void *thread = neko_get_current_thread();
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

__attribute__((used, visibility("default"))) void neko_impl_5(void* _this) {
    JNIEnv *env = neko_current_env();
    void *thread = neko_get_current_thread();
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

__attribute__((used, visibility("default"))) void neko_impl_6(void* _this) {
    JNIEnv *env = neko_current_env();
    void *thread = neko_get_current_thread();
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

__attribute__((used, visibility("default"))) void neko_impl_7(void* _this) {
    JNIEnv *env = neko_current_env();
    void *thread = neko_get_current_thread();
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

__attribute__((used, visibility("default"))) void neko_impl_8(void* _this) {
    JNIEnv *env = neko_current_env();
    void *thread = neko_get_current_thread();
    neko_slot stack[32];
    int sp = 0;
    neko_slot locals[9];
    memset(locals, 0, sizeof(locals));
    locals[0].o = _this;
L0: ;
    PUSH_O(locals[0].o);
    stack[sp] = stack[sp-1]; sp++;
    { NekoManifestFieldSite *__site = &g_neko_field_sites_8[0]; void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; PUSH_I(neko_field_read_I(__recv, __site)); }
    if (neko_exception_check(env)) { jthrowable __exc = neko_exception_occurred(env); neko_exception_clear(env); neko_throw(env, __exc); goto __neko_exception_exit; }
    PUSH_I(3);
    { jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }
    { NekoManifestFieldSite *__site = &g_neko_field_sites_8[1]; jint val = POP_I(); void *__recv = POP_O(); if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; neko_field_write_I(__recv, __site, val); }
    if (neko_exception_check(env)) { jthrowable __exc = neko_exception_occurred(env); neko_exception_clear(env); neko_throw(env, __exc); goto __neko_exception_exit; }
L1: ;
    return;
L2: ;
__neko_exception_exit: ;
    return;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    jvmtiEnv *jvmti = NULL;
    jint env_status;
    (void)reserved;
    g_neko_java_vm = vm;
    env_status = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
    if (env_status != JNI_OK || env == NULL) {
        neko_error_log("GetEnv(JNI_VERSION_1_6) failed, falling back to throw body");
        return JNI_VERSION_1_6;
    }
    env_status = (*vm)->GetEnv(vm, (void**)&jvmti, JVMTI_VERSION_1_2);
    if (env_status != JNI_OK || jvmti == NULL) {
        neko_error_log("GetEnv(JVMTI_VERSION_1_2) failed, falling back to throw body");
        return JNI_VERSION_1_6;
    }
    neko_mark_loader_loaded(env);
    g_neko_jvmti = jvmti;
    if (!neko_resolve_vm_symbols()) {
        return JNI_VERSION_1_6;
    }
    if (!neko_parse_vm_layout(env)) {
        return JNI_VERSION_1_6;
    }
    neko_log_runtime_helpers_ready();
    neko_log_wave4a_status();
    if (!neko_init_jvmti(vm, jvmti)) {
        return JNI_VERSION_1_6;
    }
    if (!neko_discover_loaded_classes(env, jvmti)) {
        return JNI_VERSION_1_6;
    }
    if (!neko_discover_manifest_owners(env, jvmti)) {
        return JNI_VERSION_1_6;
    }
    if (!neko_prewarm_ldc_sites(env)) {
        return JNI_VERSION_1_6;
    }
    neko_manifest_lock_enter();
    neko_patch_discovered_methods();
    neko_manifest_lock_exit();
    if (!neko_install_class_prepare_callback(jvmti)) {
        return JNI_VERSION_1_6;
    }
    neko_debug_log("discovery matched %u/%u manifest entries", g_neko_manifest_match_count, g_neko_manifest_method_count);
    neko_debug_log("patched %u/%u manifest entries", g_neko_manifest_patch_count, g_neko_manifest_method_count);
    neko_log_wave2_ready();
    neko_log_wave3_ready();
    return JNI_VERSION_1_6;
}

""";
    private static final String TEMPLATE_H = """
#ifndef NEKO_NATIVE_H
#define NEKO_NATIVE_H

#include <jni.h>
#include <stdint.h>

int32_t neko_impl_0(void* _this, int32_t p0, int32_t p1);
int32_t neko_impl_1(void* _this, int32_t p0, int32_t p1);
void neko_impl_2(void* _this);
int32_t neko_impl_3(void* _this, int32_t p0, int32_t p1);
void neko_impl_4(void* _this);
void neko_impl_5(void* _this);
void neko_impl_6(void* _this);
void neko_impl_7(void* _this);
void neko_impl_8(void* _this);

#endif

""";
    private static final String TEMPLATE_S = """
#if !defined(__x86_64__) || !defined(__linux__)
#error "M3 stubs only support x86_64 Linux"
#endif

.intel_syntax noprefix
.text

# Signature-stub sketch:
#   i2i: lookup manifest entry by Method*, pop return address, unpack interpreter slots,
#        call hidden C dispatcher, restore sender_sp (r13) and jump to continuation.
#   c2i: lookup manifest entry by Method*, materialize any stack-overflow args for the
#        hidden C dispatcher, put entry in rdi, call dispatcher, return to compiled caller.

.globl neko_sig_0_i2i
.type neko_sig_0_i2i, @function
neko_sig_0_i2i:
    lea r10, [rip + g_neko_manifest_method_stars]
    xor r11d, r11d
neko_sig_0_i2i_found_scan:
    cmp r11d, DWORD PTR [rip + g_neko_manifest_method_count]
    jae neko_sig_0_i2i_fail
    cmp QWORD PTR [r10 + r11 * 8], rbx
    je neko_sig_0_i2i_found
    inc r11d
    jmp neko_sig_0_i2i_found_scan
neko_sig_0_i2i_found:
    mov rax, r11
    imul rax, rax, 88
    lea r10, [rip + g_neko_manifest_methods]
    add rax, r10
    test WORD PTR [rax + 32], 1
    jnz neko_sig_0_i2i_static
    pop r10
    mov r11, rsp
    sub rsp, 24
    mov QWORD PTR [rsp + 0], rax
    mov QWORD PTR [rsp + 8], r10
    mov rsi, QWORD PTR [r11 + 16]
    mov edx, DWORD PTR [r11 + 8]
    mov ecx, DWORD PTR [r11 + 0]
    mov rdi, QWORD PTR [rsp + 0]
    call neko_sig_0_dispatch_instance
    mov r10, QWORD PTR [rsp + 8]
    mov rsp, r13
    jmp r10
neko_sig_0_i2i_static:
    pop r10
    mov r11, rsp
    sub rsp, 24
    mov QWORD PTR [rsp + 0], rax
    mov QWORD PTR [rsp + 8], r10
    mov esi, DWORD PTR [r11 + 8]
    mov edx, DWORD PTR [r11 + 0]
    mov rdi, QWORD PTR [rsp + 0]
    call neko_sig_0_dispatch_static
    mov r10, QWORD PTR [rsp + 8]
    mov rsp, r13
    jmp r10
neko_sig_0_i2i_fail:
    pop r10
    xor eax, eax
    mov rsp, r13
    jmp r10

.globl neko_sig_0_c2i
.type neko_sig_0_c2i, @function
neko_sig_0_c2i:
    lea r10, [rip + g_neko_manifest_method_stars]
    xor r11d, r11d
neko_sig_0_c2i_found_scan:
    cmp r11d, DWORD PTR [rip + g_neko_manifest_method_count]
    jae neko_sig_0_c2i_fail
    cmp QWORD PTR [r10 + r11 * 8], rbx
    je neko_sig_0_c2i_found
    inc r11d
    jmp neko_sig_0_c2i_found_scan
neko_sig_0_c2i_found:
    mov rax, r11
    imul rax, rax, 88
    lea r10, [rip + g_neko_manifest_methods]
    add rax, r10
    test WORD PTR [rax + 32], 1
    jnz neko_sig_0_c2i_static
    sub rsp, 32
    mov QWORD PTR [rsp + 0], rax
    mov QWORD PTR [rsp + 8], rsi
    mov QWORD PTR [rsp + 16], rdx
    mov QWORD PTR [rsp + 24], rcx
    mov rdi, QWORD PTR [rsp + 0]
    call neko_sig_0_dispatch_instance
    add rsp, 32
    ret
neko_sig_0_c2i_static:
    sub rsp, 32
    mov QWORD PTR [rsp + 0], rax
    mov QWORD PTR [rsp + 8], rsi
    mov QWORD PTR [rsp + 16], rdx
    mov rdi, QWORD PTR [rsp + 0]
    call neko_sig_0_dispatch_static
    add rsp, 32
    ret
neko_sig_0_c2i_fail:
    xor eax, eax
    ret

.globl neko_sig_1_i2i
.type neko_sig_1_i2i, @function
neko_sig_1_i2i:
    lea r10, [rip + g_neko_manifest_method_stars]
    xor r11d, r11d
neko_sig_1_i2i_found_scan:
    cmp r11d, DWORD PTR [rip + g_neko_manifest_method_count]
    jae neko_sig_1_i2i_fail
    cmp QWORD PTR [r10 + r11 * 8], rbx
    je neko_sig_1_i2i_found
    inc r11d
    jmp neko_sig_1_i2i_found_scan
neko_sig_1_i2i_found:
    mov rax, r11
    imul rax, rax, 88
    lea r10, [rip + g_neko_manifest_methods]
    add rax, r10
    test WORD PTR [rax + 32], 1
    jnz neko_sig_1_i2i_static
    pop r10
    mov r11, rsp
    sub rsp, 24
    mov QWORD PTR [rsp + 0], rax
    mov QWORD PTR [rsp + 8], r10
    mov rsi, QWORD PTR [r11 + 0]
    mov rdi, QWORD PTR [rsp + 0]
    call neko_sig_1_dispatch_instance
    mov r10, QWORD PTR [rsp + 8]
    mov rsp, r13
    jmp r10
neko_sig_1_i2i_static:
    pop r10
    mov r11, rsp
    sub rsp, 24
    mov QWORD PTR [rsp + 0], rax
    mov QWORD PTR [rsp + 8], r10
    mov rdi, QWORD PTR [rsp + 0]
    call neko_sig_1_dispatch_static
    mov r10, QWORD PTR [rsp + 8]
    mov rsp, r13
    jmp r10
neko_sig_1_i2i_fail:
    pop r10
    xor eax, eax
    mov rsp, r13
    jmp r10

.globl neko_sig_1_c2i
.type neko_sig_1_c2i, @function
neko_sig_1_c2i:
    lea r10, [rip + g_neko_manifest_method_stars]
    xor r11d, r11d
neko_sig_1_c2i_found_scan:
    cmp r11d, DWORD PTR [rip + g_neko_manifest_method_count]
    jae neko_sig_1_c2i_fail
    cmp QWORD PTR [r10 + r11 * 8], rbx
    je neko_sig_1_c2i_found
    inc r11d
    jmp neko_sig_1_c2i_found_scan
neko_sig_1_c2i_found:
    mov rax, r11
    imul rax, rax, 88
    lea r10, [rip + g_neko_manifest_methods]
    add rax, r10
    test WORD PTR [rax + 32], 1
    jnz neko_sig_1_c2i_static
    sub rsp, 16
    mov QWORD PTR [rsp + 0], rax
    mov QWORD PTR [rsp + 8], rsi
    mov rdi, QWORD PTR [rsp + 0]
    call neko_sig_1_dispatch_instance
    add rsp, 16
    ret
neko_sig_1_c2i_static:
    sub rsp, 16
    mov QWORD PTR [rsp + 0], rax
    mov rdi, QWORD PTR [rsp + 0]
    call neko_sig_1_dispatch_static
    add rsp, 16
    ret
neko_sig_1_c2i_fail:
    xor eax, eax
    ret


""";

    public CCodeGenerator(long masterSeed) {
        this.symbols = new SymbolTableGenerator(masterSeed);
    }

    public void configureStringCacheCount(int stringCacheCount) {
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
        return manifestMethodIndex.computeIfAbsent(bindingKey, ignored -> manifestMethodIndex.size());
    }

    public String reserveManifestFieldSite(String bindingKey, String bindingOwner, String owner, String name, String desc, boolean isStatic) {
        int methodId = registerManifestMethod(bindingKey);
        registerOwnerClassReference(bindingOwner, owner);
        List<ManifestFieldSiteRef> sites = manifestFieldSites.computeIfAbsent(bindingKey, ignored -> new ArrayList<>());
        int siteIndex = sites.size();
        ManifestFieldSiteRef site = new ManifestFieldSiteRef(methodId, siteIndex, classSlotIndex.get(owner), owner, name, desc, isStatic, !isPrimitiveFieldDescriptor(desc));
        sites.add(site);
        return site.arrayElementExpression();
    }

    public String reserveManifestInvokeSite(String bindingKey, String bindingOwner, String owner, String name, String desc, int opcode) {
        int methodId = registerManifestMethod(bindingKey);
        registerOwnerClassReference(bindingOwner, owner);
        List<ManifestInvokeSiteRef> sites = manifestInvokeSites.computeIfAbsent(bindingKey, ignored -> new ArrayList<>());
        int siteIndex = sites.size();
        sites.add(new ManifestInvokeSiteRef(methodId, siteIndex, owner, name, desc, opcode, signatureKey(desc)));
        return "&" + manifestInvokeSiteArrayName(methodId) + '[' + siteIndex + ']';
    }

    public String reserveManifestStringLdcSite(String bindingKey, String bindingOwner, String literal) {
        int methodId = registerManifestMethod(bindingKey);
        List<ManifestLdcSiteRef> sites = manifestLdcSites.computeIfAbsent(bindingKey, ignored -> new ArrayList<>());
        int siteIndex = sites.size();
        ManifestLdcSiteRef site = new ManifestLdcSiteRef(methodId, siteIndex, LdcKind.STRING, literal, new Utf8BlobRef(methodId, siteIndex, modifiedUtf8Bytes(literal)));
        sites.add(site);
        return site.arrayElementExpression();
    }

    public String reserveManifestClassLdcSite(String bindingKey, String bindingOwner, String descriptor) {
        int methodId = registerManifestMethod(bindingKey);
        List<ManifestLdcSiteRef> sites = manifestLdcSites.computeIfAbsent(bindingKey, ignored -> new ArrayList<>());
        int siteIndex = sites.size();
        ManifestLdcSiteRef site = new ManifestLdcSiteRef(methodId, siteIndex, LdcKind.CLASS, descriptor, new Utf8BlobRef(methodId, siteIndex, descriptor.getBytes(StandardCharsets.UTF_8)));
        sites.add(site);
        return site.arrayElementExpression();
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
        return icacheSites.computeIfAbsent(siteKey, ignored -> new IcacheSiteRef(ownerBindIndex.get(bindingOwner), icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()), siteIndex, bindingOwner, methodKey)).symbol();
    }

    public String reserveInvokeCacheDirectStub(String bindingOwner, String methodKey, int siteIndex, NativeMethodBinding binding, Type[] args, Type returnType) {
        String cacheMethodKey = bindingOwner + '#' + methodKey;
        String siteKey = cacheMethodKey + '#' + siteIndex;
        registerBindingOwner(bindingOwner);
        return icacheDirectStubs.computeIfAbsent(siteKey, ignored -> new IcacheDirectStubRef(ownerBindIndex.get(bindingOwner), icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()), siteIndex, binding, args.clone(), returnType)).symbol();
    }

    public String reserveInvokeCacheMeta(String bindingOwner, String methodKey, int siteIndex, String name, String desc, boolean isInterface, String translatedClassSlot, String translatedStubSymbol) {
        String cacheMethodKey = bindingOwner + '#' + methodKey;
        String siteKey = cacheMethodKey + '#' + siteIndex;
        registerBindingOwner(bindingOwner);
        return icacheMetas.computeIfAbsent(siteKey, ignored -> new IcacheMetaRef(ownerBindIndex.get(bindingOwner), icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()), siteIndex, name, desc, isInterface, translatedClassSlot, translatedStubSymbol)).symbol();
    }

    public String generateHeader(List<NativeMethodBinding> bindings) {
        return renderHeader(bindings);
    }

    public List<GeneratedSource> generateAdditionalSources(List<NativeMethodBinding> bindings) {
        if (bindings.isEmpty()) {
            return List.of();
        }
        return List.of(new GeneratedSource("neko_stubs.S", generateAssemblySource(bindings)));
    }

    public List<SignatureInfo> signatureInfos(List<NativeMethodBinding> bindings) {
        return signatureInfosForBindings(bindings);
    }

    public String generateSource(List<CFunction> functions, List<NativeMethodBinding> bindings) {
        resetOutput();
        append(renderSourcePreamble());
        append(renderResolutionCachesSection());
        append(renderRuntimeSupportSection());
        append(renderHotSpotSupportSection());
        append(renderManifestSection(bindings));
        append(renderSignatureDispatchSection(bindings));
        append(renderBootstrapPreludeSection());
        append(renderWave1RuntimeSection());
        append(renderBootstrapPostWave1PreDiscoverySection());
        append(renderDiscoverySection());
        append(renderBootstrapPostDiscoverySection());
        append(renderBindOwnerFunctionsSection());
        append(renderBindSupportSection());
        append(renderWave2Section());
        append(renderWave3Section());
        append(renderWave4aSection());
        append(renderEntryPatchSection());
        append(renderObjectReturnSection());
        append(renderIcacheDirectStubsSection());
        append(renderIcacheMetasSection());
        append(renderFunctionBodies(functions));
        append(renderJniOnLoadSection());
        return finishOutput();
    }

    private void resetOutput() {
        output.setLength(0);
    }

    private void append(String text) {
        output.append(text);
    }

    private String finishOutput() {
        return output.toString();
    }

    private String renderSourcePreamble() {
        StringBuilder sb = new StringBuilder();
        sb.append(TEMPLATE_C_PART_1);
        sb.append(TEMPLATE_C_PART_2);
        sb.append(TEMPLATE_C_PART_3);
        sb.append(TEMPLATE_C_PART_4);
        sb.append(TEMPLATE_C_PART_5);
        sb.append(TEMPLATE_C_PART_6);
        sb.append(TEMPLATE_C_PART_7);
        sb.append(TEMPLATE_C_PART_8);
        sb.append(TEMPLATE_C_PART_9);
        sb.append(TEMPLATE_C_PART_10);
        sb.append(TEMPLATE_C_PART_11);
        sb.append(TEMPLATE_C_PART_12);
        sb.append(TEMPLATE_C_PART_13);
        sb.append(TEMPLATE_C_PART_14);
        sb.append(TEMPLATE_C_PART_15);
        sb.append(TEMPLATE_C_PART_16);
        sb.append(TEMPLATE_C_PART_17);
        sb.append(TEMPLATE_C_PART_18);
        sb.append(TEMPLATE_C_PART_19);
        sb.append(TEMPLATE_C_PART_20);
        sb.append(TEMPLATE_C_PART_21);
        sb.append(TEMPLATE_C_PART_22);
        sb.append(TEMPLATE_C_PART_23);
        sb.append(TEMPLATE_C_PART_24);
        sb.append(TEMPLATE_C_PART_25);
        sb.append(TEMPLATE_C_PART_26);
        sb.append(TEMPLATE_C_PART_27);
        sb.append(TEMPLATE_C_PART_28);
        return sb.toString();
    }

    private String renderHeader(List<NativeMethodBinding> bindings) {
        return TEMPLATE_H;
    }

    private String renderResolutionCachesSection() { return ""; }
    private String renderRuntimeSupportSection() { return ""; }
    private String renderHotSpotSupportSection() { return ""; }
    private String renderManifestSection(List<NativeMethodBinding> bindings) { return ""; }
    private String renderSignatureDispatchSection(List<NativeMethodBinding> bindings) { return ""; }
    private String renderBootstrapPreludeSection() { return ""; }
    private String renderWave1RuntimeSection() { return ""; }
    private String renderBootstrapPostWave1PreDiscoverySection() { return ""; }
    private String renderDiscoverySection() { return ""; }
    private String renderBootstrapPostDiscoverySection() { return ""; }
    private String renderBindOwnerFunctionsSection() { return ""; }
    private String renderBindSupportSection() { return ""; }
    private String renderWave2Section() { return ""; }
    private String renderWave3Section() { return ""; }
    private String renderWave4aSection() { return ""; }
    private String renderEntryPatchSection() { return ""; }
    private String renderObjectReturnSection() { return ""; }
    private String renderIcacheDirectStubsSection() { return ""; }
    private String renderIcacheMetasSection() { return ""; }
    private String renderFunctionBodies(List<CFunction> functions) { return ""; }
    private String renderJniOnLoadSection() { return ""; }

    private String generateAssemblySource(List<NativeMethodBinding> bindings) {
        return TEMPLATE_S;
    }

    private List<SignatureInfo> signatureInfosForBindings(List<NativeMethodBinding> bindings) {
        return List.of(
            new SignatureInfo(0, "(II)I"),
            new SignatureInfo(1, "()V")
        );
    }

    private byte[] modifiedUtf8Bytes(String value) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        byte[] buffer = new byte[value.length() * 3];
        int index = 0;
        for (int i = 0; i < value.length(); i++) {
            int ch = value.charAt(i);
            if (ch >= 0x0001 && ch <= 0x007F) {
                buffer[index++] = (byte) ch;
            } else if (ch <= 0x07FF) {
                buffer[index++] = (byte) (0xC0 | ((ch >> 6) & 0x1F));
                buffer[index++] = (byte) (0x80 | (ch & 0x3F));
            } else {
                buffer[index++] = (byte) (0xE0 | ((ch >> 12) & 0x0F));
                buffer[index++] = (byte) (0x80 | ((ch >> 6) & 0x3F));
                buffer[index++] = (byte) (0x80 | (ch & 0x3F));
            }
        }
        byte[] out = new byte[index];
        System.arraycopy(buffer, 0, out, 0, index);
        return out;
    }

    private String signatureKey(String descriptor) {
        Type[] argumentTypes = Type.getArgumentTypes(descriptor);
        StringBuilder key = new StringBuilder();
        key.append('(');
        for (Type argumentType : argumentTypes) {
            key.append(collapseKind(argumentType));
        }
        return key.append(')').append(collapseKind(Type.getReturnType(descriptor))).toString();
    }

    private char collapseKind(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> 'V';
            case Type.BOOLEAN -> 'Z';
            case Type.BYTE -> 'B';
            case Type.SHORT -> 'S';
            case Type.CHAR -> 'C';
            case Type.INT -> 'I';
            case Type.LONG -> 'J';
            case Type.FLOAT -> 'F';
            case Type.DOUBLE -> 'D';
            default -> 'L';
        };
    }

    private boolean isPrimitiveFieldDescriptor(String desc) {
        return desc != null && desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0;
    }

    private String manifestInvokeSiteArrayName(int methodId) {
        return "g_neko_invoke_sites_" + methodId;
    }

    public record GeneratedSource(String fileName, String content) {}
    public record SignatureInfo(int id, String key) {}

    private record MethodRef(String owner, String name, String desc, boolean isStatic) {}
    private record FieldRef(String owner, String name, String desc, boolean isStatic) {}
    private record StringRef(String cacheVar, String value) {}
    private enum LdcKind { STRING, CLASS }
    private record Utf8BlobRef(int methodId, int siteIndex, byte[] bytes) {}

    private record ManifestFieldSiteRef(int methodId, int siteIndex, int ownerClassIndex, String owner, String name, String desc, boolean isStatic, boolean isReference) {
        private String arrayElementExpression() {
            return "&g_neko_field_sites_" + methodId + '[' + siteIndex + ']';
        }
    }

    private record ManifestInvokeSiteRef(int methodId, int siteIndex, String owner, String name, String desc, int opcode, String signatureKey) {}

    private record ManifestLdcSiteRef(int methodId, int siteIndex, LdcKind kind, String rawConstant, Utf8BlobRef blob) {
        private String arrayElementExpression() {
            return "&g_neko_ldc_sites_" + methodId + '[' + siteIndex + ']';
        }
    }

    private record IcacheSiteRef(int ownerId, int methodId, int siteIndex, String bindingOwner, String methodKey) {
        private String symbol() {
            return "neko_icache_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    private record IcacheDirectStubRef(int ownerId, int methodId, int siteIndex, NativeMethodBinding binding, Type[] args, Type returnType) {
        private String symbol() {
            return "neko_icache_stub_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    private record IcacheMetaRef(int ownerId, int methodId, int siteIndex, String name, String desc, boolean isInterface, String translatedClassSlot, String translatedStubSymbol) {
        private String symbol() {
            return "neko_icache_meta_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    private static final class OwnerResolution {
        private final Set<String> classes = new LinkedHashSet<>();
        private final Set<MethodRef> methods = new LinkedHashSet<>();
        private final Set<FieldRef> fields = new LinkedHashSet<>();
        private final Set<StringRef> strings = new LinkedHashSet<>();
    }
}
