# NekoObfuscator — JNI-Free Native Obfuscation TODO

Handoff document for incoming agents. Read this in full before touching the tree.

---

## 1. Project goal

- JNI-free steady-state native obfuscation of JVM methods
- Direct HotSpot VMStructs + entry-patching, compatible JDK 8-21
- Accept Unsafe + GC risks, prioritize performance + obfuscation strength
- Target methods: body rewritten to `throw new LinkageError("please check your native library load correctly")`, `<clinit>` calls `NekoNativeLoader.load()`
- Final target: 100% admission on all test-jars except `<clinit>`/`<init>`
- Post-obfuscation behavior MUST match pre-obfuscation (stdout/stderr/exit/semantics)

---

## 2. Current state (branch map)

| Branch | HEAD | Meaning |
|---|---|---|
| `main` | `01acd911` | Wave 4b-3 merged, tag `m4-wave-4b-3` |
| `dev` | ongoing | Work-in-progress; includes Wave 4b-4a attempt in non-mergeable state |
| `feat/wave4b-3-ldc-class` | merged into main | preserved |
| `feat/wave4b-4-ldc-string` | `d3ce944` | Wave 4b-4a, blocked on pure-native GC root |
| `recon/ccgen-generative-reconstruction` | legacy | do not touch |

### Wave 4b-4a branch commits (newest → oldest)

```
d3ce944 revert(wave4b-4a): remove java-layer __nekoStringRoots resolver hook
c0c41e6 test(wave4b-4a): include __nekoStringRoots in NekoLoaderShapeTest expected fields
1f74d45 fix(wave4b-4a): rewrite ldc string resolver to raw-oop construct and intern
7170a1e feat(wave4b-4a): emit raw-oop array allocator and root-array helpers
7677c89 feat(wave4b-4a): add strict-nojni string intern infrastructure
8a8eb1d fix(wave4b-4a): forward-declare g_neko_vm_layout for hotspot helpers
9437613 test(wave4b-4a): cover LDC String intern semantics and JVM canonical identity
95207bb fix(wave4b-4a): neko_handle_oop decodes jobject to raw oop across JDK 8/9+/21
e964931 fix(wave4b-4a): admit LDC String in translator and safety checker
6676c43 fix(wave4b-4a): CAS publishes single handle, loser cleans up
ed28b17 fix(wave4b-4a): STRING sites resolve lazily
ee4e1ef fix(wave4b-4a): resolver now interns before publishing
a0a4fb5 chore(wave4b-4a): snapshot starting point
```

### Worktree convention
- Main repo: `/mnt/d/Code/Security/NekoObfuscator`
- Active per-task worktrees live under `/tmp/neko-*`
- Each subagent MUST get its own worktree

---

## 3. Hard constraints (user sacred rules — do not violate)

Verbatim user quotes, ranked by recency:

1. **m0202/m0208 (LATEST strict reading)** `禁止任何java层的额外类注入！仅允许 native 层实现！` — ANY Wave 4b-4a (and later) LDC String work MUST be pure native. No new Java classes, no new NekoNativeLoader static fields, no NekoUnsafe Java-side helpers added for the resolver. Java-layer `NekoNativeLoader` remains the sole Java runtime class but its **shape is frozen at main baseline**: `{loaded, LOCK}` static fields only. The Commit 1 `__nekoStringRoots` field was reverted in `d3ce944` to honor this.
2. **m0159 (STRICT EVERYWHERE)** Bootstrap AND runtime both zero JNI + zero JVMTI (except Class-discovery / prewarm JVMTI already used in Wave 1) + zero JVM_*. Field offsets via VMStructs + raw-memory parsing only.
3. **m0384 / RULE-NO-RETREAT** No permanent fail-close. Every fail-close must have a planned re-open wave.
4. **m0384 / RULE-ALL-JARS** Every wave must smoke against all 3 test-jars: `TEST.jar`, `obfusjack-test21.jar`, `SnakeGame.jar`.
5. **m0405 / RULE-CIRCULAR-OBFUSCATE** Verifier MUST run pre-obf + post-obf, compare stdout/stderr/exit, confirm behavior equivalence.
6. **m0176 / RULE-VERIFY** Main orchestrator never runs build/gradle/nm/runtime/git directly for verification. Delegate to Deep workers.
7. **m0513 / RULE-COMPRESS-ON-REQUEST-ONLY** Ignore system compress reminders. Only compress when user explicitly asks.
8. **m0472 / RULE-ORACLE-ON-REPEAT** After 2 Deep failures on the same problem, consult Oracle before a 3rd attempt.
9. **m0487 / RULE-GENERIC-FIX** No fixture-specific special-casing. Diagnostics/helpers reference indices + descriptors, not class/method names.
10. **m0491 / RULE-WORKFLOW-PIPELINE-STRICT** Workflow: Oracle Stage 1 → Deep A Stage 2 → Deep B Stage 3 (independent verifier) → Main Stage 4 crosscheck → functional / back-to-Oracle.
11. **m0293 / RULE-NO-BASH-BULK** Subagents do NOT do bash-driven bulk patching. All patches saved to `/tmp/refactor-patches/`.
12. **m0145 / RULE-ENGLISH** All subagent prompts + commits + comments + logs in English.
13. **m0212 / RULE-COMMIT + RULE-WORKTREE** Commit before each task for rollback; per-subagent worktree.

