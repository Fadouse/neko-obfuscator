# Oracle 4 — Strict No-JNI Architecture (Option A)

## Summary

| DD | Decision | One-line consequence |
|---|---|---|
| DD-1 | Use a pure boot-`ClassLoaderData::_klasses` name scan and add `Klass::_next_link`; reject SystemDictionary / `_well_known_klasses` / internal `find_class` dlsym paths. | `java/lang/String`, `[B`, and `[C` become independent of manifest-owner discovery and require no JNI. |
| DD-2 | Replace `owner_class_slot`-driven resolution with a hybrid: bootstrap preseed for boot-visible owners, runtime miss resolution from the enclosing translated method’s holder CLD, then `CAS(NULL -> Klass*)`. | Every site caches `Klass*` directly; no `jclass`, no loader mirror JNI calls. |
| DD-3 | Keep boot-CLD root cells only for true long-lived oop anchors (Oracle 1 A2). Remove static-base globals entirely and remove owner-class globals entirely. | `NewGlobalRef` / `DeleteGlobalRef` disappear instead of being reimplemented. |
| DD-4 | Treat `Klass::_java_mirror` as the canonical zero-JNI bridge from metadata to `java/lang/Class` mirror / static base. | All mirror/static-base access becomes VMStructs + OopHandle loads. |
| DD-5 | For existing exception objects, write `JavaThread::_pending_exception` directly. For synthetic exceptions, do **not** invent a fake pure-VMStruct constructor path. | `ATHROW` is clean; native-generated NPE/AIOOBE/CCE/LinkageError still need an explicit constraint exception or an ABI-risk branch. |
| DD-6 | Delete remaining bootstrap / bind / reflection JNI families and replace them with CLDG/klass/method/field walkers or remove the feature path. | `JniOnLoadEmitter`, `Wave2FieldLdcEmitter`, `Wave3InvokeStaticEmitter`, `Wave1RuntimeEmitter`, and some `OpcodeTranslator` intrinsics need structural rewrites, not point fixes. |

Reference-note: `explore-jni-audit-option-a.md` was not present at task start, so this design used the authoritative plans/logs plus direct reads of the current generator sources.

## DD-1: Well-known class capture

### Decision

Use a pure boot `ClassLoaderData::_klasses` walk and compare `Klass::_name` Symbol bytes against literal names:

- `"java/lang/String"`
- `"[B"`
- `"[C"`

This is the primary strict-no-JNI replacement for Oracle 3’s rejected `FindClass` / `Class.forName` capture.

### Rationale (why rejected B / B' / C)

**Why A wins**

1. It stays on the same VMStructs surface already required by Oracle 1 A2: `ClassLoaderDataGraph::_head`, `ClassLoaderData::_next`, `ClassLoaderData::_class_loader`, `ClassLoaderData::_klasses`, `Klass::_name`, `Symbol::_length`, `Symbol::_body`.
2. The only new structural dependency on the primary path is `Klass::_next_link`. That is a much smaller risk surface than introducing SystemDictionary or HotSpot internal resolver symbols.
3. The walk is naturally safepoint-safe for this use because it runs in bootstrap before translated entrypoints open, and all three target classes are boot-owned process-lifetime metadata.
4. It fixes the exact Deep A blocker: `java/lang/String` is not a manifest owner, so class capture must be independent of manifest-owner discovery.

**Why B loses (`SystemDictionary` walk)**

- JDK 8 / 11 / 17 / 21 do not share one stable dictionary shape.
- JDK 21’s `ConcurrentHashTable` pushes the design into a version-split walker that is substantially larger and harder to self-check.
- The problem to solve is three fixed boot classes, not general dictionary traversal.

**Why B' loses (internal `SystemDictionary::find_class` dlsym)**

- Mangled symbol names and signatures are compiler-, build-, and JDK-specific.
- It is exactly the kind of ABI-stable-looking / ABI-unstable-in-practice hook the user asked us to avoid.
- The data-walk path is simpler and more reviewable.

**Why C loses (`Universe::_well_known_klasses`)**

- Exposure through VMStructs is not established in the current tree.
- Even if exposed, index semantics are more version-sensitive than a name match on `Klass::_name`.
- It solves fewer adjacent problems than the `_klasses` walk, which is also reusable for DD-2 / DD-6.

### Full C skeleton

