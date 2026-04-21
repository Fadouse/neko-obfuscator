# Oracle 3 Design — Well-Known String Class Capture for W1

**Context**: Oracle consultation #3 for Wave W1 escalation after three failed fixes on the same root cause.

**Decision**: Design C+E — bootstrap JNI no-init capture for `java/lang/String` and array klasses, keep boot `ClassLoaderData::_handles` handle-chain as the fast path, keep Candidate E as the failure fallback when root-chain allocation or publish misses self-check.

---

## Recommendation (one sentence)

Add a dedicated bootstrap helper that resolves `java/lang/String`, `[B`, and `[C` through the existing `neko_load_class_noinit(...)` + `neko_class_klass_pointer(...)` path before `neko_resolve_string_intern_layout()` and `neko_string_intern_prewarm_and_publish(env)`; this removes the accidental dependency on manifest-owner discovery and preserves Design A2 with a delta-small patch.

## Candidate matrix

| Candidate | Robustness | Complexity | New VMStructs | Publish-window safety | Verdict |
|---|---|---|---|---|---|
| **A. boot CLD `_klasses` walk** | JDK 11/17/21 workable, JDK 8 carries `_next_link` / `_klasses` exposure gap per Oracle 2 evidence | medium-large | `Klass::_next_link` plus JDK 8 special path | good once data is found | Loses on JDK span |
| **B. SystemDictionary / well-known tables** | JDK 8/11/17/21 split path, JDK 21 `ConcurrentHashTable` drift | large | several internals, table layout knowledge | good once data is found | Loses on portability |
| **C. JNI well-known capture** | strong across JDK 8/11/17/21 | small-medium | **0** | JNI work happens before chunk allocate/publish | **SELECTED** |
| **D. manifest pseudo-owner** | medium | medium | 0 | good | Pollutes manifest semantics and still leaves array klass capture unresolved |
| **E. per-call regenerate** | strong as a fallback | already present | 0 | good | **FALLBACK ONLY** |

## Why C wins

1. **Robustness**
   - `java/lang/String`, `[B`, and `[C` are core classes available on every supported JDK.
   - Existing helper `neko_load_class_noinit(...)` already routes through `Class.forName(name, false, loader)`, so the no-`<clinit>` contract is explicit.
   - Existing helper `neko_class_klass_pointer(...)` already unwraps the mirror using `java_lang_Class::_klass`, which is already in `NekoVmLayout`.

2. **Simplicity**
   - The fix lives entirely in bootstrap.
   - Deep A keeps the current boot-CLD handle-chain code, slot wiring, self-check, and site read path.
   - The patch removes the accidental dependency on `neko_discover_manifest_owners(...)` for a class that is never a user manifest owner.

3. **Constraint compliance**
   - Zero new Java classes.
   - Zero new loader static fields.
   - Zero new JVMTI usage for this path.
   - JNI usage stays minimal and aligns with helpers already present in the tree.

4. **Safepoint safety**
   - All JNI class capture happens before chunk allocation and before `*_handles._head` publication.
   - The publish window remains: compute count → `malloc` chain → self-check → CAS publish → assign cells.

5. **Failure mode**
   - If boot-CLD chain allocation, self-check, or CAS publish fails, backend flips to Candidate E fallback exactly as Oracle 1 already allowed.
   - The well-known class capture itself should succeed on supported JDK 8/11/17/21. Treat capture failure as an unsupported-runtime signal worth logging loudly.

## Why the other candidates lose

### A. Walk boot CLD `_klasses`

A carries VM ABI drift on the one field the current codebase does **not** already parse: `Klass::_next_link`.
Oracle 2 already recorded the portability gap: JDK 11/17/21 expose enough structure for a `_klasses` walk, while JDK 8 loses the stable path in VMStructs and forces a second implementation.
The byte-compare itself is easy because current code already has:

- `g_neko_vm_layout.off_cld_klasses`
- `g_neko_vm_layout.off_klass_name`
- `g_neko_vm_layout.off_symbol_length`
- `g_neko_vm_layout.off_symbol_body`
- `neko_read_symbol_bytes(...)`

Target bytes would be:

- `"java/lang/String"` length `16`
- `"[B"` length `2`
- `"[C"` length `2`

The missing list-link field is the blocker that makes A lose on robustness.

### B. SystemDictionary / well-known tables

Oracle 2 already mapped the portability problem:

- JDK 8 exposes `SystemDictionary::_dictionary`
- JDK 11/17 shift the useful walk toward CLDG data
- JDK 21 moves dictionary internals behind `ConcurrentHashTable`, and VMStructs coverage becomes sparse for a safe generic walker

B buys one class lookup through a much larger reverse-engineering surface.

### D. manifest pseudo-owner

D keeps the runtime simple and makes the manifest lie.
`java/lang/String` is a runtime dependency, not a translated owner, and `[B` / `[C` still need separate handling because they are array descriptors, not manifest owners.
It also contaminates manifest-owner accounting and any rule that assumes owner entries correspond to translated application code.

### E. per-call regenerate

E remains the right fallback.
The cost shape is allocation-scale work per hit: decode payload, allocate backing array, allocate `String`, copy bytes, and store fields. Budget it as hundreds of nanoseconds to low microseconds per hot hit for short literals, with linear growth by payload size. That profile is fine for fail-open behavior and risky as the default fast path under W12 perf gating.

## Selected design

### 1. New bootstrap helper

Add one helper pair in `BootstrapEmitter`:

```c
static jboolean neko_capture_bootstrap_klass_noinit(JNIEnv *env,
                                                    const char *internal_name,
                                                    void **slot) {
    jclass klass = NULL;
    void *klass_ptr = NULL;
    if (env == NULL || internal_name == NULL || slot == NULL) return JNI_FALSE;
    if (*slot != NULL) return JNI_TRUE;

    klass = neko_load_class_noinit(env, internal_name);
    if (klass == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
#ifdef NEKO_DEBUG_ENABLED
        if (neko_debug_enabled()) {
            neko_native_debug_log("wellknown_klass_capture_fail=name=%s", internal_name);
        }
#endif
        return JNI_FALSE;
    }

    klass_ptr = neko_class_klass_pointer(klass);
    if (klass_ptr == NULL) {
#ifdef NEKO_DEBUG_ENABLED
        if (neko_debug_enabled()) {
            neko_native_debug_log("wellknown_klass_capture_null=name=%s", internal_name);
        }
#endif
        neko_delete_local_ref(env, klass);
        return JNI_FALSE;
    }

    *slot = klass_ptr;
#ifdef NEKO_DEBUG_ENABLED
    if (neko_debug_enabled()) {
        neko_native_debug_log("wellknown_klass_capture_ok=name=%s ptr=%p", internal_name, klass_ptr);
    }
#endif
    neko_delete_local_ref(env, klass);
    return JNI_TRUE;
}

static jboolean neko_capture_required_string_klasses(JNIEnv *env) {
    jboolean ok_string;
    jboolean ok_byte;
    jboolean ok_char;
    if (NEKO_STRING_INTERN_SLOT_COUNT == 0u) return JNI_TRUE;

    ok_string = neko_capture_bootstrap_klass_noinit(env,
                                                    "java/lang/String",
                                                    &g_neko_vm_layout.klass_java_lang_String);
    ok_byte = neko_capture_bootstrap_klass_noinit(env,
                                                  "[B",
                                                  &g_neko_vm_layout.klass_array_byte);
    ok_char = neko_capture_bootstrap_klass_noinit(env,
                                                  "[C",
                                                  &g_neko_vm_layout.klass_array_char);
#ifdef NEKO_DEBUG_ENABLED
    if (neko_debug_enabled()) {
        neko_native_debug_log(
            "wellknown_string_capture_ok=%d string=%p byte=%p char=%p",
            (ok_string && ok_byte && ok_char) ? 1 : 0,
            g_neko_vm_layout.klass_java_lang_String,
            g_neko_vm_layout.klass_array_byte,
            g_neko_vm_layout.klass_array_char
        );
    }
#endif
    return (ok_string && ok_byte && ok_char) ? JNI_TRUE : JNI_FALSE;
}
```

