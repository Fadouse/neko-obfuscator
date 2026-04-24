# NekoObfuscator Master Implementation Plan v6.4

Revision history:
- v1-v4: initial drafts (Momus rejected 3×)
- v5: Momus [OKAY] approved, 794 lines
- v6: this document — Option A strict-no-JNI + DD-5 Option 1 revision (m0107, m0117)
- v6.4: Oracle 9 amendment — `InstanceKlass::allocate_instance` is not portable/exported, so DD-5 now permits a bootstrap-only JNI throwable cache and keeps post-bootstrap synthetic dispatch to cached `jthrowable` + `Throw` only.
- v6.5: W9 recovery note — reverted the broken safe-Java fallback regression and fixed the canonical W9 debug harness reference to the v14 invocation shape.

> **v6.4 amendment reason:** Oracle 9 confirmed that `InstanceKlass::allocate_instance(Thread*)` is absent from the `.dynsym` of supported OpenJDK builds and cannot be a portable synthetic-exception allocation backend. User explicitly approved the Opt-6/5 bootstrap-only JNI relaxation: construct global throwable handles during `JNI_OnLoad`; after bootstrap, generated code may only dispatch those cached handles via `(*env)->Throw(env, jthrowable)`.

## v6 change summary
- ADDED: W0 Strict-no-JNI bootstrap refactor (prereq to W1+)
- ADDED: GATE-13 Strict JNI surface verification, split by v6.4 into bootstrap-cache allowance (13A) and post-bootstrap strict surface (13B)
- ADDED: §6.0.2 Synthetic exception construction sequence
- REVISED: W1 consumes W0-captured `klass_java_lang_String` (Oracle 3 C → W0 DD-1)
- REVISED: W2 owner resolution via DD-2 holder-CLD walk (Oracle 2 A superseded)
- REVISED: W3 drops `owner_class_slot` per DD-3 (reversing v5 W3)
- REVISED: W4-W9 audited for hidden JNI; exception sites converted to Oracle 9 cached throwable dispatch
- PRESERVED: §1-§5, §6.0, §6.0.1, §7-§10 from v5
- NOTE: Legacy gate labels GATE-1..GATE-12 are preserved; new GATE-13 executes operationally between GATE-5 and GATE-6 while retaining an integer label.

**Branch**: `dev-impl-nojni` (worktree at `worktree/dev-impl`, based on `origin/dev@c603da7`)
**Orchestrator**: Sisyphus (Main)
**Verification policy**: m0176 — Main never runs build/gradle/nm/runtime/git; all verification delegated to Deep workers.
**Authoring policy**: m0293 — no bash-driven bulk patching; patches archived under `tmp/refactor-patches/`.
**Language policy**: m0145 — English only for prompts / commits / comments / logs.
**Worktree policy**: m0212 — each Deep worker gets its own worktree under `worktree/` and commits before starting its task.

---

## 1. User-Sacred Constraints (restated before every wave)

| ID | Constraint |
|---|---|
| m0208 | 禁止任何 java 层的额外类注入 — NO new Java classes. `NekoNativeLoader` shape FROZEN at `{loaded, LOCK}`. No new static fields. |
| m0159 | Zero JVMTI everywhere. JNI surface now superseded by m0107/m0117 strict allowlist only. Everything else must be VMStructs + `dlsym(libjvm.so)` + raw memory + HotSpot internal C++ symbols on the approved list. |
| m0384 | No permanent fail-close. Every fail-close must have a planned reopen wave. Every wave smokes all 3 test-jars: `TEST.jar`, `obfusjack-test21.jar`, `SnakeGame.jar`. |
| m0405 | Verifier runs pre-obf + post-obf and compares stdout / stderr / exit. Behavior MUST be equivalent. |
| m0176 | Main orchestrator never executes build / gradle / nm / runtime / git for verification — delegate to Deep. |
| m0472 | After 2 Deep failures on same problem, consult Oracle before 3rd attempt. |
| m0487 | No fixture-specific special-casing. Diagnostics reference indices + descriptors, NOT class / method names. |
| m0491 | Per-task flow: Oracle (design) → Deep A (implementation) → Deep B (independent verifier) → Main (crosscheck). |
| m0293 | Patches saved to `tmp/refactor-patches/`. No bash-driven bulk patching. |
| m0145 | English only. |
| m0212 | Per-Deep-worker worktree. Commit before each task. |
| m0107 | **Option A strict-no-JNI**. Only permitted JNI touchpoints in generated `.so`: `JNI_OnLoad(JavaVM*, void*)`, one `(*vm)->GetEnv(vm, &env, JNI_VERSION_1_6)` at the start of `JNI_OnLoad`, and nothing else except the m0117 allowance. Zero other JNI function-table calls anywhere in bootstrap, steady-state, or translated method bodies. |
| m0117 | **DD-5 Oracle 9 exception policy**. Synthetic exception objects are preconstructed during `JNI_OnLoad` only via the approved bootstrap throwable-cache JNI calls; after `JNI_OnLoad`, synthetic exception dispatch may use only `(*env)->Throw(env, cached_global_jthrowable)`. |
| m0208+ | `NekoNativeLoader` remains the sole Java runtime class. Shape test `NekoLoaderShapeTest.java:64-75` enforces `{loaded, LOCK}` only. |

**Final allowed JNI surface for v6.4-generated `.so`**:

1. `JNI_OnLoad(JavaVM*, void*)` entry symbol
2. `(*vm)->GetEnv(vm, &env, JNI_VERSION_1_6)` — exactly 1 call in `JNI_OnLoad`
3. Bootstrap-cache-only `(*env)->FindClass`, `GetMethodID`, `NewStringUTF`, `NewObjectA`, `NewGlobalRef`, and `DeleteLocalRef` — only inside `JNI_OnLoad` throwable-cache helpers before `JNI_OnLoad` returns
4. `(*env)->Throw(env, jthrowable)` — only for synthetic exception dispatch using cached global throwable handles
5. Nothing else

---

## 2. Sacred Technical Invariants

- `MANIFEST_ENTRY_SIZE = 88` (x64 — `_Static_assert`ed at `ManifestEmitter.java:182`).
- `MANIFEST_FLAGS_OFFSET = 32`, `MANIFEST_SIGNATURE_ID_OFFSET = 36`, `MANIFEST_IMPL_FN_OFFSET = 40`, `MANIFEST_METHOD_STAR_OFFSET = 48`.
- JDK 21 `Method::_flags` offset 48, mask `(1<<8)|(1<<9)|(1<<10)|(1<<12)` (Strategy B ACC_NATIVE / no-compile detection).
- JDK 21 strong global JNI ref = raw_slot + 2 (untag before deref). Preserve this invariant even though DD-3 removes planned steady-state global-ref dependence.
- JDK 9+ `Klass::_java_mirror` = OopHandle → needs double-deref via `OopHandle::_obj` cell.
- Cannot cache resolved mirror oop long-term; cache `Klass*` or root-cell only.
- `NekoNativeLoader` is ONLY allowed Java runtime class. Shape `{loaded: volatile boolean, LOCK: Object}` only.
- `NEKO_DEBUG_ENABLED` compile guard — release `.so` MUST have zero trace strings.
- Translated impl returns raw oop in rax; stub forwards unchanged (no `jobject` wrap).
- Test 2.8 `Sec ERROR` on TEST.jar is fixture cosmetic, NOT a failure; success marker is `-------------Tests r Finished-------------`.
- Baseline pre-existing failures remain 17 total unless a later wave deliberately reopens them with equivalent pre/post behavior.
- Canonical GC matrix is 6 collectors: Serial / Parallel / G1 / ZGC / Shenandoah / Epsilon.
- **`Klass::_next_link` VMStructs exposure is REQUIRED on the target JDK.** If absent, strict Option A bootstrap fails fast with a loud unsupported-runtime diagnostic. Do NOT silently fall back to SystemDictionary, `FindClass`, or internal resolver shortcuts.

---

## 3. Test Jar Success Markers

| Jar | Config | Success Criterion |
|---|---|---|
| TEST.jar | `configs/native-test.yml` | stdout contains `-------------Tests r Finished-------------`; exit 0 under `timeout 60` |
| obfusjack-test21.jar | `configs/native-obfusjack.yml` | stdout contains `=== All tests completed ===`; exit 0 under `timeout 60` |
| SnakeGame.jar | `configs/native-snake.yml` | GUI app — no stdout required; exit 0 under `timeout 5` (SIGKILL from timeout accepted) |

---

## 4. Baseline (main HEAD `01acd911`)

| Jar | Translated | Total | Pct |
|---|---:|---:|---:|
| TEST.jar | 11 | 89 | 12.4% |
| obfusjack-test21.jar | 12 | 101 | 11.9% |
| SnakeGame.jar | 5 | 26 | 19.2% |

`.so` size baseline: 44968 bytes on main.

**Pre-existing test failures**: 17 total (13 original + 4 newly exposed by Wave 4b-3) — NOT regressions. These 17 MUST appear identically in pre-obf AND post-obf runs of every applicable wave gate.

---

## 5. Wave Dependency Graph

```
[W0] Strict-no-JNI bootstrap refactor
  ├─ DD-1 boot CLD _klasses walk + Klass::_next_link
  ├─ DD-3 remove owner/static global refs
  ├─ DD-4 mirror/static-base via Klass::_java_mirror
  ├─ DD-5 Oracle 9 bootstrap throwable cache + post-bootstrap cached Throw
  └─ DD-6 bootstrap/bind/runtime JNI family deletion
      │
      ├──> [W1] Wave 4b-4a: LDC String GC root (Oracle 1 A2 survives, now consumes W0 DD-1 capture)
      │
      ├──> [W2] Zero-JVMTI + owner resolution redesign
      │        DD-2 holder-CLD walk replaces Oracle 2 `Class.forName(...)` path
      │
      ├──> [W3] Manifest/site cache convergence
      │        DD-3 removes `owner_class_slot`, `static_base_global_ref`, `static_base_slot`
      │
      ├──> [W4] Wave 4b-4b: naming cleanup + `_nosafepoint` fast path + teardown
      │
  ├──> [W5] Wave 4b-5: ATHROW + try-catch + first cached synthetic exception path
      │
      ├──> [W6] Wave 4c: arrays / CHECKCAST / INSTANCEOF / NPE / divzero / NEW
      │        heavy synthetic exception surface (NPE / AIOOBE / CCE / AE / NASE / OOME)
      │
      ├──> [W7] Wave 4d: INVOKE-ref via JavaCalls or strict replacement
      │        null-receiver synthetic NPE reopened through cached throwable dispatch
      │
      ├──> [W8] Wave 4e: VTABLE-INLINE devirtualization for INVOKEVIRTUAL / INVOKEINTERFACE
      │
      ├──> [W9] Wave 5: full SafetyChecker relaxation
      │        adds IMSE / BME / LinkageError synthetic paths where required
      │
      ├──> [W10] M4o gate: 100% admission except `<clinit>` / `<init>`
      │
      ├──> [W11] M5a-k hardening (10 items)
      │
      └──> [W12] M5k v1-complete: final smoke + behavior-equivalence gate
```