```c
static jboolean neko_symbol_equals_literal(void *sym,
                                           const char *literal,
                                           uint16_t literal_len) {
    const uint8_t *body = NULL;
    uint16_t sym_len = 0;
    if (sym == NULL || literal == NULL) return JNI_FALSE;
    if (!neko_read_symbol_bytes(sym, &body, &sym_len)) return JNI_FALSE;
    if (sym_len != literal_len) return JNI_FALSE;
    return literal_len == 0u || memcmp(body, literal, literal_len) == 0
        ? JNI_TRUE : JNI_FALSE;
}

static inline Klass* neko_cld_first_klass(void *cld) {
    if (cld == NULL || g_neko_vm_layout.off_cld_klasses < 0) return NULL;
    return *(Klass**)((uint8_t*)cld + g_neko_vm_layout.off_cld_klasses);
}

static inline Klass* neko_klass_next_link(Klass *klass) {
    if (klass == NULL || g_neko_vm_layout.off_klass_next_link < 0) return NULL;
    return *(Klass**)((uint8_t*)klass + g_neko_vm_layout.off_klass_next_link);
}

static Klass* neko_find_named_klass_in_cld(void *cld,
                                           const char *internal_name,
                                           uint16_t internal_name_len) {
    for (Klass *k = neko_cld_first_klass(cld); k != NULL; k = neko_klass_next_link(k)) {
        void *name_sym;
        if (g_neko_vm_layout.off_klass_name < 0) return NULL;
        name_sym = *(void**)((uint8_t*)k + g_neko_vm_layout.off_klass_name);
        if (neko_symbol_equals_literal(name_sym, internal_name, internal_name_len)) {
            return k;
        }
    }
    return NULL;
}

static jboolean neko_capture_boot_well_known_klasses(void) {
    void *boot_cld = neko_find_boot_class_loader_data();
    if (boot_cld == NULL) return JNI_FALSE;

    g_neko_vm_layout.klass_java_lang_String =
        neko_find_named_klass_in_cld(boot_cld, "java/lang/String", 16u);
    g_neko_vm_layout.klass_array_byte =
        neko_find_named_klass_in_cld(boot_cld, "[B", 2u);
    g_neko_vm_layout.klass_array_char =
        neko_find_named_klass_in_cld(boot_cld, "[C", 2u);

#ifdef NEKO_DEBUG_ENABLED
    if (neko_debug_enabled()) {
        neko_native_debug_log(
            "boot_well_known_scan_ok=%d string=%p byte=%p char=%p",
            (g_neko_vm_layout.klass_java_lang_String != NULL
             && g_neko_vm_layout.klass_array_byte != NULL
             && g_neko_vm_layout.klass_array_char != NULL) ? 1 : 0,
            g_neko_vm_layout.klass_java_lang_String,
            g_neko_vm_layout.klass_array_byte,
            g_neko_vm_layout.klass_array_char
        );
    }
#endif

    return (g_neko_vm_layout.klass_java_lang_String != NULL
            && g_neko_vm_layout.klass_array_byte != NULL
            && g_neko_vm_layout.klass_array_char != NULL)
        ? JNI_TRUE : JNI_FALSE;
}

/* strict JNI_OnLoad ordering */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    (void)reserved;
    g_neko_java_vm = vm;
#ifdef NEKO_DEBUG_ENABLED
    {
        const char *env_debug = getenv("NEKO_NATIVE_DEBUG");
        neko_debug_level = env_debug != NULL ? atoi(env_debug) : 0;
    }
#endif
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_VERSION_1_6;
    }

    if (!neko_resolve_vm_symbols()) return JNI_VERSION_1_6;
    if (!neko_parse_vm_layout_strict()) return JNI_VERSION_1_6;
    if (!neko_capture_boot_well_known_klasses()) return JNI_VERSION_1_6;

    neko_resolve_string_intern_layout();
    neko_string_intern_prewarm_and_publish_strict();
    neko_patch_discovered_methods_strict();
    return JNI_VERSION_1_6;
}
```

### VMStructs offsets required

Add to `NekoVmLayout`:

- `ptrdiff_t off_klass_next_link;`

Parse from VMStructs:

- `Klass::_next_link`

No new SystemDictionary, Dictionary, ConcurrentHashTable, or `Universe::_well_known_klasses` offsets are required on the primary path.

### JDK 8 / 11 / 17 / 21 compat notes

- **JDK 11 / 17 / 21**: this is the preferred path. It matches the current codebase’s existing CLDG + Symbol strategy and only adds one missing link field.
- **JDK 8**: this is the main risk point. If the target JDK 8 build does **not** expose `Klass::_next_link` in VMStructs, strict Option A has no clean replacement for DD-1. In that case the loader must fail fast with a loud unsupported-runtime diagnostic; do **not** silently swap to SystemDictionary or internal resolver symbols.
- **GC correctness**: the walk uses only metadata pointers and Symbol bytes during bootstrap. No heap oop is cached long-term by this path.
- **Startup cost**: trivial. One linear walk of the boot CLD klass list.

## DD-2: Owner-class resolution

### Decision (include `owner_class_slot` fate)

Use a hybrid `Klass*`-first design:

1. **Bootstrap preseed**: walk the boot CLD once and seed `site->cached_klass` for any manifest owners that are boot-visible.
2. **Runtime miss path**: resolve from the enclosing translated method’s holder CLD, then optional parent-loader CLDs, then boot CLD, and publish the result with `CAS(NULL -> resolved_klass)`.
3. **`owner_class_slot` fate**: eliminate it from runtime semantics. The strict path should stop populating and stop reading `owner_class_slot`; the canonical per-site state is just `cached_klass`.

The minimum extra metadata needed is not a `jclass*`. It is two side arrays populated when each translated method is matched / patched:

- `g_neko_manifest_method_holder_klass[method_index]`
- `g_neko_manifest_method_holder_cld[method_index]`

That gives every site a loader-correct starting point without JNI.

### Rationale

