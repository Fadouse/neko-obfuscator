# NekoObfuscator — JNI-Free HotSpot Native Obfuscation (Aggressive v1)

> **Owner**: Sisyphus (主 agent)
> **Scope**: Replace current JNI-based native obfuscation with direct HotSpot Method entry patching
> **Status**: PLAN — awaiting Momus review + user approval
> **JDK target**: HotSpot 1.8, 11, 17, 21 (x86_64 Linux primary; Win64, macOS x86_64/AArch64, Linux AArch64 secondary)
> **Source of truth**: this doc + Oracle session `ses_2607398dcffeNHXeknj4hAOrei` (task `bg_3c121b0b`)

---

## 0. Problem statement and hard constraints (locked)

### Problem
Current pipeline rewrites selected Java methods to `ACC_NATIVE`, registers implementations via `RegisterNatives` from generated C code. Every invocation pays JNI transition cost (~35–60 ns on modern HotSpot, plus handle marshalling). Reference architecture (`_reference_native_obfuscator/` = radioegor146/native-obfuscator) explicitly states "slows down code significantly". User wants zero per-call JNI overhead.

### Hard constraints (confirmed by user, do not violate)

- **C1**: Bypass JNI completely at steady state. No `RegisterNatives`, no `JNIEnv` lookup per call, no jobject/jclass wrapping after `JNI_OnLoad`.
- **C2**: Single artifact runs on HotSpot JDK 1.8 → 21. Runtime VMStructs-based offset discovery.
- **C3**: Unsafe + GC risk explicitly accepted by user. Do not emit safety warnings.
- **C4**: JVM-side helper runtime is **exactly ONE class**: `dev.nekoobfuscator.runtime.NekoNativeLoader`.
- **C5**: All bootstrap / registration / hot-loader / entry-patch logic lives in **native code only**. The single Java class is a pure shell: resource extraction + `System.load()`, nothing else.
- **C6**: Target method bytecode shape: non-native, original signature, body throws `LinkageError("please check your native library load correctly")`. Class gets injected `static { NekoNativeLoader.load(); }`.
- **C7**: v1 AGGRESSIVE — supports leaf, outbound Java calls, object allocation, string creation, exception propagation in one cycle. No staged rollout to leaf-only v1.
- **C8**: Fail closed — if any condition prevents safe patching, leave Java fallback intact (LinkageError will throw at invocation).

### Non-goals for v1
- OpenJ9 compatibility (HotSpot only — fail closed on OpenJ9)
- GraalVM Native Image
- Redefinition tracking — addressed in M5b (full `JVMTI_EVENT_CLASS_FILE_LOAD_HOOK` / `RetransformClasses` / `RedefineClasses` support)
- Installing fake nmethods in `Method::_code` (left for potential v2)
- Support for `<init>`, `<clinit>`, `synchronized`, abstract, native, bridge methods (excluded from translation)

---

## 1. Architecture (Oracle E1 + extended scope)

### 1.1 Runtime dataflow

```
BUILD TIME (NekoObfuscator)
  ↓
  NativeCompilationStage:
    - select methods via @NativeTranslate / config patterns
    - for each target class:
        a) inject `static { NekoNativeLoader.load(); }` into <clinit>
        b) for each target method: replace body with `throw new LinkageError(...)` (non-native)
    - translate bytecode → raw C via NativeTranslator
    - emit C sources + .S trampoline stubs via CCodeGenerator
    - build platform libraries via NativeBuildEngine (Zig cc)
    - embed manifest (method catalog + function pointers) inside .so/.dll/.dylib as const data

RUNTIME
  ↓
  target Class loaded → <clinit> fires → NekoNativeLoader.load() (idempotent)
    ↓
    System.load(/tmp/libneko_<platform>_<arch>.<ext>)
    ↓
    JNI_OnLoad(vm, reserved):  ← the ONE AND ONLY JNI touch point
      1. GetEnv(JNI_VERSION_1_6) → JNIEnv*  (kept only for this function scope)
      2. GetEnv(JVMTI_VERSION_1_2) → jvmtiEnv*
      3. resolve libjvm handle (dlopen NULL-RTLD_DEFAULT on Linux/macOS, GetModuleHandle on Win)
      4. dlsym VMStructs/VMTypes/VMIntConstants/VMLongConstants tables
      5. parse layout → Method field offsets, Klass field offsets, oop compression params, Thread offsets
      6. validate JDK/arch/GC compatibility; on fail → return JNI_VERSION_1_6 (let fallback throw)
      7. iterate g_neko_manifest_methods[] (embedded in .so):
          - JVMTI GetLoadedClasses → find owner class
          - JVMTI GetClassMethods → find target jmethodID
          - dereference jmethodID → Method*
          - check Method::_code == NULL (refuse if already compiled)
          - set no-compile bits (AccessFlags for JDK 8/11/17, MethodFlags for JDK 21)
          - atomic store_release writes to _i2i_entry, _from_interpreted_entry, _from_compiled_entry
      8. SetEventCallbacks(ClassPrepare, ClassUnload)
      9. SetEventNotificationMode(JVMTI_ENABLE, ClassPrepare/Unload)
      10. return JNI_VERSION_1_6
    ↓
  <clinit> continues normally. Target methods are now redirected.

PER-CALL (ZERO JNI)
  ↓
  caller (interpreter or compiled) invokes target method
    → HotSpot dispatch reads Method::_*_entry → our stub
    → signature-specific i2i or c2i stub (per-signature, shared across methods of same signature)
    → unpacks interpreter/compiled ABI args → raw C calling convention
    → calls raw C impl neko_impl_NNN(NekoThreadContext*, receiver?, args...)
    → impl runs (may call outbound neko_invoke_java() for Java->Java calls)
    → returns via stub → caller sees result in interpreter/compiled return register
    → if Thread::_pending_exception != NULL, dispatcher jumps to VM forward-exception path
```

### 1.2 Bytecode shape after rewrite

Original:
```java
public class Target {
    public int compute(int x, int y) { return x * x + y; }
}
```

After obfuscation:
```java
public class Target {
    static { NekoNativeLoader.load(); }
    public int compute(int x, int y) {
        throw new LinkageError("please check your native library load correctly");
    }
}
```

- Method is **NOT** `ACC_NATIVE`.
- Body is exact 5-opcode sequence: `NEW LinkageError; DUP; LDC "..."; INVOKESPECIAL <init>; ATHROW`.
- `maxStack = 3`, `maxLocals = parameterSlotCount(access, desc)`.
- Exception table empty.
- Class recomputes frames (`COMPUTE_FRAMES | COMPUTE_MAXS`).

### 1.3 NekoNativeLoader final shape

```java
public final class NekoNativeLoader {
    private static volatile boolean loaded;
    private static final Object LOCK = new Object();

    private NekoNativeLoader() {}

    public static void load() {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            String platform = detectPlatform();
            String arch = detectArch();
            String resourcePath = "/neko/native/" + resourceName(platform, arch);
            Path tmp = extractResource(resourcePath, libExt(platform));
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
        }
    }

    private static String detectPlatform() { /* win/mac/linux */ }
    private static String detectArch()     { /* x64/aarch64 */ }
    private static String resourceName(String platform, String arch) { ... }
    private static String libExt(String platform) { /* .so/.dll/.dylib */ }
    private static Path extractResource(String resourcePath, String ext) throws IOException { ... }
}
```

**Removed** (vs current code):
- `bindClass(Class, String)`
- `nekoBindClass(Class, String)` native
- `nekoVmOption`, `nekoAddressSize`, `nekoArrayBaseOffset`, `nekoArrayIndexScale`, `nekoInstanceFieldOffset`, `nekoStaticFieldOffset`, `nekoStaticFieldBase` (7 callback helpers)
- All Unsafe / HotSpotDiagnosticMXBean reflection

### 1.4 JDK compatibility matrix

| Field / Mechanism | JDK 8 | 11 | 17 | 21 |
|---|---|---|---|---|
| `Method::_i2i_entry`, `_from_interpreted_entry`, `_from_compiled_entry`, `_code`, `_access_flags`, `_constMethod` | ✓ stable (VMStructs) | ✓ | ✓ | ✓ |
| No-compile / no-inline bits | AccessFlags: `JVM_ACC_NOT_C1_COMPILABLE = 0x04000000`, `JVM_ACC_NOT_C2_COMPILABLE = 0x02000000` | AccessFlags + `Method::_flags` bit `dont_inline = 1<<2` | same as 11 | **MethodFlags**: `is_not_c2_compilable = 1<<8`, `is_not_c1_compilable = 1<<9`, `dont_inline = 1<<12` |
| Compressed OOP globals | `Universe._narrow_oop`, `Universe._narrow_klass` | same | same | `CompressedOops::_narrow_oop`, `CompressedKlassPointers::_narrow_klass` |
| `Method::_adapter` directly in Method | ✓ | ✗ (moved) | ✗ | ✗ |
| `Method::_intrinsic_id` type | `u1` | `u2` | `u2` | `u2` |
| `ConstMethod::_method_idnum` | ✓ | ✓ | ✓ | ✓ |