### 2. Bootstrap sequence placement

Place the new step immediately after `neko_parse_vm_layout(env)` succeeds.
That gives the helper access to `off_class_klass` and decouples string capture from manifest-owner discovery.

Recommended `JNI_OnLoad` ordering:

```c
if (!neko_resolve_vm_symbols()) return JNI_VERSION_1_6;
if (!neko_parse_vm_layout(env)) return JNI_VERSION_1_6;

(void)neko_capture_required_string_klasses(env);   // new primary source
if (g_neko_vm_layout.klass_java_lang_String != NULL) {
    neko_resolve_string_intern_layout();
}

// existing bootstrap work can stay in place
if (!neko_init_jvmti(vm, jvmti)) return JNI_VERSION_1_6;
if (!neko_discover_loaded_classes(env, jvmti)) return JNI_VERSION_1_6;
if (!neko_discover_manifest_owners(env, jvmti)) return JNI_VERSION_1_6;

neko_string_intern_prewarm_and_publish(env);
```

This ordering gives W1 a self-contained string bootstrap path today and stays forward-compatible with Oracle 2’s broader zero-JVMTI cleanup.

### 3. Prewarm gate

Deep A’s current implementation eagerly seeds root cells with initial `String` oops. With that shape, prewarm needs all three klass pointers ready.
Add a readiness check at the top of `neko_string_intern_prewarm_and_publish()`:

```c
static jboolean neko_string_root_bootstrap_ready(void) {
    if (g_neko_vm_layout.klass_java_lang_String == NULL) return JNI_FALSE;
    if (g_neko_vm_layout.klass_array_char == NULL) return JNI_FALSE;
    if (g_neko_vm_layout.off_string_coder >= 0 && g_neko_vm_layout.klass_array_byte == NULL) return JNI_FALSE;
    return JNI_TRUE;
}

static void neko_string_intern_prewarm_and_publish(JNIEnv *env) {
    void *boot_cld;
    NekoChunkedHandleListChunk *chunk_head;
    if (env == NULL) return;
    if (!neko_string_root_bootstrap_ready()) {
        g_neko_string_root_backend = NEKO_STRING_ROOT_BACKEND_FALLBACK_REGENERATE;
#ifdef NEKO_DEBUG_ENABLED
        if (neko_debug_enabled()) {
            neko_native_debug_log("string_root_bootstrap_ready=0 string=%p byte=%p char=%p coder_off=%td",
                                  g_neko_vm_layout.klass_java_lang_String,
                                  g_neko_vm_layout.klass_array_byte,
                                  g_neko_vm_layout.klass_array_char,
                                  g_neko_vm_layout.off_string_coder);
        }
#endif
        neko_log_boot_cld_root_chain_result(0, "candidate_e");
        return;
    }

    // existing W1 chain allocation / self-check / publish / assign path
}
```

### 4. Why `[B` and `[C` belong in the same helper

For strict Oracle 1 A2 semantics, boot-chain publication itself only needs the root cells.
Deep A’s current eager-seeding implementation also calls `neko_create_ldc_string_oop(...)` during `neko_assign_string_root_cells(...)`, and that code path needs:

- `g_neko_vm_layout.klass_java_lang_String`
- `g_neko_vm_layout.klass_array_char`
- `g_neko_vm_layout.klass_array_byte` on JDK 9+

Capturing all three in the same helper keeps the delta small and unblocks both:

- eager bootstrap seeding now
- Candidate E per-call fallback later

`[Ljava/lang/String;`, `java/lang/Object`, and `klass_array_object` do not participate in W1 string-root publish.