1. `owner_class_slot` exists only to carry a JNI mirror across calls so later code can do `getClassLoader()` and `Class.forName(...)`. Under strict Option A that entire idea is invalid.
2. The translated method’s own `Method* -> holder Klass* -> holder CLD` is already the right loader anchor. Starting from that CLD is both more accurate and cheaper than general global scans.
3. `cached_klass` is the value the runtime actually wants. Storing a `jclass` and then unwrapping it later is pointless overhead under strict rules.
4. Boot preseed removes most cold misses for JDK/platform/library owners. Runtime misses remain amortized once per site.

### Full C skeleton

```c
/* side arrays populated by strict bootstrap method discovery / patching */
static void* g_neko_manifest_method_holder_klass[NEKO_MANIFEST_STORAGE_COUNT] = { NULL };
static void* g_neko_manifest_method_holder_cld[NEKO_MANIFEST_STORAGE_COUNT] = { NULL };
static ptrdiff_t g_neko_off_class_loader_parent = -1;
static void* g_neko_boot_cld = NULL;
static void* g_neko_platform_cld = NULL; /* optional cache */
static void* g_neko_system_cld = NULL;   /* optional cache */

static inline oop neko_cld_loader_oop_nosafepoint(void *cld) {
    void *loader_field;
    if (cld == NULL || g_neko_vm_layout.off_cld_class_loader < 0) return NULL;
    loader_field = (uint8_t*)cld + g_neko_vm_layout.off_cld_class_loader;
    if (g_neko_vm_layout.cld_class_loader_is_oophandle) {
        void *handle = *(void**)loader_field;
        if (handle == NULL || g_neko_vm_layout.off_oophandle_obj < 0) return NULL;
        return neko_load_oop_from_cell((uint8_t*)handle + g_neko_vm_layout.off_oophandle_obj);
    }
    return *(oop*)loader_field;
}

static inline oop neko_loader_parent_oop_nosafepoint(oop loader) {
    if (loader == NULL || g_neko_off_class_loader_parent < 0) return NULL;
    return neko_load_heap_oop_from_published(loader, (int32_t)g_neko_off_class_loader_parent);
}

static void* neko_lookup_cld_by_loader_oop_cold(oop loader_oop) {
    for (void *cld = neko_boot_cld_head(); cld != NULL;
         cld = *(void**)((uint8_t*)cld + g_neko_vm_layout.off_cld_next)) {
        if (neko_cld_loader_oop_nosafepoint(cld) == loader_oop) return cld;
    }
    return NULL;
}

static Klass* neko_scan_cld_chain_for_owner(void *start_cld,
                                            const char *owner_internal,
                                            uint16_t owner_len) {
    Klass *resolved = NULL;
    void *cld = start_cld;

    if (cld != NULL) {
        resolved = neko_find_named_klass_in_cld(cld, owner_internal, owner_len);
        if (resolved != NULL) return resolved;
    }

    if (g_neko_off_class_loader_parent >= 0) {
        oop loader = neko_cld_loader_oop_nosafepoint(start_cld);
        while (loader != NULL) {
            void *parent_cld;
            loader = neko_loader_parent_oop_nosafepoint(loader);
            if (loader == NULL) break;
            if (loader == neko_cld_loader_oop_nosafepoint(g_neko_platform_cld)) {
                parent_cld = g_neko_platform_cld;
            } else if (loader == neko_cld_loader_oop_nosafepoint(g_neko_system_cld)) {
                parent_cld = g_neko_system_cld;
            } else {
                parent_cld = neko_lookup_cld_by_loader_oop_cold(loader);
            }
            if (parent_cld == NULL) break;
            resolved = neko_find_named_klass_in_cld(parent_cld, owner_internal, owner_len);
            if (resolved != NULL) return resolved;
        }
    }

    return g_neko_boot_cld == NULL
        ? NULL
        : neko_find_named_klass_in_cld(g_neko_boot_cld, owner_internal, owner_len);
}

static Klass* neko_site_owner_klass_nosafepoint(uint32_t manifest_method_index,
                                                const char *current_owner_internal,
                                                const char *site_owner_internal,
                                                void *volatile *cached_klass_slot) {
    Klass *cached;
    Klass *resolved = NULL;
    Klass *holder_klass;
    void *holder_cld;
    Klass *expected = NULL;
    uint16_t site_owner_len;

    if (site_owner_internal == NULL || cached_klass_slot == NULL) return NULL;

    cached = (Klass*)__atomic_load_n(cached_klass_slot, __ATOMIC_ACQUIRE);
    if (cached != NULL) return cached;

    holder_klass = (Klass*)g_neko_manifest_method_holder_klass[manifest_method_index];
    holder_cld   = g_neko_manifest_method_holder_cld[manifest_method_index];
    if (holder_klass == NULL || holder_cld == NULL) return NULL;

    if (current_owner_internal != NULL && strcmp(current_owner_internal, site_owner_internal) == 0) {
        resolved = holder_klass;
    } else {
        site_owner_len = (uint16_t)strlen(site_owner_internal);
        resolved = neko_scan_cld_chain_for_owner(holder_cld, site_owner_internal, site_owner_len);
    }

    if (resolved == NULL) return NULL;

    if (!__atomic_compare_exchange_n(cached_klass_slot,
                                     &expected,
                                     resolved,
                                     JNI_FALSE,
                                     __ATOMIC_ACQ_REL,
                                     __ATOMIC_ACQUIRE)) {
        resolved = expected;
    }
    return resolved;
}

/* site-specific adapters */
static inline Klass* neko_field_site_owner_klass_nosafepoint(uint32_t manifest_method_index,
                                                             const NekoManifestMethod *method,
                                                             NekoManifestFieldSite *site) {
    return site == NULL || method == NULL ? NULL
        : neko_site_owner_klass_nosafepoint(manifest_method_index,
                                            method->owner_internal,
                                            site->owner_internal,
                                            &site->cached_klass);
}

static inline Klass* neko_ldc_site_owner_klass_nosafepoint(uint32_t manifest_method_index,
                                                           const NekoManifestMethod *method,
                                                           NekoManifestLdcSite *site) {
    return site == NULL || method == NULL || site->kind != NEKO_LDC_KIND_CLASS ? NULL
        : neko_site_owner_klass_nosafepoint(manifest_method_index,
                                            method->owner_internal,
                                            (const char*)site->raw_constant_utf8,
                                            &site->cached_klass);
}
```

