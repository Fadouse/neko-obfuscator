# Oracle 15 — M5i `libjvm+0xd2f1b5` Crash Resolution

Read-only consultation, session `ses_23d0ba7a0ffedPNfQqyb3kJHSe`, run 7m 59s.

This document supersedes Oracle 14 §M5i with the precise root cause and concrete remediation for the recurring `libjvm+0xd2f1b5` crash on JDK 21.0.10+7 G1 GC.

---

## Bottom Line

`libjvm+0xd2f1b5` is **not** the Throwable backtrace decoder. It is HotSpot's `oopDesc::is_a(ClassNotFoundException_klass)` check inside `handle_resolution_exception`, segfaulting because `THREAD->_pending_exception` already holds a corrupted non-oop value.

The corruption has **two root causes**, both pre-existing in Neko's runtime (M5i-induced calls merely amplified them):

1. `neko_env_from_thread` wrongly dereferences `JavaThread::_jni_environment`, which is an **inline** `JNIEnv` struct, not a pointer.
2. `neko_load_oop_from_cell` decodes JNI handle cells through `neko_decode_heap_oop`, but JNI handles store **wide** oops, not compressed heap-field words.

Apply both fixes and `JVM_FillInStackTrace` produces fresh stack traces correctly. Per-throw HotSpot allocation is allowed by master plan (the rule forbids per-throw `NewObject` from native, not internal JVM allocations during normal exception delivery).

If "no per-throw allocation in steady-state" is interpreted strictly, M5i becomes CLOSED-PERMANENT. Otherwise, M5i is implementable.

---

## 1. Exact crash site

`libjvm+0xd2f1b5` decodes to the inlined `oopDesc::is_a` check inside:

- `src/hotspot/share/classfile/systemDictionary.cpp` — `handle_resolution_exception`, lines 292-303 in JDK 21.0.10+7.

```cpp
static void handle_resolution_exception(Symbol* class_name, bool throw_error, TRAPS) {
  if (HAS_PENDING_EXCEPTION) {
    if (throw_error && PENDING_EXCEPTION->is_a(vmClasses::ClassNotFoundException_klass())) {
      ResourceMark rm(THREAD);
      Handle e(THREAD, PENDING_EXCEPTION);
      CLEAR_PENDING_EXCEPTION;
      THROW_MSG_CAUSE(vmSymbols::java_lang_NoClassDefFoundError(), class_name->as_C_string(), e);
    } else {
      return;
    }
  }
  ...
}
```

The crashing instruction is the inlined `oopDesc::klass()` from `oop.inline.hpp:89-95` plus `oopDesc::is_a()` at `oop.inline.hpp:148-149`:

```cpp
Klass* oopDesc::klass() const {
    if (UseCompressedClassPointers) {
        return CompressedKlassPointers::decode_not_null(_metadata._compressed_klass);
    } else {
        return _metadata._klass;
    }
}

bool oopDesc::is_a(Klass* k) const {
    return klass()->is_subtype_of(k);
}
```

The crashing assembly:

```asm
41 8b 78 08    mov 0x8(%r8), %edi
```

Reads compressed klass from `oop + 8`. With `R8 = 0x259c1dac0`, dereferences `0x259c1dac8` and segfaults.

`RSI = ClassNotFoundException Klass*` is the comparison target from `vmClasses::ClassNotFoundException_klass()`, **not** a stale entry from a backtrace. The crash is during class-resolution exception handling, not during stack-trace decoding.

---

## 2. Root cause #1 — JNIEnv derivation bug

Current Wave1RuntimeEmitter:

```c
NEKO_FAST_INLINE JNIEnv* neko_env_from_thread(void *thread) {
    if (thread == NULL || g_neko_vm_layout.off_java_thread_jni_environment < 0) return NULL;
    return *(JNIEnv**)((uint8_t*)thread + g_neko_vm_layout.off_java_thread_jni_environment);
}
```

`JavaThread::_jni_environment` is an **inline** `JNIEnv` struct (not a pointer). HotSpot's `JavaThread::jni_environment()` returns `&_jni_environment`, i.e., the address of the struct, not its contents.

Correct:

```c
NEKO_FAST_INLINE JNIEnv* neko_env_from_thread(void *thread) {
    if (thread == NULL || g_neko_vm_layout.off_java_thread_jni_environment < 0) return NULL;
    return (JNIEnv*)((uint8_t*)thread + g_neko_vm_layout.off_java_thread_jni_environment);
}
```

