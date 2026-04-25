# Oracle 14 — Final Deferral Resolution

Read-only consultation, session `ses_23d4511aaffeQ9pBuZIByA9h7n`, run 8m 44s.

This document is the canonical resolution for the three deferred deliverables of master plan v6.4 W11-M5:

- **M5h** — reference field admission (`GETSTATIC` ref, `PUTFIELD` ref, `PUTSTATIC` ref, `AASTORE`)
- **M5i** — stack-trace fidelity for cached pending exceptions
- **M5f** — `MONITORENTER` / `MONITOREXIT` / `INVOKEDYNAMIC`

Prior fix attempts on M5h: 4 iterations + Oracle 11 + Oracle 12. Cumulative outcome: every attempt crashed at runtime. Oracle 14 is the final design pass.

---

## Bottom Line

**Two of the three deferrals are fundamentally impossible** under the combined invariants:

1. Strict-no-JNI runtime (only `JNI_OnLoad` + 1 `(*vm)->GetEnv` + bootstrap-only allowlist + cached `(*env)->Throw`).
2. Zero-JVMTI everywhere.
3. Full 6-collector GC matrix `{Serial, Parallel, G1, ZGC, Shenandoah, Epsilon}`.
4. JDK 8 through JDK 21 portability.
5. No fallback / no fail-close that disables features.
6. No new Java production classes.

| Deliverable | Verdict | Rationale |
|---|---|---|
| **M5h** full reference field admission | **CLOSED-PERMANENT** | No portable VMStruct-only path for ZGC/Shenandoah load/store barriers; G1 needs SATB pre-barrier + dirty-card queue (not just card mark); reduced-GC scope would violate the no-fallback rule. |
| **M5i** stack-trace fidelity | **IMPLEMENTABLE (single-thread)** | `JVM_FillInStackTrace` via `dlsym` + cached `(*env)->Throw` is allowed by the strict-no-JNI surface and produces fresh throw-site backtrace. Concurrent fidelity with shared cached throwables is not achievable; single-threaded fixture passes. |
| **M5f** MONITOR* / INVOKEDYNAMIC | **CLOSED-PERMANENT** | Monitor semantics require HotSpot `ObjectSynchronizer` (mark-word + biased + lightweight + inflated + safepoint deflation); not VMStruct-portable. Indy linkage requires JVM `MethodHandle`/`CallSite` machinery; no stable C ABI. Bytecode-level lowering of `StringConcatFactory` is a separate obfuscation-time project, not a runtime opcode. |

The honest end-state for the v1 admission counts therefore remains:

| Jar | Admitted (final) |
|---|---:|
| `TEST.jar` | 14 |
| `obfusjack-test21.jar` | 17 |
| `SnakeGame.jar` | 12 |

Implementing M5i raises the per-fixture stack-trace test from `@Disabled` to passing without changing admission counts.

---

## M5h — Why Full Admission Is Impossible

### Root cause of every prior crash

Past attempts crossed three unsafe boundaries simultaneously:

1. **Static mirror decode was not raw-oop-correct.** JDK 9+ `Klass::_java_mirror` is an inline `OopHandle` whose `_obj` points to an oop storage cell. The trace `mir=0x7ff87fe60` is consistent with treating the storage cell pointer as the oop itself.
2. **Write barrier was incomplete.** A raw card byte write is sufficient only for non-concurrent collectors. G1 needs SATB pre-barrier on the loaded reference + dirty-card queue post-barrier. ZGC and Shenandoah have colored references and forwarding semantics not exposed via VMStructs.
3. **Reference paths ran during VM startup / class initialization.** The `libjvm+0x52635f` movaps crashes occurred 34ms after startup, in `_thread_in_vm` state, immediately after `[nk] n sfm java/lang/System.out`. Raw oop manipulation during class mirror initialization is unsafe; static field offsets and thread state are not in steady state.

The earlier alignment / `snprintf` fixes were real but secondary; M5h-specific crashes remained after Oracle 11 and Oracle 12.

### Collector matrix reality