Bootstrap extras for this design:

```c
static void neko_record_manifest_match_strict(uint32_t index,
                                              void *method_star,
                                              Klass *holder_klass,
                                              void *holder_cld) {
    if (index >= g_neko_manifest_method_count || method_star == NULL) return;
    neko_manifest_lock_enter();
    g_neko_manifest_method_stars[index] = method_star;
    g_neko_manifest_method_holder_klass[index] = holder_klass;
    g_neko_manifest_method_holder_cld[index] = holder_cld;
    if (g_neko_manifest_patch_states[index] == NEKO_PATCH_STATE_NONE) {
        (void)neko_patch_method(&g_neko_manifest_methods[index], method_star);
    }
    neko_manifest_lock_exit();
}
```

### CAS + caching semantics

- **Fast path**: one acquire-load of `site->cached_klass`.
- **Slow path**: no allocation, no JNI, no JavaCalls, no safepoint-permitting helper. It is a pure metadata / raw-memory walk.
- **Publication**: runtime uses `CAS(NULL -> resolved)` only. Losers adopt the winner.
- **Bootstrap preseed**: may use plain release-store before translated entries are reachable; runtime still treats the slot as CAS-owned.
- **Lifetime**: `cached_klass` is intentionally process-lifetime until W11a class-unload invalidation. This design is not unload-safe yet; that is acceptable because W11a already exists for that work.

### Startup cost estimate

- Boot preseed: `O(boot_cld_klass_count + manifest_owner_count)` once.
- Runtime miss: `O(classes in holder loader + parent chain + boot classes)` once per site, then amortized to a single atomic load.

## DD-3: Global ref replacement

### Decision per category

| Category | Decision |
|---|---|
| Static-base holders (`static_base_global_ref`) | **Delete the mechanism.** Derive static base from `Klass::_java_mirror` on demand. No root cell needed. |
| Owner-class mirrors (`owner_class_slot`) | **Delete the mechanism.** Site state is `cached_klass` only. |
| LDC String roots | **Keep Oracle 1 A2 exactly.** One boot-CLD root cell per unique literal. |
| Test-helper / diagnostic long-lived oops | If they survive the strict audit, anchor them with the same boot-CLD root-cell allocator. |
| Any other `NewGlobalRef` call site found during implementation | Either migrate it to a boot-CLD root cell (if it is a true long-lived oop) or delete the cache entirely. Do **not** dlsym `JNIHandles::*`, `ClassLoaderData::allocate_handle`, or internal `OopHandle` allocators. |

The important simplification is: **most current global refs are unnecessary once `Klass*` becomes the canonical cache value.**

### Full skeleton for each category

**A. Static-base holders — delete, derive via mirror**

```c
static jboolean neko_resolve_static_field_site_nosafepoint(uint32_t manifest_method_index,
                                                           const NekoManifestMethod *method,
                                                           NekoManifestFieldSite *site) {
    Klass *holder;
    int32_t offset = -1;

    if (site == NULL || method == NULL || !site->is_static) return JNI_FALSE;
    if (__atomic_load_n(&site->resolved_offset, __ATOMIC_ACQUIRE) >= 0) return JNI_TRUE;

    holder = neko_field_site_owner_klass_nosafepoint(manifest_method_index, method, site);
    if (holder == NULL) return JNI_FALSE;
    if (!neko_resolve_field_offset(holder,
                                   site->field_name,
                                   (uint32_t)strlen(site->field_name),
                                   site->field_desc,
                                   (uint32_t)strlen(site->field_desc),
                                   true,
                                   &offset)) {
        return JNI_FALSE;
    }

    site->field_offset_cookie = offset;
    __atomic_store_n(&site->resolved_offset, offset, __ATOMIC_RELEASE);
    return JNI_TRUE;
}

static inline oop neko_field_site_static_base_nosafepoint(uint32_t manifest_method_index,
                                                          const NekoManifestMethod *method,
                                                          NekoManifestFieldSite *site) {
    Klass *holder = neko_field_site_owner_klass_nosafepoint(manifest_method_index, method, site);
    return holder == NULL ? NULL : neko_klass_static_base_nosafepoint(holder);
}
```

