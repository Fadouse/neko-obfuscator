# Oracle 6 — SIGSEGV Immediately After Well-Known Klass Capture

## Root Cause Hypothesis

### Most likely (high confidence)
The new crash is **not** in boot-CLD root-chain publish. It happens one step earlier in `neko_mark_loader_loaded()`, which is the **first** call after `neko_capture_wellknown_klasses()` in `JniOnLoadEmitter.java:29-33`.

The concrete bug is in the **mirror resolver** used by `neko_mark_loader_loaded()`:

- `BootstrapEmitter.java:855-864`:
  - `mirror_oop = neko_resolve_mirror_oop_from_klass(&g_neko_vm_layout, loader_klass);`
  - then writes `loaded = 1` at `mirror_oop + off_loader_loaded_field`
- `Wave4aRuntimeApiEmitter.java:229-250` currently resolves `Klass::_java_mirror` incorrectly on JDK 9+:
  - it returns the **contents** of `OopHandle::_obj`, not the **address of the cell**
  - then `neko_resolve_mirror_oop_from_klass()` dereferences that result again as `*(oop*)locator`

That is a classic **double-dereference of the mirror** and also bypasses the existing compressed-oop-safe loader (`neko_load_oop_from_cell`). The result is that `mirror_oop` becomes garbage before the store to `loaded`.

### Second most likely (same bug family, not the immediate crash)
`BootstrapEmitter.java:1323-1352` (`neko_derive_class_klass_offset_from_mirror`) has the same conceptual bug: it reads the mirror oop with a raw `*(void**)mirror_handle` path instead of going through the oop-cell loader. That may not be the immediate crash on this run, but it is unsafe on compressed-oops builds and should be fixed in the same patch set.

### Lower-probability alternatives
1. `off_oophandle_obj` parsed incorrectly from VMStructs.
2. `off_klass_java_mirror` parsed incorrectly.
3. Later CLD `_handles` publish bug.

These are less likely because the crash occurs **before** `[nk] ol mark_loader_loaded ok ...`, and `neko_mark_loader_loaded()` is the only operation in that gap.

## Crash Location Analysis

### Source-level location
The active `JNI_OnLoad` order is:

- `JniOnLoadEmitter.java:23-30` — resolve VM, parse layout, capture well-known klasses
- `JniOnLoadEmitter.java:32` — `neko_mark_loader_loaded();`
- `JniOnLoadEmitter.java:33` — trace `[nk] ol mark_loader_loaded ok ...`

Observed runtime output ends at:

- `[nk] cap ok str=... arr_b=... arr_c=...`

and never prints:

- `[nk] ol mark_loader_loaded ok ...`

So the crash window is exactly:

- `JniOnLoadEmitter.java:32`
- into `BootstrapEmitter.java:855-864`

### Instruction-level correlation
From `hs_err_pid1401364.log`:

- `RAX = 0x74`
- faulting instruction at `RIP=...35b9` is `c6 04 01 01`
- effective address is `RCX + RAX = 0x14ae5a501 + 0x74 = 0x14ae5a575`

That matches the source store in `BootstrapEmitter.java:864`:

```c
*(volatile uint8_t*)((uint8_t*)mirror_oop + g_neko_vm_layout.off_loader_loaded_field) = 1u;
```

`0x74 == 116`, and the crash log shows the `loaded` static field for `dev/nekoobfuscator/runtime/NekoNativeLoader` at exactly `@116`.

So `JNI_OnLoad+0x3237` is best mapped to the **inlined** field store from `neko_mark_loader_loaded()`.

## Why `si_addr=0x14ae5a575`

This address is best explained as **object-header-as-pointer**, not a plain NULL dereference and not a simple high-32 truncation.

Evidence:

- The correct mirror oop is present in the crash log as:
  - `R9 = 0x000000054b029858`
  - `java mirror: a 'java/lang/Class'{0x000000054b029858} = 'dev/nekoobfuscator/runtime/NekoNativeLoader'`
- The bad base used for the store is:
  - `RCX = 0x000000014ae5a501`
- That value ends in `...501`, which looks like a **HotSpot mark word / tagged header value**, not a decoded heap oop:
  - heap object pointers are aligned
  - mark words commonly carry low tag bits

