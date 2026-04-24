# Oracle 9 — `InstanceKlass::allocate_instance` Alternative

## Hypothesis confirmation (allocate_instance really not in .dynsym)

Confirmed. The current DD-5 Option 1 premise is invalid for the tested HotSpot builds: `InstanceKlass::allocate_instance(Thread*)` is not exported from the dynamic symbol table, so `dlsym` cannot be a portable allocation backend.

Ground truth supplied for JDK 21 default, `java-21-openjdk`, and `java-22-openjdk`:

```bash
nm -D --defined-only libjvm.so | c++filt | grep 'InstanceKlass::allocate_instance'
# no matches
```

The current emitted resolver in `BootstrapEmitter.java:801-828` tries:

```c
"_ZN13InstanceKlass17allocate_instanceEP6Thread",
"_ZN13InstanceKlass17allocate_instanceEP10JavaThread",
"_ZN13InstanceKlass17allocate_instanceEP7Thread*",
"_ZN13InstanceKlass17allocate_instanceEP10JavaThread*"
```

The last two are invalid mangled names because a literal `*` never appears that way in Itanium C++ ABI mangling. The first two still cannot work on the checked JDKs because the symbol is not in `.dynsym`. The runtime trace `[nk] n dlsym_allocate_instance=FAILED` is therefore expected, not an implementation typo.

Conclusion: DD-5 Option 1 must stop depending on `dlsym(InstanceKlass::allocate_instance...)`. There is no small name-table fix that makes it portable across JDK 8-21 / Linux / macOS / Windows / x64 / aarch64.

## Each option evaluated - pros/cons/risk

### Rank 1 — Opt-6 + Opt-5 combined: bootstrap-only JNI exception cache, post-bootstrap `env->Throw` only

**Recommendation:** accept a narrow DD-5 policy relaxation: during `JNI_OnLoad` only, create and globalize preconstructed exception objects with standard JNI (`FindClass`, `GetMethodID`, `NewStringUTF` where needed, `NewObjectA`, `NewGlobalRef`, `DeleteLocalRef`). After `JNI_OnLoad` returns, translated/runtime code may only dispatch those cached `jthrowable` globals via `(*env)->Throw(env, cached)`.

Pros:

- Portable by construction across JDK 8-21, Linux/macOS/Windows, x64/aarch64, and all GCs because allocation, constructor execution, object layout, stack trace/backtrace fields, and JNI handle rooting are delegated to the JVM.
- Does not require exported C++ allocation symbols, VM-private allocation ABI, manual TLAB slow paths, compact-header handling, or raw `Throwable` field initialization.
- `env->Throw` receives a valid JNI global handle, not a raw oop. That is materially safer than the current raw-oop idea, because JNI `jthrowable` is a handle-level type in HotSpot.
- Preserves zero JVMTI, does not touch `MANIFEST_ENTRY_SIZE = 88`, and leaves Oracle 7 Fix G/H mirror helpers untouched.
- The post-`JNI_OnLoad` surface remains strict: no `FindClass`, `GetMethodID`, `NewObject`, `AllocObject`, `NewGlobalRef`, `Call*Method`, `ThrowNew`, `ExceptionCheck`, or `ExceptionClear` after bootstrap.

Cons / semantic compromises:

- This violates the current literal m0107/m0117 rule during bootstrap. The master plan must explicitly amend DD-5 to permit the bootstrap-only construction/cache window.
- Reusing singleton `Throwable` instances means stack traces point to construction time (`JNI_OnLoad`), not the native throw site. This is already closer to DD-5's “minimal synthetic exception” spirit than full Java semantics, but not stack-trace equivalent.
- Throwable instances are mutable (`cause`, `suppressed`, `stackTrace`). If Java code catches and mutates a cached exception, later throws of the same cached object observe that mutation.
- Dynamic messages such as exact bad index values are not available unless the cache includes fixed generic messages or a bounded pool of variants.

Risk: **Low operational risk, explicit policy risk.** It is the only candidate here that is realistically portable without relying on HotSpot-private ABI.

### Rank 2 — Opt-3: use `env->ThrowNew`

Pros:

- Portable and lets the JVM allocate/initialize the exception at each throw site.
- Avoids reusable singleton exception mutability and stale stack traces.

Cons:

