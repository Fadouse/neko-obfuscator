# Oracle 16 — M5i Fidelity Walkable-Frame Resolution

Read-only consultation, session `ses_23cfdd3f3ffeJ6X4bZtxMxGUYu`, run 8m 10s.

This document is the canonical resolution of W11-M5i' (stack-trace fidelity) after Oracle 15's two-bug fix landed. The libjvm SIGSEGV is gone; the test now fails because the cached jthrowable's bootstrap-time backtrace is unchanged. Oracle 16 explains why and closes M5i' permanently.

---

## Bottom Line

**Root cause of the frozen bootstrap trace**: H4 (bridge predicate returns false) + H8 (Wave4a anchor save/restore exists in source but is NOT actually wired around translated method bodies in the inspected worktree). With the predicate failing, `neko_raise_cached_with_trace` falls back to `neko_raise_cached_pending`, which installs the cached throwable oop without refreshing its backtrace. Hence the bootstrap-time backtrace remains.

**Closure verdict**: M5i' is **CLOSED-PERMANENT** as a fill-based fidelity claim under strict-no-JNI. Even if Wave4a were correctly wired and `JVM_FillInStackTrace` ran successfully, `StackVictim.probe` cannot appear in the resulting trace because it is a C stub method with no HotSpot Java/interpreted/native-wrapper frame for the VM stack walker to discover.

| Status | Resolution |
|---|---|
| `libjvm+0xd2f1b5` SIGSEGV | FIXED by Oracle 15 (correct JNIEnv derivation + wide-oop JNI handle decode). |
| Caller frames (`StackHarness.captureAioobe`, `Main.main`) appear in trace | Achievable IF Wave4a anchor wiring is verified end-to-end. |
| `StackVictim.probe` (translated C stub) appears in trace | **IMPOSSIBLE** under strict-no-JNI. CLOSED-PERMANENT. |
| Test fixture `NativeObfStackTraceTest` passes as written | **IMPOSSIBLE**. Test requires Sisyphus's `sawVictim == true`, which strict-no-JNI cannot deliver via `JVM_FillInStackTrace`. |

---

## 1. Why the Old Backtrace Proves Fill Did Not Run

OpenJDK 21.0.10+7 path:

- `src/hotspot/share/prims/jvm.cpp:510-513` — `JVM_FillInStackTrace` resolves the `jobject` and calls `java_lang_Throwable::fill_in_stack_trace`.
- `src/hotspot/share/classfile/javaClasses.cpp:2518-2523` — fill **first clears** `Throwable.backtrace` and the lazily materialized `Throwable.stackTrace`.
- `javaClasses.cpp:2668-2670` — after walking, fill installs the completed backtrace and depth.
- `src/hotspot/share/prims/jni.cpp:514-521` — `jni_Throw` only installs the supplied throwable oop; it does not refill stack traces.

If `JVM_FillInStackTrace` had reached HotSpot's fill path, the JNI_OnLoad backtrace would have been **cleared first**, then either replaced by a fresh trace or left empty. The only way the bootstrap-time trace can remain byte-for-byte intact in user-visible output is if `JVM_FillInStackTrace` was **never called** for this throwable at this throw site.

Therefore the bridge fell back to `neko_raise_cached_pending`, which directly installs the cached oop into `_pending_exception` without touching the backtrace fields.

---

## 2. Walkable-Frame Requirement

`last_Java_sp` alone is **not** sufficient to make `JVM_FillInStackTrace` produce a useful trace. The full `JavaFrameAnchor` must be valid AND the thread must be in the right state.

**JDK 21 x86/Linux references:**

- `src/hotspot/share/runtime/javaFrameAnchor.hpp:60-76` — when `_last_Java_sp != nullptr`, the rest of the anchor must also be valid.
- `src/hotspot/cpu/x86/javaFrameAnchor_x86.hpp:65-80` — x86 `walkable()` requires both `last_Java_sp` and `last_Java_pc`; it also carries `last_Java_fp`.
- `src/hotspot/share/runtime/javaThread.hpp:910-912` — `JavaThread::last_frame()` calls `_anchor.make_walkable()` then `pd_last_frame()`.
- `src/hotspot/os_cpu/linux_x86/javaThread_linux_x86.cpp:29-32` — `pd_last_frame()` constructs `frame(last_Java_sp, last_Java_fp, last_Java_pc)`.
- `src/hotspot/cpu/x86/frame_x86.cpp:660-667` — `make_walkable()` recovers `last_Java_pc` from `last_Java_sp[-1]` only if the stack layout is a real HotSpot Java/native-wrapper frame.

**Thread-state requirement:**

The exported `JVM_FillInStackTrace` and `JNI Throw` use `JVM_ENTRY`/`JNI_ENTRY`, which assume the thread is `_thread_in_native` and transition to `_thread_in_vm` via `ThreadInVMfromNative`:

- `src/hotspot/share/runtime/interfaceSupport.inline.hpp:382-389` — `JVM_ENTRY` uses `ThreadInVMfromNative`.
- `src/hotspot/share/runtime/interfaceSupport.inline.hpp:96-99` — the native transition asserts the thread is `_thread_in_native` and that any last Java frame is walkable.

If the translated stub manually sets `_thread_in_vm` before calling `JVM_FillInStackTrace`, the assertion may fire (debug build) or silently corrupt the thread state (product build).

---

## 3. Why `StackVictim.probe` Cannot Appear

HotSpot obtains stack-trace `Method*` values from real HotSpot frames:

- **interpreted frame** → `fr.interpreter_frame_method()` (`javaClasses.cpp:2584-2595`).
- **compiled/native nmethod frame** → `CodeBlob`/`CompiledMethod` metadata (`javaClasses.cpp:2597-2611`).
- **backtrace storage** → `method->orig_method_idnum()`, bci/version, mirror, method name (`javaClasses.cpp:2268-2291`).
- **materialization** → `StackTraceElement[]` resolves those ids back through the holder class (`javaClasses.cpp:2766-2775`).

A Neko manifest `Method*` used by translated dispatch is **not a HotSpot frame**. It exists only inside `g_neko_method_*` arrays for runtime dispatch lookup, not as part of any compiled nmethod or interpreter frame on the JavaThread stack.

When HotSpot's BacktraceBuilder walks the stack starting from `last_Java_sp`, it will:

1. Skip the C stub frames (no Java metadata).
2. Find the first frame with a real `Method*` — typically the Java caller (`StackHarness.captureAioobe`).
3. Walk upward from there.

`StackVictim.probe` is invisible to the walker. There is no portable strict-no-JNI mechanism to register a synthetic Method*/Frame for a translated C stub.

---

## 4. Hypothesis Verdict

| Hypothesis | Verdict |
|---|---|
| H1 (frozen `stackTrace` lazy cache) | False. Fill clears it (`javaClasses.cpp:2518-2523`). |
| H2 (decoder runs, omits StackVictim) | Partially true (decoder would omit StackVictim when fill DOES run), but not THIS failure. |
| H3 (Throw refills backtrace from somewhere) | False. `jni_Throw` does not touch backtrace. |
| H4 (bridge predicate returns FALSE) | **True. Confirmed by frozen bootstrap trace.** |
| H5 (saved_java_sp captures C frame) | Real risk if Wave4a is wired, but not THIS failure. |
| H6 (StackVictim.probe inherently absent) | **True. Confirmed by HotSpot stack walker semantics.** |
| H7 (thread state mismatch) | Real risk if bridge is invoked from `_thread_in_vm`. |
| **H8 (Wave4a not wired around translated bodies)** | **True. The implementation cause behind H4.** |

---

## 5. Diagnostic Template (for future verification, not for production)

If you ever need to verify the bridge engages, the following diagnostic template adds three throwable-field offsets and instruments the bridge entry:

```c
static ptrdiff_t g_diag_throwable_backtrace_off = -1;
static ptrdiff_t g_diag_throwable_stacktrace_off = -1;
static ptrdiff_t g_diag_throwable_depth_off = -1;

static void neko_diag_anchor(const char *tag, void *thread) {
    void *sp = NULL, *fp = NULL, *pc = NULL;
    uint32_t state = 0xffffffffu;
    if (thread != NULL && g_neko_vm_layout.off_java_thread_last_Java_sp >= 0)
        sp = __atomic_load_n((void**)((uint8_t*)thread + g_neko_vm_layout.off_java_thread_last_Java_sp), __ATOMIC_ACQUIRE);
    if (thread != NULL && g_neko_vm_layout.off_java_thread_last_Java_fp >= 0)
        fp = *(void**)((uint8_t*)thread + g_neko_vm_layout.off_java_thread_last_Java_fp);
    if (thread != NULL && g_neko_vm_layout.off_java_thread_last_Java_pc >= 0)
        pc = *(void**)((uint8_t*)thread + g_neko_vm_layout.off_java_thread_last_Java_pc);
    if (thread != NULL && g_neko_vm_layout.off_thread_thread_state >= 0)
        state = __atomic_load_n((uint32_t*)((uint8_t*)thread + g_neko_vm_layout.off_thread_thread_state), __ATOMIC_ACQUIRE);
    NEKO_TRACE(0, "[nk] xbr %s thread=%p state=%u sp=%p fp=%p pc=%p ready=%d fill_fn=%p",
        tag, thread, state, sp, fp, pc,
        (int)neko_exception_bridge_ready(thread),
        (void*)g_neko_JVM_FillInStackTrace);
}
```

Expected output BEFORE bridge engages: `state=8 (in_vm) sp=NULL fp=NULL pc=NULL ready=0` → fallback to `neko_raise_cached_pending` → bootstrap backtrace remains.

---

## 6. Closure Decision

### Why M5i' is closed permanently

