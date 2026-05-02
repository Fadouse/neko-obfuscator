package dev.nekoobfuscator.native_.codegen.emit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits per-shape Java-state direct-call dispatchers. The hot path enters
 * Java through HotSpot's shared {@code StubRoutines::call_stub} with a
 * stack-local JavaCallWrapper mirror, so HotSpot's stack walker sees a real
 * entry frame and can return to the translated Java-ABI native body.
 *
 * <h2>HotSpot's compiled-Java calling convention (x86-64 SysV)</h2>
 *
 * Reference: hotspot/cpu/x86/sharedRuntime_x86_64.cpp
 * (SharedRuntime::java_calling_convention) and the j_rarg* register file
 * in hotspot/cpu/x86/register_x86.hpp.
 *
 * <ul>
 *   <li>{@code %rbx} = {@code Method*} (HotSpot's "method receiver" register)</li>
 *   <li>GP arg slots in order: {@code j_rarg0..j_rarg5} =
 *       {@code rsi, rdx, rcx, r8, r9, rdi}.
 *       Note j_rarg5 is {@code rdi} (swapped vs the C ABI's c_rarg0=rdi).
 *       For instance methods the receiver is {@code j_rarg0} = rsi.</li>
 *   <li>FP arg slots: {@code xmm0..xmm7} (same as SysV C).</li>
 *   <li>Args beyond the register set spill to the Java stack at
 *       {@code [rsp + i*8]} (caller pre-call rsp; the call instruction
 *       pushes its own return PC).</li>
 *   <li>{@code %r15} = {@code JavaThread*} (preserved across all Java
 *       calling-convention crossings).</li>
 *   <li>Return regs: {@code %rax} for int/long/object/ref, {@code %xmm0}
 *       for float/double.</li>
 * </ul>
 *
 * <h2>State-transition contract</h2>
 *
 * Translated methods are entered through patched {@code Method::_from_*_entry}
 * stubs, not JNI native wrappers, so their nested Java calls start and finish
 * in {@code _thread_in_java}. Bootstrap/native callers may still enter this
 * bridge from {@code _thread_in_native}; only that case performs an explicit
 * native-to-Java transition around HotSpot's call_stub.
 *
 * <ol>
 *   <li>Publishes the JavaFrameAnchor ({@code _last_Java_sp/fp/pc}) for the
 *       synthetic call_stub frame.</li>
 *   <li>Loads args into the Java calling convention registers + Java stack.</li>
 *   <li>{@code call *<entry>} (the Method's {@code _from_compiled_entry})
 *       while leaving Java-state callers in Java.</li>
 *   <li>On return clears the temporary anchor and returns the result via
 *       rax/xmm0 packed into
 *       {@code out_rax} / {@code out_xmm0}.</li>
 * </ol>
 *
 * <h2>Object-arg / return marshalling</h2>
 *
 * HotSpot compiled entries take and return raw oops, not JNI handles. The
 * C dispatcher recovers raw oops from {@code jobject} args via the standard
 * tag-mask + deref. Returned raw oops go through
 * {@code neko_direct_oop_to_handle} into the active JNIHandleBlock so the
 * caller sees a normal {@code jobject}.
 *
 * <h2>Architecture coverage</h2>
 *
 * x86-64 SysV (Linux + macOS) implemented. x86-64 Windows + AArch64 SysV
 * emit per-shape stubs that abort with a clear diagnostic until their
 * backends land. The runtime guard {@code g_neko_direct_invoke_ready}
 * ensures call sites only enter the dispatcher when the layout walker has
 * resolved every required offset.
 */
public final class NativeToJavaInvokeEmitter {

    private final LinkedHashMap<String, SignaturePlan.Shape> shapes = new LinkedHashMap<>();

    public NativeToJavaInvokeEmitter() {}

    public static String shapeKey(SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        sb.append(shape.isStatic() ? 'S' : 'V').append(':');
        sb.append(shape.returnKind()).append(':');
        for (char a : shape.argKinds()) sb.append(a);
        return sb.toString();
    }

    public String register(SignaturePlan.Shape shape) {
        String key = shapeKey(shape);
        shapes.putIfAbsent(key, shape);
        return dispatcherSymbol(key);
    }

    public boolean isEmpty() { return shapes.isEmpty(); }
    public Set<String> shapeKeys() { return new LinkedHashSet<>(shapes.keySet()); }
    public List<SignaturePlan.Shape> registeredShapes() { return List.copyOf(shapes.values()); }
    public boolean hasShape(SignaturePlan.Shape shape) { return shapes.containsKey(shapeKey(shape)); }

    public static String dispatcherSymbol(String key) {
        return "neko_njx_" + key.replace(':', '_');
    }
    public static String trampolineSymbol(String key) {
        return "neko_njx_tramp_" + key.replace(':', '_');
    }
    public static String trampolineBeginSymbol(String key) {
        return trampolineSymbol(key) + "_begin";
    }
    public static String trampolineEndSymbol(String key) {
        return trampolineSymbol(key) + "_end";
    }

    public String renderPrelude() {
        if (shapes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("/* === Native→Java direct invoke (forward decls) === */\n");
        sb.append("typedef struct neko_njx_runtime {\n")
          .append("    ptrdiff_t off_last_Java_fp;\n")
          .append("    ptrdiff_t off_last_Java_pc;\n")
          .append("    ptrdiff_t off_last_Java_sp;\n")
          .append("    ptrdiff_t off_thread_state;\n")
          .append("    ptrdiff_t off_thread_polling_word;\n")
          .append("    int32_t state_in_native_trans;\n")
          .append("    int32_t state_in_java;\n")
          .append("    int32_t state_in_native;\n")
          .append("    int32_t _pad;\n")
          .append("    void *heap_base;\n")
          .append("} neko_njx_runtime_t;\n");
        sb.append("typedef jvalue (*neko_njx_dispatcher_t)(void *thread, JNIEnv *env, void *method_ptr, void *entry_point, jobject receiver, const jvalue *args);\n");
        sb.append("typedef void (*neko_call_stub_t)(void*, intptr_t*, int32_t, void*, void*, intptr_t*, int32_t, void*);\n");
        sb.append("extern void *g_neko_call_stub_entry;\n");
        sb.append("extern ptrdiff_t g_neko_off_jcw_anchor;\n");
        /* Forward declare the priv-heap allocator from MethodPatcherEmitter. */
        sb.append("static void *neko_priv_alloc_njx_trampoline(void *code_start, void *code_end, int frame_size_words);\n");
        for (Map.Entry<String, SignaturePlan.Shape> e : shapes.entrySet()) {
            String key = e.getKey();
            sb.append("static jvalue ").append(dispatcherSymbol(e.getKey()))
              .append("(void *thread, JNIEnv *env, void *method_ptr, void *entry_point, jobject receiver, const jvalue *args);\n");
            sb.append("extern char ").append(trampolineBeginSymbol(key)).append("[];\n");
            sb.append("extern char ").append(trampolineEndSymbol(key)).append("[];\n");
            /* Per-shape priv-heap trampoline PC, populated at OnLoad time. */
            sb.append("static void *").append(wrapperGlobalName(key)).append(" = NULL;\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    public static String wrapperGlobalName(String key) {
        return "g_njx_wrapper_" + key.replace(':', '_');
    }

    /** Render a function that the JNI_OnLoad bootstrap invokes once the
     * priv heap is registered. It allocates priv-heap wrappers for every
     * registered shape so subsequent dispatches go through CodeBlob-
     * recognized frames. */
    public String renderInitFunction() {
        if (shapes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("static void neko_njx_init_wrappers(void) {\n");
        for (Map.Entry<String, SignaturePlan.Shape> e : shapes.entrySet()) {
            String key = e.getKey();
            int frameSize = njxFrameSizeWords(computeLayout(e.getValue()).stackArgs());
            sb.append("    ").append(wrapperGlobalName(key))
              .append(" = NULL;\n");
        }
        sb.append("    NEKO_DIRECT_LOG(\"njx call_stub dispatchers initialized: ").append(shapes.size()).append(" shapes\");\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    public String renderBodies() {
        if (shapes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("/* === Native→Java direct invoke implementations === */\n");
        sb.append(renderHelpers());
        sb.append(renderCallStubHandleHelpers());
        /* Per-shape naked trampolines + C dispatchers; per-arch sections. */
        sb.append("#if defined(__x86_64__) && (defined(__linux__) || defined(__APPLE__))\n");
        for (Map.Entry<String, SignaturePlan.Shape> e : shapes.entrySet()) {
            sb.append(renderShapeCallStub(e.getKey(), e.getValue()));
        }
        sb.append("#else\n");
        for (Map.Entry<String, SignaturePlan.Shape> e : shapes.entrySet()) {
            sb.append(renderShapeUnsupported(e.getKey(), e.getValue()));
        }
        sb.append("#endif\n\n");
        return sb.toString();
    }

    private String renderCallStubHandleHelpers() {
        return """
NEKO_FAST_INLINE void *neko_njx_install_java_handles(void *thread) {
    if (thread == NULL || g_neko_off_thread_active_handles <= 0) return NULL;
    return *(void**)((char*)thread + g_neko_off_thread_active_handles);
}

NEKO_FAST_INLINE void neko_njx_restore_java_handles(void *thread, void *old_handles, void *java_handles) {
    (void)java_handles;
    if (thread != NULL && g_neko_off_thread_active_handles > 0) {
        *(void**)((char*)thread + g_neko_off_thread_active_handles) = old_handles;
    }
}

NEKO_FAST_INLINE int32_t neko_njx_result_basic_type(char ret) {
    switch (ret) {
        case 'J': return g_neko_basictype_long;
        case 'F': return g_neko_basictype_float;
        case 'D': return g_neko_basictype_double;
        case 'L': return g_neko_basictype_object;
        default:  return g_neko_basictype_int;
    }
}

typedef struct {
    void *stub;
    void *link;
    intptr_t *result;
    int32_t result_type;
    void *method;
    void *entry;
    intptr_t *params;
    int32_t size;
    void *thread;
} neko_call_stub_args_t;

#if defined(__x86_64__) && (defined(__linux__) || defined(__APPLE__))
__attribute__((naked, noinline, used))
static void neko_call_stub_guarded(neko_call_stub_args_t *a) {
    __asm__ volatile (
        "pushq %%rbp\\n"
        "movq  %%rsp, %%rbp\\n"
        "pushq %%rbx\\n"
        "pushq %%r12\\n"
        "pushq %%r13\\n"
        "pushq %%r14\\n"
        "pushq %%r15\\n"
        "subq  $8, %%rsp\\n"
        "movq  %%rdi, %%r10\\n"
        "movq  64(%%r10), %%rax\\n"
        "pushq %%rax\\n"
        "movslq 56(%%r10), %%rax\\n"
        "pushq %%rax\\n"
        "movq  8(%%r10), %%rdi\\n"
        "movq  16(%%r10), %%rsi\\n"
        "movslq 24(%%r10), %%rdx\\n"
        "movq  32(%%r10), %%rcx\\n"
        "movq  40(%%r10), %%r8\\n"
        "movq  48(%%r10), %%r9\\n"
        "movq  0(%%r10), %%r11\\n"
        "call *%%r11\\n"
        "addq  $24, %%rsp\\n"
        "popq  %%r15\\n"
        "popq  %%r14\\n"
        "popq  %%r13\\n"
        "popq  %%r12\\n"
        "popq  %%rbx\\n"
        "popq  %%rbp\\n"
        "ret\\n"
        :
        :
        : "memory"
    );
}
#else
static void neko_call_stub_guarded(neko_call_stub_args_t *a) {
    ((neko_call_stub_t)a->stub)(a->link, a->result, a->result_type, a->method,
        a->entry, a->params, a->size, a->thread);
}
#endif

static jvalue neko_njx_dispatch_generic(
    void *thread, JNIEnv *env, void *method_ptr, void *entry_point,
    jobject receiver, const jvalue *args, const char *arg_kinds,
    char ret, int is_static, const char *shape
) {
    jvalue result; result.j = 0;
    (void)env;
    if (!g_neko_direct_invoke_ready || method_ptr == NULL || entry_point == NULL || thread == NULL) {
        fprintf(stderr, "[neko-direct] precondition failed shape=%s ready=%d m=%p e=%p t=%p\\n",
            shape, (int)g_neko_direct_invoke_ready, method_ptr, entry_point, thread);
        abort();
    }
    neko_njx_note_dispatch();
    intptr_t call_params[96]; memset(call_params, 0, sizeof(call_params));
    int __njx_pos = 0;
    jobject __njx_receiver_handle = is_static ? NULL : neko_njx_stable_handle(thread, receiver, shape);
    if (!is_static) call_params[__njx_pos++] = (intptr_t)(uintptr_t)neko_njx_handle_to_oop(thread, __njx_receiver_handle);
    for (int i = 0; arg_kinds != NULL && arg_kinds[i] != '\\0'; i++) {
        if (__njx_pos + 2 >= (int)(sizeof(call_params) / sizeof(call_params[0]))) {
            fprintf(stderr, "[neko-direct] too many call params shape=%s\\n", shape);
            abort();
        }
        switch (arg_kinds[i]) {
            case 'L': {
                jobject __njx_arg_handle = neko_njx_stable_handle(thread, args[i].l, shape);
                call_params[__njx_pos++] = (intptr_t)(uintptr_t)neko_njx_handle_to_oop(thread, __njx_arg_handle);
                break;
            }
            case 'J':
                *(jlong*)(call_params + 1 + __njx_pos) = args[i].j;
                __njx_pos += 2;
                break;
            case 'F':
                *(jfloat*)(call_params + __njx_pos) = args[i].f;
                __njx_pos++;
                break;
            case 'D':
                *(jdouble*)(call_params + 1 + __njx_pos) = args[i].d;
                __njx_pos += 2;
                break;
            default:
                *(jint*)(call_params + __njx_pos) = args[i].i;
                __njx_pos++;
                break;
        }
    }

    int64_t out_rax = 0;
    void *__njx_ret_oop = NULL;
    double out_xmm0 = 0.0;
    neko_handle_save_t __njx_hsave;
    neko_handle_save(thread, &__njx_hsave);
    void *__njx_old_handles = __njx_hsave.block;
    void *__njx_java_handles = neko_njx_install_java_handles(thread);
    if (__njx_java_handles == NULL) {
        fprintf(stderr, "[neko-direct] Java handle block unavailable shape=%s\\n", shape);
        abort();
    }
    void *saved_sp = (g_neko_off_last_Java_sp > 0) ? *(void**)((char*)thread + g_neko_off_last_Java_sp) : NULL;
    void *saved_pc = (g_neko_off_last_Java_pc > 0) ? *(void**)((char*)thread + g_neko_off_last_Java_pc) : NULL;
    void *saved_fp = (g_neko_off_last_Java_fp > 0) ? *(void**)((char*)thread + g_neko_off_last_Java_fp) : NULL;
    uint8_t __jcw_buf[256] __attribute__((aligned(16))); memset(__jcw_buf, 0, sizeof(__jcw_buf));
    if (g_neko_call_stub_entry == NULL || g_neko_off_jcw_anchor <= 0
        || g_neko_method_layout.off_frame_anchor_sp < 0
        || g_neko_method_layout.off_frame_anchor_pc < 0
        || g_neko_method_layout.off_frame_anchor_fp < 0
        || (size_t)(g_neko_off_jcw_anchor + g_neko_method_layout.off_frame_anchor_fp + (ptrdiff_t)sizeof(void*)) > sizeof(__jcw_buf)) {
        fprintf(stderr, "[neko-direct] call_stub precondition failed shape=%s entry=%p jcw_anchor=%td\\n",
            shape, g_neko_call_stub_entry, g_neko_off_jcw_anchor);
        abort();
    }
    *(void**)(__jcw_buf + 0) = thread;
    *(void**)(__jcw_buf + 8) = __njx_old_handles;
    *(void**)(__jcw_buf + 16) = method_ptr;
    *(void**)(__jcw_buf + 24) = is_static ? NULL : (void*)(uintptr_t)call_params[0];
    char *__jcw_anchor = (char*)__jcw_buf + g_neko_off_jcw_anchor;
    *(void**)(__jcw_anchor + g_neko_method_layout.off_frame_anchor_sp) = saved_sp;
    *(void**)(__jcw_anchor + g_neko_method_layout.off_frame_anchor_pc) = saved_pc;
    *(void**)(__jcw_anchor + g_neko_method_layout.off_frame_anchor_fp) = saved_fp;
    if (g_neko_off_last_Java_sp > 0) *(void**)((char*)thread + g_neko_off_last_Java_sp) = NULL;
    if (g_neko_off_last_Java_pc > 0) *(void**)((char*)thread + g_neko_off_last_Java_pc) = NULL;
    if (g_neko_off_last_Java_fp > 0) *(void**)((char*)thread + g_neko_off_last_Java_fp) = NULL;

    intptr_t __call_result[2]; __call_result[0] = 0; __call_result[1] = 0;
    NEKO_DIRECT_LOG("  -> call_stub shape=%s slots=%d saved_sp=%p", shape, __njx_pos, saved_sp);
    jboolean __njx_restore_native_state = JNI_FALSE;
    if (g_neko_off_thread_state > 0) {
        int32_t __entry_state = *(int32_t*)((char*)thread + g_neko_off_thread_state);
        if (__entry_state == g_neko_thread_state_in_native) {
            neko_transition_native_to_java(thread);
            __njx_restore_native_state = JNI_TRUE;
        } else if (__entry_state != g_neko_thread_state_in_java) {
            fprintf(stderr, "[neko-direct] generic call_stub entered in unsupported thread state shape=%s state=%d java=%d native=%d\\n",
                shape, __entry_state, g_neko_thread_state_in_java, g_neko_thread_state_in_native);
            abort();
        }
    }
    {
        neko_call_stub_args_t __stub_args = {
            g_neko_call_stub_entry, (void*)__jcw_buf, __call_result,
            neko_njx_result_basic_type(ret), method_ptr, entry_point,
            call_params, __njx_pos, thread
        };
        neko_call_stub_guarded(&__stub_args);
    }
    if (g_neko_off_thread_state > 0) {
        int32_t __state = *(int32_t*)((char*)thread + g_neko_off_thread_state);
        if (__njx_restore_native_state) {
            if (__state == g_neko_thread_state_in_java) {
                neko_transition_java_to_native(thread);
            } else if (__state != g_neko_thread_state_in_native) {
                fprintf(stderr, "[neko-direct] generic call_stub returned in unsupported native-caller state shape=%s state=%d java=%d native=%d\\n",
                    shape, __state, g_neko_thread_state_in_java, g_neko_thread_state_in_native);
                abort();
            }
        } else {
            if (__state != g_neko_thread_state_in_java) {
                if (__state == g_neko_thread_state_in_native) {
                    neko_transition_native_to_java(thread);
                    __state = *(int32_t*)((char*)thread + g_neko_off_thread_state);
                }
            }
            if (__state != g_neko_thread_state_in_java) {
                fprintf(stderr, "[neko-direct] generic call_stub returned outside _thread_in_java shape=%s state=%d expected=%d\\n",
                    shape, __state, g_neko_thread_state_in_java);
                abort();
            }
        }
    }
    out_rax = (int64_t)__call_result[0];
    memcpy(&out_xmm0, __call_result, sizeof(out_xmm0));
    NEKO_DIRECT_LOG("  <- call_stub shape=%s r=0x%llx xmm0=%g", shape, (unsigned long long)out_rax, out_xmm0);
    if (ret == 'L' && out_rax != 0) {
        __njx_ret_oop = neko_njx_result_ref_to_oop(thread, (void*)(uintptr_t)out_rax);
        if (__njx_ret_oop == NULL) {
            fprintf(stderr, "[neko-direct] object result could not be materialized shape=%s ref=%p\\n",
                shape, (void*)(uintptr_t)out_rax);
            abort();
        }
    }
    if (g_neko_off_last_Java_sp > 0) *(void**)((char*)thread + g_neko_off_last_Java_sp) = saved_sp;
    if (g_neko_off_last_Java_pc > 0) *(void**)((char*)thread + g_neko_off_last_Java_pc) = saved_pc;
    if (g_neko_off_last_Java_fp > 0) *(void**)((char*)thread + g_neko_off_last_Java_fp) = saved_fp;
    neko_njx_restore_java_handles(thread, __njx_old_handles, __njx_java_handles);
    neko_handle_restore(&__njx_hsave);
    switch (ret) {
        case 'V': break;
        case 'J': result.j = (jlong)out_rax; break;
        case 'F': { float __f = (float)out_xmm0; result.f = __f; break; }
        case 'D': result.d = (jdouble)out_xmm0; break;
        case 'L':
            result.l = neko_njx_oop_to_handle(thread, __njx_ret_oop);
            if (getenv("NEKO_HANDLE_DEBUG") != NULL) {
                fprintf(stderr, "[neko-debug] njx object result shape=%s oop=%p handle=%p\\n",
                    shape, __njx_ret_oop, (void*)result.l);
            }
            break;
        default:  result.i = (jint)(int32_t)out_rax; break;
    }
    return result;
}

""";
    }

    private String renderHelpers() {
        return """
/* Convert a jobject-like reference to its raw oop. This may already be a raw
 * oop when direct call_stub re-enters translated native code. */
static void neko_persist_current_handle_slot(void *thread, jobject handle, const char *what);

NEKO_FAST_INLINE void *neko_njx_handle_to_oop(void *thread, jobject handle) {
    return neko_thread_ref_oop(thread, handle);
}

NEKO_FAST_INLINE jobject neko_njx_stable_handle(void *thread, jobject ref, const char *shape) {
    void *oop;
    if (ref == NULL) return NULL;
    if (neko_thread_ref_is_live_handle(thread, ref)) return ref;
    oop = neko_thread_ref_oop(thread, ref);
    if (oop == NULL) {
        fprintf(stderr, "[neko-direct] object argument could not be materialized shape=%s ref=%p\\n",
            shape != NULL ? shape : "?", (void*)ref);
        abort();
    }
    return neko_direct_oop_to_handle(thread, oop);
}

/* Wrap a raw oop returned from compiled Java back into a jobject via the
 * active JNIHandleBlock. Reuses the AALOAD fast path's helper. */
NEKO_FAST_INLINE jobject neko_njx_oop_to_handle(void *thread, void *oop) {
    jobject handle;
    if (oop == NULL) return NULL;
    handle = neko_direct_oop_to_handle(thread, oop);
    neko_persist_current_handle_slot(thread, handle, "native-to-java result");
    return handle;
}

NEKO_FAST_INLINE void *neko_njx_result_ref_to_oop(void *thread, void *ref) {
    if (ref == NULL) return NULL;
    if (neko_thread_ref_is_live_handle(thread, (jobject)ref)) {
        return neko_thread_ref_oop(thread, (jobject)ref);
    }
    return neko_zgc_good_oop(ref);
}

/* Resolve Method* + _from_interpreted_entry. HotSpot's call_stub builds an
 * interpreter-shaped argument stack, so direct calls must enter through
 * Method::_from_interpreted_entry. */
static int neko_njx_resolve_method_entry(void *m, void **out_method, void **out_entry) {
    if (m == NULL || out_method == NULL || out_entry == NULL) return 0;
    if (!g_neko_method_layout.initialized || !g_neko_method_layout.usable) {
        NEKO_DIRECT_LOG("resolve_entry: layout not ready (init=%d usable=%d)",
            (int)g_neko_method_layout.initialized, (int)g_neko_method_layout.usable);
        return 0;
    }
    if (g_neko_method_layout.off_method_from_interpreted_entry <= 0) {
        NEKO_DIRECT_LOG("resolve_entry: interpreted-entry offset unresolved");
        return 0;
    }
    void *e = *(void**)((char*)m + g_neko_method_layout.off_method_from_interpreted_entry);
    if (e == NULL) {
        NEKO_DIRECT_LOG("resolve_entry: Method* %p has NULL _from_interpreted_entry", m);
        return 0;
    }
    *out_method = m;
    *out_entry  = e;
    return 1;
}

/* Resolve from a native jmethodID cell created by neko_make_native_method_id. */
static int neko_njx_resolve_entry(jmethodID mid, void **out_method, void **out_entry) {
    if (mid == NULL || out_method == NULL || out_entry == NULL) return 0;
    void *m = *(void**)mid;
    if (m == NULL) {
        NEKO_DIRECT_LOG("resolve_entry: jmethodID %p deref to NULL Method*", mid);
        return 0;
    }
    return neko_njx_resolve_method_entry(m, out_method, out_entry);
}

""";
    }

    private record X86Layout(int gpCount, int xmmCount, int stackArgs, int[] argLoc, int[] argRegIdx, int[] argStackIdx) {}

    private static X86Layout computeLayout(SignaturePlan.Shape shape) {
        char[] args = shape.argKinds();
        int gpCount = shape.isStatic() ? 0 : 1;
        int xmmCount = 0;
        int stackArgs = 0;
        int[] argLoc = new int[args.length];
        int[] argRegIdx = new int[args.length];
        int[] argStackIdx = new int[args.length];
        for (int i = 0; i < args.length; i++) {
            char a = args[i];
            if (a == 'F' || a == 'D') {
                if (xmmCount < 8) { argLoc[i] = 1; argRegIdx[i] = xmmCount++; }
                else              { argLoc[i] = 2; argStackIdx[i] = stackArgs++; }
            } else {
                if (gpCount < 6)  { argLoc[i] = 0; argRegIdx[i] = gpCount++; }
                else              { argLoc[i] = 2; argStackIdx[i] = stackArgs++; }
            }
        }
        return new X86Layout(gpCount, xmmCount, stackArgs, argLoc, argRegIdx, argStackIdx);
    }

    private static int njxFrameSizeWords(int stackArgs) {
        return 8 + stackArgs + ((stackArgs & 1) == 0 ? 0 : 1);
    }

    private static String resultBasicTypeExpr(char ret) {
        return switch (ret) {
            case 'J' -> "g_neko_basictype_long";
            case 'F' -> "g_neko_basictype_float";
            case 'D' -> "g_neko_basictype_double";
            case 'L' -> "g_neko_basictype_object";
            default -> "g_neko_basictype_int";
        };
    }

    /* ----------------------------------------------------------------
     * x86-64 SysV: per-shape dispatcher + naked trampoline.
     *
     * The naked trampoline takes a C-ABI signature with primitives passed
     * as int64_t/double, plus arrays for stack-bound args:
     *
     *   void neko_njx_tramp_<S>(
     *       void *thread,           // JavaThread* (rdi)
     *       void *method_ptr,       // Method* — goes to %rbx (rsi)
     *       void *entry_point,      // _from_compiled_entry — call target (rdx)
     *       const int64_t *gp_args, // values for j_rarg0..N (rcx)
     *       const double  *fp_args, // values for xmm0..N (r8)
     *       const int64_t *stack_args, int n_stack_args, // (r9, [rbp+16])
     *       int64_t *out_rax,       // [rbp+24]
     *       double  *out_xmm0       // [rbp+32]
     *   );
     *
     * The C dispatcher composes those arrays from the jvalue[] args and
     * calls the trampoline. Per-shape because the ARG COUNT into the
     * Java calling convention is fixed at compile time — but the ORDER
     * of register loads is shape-independent (j_rarg0 = gp_args[0], etc).
     * ---------------------------------------------------------------- */
    private String renderShapeX86_64SysV(String key, SignaturePlan.Shape shape) {
        char ret = shape.returnKind();
        char[] args = shape.argKinds();
        boolean isStatic = shape.isStatic();
        String fn = dispatcherSymbol(key);
        String tramp = trampolineSymbol(key);
        String trampBegin = trampolineBeginSymbol(key);
        String trampEnd = trampolineEndSymbol(key);

        /* Java calling convention split:
         *   - receiver (if instance) goes to j_rarg0 first
         *   - then args in order: int/object/long → next j_rarg or stack;
         *     float/double → next xmm or stack
         *   - long/double take 1 slot in regs but 2 slots on stack
         *     (we compress to 1 slot here since both ABI lanes pass them
         *     in 8-byte slots)
         */
        X86Layout layout = computeLayout(shape);
        int gpCount = layout.gpCount();
        int xmmCount = layout.xmmCount();
        int stackArgs = layout.stackArgs();
        int javaSlots = isStatic ? 0 : 1;
        for (char a : args) javaSlots += (a == 'J' || a == 'D') ? 2 : 1;
        int[] argLoc = layout.argLoc();      // 0=GP, 1=XMM, 2=stack
        int[] argRegIdx = layout.argRegIdx();
        int[] argStackIdx = layout.argStackIdx();

        StringBuilder sb = new StringBuilder();
        sb.append("/* shape ").append(key).append(" — ");
        sb.append(isStatic ? "static" : "instance").append(" args=\"");
        for (char a : args) sb.append(a);
        sb.append("\" ret=").append(ret).append(" gp=").append(gpCount).append(" xmm=").append(xmmCount)
          .append(" stk=").append(stackArgs).append(" (x86_64 SysV) */\n");

        /* ---- Naked trampoline ---- */
        sb.append("__attribute__((naked, used, visibility(\"hidden\")))\n");
        sb.append("static void ").append(tramp)
          .append("(void *thread, void *method_ptr, void *entry_point,\n")
          .append("             const int64_t *gp_args, const double *fp_args,\n")
          .append("             const int64_t *stack_args, int n_stack_args,\n")
          .append("             int64_t *out_rax, double *out_xmm0,\n")
          .append("             const neko_njx_runtime_t *rt) {\n");
        sb.append("    __asm__ volatile (\n");
        sb.append("        \".globl ").append(trampBegin).append("\\n\"\n");
        sb.append("        \"").append(trampBegin).append(":\\n\"\n");
        /* SysV C ABI on entry to this naked function:
         *   rdi=thread, rsi=method_ptr, rdx=entry_point,
         *   rcx=gp_args, r8=fp_args, r9=stack_args,
         *   [rbp+16]=n_stack_args (after pushq rbp; mov rsp,rbp),
         *   [rbp+24]=out_rax, [rbp+32]=out_xmm0, [rbp+40]=rt
         *
         * CRITICAL: HotSpot uses %r12 as r12_heapbase when compressed oops
         * are enabled. Compiled Java code does narrow-oop decode as
         * `oop = r12 + (narrow << shift)`. Clobbering %r12 with anything
         * other than the heap base will cause every narrow-oop deref to
         * land at a bogus address (crash). HotSpot also uses %r15 as the
         * thread register (j_thread_reg).
         *
         * We MUST preserve %r12 = heap_base across the call (HotSpot set it
         * up before our JNI native body was entered; we just don't disturb
         * it). We use %r15 for both thread-pointer storage AND the "thread
         * register" HotSpot expects. */
        sb.append("        \"pushq %%rbp\\n\"\n");
        sb.append("        \"movq  %%rsp, %%rbp\\n\"\n");
        /* Save callee-saved regs we'll clobber: rbx, r12-r15. We DO save
         * r12 because we'll set it to the heap base before calling Java
         * (HotSpot's r12_heapbase convention). The pop in the epilogue
         * restores the original r12 our C caller had. */
        sb.append("        \"pushq %%rbx\\n\"\n");
        sb.append("        \"pushq %%r12\\n\"\n");
        sb.append("        \"pushq %%r13\\n\"\n");
        sb.append("        \"pushq %%r14\\n\"\n");
        sb.append("        \"pushq %%r15\\n\"\n");
        sb.append("        \"pushq 40(%%rbp)\\n\"     /* rt spill copied with the frame */\n");
        /* Stash incoming args into callee-saved regs so they survive the
         * call:
         *   r15 = thread (HotSpot's thread register; also our scratch)
         *   r13 = entry_point
         *   r14 = out_rax
         *   rbx = method_ptr (HotSpot's method receiver register)
         * out_xmm0 and rt we leave on stack — read them as needed.
         *
         * NOTE: We do NOT touch %r12. It already holds HotSpot's heap base
         * from when we entered our JNI native body. Compiled Java code we
         * call back into requires this. */
        sb.append("        \"movq  %%rdi, %%r15\\n\"     /* thread */\n");
        sb.append("        \"movq  %%rsi, %%rbx\\n\"     /* method_ptr -> rbx */\n");
        sb.append("        \"movq  %%rdx, %%r13\\n\"     /* entry */\n");
        sb.append("        \"movq  24(%%rbp), %%r14\\n\" /* out_rax */\n");
        /* Buffer the gp_args/fp_args/stack_args ptrs into temp regs that
         * we'll consume in arg load. We use r10 (gp), r11 (fp), rax (stack
         * source ptr). All are caller-save. */
        sb.append("        \"movq  %%rcx, %%r10\\n\"     /* gp_args ptr */\n");
        sb.append("        \"movq  %%r8,  %%r11\\n\"     /* fp_args ptr */\n");

        /* Push stack args FIRST (before we set up the call frame for
         * compiled entry). HotSpot's compiled callee reads stack args at
         * [rsp+8 + i*8] when its own prologue runs — i.e. after our
         * `call *r13` pushes the return PC. So at the moment of `call`,
         * rsp must point AT stk[0] (with the return PC about to be
         * pushed below it). Layout:
         *
         *   high addr
         *   ...
         *   [rbp+16]   n_stack_args (input arg slot)
         *   [rbp+8]    return PC of this naked
         *   [rbp+0]    saved rbp <- rbp
         *   [rbp-8]    saved rbx
         *   [rbp-16]   saved r12
         *   [rbp-24]   saved r13
         *   [rbp-32]   saved r14
         *   [rbp-40]   saved r15
         *   [rbp-48]   rt pointer
         *   ... pad to 16 if stack arg count is odd ...
         *   stk[N-1]
         *   ...
         *   stk[0]     <- rsp at the moment of `call *r13`
         *   <pushed return PC after call>
         *
         * So we pre-pad if stack arg count is odd, then push args in
         * reverse so stk[0] ends at the lowest rsp.
         */
        if (stackArgs > 0) {
            sb.append("        /* rbp-48 is 16-aligned; odd stack arg counts need one pad word. */\n");
            if ((stackArgs & 1) != 0) {
                sb.append("        \"subq  $8, %%rsp\\n\"\n");
            }
            /* Push stack args in reverse: stk[N-1] first, stk[0] last. */
            for (int i = stackArgs - 1; i >= 0; i--) {
                sb.append("        \"movq  ").append(i * 8).append("(%%r9), %%rax\\n\"\n");
                sb.append("        \"pushq %%rax\\n\"\n");
            }
        } else {
            /* No stack args. rsp = rbp - 48 is already 16-aligned. */
        }

        /* Load FP args from fp_args[] into xmm0..xmm(xmmCount-1). */
        for (int i = 0; i < xmmCount; i++) {
            sb.append("        \"movsd ").append(i * 8).append("(%%r11), %%xmm").append(i).append("\\n\"\n");
        }

        /* Load GP args from gp_args[] into the JAVA arg regs.
         * j_rarg0..j_rarg5 = rsi, rdx, rcx, r8, r9, rdi.
         * NOTE: rsi is also a SysV C-arg reg (the original method_ptr lived
         * there but we already moved it to rbx). We must load rdi LAST
         * because rdi is also the gp_args source (no — we moved gp_args to
         * r10 via rcx, so rdi is free). Order is irrelevant for correctness
         * because all sources come from r10[*] which is preserved. */
        String[] javaGpRegs = { "rsi", "rdx", "rcx", "r8", "r9", "rdi" };
        for (int i = 0; i < gpCount; i++) {
            sb.append("        \"movq  ").append(i * 8).append("(%%r10), %%").append(javaGpRegs[i]).append("\\n\"\n");
        }

        /* Publish JavaFrameAnchor pointing at OUR trampoline frame. HotSpot's
         * stack walker, when traversing from compiled-callee back through our
         * trampoline (which is registered as a BufferBlob in the priv heap),
         * uses the anchor to find the boundary at which to fall back to
         * walking the C call chain. Anchor.last_Java_pc points at the
         * instruction right after our `call *r13` so the walker associates
         * us with that label.
         *
         * Caller-managed restoration: the C dispatcher around us SAVES the
         * prior anchor (set by HotSpot's outer JNI native_wrapper for the
         * translated body's caller) before invoking the trampoline and
         * RESTORES it after. */
        sb.append("        \"movq -48(%%rbp), %%r11\\n\" /* rt */\n");
        sb.append("        \"movq 0(%%r11), %%rax\\n\"\n");
        sb.append("        \"movq %%rbp, (%%r15, %%rax)\\n\"\n");
        sb.append("        \"leaq 4f(%%rip), %%rax\\n\"\n");
        sb.append("        \"movq 8(%%r11), %%rcx\\n\"\n");
        sb.append("        \"movq %%rax, (%%r15, %%rcx)\\n\"\n");
        sb.append("        \"movq 16(%%r11), %%rax\\n\"\n");
        sb.append("        \"movq %%rsp, (%%r15, %%rax)\\n\"\n");

        /* Set %r12 to the heap base. HotSpot compiled code uses %r12 as
         * r12_heapbase for narrow-oop decode (oop = r12 + (narrow << shift)).
         * Zero-based compressed oops has heap_base = NULL → r12 = 0.
         *
         * We MUST do this even though %r12 is callee-save: HotSpot's
         * convention requires it for compiled methods to function. Our
         * caller restores their %r12 from our stack push when we return. */
        sb.append("        \"movq 56(%%r11), %%r12\\n\"\n");

        /* Call compiled entry. Method* in rbx, args in their slots. */
        sb.append("        \"call *%%r13\\n\"\n");
        sb.append("        \"4:\\n\"\n");

        /* Save result. r14 still holds out_rax pointer. */
        sb.append("        \"movq %%rax, (%%r14)\\n\"\n");
        sb.append("        \"movq 32(%%rbp), %%r14\\n\"\n");
        sb.append("        \"movsd %%xmm0, (%%r14)\\n\"\n");
        sb.append("        \"movq -48(%%rbp), %%r11\\n\" /* reload rt after Java clobbers */\n");

        /* Clear the anchor we set up before the call. The C dispatcher
         * around us will subsequently restore the OUTER anchor (the one
         * HotSpot's native_wrapper had set on entry to the translated
         * body) — we just clear ours so it doesn't bleed past return. */
        sb.append("        \"movq 16(%%r11), %%rax\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%rax)\\n\"\n");
        sb.append("        \"movq 0(%%r11), %%rax\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%rax)\\n\"\n");
        sb.append("        \"movq 8(%%r11), %%rax\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%rax)\\n\"\n");

        /* Epilogue: lea rsp back to right after the 5 callee-save pushes
         * plus the rt spill (48 bytes), discard the spill, pop regs, return. The leaq
         * folds back over any stack args + pad. */
        sb.append("        \"leaq -48(%%rbp), %%rsp\\n\"\n");
        sb.append("        \"addq $8, %%rsp\\n\"\n");
        sb.append("        \"popq %%r15\\n\"\n");
        sb.append("        \"popq %%r14\\n\"\n");
        sb.append("        \"popq %%r13\\n\"\n");
        sb.append("        \"popq %%r12\\n\"\n");
        sb.append("        \"popq %%rbx\\n\"\n");
        sb.append("        \"popq %%rbp\\n\"\n");
        sb.append("        \"ret\\n\"\n");
        sb.append("        \".globl ").append(trampEnd).append("\\n\"\n");
        sb.append("        \"").append(trampEnd).append(":\\n\"\n");
        sb.append("        :\n");
        sb.append("        :\n");
        sb.append("        : \"memory\"\n");
        sb.append("    );\n");
        sb.append("}\n\n");

        /* ---- C dispatcher: marshals jvalue[] -> trampoline arrays ---- */
        sb.append("static jvalue ").append(fn)
          .append("(void *thread, JNIEnv *env, void *method_ptr, void *entry_point, jobject receiver, const jvalue *args) {\n");
        sb.append("    jvalue result; result.j = 0;\n");
        sb.append("    (void)env;\n");
        sb.append("    if (!g_neko_direct_invoke_ready || method_ptr == NULL || entry_point == NULL || thread == NULL) {\n");
        sb.append("        fprintf(stderr, \"[neko-direct] precondition failed shape=").append(key)
          .append(" ready=%d m=%p e=%p t=%p\\n\", (int)g_neko_direct_invoke_ready, method_ptr, entry_point, thread); abort();\n");
        sb.append("    }\n");
        sb.append("    neko_njx_note_dispatch();\n");
        sb.append("    NEKO_DIRECT_LOG(\"dispatch shape=").append(key).append(" m=%p e=%p recv=%p\", method_ptr, entry_point, (void*)receiver);\n");
        sb.append("    int64_t gp_args[").append(Math.max(gpCount, 1)).append("];\n");
        sb.append("    double  fp_args[").append(Math.max(xmmCount, 1)).append("];\n");
        if (stackArgs > 0) sb.append("    int64_t stack_args[").append(stackArgs).append("];\n");
        else sb.append("    int64_t stack_args[1] = {0};\n");

        /* Receiver as raw oop -> gp[0] */
        if (!isStatic) {
            sb.append("    gp_args[0] = (int64_t)(uintptr_t)neko_njx_handle_to_oop(thread, receiver);\n");
        }
        for (int i = 0; i < args.length; i++) {
            String dst = (argLoc[i] == 0) ? ("gp_args[" + argRegIdx[i] + "]")
                       : (argLoc[i] == 1) ? ("fp_args[" + argRegIdx[i] + "]")
                                          : ("stack_args[" + argStackIdx[i] + "]");
            switch (args[i]) {
                case 'L' -> sb.append("    ").append(dst).append(" = (int64_t)(uintptr_t)neko_njx_handle_to_oop(thread, args[").append(i).append("].l);\n");
                case 'J' -> sb.append("    ").append(dst).append(" = (int64_t)args[").append(i).append("].j;\n");
                case 'F' -> {
                    if (argLoc[i] == 1) sb.append("    fp_args[").append(argRegIdx[i]).append("] = (double)args[").append(i).append("].f;\n");
                    else sb.append("    { int32_t __t; memcpy(&__t, &args[").append(i).append("].f, sizeof(__t)); stack_args[").append(argStackIdx[i]).append("] = (int64_t)__t; }\n");
                }
                case 'D' -> {
                    if (argLoc[i] == 1) sb.append("    fp_args[").append(argRegIdx[i]).append("] = args[").append(i).append("].d;\n");
                    else sb.append("    { int64_t __t; memcpy(&__t, &args[").append(i).append("].d, sizeof(__t)); stack_args[").append(argStackIdx[i]).append("] = __t; }\n");
                }
                default -> sb.append("    ").append(dst).append(" = (int64_t)(int32_t)args[").append(i).append("].i;\n");
            }
        }

        sb.append("    intptr_t call_params[").append(Math.max(javaSlots, 1)).append("]; memset(call_params, 0, sizeof(call_params));\n");
        sb.append("    int __njx_pos = 0;\n");
        if (!isStatic) {
            sb.append("    call_params[__njx_pos++] = (intptr_t)(uintptr_t)neko_njx_handle_to_oop(thread, receiver);\n");
        }
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case 'L' -> sb.append("    call_params[__njx_pos++] = (intptr_t)(uintptr_t)neko_njx_handle_to_oop(thread, args[").append(i).append("].l);\n");
                case 'J' -> sb.append("    *(jlong*)(call_params + 1 + __njx_pos) = args[").append(i).append("].j; __njx_pos += 2;\n");
                case 'F' -> sb.append("    *(jfloat*)(call_params + __njx_pos) = args[").append(i).append("].f; __njx_pos++;\n");
                case 'D' -> sb.append("    *(jdouble*)(call_params + 1 + __njx_pos) = args[").append(i).append("].d; __njx_pos += 2;\n");
                default -> sb.append("    *(jint*)(call_params + __njx_pos) = args[").append(i).append("].i; __njx_pos++;\n");
            }
        }

        sb.append("    int64_t out_rax = 0;\n");
        sb.append("    void *__njx_ret_oop = NULL;\n");
        sb.append("    double  out_xmm0 = 0.0;\n");
        /* Save handle-block top so any handles created by Java code during
         * the nested call are bounded to this call's scope. */
        sb.append("    neko_handle_save_t __njx_hsave;\n");
        sb.append("    neko_handle_save(thread, &__njx_hsave);\n");
        sb.append("    void *__njx_old_handles = __njx_hsave.block;\n");
        sb.append("    void *__njx_java_handles = neko_njx_install_java_handles(thread);\n");
        sb.append("    if (__njx_java_handles == NULL) { fprintf(stderr, \"[neko-direct] Java handle block unavailable shape=").append(key).append("\\n\"); abort(); }\n");
        /* Save the OUTER JavaFrameAnchor (set by HotSpot's native_wrapper
         * for our translated body's caller). The trampoline overwrites the
         * anchor with its own values — we restore the outer one after.
         * Mirrors what JavaCallWrapper::~JavaCallWrapper() does. */
        sb.append("    void *saved_sp = (g_neko_off_last_Java_sp > 0) ? *(void**)((char*)thread + g_neko_off_last_Java_sp) : NULL;\n");
        sb.append("    void *saved_pc = (g_neko_off_last_Java_pc > 0) ? *(void**)((char*)thread + g_neko_off_last_Java_pc) : NULL;\n");
        sb.append("    void *saved_fp = (g_neko_off_last_Java_fp > 0) ? *(void**)((char*)thread + g_neko_off_last_Java_fp) : NULL;\n");
        sb.append("    uint8_t __jcw_buf[256] __attribute__((aligned(16))); memset(__jcw_buf, 0, sizeof(__jcw_buf));\n");
        sb.append("    if (g_neko_call_stub_entry == NULL || g_neko_off_jcw_anchor <= 0\n")
          .append("        || g_neko_method_layout.off_frame_anchor_sp < 0\n")
          .append("        || g_neko_method_layout.off_frame_anchor_pc < 0\n")
          .append("        || g_neko_method_layout.off_frame_anchor_fp < 0\n")
          .append("        || (size_t)(g_neko_off_jcw_anchor + g_neko_method_layout.off_frame_anchor_fp + (ptrdiff_t)sizeof(void*)) > sizeof(__jcw_buf)) {\n")
          .append("        fprintf(stderr, \"[neko-direct] call_stub precondition failed shape=").append(key).append(" entry=%p jcw_anchor=%td\\n\", g_neko_call_stub_entry, g_neko_off_jcw_anchor); abort();\n")
          .append("    }\n");
        sb.append("    *(void**)(__jcw_buf + 0) = thread;\n");
        sb.append("    *(void**)(__jcw_buf + 8) = __njx_old_handles;\n");
        sb.append("    *(void**)(__jcw_buf + 16) = method_ptr;\n");
        sb.append("    *(void**)(__jcw_buf + 24) = ").append(isStatic ? "NULL" : "(void*)(uintptr_t)call_params[0]").append(";\n");
        sb.append("    char *__jcw_anchor = (char*)__jcw_buf + g_neko_off_jcw_anchor;\n");
        sb.append("    *(void**)(__jcw_anchor + g_neko_method_layout.off_frame_anchor_sp) = saved_sp;\n");
        sb.append("    *(void**)(__jcw_anchor + g_neko_method_layout.off_frame_anchor_pc) = saved_pc;\n");
        sb.append("    *(void**)(__jcw_anchor + g_neko_method_layout.off_frame_anchor_fp) = saved_fp;\n");
        /* JavaCallWrapper copies the current thread anchor into the wrapper,
         * then clears JavaThread::_anchor before entering Java. Leaving the
         * stale outer anchor visible while Java runs makes VM stack walks
         * start from the wrong boundary (counter overflow, fillInStackTrace,
         * GC stack walking). */
        sb.append("    if (g_neko_off_last_Java_sp > 0) *(void**)((char*)thread + g_neko_off_last_Java_sp) = NULL;\n");
        sb.append("    if (g_neko_off_last_Java_pc > 0) *(void**)((char*)thread + g_neko_off_last_Java_pc) = NULL;\n");
        sb.append("    if (g_neko_off_last_Java_fp > 0) *(void**)((char*)thread + g_neko_off_last_Java_fp) = NULL;\n");
        sb.append("    intptr_t __call_result[2]; __call_result[0] = 0; __call_result[1] = 0;\n");
        sb.append("    NEKO_DIRECT_LOG(\"  -> call_stub shape=").append(key).append(" slots=").append(javaSlots).append(" saved_sp=%p\", saved_sp);\n");
        sb.append("    jboolean __njx_restore_native_state = JNI_FALSE;\n");
        sb.append("    if (g_neko_off_thread_state > 0) {\n");
        sb.append("        int32_t __entry_state = *(int32_t*)((char*)thread + g_neko_off_thread_state);\n");
        sb.append("        if (__entry_state == g_neko_thread_state_in_native) { neko_transition_native_to_java(thread); __njx_restore_native_state = JNI_TRUE; }\n");
        sb.append("        else if (__entry_state != g_neko_thread_state_in_java) { fprintf(stderr, \"[neko-direct] call_stub entered in unsupported thread state shape=").append(key).append(" state=%d java=%d native=%d\\n\", __entry_state, g_neko_thread_state_in_java, g_neko_thread_state_in_native); abort(); }\n");
        sb.append("    }\n");
        sb.append("    {\n");
        sb.append("        neko_call_stub_args_t __stub_args = { g_neko_call_stub_entry, (void*)__jcw_buf, __call_result, ")
          .append(resultBasicTypeExpr(ret)).append(", method_ptr, entry_point, call_params, ")
          .append(javaSlots).append(", thread };\n");
        sb.append("        neko_call_stub_guarded(&__stub_args);\n");
        sb.append("    }\n");
        sb.append("    if (g_neko_off_thread_state > 0) { int32_t __state = *(int32_t*)((char*)thread + g_neko_off_thread_state); if (__njx_restore_native_state) { if (__state == g_neko_thread_state_in_java) { neko_transition_java_to_native(thread); } else if (__state != g_neko_thread_state_in_native) { fprintf(stderr, \"[neko-direct] call_stub returned in unsupported native-caller state shape=").append(key).append(" state=%d java=%d native=%d\\n\", __state, g_neko_thread_state_in_java, g_neko_thread_state_in_native); abort(); } } else { if (__state != g_neko_thread_state_in_java) { if (__state == g_neko_thread_state_in_native) { neko_transition_native_to_java(thread); __state = *(int32_t*)((char*)thread + g_neko_off_thread_state); } } if (__state != g_neko_thread_state_in_java) { fprintf(stderr, \"[neko-direct] call_stub returned outside _thread_in_java shape=").append(key).append(" state=%d expected=%d\\n\", __state, g_neko_thread_state_in_java); abort(); } } }\n");
        sb.append("    out_rax = (int64_t)__call_result[0]; memcpy(&out_xmm0, __call_result, sizeof(out_xmm0));\n");
        sb.append("    NEKO_DIRECT_LOG(\"  <- call_stub shape=").append(key).append(" r=0x%llx xmm0=%g\", (unsigned long long)out_rax, out_xmm0);\n");
        if (ret == 'L') {
            sb.append("    if (out_rax != 0) { __njx_ret_oop = neko_njx_result_ref_to_oop(thread, (void*)(uintptr_t)out_rax); if (__njx_ret_oop == NULL) { fprintf(stderr, \"[neko-direct] object result could not be materialized shape=").append(key).append(" ref=%p\\n\", (void*)(uintptr_t)out_rax); abort(); } }\n");
        }
        sb.append("    neko_njx_restore_java_handles(thread, __njx_old_handles, __njx_java_handles);\n");
        /* Restore outer anchor */
        sb.append("    if (g_neko_off_last_Java_sp > 0) *(void**)((char*)thread + g_neko_off_last_Java_sp) = saved_sp;\n");
        sb.append("    if (g_neko_off_last_Java_pc > 0) *(void**)((char*)thread + g_neko_off_last_Java_pc) = saved_pc;\n");
        sb.append("    if (g_neko_off_last_Java_fp > 0) *(void**)((char*)thread + g_neko_off_last_Java_fp) = saved_fp;\n");
        sb.append("    neko_handle_restore(&__njx_hsave);\n");
        switch (ret) {
            case 'V' -> { /* nothing */ }
            case 'I' -> sb.append("    result.i = (jint)(int32_t)out_rax;\n");
            case 'J' -> sb.append("    result.j = (jlong)out_rax;\n");
            case 'F' -> sb.append("    { float __f = (float)out_xmm0; result.f = __f; }\n");
            case 'D' -> sb.append("    result.d = (jdouble)out_xmm0;\n");
            case 'L' -> sb.append("    result.l = neko_njx_oop_to_handle(thread, __njx_ret_oop); if (getenv(\"NEKO_HANDLE_DEBUG\") != NULL) { fprintf(stderr, \"[neko-debug] njx object result shape=").append(key).append(" oop=%p handle=%p\\n\", __njx_ret_oop, (void*)result.l); }\n");
        }
        sb.append("    return result;\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String renderShapeCallStub(String key, SignaturePlan.Shape shape) {
        char ret = shape.returnKind();
        char[] args = shape.argKinds();
        boolean isStatic = shape.isStatic();
        String fn = dispatcherSymbol(key);

        StringBuilder sb = new StringBuilder();
        StringBuilder argKinds = new StringBuilder();
        for (char a : args) argKinds.append(a);
        sb.append("/* shape ").append(key).append(" call_stub */\n");
        sb.append("static jvalue ").append(fn)
          .append("(void *thread, JNIEnv *env, void *method_ptr, void *entry_point, jobject receiver, const jvalue *args) {\n");
        sb.append("    return neko_njx_dispatch_generic(thread, env, method_ptr, entry_point, receiver, args, \"")
          .append(argKinds).append("\", '").append(ret).append("', ").append(isStatic ? "1" : "0")
          .append(", \"").append(key).append("\");\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String renderShapeUnsupported(String key, SignaturePlan.Shape shape) {
        String fn = dispatcherSymbol(key);
        StringBuilder sb = new StringBuilder();
        sb.append("static jvalue ").append(fn)
          .append("(void *thread, JNIEnv *env, void *method_ptr, void *entry_point, jobject receiver, const jvalue *args) {\n");
        sb.append("    (void)thread; (void)env; (void)method_ptr; (void)entry_point; (void)receiver; (void)args;\n");
        sb.append("    fprintf(stderr, \"[neko-direct-invoke] arch backend missing for shape=").append(key).append("\\n\"); abort();\n");
        sb.append("    jvalue r; r.j = 0; return r;\n");
        sb.append("}\n\n");
        return sb.toString();
    }
}