- Violates the user restriction to `env->Throw` specifically.
- Still requires a valid `jclass` handle at throw time. Getting that handle either requires JNI class lookup/global refs or a raw metadata-to-handle bridge, so it does not actually keep the surface simpler.
- Post-bootstrap use would widen the steady-state JNI surface beyond the intended single dispatch call.

Risk: **Medium policy risk.** Technically sound if the policy is relaxed further, but not the minimum change because Opt-6 achieves portability while keeping post-bootstrap dispatch to `Throw` only.

### Rank 3 — Opt-2: pre-allocate with `AllocObject` / `NewObject` at `JNI_OnLoad`, but without the explicit global-cache policy

Pros:

- `NewObject` during bootstrap is portable and constructor-correct.
- `AllocObject` avoids constructors but still uses JVM allocation and object layout.

Cons:

- Local references created in `JNI_OnLoad` are invalid after `JNI_OnLoad` returns; raw oops extracted from them are not stable GC roots. This option is incomplete unless it becomes Opt-5/6 with `NewGlobalRef`.
- `AllocObject` is worse than `NewObject` for `Throwable`: the object may be allocated, but `Throwable`'s constructor-side state (`backtrace`, `stackTrace`, cause/suppressed defaults) is not initialized by Java semantics.
- Still bends the bootstrap JNI rule.

Risk: **Medium implementation risk unless converted into Opt-5/6.** Use `NewObject` + `NewGlobalRef`, not bare `AllocObject` or raw oop extraction.

### Rank 4 — Opt-1: open-code allocation through another HotSpot private path

Evaluated variants:

- `dlsym(Klass::allocate_instance)`
- `dlsym(CollectedHeap::obj_allocate)`
- private `JavaCalls` / `Exceptions::*` path

Pros:

- Could preserve a stricter “no normal JNI construction” story if a specific JDK build exports the needed symbols.

Cons:

- Same failure class as `InstanceKlass::allocate_instance`: HotSpot C++ member functions are not a stable exported ABI. Linux/macOS name mangling, Windows decoration, compiler flags, and JDK update builds can differ.
- `CollectedHeap::obj_allocate` and allocation slow paths require more VM state than just a `Klass*`: GC barriers, allocation context, thread state, safepoint/handshake assumptions, and exception/OOME behavior.
- `JavaCalls`/`Exceptions::*` paths need handles, thread state transitions, and pending-exception protocol. If not exported, they are no better than the failed `InstanceKlass` attempt.
- A per-JDK/per-platform symbol matrix would be large and brittle, directly conflicting with the requested JDK 8-21 × OS × arch portability.

Risk: **High ABI risk.** Not recommended for the primary path. If pursued, it should be an explicit experimental branch, disabled by default, with startup validation and per-JDK symbol maps.

### Rank 5 — Opt-4: manually allocate a heap oop via TLAB bump and write object header / klass word / minimal fields

Pros:

- Appears to satisfy the strictest no-JNI construction interpretation on paper.
- The tree already has adjacent scaffolding such as `neko_rt_try_alloc_instance_fast_nosafepoint(...)` and TLAB offset parsing.

Cons:

- It is not enough to “write klass word + zero fields” for a portable `Throwable`. `Throwable` has VM-sensitive fields (`backtrace`, `stackTrace`, cause, suppressed exceptions) and constructor semantics that vary across JDKs.
- `env->Throw(jthrowable)` expects a JNI handle, not a raw oop. Manually allocating an oop still leaves the missing handle/rooting problem unless another private handle allocator is introduced.
- TLAB fast allocation is not a full allocation protocol. It has no portable slow path, no guaranteed GC/barrier compatibility, and breaks down under compact object headers or layout changes.
- Header layout, compressed class pointers, mark word, object alignment, and future compact-object-header configurations are not stable enough for JDK 8-21 portability.

Risk: **Very high correctness/GC risk.** Do not use this for synthetic exceptions.

## Recommendation (with rationale)

Adopt **Opt-6 implemented as Opt-5**: a bootstrap-only exception cache using normal JNI construction and global references, with a hard rule that **post-`JNI_OnLoad` generated code only calls `(*env)->Throw(env, cached_jthrowable)`**.

This is the minimum architecturally safe path because the requested strict set A-D is internally inconsistent on HotSpot: without exported allocation helpers and without JNI construction/handle creation, there is no portable way to create a `Throwable` object and pass a valid `jthrowable` to `env->Throw`. The practical amendment keeps the steady-state obfuscation goal intact while moving the unportable part into a small, auditable bootstrap exception.

