# Oracle 7 — Post-Fix-D Regression at `JNI_OnLoad+0x29bf`

## Confirm/refute hypothesis

**Confirmed, with one refinement:** the regression is not that `neko_load_oop_from_cell()` suddenly became intrinsically wrong everywhere; it is that Oracle 6 Fix D started feeding it the **wrong cell kind** on the JDK 9+ mirror path.

### Evidence from the post-Fix-D crash

Crash site (`hs_err_pid1628554.log`, `JNI_OnLoad+0x29bf`) is now best explained as:

1. `neko_derive_class_klass_offset_from_mirror()` calls `neko_resolve_mirror_oop_from_klass()`.
2. On JDK 21, `Klass::_java_mirror` is an `OopHandle`, so the field in `Klass` is an **8-byte pointer to an oop storage slot**.
3. Current `Wave4aRuntimeApiEmitter.java:229-247` resolves that storage-slot address, but then passes it through `neko_load_oop_from_cell()`.
4. `BootstrapEmitter.java:1696-1703` interprets any cell as an **inline heap oop cell** when compressed oops are enabled, so it does:
   - `u4 narrow = *(u4*)cell`
   - `decoded = narrow_oop_base + ((uint64_t)narrow << narrow_oop_shift)`
5. But the JDK 9+ mirror storage slot is **not** a narrow-oop cell. It is a wide `oop` slot.

The register/disassembly correlation is unusually strong:

- HotSpot says the real `java/lang/String` mirror is `0x00000007ff880460`.
- At crash time, `RSI = 0x00000000ff880460`, i.e. the **low 32 bits** of that wide oop.
- The faulting block shows a 32-bit load followed by decode math before the scan dereference:
  - `0x...6a22: 8b 31` → load 32-bit value from cell
  - `0x...6a2a: 48 d3 e2` → shift it
  - `0x...6a52: 48 8b 34 0a` → dereference `[rdx + 8]`
- `0x00ff880460 << 3 == 0x00000007fc402300`, which matches `RDX` exactly.
- Crash address is `si_addr = RDX + 8 = 0x00000007fc402308`.

So the current code is reading the low 32 bits of a **wide** oop slot, decoding them as if they were a compressed inline oop, and then scanning that garbage heap address as a `java.lang.Class` object. That is why the signature changed from the earlier mark-word-like crash to this new `SEGV_ACCERR` in protected heap space.

### Refined conclusion

- **Yes:** the current JDK 9+ mirror resolution is wrong.
- **Yes:** Fix D regressed the JDK 9+ path by routing `OopHandle` storage through the inline-cell decoder.
- **No:** do **not** “fix” this by changing `neko_load_oop_from_cell()` globally. That helper is still the right abstraction for **inline oop fields** (JDK 8 mirror field, other true compressed-oop cells, existing root-cell consumers that intentionally use that encoding contract).

Oracle 6 Fix E in `BootstrapEmitter` was still directionally correct: it removed the old raw double-deref at the call site. The regression is now in the shared helper contract introduced by Fix D.

---

## Correct decoding rule

### JDK 8 (`Klass::_java_mirror` is direct `oop` field)

`Klass::_java_mirror` is a direct oop field in metadata.

Use:

```c
off = layout->off_klass_java_mirror;
cell = (const uint8_t*)klass + off;
mirror = (oop)neko_load_oop_from_cell(cell);
```

That is correct because the field itself is an inline oop cell:

- compressed-oops build → field is narrow, decode via `narrow_oop_base` / `narrow_oop_shift`
- non-compressed-oops build → field is wide, plain 64-bit load

### JDK 9+ (`Klass::_java_mirror` is `OopHandle`)

`Klass::_java_mirror` is **not** an oop cell. It is an `OopHandle` whose `_obj` member is an `oop*` pointing to a storage slot.

Correct rule:

```c
const void *oop_handle_addr = (const uint8_t*)klass + layout->off_klass_java_mirror;
oop *storage_slot = *(oop**)((const uint8_t*)oop_handle_addr + layout->off_oophandle_obj);
oop mirror = storage_slot == NULL ? NULL : __atomic_load_n((void* const*)storage_slot, __ATOMIC_ACQUIRE);
```

Key distinction:

- `klass + off_klass_java_mirror` → address of the embedded `OopHandle`
- `*(oop**)(...)` → wide pointer to slot
- `*storage_slot` → wide `oop`

