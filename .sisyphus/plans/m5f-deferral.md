# W11-M5f deferral — MONITOR* / INVOKEDYNAMIC

## Decision

W11-M5f is **DEFERRED to M5k/W12**. Full admission is not safe in this sub-wave because the current strict-no-JNI runtime cannot execute `MONITORENTER` / `MONITOREXIT` or generic `INVOKEDYNAMIC` semantics without relying on forbidden JNI or JVM-internal behavior that has not been implemented and verified across the supported JDK/OS/CPU matrix.

## Fixture impact

The M5f opcode scan found these affected fixture methods:

- `TEST.jar`: 1 method with `INVOKEDYNAMIC`
  - `pack/tests/basics/runable/Task#run()V`
- `obfusjack-test21.jar`: 35 methods with `INVOKEDYNAMIC`; 1 of those methods also has `MONITORENTER` / `MONITOREXIT`
  - `org/example/Main#main([Ljava/lang/String;)V` contains `INVOKEDYNAMIC`, `MONITORENTER`, and `MONITOREXIT`
  - Additional `INVOKEDYNAMIC` sites are present in record-style `toString` / `hashCode` / `equals`, lambda, string-concat, virtual-thread, random-API, matrix, and class-initializer methods under `org/example/Main*`
- `SnakeGame.jar`: no `MONITORENTER`, `MONITOREXIT`, or `INVOKEDYNAMIC` methods found

The native fixture configs target `**/*`, so flipping admission would translate runnable `TEST` / `obfusjack` methods instead of leaving them on Java bytecode paths.

## Strict-no-JNI root cause

### MONITOR*

The existing runtime helper shape previously exposed JNI vtable wrappers for `MonitorEnter` / `MonitorExit`, but those calls are outside the strict-no-JNI steady-state contract. A compliant implementation must reproduce HotSpot monitor semantics directly from raw object state, including null checks, reentrancy, balanced exit, inflated monitors, object header / mark-word transitions, biased-locking-era layouts, lightweight locking variants, and GC safety across JDK 8-21 on Linux, macOS, Windows, x64, and aarch64. That implementation is not present and cannot be safely substituted with a cached `LinkageError`/`IllegalMonitorStateException` fallback for runnable fixture methods without changing behavior.

### INVOKEDYNAMIC

Generic `INVOKEDYNAMIC` linkage requires bootstrap method invocation, `MethodHandle` / `CallSite` construction, classloader/module access checks, call-site caching, and mutable/volatile call-site behavior. The current generic translator path is Java/JNI-style MethodHandle linkage, which violates strict-no-JNI steady state. Replacing it with unconditional cached `BootstrapMethodError` would be a safe synthetic failure path for unreachable code, but it would break runnable fixture methods once admitted.

## Policy until reopened

- `MONITORENTER`, `MONITOREXIT`, and unsupported `INVOKEDYNAMIC` remain native-translation exclusions.
- Revisit in M5k/W12 only with a compliant monitor strategy and a bootstrap-only, allowlisted indy design that avoids steady-state JNI.
- Cached throwables remain reserved for future synthetic exceptional paths:
  - `g_neko_throw_imse` for monitor-state failures
  - `g_neko_throw_le` for unsupported monitor linkage/runtime guard failures
  - `g_neko_throw_bme` for unsupported indy bootstrap/link failures

## Verification expectation

M5f deferral intentionally keeps admission counts at the W11-M5e baseline for affected fixture methods. M5g+ may proceed with the existing safety exclusions intact.