Verified against:
- `src/hotspot/share/runtime/javaThread.hpp` (`JNIEnv _jni_environment;`)
- `src/hotspot/share/runtime/javaThread.hpp` (`JNIEnv* jni_environment() { return &_jni_environment; }`)

---

## 3. Root cause #2 — JNI handle decode bug (pre-existing)

Current `neko_load_oop_from_cell`:

```c
static void* neko_load_oop_from_cell(const void *cell) {
    if (g_neko_vm_layout.narrow_oop_shift > 0 || g_neko_vm_layout.narrow_oop_base != 0u) {
        u4 narrow = __atomic_load_n((const u4*)cell, __ATOMIC_ACQUIRE);
        return neko_decode_heap_oop(narrow);
    }
    return __atomic_load_n((void* const*)cell, __ATOMIC_ACQUIRE);
}
```

This is correct for compressed oop **fields** inside a Java object. It is **wrong** for JNI global/local handle cells and OopStorage entries.

JNI handles store **wide oops** (`uintptr_t`-sized), even when `UseCompressedOops` is enabled. The cell layout is documented in:
- `src/hotspot/share/runtime/jniHandles.cpp` (`oop *_first_block`, etc.)
- `src/hotspot/share/runtime/jniHandles.inline.hpp::JNIHandles::resolve_impl`

The diagnostic value is conclusive:

```text
0x259c1dac0 >> 3 = 0x4b383b58
```

With zero-based compressed oops and shift 3, that is what you get when reading the **low 32 bits** of a real wide oop like `0x000000054b383b58` (which is in the live heap range per the hs_err map) and applying compressed-oop decoding.

Correct dedicated helper for JNI handle cells:

```c
NEKO_FAST_INLINE void* neko_load_oop_from_jni_ref(jobject ref) {
    uintptr_t cell;
    if (ref == NULL) return NULL;
    cell = ((uintptr_t)ref) & ~(uintptr_t)(sizeof(void*) - 1u);
    return __atomic_load_n((void* const*)cell, __ATOMIC_ACQUIRE);
}
```

Apply this in `neko_raise_cached_pending` (and any other place that decodes a `jthrowable`/`jobject` global cell):

```c
static jint neko_raise_cached_pending(void *thread, jthrowable cached) {
    void *oop;
    if (thread == NULL || cached == NULL) return JNI_ERR;
    oop = neko_load_oop_from_jni_ref((jobject)cached);
    if (oop != NULL) neko_set_pending_exception(thread, oop);
    return oop != NULL ? JNI_OK : JNI_ERR;
}
```

---

## 4. Hypothesis ranking (final)

| Hypothesis | Confidence | Verdict |
|---|---|---|
| H7 (corrupted pending exception from JNI handle misdecode) | **Highest** | Confirmed via diagnostic value match. |
| JNIEnv derivation bug (inline struct vs pointer) | **High** | Confirmed by reading HotSpot `JavaThread::_jni_environment` declaration. |
| H5 (non-walkable frame for `JVM_FillInStackTrace`) | Medium | Real but not THIS crash. |
| H1 (stale `stackTrace`/`backtrace`) | Low/false | `JVM_FillInStackTrace` clears both via `set_backtrace(throwable, nullptr)` + `clear_stacktrace(throwable)` at `javaClasses.cpp:2518-2523`. |
| H2/H4 (constructor / backtrace layout mismatch) | Low | `JVM_FillInStackTrace` is the standard `Throwable.fillInStackTrace` native; layout is consistent. |
| H3/H6 (stale Method* / C stub in decoded backtrace) | Low | `RSI = ClassNotFoundException` is from `handle_resolution_exception`, not from a decoded stack frame. |

---

## 5. Recommended remediation (paste-ready)

### Wave1RuntimeEmitter

