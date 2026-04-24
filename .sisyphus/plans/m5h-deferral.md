# W11-M5h Reference GETSTATIC / PUTFIELD / PUTSTATIC / AASTORE — DEFERRED to follow-up sub-wave

## Status
- HEAD at deferral: `a02b714` (W11-M5g GREEN). Wave3 snprintf cleanup committed in this same wave as `fix(w11-m5h):` (Fix 2). Reference admission DEFERRED.
- Stash: `stash@{0} On dev-impl-nojni: M5h ref admission WIP` contains the 6-file ref admission diff.
- Deferred test: `verification/w11/NativeObfRefFieldTest.java.deferred` (preserved for follow-up).

## What was attempted
A first-cut M5h implementation admitted reference GETSTATIC / PUTFIELD / PUTSTATIC / AASTORE in the SafetyChecker and emitted GC write-barrier helpers in `Wave2FieldLdcEmitter.java`:
- `neko_card_mark_oop_store(slot)`
- `neko_oop_store_at(thread, base, offset, raw_oop, is_volatile)`  — G1 SATB pre-barrier + atomic store + card mark; LE for ZGC/Shenandoah
- `neko_oop_klass(oop_value)` / `neko_raw_array_length(array_oop)` / `neko_reference_array_store_check(array_oop, value_oop)` / `neko_aastore_raw(thread, array_oop, index, value_oop)`
- New `NekoVmLayout` fields: `card_table_base`, `gc_kind` enum, `off_obj_array_klass_element_klass`, `klass_java_lang_Object`
- `OpcodeTranslator` reference cases routed to the new helpers
- `NativeObfRefFieldTest` covering GETSTATIC of a String, PUTFIELD of a String, PUTSTATIC of a String, AASTORE of a String into String[]

Build + obfuscation completed; admission grew (TEST 14→23, obfusjack 17→26, SnakeGame 12→14).

## What went wrong
1. **First runtime attempt**: obfusjack SIGSEGV at `libc.so+0x8ae90` (snprintf+0x96) called from `libneko+0xc3ff` from `neko_impl_25+0x231`. Diagnosed by Oracle 11 as x86-64 SysV ABI alignment fault: dead `snprintf` cold paths in `Wave3InvokeStaticEmitter.renderBindSupport()` ran with misaligned rsp because `AssemblyStubEmitter.alignForPopCallFrame` and the snprintf-allocated `char message[256/320]` interacted badly with M5h's wider admission.

2. **Fix 1 alone (alignUp(bytes, 16))**: TEST exit 134 SIGSEGV `libc+0x8ae90` (still snprintf alignment), obfusjack exit 134 SIGSEGV same, SnakeGame OK.

3. **Fix 1 + Fix 2 (drop snprintf in renderBindSupport)**: TEST exit 134 SIGSEGV at `libjvm+0x52635f` (NEW signature, `_thread_in_vm` state, 34ms after VM start, after `[nk] n sfm java/lang/System.out` trace), obfusjack FIXED (exit 1 LinkageError fallback), SnakeGame exit 1 LinkageError fallback. Diagnosed by Oracle 12 as Fix 1 over-aligning to 0-mod-16 instead of 8-mod-16.

4. **Fix 1' = alignUp(bytes+8,16)-8 + Fix 2**: TEST FIXED (exit 1 LinkageError), SnakeGame exit 1 LinkageError, obfusjack RE-BROKE (exit 134 SIGSEGV `libjvm+0x52635f` at `neko_impl_25+0x23d`).

5. **Reverted alignment to original 8-mod-16 + Fix 2**: TEST exit 1 LinkageError, SnakeGame exit 1 LinkageError, obfusjack exit 134 SIGSEGV.

The crash signature `libjvm+0x52635f` (`movaps -0x50(%rbp)`) consistently appears at the M5h sfm/fsr static-field-mirror resolution path (visible last trace: `[nk] n sfm java/lang/System.out:Ljava/io/PrintStream; klass=... mir=0x7ff87fe60 off=116`). The `mir=0x7ff87fe60` looks like a JNI-tagged handle, not a raw oop — hypothesis (a) from Oracle 12 escalation: M5h emits handle-vs-oop confusion in the static-field-mirror path that corrupts VM state during `<clinit>`, causing the subsequent VM-internal `movaps` to crash.

## Final v23 baseline check (HEAD with only Wave3 Fix 2 + AssemblyStubEmitter comment)
verification/w11/m5h-defer-v23/:
- TEST translated=14 dp 1/14 exit 1 (LinkageError fallback)
- SnakeGame translated=12 dp 2/12 exit 1 (LinkageError fallback)
- obfusjack translated=17 dp 6/17 exit 1 (LinkageError fallback)
- All 3 GREEN: matches v17 baseline counts, no SIGSEGV, no core dumps

## Deferral scope and re-attempt plan (follow-up sub-wave M5h')
1. **Decode the M5h sfm/fsr handle**: confirm `mir` returned by `neko_site_owner_klass(site)` and the static-field mirror lookup is the InstanceMirrorKlass oop, not a JNI handle. If it IS a JNI handle, untag via `neko_load_oop_from_cell` before any subsequent address arithmetic.
2. **Gate M5h reference admission post-bootstrap**: defer reference GETSTATIC/PUTFIELD/PUTSTATIC patching until after all classes have completed `<clinit>` (if any class's `<clinit>` runs translated reference code, the M5h sfm path runs in `_thread_in_vm` state which is unsafe for our raw oop manipulation). Add a `g_neko_ref_admission_armed` flag set after the first non-`<clinit>` patch.
3. **Verify with full-platform GC matrix** (M5g already verified zero-FORBIDDEN audit; M5h' must re-run M5c-style 18-row matrix).
4. **Recover the stashed diff** with `git stash apply stash@{0}` once the sfm path is corrected.
5. **Restore the deferred test** from `verification/w11/NativeObfRefFieldTest.java.deferred`.

## What landed in the M5h commit
- `AssemblyStubEmitter.java`: comment-only diff documenting the alignment investigation conclusion. Behavior preserved (original 8-mod-16 logic).
- `Wave3InvokeStaticEmitter.java`: Fix 2 — removed 10 dead `snprintf` calls + `char message[256/320]` buffers in `neko_bind_*_slot` and `neko_bound_*` functions. The unused message text was passed to `neko_raise_bound_resolution_error` which already does `(void)message; (void)errorClass; neko_throw_cached(env, g_neko_throw_le);` — so the snprintf was dead code that nonetheless allocated stack space and ran format string parsing. Fix 2 is a pure improvement: clean code path, safe across all admission widths, restored M5h obfusjack from SIGSEGV to expected LinkageError fallback.

## Acceptance for M5h DONE (when re-attempted as M5h')
- Reference GETSTATIC / PUTFIELD / PUTSTATIC / AASTORE admitted without SIGSEGV across all 3 jars
- `NativeObfRefFieldTest` passes
- Behavior diff empty (post-obf output matches pre-obf for all reference field/array operations)
- Full GC matrix 18/18 PASS
- M5g audit re-run: 0 FORBIDDEN raw oop derefs (the new helpers must use barrier-safe pattern)
- v23 baseline no regression