**B. Generic boot-CLD root cells — only for real long-lived oop anchors**

```c
typedef struct NekoBootHandleArena {
    NekoChunkedHandleListChunk *head;
    uint32_t capacity;
    uint32_t next_index;
} NekoBootHandleArena;

static void* neko_boot_handle_cell_at(NekoBootHandleArena *arena, uint32_t index) {
    return arena == NULL ? NULL : neko_nth_string_root_cell(arena->head, index);
}

static void* neko_boot_handle_acquire(NekoBootHandleArena *arena) {
    uint32_t slot;
    if (arena == NULL || arena->next_index >= arena->capacity) return NULL;
    slot = arena->next_index++;
    return neko_boot_handle_cell_at(arena, slot);
}

static inline oop neko_boot_handle_load(void *cell) {
    return (oop)neko_load_oop_from_cell(cell);
}

static inline void neko_boot_handle_store(void *cell, oop obj) {
    neko_store_oop_to_cell(cell, obj);
}
```

Use this allocator for:

- Oracle 1 string roots (already selected)
- any strict-audit survivors that truly need a persistent oop across calls

Do **not** use it for static bases or owner classes; those no longer need oop caches.

### GC safety proof

1. **Boot-CLD root cells**: already proven by Oracle 1 A2. Cells live in `ClassLoaderData::_handles` and are traversed by the GC as strong roots across Serial / Parallel / G1 / ZGC / Shenandoah / Epsilon.
2. **Static-base-on-demand**: no extra root is needed because the `java/lang/Class` mirror is already anchored by `Klass::_java_mirror` / `OopHandle`. The code re-loads the mirror each use and does not keep a raw mirror oop across a safepoint.
3. **`cached_klass`**: metadata pointer only, not a heap oop. GC does not move metadata. Class unload invalidation remains W11a work and is not claimed here.
4. **Rejected alternatives**:
   - `ClassLoaderData::allocate_handle(oop)` dlsym: ABI-unstable C++ call.
   - `JNIHandles::make_global` dlsym: recreates JNI-internal semantics through unsupported ABI.
   - direct internal `OopHandle` allocators: same problem, harder to validate than boot-CLD cells.

## DD-4: Mirror/static-base access

### Confirmation

Yes. This path is safe and should become the canonical mirror/static-base API:

- On **JDK 9+**, `Klass::_java_mirror` is an OopHandle-backed cell; load through the handle’s `_obj` slot.
- On **JDK 8**, `Klass::_java_mirror` is a direct oop field.
- The mirror already lives as long as the class lives. The runtime only needs to re-load it each use and avoid caching the raw mirror oop across a safepoint.

### Helper signatures

```c
static inline oop neko_oop_load_from_cell_nosafepoint(const void *cell);
static inline oop neko_klass_mirror_nosafepoint(Klass *klass);
static inline oop neko_klass_static_base_nosafepoint(Klass *holder);
static inline oop neko_cld_loader_oop_nosafepoint(void *cld);
static inline oop neko_loader_parent_oop_nosafepoint(oop loader);
static inline Klass* neko_find_named_klass_in_cld(void *cld,
                                                  const char *internal_name,
                                                  uint16_t internal_name_len);
```

Suggested implementations:

```c
static inline oop neko_oop_load_from_cell_nosafepoint(const void *cell) {
    return (oop)neko_load_oop_from_cell(cell);
}

static inline oop neko_klass_mirror_nosafepoint(Klass *klass) {
    const void *cell;
    if (klass == NULL || g_neko_vm_layout.off_klass_java_mirror < 0) return NULL;
    if (g_neko_vm_layout.java_spec_version >= 9) {
        void *handle = *(void**)((uint8_t*)klass + g_neko_vm_layout.off_klass_java_mirror);
        if (handle == NULL || g_neko_vm_layout.off_oophandle_obj < 0) return NULL;
        cell = (uint8_t*)handle + g_neko_vm_layout.off_oophandle_obj;
        return neko_oop_load_from_cell_nosafepoint(cell);
    }
    return *(oop*)((uint8_t*)klass + g_neko_vm_layout.off_klass_java_mirror);
}

static inline oop neko_klass_static_base_nosafepoint(Klass *holder) {
    return neko_klass_mirror_nosafepoint(holder);
}
```

## DD-5: Exception handling

### Decision

Use raw writes to `JavaThread::_pending_exception` as the canonical strict Option A throw mechanism for **existing exception oop values**. Do **not** use `ThreadShadow::set_pending_exception` as the primary design; it adds an unnecessary ABI dependency when the VMStruct offsets already exist.

However:

