# Oracle 5: W1 LDC String Root Cell Nil Diagnosis

## Root Cause

Two separate issues:

### Issue 1: Missing capture/root-chain traces
- **NOT a code bug** — source/runtime artifact mismatch
- If later `[nk] n w2 ...` logs appear but earlier `boot_cld=` / `boot_cld_root_chain_ok=` do not, the built native artifact does not match these emitter sources
- Both `NEKO_TRACE` and `neko_native_debug_log` share identical debug gating (BootstrapEmitter.java:57-62, 88-98, 119-132)
- Fix: ensure fresh rebuild from current emitter sources before testing

### Issue 2: Nil LDC String roots (THE REAL BUG)
- BOOT_CLD path allows published root cells to remain empty forever
- `Wave2FieldLdcEmitter.java:726-733`: resolver loads from `entry->root_cell`, gets NULL (prewarm failed to materialize), returns NULL
- Nothing lazily backfills the cell — if prewarm skipped or failed for any entry, that entry stays nil permanently

## Fix Plan

### Fix A: Lazy fill + CAS publish in resolver (PRIMARY FIX)
- **File**: `Wave2FieldLdcEmitter.java:726-733`
- **Change**: If `entry->root_cell != NULL` but `neko_load_oop_from_cell(...)` returns NULL:
  1. Call `neko_create_ldc_string_oop(site, ...)`
  2. If creation succeeds, CAS-publish into root_cell (not blind store)
  3. Reload and return cell value
- **CAS semantics**: compare_exchange(root_cell, NULL, new_oop) — adopt winner on race

### Fix B: Branch-specific prewarm tracing (OBSERVABILITY)
- **File**: `BootstrapEmitter.java:1884-1888, 1896-1901, 1903-1908, 1910-1914, 1916-1920`
- **Change**: Replace generic `"candidate_e"` fallback reasons with distinct tags:
  - `missing_klasses`, `boot_cld_or_handles`, `alloc_chunks`, `self_check`, `publish_chain`

### Fix C: Capture function tracing (OBSERVABILITY)
- **File**: `BootstrapEmitter.java:1445-1509`
- **Change**: Add `NEKO_TRACE(0, "[nk] cap ...")` on entry, each false-return branch, and success

### Fix D (follow-up): Split intern metadata from oop materialization
- **File**: `Wave2FieldLdcEmitter.java:551-614`
- Currently aborts intern-entry creation if `neko_create_ldc_string_oop()` fails at :568-570
- Recommended: split "intern metadata build" from "oop materialization" so resolved_cache_handle can exist even if initial materialization fails

## Escalation Trigger
If nil values persist after lazy-fill, check Wave4a allocator availability:
- `Wave2FieldLdcEmitter.java:474, 498`
- `Wave4aRuntimeApiEmitter.java:261-365`
- If Wave4a disabled, need non-JNI fallback allocation path for string materialization

## Backend Values
- 1 = BOOT_CLD, 2 = FALLBACK_REGENERATE, 0 = invalid (behaves like fallback)

## Effort Estimate
Medium (1-2 days)