**Derived offsets** (not in VMStructs):
- `_native_function = sizeof(Method)` = VMType size of "Method"
- `_signature_handler = sizeof(Method) + wordSize`

### 1.5 File-level touch map

| File | M1 | M2 | M3 | M4 | M5 |
|---|---|---|---|---|---|
| `neko-native/src/main/java/dev/nekoobfuscator/native_/stage/NativeCompilationStage.java` | **rewrite** `rewriteClasses`, `ensureClinitLoadsNative`, `appendNativeBootstrap` | — | — | — | **verify** original `LineNumberTable` / `ConstMethod` preservation for native-patched methods; scrub native-facing flag state only if stack traces still report `Native Method` |
| `neko-runtime/src/main/java/dev/nekoobfuscator/runtime/NekoNativeLoader.java` | **collapse** to load() + private helpers only | — | — | — | — |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java` | — | **replace** `renderJniOnLoad`, `renderNativeBindingTables`, `renderNativeBindingRegistry`, `renderRuntimeSupport`, `renderPrototype` | generate stub dispatchers | generate outbound/alloc/string helpers **plus** array alloc/access, type-check, implicit NPE, div-zero, reference-LDC runtime-support emission | **extend** emitted bootstrap / JVMTI / runtime-support for redefine re-patch, 5-GC barrier selection, MONITOR fast+slow paths, INVOKEDYNAMIC resolution/cache, stack-trace preservation, compact-header math, perf tuning |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/NativeBuildEngine.java` | — | **add** `.S` support, adjust Zig cc flags | — | — | — |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/NativeTranslator.java` | — | — | **rewrite** C signatures (drop JNIEXPORT/JNIEnv*) | update opcode translation to use new helpers | — |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java` | — | — | — | **rewrite** invoke/field/string opcodes **plus** array creation/access, `CHECKCAST` / `INSTANCEOF`, implicit NPE injection points, div-zero guards, reference-LDC lowering | **add** `MONITORENTER` / `MONITOREXIT`, `INVOKEDYNAMIC`, GC-barrier-aware reference stores, compact-header-aware array math hooks |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/NativeTranslationSafetyChecker.java` | — | — | tighten rules (reject ctor, clinit, sync, abstract, bridge, native, already native) | relax rules for alloc / invoke / string / arrays / type-checks / reference-LDC; keep `AASTORE` reference path fail-closed until M5h, keep `MONITOR*` / `INVOKEDYNAMIC` blocked until M5f / M5g | unblock `MONITOR*`, `INVOKEDYNAMIC`, reference-field writes / `AASTORE` once GC/barrier/runtime support lands; refuse patching when GC mode is unknown |
| `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java` | **update** — no `ACC_NATIVE` assertions, add throw body assertions, remove `bindClass` assertions | — | — | **expand** fixture assertions for arrays, type-checks, implicit NPE, div-zero, reference-LDC end-to-end behavior | **expand** smoke assertions for monitor, indy, GC-barrier-backed reference writes, stack-trace shape, compact-header mode |
| `neko-test/src/test/java/dev/nekoobfuscator/test/NekoNativeLoaderReflectionTest.java` | **delete** — its assertion target (the 7 callback helpers) is removed by M1b; coverage subsumed by `NekoLoaderShapeTest` | — | — | — | — |
| `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationPerfTest.java` | — | — | — | **retain** calc / fixture perf guard while broader opcode suite lands | **tune** thresholds; add full JDK × GC smoke / regression checks |
| `neko-test/src/test/java/dev/nekoobfuscator/test/NekoLoaderShapeTest.java` | **new** shape test for the single-class constraint | — | — | — | — |
| `test-jars/TEST.jar` | — | — | leaf smoke fixture | **expand** fixture coverage for arrays / type-check / NPE / div-zero / reference-LDC | **use** for redefine / GC / monitor / indy / stack-trace / compact-header smoke matrix |
| `test-jars/obfusjack-test21.jar` | — | — | — | **keep** end-to-end aggressive fixture run | **keep** ship-ready matrix fixture run |
| `test-jars/SnakeGame.jar` | — | — | — | **keep** headless aggressive fixture run | **keep** ship-ready matrix fixture run |
| New: `neko-test/src/test/java/.../NativeOutboundCallTest.java` | — | — | — | **new** — verifies Java→native→Java outbound calls work without JNI | — |
| New: `neko-test/src/test/java/.../NativeFieldAccessTest.java` | — | — | — | **new** — covers GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC across all primitive + reference types | — |
| New: `neko-test/src/test/java/.../NativeAllocTest.java` | — | — | — | **new** — covers NEW/ANEWARRAY/NEWARRAY, stresses TLAB fast path + slow fallback | — |
| New: `neko-test/src/test/java/.../NativeStringTest.java` | — | — | — | **new** — covers LDC String + String construction across JDK 8 char[] + JDK 9+ LATIN1/UTF16 coder paths | — |
| New: `neko-test/src/test/java/.../NativeExceptionPropagationTest.java` | — | — | — | **new** — verifies pending_exception path, exception thrown from native, caught in Java, rethrown across frames | — |
| New: `neko-test/src/test/java/.../NativeCompressedOopTest.java` | — | — | — | **new** — runs fixture under `-XX:+UseCompressedOops` and `-XX:-UseCompressedOops` | — |
| New: `neko-test/src/test/java/.../NativeClassUnloadTest.java` | — | — | — | — | **new** — child-ClassLoader unload safety |
| New: `neko-test/src/test/java/.../NativeRedefineTest.java` | — | — | — | — | **new** — JVMTI RedefineClasses / RetransformClasses full support verification |
| New: `neko-test/src/test/java/.../NativeCollectorGatingTest.java` | — | — | — | — | **new** — runs under Serial / Parallel / G1 / ZGC / Shenandoah to confirm patch-or-refuse behavior |
| New: `.github/workflows/native-matrix.yml` | — | — | — | — | **new** — JDK 8/11/17/21 x Linux x86_64 CI matrix |
| New: `docs/performance.md` | — | — | — | — | **new** — ns/call figures, before/after tables |
| `README.md` | — | — | — | — | **update** — add CI status badge + JDK support table |
| `docs/CONFIG.md` | — | — | — | — | **update** — document native CI matrix and supported JDK range |
| New: `neko-test/src/test/java/.../NativeDispatchMicrobench.java` | — | — | — | — | **new** — JMH micro-benchmark for i2i/c2i dispatch ns/op |
| `neko-test/build.gradle.kts` | — | — | — | — | **update** — add `org.openjdk.jmh:jmh-core` + `jmh-generator-annprocess` to test classpath |

---

## 2. Milestone breakdown

Each milestone is a sequence of atomic sub-tasks. Each sub-task is executed by exactly one `ultrabrain` agent. Main agent's job per sub-task: craft the prompt (referencing this doc), verify the result, decide go/no-go.

### M1 — Bytecode rewrite + NekoNativeLoader collapse
**Goal**: Java-only changes. After M1 is done: obfuscated code compiles, loads (no-op native not present yet), every translated method throws LinkageError at invocation. Existing integration test becomes "expect LinkageError on Calc invocation" (temporary).

**Sub-tasks**:

#### M1a — Bytecode rewrite in NativeCompilationStage
- Replace `methodNode.instructions.clear()` + `ACC_NATIVE` flag with the throw-body emission (see §1.2).
- Replace `appendNativeBootstrap` — keep only `NekoNativeLoader.load()` call, remove `bindClass` call + `LDC class` + `LDC name` pushes.
- Ensure `COMPUTE_FRAMES | COMPUTE_MAXS` are used by the ClassWriter that emits the rewritten class.
- Exclusion rules: reject methods that are already `ACC_NATIVE`, `ACC_ABSTRACT`, `ACC_SYNCHRONIZED`, `ACC_BRIDGE`, `<init>`, `<clinit>`. Enforce in `NativeTranslationSafetyChecker` or at selection time.

**Files**: `NativeCompilationStage.java`, `NativeTranslationSafetyChecker.java`

**Verification**:
- `./gradlew :neko-native:build`
- ASM inspection test: after running obfuscation on a fixture, assert rewritten method has opcode sequence `NEW java/lang/LinkageError, DUP, LDC "please check your native library load correctly", INVOKESPECIAL <init>, ATHROW`
- Assert `ACC_NATIVE` bit is NOT set on any rewritten method
- Assert `<clinit>` contains exactly ONE `INVOKESTATIC NekoNativeLoader.load ()V` call
- Assert `bindClass` is NOT called anywhere

#### M1b — NekoNativeLoader collapse
- Delete `bindClass(Class, String)`, `nekoBindClass(...)`, and the 7 callback methods.
- Simplify `load()` per §1.3 spec. Keep it thread-safe and idempotent.
- Remove all Unsafe / HotSpotDiagnosticMXBean reflection.
- The class must have NO public methods other than `load()`. No public static fields. Private constructor.

**Files**: `NekoNativeLoader.java`

**Verification**:
- `./gradlew :neko-runtime:build`
- Reflection test (new): `NekoNativeLoader.class.getMethods()` returns only `load()` + inherited Object methods.
- Assert class is `final`, has private constructor.

#### M1c — Test updates + loader shape test + obsolete test removal
- Update `NativeObfuscationIntegrationTest.java`: remove assertions about `ACC_NATIVE` flag, add assertions about throw body (exact opcode sequence per §1.2).
- **Delete `NekoNativeLoaderReflectionTest.java`**: its entire premise is validating the 7 callback helpers (`nekoVmOption`, `nekoAddressSize`, `nekoArrayBaseOffset`, `nekoArrayIndexScale`, `nekoInstanceFieldOffset`, `nekoStaticFieldOffset`, `nekoStaticFieldBase`) which M1b removes. Its coverage (class shape + helper presence) is replaced by the stricter `NekoLoaderShapeTest`.
- Integration tests expecting the obfuscated app to RUN will temporarily expect `LinkageError`. Mark them `@Disabled("until M3 entry patch lands")` with clear reason — DO NOT DELETE.
- Add `NekoLoaderShapeTest.java`: enforces the single-class constraint (see M1b verification). Must also assert: `NekoNativeLoader.class.getDeclaredMethods()` contains ONLY `load()` (plus any private helpers); NO method named `bindClass`, `nekoBindClass`, `nekoVmOption`, `nekoAddressSize`, `nekoArrayBaseOffset`, `nekoArrayIndexScale`, `nekoInstanceFieldOffset`, `nekoStaticFieldOffset`, `nekoStaticFieldBase` exists at any visibility.

**Files**: `NativeObfuscationIntegrationTest.java`, deleted `NekoNativeLoaderReflectionTest.java`, new `NekoLoaderShapeTest.java`

**Verification**:
- `./gradlew :neko-test:test --tests "*NekoLoaderShapeTest*"` passes
- `./gradlew :neko-test:test --tests "*NativeObfuscation*"` passes (minus disabled perf/integration end-to-end runs)
- `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NekoNativeLoaderReflectionTest"` reports "No tests found" (file deleted)
- `find neko-test -name 'NekoNativeLoaderReflectionTest.java'` returns empty