This second load is **always wide** for HotSpot `OopHandle` storage, independent of `UseCompressedOops`, GC, or compressed class pointers.

### GC/platform note

This rule is HotSpot-structural, not GC-specific:

- G1/ZGC/Shenandoah may change barrier/metadata behavior,
- but `OopHandle::resolve() == *_obj` remains a wide `oop` load.

So no GC-specific branch is needed here.

---

## Fix G (replaces/refines Fix D)

**Target:** `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/Wave4aRuntimeApiEmitter.java` around current `229-247`.

### Prescribed change

Split “inline oop cell load” from “OopHandle slot load”. Do **not** use `neko_load_oop_from_cell()` on the JDK 9+ mirror path.

### Recommended replacement

```c
static inline const void* neko_resolve_mirror_storage_slot_from_klass(const NekoVmLayout *layout, Klass *klass) {
    const uint8_t *oop_handle_addr;
    void *storage_slot;
    if (layout == NULL || klass == NULL || layout->off_klass_java_mirror < 0) return NULL;
    if (layout->java_spec_version < 9) return NULL;
    if (layout->off_oophandle_obj < 0) return NULL;

    oop_handle_addr = (const uint8_t*)klass + layout->off_klass_java_mirror;
    storage_slot = __atomic_load_n((void* const*)((const uint8_t*)oop_handle_addr + layout->off_oophandle_obj), __ATOMIC_ACQUIRE);
    return storage_slot;
}

static inline oop neko_resolve_mirror_oop_from_klass(const NekoVmLayout *layout, Klass *klass) {
    const void *cell;
    const void *storage_slot;
    if (layout == NULL || klass == NULL || layout->off_klass_java_mirror < 0) return NULL;

    if (layout->java_spec_version >= 9) {
        storage_slot = neko_resolve_mirror_storage_slot_from_klass(layout, klass);
        if (storage_slot == NULL) return NULL;
        return __atomic_load_n((void* const*)storage_slot, __ATOMIC_ACQUIRE);
    }

    cell = (const uint8_t*)klass + layout->off_klass_java_mirror;
    return (oop)neko_load_oop_from_cell(cell);
}
```

### Why this exact refinement

It preserves the good part of Fix D — a single resolver entry point — while correcting the representation split:

- **JDK 8 direct field** → `neko_load_oop_from_cell()`
- **JDK 9+ OopHandle slot** → wide slot load only

That is the minimal fix that matches the crash evidence.

### What not to do

Do **not** replace `neko_load_oop_from_cell()` with unconditional wide loads. That would quietly break true inline compressed-oop fields.

Do **not** revert Oracle 6 wholesale. Keep the call-site cleanup, change only the JDK 9+ mirror load semantics.

---

## Fix H (BootstrapEmitter adjustments)

### Behavioral adjustment

No new bootstrap-side behavioral rewrite is required beyond consuming the corrected `neko_resolve_mirror_oop_from_klass()`.

`BootstrapEmitter.java:1331` is already calling the shared helper after Fix E:

```c
mirror_oop = neko_resolve_mirror_oop_from_klass(&g_neko_vm_layout, (Klass*)known_klass);
```

That should remain.

### Required cleanup

Two small bootstrap-side adjustments are still advisable:

1. **Update the stale comment** at `BootstrapEmitter.java:1323-1326`.
   It still describes the old “double-dereference” model and is now misleading.

   Replace the comment with something like:

   ```c
   /* W1: Derive off_class_klass by resolving the java.lang.Class mirror for a known Klass,
    * then scanning the mirror object for the hidden Klass* back-pointer. On JDK 8 the mirror
    * is read from a direct oop field; on JDK 9+ it is resolved through Klass::_java_mirror
    * (OopHandle -> oop storage slot -> wide oop). */
   ```

2. **Do not change `neko_load_oop_from_cell()` / `neko_store_oop_to_cell()` in this fix.**
   The regression came from applying that abstraction to the wrong storage representation.

### Bottom line for Fix H

- Keep Fix E’s call-site cleanup.
- Update the comment.
- Leave the generic inline-cell loader alone.

---

## Observability enhancement

Use `neko_native_debug_log(...)`, not `NEKO_TRACE(...)`, for the next diagnostic points. `neko_native_debug_log` newline-flushes; `NEKO_TRACE` is just `fprintf(stderr, ...)` and can disappear when the process dies mid-line.