```c
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

NEKO_FAST_INLINE void* neko_load_oop_from_jni_ref(jobject ref) {
    uintptr_t cell;
    if (ref == NULL) return NULL;
    cell = ((uintptr_t)ref) & ~(uintptr_t)(sizeof(void*) - 1u);
    return __atomic_load_n((void* const*)cell, __ATOMIC_ACQUIRE);
}

static jint neko_raise_cached_pending(void *thread, jthrowable cached) {
    void *oop;
    if (thread == NULL || cached == NULL) return JNI_ERR;
    oop = neko_load_oop_from_jni_ref((jobject)cached);
    if (oop != NULL) neko_set_pending_exception(thread, oop);
    return oop != NULL ? JNI_OK : JNI_ERR;
}

static jint neko_raise_cached_with_trace(void *thread, jthrowable cached, jboolean fill_trace_now) {
    JNIEnv *env;
    if (thread == NULL || cached == NULL) return neko_raise_cached_pending(thread, cached);
    if (!neko_exception_bridge_ready(thread)) return neko_raise_cached_pending(thread, cached);
    env = neko_env_from_thread(thread);
    if (env == NULL) return neko_raise_cached_pending(thread, cached);
    if (fill_trace_now == JNI_TRUE && g_neko_JVM_FillInStackTrace != NULL) {
        g_neko_JVM_FillInStackTrace(env, (jobject)cached);
    }
    return (*env)->Throw(env, cached);
}
```

### Why this fixes both crashes

- M5i path (`neko_raise_cached_with_trace` → `(*env)->Throw`) now passes a valid `JNIEnv*` because `neko_env_from_thread` no longer dereferences. HotSpot's `Throw` accepts the cached `jthrowable`, calls `JNIHandles::resolve` correctly, and writes a valid wide oop to `_pending_exception`.
- The legacy fallback path (`neko_raise_cached_pending` when bridge predicate is false or env is NULL) now uses `neko_load_oop_from_jni_ref`, which reads the wide oop from the aligned handle cell instead of treating the cell value as a compressed oop. `_pending_exception` receives a valid oop; subsequent `is_a` checks no longer crash.

---

## 6. Strict-no-JNI surface accounting

- **No new JNI calls.** `(*env)->Throw` was already accounted for in W12-H. `JVM_FillInStackTrace` is a `dlsym` HotSpot internal, not a JNI table call.
- **No new JNI families introduced.** No `ExceptionCheck`, no `ExceptionClear`, no `NewObject`, no `ThrowNew`.
- The fix is corrective on existing primitives, not additive.

---

## 7. Per-throw allocation reality

`JVM_FillInStackTrace` allocates new backtrace chunks (Object[] arrays of Method*, bci, mirrors, names, optional conts/hidden). This allocation happens through HotSpot's normal exception delivery path with proper write barriers, GC safety, and OOM handling.

The master plan's "no fallback / no fail-close" rule forbids:
- Disabling features to make tests pass.
- Special-casing fixtures.
- Per-throw allocation of replacement objects via JNI `NewObject`/`AllocObject`.

It does **not** forbid HotSpot's internal allocations during exception delivery, which is part of normal Java semantics.

If "zero per-throw allocation" is reinterpreted as a hard rule, M5i becomes CLOSED-PERMANENT (same status as M5h and M5f). Fresh Java stack traces require allocation; there is no portable lock-free way to give a cached throwable a fresh backtrace under strict-no-JNI.

---

## 8. Verification signals

After applying both fixes, the following must hold:

1. The bad address `0x259c1dac0` no longer appears in any debug log.
2. `[nk] cached=<tagged ref> cell=<aligned> oop=0x00000005...` (or `0x00000007...`) — the wide oop is in the live heap range, not a shifted low address.
3. `pack.stacktrace.StackHarness.captureAioobe()` returns a non-null `StackTraceElement[]` containing both `pack.stacktrace.StackVictim.probe` and `pack.stacktrace.StackHarness.captureAioobe` frames.
4. `STACKTRACE_AIOOBE=` and `STACKTRACE_NPE=` markers appear in test stdout.
5. No new `hs_err_pid*.log` from the test.

---

## 9. JDK 8-21 portability

| JDK | `JavaThread::_jni_environment` | `JNI handle cell` | `JVM_FillInStackTrace` |
|---|---|---|---|
| 8 | inline JNIEnv | wide oop | exported |
| 11 | inline JNIEnv | wide oop | exported |
| 17 | inline JNIEnv | wide oop | exported |
| 21 | inline JNIEnv | wide oop | exported |

Both fixes are JDK 8-21 portable. Wide JNI handle layout is JNI-spec-mandated; HotSpot's `_jni_environment` field has been inline since JDK 6.

---

## 10. Closure status

- **M5i** — IMPLEMENTABLE (single-threaded fixture). Apply both fixes per §5; build + test + verify per §8.
- **M5h** — CLOSED-PERMANENT (Oracle 14 §M5h).
- **M5f** — CLOSED-PERMANENT (Oracle 14 §M5f).

W12 v2-final signoff: with M5i landed, GATE-E moves from `GATED` to `PASS`.