#### M1d — Remove dead codegen that references old JNI path
- In `CCodeGenerator.java`, delete (or gate-off-by-flag) the code paths that emit `Java_..._nekoBindClass`, `JNINativeMethod[]` arrays for loader, old `JNI_OnLoad` body. The native binary still builds and loads (empty lib), but nothing interesting happens inside.
- Purpose: prevents confusion during M2. All actual native work happens in M2.

**Files**: `CCodeGenerator.java`

**Verification**:
- `./gradlew :neko-native:build`
- Build a fixture; resulting `libneko_*.so` loads without error when run (`java -jar <obfuscated>.jar` doesn't call any translated method, so no LinkageError yet — or, if it does, LinkageError is OK because we're not patching yet).

### M2 — Native bootstrap + VMStruct parser + manifest (no patching yet)

**Goal**: JNI_OnLoad resolves libjvm, parses VMStructs, locates all manifest entries in already-loaded classes, logs which ones match, but DOES NOT PATCH YET. This validates the discovery layer before the dangerous write layer.

#### M2a — Manifest generation in CCodeGenerator
- Emit manifest const struct (see §2.2.6 in Oracle output) per binding.
- Generate ONE C source file per build that contains `g_neko_manifest_methods[]` with entries `{ owner_internal, name, desc, hashes, flags, signature_id, impl_fn_ptr }`.
- `impl_fn_ptr` is a forward declaration to the generated C function (filled by linker).

**Files**: `CCodeGenerator.java`, `NativeTranslator.java` (binding metadata)

**Verification**:
- Generated `neko_native.c` contains expected manifest entries.
- `zig cc` compiles without error.

#### M2b — libjvm resolution + symbol import
Write `neko_bootstrap.c` (generated content in `CCodeGenerator`):
- `neko_resolve_libjvm()` — Linux `dlopen(NULL)`, macOS `dlsym(RTLD_DEFAULT)`, Windows `GetModuleHandleW("jvm.dll")`.
- Resolve the 20+ VMStructs symbols listed in Oracle §B3.
- Fail-closed path: if any required symbol is missing, log and return without patching.

**Files**: `CCodeGenerator.java`

**Verification**:
- Compile and load on Linux JDK 8/11/17/21. Confirm log lines "resolved N/20 symbols" show 20/20 on HotSpot.

#### M2c — VMStructs parser + offset table
- Parse `gHotSpotVMStructs[]` array: each entry has (typeName, fieldName, typeString, isStatic, offset/address).
- Build lookup table: `offset_of("Method", "_i2i_entry")` etc.
- Parse `gHotSpotVMTypes[]` for type sizes (sizeof(Method)).
- Parse `gHotSpotVMIntConstants[]` for the no-compile bits and `MethodFlags` bit values.
- Resolve per-JDK variants (AccessFlags vs MethodFlags; Universe vs CompressedOops).

**Files**: `CCodeGenerator.java` — the emitted `neko_vmstructs.c`

**Verification**:
- On each JDK, log full offset table. Values should match known OpenJDK source offsets (compare to hotspot/share/oops/method.hpp line ordering).

#### M2d — JVMTI bootstrap + class enumeration
- `vm->GetEnv(..., JVMTI_VERSION_1_2, (void**)&jvmti)`.
- Request capabilities: `can_generate_compiled_method_load_events = 1` (optional), `can_get_bytecodes = 1` (optional), `can_tag_objects = 0`, `can_generate_all_class_hook_events = 1`.
- `jvmti->GetLoadedClasses(&count, &classes)`.
- For each loaded class, `jvmti->GetClassSignature(class, &sig, &generic)` — if matches any manifest entry owner, continue.
- `jvmti->GetClassMethods(class, &mcount, &mids)` — for each `jmethodID`, `jvmti->GetMethodName(mid, &name, &sig, &generic)` — match to manifest entry.
- Dereference `jmethodID` → `Method*` per HotSpot: `Method::resolve_jmethod_id(mid)` is `*(Method**)mid`.

**Files**: `CCodeGenerator.java` — the emitted `neko_jvmti.c`

**Verification**:
- Log line "matched Method* for {owner}.{name}{desc} at {addr}" for each expected method.
- Cross-check by counting manifest entries vs matched entries.

#### M2e — NativeBuildEngine .S support
- Update `NativeBuildEngine.build()` to accept both `.c` and `.S` sources.
- Zig cc handles `.S` natively; ensure correct arch flag (`-target x86_64-linux-gnu`, `-target aarch64-linux-gnu`).
- Manifest output filenames: `neko_native.c`, `neko_bootstrap.c`, `neko_vmstructs.c`, `neko_jvmti.c`, `neko_stubs_<arch>.S` (.S added in M3).

**Files**: `NativeBuildEngine.java`

**Verification**:
- Build succeeds on all target triplets with `.c` files; `.S` tested in M3.