- **`ATHROW` with a non-null exception object already on stack** is clean.
- **Exceptions returned from future JavaCalls paths** are clean if the called VM helper already set the thread’s pending exception.
- **Synthetic exceptions created entirely in native code** (`NullPointerException`, `ArrayIndexOutOfBoundsException`, `ArithmeticException`, `ClassCastException`, `NoClassDefFoundError`, `LinkageError`, `NoSuchFieldError`, `NoSuchMethodError`, etc.) do **not** have a clean cross-JDK strict-no-JNI implementation using only VMStructs + raw memory.

Therefore the recommended master-plan change is:

- land strict raw-pending-exception support for existing oops now;
- keep synthetic-throw-producing admissions closed until the user grants one of:
  1. a **narrow constraint exception** for exception construction only, or
  2. an explicit **ABI-risk branch** that dlsyms HotSpot internal `Exceptions::*` helpers.

I do **not** recommend inventing a “manual raw Throwable allocation” path. It is too risky for constructor semantics, message semantics, and stacktrace fidelity across JDK 8 / 11 / 17 / 21.

### Full skeleton

```c
static inline void neko_set_pending_exception_raw(JavaThread *thread, oop exc) {
    if (thread == NULL || exc == NULL || g_neko_vm_layout.off_thread_pending_exception < 0) return;

    __atomic_store_n((oop*)((uint8_t*)thread + g_neko_vm_layout.off_thread_pending_exception),
                     exc,
                     __ATOMIC_RELEASE);

    if (g_neko_vm_layout.off_thread_exception_oop >= 0) {
        __atomic_store_n((oop*)((uint8_t*)thread + g_neko_vm_layout.off_thread_exception_oop),
                         exc,
                         __ATOMIC_RELEASE);
    }

    if (g_neko_vm_layout.off_thread_exception_pc >= 0) {
        __atomic_store_n((void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_exception_pc),
                         NULL,
                         __ATOMIC_RELEASE);
    }
}

static inline oop neko_pending_exception_raw(JavaThread *thread) {
    if (thread == NULL || g_neko_vm_layout.off_thread_pending_exception < 0) return NULL;
    return __atomic_load_n((oop*)((uint8_t*)thread + g_neko_vm_layout.off_thread_pending_exception),
                           __ATOMIC_ACQUIRE);
}

static inline void neko_clear_pending_exception_raw(JavaThread *thread) {
    if (thread == NULL || g_neko_vm_layout.off_thread_pending_exception < 0) return;
    __atomic_store_n((oop*)((uint8_t*)thread + g_neko_vm_layout.off_thread_pending_exception),
                     NULL,
                     __ATOMIC_RELEASE);
    if (g_neko_vm_layout.off_thread_exception_oop >= 0) {
        __atomic_store_n((oop*)((uint8_t*)thread + g_neko_vm_layout.off_thread_exception_oop),
                         NULL,
                         __ATOMIC_RELEASE);
    }
    if (g_neko_vm_layout.off_thread_exception_pc >= 0) {
        __atomic_store_n((void**)((uint8_t*)thread + g_neko_vm_layout.off_thread_exception_pc),
                         NULL,
                         __ATOMIC_RELEASE);
    }
}

static inline jboolean neko_raise_athrow_nosafepoint(JavaThread *thread, oop exception_oop) {
    if (thread == NULL) return JNI_FALSE;
    if (exception_oop == NULL) {
        /* null-ATHROW still requires a synthetic NPE; that is intentionally not faked here */
        return JNI_FALSE;
    }
    neko_set_pending_exception_raw(thread, exception_oop);
    return JNI_TRUE;
}
```

Recommended policy wiring:

- `ATHROW`: switch emitter to `neko_raise_athrow_nosafepoint(thread, (oop)POP_O())`.
- `__neko_exception_exit`: branch on `neko_pending_exception_raw(thread) != NULL`.
- W5 can open `ATHROW` only after null-ATHROW behavior is explicitly decided.
- W6 / W9 native-generated throws remain blocked pending constraint resolution.

## DD-6: Remaining JNI elimination

The strict audit of the current source tree shows JNI still lives in five generator areas:

- `JniOnLoadEmitter.java`
- `Wave1RuntimeEmitter.java`
- `Wave2FieldLdcEmitter.java`
- `Wave3InvokeStaticEmitter.java`
- `OpcodeTranslator.java`

The correct response is not to “replace one JNI call with another helper.” The correct response is to delete the JNI-shaped subsystem and replace it with VMStruct-native state.

### Table: JNI call -> replacement

