# Oracle 2 Design — Pure-VMStructs Class Discovery (Zero-JVMTI)

**Task ID**: bg_a005e537
**Session**: ses_251d1e656ffe68xKpKyIyZ5LhT
**Duration**: 16m 17s
**Decision**: Design A — pure on-demand `Class.forName(name, false, ownerLoader)` + atomic cache

---

## Recommendation (one sentence)

Delete bootstrap JVMTI prewarm (`GetLoadedClasses` + `ClassPrepare`), delete the live hook, and converge class discovery to **first-hit `Class.forName(binaryName, false, ownerLoader)` with `CAS(NULL -> resolved_klass)` caching**. JDK 8/11/17/21 correctness is stable, implementation is delta-small, ABI risk is minimal. B/C/D all drag the project into HotSpot internal structures and concurrency timing traps.

## Candidate matrix

| Candidate | Correctness | Complexity | Cost | Risk | JDK span |
|---|---|---|---|---|---|
| **A. On-demand `Class.forName(..., false, loader)` + atomic cache** | ✅ JDK API contract | 1-2d (delete-path) | 1 resolve per owner class, amortized | Low | ✅ 8/11/17/21 |
| B. VMStructs walk (CLDG or SystemDictionary) | depends on VMStructs coverage | 1-2wks | zero runtime cost after prewarm | High (ABI drift, concurrent CLDG races, JDK8 requires reverse-engineering Dictionary) | Splits into 2+ paths |
| C. Patch HotSpot internal dispatch (e.g. `SystemDictionary::resolve_or_null`) | depends on patch point | 2+wks | zero | Very high (calling convention, stack layout, inlining, version drift) | Fragile |
| D. B at bootstrap + A fallback | = A for correctness | 1-2wks | zero cold-start per owner | = B risk surface | Overbuilt |

## Why A wins

**Correctness**
- `Class.forName(String, boolean, ClassLoader)` with `initialize=false` loads and links but does NOT run `<clinit>`. Per JLS 12.4.1 and JDK 17 `Class` doc: *"The class is initialized only if the initialize parameter is true"*.
- Existing `Wave1RuntimeEmitter.neko_load_class_noinit_with_loader()` already calls this API with `JNI_FALSE` as the second argument — no new code path required.
- Custom loader semantics preserved — same loader used by app code, no loader-identity mismatch.
- Concurrent first-callers: JVM internal class loading sync guarantees all threads receive same `Class<?>` reference; native side uses `CAS(NULL -> resolved)` to converge on a single `Klass*`.
- Translated methods exclude `<clinit>` and `<init>` by design, so owner class is always resolvable at first hit.

**Complexity**
- A is a delete-path: remove `GetLoadedClasses`, `ClassPrepare` callback install, `SetEventCallbacks`, `CreateRawMonitor`, `AddCapabilities`, JVMTI env init, all related helpers.
- B/C/D all require adding hundreds of LOC of fragile reverse engineering.

**Performance**
- Hot path: one atomic acquire load — same as current implementation.
- Cold path: one-off per owner class (amortized across many translated calls).
- Startup becomes faster (no prewarm of all loaded classes).

**Risk**
- Primary risk surface is future `ClassUnload` / `RedefineClasses` support (M5a-k, out of scope for this wave). A doesn't expand that risk.
- B/D add ABI drift + concurrent CLDG races to risk surface.

**Boot-time ordering**
- A requires only: `NekoVmLayout` resolved, manifest initialized, entry patch installed.
- No "SystemDictionary populated" precondition — owner class is resolved on first real call, by which time the JVM is fully operational.

**Safepoint safety**
- No raw-memory class graph traversal, so safepoint-sensitive surface is minimal.
- Slow path uses JNI `Class.forName`; normal JVM class loading sync handles placeholder/define token/initialization ordering.
- No JDK9+ `ClassLoaderData` linked-list race, no concurrent unload exposure.

## `<clinit>` ordering

- `Class.forName(name, false, loader)` → no `<clinit>` execution.
- Normal `<clinit>` still triggered by subsequent bytecode semantics (static field access, static method invocation, `new`, etc.).
- Ordering identical to unobfuscated behavior — no regression.

## Thread safety

```c
// Read path
cached = __atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);
if (cached != NULL) return cached;

// Miss path
klassObj = Class.forName(..., false, ownerLoader);  // JVM sync handles concurrency
resolved = neko_class_klass_pointer(klassObj);
Klass* expected = NULL;
__atomic_compare_exchange_n(&site->cached_klass,
                            &expected,
                            resolved,
                            JNI_FALSE,
                            __ATOMIC_ACQ_REL,
                            __ATOMIC_ACQUIRE);
if (expected != NULL) resolved = expected;  // lost race, adopt winner
return resolved;
```

## Implementation skeleton