1. **Strict-no-JNI bans the only Java-level path** that could synthesize a fresh Throwable per throw with proper Method* metadata for translated frames (`NewObject`, `NewObjectA` outside JNI_OnLoad, `(*env)->Throw` of a fresh throwable).
2. **`JVM_FillInStackTrace` cannot invent frames** for non-HotSpot methods. C stubs are invisible to the stack walker.
3. **Synthetic `StackTraceElement[]` mutation** (Option X) would require:
   - Raw GC-correct writes into `Throwable.stackTrace` heap-field via VMStruct offsets.
   - Allocation of `StackTraceElement` objects per throw.
   - String/Class mirror plumbing for class names.
   - JDK 8 vs 9+ field-offset divergence handling.
   - Full GC matrix barrier compliance (G1 SATB, ZGC load/store color, Shenandoah LRB) — same blocker as M5h.
4. **VM backtrace mutation** (Option W) is even more fragile (private layout, JDK-version-sensitive encoding).

The combination of Strict-no-JNI + Zero-JVMTI + Full 6-GC matrix + JDK 8-21 + No new Java production classes makes synthetic-frame registration impossible without crossing one of those invariants.

### Comparison with prior closures

| Wave | Status | Closure rationale |
|---|---|---|
| M5h | CLOSED-PERMANENT (Oracle 14) | No portable VMStruct path for ZGC/Shenandoah ref-field write barriers. |
| M5f | CLOSED-PERMANENT (Oracle 14) | Monitor semantics + INVOKEDYNAMIC require non-VMStruct HotSpot machinery. |
| **M5i'** | **CLOSED-PERMANENT (Oracle 16)** | **Translated C-stub frames invisible to HotSpot walker; synthetic frames cross other invariants.** |

### What stays vs what reverts

**Keep** (correctness improvements regardless of fidelity goal):

1. Oracle 15 BUG #1 fix — `neko_env_from_thread` returns inline JNIEnv address (not a deref). Currently in Wave1RuntimeEmitter.
2. Oracle 15 BUG #2 fix — `neko_load_oop_from_jni_ref` helper + `neko_raise_cached_pending` uses wide-oop JNI handle decode. Currently in BootstrapEmitter.
3. `JVM_FillInStackTrace` symbol resolution at JNI_OnLoad bootstrap. Currently in BootstrapEmitter. Resolves but is not actively invoked from a production translator path; documented as future-use diagnostic hook.

**Revert / Re-defer**:

1. `neko_raise_cached_with_trace` is functionally a no-op given the bridge predicate consistently returns false in current code paths. Either:
   - **Option A (recommended)**: Keep the helper + 17 OpcodeTranslator migrations as-is. Behavior is identical to legacy `neko_raise_cached_pending` because the predicate falls back. No regression. Frees future implementation if Wave4a wiring is ever audited and verified.
   - **Option B**: Revert the 17 OpcodeTranslator migrations. Cleaner but loses the structural readiness.
   - We choose **Option A**: keep helper + migrations, document M5i' fidelity as deferred to v2.0 (post-strict-no-JNI relaxation).

2. `NativeObfStackTraceTest` re-`@Disabled` with rationale citing this Oracle 16 doc.

3. Master plan v6.5: M5i' marked CLOSED-PERMANENT. v1.0 admission counts unchanged at 14/17/12.

---

## 7. Risk Notes

1. The remaining bridge code (`neko_raise_cached_with_trace`) is structurally inert under current Wave4a wiring. Future contributors must understand it does not deliver fresh stack traces; the helper exists as a forward-compatibility hook.
2. If Wave4a is later audited and confirmed wired, the bridge will deliver fresh traces for **caller frames only**; `StackVictim.probe` will still be missing per §3.
3. Raw heap writes to `Throwable.stackTrace` or backtrace internals are GC-sensitive across G1/Shenandoah/ZGC and re-cross M5h's barrier blocker.
4. Thread-state emulation between `_thread_in_native` and `_thread_in_vm` for exported JNI/JVM entry calls is dangerous and JDK-version-sensitive.

---

## 8. Final Status

- **M5i'** — CLOSED-PERMANENT. Test re-`@Disabled`. Helper code retained as inert future-compat scaffolding.
- **M5h** — CLOSED-PERMANENT (Oracle 14).
- **M5f** — CLOSED-PERMANENT (Oracle 14).
- **W12 v2-final signoff** — supersedes v1-complete: still PASS WITH DOCUMENTED CLOSED-PERMANENT deferrals on M5h/M5f/M5i. Admission counts 14/17/12.
- **v1-complete signoff** — remains valid; v2-final adds Oracle 14/15/16 documentation and Oracle 15 correctness fixes (no behavior regression, two pre-existing bugs eliminated).

The strict-no-JNI invariants chosen at the project level make the M5h/M5f/M5i deliverables structurally impossible without invariant relaxation. Oracle 14/15/16 collectively constitute the canonical evidence for closure.