**Sequencing rationale**:

- W0 is mandatory. Without DD-1 / DD-3 / DD-4 / DD-6 landing first, W1-W9 would keep prescribing forbidden JNI paths.
- Oracle 1 survives unchanged in principle, but its bootstrap dependency is now W0 boot-CLD metadata capture instead of Oracle 3 JNI well-known class capture.
- Oracle 2 and Oracle 3 are context only. Oracle 4 is authoritative wherever they conflict.
- W2 and W3 are separated intentionally: W2 changes owner resolution semantics; W3 changes manifest/site layout and cache representation while preserving `MANIFEST_ENTRY_SIZE = 88`.
- W5-W9 explicitly track synthetic exception reopenings under m0117 as amended by Oracle 9. No post-bootstrap synthetic throw path may use anything beyond cached global `jthrowable` handles + `(*env)->Throw(...)`.

---

## 6. Wave-by-Wave Spec

### 6.0 — Canonical Behavior-Equivalence Gate (MUST apply unless wave explicitly overrides with tighter criteria)

Every wave’s **Verification** section below references this canonical gate. Deep B executes it verbatim. Any wave that weakens it must state the reason inline.

### 6.0.1 — Canonical paths + NEKO_DEBUG runtime model

**Obfuscation entry point**: driven by the `:neko-cli` `obfuscate` subcommand. The test helper `dev.nekoobfuscator.test.NativeObfuscationHelper` is the canonical way to populate post-obf fixtures for gates; the CLI is the canonical way to do it manually. There is NO `:neko-test:runNativeObfuscation` Gradle task.

**Manual obfuscate-one-jar sequence**:
```
./gradlew :neko-cli:installDist
neko-cli/build/install/neko-cli/bin/neko-cli obfuscate \
  -c configs/native-<stem>.yml \
  -i test-jars/<stem>.jar \
  -o neko-test/build/test-native/<stem>-native.jar
```
where `<stem>` ∈ {`test` for TEST.jar (config `native-test.yml`, output `TEST-native.jar`), `obfusjack` for obfusjack-test21.jar (config `native-obfusjack.yml`, output `obfusjack-native.jar`), `snake` for SnakeGame.jar (config `native-snake.yml`, output `SnakeGame-native.jar`)}.

**Batched-via-test-helper sequence** (preferred in CI/gate — populates all 3 fixtures in one run):
```
./gradlew :neko-test:test --tests 'dev.nekoobfuscator.test.NativeObfuscationIntegrationTest'
# NativeObfuscationHelper.ensureObfuscatedFixtures() writes:
#   neko-test/build/test-native/TEST-native.jar
#   neko-test/build/test-native/obfusjack-native.jar
#   neko-test/build/test-native/SnakeGame-native.jar
```

**Canonical post-obf artifact paths** (used by every GATE and Wn-letter runtime check unless stated otherwise):

| Source jar | Post-obf path |
|---|---|
| `test-jars/TEST.jar` | `neko-test/build/test-native/TEST-native.jar` |
| `test-jars/obfusjack-test21.jar` | `neko-test/build/test-native/obfusjack-native.jar` |
| `test-jars/SnakeGame.jar` | `neko-test/build/test-native/SnakeGame-native.jar` |

**Test module + package**: All tests live in module `:neko-test`, package `dev.nekoobfuscator.test`, directory `neko-test/src/test/java/dev/nekoobfuscator/test/`. Do NOT target `:neko-native:test` or `:neko-runtime:test` for plan gates.

**NEKO_DEBUG runtime model**: `NEKO_DEBUG_ENABLED` is a **C compile-time** macro baked into the `.so` by the native build when `-PnekoNativeDebug=true` Gradle property or `NEKO_NATIVE_BUILD_DEBUG=true` env var is set **at build time**. There is NO runtime env variable named `NEKO_DEBUG`.

To execute any Wn-letter trace-based gate:

1. Build debug CLI: `NEKO_NATIVE_BUILD_DEBUG=true ./gradlew :neko-cli:installDist -PnekoNativeDebug=true`
2. Re-obfuscate with that CLI.
3. Run post-obf jar: `java -Dneko.native.debug=true -jar neko-test/build/test-native/<stem>-native.jar`
4. Trace output appears on stderr when the `.so` was built with `NEKO_DEBUG_ENABLED=1`.

For gates that specify `-Dneko.native.debug=true`: this is a belt-and-suspenders runtime signal, redundant when the `.so` itself was built with `NEKO_DEBUG_ENABLED=1` but harmless. Release gates (GATE-10, W12) require the opposite: CLI built **without** `-PnekoNativeDebug=true` / `NEKO_NATIVE_BUILD_DEBUG`, producing a `.so` with `NEKO_DEBUG_ENABLED=0` and zero trace strings.

**GATE-1 — Build & Lint**
- Command: `./gradlew :neko-native:build`
- Pass: exit code `0`; no new compile warnings vs previous-wave baseline; `zig cc` inner build exits `0`.

**GATE-2 — Diagnostics**
- Command: `./gradlew :neko-test:test --tests 'dev.nekoobfuscator.test.*'`
- Pass: exit code `0`; test count ≥ previous wave.

**GATE-3 — Pre-obf baseline capture** (once per wave, reusable across jars)
- For jar in {`TEST.jar`, `obfusjack-test21.jar`, `SnakeGame.jar`}:
  - `timeout 60 java -jar test-jars/<jar>` (SnakeGame: `timeout 5`).
  - Capture stdout → `verification/<wave>/baseline/<jar>.pre.stdout`, stderr → `.pre.stderr`, exit → `.pre.exit`.

**GATE-4 — Post-obf capture**
- Obfuscate via `./gradlew :neko-test:test --tests 'dev.nekoobfuscator.test.NativeObfuscationIntegrationTest'` which triggers `NativeObfuscationHelper.ensureObfuscatedFixtures()` (`neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationHelper.java:35-46`) producing all 3 canonical post-obf jars (see §6.0.1). Manual CLI equivalent also in §6.0.1.
- Run per-jar from the canonical post-obf path (§6.0.1): `timeout 60 java -jar neko-test/build/test-native/TEST-native.jar` and `timeout 60 java -jar neko-test/build/test-native/obfusjack-native.jar`; SnakeGame uses `timeout 5 java -jar neko-test/build/test-native/SnakeGame-native.jar`.
- Capture stdout → `.post.stdout`, stderr → `.post.stderr`, exit → `.post.exit`.

**GATE-5 — Success-marker check (per jar)**
- TEST.jar: `grep -qF -- '-------------Tests r Finished-------------' .post.stdout` → exit `0`.
- obfusjack-test21.jar: `grep -qF -- '=== All tests completed ===' .post.stdout` → exit `0`.
- SnakeGame.jar: `.post.exit` ∈ {`0`, `124`} (124 = timeout SIGKILL, acceptable for GUI app).

**GATE-13A — Bootstrap-cache allowed JNI inventory**
- **Execution order**: run this gate after GATE-5 and before GATE-6 on every wave beginning at W0, while preserving legacy numbering of GATE-1..GATE-12.
- Symbol-surface command:
  - Platform-aware extraction (`bash` / `zsh`):
    ```bash
    case "$(uname -s)" in
      Linux*)   PLAT=linux;   EXT=.so    ;;
      Darwin*)  PLAT=macos;   EXT=.dylib ;;
      MINGW*|MSYS*|CYGWIN*|Windows*) PLAT=windows; EXT=.dll ;;
    esac
    case "$(uname -m)" in
      x86_64|amd64)    ARCH=x64     ;;
      aarch64|arm64)   ARCH=aarch64 ;;
    esac
    LIBNAME="neko/native/libneko_${PLAT}_${ARCH}${EXT}"
    unzip -p neko-test/build/test-native/TEST-native.jar "$LIBNAME" > /tmp/neko-gate13.bin
    ```
    Artifact layout per `NativeBuildEngine.java:78,112` + `NativeObfuscationHelper.java:253-260` = `neko/native/libneko_<platform>_<arch>.<ext>`. Supported artifacts: `libneko_linux_x64.so`, `libneko_linux_aarch64.so`, `libneko_macos_x64.dylib`, `libneko_macos_aarch64.dylib`, `libneko_windows_x64.dll`.
  - Symbol-surface inspection (pick by host OS):
    - Linux / macOS: `nm --defined-only /tmp/neko-gate13.bin | grep -E '^.* [TtDdBb] .*JNI_OnLoad$'`
    - Windows (MSYS/Cygwin w/ binutils): `nm --defined-only /tmp/neko-gate13.bin | grep -E 'JNI_OnLoad'`
    - Windows (LLVM): `llvm-nm --defined-only /tmp/neko-gate13.bin | grep 'JNI_OnLoad'`
    - Windows (MSVC toolchain): `dumpbin /EXPORTS /tmp/neko-gate13.bin | grep JNI_OnLoad`
  - Pass: exactly **1** entry returned.
- Full JNI-reference inventory command:
  - `grep -oE '\(\*(vm|env)\)->[A-Za-z_][A-Za-z0-9_]*' <generated C source> | sort | uniq -c`
  - Pass values: exactly one `(*vm)->GetEnv`; bootstrap-cache helper calls may include only `(*env)->FindClass`, `GetMethodID`, `NewStringUTF`, `NewObjectA`, `NewGlobalRef`, and `DeleteLocalRef`; `(*env)->Throw` count follows wave-specific synthetic exception expectations below.
- Bootstrap location policy:
  - `(*vm)->GetEnv` appears exactly once, inside `JNI_OnLoad`.
  - `(*env)->FindClass`, `GetMethodID`, `NewStringUTF`, `NewObjectA`, `NewGlobalRef`, and `DeleteLocalRef` appear only in `neko_make_global_throwable`, `neko_init_throwable_cache`, or immediately adjacent `JNI_OnLoad` bootstrap-cache code.
  - `ThrowNew`, `ExceptionOccurred`, `ExceptionCheck`, `ExceptionClear`, `AllocObject`, `Call*Method`, `GetStatic*`, `SetStatic*`, `NewWeakGlobalRef`, and `DeleteGlobalRef` remain forbidden unless separately approved.
- Build-time automation requirement:
  - Add a dedicated verification script invoked during build and by every wave gate report.
  - Script output must be archived at `verification/<wave>/jni-surface.txt`.