### Add these logs

#### 1) Inside the mirror resolver

For JDK 9+ path:

```c
neko_native_debug_log(
    "mirror_resolve kind=oophandle klass=%p handle_addr=%p storage_slot=%p mirror=%p",
    klass,
    (const uint8_t*)klass + layout->off_klass_java_mirror,
    storage_slot,
    mirror
);
```

For JDK 8 path:

```c
neko_native_debug_log(
    "mirror_resolve kind=inline klass=%p cell=%p mirror=%p",
    klass,
    cell,
    mirror
);
```

If you want one extra discriminator on compressed-oops builds, also log the raw low 32 bits before decode on the inline path only.

#### 2) Before the class-mirror scan

In `neko_derive_class_klass_offset_from_mirror()`:

```c
neko_native_debug_log("class_klass_scan_begin known_klass=%p mirror=%p", known_klass, mirror_oop);
```

#### 3) Before the loader-loaded write

In `neko_mark_loader_loaded()`:

```c
neko_native_debug_log(
    "loader_loaded_write klass=%p mirror=%p field_off=%td",
    loader_klass,
    mirror_oop,
    g_neko_vm_layout.off_loader_loaded_field
);
```

These three logs are enough to separate:

- mirror resolution failure,
- mirror scan failure,
- loader-loaded write failure.

---

## Verification plan

### Primary proof on the same JDK 21 / G1 debug jar

You want to see this progression without a crash:

1. Existing bootstrap traces still appear through capture.
2. New flushed mirror log shows a **wide mirror oop** for JDK 9+.
3. `class_klass_scan_begin ... mirror=0x00000007...` shows a real heap oop, not `0x00000007fc...` garbage.
4. Either:
   - `class_klass_offset_scan=%td` appears, or
   - `off_class_klass` was already present and the scan is skipped.
5. `loader_loaded_write klass=... mirror=... field_off=...` appears.
6. `[nk] ol mark_loader_loaded ok ...` appears.
7. `[nk] ol resolve_string_intern_layout ok ...` appears.
8. Then normal prewarm/root-chain logs continue.

### Specific signature that proves the regression is fixed

For `java/lang/String` on this runtime, the corrected JDK 9+ resolver should yield a mirror close to what HotSpot reports in the hs_err log:

- HotSpot mirror: `0x00000007ff880460`
- Resolver log should show the same wide oop (process-specific variation allowed)
- It should **not** show the decoded garbage value `0x00000007fc402300`

### Cross-version checks to keep in mind

- **JDK 8 + compressed oops on:** mirror resolver must use the inline-cell path and still decode correctly.
- **JDK 8 + compressed oops off:** mirror resolver must fall back to plain wide load through `neko_load_oop_from_cell()`.
- **JDK 9+ / 11+ / 17+ / 21+:** resolver must always use the OopHandle-wide-slot path.

No GC-specific branching should appear in the final implementation.

---

## Risk register

### 1) Over-correcting by changing the generic oop-cell helpers

**Risk:** a blanket change to `neko_load_oop_from_cell()` would fix the mirror path but break real inline compressed-oop cells.

**Mitigation:** keep the helper semantics unchanged; narrow the fix to the mirror resolver.

### 2) Reusing the wrong abstraction again later

**Risk:** future code may again feed an `OopHandle` slot into an “inline cell” helper because the names sound interchangeable.

**Mitigation:** introduce a dedicated helper name for JDK 9+ mirror slot resolution (`...storage_slot...`), and keep the type/contract distinction explicit in code and comments.

### 3) Hidden follow-on bug after mirror fix

**Risk:** once this regression is fixed, execution may advance into a later bootstrap/root-chain path and expose a separate bug.

**Mitigation:** keep the new flushed logs in place for the next run so the next failure, if any, is stage-localized immediately.

---

## Short prescription

The next fix is **not** “change compressed-oop decode math”; it is **“stop decoding JDK 9+ mirror `OopHandle` storage as a compressed inline oop cell.”**

Refine Oracle 6 Fix D so that:

- JDK 8 mirror path stays on `neko_load_oop_from_cell()`
- JDK 9+ mirror path does `OopHandle -> oop* slot -> wide oop load`

That matches the crash evidence exactly and is the minimal cross-version-safe correction.