| JNI call / family | Current use found in tree | Strict replacement |
|---|---|---|
| `FindClass` | `JniOnLoadEmitter` debug-property fallback; `Wave1RuntimeEmitter` helper inventory; `Wave2` reflection / `Unsafe`; `Wave3` bind support; opcode intrinsics | Delete bootstrap/property/reflection uses. For actual class resolution use DD-1 / DD-2 CLD + `Klass::_name` scans. |
| `CallStaticObjectMethod(Class.forName)` | `Wave1RuntimeEmitter.neko_load_class_noinit*`; `Wave2` LDC Class slow path | Delete entirely. Replace with `Klass*` lookup in current holder CLD / parent-chain CLDs / boot CLD. |
| `GetMethodID` / `GetStaticMethodID` | bootstrap property lookup; `Wave2` reflection / `Unsafe`; `Wave3` bind-time method slots; MethodHandle / indy helpers | Replace with pure `Method*` scans on `InstanceKlass::_methods` + `ConstMethod` name/desc matching. For bootstrap/property helpers, delete path instead of replacing it. |
| `GetFieldID` / `GetStaticFieldID` | `neko_mark_loader_loaded`; `Wave2` `Unsafe.theUnsafe`; wrapper `TYPE` lookups; bind-time field slots | Replace with `neko_resolve_field_offset(Klass*, name, desc, is_static)` plus `neko_klass_static_base_nosafepoint(holder)`. |
| `GetObjectClass` | `OpcodeTranslator` intrinsic for `Object.getClass()` | Replace with `neko_oop_klass(obj)` + `neko_klass_mirror_nosafepoint(klass)`. Keep this intrinsic closed until compact-header decode is available on JDK 21. |
| `IsSameObject` | loader equality in current `neko_ldc_site_matches_loaded_class(...)` | Delete that helper. Compare raw loader oop pointer identity instead. Boot loader remains `NULL`. |
| `IsAssignableFrom` / `IsInstanceOf` | current CHECKCAST / INSTANCEOF path uses `IsInstanceOf`; no direct `IsAssignableFrom` hit was found in the inspected generator sources | Introduce `neko_klass_is_subtype_of(lhs, rhs)` using VMStruct hierarchy offsets (`_super`, secondary supers, etc.) when Wave 4c reopens these opcodes. Until then keep the admissions closed. |
| `NewGlobalRef` / `DeleteGlobalRef` | class slots, string slots, owner-class slots, static-base holders, `Unsafe` singleton, indy table, bind support | Remove by category per DD-3. Only true long-lived oop anchors may survive, and those must use boot-CLD root cells. |
| `Throw`, `ThrowNew`, `ExceptionOccurred`, `ExceptionClear`, `ExceptionCheck` | `ATHROW`, bind-time failure helpers, Wave 2 “capture pending”, many wrapper helpers | Replace with raw pending-exception reads / writes for existing oops. For synthetic exceptions, stop and escalate; do not simulate constructors with raw heap writes. |
| `GetEnv` outside `JNI_OnLoad` | `Wave1RuntimeEmitter.neko_current_env()` and all later helper chains that call through it | Delete. Strict Option A allows exactly one JNI call: `(*vm)->GetEnv(..., JNI_VERSION_1_6)` inside `JNI_OnLoad`. No later helper may call through the JNI function table. |
| `System.getProperty` via JNI | `JniOnLoadEmitter` and `BootstrapEmitter.neko_detect_java_spec_version()` | Delete. Use `getenv("NEKO_NATIVE_DEBUG")` for debug and require `JAVA_SPEC_VERSION` (or equivalent VM constant) from VMStruct constants for JDK version. If absent, fail strict bootstrap. |
| `Class.getDeclaredField`, `Unsafe.objectFieldOffset`, `Unsafe.staticFieldBase`, `Unsafe.arrayBaseOffset`, `Unsafe.arrayIndexScale` | `Wave2FieldLdcEmitter` reflection/Unsafe fallback layer | Delete entire layer. Field offsets already have `neko_resolve_field_offset(...)`; static base comes from `Klass::_java_mirror`; array header/base/scale comes from `Klass::_layout_helper`. |
| JVMTI `GetLoadedClasses`, `GetClassSignature`, `GetClassMethods`, `GetMethodName` | current bootstrap class discovery / method patch discovery / well-known capture | Replace with the same CLDG / CLD / method-array walkers that strict DD-1 and DD-2 already need. This is now the canonical bootstrap discovery path. |

Additional strict-cleanup notes:

- `neko_current_env()` should disappear from the strict build.
- The huge JNI wrapper inventory in `Wave1RuntimeEmitter.renderRuntimeSupport()` should move behind a non-strict feature flag or be deleted wave-by-wave.
- `Wave3` class/string bind caches (`g_cls_*`, `g_str_*`) are the wrong abstraction under strict Option A. The replacements are `Klass*` caches, `Method*` caches, field-offset caches, and Oracle 1 string-root entries.
- `neko_mark_loader_loaded()` must stop using `FindClass` / `GetStaticFieldID`. Capture `dev/nekoobfuscator/runtime/NekoNativeLoader` by CLDG scan, resolve the `loaded:Z` field offset with the existing field walker, and write the static boolean via the class mirror.

## Cross-cutting: Symbol resolution infrastructure

Keep the existing `libjvm.so` / `jvm.dll` handle resolution strategy and formalize it into two tiers:

### Tier 1 — required data symbols (primary path)

Resolve only the current VMStruct / VMType / VMConstant roots already in `BootstrapEmitter`:

- `gHotSpotVMStructs`
- `gHotSpotVMStructEntry*`
- `gHotSpotVMTypes`
- `gHotSpotVMTypeEntry*`
- `gHotSpotVMIntConstants`
- `gHotSpotVMLongConstants`
- optional VM flags such as `UseCompactObjectHeaders`

This tier is enough for the primary strict architecture.

### Tier 2 — optional ABI-risk symbols (disabled by default)

Primary design: **empty**.