**GATE-13B — Post-bootstrap strict JNI surface verification**
- Forbidden post-bootstrap reference command:
  - `grep -oE '\(\*(vm|env)\)->[A-Za-z_][A-Za-z0-9_]*' <generated C source> | grep -vE '^\(\*vm\)->GetEnv$|^\(\*env\)->(FindClass|GetMethodID|NewStringUTF|NewObjectA|NewGlobalRef|DeleteLocalRef|Throw)$'`
  - Pass: empty, then manually verify the bootstrap-cache-only methods listed in GATE-13A do not occur outside the cache helpers / `JNI_OnLoad`.
- Wave-specific `Throw` expectations:
  - W0-W4: `Throw=0` unless the bootstrap loader guard or Oracle 9 cache dispatch has already landed; no post-bootstrap construction helpers allowed.
  - W5: `Throw>=1` using cached `g_neko_throw_npe`.
  - W6-W8: `Throw>=5` using cached NPE/AIOOBE/CCE/AE/OOM/NASE handles.
  - W9-W12: `Throw>=10` using cached IMSE/ASE/BME/LinkageError handles as needed.
- Build-time automation requirement:
  - Add a dedicated verification script invoked during build and by every wave gate report.
  - Script output must be archived at `verification/<wave>/jni-surface.txt`.

**GATE-6 — Behavior equivalence (per jar, stdout)**
- `diff .pre.stdout .post.stdout` → empty OR only lines matching the allowed fixture list below.
- **Allowed fixture exceptions**:
  - TEST.jar Test 2.8 `Sec ERROR` cosmetic (§2 invariant).
  - 17 pre-existing failures (§4 baseline) — must appear identically in both pre and post outputs.
  - SnakeGame.jar: stdout excluded from diff (GUI, no stdout expected).

**GATE-7 — Behavior equivalence (per jar, stderr)**
- `diff .pre.stderr .post.stderr` → empty OR only lines matching allowed fixture list above.
- SnakeGame.jar: stderr excluded from diff (GUI timing).

**GATE-8 — Exit code equivalence (per jar)**
- `.pre.exit` == `.post.exit` on TEST.jar and obfusjack-test21.jar.
- SnakeGame.jar: `.post.exit` ∈ {`0`, `124`}.

**GATE-9 — Admission count (per jar, no regression)**
- Command: `grep -cE '^neko_impl_[0-9]+' neko-native/build/native-src/generated/neko_impl.c`
- Pass: count ≥ previous wave’s count. Per-wave additive expectations stated per wave.