## New VMStructs offsets required

**Required additions: 0.**

This design uses only fields the code already parses today:

- `java_lang_Class::_klass` → `g_neko_vm_layout.off_class_klass`
- `java_lang_String::_value` → `g_neko_vm_layout.off_string_value`
- `java_lang_String::_coder` → `g_neko_vm_layout.off_string_coder`
- existing boot CLD fields already used by Design A2

No `ClassLoaderData::_klasses` walk is needed, so no `Klass::_next_link` field is needed.

## Self-check and trace points

Keep the existing authoritative W1-C trace exactly as-is:

- success: `boot_cld_root_chain_ok=1`
- failure: `boot_cld_root_chain_ok=0 fallback=candidate_e`

Add these traces around the new capture path:

- per-class success: `wellknown_klass_capture_ok=name=%s ptr=%p`
- per-class miss: `wellknown_klass_capture_fail=name=%s`
- aggregate result: `wellknown_string_capture_ok=%d string=%p byte=%p char=%p`
- prewarm gate miss: `string_root_bootstrap_ready=0 string=%p byte=%p char=%p coder_off=%td`

Expected healthy debug sequence on a jar with string-LDC sites:

1. `wellknown_string_capture_ok=1 ...`
2. `boot_cld_root_chain_ok=1`
3. `neko_ldc_string_site_oop=idx=<n> ptr=0x...`

The old symptom line `si unresolved java/lang/String.hash` should disappear once `klass_java_lang_String` is captured. If it remains while `wellknown_string_capture_ok=1`, the next bug lives in field-offset walking, not class capture.

## Test strategy for Deep A

1. **Single-process debug run on each jar**
   - Run each of `TEST.jar`, `obfusjack-test21.jar`, and `SnakeGame.jar` with `NEKO_NATIVE_DEBUG=1`.
   - Verify `wellknown_string_capture_ok=1` appears once per process.

2. **W1-C gate**
   - Verify `boot_cld_root_chain_ok=1` appears exactly once per process.
   - Zero occurrences means bootstrap never published. More than one occurrence means duplicate publish.

3. **W1-D gate**
   - Verify every exercised string-LDC site logs `neko_ldc_string_site_oop=idx=<n> ptr=0x...` with a non-nil pointer.
   - `TEST.jar` and `obfusjack-test21.jar` should move from `ptr=(nil)` to non-nil immediately with the current eager-prewarm shape.

4. **Secondary symptom cleanup**
   - Verify `si unresolved java/lang/String.hash` disappears.
   - If it remains, capture succeeded and the follow-up work belongs in `neko_resolve_field_offset(...)`.

5. **Behavior and baseline**
   - Re-run `./gradlew :neko-native:build`.
   - Re-run the existing smoke matrix and confirm the current baseline stays at `83 tests, 17 failed, 4 skipped` with the same exit-code profile and no admission regressions.

6. **Fallback sanity**
   - Force `neko_publish_string_root_chain(...)` to return false in a local diagnostic build and confirm:
     - `boot_cld_root_chain_ok=0 fallback=candidate_e`
     - translated methods still run through per-call regeneration

## Expected commit delta

**Medium** — about 40–80 lines in emitted bootstrap C plus one `JNI_OnLoad` call-site change.

- one new helper pair
- one readiness gate
- trace additions
- zero manifest format changes
- zero `NekoVmLayout` growth

## Concrete implementation recommendation

Land this as a narrow bootstrap patch in Deep A’s branch:

1. add `neko_capture_required_string_klasses(env)`
2. call it immediately after `neko_parse_vm_layout(env)`
3. guard `neko_resolve_string_intern_layout()` behind captured `klass_java_lang_String`
4. add `neko_string_root_bootstrap_ready()` at prewarm entry
5. keep Candidate E backend as the publish-failure fallback

That patch directly targets the real blocker and gives W1-C / W1-D a clear pass condition on the next run.