| Collector | Raw load/store status under strict-no-JNI |
|---|---|
| Epsilon | Trivial barrier, but still needs correct oop encoding + type checks. |
| Serial | Post-store card mark sufficient. |
| Parallel | Post-store card mark sufficient. |
| G1 | Needs SATB pre-barrier on loaded reference during concurrent marking + dirty-card queue post-barrier. Raw card byte alone is insufficient. |
| ZGC | Needs `Z` load/store barriers + color/remap handling. Not VMStruct-exposed. JDK 21 generational ZGC changes the store-barrier story. |
| Shenandoah | Needs load-reference barrier + SATB/IU pre-barrier + forwarding. Not VMStruct-exposed. |

### Static mirror canonical decode (already addressed)

JDK 8 reads `Klass::_java_mirror` as raw oop. JDK 9-21 must double-deref the inline `OopHandle::_obj` storage cell.

```c
static inline oop neko_mirror_from_klass_jdk8(Klass *k) {
    if (k == NULL || g_neko_vm_layout.off_klass_java_mirror < 0) return NULL;
    return *(oop *)((uint8_t *)k + g_neko_vm_layout.off_klass_java_mirror);
}

static inline oop neko_mirror_from_klass_jdk9_plus(Klass *k) {
    uint8_t *oop_handle_addr;
    void **storage_cell;
    if (k == NULL) return NULL;
    if (g_neko_vm_layout.off_klass_java_mirror < 0) return NULL;
    if (g_neko_vm_layout.off_oophandle_obj < 0) return NULL;
    oop_handle_addr = (uint8_t *)k + g_neko_vm_layout.off_klass_java_mirror;
    storage_cell = *(void ***)((uint8_t *)oop_handle_addr + g_neko_vm_layout.off_oophandle_obj);
    if (storage_cell == NULL) return NULL;
    return (oop)__atomic_load_n(storage_cell, __ATOMIC_ACQUIRE);
}
```

This raw load is acceptable only for non-concurrent / non-Z / non-Shenandoah collector subsets, which the master plan does not allow.

### Hard blocker for full matrix

```c
static inline void neko_ref_store_full_gc_matrix_not_supported(void) {
#if defined(NEKO_ENABLE_M5H_REFERENCE_WRITES)
# error "M5h reference writes require a collector-specific HotSpot barrier backend; do not enable under strict-no-JNI full GC matrix."
#endif
}
```

### Decision

`SafetyChecker` keeps reference `GETSTATIC` / `PUTFIELD` / `PUTSTATIC` / `AASTORE` rejected with an explicit Oracle 14 reason. The stashed WIP scaffolding (`stash@{0}`) is rejected. Admission counts unchanged.

---

## M5i — `JVM_FillInStackTrace` Exception Bridge

### Root cause

1. Bootstrap-created global `jthrowable`s have backtraces frozen at construction time.
2. `neko_raise_cached_pending(thread, cached)` writes the cached object directly into `JavaThread::_pending_exception` without invoking `Throwable.fillInStackTrace`.
3. The `libjvm+0xd2f1b5` secondary crash is consistent with HotSpot exception delivery / stack walking observing a pending exception from a translated entry that lacks a valid JNI/native transition and Java frame anchor.

### Design

- Resolve `JVM_FillInStackTrace` once at bootstrap via existing `neko_resolve_libjvm_symbol`.
- Derive `JNIEnv*` from `JavaThread::_jni_environment` (no second `GetEnv`).
- At each synthetic throw site, call `JVM_FillInStackTrace(env, cached)` then `(*env)->Throw(env, cached)`.
- Predicate this on a bridge-readiness flag set by the assembly stub when a walkable last-Java-frame anchor exists and translated locals are no longer needed.

