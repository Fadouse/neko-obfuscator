# W11-M5i stack-trace fidelity — DEFERRED

## Summary

`NativeObfStackTraceTest` was added with a packaged fixture that translates only
`pack.stacktrace.StackVictim#probe([II)I` and
`pack.stacktrace.StackVictim#divide(Ljava/lang/Integer;I)I`. The non-translated
`StackHarness` catches `ArrayIndexOutOfBoundsException` / `NullPointerException`
and asserts `Throwable.getStackTrace()` contains both `StackVictim` and
`StackHarness` frames.

## Findings

- Initial run crashed in HotSpot (`SIGSEGV`, `libjvm.so+0x766b7d`) before any
  `STACKTRACE_*` output because translated cached exception sites still used
  `neko_throw_cached(env, g_neko_throw_*)`, violating the M5b rule that
  translated hot paths must not use `env->Throw`.
- `OpcodeTranslator` cached-throw sites were migrated to
  `neko_raise_cached_pending(thread, g_neko_throw_*)`.
- A second crash exposed a raw-oop/JNI-handle mismatch in array bounds checks:
  translated arrays are raw oops, but the generated bounds path called
  `neko_get_array_length(env, (jarray)raw_oop)`, whose JNI implementation
  expects a handle cell.
- `Wave1RuntimeEmitter` now emits `neko_raw_array_length(void*)`, and translated
  `ARRAYLENGTH` / array bounds checks use that raw helper instead of JNI
  `GetArrayLength`.
- After the raw-length fix, the stack-trace fixture still crashes before any
  stack output (`SIGSEGV`, `libjvm.so+0xd2f1b5`, exit 134) after native patching
  succeeds with `dp 2/2`.

## Root cause / deferral rationale

Strict-no-JNI translated entries can set `_pending_exception` via
`neko_set_pending_exception`, but this is not a full HotSpot implicit-exception
throw bridge. Cached throwable objects also cannot provide a fresh Java throw-site
backtrace. A compliant implementation needs future VM-state exception machinery
that enters HotSpot with a valid Java frame anchor and creates/fills a fresh
`Throwable` for the current translated throw site. Poking `_exception_pc`,
`_last_Java_frame`, or cached throwable backtrace fields directly is unsafe and
was explicitly avoided.

## Status

- Outcome: **C / DEFERRED** — translated cached pending exceptions do not yet
  preserve stack-trace frames and still crash on this fixture before
  `Throwable.getStackTrace()` can be inspected.
- `NativeObfStackTraceTest` remains in-tree but disabled with the above reason.
- The crash-reduction fixes are retained because they remove translated-path
  `env->Throw` usage and avoid JNI array-length calls on raw oops.