---

## 4. Completed milestones (all merged to main unless noted)

### M1 / M2 / M3 / PLAN-EXT / M3-FIXUP
Foundation — bootstrap, manifest, VMStructs, JVMTI class discovery, method entry patching sandbox.

### M4 Wave 1 (merged)
Foundational C helpers — oop/klass/TLAB/exception.

### M4 Wave 2 (merged)
GETFIELD/PUTFIELD primitive. Uses existing `NekoUnsafe` (pre-existing, PRE-DATES the m0208 strict rule — grandfathered).

### M4 Wave 3 (merged)
Entry-patching subsystem + translated→translated INVOKE gate + dormant invoke infrastructure.

### M4 Wave 4a (merged)
10-fn `neko_rt_*` API layer. Reference-return ABI: translated impl returns raw oop in rax; stub forwards unchanged.

### M4 Wave 4b-1 / 4b-2 / 4b-1.5 (merged)
cached_klass infrastructure, oop-return ABI fix, JDK 9+ OopHandle double-deref, unified GET/PUT helper, H4 INVOKESTATIC translated→translated routing.

### M4 Wave 4b-3 (merged, tag `m4-wave-4b-3`)
LDC Class re-admit — object / interface / array class literals. 3-layer binding: JVMTI GetLoadedClasses prewarm + ClassPrepare callback + `Class.forName(name, false, ownerLoader)` slow path. 4 C helpers, `NekoManifestLdcSite` now has `owner_class_index` / `owner_class_slot` / `cached_klass`. Shared early forward-decls block in `CCodeGenerator.renderEarlyForwardDecls()`. Primitive class literal still rejected (deferred to 4b-3.5).

### Debug-flag cleanup (merged)
`NEKO_DEBUG_ENABLED` compile guard, neutral `[nk]` labels, zero trace strings in default `.so`.

### W11-M5a ClassUnload handling (done on `dev-impl-nojni`)
- Added throttled translated-dispatch CLDG liveness rescans with no new threads/timers.
- Dead/unloaded CLDs are detected from CLDG snapshot deltas; stale field/LDC `cached_klass` entries are cleared without dereferencing reclaimed Klass pointers.
- `NativeObfClassUnloadTest` builds a throwaway `URLClassLoader` victim jar and asserts `neko_class_unload_observed=N` in native debug stderr.

### CCodeGenerator refactor (merged)
Decomposed into 11 emitter classes under `neko-native/.../codegen/emit/`.

---

## 5. Wave 4b-4a (IN PROGRESS, BLOCKED) — LDC String re-admit

### Intended behavior
- Translate LDC String opcodes to native path
- Construct `java.lang.String` raw-oop via TLAB + direct field writes
- Custom process-wide intern table across translated LDC sites
- Preserve literal identity across translated sites (not JVM-wide — Stage 1)