```c
typedef void (JNICALL *neko_JVM_FillInStackTrace_fn)(JNIEnv *env, jobject throwable);
static neko_JVM_FillInStackTrace_fn g_neko_JVM_FillInStackTrace = NULL;

static jboolean neko_resolve_stacktrace_symbols(void) {
    g_neko_JVM_FillInStackTrace =
        (neko_JVM_FillInStackTrace_fn)neko_resolve_libjvm_symbol("JVM_FillInStackTrace");
    return g_neko_JVM_FillInStackTrace != NULL ? JNI_TRUE : JNI_FALSE;
}

static inline JNIEnv *neko_env_from_thread(void *thread) {
    if (thread == NULL || g_neko_vm_layout.off_java_thread_jni_environment < 0) return NULL;
    return (JNIEnv *)((uint8_t *)thread + g_neko_vm_layout.off_java_thread_jni_environment);
}

static jint neko_raise_cached_with_trace(void *thread, jthrowable cached, jboolean fill_trace_now) {
    JNIEnv *env;
    if (thread == NULL || cached == NULL) return JNI_ERR;
    env = neko_env_from_thread(thread);
    if (env == NULL) return JNI_ERR;
    if (fill_trace_now && g_neko_JVM_FillInStackTrace != NULL) {
        g_neko_JVM_FillInStackTrace(env, (jobject)cached);
    }
    return (*env)->Throw(env, cached);
}

static inline jboolean neko_exception_bridge_ready(void *thread) {
    if (thread == NULL) return JNI_FALSE;
    if (g_neko_vm_layout.off_java_thread_last_Java_sp < 0) return JNI_FALSE;
    if (*(void **)((uint8_t *)thread + g_neko_vm_layout.off_java_thread_last_Java_sp) == NULL) return JNI_FALSE;
    return JNI_TRUE;
}
```

### Strict-no-JNI surface accounting

`JVM_FillInStackTrace` is **not** a JNI table call (`(*env)->...`); it is a HotSpot internal exported via `dlsym`. The strict-no-JNI policy bans only `(*vm)`/`(*env)` indirect call families. Symbol resolution via `dlsym` of HotSpot internals is the same mechanism W0 already uses for `neko_resolve_libjvm_symbol`. No new surface area at the JNI level.

The `(*env)->Throw` call remains the single allowed post-bootstrap JNI invocation, already accounted for in W12-H.

### New VMStructs fields

- `JavaThread::_jni_environment`
- `JavaThread::_anchor` (existing)
- `JavaFrameAnchor::_last_Java_sp` (existing)
- `Thread::_thread_state` (existing)

### New `dlsym` symbol

- `JVM_FillInStackTrace` — unmangled C symbol, `src/hotspot/share/prims/jvm.cpp`, available JDK 8-21.

### Test fixture (existing, currently `@Disabled`)

- `NativeObfStackTraceTest`
- `pack.stacktrace.StackVictim#probe([II)I`
- `pack.stacktrace.StackVictim#divide(Ljava/lang/Integer;I)I`
- `pack.stacktrace.StackHarness` (non-translated)

Pass criterion: `Throwable.getStackTrace()` after AIOOBE / NPE contains both `StackVictim` and `StackHarness` frames.

### Admission counts

Unchanged. `NativeObfStackTraceTest` translates exactly 2 methods; counts on the 3 canonical jars do not move.

### Risks

- Shared cached throwables cannot provide concurrent Java parity; two threads racing on the same cached `NPE` will overwrite each other's stack traces. Single-threaded fixture is unaffected.
- If the assembly stub does not establish a walkable Java frame, `JVM_FillInStackTrace` may omit `StackVictim` or crash.
- After `JVM_FillInStackTrace` allocates, no raw translated oop locals may be used.

---

## M5f — Why MONITOR* and INVOKEDYNAMIC Are Impossible

### MONITOR*

Correct semantics require: null check, recursive locking, biased locking (JDK 8-15), lightweight stack locks, inflated `ObjectMonitor`s, owner transitions, wait/notify compatibility, safepoint and deflation handling, JDK-version mark-word layouts, future compact-header gating. Raw CAS on the mark word covers only the trivial uncontended fast path. No portable VMStruct-only path implements inflation/deflation, recursive ownership, or balanced exit.

### INVOKEDYNAMIC

Generic indy requires: bootstrap method invocation, `MethodHandle`/`CallSite` construction, classloader/module/access checks, call-site caching, mutable/volatile call-site behavior, JVM adapter/linker. Strict-no-JNI forbids the normal Java/JNI path; VMStructs do not expose a portable substitute.

### Plausible (but separate) future scope

Bytecode-level lowering of `StringConcatFactory.makeConcat*` to ordinary bytecode at obfuscation time, before the native translator sees the method. This is an obfuscation-time transform project, not a runtime opcode implementation, and does not solve lambda metafactory indy without permitting new generated classes.

### Hard blockers