Primary scope statement for the master plan:

> New JNI calls for synthetic exception construction are permitted only inside `JNI_OnLoad` before it returns. After `JNI_OnLoad`, the generated native image may use only `(*env)->Throw(env, jthrowable)` for synthetic exception dispatch; all synthetic exception values passed to `Throw` must be preconstructed global `jthrowable` handles from the bootstrap cache.

This preserves:

- zero JVMTI;
- no new Java classes;
- no source edits to Oracle 7 Fix G/H mirror helpers;
- `MANIFEST_ENTRY_SIZE = 88`;
- no `InstanceKlass::allocate_instance` / `Klass::allocate_instance` / `CollectedHeap::obj_allocate` dependency;
- no post-bootstrap `FindClass`, `NewObject`, `CallStaticMethod`, `ThrowNew`, `ExceptionCheck`, or `ExceptionClear`.

## Exact source changes (emitter-level, exact C code to emit)

### 1. `CCodeGenerator.java` — replace allocation function-pointer state with cached throwables

Current `NekoVmLayout` fields:

```c
/* W0 DD-6: dlsym'd function pointers (optional symbols) */
void* allocate_instance_fn;
void* java_thread_current_fn;
```

Change to either remove them entirely or leave them unused for non-exception allocation experiments. Add cached throwables as globals outside `NekoVmLayout` so `MANIFEST_ENTRY_SIZE = 88` remains untouched and VM layout parsing remains metadata-only:

```c
static jthrowable g_neko_throw_npe = NULL;
static jthrowable g_neko_throw_aioobe = NULL;
static jthrowable g_neko_throw_cce = NULL;
static jthrowable g_neko_throw_ae = NULL;
static jthrowable g_neko_throw_le = NULL;
static jthrowable g_neko_throw_oom = NULL;
static jthrowable g_neko_throw_imse = NULL;
static jthrowable g_neko_throw_ase = NULL;
static jthrowable g_neko_throw_nase = NULL;
static jthrowable g_neko_throw_bme = NULL;
static jthrowable g_neko_throw_loader_linkage = NULL;
```

Keep existing `klass_exc_*` fields if other raw metadata paths still use them for subtype checks or diagnostics, but synthetic exception dispatch should not depend on them.

### 2. `BootstrapEmitter.java` — delete the `allocate_instance` resolver as a required/expected path

Replace current `neko_resolve_strict_optional_symbols()` with a version that does not imply exception allocation readiness:

```c
static void neko_resolve_strict_optional_symbols(void) {
    /* DD-5 Oracle 9: no synthetic exception allocation uses HotSpot private
     * allocate_instance symbols. Those symbols are not exported on supported
     * OpenJDK 21/22 builds and are not portable across the target matrix.
     * Keep this block empty unless a future feature adds a separately-approved,
     * optional ABI-risk resolver. */
}
```

If `java_thread_current_fn` is still used by non-exception runtime code, split it into a differently named resolver and do not couple it to synthetic exception allocation.

### 3. `BootstrapEmitter.java` — emit bootstrap-only exception cache helpers

Emit this C block near other bootstrap helper functions, after JNI types are available and before `JNI_OnLoad`:

```c
static jthrowable neko_make_global_throwable(JNIEnv *env,
                                             const char *class_name,
                                             const char *message) {
    jclass cls = NULL;
    jmethodID init = NULL;
    jstring text = NULL;
    jobject local = NULL;
    jobject global = NULL;

    if (env == NULL || class_name == NULL) return NULL;

    cls = (*env)->FindClass(env, class_name);
    if (cls == NULL) goto cleanup;

    if (message != NULL) {
        jvalue args[1];
        init = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;)V");
        if (init == NULL) goto cleanup;
        text = (*env)->NewStringUTF(env, message);
        if (text == NULL) goto cleanup;
        args[0].l = text;
        local = (*env)->NewObjectA(env, cls, init, args);
    } else {
        init = (*env)->GetMethodID(env, cls, "<init>", "()V");
        if (init == NULL) goto cleanup;
        local = (*env)->NewObjectA(env, cls, init, NULL);
    }

    if (local == NULL) goto cleanup;
    global = (*env)->NewGlobalRef(env, local);

cleanup:
    if (local != NULL) (*env)->DeleteLocalRef(env, local);
    if (text != NULL) (*env)->DeleteLocalRef(env, text);
    if (cls != NULL) (*env)->DeleteLocalRef(env, cls);
    return (jthrowable)global;
}

static jboolean neko_init_throwable_cache(JNIEnv *env) {
    if (env == NULL) return JNI_FALSE;

    g_neko_throw_npe = neko_make_global_throwable(env, "java/lang/NullPointerException", NULL);
    g_neko_throw_aioobe = neko_make_global_throwable(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
    g_neko_throw_cce = neko_make_global_throwable(env, "java/lang/ClassCastException", NULL);
    g_neko_throw_ae = neko_make_global_throwable(env, "java/lang/ArithmeticException", "/ by zero");
    g_neko_throw_le = neko_make_global_throwable(env, "java/lang/LinkageError", NULL);
    g_neko_throw_oom = neko_make_global_throwable(env, "java/lang/OutOfMemoryError", NULL);
    g_neko_throw_imse = neko_make_global_throwable(env, "java/lang/IllegalMonitorStateException", NULL);
    g_neko_throw_ase = neko_make_global_throwable(env, "java/lang/ArrayStoreException", NULL);
    g_neko_throw_nase = neko_make_global_throwable(env, "java/lang/NegativeArraySizeException", NULL);
    g_neko_throw_bme = neko_make_global_throwable(env, "java/lang/BootstrapMethodError", NULL);
    g_neko_throw_loader_linkage = neko_make_global_throwable(
        env,
        "java/lang/LinkageError",
        "please check your native library load correctly"
    );

    return g_neko_throw_npe != NULL
        && g_neko_throw_aioobe != NULL
        && g_neko_throw_cce != NULL
        && g_neko_throw_ae != NULL
        && g_neko_throw_le != NULL
        && g_neko_throw_oom != NULL
        && g_neko_throw_imse != NULL
        && g_neko_throw_ase != NULL
        && g_neko_throw_nase != NULL
        && g_neko_throw_bme != NULL
        && g_neko_throw_loader_linkage != NULL
        ? JNI_TRUE : JNI_FALSE;
}

static inline jint neko_throw_cached(JNIEnv *env, jthrowable cached) {
    if (env == NULL || cached == NULL) return JNI_ERR;
    return (*env)->Throw(env, cached);
}

static inline jthrowable neko_cached_throwable_for_kind(uint32_t kind) {
    switch (kind) {
        case NEKO_THROW_NPE: return g_neko_throw_npe;
        case NEKO_THROW_AIOOBE: return g_neko_throw_aioobe;
        case NEKO_THROW_CCE: return g_neko_throw_cce;
        case NEKO_THROW_AE: return g_neko_throw_ae;
        case NEKO_THROW_LE: return g_neko_throw_le;
        case NEKO_THROW_OOM: return g_neko_throw_oom;
        case NEKO_THROW_IMSE: return g_neko_throw_imse;
        case NEKO_THROW_ASE: return g_neko_throw_ase;
        case NEKO_THROW_NASE: return g_neko_throw_nase;
        case NEKO_THROW_BME: return g_neko_throw_bme;
        default: return g_neko_throw_le;
    }
}
```

Also emit stable constants before the helper or in the shared runtime header:

```c
#define NEKO_THROW_NPE    1u
#define NEKO_THROW_AIOOBE 2u
#define NEKO_THROW_CCE    3u
#define NEKO_THROW_AE     4u
#define NEKO_THROW_LE     5u
#define NEKO_THROW_OOM    6u
#define NEKO_THROW_IMSE   7u
#define NEKO_THROW_ASE    8u
#define NEKO_THROW_NASE   9u
#define NEKO_THROW_BME    10u
```

### 4. `JniOnLoadEmitter.java` — initialize the cache inside `JNI_OnLoad` only

Insert this immediately after the existing successful `GetEnv` and before VMStruct parsing can call into strict raw paths:

```c
    if (!neko_init_throwable_cache(env)) {
        neko_error_log("failed to initialize bootstrap throwable cache");
        return JNI_ERR;
    }
```

Resulting skeleton:

```c
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    jint env_status;
    (void)reserved;
    g_neko_java_vm = vm;
    neko_init_debug_level_from_env();
    neko_native_debug_log("onload enter");

    env_status = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
    if (env_status != JNI_OK || env == NULL) {
        neko_error_log("GetEnv(JNI_VERSION_1_6) failed");
        return JNI_ERR;
    }

    if (!neko_init_throwable_cache(env)) {
        neko_error_log("failed to initialize bootstrap throwable cache");
        return JNI_ERR;
    }

    if (!neko_resolve_vm_symbols()) return JNI_VERSION_1_6;
    if (!neko_parse_vm_layout_strict(env)) return JNI_VERSION_1_6;
    if (!neko_capture_wellknown_klasses()) return JNI_VERSION_1_6;
    neko_mark_loader_loaded();
    ...
    return JNI_VERSION_1_6;
}
```