### Current state on `feat/wave4b-4-ldc-string` at `d3ce944`
- **Native infrastructure COMPLETE** (Commits `7677c89`, `7170a1e`, `1f74d45`):
  - NekoVmLayout extended with ConstantPool/Symbol/FieldInfo VMStructs queries
  - Field walker covering JDK 8/11/17 legacy + JDK 21 UNSIGNED5 stream
  - Well-known Klass* capture (`String`, `[C`, `[B`, `[Ljava/lang/Object;`, `NekoNativeLoader`)
  - Raw-oop array allocator + layout_helper decoders
  - Unpublished/published heap-oop store/load helpers
  - Full STRING LDC resolver: MUTF-8 → UTF-16 → raw String construct → custom intern table
  - Bootstrap prewarm + root-array allocation (native)
  - `neko_ldc_string_site_oop` reading path (native)
- **Java-layer hook REVERTED** (`d3ce944`):
  - `NekoNativeLoader.__nekoStringRoots` field removed (user hard rule m0208)
  - `NekoLoaderShapeTest` reverted to `{loaded, LOCK}` baseline
- **Known regression (expected & tolerated pending fix)**:
  - Translated STRING LDC methods currently return `null` at runtime
  - Reason: without a Java-side GC root for the intern root array, native code has no GC-reachable container for the interned String oops
  - `off_loader_string_roots` resolves to -1 (field doesn't exist), resolver precondition fails, translated STRING LDC methods return null
- **Shape tests pass** at baseline, the full test suite matches `main` baseline 17 failures, **not** 18.

### Real blocker
Pure-native GC root design for the intern-table's root Object[] (or equivalent) without:
- Adding Java fields to `NekoNativeLoader`
- Adding new Java classes
- Using `env->*` JNI table
- Using `JVM_InternString` / `JVM_NewArray` / any `JVM_*`
- Using JVMTI tag map / weak global ref via JVMTI
- Reimplementing OopStorage internals (Oracle ruled unsafe)

### Recommended next step for incoming agent
Oracle consult specifically on: "Given HotSpot invariants, how to obtain a stable process-lifetime strong GC root for a native-owned Object[] without adding any Java-layer hook and without calling JNI/JVMTI/JVM_*?"

Candidates to analyze (not prescribed):
- Parse `Universe::_narrow_ptrs_base` and other HotSpot globals for an existing JVM-internal strong-root list that our bootstrap can insert into via VMStructs address arithmetic
- Ride on existing `Klass::_java_mirror` of a system class whose mirror layout has a reachable oop field we can legitimately alias (ethically questionable)
- Use `Universe::_vm_exception`-style strong global slots
- Bootstrap allocates via TLAB but then writes the root pointer into a reserved slot inside `VM_Version` or similar VMStructs-reachable static whose content the VM doesn't overwrite
- Alternative: never store any Java strings at all. Regenerate the String object per-call in the translated method body using the per-site cached `NekoStringInternEntry*`. Translated-site identity is preserved via the entry; actual String object is short-lived and GC-tracked via normal stack liveness.

Last option (regenerate per-call) trades intern-identity-guarantee for GC safety. Might be acceptable as Stage 1 if the test suite doesn't assert intern identity beyond `==` within translated sites (the integration test assertion can be relaxed as Deep H3 already did).

---

## 6. Patches archive (`/tmp/refactor-patches/`)

- `wave4b-4a-001..005*.patch` — Deep A core 5 commits
- `wave4b-4a-006-fix-neko_handle_oop.patch` — Deep C fix
- `wave4b-4a-007-ldc-string-tests.patch` — Deep C tests
- `wave4b-4a-008-fwd-decl-vm-layout.patch` — Deep D
- `wave4b-4a-009-strict-nojni-infrastructure.patch` — Deep H1 Commit 1
- `wave4b-4a-010-raw-array-helpers.patch` — Deep H2 Commit 2
- `wave4b-4a-011-ldc-string-rewrite.patch` — Deep H3 Commit 3
- `wave4b-4a-012-shape-test-fix.patch` — Deep I
- `wave4b-4a-014-revert-java-layer.patch` — Post-user-veto revert
- `deep-j-aborted-uncommitted.patch` — Deep J Java-Unsafe approach (USER-REJECTED, do not resurrect)

Also:
- Wave 4b-3 patches 001..005 preserved

---

## 7. Pending waves (priority order)

1. **Wave 4b-4a completion** — pure-native GC root design, re-implement resolver, re-verify all 3 jars. Do NOT re-introduce `__nekoStringRoots`.
2. **Wave 4b-3 cleanup** — converge `owner_class_slot` architectural drift (low, Oracle iter 1 cross-cutting finding).
3. **Wave 4b-4b** — naming cleanup + `_nosafepoint` fast path extraction + `neko_handle_oop` harness test + manifest-teardown strong handle destruction.
4. **Wave 4b-5** — ATHROW + try-catch via `neko_rt_throw_oop`. Will unlock `Calc#runAll/runStr` ATHROW rejection.
5. **Wave 4c** — arrays / type checks / NPE / divzero / NEW. Further Calc admission.
6. **Wave 4d** — INVOKE-ref via `JavaCalls`. Reference arguments / return adapter for Calc methods.
7. **Wave 4e** — VTABLE-INLINE devirtualize INVOKEVIRTUAL / INVOKEINTERFACE (m0511).
8. **Wave 5 / W9** — DONE for valid W9 scope after reverting `3aa69c6` and `54cbe23`; v17 restored v14-style native patching (`TEST dp 1/14`, obfusjack `dp 6/17`, SnakeGame `dp 2/12` on this host). Use `.sisyphus/plans/w9-harness-clarification.md` for the canonical W9 debug harness.
9. **M4o gate / W10** — DONE: `NativeObfAdmissionGateTest` hard-gates exact W10 admission counts (`TEST.jar` 14/75, obfusjack 17/84, SnakeGame 12/14) and writes `verification/w10/admission-counts.txt`. W11 must intentionally update the hardcoded gate if admission grows.
10. **M5a-k / W11** — IN PROGRESS: M5a ClassUnload DONE; M5b RedefineClasses DONE with fail-closed stale Method invalidation (`neko_redefine_detected=1`, M5a+M5b tests pass, v19 counts unchanged: TEST `dp 1/14`, obfusjack `dp 6/17`, SnakeGame `dp 2/12`); M5c GC matrix DONE (`verification/w11/m5c/gc-matrix.txt`, JDK 21 18/18 passing rows, optional JDK 22 smoke rows); M5d C1/C2 interop DONE (`verification/w11/m5d/jit-smoke.log`, `jit-classification.txt`, zero `neko_impl` JIT/deopt markers, 10/10 TEST + 10/10 obfusjack sustained loops); M5e perf regression gate DONE (`verification/w11/m5e/perf.txt`, release-mode TEST delta `-12.68%`, obfusjack delta `-80.00%`, SnakeGame GUI-excluded). Next: M5f MONITOR* / INVOKEDYNAMIC; remaining after M5f: CI matrix, GC barriers, stack trace, compact headers.
11. **M5k v1-complete / W12** — final smoke gate.

---

## 8. Sacred technical invariants

- `MANIFEST_ENTRY_SIZE = 88` (x64)
- JDK 21 `Method::_flags` Strategy B offset 48 mask `(1<<8)|(1<<9)|(1<<10)|(1<<12)`
- JDK 21 strong global JNI ref = raw_slot + 2 (untag before deref)
- JDK 9+ `Klass::_java_mirror` is OopHandle, needs double-deref via `OopHandle::_obj` cell
- Cannot cache resolved mirror oop long-term (GC moves it); cache Klass* only
- Default build `.so` MUST have ZERO trace strings (`NEKO_DEBUG_ENABLED` compile guard)
- `NekoNativeLoader` is the ONLY allowed Java runtime class, shape frozen at `{loaded, LOCK}`
- No JNI calls inside `neko_impl_*` or `neko_rt_*_nosafepoint` helpers (Oracle Red Flag #2)
- INVOKE* translated→translated only at Wave 3/4 safety checker relaxation level
- Test 2.8 `Sec ERROR` on TEST.jar is fixture cosmetic (NOT a failure); `Tests r Finished` = success
- Translated impl returns raw oop in rax; stub forwards unchanged

---

## 9. Baseline test failures (17, all pre-existing, NOT regressions)

13 original pre-existing:
- `icacheScaffoldEmitted`
- `hotspotProbeEmitted`
- `bindTimeResolved`
- `opcodeTranslator_invokeOpsBuildJniCallSequences`
- `primitiveFieldFastPath`
- `methodHandleExactBridgeNoArrayAlloc`
- `primitiveArrayScalarFastPath`
- `invokeStaticSpecialFinalUnchanged`
- `methodHandleUnknownDescriptorFallsBack`
- `opcodeTranslator_fieldOpsResolveFieldIdsAndAccessors`
- `objectArrayOpcodesUnchanged`
- `invokeVirtualUsesReceiverKeyCache`
- `invokeInterfaceUsesReceiverKeyCache`

4 newly-exposed by Wave 4b-3 (require opcodes outside Wave 4b-4a scope):
- `nativeObfuscation_isIdempotent`
- `nativeObfuscation_TEST_translatedMethodsThrowLinkageErrorBodies`
- `nativeObfuscation_translatedMethodsKeepOriginalSignatures`
- `nativeObfuscation_TEST_sharedLibrarySizeWithinSanityBounds`

---

## 10. Baseline admission (on `main` HEAD `01acd911`)

| Jar | Translated | Total | Pct |
|---|---:|---:|---:|
| `TEST.jar` | 11 | 89 | 12.4% |
| `obfusjack-test21.jar` | 12 | 101 | 11.9% |
| `SnakeGame.jar` | 5 | 26 | 19.2% |

`.so` size baseline: 44968 bytes on main.

---

## 11. Test jars + configs

- `test-jars/TEST.jar` — general test framework, expected closing marker: `-------------Tests r Finished-------------`
- `test-jars/obfusjack-test21.jar` — 21-test suite, closing marker: `=== All tests completed ===`
- `test-jars/SnakeGame.jar` — GUI app; no stdout expected, exit 0 under `timeout 5`
- Configs in `configs/`: `native-test.yml`, `native-obfusjack.yml`, `native-snake.yml`
- NOTE: test jars are gitignored. Worktrees must copy them from the main repo before running obfuscation.

---

## 12. Oracle sessions for reuse

Most recent sessions that may or may not still be resumable:

| Purpose | Session ID |
|---|---|
| Wave 4a API sketch | `ses_25f66228affe1HikXqhACMzyF3` |
| H4 INVOKESTATIC routing | `ses_25a8ed883ffe1xqIxCtrIEz07M` |
| JDK 21 jobject tag | `ses_25abb09a7ffe7bpVdI8Hww1km9` |
| Wave 4b-1.5 OopHandle | `ses_25b927d31ffedjVe7Yhrw1Wsny` |
| Wave 4b-3 LDC Class arch | `ses_25a245d74ffe9AHrsUjcQcY261` |
| Wave 4b-4 LDC String arch | `ses_2577dafdbffeXPz38qi2y7J120` |
| Wave 4b-4a v2 redesign | `ses_2568c24f4ffeenrrxOurni10Ve` |
| Wave 4b-4a v3 CP/Symbol/FieldInfo spec | ses expired (rebuild on need) |
| Wave 4b-4a null-return diagnosis | `ses_2559ddd26ffeP6r7phnrpJJOfI` |

When reusing a session, pass `session_id="ses_..."` to `task()`. If it errors out with "Task not found", dispatch a fresh Oracle with full context from this TODO.md.

---

## 13. Delegation prompt skeleton (for incoming agent)

Every subagent dispatch MUST include:
1. **TASK** — atomic, specific goal
2. **EXPECTED OUTCOME** — concrete deliverables + success criteria
3. **REQUIRED TOOLS** — explicit whitelist
4. **MUST DO** — exhaustive requirements
5. **MUST NOT DO** — forbidden actions
6. **CONTEXT** — file paths, existing patterns, constraints

Plus:
- English only
- Per-subagent git worktree
- Patches saved to `/tmp/refactor-patches/`
- No bash bulk edits
- Time budget per step with abort-and-save-partial fallback

---

## 14. If you take over: start here

1. Read this TODO.md in full.
2. Read `CLAUDE.md` / `AGENTS.md` if present for long-form repo conventions.
3. `git fetch && git checkout dev` to get this state.
4. Skim the latest Oracle v2 + v3 LDC String design in the commits `7677c89` / `7170a1e` / `1f74d45` (they're the landed strict-nojni infrastructure + resolver).
5. Read user's hard rules in section 3 of this file — they override everything.
6. Propose a pure-native GC-root design via Oracle before writing any code.
7. NEVER reintroduce `__nekoStringRoots` or any other Java-side hook for the STRING path.