#### M2f — Integration smoke test (M2 gate)
- End-to-end: obfuscate TEST.jar, run it. Class loads, `NekoNativeLoader.load()` fires, `System.load(...)` fires, `JNI_OnLoad` logs "matched N/N methods", returns, `<clinit>` completes, Calc runs — and throws `LinkageError` on first translated method call (because M2 doesn't patch).
- Confirm NO crash, NO `UnsatisfiedLinkError`, the library loaded cleanly.

**Verification**:
- `java -Dneko.native.debug=1 -jar <obfuscated-TEST>.jar` — see log of N matched methods, then LinkageError as expected.

### M3 — Entry patching + signature-specific dispatch stubs + leaf execution

**Goal**: M2 discovery + patching. After M3: leaf methods run through raw C impl, zero JNI per call. Methods that call other Java methods or allocate still go through fallback (M4).

#### M3a — Signature enumeration + signature_id assignment
- Walk all manifest entries, compute canonical signature (static?, arg basic types, return basic type). Assign monotonic `signature_id`.
- Per signature, we'll generate ONE pair of stubs (i2i + c2i).

**Files**: `CCodeGenerator.java`, `NativeTranslator.java`

**Verification**: Logged signature table matches expectations.

#### M3b — x86_64 SysV i2i/c2i stub generation (.S)
- For each unique signature, emit two .S functions: `neko_i2i_sig_<id>` and `neko_c2i_sig_<id>`.
- i2i stub: follows HotSpot interpreter entry ABI (rbx=Method*, r13=sender_sp, r15=JavaThread*).
- c2i stub: follows HotSpot compiled-caller ABI (integer args in rsi/rdx/rcx/r8/r9, refs same but in oop form, FP in xmm0-7, stacked args per HotSpot compiled_arg_layout).
- Both stubs call a C dispatcher `neko_dispatch_i2i_sig_<id>(ctx, patch_ptr, arg_base, thread)` which:
  1. Unpacks args from interpreter frame or compiled arg regs
  2. Calls `impl_fn(ctx, receiver_or_nullptr, args...)`
  3. Checks `thread->_pending_exception`
  4. Packs return value back into interpreter/compiled return location
- Generated `.S` per arch: `neko_stubs_x86_64_sysv.S`, `neko_stubs_x86_64_win64.S`, `neko_stubs_aarch64.S`.

**Files**: `CCodeGenerator.java` + emitted .S

**Verification**:
- Compile with zig cc — passes.
- Disassemble produced .so — stubs have expected shape.

#### M3c — No-compile / no-inline flag mutation
- Per JDK version:
  - JDK 8/11/17: OR `_access_flags` with `JVM_ACC_NOT_C1_COMPILABLE | JVM_ACC_NOT_C2_COMPILABLE` (0x04000000 | 0x02000000)
  - JDK 11/17/21: OR `_flags` (as u2 for JDK ≤17, u4 for JDK 21 MethodFlags) with dont_inline bit (JDK 11/17: 1<<2; JDK 21: 1<<12)
  - JDK 21: OR `_flags` with `is_not_c2_compilable | is_not_c1_compilable` (1<<8 | 1<<9)
- Detect JDK version: parse via JVM version string (`JavaVM::GetVersion()` via JNI? — actually we can just read the HotSpot `gHotSpotVMIntConstants` "JAVA_SPEC_VERSION" entry).

**Files**: `CCodeGenerator.java` — emitted `neko_patcher.c`

**Verification**:
- After patch, read flags back via VMStructs offsets, assert bits are set.

#### M3d — Entry patch write sequence
- For each matched (manifest_entry, Method*) pair:
  1. Load `Method::_code` with acquire. If non-NULL → refuse, continue to next entry.
  2. Apply M3c flag mutations.
  3. `store_release(&method->_i2i_entry, stub_for_sig_id.i2i)`
  4. `store_release(&method->_from_interpreted_entry, stub_for_sig_id.i2i)`
  5. `store_release(&method->_from_compiled_entry, stub_for_sig_id.c2i)`
- Use GCC `__atomic_store_n(..., __ATOMIC_RELEASE)` or MSVC `_InterlockedExchangePointer`.

**Files**: `CCodeGenerator.java` — emitted `neko_patcher.c`

**Verification**:
- Log "patched {owner}.{name}{desc}" per entry, confirm post-patch flag/entry values via readback.

#### M3e — ClassPrepare callback
- JVMTI `ClassPrepare` event fires when a new class transitions to prepared state.
- Callback receives `(jvmtiEnv*, JNIEnv*, jthread, jclass)`.
- Match class against manifest; patch if found.

**Files**: `CCodeGenerator.java` — emitted `neko_jvmti.c`

**Verification**:
- Dynamically load a new class post-bootstrap; observe ClassPrepare fires and patching occurs.

#### M3f — Translator: drop JNI exports, emit raw C impls
- `NativeTranslator`: remove JNIEXPORT/JNIEnv*/jclass from function signature.
- Emit `static <retType> neko_impl_<id>(NekoThreadContext* ctx, <receiver?>, <args>)`.
- `CCodeGenerator.renderPrototype`: update accordingly.
- `CCodeGenerator.renderFunction`: remove JNI env variable, remove old JNI wrapper helpers.

**Files**: `NativeTranslator.java`, `CCodeGenerator.java`

**Verification**: Generated C compiles.

#### M3g — Leaf-method opcode support validation
- OpcodeTranslator: temporarily reject `INVOKEVIRTUAL/SPECIAL/STATIC/INTERFACE`, `NEW`, `NEWARRAY`, `ANEWARRAY`, `MULTIANEWARRAY`, `LDC <String>` (defer to M4).
- GETSTATIC/PUTSTATIC/GETFIELD/PUTFIELD on primitive types only.
- All arithmetic, control flow, local/array access on primitives — must work.

**Files**: `OpcodeTranslator.java`, `NativeTranslationSafetyChecker.java`

**Verification**:
- Compile obfuscated TEST.jar.
- Run it. Calc bench runs through native impl (confirm via debug log "entered neko_impl_<id>").
- Median `< 150ms` per existing perf test.

#### M3h — End-to-end M3 test
- `./gradlew :neko-test:test --tests "*NativeObfuscationPerfTest*"` passes.
- `./gradlew :neko-test:test --tests "*NativeObfuscationIntegrationTest*"` passes (re-enable disabled tests).
- `./gradlew :neko-test:test --tests "*NekoLoaderShapeTest*"` still passes.

### M4 — Outbound Java calls + allocation + string + exception propagation (completes v1)

**Goal**: relaxation of M3g restrictions. v1 aggressive target achieved.

#### M4a — Outbound method invocation (neko_invoke_java)
- `neko_invoke_java(NekoThreadContext* ctx, Method* target, uint8_t sig_id, const neko_slot* args) → neko_value`
- Uses pre-generated outbound C2-ABI marshalling stubs (per signature).
- Calls `target->_from_compiled_entry` directly.
- Spills live reference locals to thread-local shadow handle buffer before call (GC barrier).

**Files**: `CCodeGenerator.java` — `neko_outbound_<sig>.S` + `neko_invoke.c`, new `NativeOutboundCallTest.java`

**Verification**:
- New fixture class `OutboundHarness` with 3 methods all `@NativeTranslate`:
  - `static int add(int a, int b) { return a + b; }`
  - `static int square(int x) { return multiply(x, x); }`  // calls another translated method
  - `static int multiply(int a, int b) { return a * b; }`
- `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeOutboundCallTest"` passes, specifically:
  - `assertEquals(25, OutboundHarness.square(5))` — verifies Java→native→Java chain without JNI
  - `assertEquals(7, OutboundHarness.add(3, 4))` — leaf baseline
- `JNI_OnLoad` logs "installed N outbound stubs" where N == number of unique sig_ids in manifest
- Under `-Dneko.native.trace=call`, translated method entry/exit is logged; JNI wrapper log lines are ABSENT in the hot path (only appear once during bootstrap)

#### M4b — Field offset metadata parser
- At bootstrap, for each field used by translated code, resolve (Klass, fieldName, fieldDesc) → offset via parsing `InstanceKlass::_fields` (a u2[] with field info entries).
- Cache `(owner, name, desc) → offset` in a hash table embedded in manifest.
- Use for GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC at runtime.

**Files**: `CCodeGenerator.java`, new `NativeFieldAccessTest.java`

**Verification**:
- New fixture class `FieldTypesHarness` with `@NativeTranslate`-marked getters/setters for fields: `byte b; short s; char c; int i; long l; float f; double d; boolean z; Object ref; String str; int[] arr;` + static variants of each.
- `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeFieldAccessTest"` passes with:
  - for each field type T: `setT(expectedValue); assertEquals(expectedValue, getT())` after native translation
  - static field variants: `setStaticT(v); assertEquals(v, getStaticT())`
-  - reference field reads only: seed `ref`, `str`, and `arr` from Java, then `assertSame(seedRef, getRef())`, `assertSame(seedStr, getStr())`, and `assertSame(seedArr, getArr())`
- Note: Reference-field `PUTFIELD` / `PUTSTATIC` paths are scope-locked to M5h (GC write barriers) and MUST fail-closed at `NativeTranslationSafetyChecker` in M4 scope.

#### M4c — Compressed oop decode/encode helpers
- Emit inline helpers:
  ```c
  static inline void* decode_oop(uint32_t narrow) {
      return (narrow == 0) ? NULL : (void*)((uintptr_t)narrow_oop_base + ((uintptr_t)narrow << narrow_oop_shift));
  }
  static inline uint32_t encode_oop(void* oop) { ... }
  ```
- Pick correct globals per JDK (Universe vs CompressedOops).

**Files**: `CCodeGenerator.java`, new `NativeCompressedOopTest.java`

**Verification**:
- Same `FieldTypesHarness` fixture run under BOTH JVM flag sets:
  - Compressed mode: `java -XX:+UseCompressedOops -XX:+UseCompressedClassPointers -jar <obfuscated>.jar`
  - Uncompressed mode: `java -XX:-UseCompressedOops -XX:-UseCompressedClassPointers -jar <obfuscated>.jar`
- Both runs must produce identical output for the reference-field read-only checks from M4b
- `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeCompressedOopTest"` runs both modes via `@ParameterizedTest` with `CommandLineBuilder` variants and asserts exit code 0 + `assertEquals("OK", stdout.trim())`
- On JDK 21 additionally with `-XX:+UseCompactObjectHeaders` (preview flag) — if collector gating refuses, expected log line "unsupported object header mode, falling back to throw body" appears

#### M4d — TLAB allocation fast path
- Read `JavaThread::_tlab.{_top, _end, _start}` offsets.
- Fast path in C:
  ```c
  void* neko_alloc_fast(NekoThreadContext* ctx, Klass* klass, size_t size) {
      void* top = *(void**)(ctx->thread + tlab_top_off);
      void* end = *(void**)(ctx->thread + tlab_end_off);
      if ((char*)top + size > (char*)end) return NULL;  // slow path
      *(void**)(ctx->thread + tlab_top_off) = (char*)top + size;
      // initialize mark word, klass ptr, clear body
      return top;
  }
  ```
- Slow path: fall back to VM helper resolved via mangled symbol or return NULL → throw OutOfMemoryError via pending_exception.

**Files**: `CCodeGenerator.java`, new `NativeAllocTest.java`

**Verification**:
- New fixture class `AllocHarness` with `@NativeTranslate` methods:
  - `static int[] makeIntArray(int len) { return new int[len]; }`
  - `static Object[] makeObjArray(int len) { return new Object[len]; }`
  - `static java.util.ArrayList<Integer> makeList() { return new java.util.ArrayList<>(); }`  (exercises NEW + <init>)
- `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeAllocTest"` passes:
  - `assertEquals(1024, makeIntArray(1024).length)`
  - Loop of 1_000_000 `makeIntArray(16)` under `-Xmx128m -Xlog:gc*:file=gc.log` — completes without OOM, `gc.log` shows ≥ 3 young GC events (confirms GC safety)
  - `makeObjArray(100)` returned array slots are all `null`
  - `makeList()` returns a usable `ArrayList<Integer>`; `list.add(42); assertEquals(1, list.size());`
- Stress test: run 1M allocations across 8 threads concurrently — no VM crash, no heap corruption (confirmed by `-XX:+UseG1GC -XX:+VerifyBeforeGC -XX:+VerifyAfterGC`)

#### M4e — String creation (JDK 9+ compact strings)
- For `LDC <String>` and `NEW String / <init>(char[])`:
  - Allocate `java/lang/String` via M4d.
  - JDK 8: allocate `char[]`, copy, set `value` (offset from field parser).
  - JDK 9+: determine coder (LATIN1 if all chars <= 0xFF, UTF16 otherwise), allocate `byte[]` of correct size, copy, set `value`, `coder`, `hash = 0`.

**Files**: `CCodeGenerator.java`, new `NativeStringTest.java`

**Verification**:
- New fixture class `StringHarness` with `@NativeTranslate`:
  - `static String latinOnly() { return "Hello World ASCII only"; }`  // LDC + LATIN1
  - `static String withUtf16() { return "emoji here: \uD83D\uDC31 cat"; }`  // LDC + UTF16 forced (surrogate)
  - `static String empty() { return ""; }`
  - `static String concatenated(String a, int n) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < n; i++) sb.append(a); return sb.toString(); }`  // exercises new String(byte[], int) paths
- `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeStringTest"` passes:
  - `assertEquals("Hello World ASCII only", latinOnly())`
  - `assertEquals("emoji here: 🐱 cat", withUtf16())`
  - `assertEquals("", empty())`
  - `assertEquals("xxxxxxxxxx", concatenated("x", 10))`
  - JDK 9+: reflection assertion that `latinOnly().coder == 0` (LATIN1) and `withUtf16().coder == 1` (UTF16)
  - `latinOnly().hashCode() == "Hello World ASCII only".hashCode()` — confirms layout compatible with JDK's String internals
- Test must pass on both JDK 8 and JDK 17 runs (via `@EnabledForJreRange` if needed)

#### M4f — Exception propagation
- After any outbound call or allocation, check `thread->_pending_exception`.
- On non-null: jump to dispatcher exit that restores stub's caller stack and returns control to HotSpot's forward-exception path.
- In dispatcher stub: if pending_exception != NULL on return, tail into `neko_forward_exception_to_vm` which synthesizes an interpreter-frame-like exit.

**Files**: `CCodeGenerator.java`, new `NativeExceptionPropagationTest.java`

**Verification**:
- New fixture class `ExceptionHarness` with `@NativeTranslate`:
  - `static void thrower(String msg) { throw new IllegalStateException(msg); }`
  - `static int catcher() { try { thrower("boom"); return 1; } catch (IllegalStateException e) { return e.getMessage().length(); } }`
  - `static int rethrowWrapper() { try { thrower("inner"); return -1; } catch (IllegalStateException e) { throw new RuntimeException("wrapped", e); } }`
  - `static int nullDeref(Object o) { return o.hashCode(); }`  // NPE from translated code
- `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeExceptionPropagationTest"` passes:
  - `assertEquals(4, ExceptionHarness.catcher())`  // "boom".length() == 4
  - `RuntimeException ex = assertThrows(RuntimeException.class, ExceptionHarness::rethrowWrapper); assertEquals("wrapped", ex.getMessage()); assertTrue(ex.getCause() instanceof IllegalStateException); assertEquals("inner", ex.getCause().getMessage());`
  - `assertThrows(NullPointerException.class, () -> ExceptionHarness.nullDeref(null))`
  - Stack trace of caught exception must contain a frame naming `thrower` (string check: `Arrays.stream(ex.getStackTrace()).anyMatch(f -> f.getMethodName().equals("thrower"))`)

#### M4g — Relax NativeTranslationSafetyChecker
- Remove M3g temporary rejections. Still reject: synchronized, ctor, clinit, abstract, native, bridge methods.
- Allow: INVOKE*, NEW, NEWARRAY, ANEWARRAY, MULTIANEWARRAY, `ARRAYLENGTH`, `CHECKCAST`, `INSTANCEOF`, primitive `*ALOAD` / `*ASTORE`, `LDC` String + reference constants, `GETFIELD` / `GETSTATIC` for all types, and `PUTFIELD` / `PUTSTATIC` for primitive types only.
- Keep fail-closed until later milestones for: reference `AASTORE`, `MONITORENTER`, `MONITOREXIT`, `INVOKEDYNAMIC`, and reference `PUTFIELD` / `PUTSTATIC` until M5h lands GC write barriers.

**Files**: `NativeTranslationSafetyChecker.java`

**Verification**:
- Re-run obfuscation on `test-jars/TEST.jar` with unchanged config. Previously rejected methods (count via stage log "rejected N methods") now translate.
- Specific acceptance: methods in `pack/tests/basics/runable/Task.class` (2201 bytes, contains INVOKEs) must be translatable — run obfuscation and grep stage log for `pack/tests/basics/runable/Task.run()V -> translated`.
- No regressions in M3 leaf tests: `./gradlew :neko-test:test --tests "*NativeObfuscation*"` all pass.
- Counter assertion: compare pre-M4g and post-M4g `translatedMethodCount` in stage stats — post must be ≥ 2× pre (verified by capturing log line `NativeStageResult: translated N methods`).

#### M4i — Array creation
- Support `NEWARRAY` for all primitive element tags (`boolean`, `byte`, `char`, `short`, `int`, `long`, `float`, `double`), `ANEWARRAY` for object arrays, `MULTIANEWARRAY` for N-dimensional arrays, and `ARRAYLENGTH`.
- Add per-type array `Klass*` lookup cache keyed by `(element_klass, dim)` so repeated allocations do not re-walk VM metadata.
- Allocate via TLAB when possible, with correct array header initialization: mark word + klass + length; fall back to slow path / pending OOME on failure.
- Deliverables: `neko_array_new_primitive(thread, type_tag, len)`, `neko_array_new_object(thread, element_klass_ptr, len)`, `neko_array_new_multi(thread, element_klass_ptr, dims_array, depth)`.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = process exit code `0`; fail = any non-zero exit.
- Output gate: test log from `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java` must include assertions covering `NEWARRAY`, `ANEWARRAY`, `MULTIANEWARRAY`, and `ARRAYLENGTH` on the `test-jars/TEST.jar` fixture; pass = every array case returns expected length / nesting shape, fail = any `LinkageError`, `ArrayStoreException`, or VM crash on supported cases.
- Output gate: debug trace must show array-klass cache hits after the first repeated allocation site; pass = at least one `cache-hit` pattern for a repeated `(element_klass, dim)` allocation site, fail = repeated full-resolution on every iteration.

#### M4j — Array access
- Implement `IALOAD`, `LALOAD`, `FALOAD`, `DALOAD`, `AALOAD`, `BALOAD`, `CALOAD`, `SALOAD` and matching primitive / reference `*ASTORE` lowering.
- Insert bounds check before every translated array load/store: if `index < 0 || index >= length`, write `ArrayIndexOutOfBoundsException` into `Thread::_pending_exception` and exit through the standard exception path.
- Keep reference `AASTORE` fail-closed in `NativeTranslationSafetyChecker` until M5h lands the required GC write barriers; primitive `*ASTORE` paths are enabled in M4.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/NativeTranslationSafetyChecker.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java` must assert round-trip correctness for every supported `*ALOAD` / `*ASTORE` opcode and identity preservation for `AALOAD`; pass = expected values match byte-for-byte / bit-for-bit, fail = mismatch or silent corruption.
- Output gate: translated out-of-bounds fixture in `test-jars/TEST.jar` must throw `ArrayIndexOutOfBoundsException` via pending-exception forwarding; pass = exact exception type observed and no SIGSEGV, fail = crash or wrong exception type.
- Output gate: stage log must still show reference `AASTORE` rejected before M5h; pass = explicit fail-closed rejection line for that opcode, fail = reference-store translation enabled early.

#### M4k — Type checks
- Implement `CHECKCAST` and `INSTANCEOF`.
- For class checks, walk `Klass::_super` chain; for interface checks, iterate `Klass::_secondary_supers` (and `InstanceKlass::_secondary_supers_bitmap` on JDK 21+) until match / miss.
- Resolve target `Klass*` from the constant pool with a per-call-site cache.
- On `CHECKCAST` miss, throw `ClassCastException` through `Thread::_pending_exception`; on `INSTANCEOF`, return integer `0` / `1` per JVM semantics.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java` must cover superclass, subclass, unrelated-class, and interface cases from `test-jars/TEST.jar`; pass = `INSTANCEOF` yields exact `0` / `1` and successful `CHECKCAST` preserves object identity, fail = any hierarchy miss on valid input.
- Output gate: failing cast fixture must surface `ClassCastException` with the original translated method in the stack trace; pass = exact exception type + method frame present, fail = `LinkageError`, `NullPointerException`, or missing frame.

#### M4l — Implicit NPE
- Insert null-receiver checks before translated `GETFIELD`, `PUTFIELD`, `INVOKEVIRTUAL`, `INVOKESPECIAL`, `INVOKEINTERFACE`, `ARRAYLENGTH`, all translated `*ALOAD` / `*ASTORE`, `ATHROW`, and the `MONITORENTER` path that becomes reachable in M5f.
- On null, synthesize `NullPointerException` by writing `Thread::_pending_exception` and returning through the standard exception-forward path; do not rely on host C UB / segfaults.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: null-receiver fixtures covering field access, virtual invoke, arraylength, array load/store, and `ATHROW` in `test-jars/TEST.jar` must all throw `NullPointerException`; pass = exact exception type from Java side for every case, fail = crash, wrong exception, or host-side null dereference.
- Output gate: log must not show raw signal / segmentation-fault signatures during these checks; pass = none present, fail = any low-level fault marker.

#### M4m — Divide-by-zero
- Insert explicit zero checks before `IDIV`, `LDIV`, `IREM`, and `LREM`.
- On zero divisor, construct `ArithmeticException` with message `"/ by zero"` via the pending-exception path.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: integer and long divide / remainder zero-divisor fixtures in `test-jars/TEST.jar` must throw `ArithmeticException` with message `"/ by zero"`; pass = exact type + exact message, fail = mismatched message, wrong type, or hardware trap.

#### M4n — Reference LDC
- Extend `LDC`, `LDC_W`, and `LDC2_W` lowering beyond primitive / string constants to class references, `MethodHandle`, and `MethodType`.
- Resolve constants by walking the `ConstantPool` entry, `_cache`, and `resolved_references` array with a lazy per-LDC-site cache.
- Keep fail-closed behavior if a reference constant cannot be resolved safely on a given JDK / constant-pool layout.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: class-literal, `MethodHandle`, and `MethodType` fixtures in `test-jars/TEST.jar` must survive translation and compare equal to the JVM-resolved Java baseline; pass = equality / descriptor checks succeed, fail = unresolved constant or wrong target.
- Output gate: repeated execution from the same LDC site must log at least one cache hit after the first resolution; pass = cache-hit pattern present, fail = full constant-pool walk on every invocation.

#### M4o — Full integration + perf
- All existing tests pass.
- `Calc` median `< 150ms` (ideally noticeably less than current).
- obfusjack-test21 / SnakeGame fixtures run to completion.
- Gate scope expanded to include arrays, array access, type checks, implicit NPE, divide-by-zero, and reference-LDC coverage in the aggressive fixture suite.

**Verification**:
- Command: `./gradlew :neko-test:test --tests "*NativeObfuscation*"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java` must cover outbound calls, alloc, strings, exception propagation, arrays, type-checks, implicit NPE, div-zero, and reference-LDC on `test-jars/TEST.jar`; pass = all assertions green, fail = any remaining fallback `LinkageError` for a now-in-scope opcode.
- Output gate: `java -jar <obfuscated-TEST>.jar` runs all of `test-jars/TEST.jar`'s `pack.Main` cases, output matches pre-obfuscation output byte-for-byte except for the known `Test 2.8: Sec ERROR` baseline; pass = exact match under that allowance, fail = drift or VM crash.
- Output gate: `java -jar <obfuscated-obfusjack-test21>.jar` completes within current threshold (`120s`); pass = exit code `0`, fail = timeout / crash.
- Output gate: `java -jar <obfuscated-SnakeGame>.jar` fails only on `HeadlessException`; pass = that exact expected failure mode and no `UnsatisfiedLinkError`, `LinkageError`, `NoClassDefFoundError`, or VM crash, fail = anything else.

### M5 — Hardening + JDK matrix + unload/redefine

#### M5a — ClassUnload JVMTI callback
- On class unload, find manifest entries for that class, null out cached `Method*` pointers (if we cached any — we shouldn't if using signature-shared stubs).
- Install `SetEventCallbacks` for `JVMTI_EVENT_COMPILED_METHOD_UNLOAD` and `JVMTI_EVENT_CLASS_UNLOAD`. The latter fires after the class is unloaded, so the callback should log + decrement a counter, not dereference old `Method*`.

**Files**: `CCodeGenerator.java` (emitted `neko_jvmti.c`), new `NativeClassUnloadTest.java`

**Verification**:
- New fixture: obfuscate a `.class` containing `@NativeTranslate` methods, load it via a throwaway `URLClassLoader` inside the test, invoke a translated method (confirm it works), then null the loader reference + `System.gc()` until JVMTI ClassUnload callback fires (verified by log line `neko: class unload event for {name}`).
- `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeClassUnloadTest"` passes:
  - `assertEventReceived("ClassUnload", Duration.ofSeconds(30))` — test waits up to 30s with periodic `System.gc()` calls for the event
  - After unload: re-load a DIFFERENT class with a method having the same internal name+desc — it must be patched independently (confirmed by stage log showing fresh `Method*` address distinct from the unloaded one)
  - No VM crash under `-XX:+VerifyBeforeGC -XX:+VerifyAfterGC`

#### M5b — RedefineClasses full support
- Support `JVMTI_EVENT_CLASS_FILE_LOAD_HOOK`, `RetransformClasses`, and `RedefineClasses` for already-patched classes instead of fail-closing.
- Detect redefine / retransform callbacks, locate the new `Method*` for previously patched `(owner, name, desc)` signatures, and re-apply no-compile bits plus entry-patch writes to the replacement methods.
- If callback metadata is incomplete or the replacement method cannot be matched safely, fail closed for that redefine attempt and leave the Java throw-body fallback intact per C8.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: redefine / retransform fixture in `test-jars/TEST.jar` must first call the original patched implementation, then redefine the class body, then observe the new behavior through the re-patched `Method*`; pass = post-redefine return value / side effect changes exactly to the new class bytes, fail = stale old behavior or `UnsatisfiedLinkError`.
- Output gate: debug log must show `redefine detected` and `re-patched {owner}.{name}{desc}` for the replacement `Method*`; pass = both patterns present, fail = silent no-op.
- Output gate: if a safety mismatch is injected deliberately, behavior must fail closed to the Java throw body rather than crash; pass = `LinkageError` from the fallback body, fail = VM crash / wrong method dispatch.

#### M5c — Collector / feature gating
- At bootstrap: explicitly classify `Serial`, `Parallel`, `G1`, `ZGC`, and `Shenandoah`, plus compact-header mode, before enabling translated reference traffic.
- Auto-select barrier mode at bootstrap from the detected collector; `Serial` / `Parallel` / `G1` are in-scope, `ZGC` / `Shenandoah` remain gated until their dedicated M5h runtime support lands.
- If the collector cannot be classified, or if reference-field writes are requested while GC mode is unknown, refuse patching for those methods and keep the Java fallback body intact.
- Detection via VMStructs: read collector / flag state from `Universe->_collectedHeap` type metadata plus `UseSerialGC`, `UseParallelGC`, `UseG1GC`, `UseZGC`, `UseShenandoahGC`, and `UseCompactObjectHeaders` globals as available on the running JDK.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/NativeTranslationSafetyChecker.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: parameterized GC smoke on `test-jars/TEST.jar` must show `Serial`, `Parallel`, and `G1` patch successfully with translated reference traffic enabled; pass = exit code `0` and translated output matches baseline for all three, fail = unexpected fallback or crash.
- Output gate: `ZGC` / `Shenandoah` pre-M5h runs must fail closed with explicit collector-name log lines; pass = `LinkageError` from fallback body and no crash, fail = partial patching of reference writes or wrong collector classification.
- Output gate: unknown / unclassified collector simulation must refuse patching for reference-field writes; pass = explicit `unknown collector` rejection line and fallback behavior, fail = silent patch attempt.

#### M5d — CI matrix for JDK 8/11/17/21
- Set up CI config to run `NativeObfuscationPerfTest` under each JDK.
- Document in `README.md` and `docs/`.
- GitHub Actions workflow: `.github/workflows/native-matrix.yml` with:
  - `strategy.matrix.jdk`: `[8, 11, 17, 21]`
  - `strategy.matrix.os`: `[ubuntu-22.04]` (Linux x86_64 only for v1)
  - setup-java action for each JDK
  - steps: `./gradlew :neko-test:test --tests "*Native*"`
  - upload gradle test report artifact on failure

**Files**: new `.github/workflows/native-matrix.yml`, updated `README.md` (add CI badge + JDK support table), updated `docs/CONFIG.md` (document supported JDK range + CI matrix)

**Verification**:
- (a) Local (primary): for each JDK in `{8, 11, 17, 21}`, invoke `./gradlew -PjavaToolchainVersion=<v> :neko-test:test --tests "*NativeObfuscation*"` — all must exit `0`. Record results in `/tmp/m5d-local-matrix.log`.
- (b) CI (secondary): `.github/workflows/native-matrix.yml` must trigger on PR; if `gh` CLI is available, run `gh workflow run native-matrix.yml` then `gh run list --limit 1 --json conclusion | jq -r '.[0].conclusion'` and require `success`. If `gh` is unavailable, verify the workflow file exists and passes `yamllint`.
- Workflow runtime: all 4 JDK jobs complete within 30 min budget (per-job 7.5 min)
- README has a passing status badge linking to the matrix workflow
- Note: If the Gradle toolchain plugin/configuration is not already present, add it as part of M5d (not as a separate subtask).

#### M5e — Perf tuning
- Measure; tune. Optimize signature dispatcher hot-path. Consider inline caching for outbound calls.
- Target: close the gap toward ~2–5 ns per-call boundary overhead (vs current ~35-60 ns JNI baseline).

**Files**: `CCodeGenerator.java` (optimized emission), new `neko-test/src/test/java/.../NativeDispatchMicrobench.java`, updated `neko-test/build.gradle.kts` (add JMH dependencies), new `docs/performance.md`

**Verification**:
- `./gradlew :neko-test:test --tests "*NativeObfuscationPerfTest*nativeObfuscation_TEST_calcBenchmarkMedianUnder150ms"` passes with median **< 100ms** (aggressive tightened threshold)
- Micro-benchmark harness: `neko-test/src/test/java/.../NativeDispatchMicrobench.java` using JMH (add `org.openjdk.jmh:jmh-core` to test classpath):
  - Benchmark `nekoLeafCall` — empty translated method, report ns/op
  - Benchmark `nekoOutboundCall` — translated method calling translated method, report ns/op
  - Baseline benchmark `jniBaselineCall` — keep old JNI path as control (temporary reference implementation, deleted after report generated)
  - Target: nekoLeafCall ≤ 10 ns/op on modern x86_64 (baseline ~50 ns/op JNI)
- Generated report at `docs/performance.md` contains:
  - Hardware/JDK context
  - Table of ns/op for each benchmark under each JDK
  - Comparison to pre-JNI-free baseline (from current Calc perf test logs)
- `grep "nekoLeafCall" docs/performance.md` returns non-empty

#### M5f — MONITORENTER / MONITOREXIT
- Implement `MONITORENTER` / `MONITOREXIT` with a fast path CAS on the object mark word (`UNLOCKED` → lightweight / monitor-owned) and a slow path via the resolved `ObjectSynchronizer::enter` / `exit` equivalent.
- JDK ≤ 15 must account for biased-locking-era mark-word patterns; JDK ≥ 15 assumes no biased-locking fast-path variant.
- Unblock monitor opcodes in `NativeTranslationSafetyChecker` only after both fast path and slow path are available for the running JDK.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/NativeTranslationSafetyChecker.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: synchronized fixture in `test-jars/TEST.jar` must serialize contended increments correctly across multiple threads; pass = final counter equals expected value and no deadlock, fail = lost updates / hang.
- Output gate: translated monitor enter on `null` must still route through the M4l NPE path; pass = exact `NullPointerException`, fail = crash or wrong exception.
- Output gate: debug log must show both fast-path lock acquisition and at least one slow-path fallback under contention; pass = both patterns present, fail = only one path observed across the dedicated fixture.

#### M5g — INVOKEDYNAMIC
- Implement `INVOKEDYNAMIC` resolution by evaluating the bootstrap method, materializing the `CallSite` / `MethodHandle` chain on first invocation, and caching the resolved target `Method*` in a per-call-site slot.
- Cover lambda metafactory (`LambdaMetafactory`), string concat (`StringConcatFactory`), and record helper bootstrap patterns (`ObjectMethods` for `toString` / `hashCode` / `equals`).
- Keep fail-closed behavior if the bootstrap linkage shape cannot be recognized safely on a given JDK.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/NativeTranslationSafetyChecker.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: lambda, string-concat, and record-helper fixtures in `test-jars/TEST.jar` must all survive translation; pass = returned values match Java baseline exactly, fail = bootstrap linkage error or wrong target.
- Output gate: repeated execution of the same indy call site must log a first-hit resolve and subsequent cache-hit reuse; pass = both patterns present, fail = bootstrap rerun on every invocation.

#### M5h — GC write barriers
- Emit per-GC barrier stubs for translated reference writes.
- `Serial` / `Parallel`: post-write card mark on reference-field store.
- `G1`: SATB pre-barrier (load old value, enqueue if marking active) plus card-mark post-barrier.
- `Shenandoah`: load-reference barrier plus SATB support.
- `ZGC`: load barrier plus remembered-set / colored-pointer-compatible handoff.
- Detect runtime GC mode at bootstrap from VMStructs (`Universe->_collectedHeap` concrete type or equivalent exported metadata) and select the matching barrier mode.
- Unlock M4b reference `PUTFIELD` / `PUTSTATIC` and M4j reference `AASTORE` only when the active barrier mode is known and emitted.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/NativeTranslationSafetyChecker.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: translated reference-field stores and reference `AASTORE` on `test-jars/TEST.jar` must preserve object identity across forced GC under `Serial`, `Parallel`, and `G1`; pass = identity checks remain true after repeated `System.gc()` / allocation churn, fail = stale / lost reference.
- Output gate: `ZGC` and `Shenandoah` runs must either execute with their dedicated barrier mode enabled or fail closed explicitly if the mode cannot be emitted on that JDK build; pass = correct barrier-mode log or explicit fallback `LinkageError`, fail = silent unbarriered patching.

#### M5i — Stack trace correctness
- Verify that `Throwable.getStackTrace()` for exceptions thrown from patched methods reports the original class / method / source-file / line, not `Native Method`.
- Preserve the original `LineNumberTable` / `ConstMethod` metadata path in `NativeCompilationStage` so frame walking can reuse bytecode-side metadata.
- If HotSpot still treats the patched method as native for stack-trace purposes, add a post-patch flag scrub that retains entry redirection while clearing the user-visible native indicator.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/stage/NativeCompilationStage.java`, `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: exception fixtures thrown from patched methods in `test-jars/TEST.jar` must show the original declaring class, method name, source file, and line number; pass = stack-trace elements match the bytecode baseline and no frame is labeled `Native Method`, fail = missing or native-labeled frames.
- Output gate: if post-patch flag scrub is required, debug log must state that it was applied only for the affected JDK / method shape; pass = explicit conditional log, fail = unconditional scrub without evidence.

#### M5j — Compact headers (JDK 21+)
- Detect `-XX:+UseCompactObjectHeaders` via exported VM flags / VMStructs metadata.
- Adjust object-header width assumptions (`8` vs `12` bytes / platform-specific encoded layout), klass-pointer encoding, TLAB allocation math, and array base-offset computation accordingly.
- Emit runtime branches from `CCodeGenerator` so non-compact and compact-header modes share one artifact while keeping fail-closed behavior when required metadata is missing.

**Files**: `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `test-jars/TEST.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "dev.nekoobfuscator.test.NativeObfuscationIntegrationTest"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: JDK 21 run of `test-jars/TEST.jar` under `-XX:+UseCompactObjectHeaders` must complete the translated alloc / array / field fixtures with the same observable output as the non-compact baseline; pass = output parity and no header-math crash, fail = mismatch / crash.
- Output gate: bootstrap log must state which header mode was detected and which layout branch was selected; pass = explicit compact-header mode line, fail = silent assumption.

#### M5k — Full v1-complete smoke + perf gate
- Run the ship-ready smoke across JDK `8` / `11` / `17` / `21` × GC `{Serial, Parallel, G1}` with the full opcode fixture suite now including monitor, indy, arrays, type-checks, implicit exceptions, reference-LDC, GC barriers, and compact-header branches where supported.
- Keep a perf regression check against the current JDK baseline while preserving C8 fail-closed behavior for any unsupported runtime combination.

**Files**: `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationIntegrationTest.java`, `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationPerfTest.java`, `test-jars/TEST.jar`, `test-jars/obfusjack-test21.jar`, `test-jars/SnakeGame.jar`

**Verification**:
- Command: `./gradlew :neko-test:test --tests "*NativeObfuscationIntegrationTest*" --tests "*NativeObfuscationPerfTest*"` — pass = exit code `0`; fail = non-zero exit.
- Output gate: matrix runs across JDK `8` / `11` / `17` / `21` with `Serial` / `Parallel` / `G1` must report green for the full opcode fixture suite in `test-jars/TEST.jar`; pass = all 12 combinations succeed, fail = any unexpected red cell.
- Output gate: `test-jars/obfusjack-test21.jar` and `test-jars/SnakeGame.jar` smoke runs must preserve the existing M4 expectations under every supported JDK / GC combination that can execute them; pass = stable behavior, fail = regression.
- Output gate: perf regression check from `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationPerfTest.java` must stay within the approved threshold versus the per-JDK baseline; pass = no regression beyond threshold, fail = threshold breach.

---

## 3. Delegation prompt template

Each sub-task delegated to `ultrabrain` with this shape:

```
TASK: {milestone}.{sub-id} — {one line}
REFERENCE: /mnt/d/Code/Security/NekoObfuscator/.sisyphus/plans/jni-free-native-obfuscation.md
  Read sections §0 (constraints), §1 (architecture), §2.{milestone}.{sub} (this task).
EXPECTED OUTCOME:
  - {deliverable 1}
  - {deliverable 2}
REQUIRED TOOLS: Read, Edit, Write, Bash (gradle), Lsp_diagnostics, Grep, Glob
MUST DO:
  - Match existing code style in neko-native / neko-runtime modules
  - Run lsp_diagnostics on every changed file after editing
  - Run `./gradlew :<affected>:build` after editing
  - Verify by {verification criterion from §2}
  - Report any deviation from the spec in this doc; do not silently alter behavior
MUST NOT DO:
  - Modify code outside the file list in §1.5 for this milestone
  - Suppress type errors (no @SuppressWarnings except where justified)
  - Skip verification steps
  - Commit (user will review and commit later)
  - Create new top-level Java classes in neko-runtime (C4 constraint: ONE class only)
CONTEXT:
  Current code uses {summarize current state of the file being touched} — see grep/glob results in §1.5.
  This sub-task depends on: {list prior sub-tasks}.
  This sub-task is a prerequisite for: {list dependent sub-tasks}.
```

## 4. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | HotSpot ABI drift in i2i/c2i stubs across JDKs/arches | High | High | Primary path x86_64 Linux first. Gate by strict VMStruct/feature detection. Fail closed on mismatch. |
| 2 | Compiler sees throw-body bytecode, inlines before patch matters | Medium | High | Patch at load/ClassPrepare time. Reject methods with `_code != NULL`. Set no-compile/no-inline bits before entry patch. |
| 3 | JDK 21 MethodFlags split causes wrong flag mutation | Medium | High | Explicit version gate. Do not reuse 8/11/17 AccessFlags code path on 21. Dedicated verification test. |
| 4 | Raw oop lifetime breaks under safepoint-capable methods (M4) | Medium | High | Spill ref locals to shadow buffer before outbound/alloc. Keep collector gating. Exclude complex GC modes. |
| 5 | Win64/macOS/AArch64 ABI portability issues | Medium | Medium | Linux x86_64 first. Stubs generated per arch from same signature data. Keep platform-specific .S files minimal. |

## 5. Verification gates

### After M1
- `./gradlew :neko-native:build :neko-runtime:build :neko-test:test --tests "*NekoLoader*"` passes
- Rewritten obfuscated fixture throws `LinkageError` on translated method call (expected)

### After M2
- Obfuscated fixture loads, library loads, `JNI_OnLoad` logs match count, `<clinit>` completes, translated call still throws `LinkageError` (no patching yet)

### After M3 gate (v1 leaf)
- All `NativeObfuscation*Test` pass
- Calc median `< 150ms`
- No JNI calls in hot path (verified by perf_events profiling if possible)

### After M4 gate (v1 aggressive complete)
- Full test suite passes across `test-jars/TEST.jar` / `test-jars/obfusjack-test21.jar` / `test-jars/SnakeGame.jar`
- Tests covering outbound call / alloc / string / exception / arrays / type-checks / implicit NPE / div-zero / reference-LDC pass
- Any newly in-scope aggressive opcode path either executes correctly or fails closed per C8; no VM crash / silent corruption

### After M5 gate (ship-ready)
- JDK 8/11/17/21 × GC `{Serial, Parallel, G1}` ship matrix green for the full opcode fixture suite
- `Serial` / `Parallel` / `G1` use the selected barrier mode successfully; `ZGC` / `Shenandoah` either execute with M5h support or fail closed explicitly
- Redefine / retransform, monitor, indy, stack-trace, and compact-header gates all pass on supported runtimes
- Perf gate met on all JDKs with no regression beyond the approved baseline threshold

---

## 6. Open design questions (flag during implementation; not blocking PLAN approval)

- **Q1**: Manifest entry lookup in `g_neko_manifest_methods[]` — O(N) linear scan at bootstrap is fine for N < 1000. If a fixture crosses 10000, consider precomputed hash → index map. Defer to M5 if observed.
- **Q2**: Should `static { NekoNativeLoader.load(); }` be emitted on EVERY target class or just one "anchor" class? Emitting on every class is robust (each class triggers native load independently) but slightly redundant. Oracle recommended emitting on every class — we follow.
- **Q3**: What happens if obfuscated app uses a separate ClassLoader for target classes and `NekoNativeLoader` is not visible to that loader? → user's responsibility. We document "must be visible on system classloader". Current behavior is same.
- **Q4**: Windows compiled-caller ABI (different from SysV) — M3b has a note, but if stub generation is tricky we defer Win64 to after M4. Primary target is Linux x86_64.

---

## 7. Traceability

- Oracle architecture session: `ses_2607398dcffeNHXeknj4hAOrei` (task `bg_3c121b0b`)
- Research sessions:
  - Current pipeline mapping: `ses_2608e204effeGVcBoxQaxWllXq` (task `bg_d645af11`)
  - Runtime loader mapping: `ses_2608ded58ffe1pALDZAc5Z3O0l` (task `bg_23029451`)
  - Reference impl / annotation / config: `ses_2608dc34effeyZbfd2t4jdS2pv` (task `bg_b6fce49d`)
  - Obfuscator techniques survey: `ses_2608d30b2ffelyPqzlpOxp0ZOS` (task `bg_c481b3f2`)
- User constraints locked in conversation messages m0009, m0011, m0013.

---

## 8. Scope Change Log

- `v1-完整` scope locked on 2026-04-18; added `M4i`-`M4n` (arrays / type-check / implicit-NPE / div-zero / reference-LDC), expanded `M5b` (RedefineClasses full support) and `M5c` (5-GC gating), added `M5f`-`M5j` (monitor / indy / GC-barrier / stack-trace / compact-headers).
- Reclassified top-level redefinition handling from a §0 non-goal into in-scope v1-完整 work under `M5b`, aligning the plan with full `JVMTI_EVENT_CLASS_FILE_LOAD_HOOK` / `RetransformClasses` / `RedefineClasses` coverage.

---

**END OF PLAN**