This is the only location where `FindClass`, `GetMethodID`, `NewStringUTF`, `NewObjectA`, `NewGlobalRef`, and `DeleteLocalRef` may appear.

### 5. `ImplBodyEmitter.java` — replace loader guard synthetic allocation

Current guard emits a post-bootstrap allocation path:

```c
void *error = neko_new_exception_oop(env, "java/lang/LinkageError", "please check your native library load correctly");
if (error != NULL) neko_throw(env, (jthrowable)error);
return <default>;
```

Replace with cached-handle dispatch:

```c
if (!neko_loader_ready()) {
    (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
    return <default>;
}
```

For `void` functions:

```c
if (!neko_loader_ready()) {
    (void)neko_throw_cached(env, g_neko_throw_loader_linkage);
    return;
}
```

Important: this assumes `env` is already available at the translated entrypoint. If the current implementation still obtains it via post-bootstrap `(*g_neko_java_vm)->GetEnv`, that is a separate strict-surface violation and should be fixed by passing/recovering `JNIEnv*` without a JNI function-table call. The exception-cache recommendation only changes construction/allocation; it does not bless post-bootstrap `GetEnv`.

### 6. Synthetic throw sites — emit cached throw by kind

Replace every synthetic path of this shape:

```c
void *oop = neko_new_exception_oop(env, "java/lang/NullPointerException", NULL);
if (oop != NULL) neko_throw(env, (jthrowable)oop);
return <default>;
```

with:

```c
(void)neko_throw_cached(env, g_neko_throw_npe);
return <default>;
```

Preferred generic helper shape for opcode translators:

```c
#define NEKO_THROW_AND_RETURN(env_expr, throwable_expr, ret_expr) \
    do { \
        (void)neko_throw_cached((env_expr), (throwable_expr)); \
        return ret_expr; \
    } while (0)

#define NEKO_THROW_AND_RETURN_VOID(env_expr, throwable_expr) \
    do { \
        (void)neko_throw_cached((env_expr), (throwable_expr)); \
        return; \
    } while (0)
```

Examples:

```c
/* null ATHROW / null receiver */
NEKO_THROW_AND_RETURN(env, g_neko_throw_npe, 0);

/* array bounds */
NEKO_THROW_AND_RETURN(env, g_neko_throw_aioobe, 0);

/* CHECKCAST miss */
NEKO_THROW_AND_RETURN(env, g_neko_throw_cce, NULL);

/* divide by zero */
NEKO_THROW_AND_RETURN(env, g_neko_throw_ae, 0);

/* allocation failure */
NEKO_THROW_AND_RETURN(env, g_neko_throw_oom, NULL);

/* negative array size */
NEKO_THROW_AND_RETURN(env, g_neko_throw_nase, NULL);

/* monitor mismatch */
NEKO_THROW_AND_RETURN_VOID(env, g_neko_throw_imse);

/* bootstrap/linkage failure */
NEKO_THROW_AND_RETURN(env, g_neko_throw_bme, 0);
```

### 7. Remove obsolete raw-oop exception construction helpers from strict builds

In strict mode, delete or compile out these helper paths from `Wave1RuntimeEmitter` / `Wave2FieldLdcEmitter` / `ImplBodyEmitter` post-bootstrap use:

```c
neko_new_exception_oop(...)
neko_take_pending_jni_exception_oop(...)
neko_throw_new(...)
neko_exception_occurred(...)
neko_exception_clear(...)
neko_exception_check(...)
```

`neko_throw(...)` may remain only if it is exactly a wrapper around `(*env)->Throw(env, jthrowable)` and every caller passes a cached global `jthrowable` handle, not a raw oop.

### 8. Do not change Oracle 7 Fix G/H mirror helpers

Leave the existing JDK 8 inline-cell vs JDK 9+ `OopHandle -> oop* slot -> wide oop` mirror resolution intact. The exception-cache path does not need mirror helpers for allocation.

## Master plan amendments (what DD-5 description needs to change to reflect reality)