**GATE-10 — Symbol surface (release `.so`)**
- Build release: `./gradlew :neko-native:build -PnekoDebug=false`.
- Command: `nm --defined-only /tmp/neko-gate13.bin | grep -Ei 'jvmti|__nekoStringRoots|NEKO_DEBUG'` → empty. (On Windows substitute `llvm-nm --defined-only` or `dumpbin /EXPORTS`; the `/tmp/neko-gate13.bin` artifact is produced by GATE-13's platform-aware extraction step.)
- Command: `strings /tmp/neko-gate13.bin | grep -E '^\[NEKO\]|^neko_trace_'` → empty. (On Windows substitute Sysinternals `strings.exe` or `llvm-objdump -s | grep`.)

**GATE-11 — GC matrix (6-GC matrix, canonical across plan)**
- Collectors: `{Serial, Parallel, G1, ZGC, Shenandoah, Epsilon}` — exactly 6.
- Flags:
  - Serial: `-XX:+UseSerialGC`
  - Parallel: `-XX:+UseParallelGC`
  - G1: `-XX:+UseG1GC`
  - ZGC: `-XX:+UseZGC`
  - Shenandoah: `-XX:+UseShenandoahGC`
  - Epsilon: `-XX:+UseEpsilonGC -XX:+UnlockExperimentalVMOptions`
- Pass: each jar × each collector passes GATE-5 + GATE-8. Full matrix = 3 jars × 6 collectors = 18 runs.
- **Wave applicability**: W0/W1/W2/W5/W6/W7/W9/W10/W12 MUST execute full GC matrix. W3/W4/W8 MAY execute G1 + ZGC subset (6 runs). W11-M5c executes full matrix as its deliverable.
- **JDK × GC availability** (for JDK 8-21 compatibility per user directive m0141):
  - **JDK 8** (Serial / Parallel / G1 only; no stock ZGC, no stock Shenandoah, no Epsilon): run matrix is 3 jars × 3 collectors = **9 runs**.
  - **JDK 11** (Serial / Parallel / G1 / ZGC experimental / Epsilon experimental; Shenandoah only in vendor-patched builds): run matrix is 3 jars × 5 collectors = **15 runs** with stock OpenJDK 11; full 18 runs only if vendor Shenandoah is present.
  - **JDK 17** (all 6 collectors stable): full 3 jars × 6 collectors = **18 runs**.
  - **JDK 21** (all 6 collectors stable; ZGC generational): full 3 jars × 6 collectors = **18 runs**.
  - CI matrix: waves in the "full matrix" applicability class execute their maximum available run count for each JDK being tested; waves in subset-applicability class execute `G1 + ZGC` (G1 only for JDK 8).
  - A wave passes GATE-11 on a given JDK iff every run in the JDK-applicable subset passes GATE-5 + GATE-8.

**GATE-12 — Stress loop**
- Command: each jar run 10× back-to-back under `-XX:+UseG1GC`.
- Pass: all 10 runs exit per GATE-5/8; no crash; no intermittent `Sec ERROR` beyond Test 2.8.

**Artifacts layout** (per wave):
```
verification/<wave-id>/
├── baseline/*.pre.{stdout,stderr,exit}
├── obfuscated/*.post.{stdout,stderr,exit}
├── diff/*.{stdout,stderr}.diff
├── admission-count.txt
├── jni-surface.txt
├── symbol-surface.txt
├── gc-matrix.txt
└── stress-loop.txt
```

Deep B’s verification report MUST include paths to all artifacts above.

### 6.0.2 — Synthetic exception construction sequence (DD-5 Oracle 9)

**Premise (m0117 / DD-5 amended by Oracle 9)**:

Synthetic exception dispatch after `JNI_OnLoad` may use exactly one JNI call family: `(*env)->Throw(env, jthrowable)`. Synthetic exception objects are preconstructed during `JNI_OnLoad` only, using a narrowly allowed bootstrap JNI cache builder (`FindClass`, `GetMethodID`, `NewStringUTF`, `NewObjectA`, `NewGlobalRef`, `DeleteLocalRef`). After `JNI_OnLoad` returns, no JNI construction, lookup, global-ref creation, exception query/clear, or `ThrowNew` calls are permitted. All post-bootstrap synthetic throws must pass cached global `jthrowable` handles to `Throw`.

**Preconditions (all set during `JNI_OnLoad` bootstrap cache init)**:

- `g_neko_throw_npe` — global `NullPointerException` handle
- `g_neko_throw_aioobe` — global `ArrayIndexOutOfBoundsException` handle
- `g_neko_throw_cce` — global `ClassCastException` handle
- `g_neko_throw_ae` — global `ArithmeticException` handle
- `g_neko_throw_le` — global `LinkageError` handle
- `g_neko_throw_oom` — global `OutOfMemoryError` handle
- `g_neko_throw_imse` — global `IllegalMonitorStateException` handle
- `g_neko_throw_ase` — global `ArrayStoreException` handle
- `g_neko_throw_nase` — global `NegativeArraySizeException` handle
- `g_neko_throw_bme` — global `BootstrapMethodError` handle
- `g_neko_throw_loader_linkage` — global loader-specific `LinkageError` handle
- `klass_exc_*` metadata may remain for type logic/diagnostics, but is no longer the synthetic allocation authority.

**Sequence for each synthetic-exception site**:

1. Bootstrap: `JNI_OnLoad` creates the global `jthrowable` cache with normal JNI construction.
2. Runtime: choose the cached throwable for the synthetic exception kind.
3. Runtime: call `(*env)->Throw(env, cached_global_jthrowable)`.
4. Runtime: return the default value for the translated function's return type.

**Native return convention immediately after `Throw`**:

- `void`-return translated function: `return;`
- primitive-return translated function: `return 0;`
- reference-return translated function: `return NULL;`
- dispatcher/stub side must treat this as exceptional exit and must not consume the return register as a successful value.

**Non-goals after bootstrap**:

- No constructor invocation through JNI.
- No `ThrowNew`.
- No `ExceptionOccurred`, `ExceptionClear`, `ExceptionCheck`.
- No `FindClass`, `GetMethodID`, `NewObject*`, `AllocObject`, `NewGlobalRef`, or exception query/clear outside the bootstrap cache window.
- No `InstanceKlass::allocate_instance(Thread*)` / raw `Throwable` field initialization dependency.

---

### W0 — Strict-no-JNI bootstrap refactor (NEW, prerequisite to all W1+)

**Goal**: Replace all existing bootstrap JNI function-table calls with pure VMStructs + `dlsym` paths. After W0, the generated `.so` has exactly the allowed JNI surface: `JNI_OnLoad`, one `(*vm)->GetEnv`, zero `(*env)->Throw` until W5 opens synthetic exceptions.

**Oracle design (authoritative)**: `worktree/dev-impl/.sisyphus/plans/oracle-4-strict-no-jni.md`.

**Reference docs**:

- Oracle 1 survives for string root cells: `oracle-1-ldc-string-gc-root.md`
- sibling reference: `jni-free-native-obfuscation.md`
- context only: `oracle-2-zero-jvmti.md`, `oracle-3-wellknown-class-capture.md`
- audit map: `explore-jni-audit-option-a.md`

**Deliverables**:

1. Add VMStructs offsets to `NekoVmLayout`:
   - `off_klass_next_link`
   - `off_klass_name`
   - `off_symbol_length`
   - `off_symbol_body`
   - `off_cld_klasses`
2. Add `neko_resolve_libjvm_symbol(name)` — `dlopen("libjvm.so")` / platform equivalent + `dlsym` wrapper with cached pointers.
3. Implement `neko_walk_boot_cld_klasses(jvm_walker)` — traverses boot CLD `_klasses` linked list via `Klass::_next_link`, yielding `Klass*` + Symbol bytes.
4. Implement `neko_find_klass_by_name_in_cld(cld, utf8_name, utf8_len)` — CLD-scoped lookup by raw Symbol bytes.
5. Implement DD-1 well-known class capture:
   - replace `FindClass("java/lang/String")`, `FindClass("[B")`, `FindClass("[C")`
   - cache into `g_neko_vm_layout.klass_java_lang_String`, `klass_array_byte`, `klass_array_char`
   - **fail fast if `Klass::_next_link` offset is -1**
6. Implement DD-1 exception Klass* pre-cache:
   - `klass_exc_npe`
   - `klass_exc_aioobe`
   - `klass_exc_cce`
   - `klass_exc_ae`
   - `klass_exc_le`
   - `klass_exc_oom`
   - `klass_exc_imse`
   - `klass_exc_bme`
   - `klass_exc_nase`
7. Implement DD-3 global-ref elimination:
   - remove `NewGlobalRef` / `DeleteGlobalRef`
   - remove owner-class globals
   - keep boot-CLD root cells only for Oracle 1 true long-lived oop anchors
8. Implement DD-4 field/static-base replacement:
   - replace `GetStaticObjectField`, `GetStaticLongField`, `SetStaticObjectField`, and siblings with raw offset reads/writes via `Klass::_java_mirror`
   - honor JDK 9+ OopHandle double-deref
9. Replace `neko_mark_loader_loaded` JNI field access with raw mirror read + raw offset write while preserving `NekoNativeLoader` shape.
10. Initialize the Oracle 9 bootstrap throwable cache in `JNI_OnLoad`.
    - create global `jthrowable` handles for NPE/AIOOBE/CCE/AE/LE/OOM/IMSE/ASE/NASE/BME and loader-linkage errors
    - do not resolve `InstanceKlass::allocate_instance(Thread*)`; it is intentionally not required
11. Keep any optional `JavaThread::current()` resolver decoupled from synthetic exception allocation and require separate approval before use.
12. Remove all other `env->*` function-table calls across emitters, wrappers, and translator output. `explore-jni-audit-option-a.md` is the authoritative audit list.

**MUST DO**:

- Fail fast on unavailable `Klass::_next_link` VMStructs exposure. Do NOT silently fall back.
- Preserve existing Java-layer behavior. `NekoNativeLoader` shape remains `{loaded, LOCK}` only.
- Preserve `MANIFEST_ENTRY_SIZE = 88` while W3 prepares layout cleanup. Any field removal required before W3 must be offset-neutral via padding or equivalent.
- Make GATE-13 pass immediately after W0: exactly one `GetEnv`, zero `Throw`, no other JNI references.
- Preserve Oracle 1 boot-CLD root-cell model for true long-lived oop anchors.
- Keep zero-JVMTI direction intact; W0 may delete JVMTI-adjacent helpers if they are obviously dead under strict Option A, but W2 remains the formal zero-JVMTI cleanup wave.

**MUST NOT DO**:

- Add new Java classes.
- Add new JVMTI usage.
- Use `GetStatic*Field`, `SetStatic*Field`, `CallStatic*Method`, `ThrowNew`, `ExceptionOccurred`, `ExceptionCheck`, `ExceptionClear`, or any other post-bootstrap JNI function-table call outside the Oracle 9 bootstrap throwable-cache allowance.
- Reintroduce `__nekoStringRoots` or any Java-side GC-root field.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1..GATE-13 in full. GATE-13 is mandatory for W0 and must report `GetEnv=1`, `Throw=0`.
- **Wave-specific additions**:
  - W0-A: `grep -rn 'off_klass_next_link' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/` → ≥ 1 hit.
  - W0-B: `grep -rnE 'off_klass_name|off_symbol_length|off_symbol_body|off_cld_klasses' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/` → ≥ 4 hits total.
  - W0-C: `grep -rn 'neko_resolve_libjvm_symbol' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/` → ≥ 1 hit.
  - W0-D: `grep -rnE 'neko_walk_boot_cld_klasses|neko_find_klass_by_name_in_cld' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/` → ≥ 2 hits total.
  - W0-E: `grep -rnE 'klass_java_lang_String|klass_array_byte|klass_array_char' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/` → ≥ 3 hits total.
  - W0-F: `grep -rnE 'klass_exc_npe|klass_exc_aioobe|klass_exc_cce|klass_exc_ae|klass_exc_le|klass_exc_oom|klass_exc_imse|klass_exc_bme|klass_exc_nase' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/` → ≥ 9 hits total.
  - W0-G: `grep -rnE 'NewGlobalRef|DeleteGlobalRef' neko-native/build/native-src/generated/` → empty.
  - W0-H: `grep -rnE 'GetStatic[A-Za-z]+Field|SetStatic[A-Za-z]+Field' neko-native/build/native-src/generated/` → empty.
  - W0-I: `grep -rn 'neko_mark_loader_loaded' neko-native/build/native-src/generated/` → ≥ 1 hit and no adjacent JNI field-access helpers remain.
  - W0-J: `grep -rnE 'neko_init_throwable_cache|g_neko_throw_npe|g_neko_throw_loader_linkage' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/` → ≥ 3 hits total; `grep -rnE 'InstanceKlass17allocate_instance|dlsym_allocate_instance|g_neko_allocate_instance_fn' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/` → empty.
  - W0-K: `grep -rnE '\(\*env\)->|\(\*vm\)->' neko-native/build/native-src/generated/ | grep -v '\(\*vm\)->GetEnv'` → empty.
  - W0-L: debug-built `.so` per §6.0.1 + `java -Dneko.native.debug=true -jar neko-test/build/test-native/TEST-native.jar 2>&1 | grep 'strict_vm_layout_ok=1'` → exit `0`; any `next_link=-1` trace is a fail.

**Patch archive**: `tmp/refactor-patches/w0-strict-no-jni-bootstrap.patch`.

---

### W1 — Wave 4b-4a: LDC String GC root (Oracle 1 design A2 survives)

**Goal**: Make `neko_ldc_string_site_oop()` return the interned `String` for pure-LDC sites using a manifest-preallocated boot `ClassLoaderData::_handles` cell pool as the GC root, with zero Java-layer hooks and zero JNI beyond the W0 allowlist.

**Oracle design (authoritative)**: `worktree/dev-impl/.sisyphus/plans/oracle-1-ldc-string-gc-root.md`.

**W0 dependency**:

- `klass_java_lang_String`, `klass_array_byte`, and `klass_array_char` are resolved in W0 by DD-1 boot CLD `_klasses` walk.
- W1 does **not** capture these classes itself.
- Oracle 3 bootstrap JNI capture is superseded and may only be retained as historical context.

**Deliverables**:

1. Remove `off_loader_string_roots` from `NekoVmLayout`.
2. Keep Oracle 1 A2 boot-CLD handle-chain cell pool design exactly.
3. Add `root_cell` to `NekoStringInternEntry` at a stable offset.
4. Bootstrap assigns one `root_cell` per unique literal.
5. Replace resolver write path with `root_cell` store.
6. Replace resolver read path with `root_cell` load.
7. Delete remaining `__nekoStringRoots` references.
8. Preserve Candidate E fallback only for root-chain publish/self-check failure after DD-1 class capture succeeded.
9. Keep all existing W1-A..W1-G gates.

**MUST DO**:

- Handle JDK 8 vs JDK 11/17/21 `off_handles` derivation exactly as Oracle 1 A2 specified.
- Use GC-barrier-aware cell load/store helpers.
- Respect W0 strict-no-JNI surface; no fallback to Oracle 3 capture path.
- Preserve `NEKO_DEBUG_ENABLED` guard on all trace output.

**MUST NOT DO**:

- Add any Java field or class.
- Reintroduce `__nekoStringRoots`.
- Cache raw `oop` long-term instead of `root_cell`.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1..GATE-13 in full; GC matrix per GATE-11 applicability required (JDK 8: 9 runs, JDK 11: 15 runs, JDK 17/21: 18 runs).
- **Wave-specific additions**:
  - W1-A: `grep -n 'off_loader_string_roots' neko-native/build/native-src/generated/` → empty.
  - W1-B: `grep -n 'off_cld_handles' neko-native/build/native-src/generated/` → ≥ 1 hit.
  - W1-C: `grep -nE '__nekoStringRoots' neko-native/build/native-src/generated/ neko-native/src/main/java/` → empty.
  - W1-D: extract `.so` per §6.0 GATE-13, then `nm --defined-only /tmp/neko-gate13.bin | grep -c 'nekoStringRoots'` → `0` (Windows host: `llvm-nm` or `dumpbin /exports` on the `.dll` artifact).
  - W1-E: debug-built `.so` per §6.0.1 + `java -Dneko.native.debug=true -jar neko-test/build/test-native/TEST-native.jar 2>&1 | grep 'boot_cld_root_chain_ok=1'` → exit `0`.
  - W1-F: for each of the 3 post-obf jars, `java -Dneko.native.debug=true -jar <post-obf-jar> 2>&1 | grep 'neko_ldc_string_site_oop' | grep -v 'NULL' | wc -l` → `≥ 1`.
  - W1-G: Admission count no regression: TEST.jar ≥ 11, obfusjack-test21 ≥ 12, SnakeGame ≥ 5.

**Patch archive**: `tmp/refactor-patches/w1-ldc-string-gc-root.patch`.

---

### W2 — Zero-JVMTI + owner resolution redesign (DD-2)

**Goal**: Remove ALL JVMTI usage and replace residual JNI class-lookup logic with DD-2 holder-CLD owner resolution. Oracle 2 Design A is superseded by Oracle 4 DD-2: no `Class.forName(...)`, no `owner_class_slot`-driven runtime semantics.

**Oracle design (authoritative)**: `worktree/dev-impl/.sisyphus/plans/oracle-4-strict-no-jni.md` DD-2 + DD-6.

**Deliverables**:

1. Delete JVMTI infrastructure from `BootstrapEmitter.java` (17 call sites from `explore-jvmti-inventory.md`).
2. Delete `#include <jvmti.h>` from generated source.
3. Delete `GetEnv(JVMTI_VERSION_1_2)` and `g_neko_jvmti` usage.
4. Restructure `JNI_OnLoad` around strict ordering:
   - `g_neko_java_vm = vm`
   - `GetEnv(JNI_VERSION_1_6)`
   - `neko_resolve_vm_symbols()`
   - `neko_parse_vm_layout_strict()`
   5. `neko_capture_wellknown_klasses()` (W0 DD-1 CLD walk — populates `klass_java_lang_String`, `klass_array_byte`, `klass_array_char`, `klass_neko_native_loader`, and exception class slots `klass_exc_npe`, `klass_exc_aioobe`, `klass_exc_cce`, `klass_exc_ae`, `klass_exc_le`, `klass_exc_oom`, `klass_exc_imse`, `klass_exc_bme`, `klass_exc_nase`; fail-fast if `Klass::_next_link` offset is unavailable on the target JDK per Oracle 4 guidance)
   6. `neko_mark_loader_loaded(env)` (uses `klass_neko_native_loader` Klass* from step 5 + `off_loader_loaded_field` from the parsed layout to write `loaded = true` via raw mirror oop store; MUST execute after `neko_parse_vm_layout_strict()` AND `neko_capture_wellknown_klasses()`; Oracle 4 anchor: `oracle-4-strict-no-jni.md:660-664`; the previous pre-layout ordering in v5 is obsolete)
   7. `neko_string_intern_prewarm_and_publish()` (W1 — consumes `klass_java_lang_String` + `klass_array_byte` + `klass_array_char` from step 5 to publish ChunkedHandleList root cells into boot CLD `_handles._head`; emits `boot_cld_root_chain_ok=1` trace on success)
   8. `neko_bootstrap_owner_discovery()` (W2 DD-2 — preseeds `site->cached_klass` for boot-visible manifest owners via CLD scan; idempotent; runtime miss path handled via on-demand holder-CLD walk with CAS)
   9. `return JNI_VERSION_1_6`
5. Introduce side arrays:
   - `g_neko_manifest_method_holder_klass[method_index]`
   - `g_neko_manifest_method_holder_cld[method_index]`
6. Bootstrap preseed `site->cached_klass` for boot-visible owners by CLD scan.
7. Runtime miss path uses enclosing translated method holder CLD, optional parent-loader chain, then boot CLD, then `CAS(NULL -> Klass*)`.
8. Remove `Class.forName(...)` generated C usage entirely.
9. Replace manifest lock implementation with non-JVMTI platform lock (`pthread_mutex` / `CRITICAL_SECTION`).
10. Standardize `cached_klass` publication on acquire-load + CAS.

**MUST DO**:

- Preserve `JNI_OnLoad` entry point.
- Keep `owner_class_slot` structural removal for W3; W2 may stop reading/populating it ahead of W3, but W3 owns manifest-layout convergence.
- Use strict metadata-only CLD/loader walk. No system dictionary walk, no HotSpot internal resolver symbol shortcut.
- Add a gate confirming generated C source contains no `Class.forName` and no `jvmti` strings.

**MUST NOT DO**:

- Reintroduce JNI class lookup helpers.
- Keep a dead `Class.forName` slow-path wrapper around under a feature flag.
- Leave `jvmtiCapabilities`, `jvmtiEventCallbacks`, or `JVMTI_VERSION_1_2` in source.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1..GATE-13 in full; GC matrix per GATE-11 applicability (JDK 8: 9 runs, JDK 11: 15 runs, JDK 17/21: 18 runs).
- **Wave-specific additions**:
  - W2-A: `grep -rnE 'jvmti|JVMTI_|jvmtiEnv|jvmtiCapabilities|jvmtiEventCallbacks' neko-native/build/native-src/generated/` → empty.
  - W2-B: `grep -n '#include <jvmti.h>' neko-native/build/native-src/generated/` → empty.
  - W2-C: extract per §6.0 GATE-13, then `nm --defined-only /tmp/neko-gate13.bin | grep -Ei 'jvmti'` → empty (Windows host: `llvm-nm` or `dumpbin /exports`).
  - W2-D: `grep -rn 'Class\.forName' neko-native/build/native-src/generated/` → empty.
  - W2-E: `grep -rnE 'g_neko_manifest_method_holder_klass|g_neko_manifest_method_holder_cld' neko-native/build/native-src/generated/` → ≥ 2 hits total.
  - W2-F: `grep -rn 'neko_site_owner_klass_nosafepoint' neko-native/build/native-src/generated/` → ≥ 1 hit.
  - W2-G: concurrent resolve race harness in `:neko-test` passes and converges to one `Klass*` winner.
  - W2-H: Admission count no regression from W1 end state.

**Patch archive**: `tmp/refactor-patches/w2-zero-jvmti-dd2.patch`.

---

### W3 — Manifest/site cache convergence (DD-3 replacement of v5 W3)

**Goal**: Remove `owner_class_slot`, `static_base_global_ref`, and `static_base_slot` from active per-site semantics. Per-site caching converges on direct `Klass*` via `cached_klass`; static bases are derived on demand from `Klass::_java_mirror`. This wave **reverses v5 W3**.

**Deliverables**:

1. `NekoManifestFieldSite` drops active fields:
   - `jclass *owner_class_slot`
   - `jobject static_base_global_ref`
   - `void *volatile *static_base_slot`
2. `NekoManifestLdcSite` drops active `owner_class_slot` for class-LDC sites.
3. Introduce/read through `neko_site_owner_klass(site)` returning `Klass*` directly.
4. Replace all readers of owner/static state with `cached_klass` + on-demand mirror/static-base helpers.
5. Preserve `MANIFEST_ENTRY_SIZE = 88` by padding/alignment management unless a deliberate new value is justified and all offsets/asserts are updated. **v6 default is preserve 88.**
6. Update manifest comments to note removed fields without keeping them active.
7. Remove any remaining `NewGlobalRef` / `DeleteGlobalRef` references from generated manifest paths.

**MUST DO**:

- Preserve `_Static_assert(sizeof(NekoManifestMethod) == 88)` unless an explicit all-offset rewrite is approved; v6 plans to keep 88.
- Make `owner_class_slot` removal visible and final. No runtime fallback path may still depend on it.
- Use padding if needed rather than silently shrinking `NekoManifestMethod` and breaking existing offsets.

**MUST NOT DO**:

- Keep `owner_class_slot` active “just in case”.
- Reintroduce global refs for static bases.
- Change manifest offsets without updating every dependent constant/assert.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1, GATE-2, GATE-5, GATE-13, GATE-8, GATE-9, GATE-12. GC matrix subset: G1 + ZGC only.
- **Wave-specific additions**:
  - W3-A: `./gradlew :neko-test:test --tests 'dev.nekoobfuscator.test.ManifestEmitterTest.testManifestEntrySize88'` → exit `0`.
  - W3-B: `grep -rn 'owner_class_slot' neko-native/build/native-src/generated/` → hits allowed only in removal comments or migration markers, NOT active struct fields.
  - W3-C: `grep -rnE 'NewGlobalRef|DeleteGlobalRef' neko-native/build/native-src/generated/` → empty.
  - W3-D: `grep -rn 'neko_site_owner_klass' neko-native/build/native-src/generated/` → ≥ 1 hit per relevant site kind.
  - W3-E: `grep -rnE 'static_base_global_ref|static_base_slot' neko-native/build/native-src/generated/` → empty except comments.
  - W3-F: Admission count no regression vs W2 end state.

**Patch archive**: `tmp/refactor-patches/w3-dd3-manifest-converge.patch`.

---

### W4 — Wave 4b-4b: naming cleanup + `_nosafepoint` fast path + teardown

**Goal**: Finalize naming cleanup, wire `_nosafepoint` variants into hot paths, add oop-handle round-trip harness, and define teardown without reopening JNI.

**Deliverables**:

1. Rename deferred helpers per TODO.
2. Wire `neko_rt_mirror_from_klass_nosafepoint`, `neko_rt_static_base_from_holder_nosafepoint`, `neko_rt_try_alloc_instance_fast_nosafepoint`, and `neko_rt_try_alloc_array_fast_nosafepoint` into hot paths.
3. Add `neko_handle_oop(raw_oop) → jobject` inverse harness test while ensuring it is not used as a reopened general JNI bridge.
4. Define `neko_manifest_teardown()` and `JNI_OnUnload` cleanup for non-JNI resources.
5. Audit `_nosafepoint` helpers for hidden JNI or implicit safepoint entry.

**Strict-no-JNI audit note**:

- `neko_rt_mirror_from_klass_nosafepoint` and `neko_rt_static_base_from_holder_nosafepoint` remain valid under DD-4.
- Any helper that still consults `JNIEnv*` outside W0/W5+ approved sites is a blocker.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1, GATE-2, GATE-5, GATE-13, GATE-8, GATE-9, GATE-12. GC matrix subset: G1 + ZGC only.
- **Wave-specific additions**:
  - W4-A: `grep -cE 'neko_rt_(mirror|static_base|try_alloc_instance|try_alloc_array)_fast_nosafepoint\(' neko-native/build/native-src/generated/` → `≥ 4`.
  - W4-B: `./gradlew :neko-test:test --tests 'dev.nekoobfuscator.test.HandleOopRoundTripTest'` → exit `0`.
  - W4-C: `grep -n 'neko_manifest_teardown' neko-native/build/native-src/generated/` → ≥ 1 hit.
  - W4-D: `grep -n 'JNI_OnUnload' neko-native/build/native-src/generated/` → ≥ 1 hit.
  - W4-E: GATE-13 still reports `GetEnv=1`, `Throw=0`, no extra JNI.

**Patch archive**: `tmp/refactor-patches/w4-4b-4b-polish.patch`.

---

### W5 — Wave 4b-5: ATHROW + try-catch via raw pending-exception + cached synthetic throw reopen

**Goal**: Admit ATHROW in SafetyChecker, keep existing-oop `ATHROW` on raw pending-exception path, and open the first cached synthetic exception path under amended m0117 for null-ATHROW and any equivalent mandatory synthetic bridge case.

**Exception class cache slots used in W5**:

- `g_neko_throw_npe` — null `ATHROW`
- existing-oop path uses raw pending-exception write and does **not** allocate

**Deliverables**:

1. `NativeTranslationSafetyChecker.java`: remove ATHROW rejection.
2. `OpcodeTranslator.java`: use strict raw pending-exception path for non-null exception oop.
3. Add cached synthetic path for null `ATHROW` → `g_neko_throw_npe`.
4. Wire try-catch unwinding via existing `neko_rt_ctx_init` + scope close.
5. Preserve stack-trace/behavior equivalence; no fake constructor JNI path.
6. Ensure first post-W0 `(*env)->Throw` sites land here and are archived by GATE-13.

**MUST DO**:

- Distinguish clearly between existing-oop throw and synthetic throw.
- Use cached `g_neko_throw_npe` for synthetic `NullPointerException`.
- Return immediately after `Throw` according to §6.0.2.

**MUST NOT DO**:

- Use `ThrowNew`.
- Use `ExceptionOccurred` / `ExceptionClear` / `ExceptionCheck`.
- Invent a manual raw `Throwable` constructor protocol or any post-bootstrap construction path.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1..GATE-13 in full; GC matrix per GATE-11 applicability (JDK 8: 9 runs, JDK 11: 15 runs, JDK 17/21: 18 runs).
- **Wave-specific additions**:
  - W5-A: Admission delta: ATHROW admitted-site count ≥ 1 on TEST.jar and ≥ 1 on obfusjack-test21.jar.
  - W5-B: TEST.jar exception sub-tests diff empty except permitted `Sec ERROR` cosmetic line.
  - W5-C: null-ATHROW synthetic test throws `NullPointerException` equivalently.
  - W5-D: `grep -oE '\(\*env\)->Throw' <generated C source> | wc -l` → `≥ 1`.
  - W5-E: GATE-13 reports exactly one `GetEnv` plus `Throw` count `N ≥ 1` and no other JNI references.

**Patch archive**: `tmp/refactor-patches/w5-athrow-option1.patch`.

---

### W6 — Wave 4c: arrays / CHECKCAST / INSTANCEOF / NPE / divzero / NEW under cached throws

**Goal**: Flip SafetyChecker admission for arrays, type checks, `NEW*`, `ARRAYLENGTH`, and divide-by-zero ops. Emit paths already exist broadly; this wave opens them under strict no-JNI with cached synthetic exceptions.

**Synthetic exception matrix for W6**:

| Opcode family | Synthetic exception | Cache slot |
|---|---|---|
| null receiver / null array (`GETFIELD`, `PUTFIELD`, `ARRAYLENGTH`, `AALOAD`, `AASTORE`, primitive `*ALOAD`, primitive `*ASTORE`) | `NullPointerException` | `g_neko_throw_npe` |
| out-of-bounds array load/store | `ArrayIndexOutOfBoundsException` | `g_neko_throw_aioobe` |
| `CHECKCAST` miss | `ClassCastException` | `g_neko_throw_cce` |
| `IDIV`, `IREM`, `LDIV`, `LREM` by zero | `ArithmeticException` | `g_neko_throw_ae` |
| `NEW`, `ANEWARRAY`, `NEWARRAY`, `MULTIANEWARRAY` allocation failure | `OutOfMemoryError` | `g_neko_throw_oom` |
| negative `NEWARRAY` / `ANEWARRAY` / `MULTIANEWARRAY` dimension | `NegativeArraySizeException` | `g_neko_throw_nase` |

**Deliverables**:

1. Remove SafetyChecker “deferred beyond Wave 2” rejects for arrays / type checks / `NEW*` / divide-by-zero ops.
2. Replace any remaining array JNI wrappers with raw runtime helpers.
3. Add explicit synthetic NPE path for null array/receiver cases.
4. Add explicit synthetic AIOOBE path for bounds violations.
5. Add explicit synthetic CCE path for `CHECKCAST` failure.
6. Add explicit synthetic AE path for divide-by-zero.
7. Add explicit synthetic OOME path for failed allocation.
8. Add explicit synthetic NASE path for negative array length.
9. Ensure GATE-13 `Throw` count grows to at least 5 distinct sites.

**MUST DO**:

- Keep `INSTANCEOF` non-throwing and return `0/1` per JVM semantics.
- Use `_nosafepoint` alloc paths from W4 first, then cached `g_neko_throw_oom` when allocation fails.
- Keep array/type operations GC-safe and barrier-aware.

**MUST NOT DO**:

- Permit silent null dereference or hardware trap fallback.
- Skip `NegativeArraySizeException` for negative dimensions.
- Reopen reference `AASTORE` without the required barrier story if W11-M5h has not landed yet; if W6 admits it, it must already be barrier-correct for the active collector subset.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1..GATE-13 in full; GC matrix per GATE-11 applicability (JDK 8: 9 runs, JDK 11: 15 runs, JDK 17/21: 18 runs).
- **Wave-specific additions**:
  - W6-A: Admission jump: TEST.jar delta ≥ 5, obfusjack-test21 delta ≥ 5, SnakeGame delta ≥ 3 vs W5.
  - W6-B: `./gradlew :neko-test:test --tests '*NativeObfArrayNpeTest*'` → exit `0`.
  - W6-C: `./gradlew :neko-test:test --tests '*NativeObfCheckCastTest*'` → exit `0`.
  - W6-D: `./gradlew :neko-test:test --tests '*NativeObfAioobeTest*'` → exit `0`.
  - W6-E: `./gradlew :neko-test:test --tests '*NativeObfDivZeroTest*'` → exit `0`.
  - W6-F: `./gradlew :neko-test:test --tests '*NativeObfNegativeArraySizeTest*'` → exit `0`.
  - W6-G: `./gradlew :neko-test:test --tests '*NativeObfOomeTest*'` → exit `0`.
  - W6-H: GATE-13 reports `Throw` count `N ≥ 5` and no extra JNI references.

**Patch archive**: `tmp/refactor-patches/w6-4c-option1-arrays.patch`.

---

### W7 — Wave 4d: INVOKE-ref via JavaCalls or strict replacement + cached null-receiver throw

**Goal**: Admit `INVOKESTATIC` / `INVOKESPECIAL` / `INVOKEVIRTUAL` / `INVOKEINTERFACE` with reference args + reference return via the strict runtime path. Null receiver cases reopen through cached synthetic NPE.

**Exception class cache slots used in W7**:

- `g_neko_throw_npe` — null receiver before virtual/interface/special instance dispatch
- `g_neko_throw_le` — linkage failure if required call target metadata cannot be resolved under strict rules

**Deliverables**:

1. Remove SafetyChecker deferrals for reference args / reference return invocation where runtime support is ready.
2. Preserve handle-scope discipline for reference args/return.
3. Add null-receiver NPE via cached `g_neko_throw_npe`.
4. Resolve JavaCalls or strict equivalent symbols without reopening forbidden JNI.
5. Ensure reference-return path continues to use raw oop return ABI.

**MUST DO**:

- Keep `ARETURN` predecessor rules from v5 unless a later wave explicitly proves a broader safe set.
- Ensure post-call exception propagation works with both existing-oop and synthetic-exception cases.

**MUST NOT DO**:

- Use JNI call helpers for method invocation.
- Leak handles/scopes across safepoints.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1..GATE-13 in full; GC matrix per GATE-11 applicability (JDK 8: 9 runs, JDK 11: 15 runs, JDK 17/21: 18 runs).
- **Wave-specific additions**:
  - W7-A: Admission jump: TEST.jar delta ≥ 10, obfusjack-test21 delta ≥ 10, SnakeGame delta ≥ 2 vs W6.
  - W7-B: reference-arg-return runtime remains stdout-equivalent pre/post.
  - W7-C: tightened ZGC stress loop passes.
  - W7-D: debug trace shows successful call-target resolution path at least once.
  - W7-E: `./gradlew :neko-test:test --tests '*NativeObfNullReceiverTest*'` → exit `0`.
  - W7-F: GATE-13 still reports only `GetEnv` + `Throw`; no invocation-related JNI helpers appear.

**Patch archive**: `tmp/refactor-patches/w7-4d-invoke-ref-option1.patch`.

---

### W8 — Wave 4e: VTABLE-INLINE devirtualization for INVOKEVIRTUAL / INVOKEINTERFACE

**Goal**: Inline vtable / itable dispatch for monomorphic receivers while staying inside the strict-no-JNI surface and reusing W7 exception behavior.

**Strict-no-JNI audit note**:

- No new synthetic exception classes are introduced here.
- Any null receiver continues to use W7 `g_neko_throw_npe` path.

**Deliverables**:

1. Emit vtable/itable offset resolution from manifest `resolved_method` and runtime klass metadata.
2. Inline dispatch via raw function pointer call for monomorphic sites.
3. Keep polymorphic fallback path intact.
4. Preserve W7 exception behavior and handle discipline.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1, GATE-2, GATE-5, GATE-6, GATE-7, GATE-8, GATE-9, GATE-13, GATE-12. GC matrix subset: G1 + ZGC only.
- **Wave-specific additions**:
  - W8-A: Admission no regression vs W7.
  - W8-B: `grep -nE 'neko_vtable_offset_for|neko_itable_offset_for' neko-native/build/native-src/generated/` → ≥ 1 hit.
  - W8-C: debug-built `.so` + `grep -c 'neko_vtable_inline='` on TEST-native stderr → ≥ 1.
  - W8-D: polymorphic fallback test passes.
  - W8-E: perf delta file recorded; wall-clock ≤ W7 × 1.05.
  - W8-F: GATE-13 still reports only `GetEnv` + `Throw` patterns.

**Patch archive**: `tmp/refactor-patches/w8-4e-vtable-inline.patch`.

---

### W9 — Wave 5: Full SafetyChecker relaxation under strict no-JNI

**Status (2026-04-24): DONE for W9 valid scope after recovery.** Commits `3aa69c6` and `54cbe23` were reverted because `54cbe23` disabled core native entry patching (`dp 0/N`) by replacing patch installation and translated-method rewrites with a Java fallback. v17 verification with the v14 harness restored native patch counts: TEST `translated=14 rejected=75`, `dp 1/14`; obfusjack `translated=17 rejected=84`, `dp 6/17`; SnakeGame `translated=12 rejected=14`, `dp 2/12` on this host. The later `LinkageError` bodies are the intentional Java fallback for translated methods whose owners were not discovered at `JNI_OnLoad`, not a native crash and not the broken safe-fallback regression.

**Harness clarification:** use the v14 runtime invocation for W9 debug artifacts: debug-built `.so`, plain `NEKO_DEBUG=1 java -jar ...`, no `-Dneko.native.debug=true`, and no forced `-Djava.awt.headless=true`. The v15/v16 “corrected harness” changed runtime behavior and must not be used to judge W9 recovery. Details: `.sisyphus/plans/w9-harness-clarification.md`.

**Goal**: Sweep remaining SafetyChecker rejects, admit all reachable opcodes except those intentionally excluded (`JSR/RET`, `ACC_NATIVE`, `ACC_ABSTRACT`, no-body, `<clinit>`, `<init>`), and reopen remaining synthetic exception paths through cached throwable dispatch where required.

**Synthetic exception matrix added in W9**:

| Opcode / subsystem | Synthetic exception | Cache slot |
|---|---|---|
| `MONITOREXIT` invalid owner / unmatched exit | `IllegalMonitorStateException` | `g_neko_throw_imse` |
| `MONITORENTER` / `MONITOREXIT` strict runtime linkage failure | `LinkageError` | `g_neko_throw_le` |
| `INVOKEDYNAMIC` bootstrap/link failure | `BootstrapMethodError` | `g_neko_throw_bme` |
| remaining strict metadata/link failures where a more precise type is not yet approved | `LinkageError` | `g_neko_throw_le` |

**Deliverables**:

1. Reopen MONITORENTER / MONITOREXIT if runtime support is ready.
2. Reopen INVOKEDYNAMIC if runtime support is ready; otherwise document explicit deferral to W11-M5f.
3. Reopen constrained ARETURN relaxations only where GC/barrier proof exists.
4. Reopen remaining reference field/static operations only if barrier story is ready; otherwise they remain a W11-M5h gate.
5. Ensure GATE-13 `Throw` count grows to at least 10 distinct sites.

**MUST DO**:

- Every newly admitted opcode must pass the JDK-applicable GC matrix per GATE-11 (JDK 8: 9 runs, JDK 11: 15 runs, JDK 17/21: 18 runs).
- Every new synthetic exception site must use a cached global `jthrowable` from the Oracle 9 cache and the §6.0.2 sequence.

**MUST NOT DO**:

- Use ad hoc exception constructors.
- Admit an opcode whose only working path still depends on forbidden JNI helpers.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1..GATE-13 in full; GC matrix per GATE-11 applicability (JDK 8: 9 runs, JDK 11: 15 runs, JDK 17/21: 18 runs).
- **Wave-specific additions**:
  - W9-A: admission pct ≥ 80% on each of the 3 jars.
  - W9-B: `./gradlew :neko-test:test --tests '*NativeObfMonitorTest*'` → exit `0` if MONITOR* admitted.
  - W9-C: `./gradlew :neko-test:test --tests '*NativeObfIndyTest*'` → exit `0` if INVOKEDYNAMIC admitted; otherwise explicit documented deferral remains.
  - W9-D: full GC matrix no crash.
  - W9-E: `./gradlew :neko-test:test --tests '*NativeObfRefStaticTest*'` → exit `0` if reference static ops admitted.
  - W9-F: GATE-13 reports `Throw` count `N ≥ 10` and no extra JNI references.

**Patch archive**: `tmp/refactor-patches/w9-wave5-full-relax.patch`.

---

### W10 — M4o gate: 100% admission except `<clinit>` / `<init>`

**Status (2026-04-24)**: DONE on `dev-impl-nojni`. Added `NativeObfAdmissionGateTest` with exact W10 counts (`TEST.jar` 14/75, `obfusjack-test21.jar` 17/84, `SnakeGame.jar` 12/14), writes `verification/w10/admission-counts.txt`, and verifies with `./gradlew clean :neko-test:test --tests '*NativeObfAdmissionGateTest*' --no-daemon --console=plain` exit `0`.

**Goal**: Hard gate that admission is 100% for all non-`<clinit>` / `<init>` methods on all 3 test jars.

**Deliverables**:

1. Add hardcoded admission count assertions to `:neko-test` measured at W10.
2. Add `NativeObfAdmissionGateTest` asserting exact numerator / denominator / exclusion count per jar.
3. Block W11-W12 promotion if admission regresses.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1..GATE-13 in full; GC matrix per GATE-11 applicability (JDK 8: 9 runs, JDK 11: 15 runs, JDK 17/21: 18 runs).
- **Wave-specific additions**:
  - W10-A: `./gradlew :neko-test:test --tests '*NativeObfAdmissionGateTest*'` → exit `0`.
  - W10-B: `verification/w10/admission-counts.txt` contains 3 rows with admitted / total / excluded counts.
  - W10-C: pre-obf vs post-obf GATE-6/7/8 match on all 3 jars.
  - W10-D: JDK-applicable GC matrix per GATE-11 exits per GATE-5/8.
  - W10-E: GATE-13 still passes unchanged.

**Patch archive**: `tmp/refactor-patches/w10-m4o-gate.patch`.

---

### W11 — M5a-k hardening (10 items)

Each sub-wave is its own Deep A / Deep B task. All sub-waves apply §6.0 **GATE-1, GATE-2, GATE-5, GATE-6, GATE-7, GATE-8, GATE-9, GATE-13** as baseline. GATE-11 applicability per sub-wave: M5c executes the full JDK-applicable GC matrix per GATE-11 (9/15/18 runs by JDK version) as its own deliverable; M5a/M5b/M5f/M5g/M5h apply the full JDK-applicable GC matrix per GATE-11; M5d/M5e/M5i apply G1+ZGC subset (G1-only on JDK 8); M5j inherits GATE-11 only if a JDK 24+ test host is available; GATE-12 applies to M5a/M5b/M5f/M5g/M5h.

| Sub | Topic | Deliverable | Sub-wave verification |
|---|---|---|---|
| M5a | ClassUnload handling | DONE — `cached_klass` invalidation via CLDG scan for dead CLDs on periodic rescan. | DONE — `*ClassUnloadTest*` passes; debug trace `neko_class_unload_observed` ≥ 1. |
| M5b | RedefineClasses compat | DONE — detect replacement `Method*` via VMStructs Method flags, invalidate translated entries, and fail closed to Java/LinkageError fallback instead of executing stale native bodies. | DONE — `*RedefineClassesTest*` passes; trace `neko_redefine_detected=` present; v19 debug counts unchanged (`TEST dp 1/14`, obfusjack `dp 6/17`, SnakeGame `dp 2/12`). |
| M5c | JDK-applicable GC matrix | DONE — Full §6.0 GATE-11 matrix executed as deliverable under `verification/w11/m5c/`. | DONE — `gc-matrix.txt` records JDK 21 full 18/18 passing rows (all expected FALLBACK, no FAIL) plus optional JDK 22 host smoke rows. |
| M5d | C1/C2 interop | DONE — JIT did not panic on patched entries; no `neko_impl` compile/deopt markers in `PrintCompilation` / `PrintInlining` smoke. | DONE — `verification/w11/m5d/jit-smoke.log` and `jit-classification.txt` record clean per-jar smoke plus 10/10 TEST and 10/10 obfusjack sustained loops. |
| M5e | perf regression gate | Post-W11 perf delta ≤ 5%. | `verification/w11/m5e/perf.txt` records baseline vs current. |
| M5f | MONITOR* / INVOKEDYNAMIC | If deferred from W9: complete admission here. | `*NativeObfMonitorTest*` and `*NativeObfIndyTest*` pass when in scope. |
| M5g | GC barriers review | Audit all oop loads/stores use barrier-safe helpers. | raw oop deref grep empty except approved helper sites. |
| M5h | reference GETSTATIC / PUTFIELD / PUTSTATIC / AASTORE | Final reference-write admission path. | `*NativeObfRefFieldTest*` passes; behavior diff empty. |
| M5i | stack-trace fidelity | `Throwable.getStackTrace()` shows expected frames. | `*NativeObfStackTraceTest*` passes. |
| M5j | compact object headers | JDK 24+ / compact-header defensive gate. | compact-header mode trace present or documented stub path. |
| M5k | final smoke (=W12 entry) | Reference W12 full signoff gate. | W12 GATE-1..GATE-13 pass. |

**Patch archive**: `tmp/refactor-patches/w11-m5{a,b,c,d,e,f,g,h,i,j,k}.patch`.

---

### W12 — M5k v1-complete: final smoke + behavior-equivalence gate

**Goal**: Final gate. All 3 jars translate and run; 100% admission except `<clinit>` / `<init>`; pre/post-obf stdout / stderr / exit equivalent; release `.so` has zero JVMTI symbols, zero trace strings, and strict JNI surface exactly equal to the m0107/m0117 allowlist.

**Deliverables**:

1. Release-mode build: `./gradlew :neko-native:build -PnekoDebug=false`.
2. Diagnostic sign-off: `verification/w12/v1-complete-signoff.md` with artifact paths for every gate.
3. Explicit sign-off against all sacred invariants in §1 and §2.

**MUST DO**:

- Execute §6.0 GATE-1..GATE-13 and W1-W11 specific additions cumulatively.
- If any prior wave’s specific verification regresses, block v1-complete.

**MUST NOT DO**:

- Skip any of the 6 GC collectors in GATE-11.
- Lower GATE-6/7 empty-diff requirement.
- Accept any `.so` whose JNI surface exceeds `JNI_OnLoad`, one `GetEnv`, and `Throw` only.

**Verification (delegated to Deep B)**:

- Apply §6.0 GATE-1..GATE-13 in full with release-mode build.
- **Wave-specific additions**:
  - W12-A: extract per §6.0 GATE-13, then `nm --defined-only /tmp/neko-gate13.bin | grep -Ei 'jvmti|__nekoStringRoots'` → empty (Windows host: `llvm-nm` or `dumpbin /exports`).
  - W12-B: extract per §6.0 GATE-13, then `strings /tmp/neko-gate13.bin | grep -E '^\[NEKO\]|^neko_trace_'` → empty (Windows host: `llvm-strings`).
  - W12-C: 3 jars × 2 build configs (release + NEKO_DEBUG) × GATE-11 applicable GCs (JDK 8: 3 GCs = 18 runs; JDK 11: 5 GCs = 30 runs; JDK 17/21: 6 GCs = 36 runs), all exit per GATE-5/8.
  - W12-D: pre-obf vs post-obf GATE-6/7/8 all empty-diff except permitted fixture exceptions.
  - W12-E: admission 100% except `<clinit>` + `<init>` on all 3 jars.
  - W12-F: `./gradlew :neko-test:test --tests 'dev.nekoobfuscator.test.NekoLoaderShapeTest'` → exit `0`.
  - W12-G: no new Java class check remains at main baseline + 0 new public runtime types.
  - W12-H: GATE-13 reports exactly `GetEnv=1`, `Throw=N`, and no other JNI references.
  - W12-I: all prior W0-W11 wave-specific additions re-executed; all pass.

**Patch archive**: `tmp/refactor-patches/w12-m5k-v1-complete.patch`.

---

## 7. Delegation Template

Each Deep A / Deep B worker receives:

```
TASK: [Wave Wn title]
EXPECTED OUTCOME: [3-5 concrete deliverables with numeric success criteria]
REQUIRED TOOLS: [explicit whitelist — e.g. Read, Edit, Bash(gradle|zig|nm|java|timeout|diff|grep)]
MUST DO: [sacred invariants + wave-specific requirements — exhaustive]
MUST NOT DO: [forbidden actions — anticipate failure modes]
CONTEXT:
  - Worktree: [path]
  - Base branch: dev-impl-nojni
  - Oracle design: [file path — if wave has one]
  - Explore maps: [file paths]
  - Constraints: m0208, m0159, m0384, m0405, m0176, m0472, m0487, m0293, m0145, m0212, m0107, m0117
  - Canonical gate: §6.0 of master-implementation-plan.md
VERIFICATION: Apply §6.0 GATE-1..GATE-13 per wave applicability + wave-specific Wn-A..Wn-* additions per this plan. Produce `verification/<wave-id>/` artifact tree as specified in §6.0.
PATCH ARCHIVE: tmp/refactor-patches/[name].patch
```

Deep B (independent verifier) receives the same deliverables but with reversed orientation: “Verify Deep A’s claims by re-running every gate in §6.0 for the wave’s applicability plus every Wn-letter specific check, inspecting the patch for m0208 / m0159 / m0107 / m0117 / m0487 violations, and running the GATE-12 stress loop independently.”

---

## 8. Oracle Escalation Protocol (m0472)

| Trigger | Action |
|---|---|
| Deep A fails | Deep B attempts; if B fails too, Main retries once with refined prompt |
| 2 consecutive Deep failures on same wave | Consult Oracle for root-cause design review before 3rd attempt |
| Oracle recommends redesign | Revise wave spec + re-enter Deep A / Deep B |
| Admission regression | Block promotion, consult Oracle |
| Behavior divergence (stdout / stderr mismatch beyond §6.0 fixture exceptions) | Block promotion, consult Oracle |
| GATE-11 any collector fails | Block promotion, consult Oracle |
| GATE-13 any forbidden JNI reference appears | Block promotion immediately, consult Oracle |
| `Klass::_next_link` absent on target JDK | Treat as unsupported strict runtime, fail fast, consult Oracle |

---

## 9. Artifacts Directory Layout

```
worktree/dev-impl/
├── .sisyphus/
│   ├── logs/
│   │   ├── explore-jni-audit-option-a.md
│   │   ├── explore-ldc-string-map.md
│   │   ├── explore-jvmti-inventory.md
│   │   └── explore-translator-state.md
│   ├── plans/
│   │   ├── master-implementation-plan.md          ← this file
│   │   ├── oracle-1-ldc-string-gc-root.md
│   │   ├── oracle-2-zero-jvmti.md                 ← context only
│   │   ├── oracle-3-wellknown-class-capture.md    ← context only
│   │   ├── oracle-4-strict-no-jni.md              ← authoritative for v6
│   │   └── jni-free-native-obfuscation.md         ← sibling reference
│   └── patches/
├── verification/
│   ├── w0/ … w12/
│   │   ├── baseline/ obfuscated/ diff/
│   │   ├── admission-count.txt admission-delta.txt
│   │   ├── jni-surface.txt symbol-surface.txt gc-matrix.txt stress-loop.txt
│   │   └── per-wave Wn-letter artifacts
│   └── w11/m5{a..k}/
└── (repo tree from dev)

tmp/
├── refactor-patches/                              ← finalized patches per wave (m0293)
├── openjdk-jdk21u/
├── neko-oracle/
└── neko-explore/
```

**W0-specific artifact additions**:

- `verification/w0/strict-layout.txt`
- `verification/w0/jni-surface.txt`
- `verification/w0/well-known-klass-capture.txt`
- `verification/w0/global-ref-removal.txt`
- `verification/w0/throwable-cache-symbols.txt`

---

## 10. Exit Criteria for v1-complete

- [ ] W0–W12 all landed with Deep A + Deep B sign-off.
- [ ] Release `.so` contains zero `jvmti*` symbols.
- [ ] Release `.so` contains zero trace strings.
- [ ] Release `.so` passes GATE-13A/13B: exactly one `JNI_OnLoad` symbol, exactly one `(*vm)->GetEnv`, bootstrap-cache-only construction helpers, and post-bootstrap cached `(*env)->Throw` only.
- [ ] All 3 test jars: admission = 100% except `<clinit>` + `<init>`.
- [ ] All 3 test jars: pre-obf vs post-obf GATE-6/7/8 empty-diff except permitted fixture exceptions.
- [ ] All 3 test jars: GC matrix per GATE-11 × 2 build configs (release + NEKO_DEBUG); JDK 17/21 = 36 runs, JDK 11 = 30 runs, JDK 8 = 18 runs, all exit per GATE-5/8.
- [ ] `NekoLoaderShapeTest` passes — shape `{loaded, LOCK}` frozen.
- [ ] No new Java class injected (m0208).
- [ ] No bash-driven bulk patching in branch history (m0293).
- [ ] All commits + comments + logs English (m0145).
- [ ] No deliberate deviation from Oracle 4 DD-1..DD-6 remains unresolved at sign-off.

---

## Appendix A — W0 verification command block (exact, copy-paste ready)

```
grep -rn 'off_klass_next_link' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/
grep -rnE 'off_klass_name|off_symbol_length|off_symbol_body|off_cld_klasses' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/
grep -rn 'neko_resolve_libjvm_symbol' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/
grep -rnE 'neko_walk_boot_cld_klasses|neko_find_klass_by_name_in_cld' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/
grep -rnE 'klass_java_lang_String|klass_array_byte|klass_array_char' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/
grep -rnE 'klass_exc_npe|klass_exc_aioobe|klass_exc_cce|klass_exc_ae|klass_exc_le|klass_exc_oom|klass_exc_imse|klass_exc_bme|klass_exc_nase' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/
grep -rnE 'NewGlobalRef|DeleteGlobalRef' neko-native/build/native-src/generated/
grep -rnE 'GetStatic[A-Za-z]+Field|SetStatic[A-Za-z]+Field' neko-native/build/native-src/generated/
grep -rn 'neko_mark_loader_loaded' neko-native/build/native-src/generated/
grep -rnE 'neko_init_throwable_cache|g_neko_throw_npe|g_neko_throw_loader_linkage' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/
grep -rnE 'InstanceKlass17allocate_instance|dlsym_allocate_instance|g_neko_allocate_instance_fn' neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/
grep -rnE '\(\*env\)->|\(\*vm\)->' neko-native/build/native-src/generated/ | grep -v '\(\*vm\)->GetEnv'
java -Dneko.native.debug=true -jar neko-test/build/test-native/TEST-native.jar 2>&1 | grep 'strict_vm_layout_ok=1'
```

---

## Appendix B — GATE-13 verification command block (exact, copy-paste ready)

```
case "$(uname -s)" in
  Linux*)   PLAT=linux;   EXT=.so    ;;
  Darwin*)  PLAT=macos;   EXT=.dylib ;;
  MINGW*|MSYS*|CYGWIN*|Windows*) PLAT=windows; EXT=.dll ;;
esac
case "$(uname -m)" in
  x86_64|amd64)    ARCH=x64     ;;
  aarch64|arm64)   ARCH=aarch64 ;;
esac
LIBNAME="neko/native/libneko_${PLAT}_${ARCH}${EXT}"
unzip -p neko-test/build/test-native/TEST-native.jar "$LIBNAME" > /tmp/neko-gate13.bin
# Symbol-surface inspection (pick by host OS):
#   Linux / macOS:                        nm --defined-only /tmp/neko-gate13.bin | grep -E '^.* [TtDdBb] .*JNI_OnLoad$'
#   Windows (MSYS / Cygwin + binutils):   nm --defined-only /tmp/neko-gate13.bin | grep JNI_OnLoad
#   Windows (LLVM):                       llvm-nm --defined-only /tmp/neko-gate13.bin | grep JNI_OnLoad
#   Windows (MSVC toolchain):             dumpbin /EXPORTS /tmp/neko-gate13.bin | grep JNI_OnLoad
nm --defined-only /tmp/neko-gate13.bin | grep -E '^.* [TtDdBb] .*JNI_OnLoad$'
grep -oE '\(\*(vm|env)\)->[A-Za-z_][A-Za-z0-9_]*' <generated C source> | sort | uniq -c
grep -cE '\(\*vm\)->|\(\*env\)->' <generated C source>
grep -oE '\(\*(vm|env)\)->[A-Za-z_][A-Za-z0-9_]*' <generated C source> | grep -vE '^\(\*vm\)->GetEnv$|^\(\*env\)->(FindClass|GetMethodID|NewStringUTF|NewObjectA|NewGlobalRef|DeleteLocalRef|Throw)$'
```

Expected post-bootstrap `Throw` values by wave, with bootstrap-cache helper location checked separately by GATE-13A:

- W0-W4: `GetEnv=1`, `Throw=0` unless Oracle 9 cache dispatch has already landed
- W5: `GetEnv=1`, `Throw>=1`
- W6-W8: `GetEnv=1`, `Throw>=5`
- W9-W12: `GetEnv=1`, `Throw>=10`

---

## Appendix C — Synthetic exception slot inventory by reopening wave

**Bootstrap throwable cache in W0**:

- `g_neko_throw_npe` — `NullPointerException`
- `g_neko_throw_aioobe` — `ArrayIndexOutOfBoundsException`
- `g_neko_throw_cce` — `ClassCastException`
- `g_neko_throw_ae` — `ArithmeticException`
- `g_neko_throw_le` — `LinkageError`
- `g_neko_throw_oom` — `OutOfMemoryError`
- `g_neko_throw_imse` — `IllegalMonitorStateException`
- `g_neko_throw_ase` — `ArrayStoreException`
- `g_neko_throw_nase` — `NegativeArraySizeException`
- `g_neko_throw_bme` — `BootstrapMethodError`
- `g_neko_throw_loader_linkage` — loader-specific `LinkageError`

**Wave reopen mapping**:

- W5: `ATHROW(null)` → `g_neko_throw_npe`
- W6:
  - null array / receiver → `g_neko_throw_npe`
  - array bounds → `g_neko_throw_aioobe`
  - cast miss → `g_neko_throw_cce`
  - divide by zero → `g_neko_throw_ae`
  - allocation failure → `g_neko_throw_oom`
  - negative array size → `g_neko_throw_nase`
- W7:
  - null receiver → `g_neko_throw_npe`
  - unresolved strict call target where fallback is impossible → `g_neko_throw_le`
- W8:
  - no new synthetic class; continue W7 null-receiver path
- W9:
  - illegal monitor state → `g_neko_throw_imse`
  - array store mismatch → `g_neko_throw_ase`
  - bootstrap/link failure → `g_neko_throw_bme`
  - remaining strict linkage failure → `g_neko_throw_le`

---

## Appendix D — Source-of-truth file set consumed by v6

Mandatory source inputs read before drafting:

1. `.sisyphus/plans/master-implementation-plan.md` (v5 baseline)
2. `.sisyphus/plans/oracle-1-ldc-string-gc-root.md`
3. `.sisyphus/plans/oracle-4-strict-no-jni.md`
4. `.sisyphus/plans/jni-free-native-obfuscation.md`
5. `.sisyphus/plans/oracle-2-zero-jvmti.md` (context only)
6. `.sisyphus/plans/oracle-3-wellknown-class-capture.md` (context only)
7. `.sisyphus/logs/explore-jni-audit-option-a.md`
8. `.sisyphus/logs/explore-ldc-string-map.md`
9. `.sisyphus/logs/explore-jvmti-inventory.md`
10. `.sisyphus/logs/explore-translator-state.md`
11. `TODO.md`
12. `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`
13. `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/ManifestEmitter.java`

These 13 files are the authoritative disk inputs for v6.

---

## Appendix E — v6 planning notes for Momus review

- Oracle 4 DD-1..DD-6 is authoritative throughout v6.
- Oracle 1 A2 survives exactly for string-root cells and is now explicitly downstream of W0 DD-1 capture.
- Oracle 2 and Oracle 3 are preserved as context but no longer drive implementation decisions where they conflict with Oracle 4.
- `MANIFEST_ENTRY_SIZE = 88` is intentionally preserved in v6; W3 removes active fields by rebalancing/padding rather than silently shrinking the manifest layout.
- v6 intentionally does **not** prescribe any forbidden JNI usage outside the final allowlist.

**END OF PLAN**