If the maintainer later approves an ABI exception for synthetic exception construction, add a separate optional resolver block with:

- a per-JDK symbol map,
- explicit startup validation,
- explicit debug logging,
- and a hard fail if the optional branch was requested but the symbol set is incomplete.

Do **not** silently mix optional helper resolution into the primary path.

### Bootstrap discipline

1. `JNI_OnLoad` calls `GetEnv` exactly once.
2. `neko_resolve_vm_symbols()` resolves Tier 1.
3. `neko_parse_vm_layout_strict()` parses VMStructs and derives all non-JNI fallbacks.
4. `neko_validate_vm_layout()` rejects any strict build missing required offsets (`off_klass_next_link`, `off_cld_klasses`, `off_cld_handles`, `off_klass_java_mirror`, etc.).
5. Only after layout validation succeeds may the runtime walk CLDG / CLD / klass metadata.

## Self-check procedures

Suggested strict startup self-checks (all `NEKO_DEBUG_ENABLED`-gated):

1. **Strict VM layout gate**
   - trace: `strict_vm_layout_ok=1 next_link=%td cld_klasses=%td cld_handles=%td klass_mirror=%td`
   - fail if any required strict offset is missing.

2. **Boot well-known scan**
   - trace: `boot_well_known_scan_ok=%d string=%p byte=%p char=%p`
   - must be `1` before `neko_resolve_string_intern_layout()`.

3. **Oracle 1 root-chain gate (unchanged)**
   - success trace: `boot_cld_root_chain_ok=1`
   - failure trace: `boot_cld_root_chain_ok=0 fallback=candidate_e`

4. **Boot owner presolve**
   - trace: `boot_owner_presolve=%u/%u`
   - count how many manifest owner names were resolved from the boot CLD at bootstrap.

5. **Loader-chain mode gate**
   - trace: `loader_parent_mode=current+parent+boot off=%td`
   - or `loader_parent_mode=current+boot off=-1`
   - makes parent-chain support explicit instead of implicit.

6. **Static-base path gate**
   - trace: `static_base_via_mirror_ok=1`
   - emit once after the first successful static field site resolution that uses `Klass::_java_mirror` instead of any global-ref slot.

7. **Existing-oop throw gate**
   - trace: `athrow_raw_pending_ok=1`
   - emitted by a dedicated strict test when `neko_raise_athrow_nosafepoint()` writes a non-null pending exception and the method exits exceptionally.

## Fallback strategies

1. **String-class capture failure is not Candidate E territory**
   - If DD-1 cannot capture `java/lang/String` / `[B` / `[C`, strict STRING support has no valid backend at all.
   - Action: fail strict bootstrap loudly and request a constraint exception or a JDK-compat fix.
   - Candidate E remains valid **only after** DD-1 succeeds and Oracle 1 root-chain publication fails.

2. **Root-chain publication failure**
   - If DD-1 succeeded but Oracle 1 handle-chain publication fails, use Oracle 1 Candidate E (`create String per call, no persistent root`) exactly as already approved.

3. **Missing `ClassLoader.parent` offset**
   - Run owner resolution in `current_cld + boot_cld` mode only.
   - Log the degraded mode explicitly.
   - Do not invent a JNI or internal-helper fallback just to recover parent-chain semantics.

4. **Synthetic exception required by a wave**
   - Stop and escalate.
   - Preferred request order:
     1. a narrow exception-construction allowance, or
     2. explicit approval for a per-JDK internal-helper ABI branch.
   - Do not silently fake `Throwable` construction.

5. **Compact object headers block raw `oop -> klass` decode**
   - Keep `Object.getClass`, `CHECKCAST`, and `INSTANCEOF` strict-admission closed until the compact-header decode path exists.
   - This does not block DD-1 through DD-4 because those operate from `Klass*`, not from arbitrary heap oop headers.

## Effort estimate (days / weeks) per decision

| Decision | Estimated work |
|---|---|
| DD-1 well-known class capture via boot CLD `_klasses` walk | **Short (2–4 days)** |
| DD-2 owner resolution via holder CLD + loader chain + `Klass*` cache | **Medium (4–6 days)** |
| DD-3 global-ref removal / root-cell narrowing | **Short (2–3 days)** |
| DD-4 mirror/static-base helper consolidation | **Quick (0.5–1 day)** |
| DD-5 raw pending-exception base path for existing oops | **Quick (1 day)** |
| DD-5 synthetic exception story under strict Option A | **Blocked pending design escalation**; if forced through internal helpers, **Large (1–2 weeks)** due JDK 8 / 11 / 17 / 21 ABI matrix |
| DD-6 remaining JNI subsystem deletion / rewrite | **Large (1–2 weeks)** |

Practical critical path for revised master plan v6:

- **Implementable now without constraint changes**: DD-1, DD-2, DD-3, DD-4, DD-5(existing-oop only)
- **Explicit decision gate required before opening later exception-producing waves**: DD-5(synthetic throws)
- **Main refactor bulk**: DD-6

That is consistent with the user’s accepted 2–3 week extra budget: the strict-no-JNI architecture itself is viable, but synthetic exception construction is the one place where the clean path ends and a conscious policy decision is required.