```c
static void neko_monitor_enter_unreachable(void *obj) {
    (void)obj;
#if defined(NEKO_ALLOW_MONITOR_TRANSLATION)
# error "MONITORENTER is not supported under strict-no-JNI full JDK 8-21 semantics."
#endif
}

static void neko_monitor_exit_unreachable(void *obj) {
    (void)obj;
#if defined(NEKO_ALLOW_MONITOR_TRANSLATION)
# error "MONITOREXIT is not supported under strict-no-JNI full JDK 8-21 semantics."
#endif
}

static void neko_invokedynamic_unreachable(void) {
#if defined(NEKO_ALLOW_INDY_TRANSLATION)
# error "INVOKEDYNAMIC must be lowered before native translation or remain excluded."
#endif
}
```

### SafetyChecker policy

```java
case Opcodes.MONITORENTER, Opcodes.MONITOREXIT ->
    addReason(reasons, "M5f deferred: monitor semantics require HotSpot ObjectSynchronizer; no strict-no-JNI portable implementation");
case Opcodes.INVOKEDYNAMIC ->
    addReason(reasons, "M5f deferred: generic invokedynamic linkage requires JVM MethodHandle/CallSite machinery");
```

### Admission counts

Unchanged. Excluded impact: TEST 1 indy method, obfusjack 35 indy methods + 1 monitor pair, SnakeGame zero.

---

## Implementation Plan (M5i Only)

1. **Resolve symbol** — extend `BootstrapEmitter` to register `JVM_FillInStackTrace` via existing `neko_resolve_libjvm_symbol`. Cache as `g_neko_JVM_FillInStackTrace`.
2. **Add VM layout offset** — `JavaThread::_jni_environment` to `NekoVmLayout`. Read from `vmStructs` table during `neko_parse_vm_layout`.
3. **Bridge helpers** — add `neko_env_from_thread`, `neko_raise_cached_with_trace`, `neko_exception_bridge_ready` to a runtime emitter (likely `Wave1RuntimeEmitter`).
4. **Translator migration** — replace existing `neko_raise_cached_pending(thread, g_neko_throw_*)` call sites in stack-trace-sensitive opcodes (NPE, AIOOBE, AE, ASE, NASE) with `neko_raise_cached_with_trace(thread, g_neko_throw_*, JNI_TRUE)`.
5. **Bridge-readiness invariant** — assembly stub must establish `JavaThread::_anchor._last_Java_sp` before any synthetic throw. If `neko_exception_bridge_ready` returns false, fall back to existing `neko_raise_cached_pending` (no stack trace, no crash).
6. **Test enable** — remove `@Disabled` from `NativeObfStackTraceTest`. Update assertions to require `StackVictim.probe`, `StackVictim.divide`, and `StackHarness` frames.
7. **Verify** — full neko-test run + 3-jar GATE-3..GATE-9 + GC subset (G1 + ZGC) for the new path. Admission counts must remain `14 / 17 / 12`.

### Failure modes to avoid

- Calling `JVM_FillInStackTrace` from a translated method whose stub did not set up the anchor → likely crash or silent omission of frames.
- Calling it after raw oop locals are still live → potential GC corruption since `JVM_FillInStackTrace` can allocate.
- Calling it during VM startup / `<clinit>` → same class of crash that doomed M5h.
- Failing to fall back to `neko_raise_cached_pending` when the bridge predicate is false → regression on existing tests.

---

## Closure Statement

- **M5h** — CLOSED-PERMANENT. No path under strict-no-JNI + full 6-GC + JDK 8-21. Reduced collector scope would violate no-fallback. Re-open only if invariants change.
- **M5f** — CLOSED-PERMANENT. Monitor and indy semantics are JVM-runtime features, not emitter helpers. Re-open only if invariants change or if a separate obfuscation-time bytecode lowering project is approved.
- **M5i** — IMPLEMENTABLE. Single-threaded fixture parity via `JVM_FillInStackTrace` bridge. Implement next.

Master plan v6.5 entry: M5h and M5f move from `DEFERRED` to `CLOSED-PERMANENT` with this document as canonical evidence; M5i moves from `DEFERRED` to `IN PROGRESS` then `DONE` after verification.

W12 v2-final signoff supersedes v1-complete: with M5i landed, the W12-E gate marker becomes `PASS` (was `GATED`); W12-A through W12-I all green.