### Replace m0117 / §6.0.2 premise

Current text says:

> Synthetic exception construction may use exactly one additional JNI call family: `(*env)->Throw(env, jthrowable)`. Exception oop allocation must use `dlsym`'d `InstanceKlass::allocate_instance(Thread*)`.

Replace with:

> Synthetic exception dispatch after `JNI_OnLoad` may use exactly one JNI call family: `(*env)->Throw(env, jthrowable)`. Synthetic exception objects are preconstructed during `JNI_OnLoad` only, using a narrowly allowed bootstrap JNI cache builder (`FindClass`, `GetMethodID`, `NewStringUTF`, `NewObjectA`, `NewGlobalRef`, `DeleteLocalRef`). After `JNI_OnLoad` returns, no JNI construction, lookup, global-ref creation, exception query/clear, or `ThrowNew` calls are permitted. All post-bootstrap synthetic throws must pass cached global `jthrowable` handles to `Throw`.

### Replace §6.0.2 preconditions

Remove:

```text
g_neko_allocate_instance_fn — dlsym'd InstanceKlass::allocate_instance(Thread*)
g_neko_java_thread_current_fn or equivalent strict thread resolver — provides Thread*
```

Add:

```text
g_neko_throw_npe / aioobe / cce / ae / le / oom / imse / ase / nase / bme / loader_linkage
    — global jthrowable handles created during JNI_OnLoad only.
```

Keep `klass_exc_*` pre-cache only if needed for metadata/type logic; it is no longer the synthetic allocation authority.

### Replace §6.0.2 sequence

Old sequence:

1. Locate current thread.
2. Call `allocate_instance_fn(klass, thread)`.
3. Initialize raw fields.
4. Call `(*env)->Throw(env, (jthrowable)exc_oop)`.

New sequence:

1. Bootstrap: `JNI_OnLoad` creates global `jthrowable` cache with normal JNI construction.
2. Runtime: choose the cached throwable for the synthetic exception kind.
3. Runtime: call `(*env)->Throw(env, cached_global_jthrowable)`.
4. Runtime: return the default value for the translated function's return type.

### Amend GATE-13 strict JNI surface verification

The old grep-only rule is no longer sufficient because bootstrap-only JNI calls are intentionally present. Split verification into two gates:

1. **Whole-source inventory:** list every `(*vm)->` / `(*env)->` call.
2. **Location policy:**
   - `(*vm)->GetEnv` appears exactly once, inside `JNI_OnLoad`.
   - `(*env)->FindClass`, `GetMethodID`, `NewStringUTF`, `NewObjectA`, `NewGlobalRef`, `DeleteLocalRef` appear only in `neko_make_global_throwable`, `neko_init_throwable_cache`, or directly in `JNI_OnLoad` bootstrap cache code.
   - `(*env)->Throw` may appear in post-bootstrap throw helpers/sites.
   - No post-bootstrap `FindClass`, `GetMethodID`, `NewObject*`, `AllocObject`, `NewGlobalRef`, `Call*Method`, `ThrowNew`, `ExceptionOccurred`, `ExceptionCheck`, `ExceptionClear`, or `DeleteGlobalRef`.

Suggested source-audit wording:

```text
GATE-13B bootstrap relaxation check:
- Allowed outside JNI_OnLoad/bootstrap-cache helpers: only `(*env)->Throw`.
- Allowed inside JNI_OnLoad/bootstrap-cache helpers only: `FindClass`, `GetMethodID`, `NewStringUTF`, `NewObjectA`, `NewGlobalRef`, `DeleteLocalRef`.
- `ThrowNew`, `ExceptionOccurred`, `ExceptionCheck`, `ExceptionClear`, `AllocObject`, `Call*Method`, `GetStatic*`, `SetStatic*`, `NewWeakGlobalRef`, `DeleteGlobalRef` remain forbidden unless separately approved.
```

### Amend W0 / W5 / W6 / W9 deliverables

- W0 deliverable 10 should no longer require resolving `InstanceKlass::allocate_instance(Thread*)`.
- W5 null-ATHROW should use `g_neko_throw_npe`.
- W6 NPE/AIOOBE/CCE/AE/OOME/NASE paths should use cached throwables.
- W9 IMSE/BME/LinkageError paths should use cached throwables.
- Any text saying “raw field init + `Throw`” should become “cached global `jthrowable` + `Throw`”.

## Verification plan

### Static/source verification

