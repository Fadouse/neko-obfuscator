# Oracle 1 Design — Pure-Native GC Root for LDC String Intern Table

**Task ID**: bg_448b7c58
**Session**: ses_251d29af0ffeW1FpSOQc5CWKF1
**Duration**: 19m 3s
**Decision**: Design A2 — boot ClassLoaderData handle-chain cells (pinned chain)

---

## Recommendation (one sentence)

Replace Java-layer `__nekoStringRoots` field (reverted) with a **manifest-preallocated `oop*` cell pool embedded in the boot `ClassLoaderData::_handles` ChunkedHandleList**; each unique LDC String literal gets one fixed `root_cell`; `NekoStringInternEntry` caches `root_cell`; reads/writes go through `NativeAccess<>::oop_load/store`.

## Candidate matrix

| Candidate | HotSpot/GC | JDK 8/11/17/21 | Unload/Redefine | Safepoint | Verdict |
|---|---|---|---|---|---|
| **A. Existing strong-root list insertion** | ✅ boot `ClassLoaderData::_handles` | ✅ (version-branched offsets) | ✅ boot CLD is process-lifetime | ✅ cell load/store safe | **SELECTED** |
| B. Ride on system class mirror | depends on field | low | low | med | high risk |
| C. Universe::_vm_exception-style slot | GC visible | low | high | high | semantic conflict |
| D. Reserved VM_Version static slot | GC does NOT scan | low | high | high | REJECTED |
| **E. Regenerate String per call** | relies on stack liveness | high | high | requires `_nosafepoint` discipline | **FALLBACK** |

## Why A wins

1. **GC correctness**: `ClassLoaderData::_handles` is an existing HotSpot strong root. `ChunkedHandleList::oops_do()` hands each `oop*` to the GC. Covers Full GC, G1, Serial, Parallel, ZGC, Shenandoah, Epsilon.
2. **Indirection through `oop*`**: native C table stores only `oop*` (root_cell address, off-heap, stable); never caches raw `oop` long-term. GC updates the cell content; read path always redoes `oop_load`.
3. **JDK portability**: VMStructs exposes all required fields:
   - `ClassLoaderDataGraph::_head` → `ClassLoaderData*`
   - `ClassLoaderData::_next` → `ClassLoaderData*`
   - `ClassLoaderData::_class_loader` → oop (JDK8) or OopHandle (11/17/21)
   - `ChunkedHandleList` layout stable: `_head : Chunk*`; `Chunk {oop _data[32]; juint _size; Chunk* _next}`
   - `_handles` offset computed from neighboring fields with per-JDK branch:
     - JDK 8: `off_handles = off_cld_klasses + wordSize`
     - JDK 11/17/21: `off_handles = off_cld_klasses - wordSize`
4. **Unload/redefine**: boot CLD lifetime == process lifetime; NekoNativeLoader / target-class / Class.forName / RedefineClasses cannot unpin root.
5. **Safepoint**: `root_cell` load/store is safepoint-safe via `NativeAccess<>::oop_load/store` (equivalent to HotSpot `OopHandle::resolve/replace`).

## Implementation skeleton

```c
// -------- bootstrap (once, early JNI_OnLoad window) --------
resolve_vmstructs();
boot_cld = walk_cldg_find_boot_cld();       // _class_loader == null / empty OopHandle
handles_head = (Chunk**)((u8*)boot_cld + off_handles);

root_count = manifest.unique_string_literal_count;
chain = alloc_chunk_chain(root_count);      // exact HotSpot Chunk layout
init_all_cells_to_null(chain);
publish_chain_once(handles_head, chain);    // CAS loop, pre-thread-open window

for each manifest literal i:
    manifest.literal[i].root_cell = nth_cell(chain, i);

// -------- runtime fast path --------
oop neko_ldc_string_site_oop_nosafepoint(NekoStringInternEntry* e, Thread* thread) {
    oop s = NativeAccess<>::oop_load(e->root_cell);
    if (s != NULL) return s;

    lock(e->init_lock);
    s = NativeAccess<>::oop_load(e->root_cell);
    if (s == NULL) {
        oop value = alloc_[B_or_C]_via_tlab(thread, ...);   // already landed
        oop str   = alloc_String_via_tlab(thread, ...);     // already landed
        heap_store_string_fields(str, value, coder/hash...); // HeapAccess<>::oop_store_at
        NativeAccess<>::oop_store(e->root_cell, str);
        s = str;
    }
    unlock(e->init_lock);
    return s;
}

// -------- teardown --------
for each root_cell:
    NativeAccess<>::oop_store(root_cell, NULL);
```

## Allocation + barriers

- **Allocation path**: continue existing TLAB fast path for `[B` / `[C` / `String`; this design only replaces the persistent root.
- **Heap-field store inside String**: `String.value`, `String.coder`, `String.hash` go through `HeapAccess<>::oop_store_at` (or existing `obj_field_put` wrapper).
- **root_cell store/load**: always `NativeAccess<>::oop_store/load` — matches HotSpot `OopHandle::resolve/replace` semantics.
- **G1 / ZGC / Shenandoah**: SATB, load barrier, Brooks-style barrier handled by Access API automatically.

## Risks + mitigations

1. **Racy publication into `ClassLoaderData::_handles._head`**
   - Mitigation: single publish window; preallocate entire chain with all cells null; publish before translated entrypoints open; self-check links post-publish; on failure flip to Candidate E fallback.
2. **ABI drift**
   - Mitigation: JDK-major branches on `_handles` offset derivation; structural self-check (size≤32, pointer alignment, round-trip read); any failure → disable STRING LDC native path.
3. **Raw oop lifetime across safepoints**
   - Mitigation: `NekoStringInternEntry` holds only `root_cell`; every slow-path arm re-loads from root_cell.

## JDK-specific notes

- **JDK 8**: `ClassLoaderData::_class_loader` is plain `oop`; `_handles` is AFTER `_klasses`.
- **JDK 11/17/21**: `_class_loader` is `OopHandle`; boot CLD detected via `OopHandle::_obj == NULL`; `_handles` is BEFORE `_klasses`.
- **JDK 21**: compact headers do NOT affect this root scheme; String layout continues using existing field walker.

## OpenJDK 21 key file anchors

- `src/hotspot/share/classfile/classLoaderData.hpp`
- `src/hotspot/share/classfile/classLoaderData.cpp`
- `src/hotspot/share/runtime/vmStructs.cpp`
- `src/hotspot/share/oops/oopHandle.inline.hpp`
- `src/hotspot/share/runtime/thread.hpp`
- `src/hotspot/share/memory/universe.hpp`

## Test assertions

1. **GC stability**: same literal, repeated calls interleaved with `System.gc()` / Full GC pressure → `==` identity stable, no crash.
2. **Collector matrix**: JDK 8/11/17/21 × Serial/Parallel/G1/ZGC/Shenandoah/Epsilon × same STRING LDC fixture → correct value, stable process.
3. **Class unload**: child loader loads obfuscated jar → triggers native STRING LDC → unload loader → rerun with fresh loader → no dangling references, no crash.
4. **Self-check**: export read-only diagnostic `boot_cld_root_chain_ok == 1`.

## Effort

Medium — 1-2 days.

## Fallback

Candidate E (regenerate String per call, no persistent root) is acceptable on bootstrap self-check failure. Gate at runtime behind self-check flag.