```c
static jboolean neko_bootstrap_owner_discovery(JNIEnv *env) {
    (void)env;
    // Design A: no bootstrap VM walk, no live hook, no prewarm
    return JNI_TRUE;
}

static Klass* neko_resolve_owner_klass_on_demand(JNIEnv *env, NekoManifestLdcSite *site) {
    Klass *cached = (Klass*)__atomic_load_n(&site->cached_klass, __ATOMIC_ACQUIRE);
    if (cached != NULL) return cached;

    jclass owner_class = site->owner_class_slot ? *site->owner_class_slot : NULL;
    if (owner_class == NULL) return NULL;

    jobject owner_loader = neko_owner_class_loader(env, owner_class);
    char *binary_name = neko_ldc_site_binary_name(site);   // existing helper
    jclass klass_obj = neko_load_class_noinit_with_loader(env, binary_name, owner_loader);
    free(binary_name);
    if (owner_loader != NULL) neko_delete_local_ref(env, owner_loader);
    if (klass_obj == NULL || neko_exception_check(env)) return NULL;

    Klass *resolved = (Klass*)neko_class_klass_pointer(klass_obj);
    Klass *expected = NULL;
    __atomic_compare_exchange_n(&site->cached_klass,
                                &expected,
                                resolved,
                                JNI_FALSE,
                                __ATOMIC_ACQ_REL,
                                __ATOMIC_ACQUIRE);
    if (expected != NULL) resolved = expected;

    neko_delete_local_ref(env, klass_obj);
    return resolved;
}
```

## JDK VMStructs exposure table (justification for rejecting B)

| JDK | Exposed in VMStructs | Absent in VMStructs |
|---|---|---|
| **8** | `SystemDictionary::_dictionary`, `SystemDictionary::_placeholders`, `ClassLoaderDataGraph::_head`, `ClassLoaderData::_class_loader` (oop), `ClassLoaderData::_next`, `Klass::_name`, `InstanceKlass::_init_state`, `DictionaryEntry::_loader_data`, `DictionaryEntry::_pd_set` | `ClassLoaderData::_klasses`, `Klass::_next_link`, `Klass::_class_loader_data`, `Dictionary::_table`, `DictionaryEntry::{klass, next}` |
| **11** | `ClassLoaderDataGraph::_head`, `ClassLoaderData::{_class_loader (OopHandle), _next, _klasses (volatile), _dictionary}`, `Klass::{_next_link, _name, _class_loader_data}`, `InstanceKlass::_init_state` | `Dictionary::_table`, DictionaryEntry internals |
| **17** | same as 11 plus `_head` is `static_ptr_volatile_field` | same as 11 |
| **21** | same as 17 but `ClassLoaderData::_dictionary` is NOT in local `vmStructs.cpp`; `Dictionary` switched to `ConcurrentHashTable` — `_table`, `_instance_klass`, `_next` source-visible but not in VMStructs | Concurrent HashTable internals |

**Implication for B**: JDK 8 requires bespoke `SystemDictionary` + `Dictionary` / `Hashtable` reverse engineering; JDK 11/17 can use CLDG `_klasses` chain; JDK 21 loses `_dictionary` from VMStructs and needs ConcurrentHashTable walker. B splits into 2+ paths, each with concurrent unload considerations.

## Test strategy

1. Run 3 native test jars under JDK 8/11/17/21 matrix; verify each translated method's first call succeeds and subsequent calls hit cache.
2. `<clinit>` ordering test: class has static counter incremented in `<clinit>`; call translated path → assert counter == 0; explicitly trigger init → assert counter == 1.
3. Concurrent first-call test: N threads hit same translated method → all succeed, `cached_klass` stable non-null, no exception.
4. Custom loader test: two loaders define same-named class → each triggers translated call → bound to respective `Class<?>`.
5. Debug counter: log `ldc-cls resolve miss/hit` to verify cold resolve happens exactly once per owner class.

## Risks + mitigations

- **First-call latency**: amortized per owner class; hot path unchanged (single atomic read). Accept and measure.
- **Class unload / RedefineClasses**: out of scope for this wave; documented as "process-lifetime within supported test matrix"; M5 addresses invalidation.
- **owner_class_slot availability**: reuse existing bind-time `owner_class_slot` infrastructure (already present in `Wave3InvokeStaticEmitter` and LDC path).

## Open questions before implementation

1. Audit `neko_load_class_noinit_with_loader()` name handling — confirm `/` → `.` conversion and array descriptor handling are consistent across all call paths.
2. Standardize `cached_klass` writes to `CAS(NULL -> resolved)` everywhere (some paths currently use release-store).
3. Keep `NEKO_DEBUG_ENABLED` gated cold-resolve telemetry for future perf analysis.

## Effort

Short — 1-2 days.