What likely happened:

1. `Klass::_java_mirror` (JDK 9+) gave an `OopHandle*`
2. current code dereferenced `OopHandle::_obj` too early and returned the **oop value** as `locator`
3. current code then did `*(oop*)locator`, i.e. dereferenced the **mirror object itself**
4. that read the first machine word of the `java.lang.Class` object (its header / mark word)
5. that header word became `mirror_oop`
6. `neko_mark_loader_loaded()` then wrote at `header_word + 0x74`

So `si_addr=0x14ae5a575` is most plausibly:

- bogus `mirror_oop` = mark word `0x14ae5a501`
- plus static field offset `0x74`

This fits the register state much better than a narrow-oop truncation theory.

## Fix D / E / etc.

### Fix D — correct mirror resolution to use oop-cell semantics
**File:** `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/Wave4aRuntimeApiEmitter.java`

**Current buggy range:** around `229-250`

**Problem:** `neko_resolve_mirror_locator_from_klass()` returns the **contents** of the mirror cell on JDK 9+, then `neko_resolve_mirror_oop_from_klass()` dereferences again with raw pointer semantics.

**Minimal patch:** change the helper to return the **address of the oop cell**, then load/decode the oop with `neko_load_oop_from_cell()`.

### Exact replacement
Replace the current block:

```c
static inline void* neko_resolve_mirror_locator_from_klass(const NekoVmLayout *layout, Klass *klass) {
    void *oop_handle_addr;
    void *mirror_handle;
    if (layout == NULL || klass == NULL || layout->off_klass_java_mirror < 0) return NULL;
    if (layout->java_spec_version >= 9) {
        if (layout->off_oophandle_obj < 0) return NULL;
        oop_handle_addr = (uint8_t*)klass + layout->off_klass_java_mirror;
        mirror_handle = *(void**)oop_handle_addr;
        if (mirror_handle == NULL) return NULL;
        return *(void***)((uint8_t*)mirror_handle + layout->off_oophandle_obj);
    }
    return (void*)((uint8_t*)klass + layout->off_klass_java_mirror);
}

static inline oop neko_resolve_mirror_oop_from_klass(const NekoVmLayout *layout, Klass *klass) {
    void *locator = neko_resolve_mirror_locator_from_klass(layout, klass);
    if (locator == NULL) return NULL;
    if (layout->java_spec_version >= 9) {
        return *(oop*)locator;
    }
    return *(oop*)locator;
}
```

with:

```c
static inline const void* neko_resolve_mirror_cell_from_klass(const NekoVmLayout *layout, Klass *klass) {
    void *oop_handle_addr;
    void *mirror_handle;
    if (layout == NULL || klass == NULL || layout->off_klass_java_mirror < 0) return NULL;
    if (layout->java_spec_version >= 9) {
        if (layout->off_oophandle_obj < 0) return NULL;
        oop_handle_addr = (uint8_t*)klass + layout->off_klass_java_mirror;
        mirror_handle = *(void**)oop_handle_addr;
        if (mirror_handle == NULL) return NULL;
        return (const void*)((const uint8_t*)mirror_handle + layout->off_oophandle_obj);
    }
    return (const void*)((const uint8_t*)klass + layout->off_klass_java_mirror);
}

static inline oop neko_resolve_mirror_oop_from_klass(const NekoVmLayout *layout, Klass *klass) {
    const void *cell = neko_resolve_mirror_cell_from_klass(layout, klass);
    if (cell == NULL) return NULL;
    return (oop)neko_load_oop_from_cell(cell);
}
```

### Why this is the right minimal fix
- It preserves strict-no-JNI.
- It reuses the already-correct abstraction for compressed/uncompressed oop cells.
- It fixes both JDK 9+ `OopHandle::_obj` and JDK 8 direct-oop-field access with one path.

### Fix E — remove the same raw mirror dereference from offset derivation
**File:** `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/BootstrapEmitter.java`

**Current risky range:** `1323-1352`

`neko_derive_class_klass_offset_from_mirror()` currently does:

```c
mirror_handle = *mirror_handle_slot;
...
mirror_oop = *(void**)mirror_handle;
```

That is the same unsafe assumption. Replace the manual load with the shared helper:

```c
mirror_oop = neko_resolve_mirror_oop_from_klass(&g_neko_vm_layout, (Klass*)known_klass);
if (mirror_oop == NULL) return;
```

and keep the existing scan loop unchanged.

This is a secondary fix, but I recommend landing it together with Fix D because it is the same bug class and affects fallback derivation on runtimes where `off_class_klass` is not directly exposed.

### Fix F — optional defensive trace directly at the write site
**File:** `BootstrapEmitter.java:855-864`

If you want one extra guardrail for the next debug run, add a debug-only trace immediately before the store:

```c
neko_native_debug_log("loader_loaded_write klass=%p mirror=%p off=%td", loader_klass, mirror_oop, g_neko_vm_layout.off_loader_loaded_field);
```

This is observability only, not the functional fix.

## Additional observability

If Fix D/E does not fully resolve the run, add these traces in this order:

1. **Before the `loaded` write** in `neko_mark_loader_loaded()`:
   - `loader_klass`
   - mirror cell address
   - decoded `mirror_oop`
   - `off_loader_loaded_field`

2. **Inside mirror resolver** (`Wave4aRuntimeApiEmitter`):
   - `klass`
   - raw `mirror_handle`
   - mirror cell address
   - raw 32-bit cell contents when compressed oops are enabled
   - decoded oop returned

3. **Only after mark-loader succeeds**, bracket `neko_string_intern_prewarm_and_publish()` with stage traces:
   - enter
   - after `neko_alloc_string_root_chunks`
   - after `neko_self_check_string_root_chain`
   - after `neko_publish_string_root_chain`
   - after `neko_assign_string_root_cells`

That will cleanly separate a mirror bug from a later CLD `_handles` chain bug.

## Verification plan

A successful fix should produce this trace progression on the same debug jar:

1. Existing lines still appear:
   - `[nk] lj 24/24`
   - `[nk] vm j=21`
   - `[nk] cap enter`
   - `[nk] cap ok str=... arr_b=... arr_c=...`

2. New progress past the old crash point:
   - `[nk] ol mark_loader_loaded ok str=...`

3. Then normal post-mark bootstrap:
   - `[nk] ol resolve_string_intern_layout ok hash_off=...`
   - either `boot_cld_root_chain_ok=1` or a specific fallback line
   - `[nk] ol prewarm ok backend=...`

4. Finally JNI_OnLoad completes and later wave traces continue:
   - `[nk] dm ...`
   - `[nk] dp ...`
   - wave 2 / wave 3 readiness logs

If the process now crashes **after** `[nk] ol mark_loader_loaded ok ...`, then the next suspect is the CLD `_handles` chain publish path the user highlighted.

## Interaction with Oracle 5 fixes

### Fix A (Wave2 lazy fill + CAS publish)
Still correct. The current crash occurs **before** `neko_resolve_string_intern_layout()` and before any Wave2 LDC resolver usage, so Fix A is not implicated.

### Fix B (branch-specific prewarm tracing)
Still correct and still useful. The reason you did not see `boot_cld_root_chain_ok=...` is that execution never reached `neko_string_intern_prewarm_and_publish()`.

### Fix C (capture tracing)
Also still correct. It was what proved the Oracle 5 artifact-mismatch diagnosis was fixed and that the run now advances past capture.

### Commit `74914cf`
Confirmed good. The new evidence (`cap ok` with three non-null Klass*) shows Oracle 5’s primary diagnosis/fix is active and working.

## Risk register

1. **Bootstrap fallback derivation still unsafe if only Fix D lands.**
   - If `neko_derive_class_klass_offset_from_mirror()` is exercised on another JDK/config, it can reproduce the same mirror-load bug class. Land Fix E with D.

2. **A later CLD `_handles` bug may appear once this crash is removed.**
   - That would be a real second-stage bug, not a contradiction of this diagnosis. Use the recommended stage traces to separate it cleanly.

3. **Cross-version assumptions around `Klass::_java_mirror` must stay cell-based, not raw-pointer-based.**
   - Do not reintroduce raw `*(void**)` mirror loads anywhere. The safe abstraction is: resolve cell address, then `neko_load_oop_from_cell()`.