1. Confirm the bad resolver is gone or inert:

```bash
grep -rnE 'allocate_instance_fn|InstanceKlass17allocate_instance|dlsym_allocate_instance' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/
# expected: empty, or comments only saying it is intentionally not used
```

2. Confirm bootstrap cache exists:

```bash
grep -rnE 'neko_init_throwable_cache|neko_make_global_throwable|g_neko_throw_npe|g_neko_throw_loader_linkage' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/
# expected: hits in BootstrapEmitter/CCodeGenerator and throw-site emitters
```

3. Confirm no post-bootstrap construction helpers remain in strict paths:

```bash
grep -rnE 'neko_new_exception_oop|neko_throw_new|neko_exception_occurred|neko_exception_clear|neko_exception_check' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/
# expected: empty in strict emitted source; comments/tests only if deliberately retained behind non-strict flag
```

4. Confirm generated C call inventory follows the amended GATE-13 location policy:

```bash
grep -oE '\(\*(vm|env)\)->[A-Za-z_][A-Za-z0-9_]*' neko-native/build/native-src/generated/neko_impl.c | sort | uniq -c
```

Expected families:

- exactly one `(*vm)->GetEnv` in `JNI_OnLoad`;
- bootstrap-cache-only `FindClass`, `GetMethodID`, `NewStringUTF`, `NewObjectA`, `NewGlobalRef`, `DeleteLocalRef`;
- post-bootstrap `Throw` only.

### Runtime/debug verification

1. Debug run should no longer print `dlsym_allocate_instance=FAILED` as a required synthetic-exception readiness signal. Ideally that log disappears entirely.
2. Add one bootstrap trace after cache creation:

```c
neko_native_debug_log(
    "throwable_cache_ok=%d npe=%p le=%p loader_le=%p",
    (g_neko_throw_npe != NULL && g_neko_throw_le != NULL && g_neko_throw_loader_linkage != NULL) ? 1 : 0,
    g_neko_throw_npe,
    g_neko_throw_le,
    g_neko_throw_loader_linkage
);
```

Expected:

```text
[nk] n throwable_cache_ok=1 npe=0x... le=0x... loader_le=0x...
```

3. Loader guard failure path, if forced, should throw the cached `LinkageError` without constructing a new exception post-bootstrap.
4. Null-ATHROW / null receiver synthetic tests should observe the correct exception class.
5. W6/W9 synthetic tests should cover every cached kind at least once:
   - `NullPointerException`
   - `ArrayIndexOutOfBoundsException`
   - `ClassCastException`
   - `ArithmeticException`
   - `OutOfMemoryError` where feasible via forced allocation-failure path
   - `NegativeArraySizeException`
   - `IllegalMonitorStateException`
   - `ArrayStoreException`
   - `BootstrapMethodError`
   - `LinkageError`

### Cross-matrix verification

Run the existing canonical behavior gates, plus the amended JNI surface gate, across the highest-priority matrix first:

1. JDK 21 / Linux x64 / G1: prove the `allocate_instance` blocker is removed.
2. JDK 21 / Linux x64 / ZGC and Shenandoah: prove cached global handles remain GC-safe.
3. JDK 8 / Linux x64: prove constructors and global refs cover the oldest supported target.
4. macOS x64/aarch64 and Windows x64 smoke: prove no C++ mangled symbol dependency remains.

### Risk-specific tests

1. **Reusable throwable mutation test:** catch a synthetic cached exception, mutate its stack trace or cause if allowed, then trigger the same synthetic exception again. Document the observed behavior. If this becomes unacceptable, the next relaxation must be per-throw construction via JNI or JavaCalls, not manual raw allocation.
2. **Concurrent throw stress:** multiple Java threads trigger the same synthetic exception kind concurrently. Expected: no crash; same global object may be thrown concurrently. If mutation leaks are unacceptable, this design needs per-thread or pooled cached throwables.
3. **Stack trace expectation test:** assert only exception type/message for synthetic native exceptions, not exact throw-site stack trace, unless the plan is further relaxed to construct per throw.

## Bottom line

There is no portable strict-no-JNI raw allocation design that satisfies A-D after `InstanceKlass::allocate_instance` is proven absent from `.dynsym`. The minimum safe architecture is a deliberate bootstrap-only JNI relaxation: preconstruct global `jthrowable` handles in `JNI_OnLoad`, then keep steady-state synthetic dispatch to `env->Throw` only.
