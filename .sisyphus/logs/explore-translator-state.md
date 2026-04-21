# Native Opcode Translator State Map — worktree/dev-impl

Source: bg_fa861b1b (explore, ses_251d32ab5ffevGnrRl5dNUg9FM, 4m2s)
Scope: `/mnt/d/Code/Security/NekoObfuscator/worktree/dev-impl/` only
Goal: map coverage/admission/emitters per TODO §7 waves

## 1) SafetyChecker

File: `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/NativeTranslationSafetyChecker.java`

### Method-level rejects
- `:27-29` class initializers `"class initializers are not translated"`
- `:30-32` constructors `"constructors remain in bytecode form"`
- `:33-35` `ACC_NATIVE` `"already-native methods are not translated"`
- `:36-38` `ACC_ABSTRACT` `"abstract methods have no translatable bytecode body"`
- `:39-41` `ACC_SYNCHRONIZED` `"synchronized methods are not translated"`
- `:42-44` `ACC_BRIDGE` `"bridge methods are skipped"`
- `:45-47` no code body `"method has no translatable bytecode body"`

### Reference return admission
- `:171-200` entry `unsupportedReferenceReturnReason`
- Accepted `ARETURN` predecessors:
  - `ALOAD` `:213-215`
  - `ACONST_NULL` `:216`
  - `LDC String` `:206-211`
  - `LDC Type(OBJECT|ARRAY)` `:206-212`
- Direct producer rule `"reference return requires direct ALOAD/ACONST_NULL producer"` `:188-190, 239-242`
- Local source rule: parameter local or recent `ASTORE` `:235-277`
- No intermediate GC-permitting `:248-250`
- Explicit `ARETURN` required `:199`
- Other reasons:
  - `"reference return requires bytecode body"` `:174`
  - `"reference return flow could not be proven"` `:185-187`
  - `"reference return requires parameter/local source"` `:244-246`

### Invoke admission/rejects
- Scan `INVOKEVIRTUAL/INVOKESPECIAL/INVOKESTATIC/INVOKEINTERFACE` `:59-69`
- `unsupportedInvokeReason`:
  - reference return `"INVOKE with reference return deferred to Wave 4 (oop return adapter)"` `:342-345`
  - reference args `"INVOKE with reference arguments deferred pending JNI-free receiver spill hardening"` `:346-350`
  - `INVOKEVIRTUAL` `"INVOKEVIRTUAL deferred pending Wave 3 vtable dispatch hardening"` `:351-353`
  - `INVOKEINTERFACE` `"INVOKEINTERFACE deferred pending Wave 3 itable dispatch hardening"` `:354-356`
- Current admitted:
  - `INVOKESTATIC` primitive args + primitive/void return
  - `INVOKESPECIAL` primitive args + primitive/void return

### LDC admission/rejects
- Admitted `:323-329`: Integer/Float/Long/Double/String
- Admitted `:330-334`: Type.OBJECT / Type.ARRAY
- Rejected:
  - MethodType `"LDC MethodType deferred to M4a (Wave 3)"` `:331-333`
  - Handle `"LDC MethodHandle deferred to M4a (Wave 3)"` `:335-337`
  - Other Type sort `"unsupported LDC Type sort: ..."` `:331-333`
  - Other constant kind `"unsupported LDC constant kind: ..."` `:338`

### Field admission/rejects
- `GETFIELD` admitted (no reject) `:75-76`
- `GETSTATIC` primitive admitted; reference rejected `"reference GETSTATIC deferred pending oop static field support"` `:77-83`
- `PUTFIELD` primitive admitted; reference rejected `"reference PUTFIELD deferred to M5h (GC write barriers)"` `:84-99`
- `PUTSTATIC` primitive admitted; reference rejected `"reference PUTSTATIC deferred to M5h (GC write barriers)"` `:86-99`

### Arrays / alloc / throw / type / monitor rejects
- `NEW/NEWARRAY/ANEWARRAY/MULTIANEWARRAY` reject `"deferred beyond Wave 2"` `:71-74`
- `AALOAD/AASTORE/BALOAD/BASTORE/CALOAD/CASTORE/SALOAD/SASTORE/IALOAD/IASTORE/LALOAD/LASTORE/FALOAD/FASTORE/DALOAD/DASTORE/ARRAYLENGTH` reject `"deferred beyond Wave 2"` `:100-116`
- `ATHROW/MONITORENTER/MONITOREXIT/CHECKCAST/INSTANCEOF` reject `"deferred beyond Wave 2"` `:117-121`
- `INVOKEDYNAMIC` reject `"INVOKEDYNAMIC deferred to M5f"` `:70`

### Arithmetic rejects
- `IDIV/IREM/LDIV/LREM` reject `"is not yet supported for leaf-native translation"` `:122-125`

### JSR/RET reject
- `:58` `"JSR/RET bytecode is not supported"`

### Translated→translated invoke closure
- `hasOnlyManifestInvokeTargets(...)` fails with `"INVOKE target not in neko manifest (translated→translated only)"` `:142-163`

### Kind classification (implicit)
- No `SIMPLE/REFERENCE/SAFEPOINT_MAY_THROW` enum
- `isGcPermittingOpcode` returns true for `INVOKE* / INVOKEDYNAMIC / NEW* / GET/PUT* / AALOAD / AASTORE / ARRAYLENGTH / ATHROW / MONITOR* / CHECKCAST / INSTANCEOF / LDC` `:289-318`

## 2) C emitter class inventory

`neko-native/.../codegen/emit/`:

### Wave1RuntimeEmitter.java
- JNI wrappers, slot macros, boxing, exception/TLAB helpers
- `renderBootstrapRuntimeSupport()`:
  - `neko_current_env` `:25-32`
  - `neko_take_pending_jni_exception_oop` `:34-47`
  - `neko_new_exception_oop` `:49-85`
  - compressed oop/klass encode/decode `:87-111`
  - `neko_get_current_thread` `:113-117`
  - pending exception accessors `:119-148`
  - `neko_tlab_alloc_slow/neko_tlab_alloc` `:150-181`
  - exception handler dispatch `:183-192`
- `renderRuntimeSupport()`:
  - `PUSH_*/POP_*` macros `:214-231`
  - huge `neko_*` JNI wrapper inventory `:233+`
- No TODO/FIXME/UnsupportedOperationException

### Wave2FieldLdcEmitter.java
- Field sites, field offsets, static bases, string/class LDC, class prewarm, string intern
- Key methods:
  - `neko_raise_null_pointer_exception` `:32-37`
  - `neko_wave2_unsafe` `:38-59`
  - field reflection/offset helpers `:60-130`
  - `neko_derive_wave2_layout_offsets` `:131-148`
  - oop field load/store support `:155-324`
  - primitive field accessor family via `appendWave2PrimitiveFieldAccessors` `:325-332`
  - MUTF8/string intern helpers `:333+`
  - `neko_prewarm_ldc_sites` `:736`
- No source-level TODO/FIXME
- Inline error paths with `IllegalStateException` text

### Wave3InvokeStaticEmitter.java
- Class/method/field/string bind, invoke-site resolution, icache metadata/direct stubs
- Methods:
  - `renderResolutionCaches` `:24-72`
  - `renderBindSupport` `:74-216`
  - `renderWave3Support` `:218-251`
  - `renderBindOwnerFunctions` `:253-298`
  - `renderIcacheDirectStubs` `:300-311`
  - `renderIcacheMetas` `:313-328`
- No TODO/FIXME/UnsupportedOperationException

### Wave4aRuntimeApiEmitter.java
- `neko_rt_*`, no-safepoint VM-entry API, handles, fast alloc
- Methods:
  - `renderWave4ASupport` `:4-395`
  - `renderObjectReturnSupport` `:397-404`
- No TODO/FIXME/UnsupportedOperationException

### ManifestEmitter.java
- Manifest structs/arrays/support
- Methods:
  - `registerManifestMethod` `:27-29`
  - reserve site methods `:31-101`
  - `renderManifestSupport` `:103-307`
  - per-site array renders `:309-377`
- No TODO/FIXME/UnsupportedOperationException

### BootstrapEmitter.java
- VM symbol probe, layout parse, class discovery, lock, callback wiring
- `renderBootstrapSupport` `:10+`
- No TODO/FIXME/UnsupportedOperationException

### JniOnLoadEmitter.java
- `JNI_OnLoad`
- No markers

### EntryPatchEmitter.java
- Method patch, no-compile flags, i2i/c2i stub patching `:71-123`
- No markers

### AssemblyStubEmitter.java
- Assembly signature dispatch, `neko_stubs.S`
- `throw new IllegalStateException("Unexpected compiled-source location: ...")` `:368`

### ImplBodyEmitter.java
- Final C function body emission
- `throw new IllegalStateException("Unsupported C statement in generator: ...")` `:65`

### CEmissionContext.java
- Symbol/site/signature state
- No markers

## 3) `neko_rt_*` API layer

Note: user-specified `neko-native/src/native/` does NOT exist; runtime API emitted by Java emitters to single C source.

`Wave4aRuntimeApiEmitter.java`:
- `static inline jboolean neko_rt_thread_is_in_vm(JavaThread *thread)` `:50-54` — check `in_vm` state
- `static void neko_rt_close_scope_chain(NekoHandleScope *scope)` `:56-63` — release handle scope chain
- `static void neko_rt_close_scopes_for_ctx(NekoRtCtx *ctx)` `:65-74` — close all scopes held by ctx
- `void neko_rt_ctx_init(NekoRtCtx *ctx, JavaThread *thread, void *java_sp, void *java_fp, void *java_pc, uint32_t flags)` `:95-109` — cache thread/frame state
- `void neko_rt_enter_vm(NekoRtCtx *ctx)` `:111-129` — write `last_Java_{sp,fp,pc}`
- `void neko_rt_leave_vm(NekoRtCtx *ctx)` `:131-155` — close scopes, restore Java state
- `NekoHandleScope* neko_rt_handles_open(NekoRtCtx *ctx, size_t reserve)` `:160-184`
- `void neko_rt_handles_close(NekoHandleScope *scope)` `:186-203`
- `NekoHandle neko_rt_handle_from_oop(NekoHandleScope *scope, oop raw)` `:205-220`
- `oop neko_rt_oop_from_handle(NekoHandle h)` `:222-224`
- `oop neko_rt_mirror_from_klass_nosafepoint(Klass *k)` `:252-255`
- `oop neko_rt_static_base_from_holder_nosafepoint(Klass *holder)` `:257-259`
- `oop neko_rt_try_alloc_instance_fast_nosafepoint(Klass *ik, size_t instance_size_bytes)` `:261-297` — TLAB fast instance alloc
- `static void* neko_rt_try_alloc_array_fast_nosafepoint(void* array_klass, int32_t length)` `:315-365` — TLAB fast array alloc

### neko_impl_*
- `NativeTranslator.java:56-66` — naming `neko_impl_<index>`
- `ManifestEmitter.java:253-256` — `impl_fn` points to `&neko_impl_<index>`
- After class rewrite, Java body throws LinkageError; real native entry via patched Method* -> stub -> `impl_fn`

## 4) Manifest structure

### Java constants (`CCodeGenerator.java`)
- `MANIFEST_FLAGS_OFFSET = 32` `:25`
- `MANIFEST_SIGNATURE_ID_OFFSET = 36` `:26`
- `MANIFEST_IMPL_FN_OFFSET = 40` `:27`
- `MANIFEST_METHOD_STAR_OFFSET = 48` `:28`
- `MANIFEST_ENTRY_SIZE = 88` `:29`

### C layout (`ManifestEmitter.java`)

`NekoManifestFieldSite` `:126-141`:
```c
typedef struct NekoManifestFieldSite {
    uint32_t owner_class_index;
    const char* owner_internal;
    const char* field_name;
    const char* field_desc;
    jclass *owner_class_slot;
    uint8_t is_static;
    uint8_t is_reference;
    uint8_t is_volatile;
    uint8_t _pad0;
    void* cached_klass;
    jobject static_base_global_ref;
    void *volatile *static_base_slot;
    ptrdiff_t field_offset_cookie;
    ptrdiff_t resolved_offset;
} NekoManifestFieldSite;
```

`NekoManifestInvokeSite` `:142-150`:
```c
typedef struct NekoManifestInvokeSite {
    const char* owner_internal;
    const char* method_name;
    const char* method_desc;
    uint8_t opcode;
    uint8_t is_interface;
    uint16_t signature_id;
    void* resolved_method;
} NekoManifestInvokeSite;
```

`NekoManifestLdcSite` `:151-162`:
```c
typedef struct NekoManifestLdcSite {
    uint32_t site_id;
    uint32_t owner_class_index;
    uint8_t kind;
    uint8_t _pad0;
    uint16_t _pad1;
    const uint8_t* raw_constant_utf8;
    size_t raw_constant_utf8_len;
    jclass *owner_class_slot;
    void* cached_klass;
    void* resolved_cache_handle;
} NekoManifestLdcSite;
```

`NekoManifestMethod` `:163-180`:
```c
typedef struct NekoManifestMethod {
    const char* owner_internal;
    const char* method_name;
    const char* method_desc;
    uint32_t owner_hash;
    uint32_t name_desc_hash;
    uint16_t flags;
    uint16_t reserved;
    uint32_t signature_id;
    void* impl_fn;
    void* method_star;
    NekoManifestFieldSite* field_sites;
    uint32_t field_site_count;
    uint32_t _pad_field_sites;
    NekoManifestLdcSite* ldc_sites;
    uint32_t ldc_site_count;
    uint32_t _pad_ldc_sites;
} NekoManifestMethod;
```

### Site types
- Field sites `reserveManifestFieldSite` `:31-48`
- Invoke sites `reserveManifestInvokeSite` `:50-65`
- String LDC sites `reserveManifestStringLdcSite` `:67-83`
- Class LDC sites `reserveManifestClassLdcSite` `:85-101`
- LDC kinds `:114-117`:
  - `NEKO_LDC_KIND_STRING 1u`
  - `NEKO_LDC_KIND_CLASS 2u`
  - `NEKO_LDC_KIND_METHOD_HANDLE 3u`
  - `NEKO_LDC_KIND_METHOD_TYPE 4u`

### Asserts
- `_Static_assert(sizeof(NekoManifestMethod) == 88)` `:182`
- Offset asserts `:183-186`

## 5) Test jar admission expectations

No hardcoded 11/89, 12/101, 5/26 counts in test source right now.

Admission-related owner files:
- `NativeObfuscationHelper.java:41-43`:
  - `TEST.jar` ↔ `native-test.yml`
  - `SnakeGame.jar` ↔ `native-snake.yml`
  - `obfusjack-test21.jar` ↔ `native-obfusjack.yml`
- `NativeObfuscationPerfTest.java:25-59` — speed tests referencing jar names
- `NativeObfuscationIntegrationTest.java:185-240` — checks TEST `Calc` translated method bodies

Conclusion: admission counts MUST be added to tests when reporting per-jar progress.

## 6) NekoNativeLoader (runtime shape)

`neko-runtime/src/main/java/dev/nekoobfuscator/runtime/NekoNativeLoader.java:9-82`:
- Fields:
  - `private static volatile boolean loaded;` `:10`
  - `private static final Object LOCK = new Object();` `:11`
- Ctor `private NekoNativeLoader()` `:13-14`
- Methods:
  - `public static void load()` `:16-36`
  - `private static String detectPlatform()` `:38-47`
  - `private static String detectArch()` `:49-55`
  - `private static String libExt(String platform)` `:57-65`
  - `private static String resourceName(...)` `:67-69`
  - `private static Path extractResource(...)` `:71-81`

Shape test: `NekoLoaderShapeTest.java:64-75`:
- Asserts field set = `{loaded, LOCK}` `:70`
- Asserts public method set = `{load}` `:32-39`

## 7) Native bootstrap entry

Entry symbols:
- `JNI_OnLoad` `JniOnLoadEmitter.java:6-61` — present
- `Agent_OnLoad` — not found
- `neko_bootstrap_init` — not found

Call order (`JniOnLoadEmitter.java`):
- `:14` `g_neko_java_vm = vm`
- `:19-23` `GetEnv(JNI_VERSION_1_6)`
- `:24-28` `GetEnv(JVMTI_VERSION_1_2)`
- `:29` `neko_mark_loader_loaded(env)`
- `:30` `g_neko_jvmti = jvmti`
- `:31-33` `neko_resolve_vm_symbols()`
- `:34-36` `neko_parse_vm_layout(env)`
- `:37` `neko_log_runtime_helpers_ready()`
- `:38` `neko_log_wave4a_status()`
- `:39-41` `neko_init_jvmti(vm, jvmti)`
- `:42-44` `neko_discover_loaded_classes(env, jvmti)`
- `:45-47` `neko_discover_manifest_owners(env, jvmti)`
- `:48` `neko_resolve_string_intern_layout()`
- `:49` `neko_string_intern_prewarm_and_publish(env)`
- `:50` `neko_manifest_lock_enter()`
- `:51` `neko_patch_discovered_methods()`
- `:52` `neko_manifest_lock_exit()`
- `:53-55` `neko_install_class_prepare_callback(jvmti)`
- `:58` `neko_log_wave2_ready()`
- `:59` `neko_log_wave3_ready()`

Related definitions:
- `neko_resolve_vm_symbols` `BootstrapEmitter.java:703`
- `neko_mark_loader_loaded` `:776`
- `neko_parse_vm_layout` `:896`
- `neko_manifest_lock_enter/exit` `:1113-1119`
- `neko_install_class_prepare_callback` `:1155`
- `neko_discover_loaded_classes` `:1650`
- `neko_discover_manifest_owners` `:1668`
- `neko_patch_discovered_methods` `EntryPatchEmitter.java:117-123`

## 8) Exception / throw helpers

Existing infra:
- `static inline jint neko_throw(JNIEnv *env, jthrowable exc)` `Wave1RuntimeEmitter.java:243`
- `static inline jint neko_throw_new(JNIEnv *env, jclass cls, const char *msg)` `:244`
- `static inline jthrowable neko_exception_occurred(JNIEnv *env)` `:245`
- `static inline void neko_exception_clear(JNIEnv *env)` `:246`
- `static inline jboolean neko_exception_check(JNIEnv *env)` `:356`
- `static inline void* neko_take_pending_jni_exception_oop(JNIEnv *env)` `:34-47`
- `static inline void* neko_new_exception_oop(JNIEnv *env, const char *class_name, const char *message)` `:49-85`
- `static inline void neko_set_pending_exception(void *thread, void *oop)` `:124-133`
- `static inline void neko_clear_pending_exception(void *thread)` `:135-144`
- `void neko_raise_athrow(void *thread, void *exception_oop)` `:146-148`
- `static void neko_wave2_capture_pending(JNIEnv *env, void *thread, const char *fallback_class, const char *fallback_message)` `Wave2FieldLdcEmitter.java:27-31`
- `static void neko_raise_null_pointer_exception(void *thread)` `:32-37`

Direct opcode:
- `ATHROW` emitter: `neko_throw(env, (jthrowable)POP_O());` `OpcodeTranslator.java:203`

NOT found: `throw_oop`, `raise_exception`

## 9) Array opcodes state

### SafetyChecker (rejects all)
- `AALOAD/AASTORE/BALOAD/BASTORE/CALOAD/CASTORE/SALOAD/SASTORE/IALOAD/IASTORE/LALOAD/LASTORE/FALOAD/FASTORE/DALOAD/DASTORE/ARRAYLENGTH` reject `NativeTranslationSafetyChecker.java:100-116`
- `NEWARRAY/ANEWARRAY/MULTIANEWARRAY` reject `:71-74`

### OpcodeTranslator (emitters present)
- Loads: `IALOAD:105 LALOAD:106 FALOAD:107 DALOAD:108 AALOAD:109 BALOAD:110 CALOAD:111 SALOAD:112`
- Stores: `IASTORE:114 LASTORE:115 FASTORE:116 DASTORE:117 AASTORE:118 BASTORE:119 CASTORE:120 SASTORE:121`
- `ARRAYLENGTH` `:202`
- `NEWARRAY` `:216`
- `ANEWARRAY` `:217-220`
- `MULTIANEWARRAY` `:238`

### Tests
- `OpcodeTranslatorUnitTest.java:236-255`

**Conclusion**: emitters done; admission gate blocks them.

## 10) CHECKCAST / INSTANCEOF state

### SafetyChecker
- `CHECKCAST/INSTANCEOF` reject `"deferred beyond Wave 2"` `:117-121`

### OpcodeTranslator (emitters present)
- `INSTANCEOF` `:230-233` — uses `cachedTypeClassExpression(...) + neko_is_instance_of(...)`
- `CHECKCAST` `:234-237` — failure constructs `ClassCastException`, `neko_throw_new(...); goto __neko_exception_exit;`

**Conclusion**: emit path exists; admission gate blocks entry.

## 11) Native build toolchain

File: `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/NativeBuildEngine.java`

Main command:
- `zigPath, "cc", "-shared", "-Oz", "-std=c11", "-Wall", "-Wextra", "-target", zigTarget, "-I", jniInclude` `:81-86`
- Debug flag `-DNEKO_DEBUG_ENABLED=1` `:87-91`
- Platform include `-I <java.home>/include/<linux|darwin|win32>` `:92-94, 133-138`
- Output `-o libneko_<target>.<ext>` `:77-79, 95`
- Include all source files (`.c` + `.s`) `:96-98, 160-167`

Target map:
- `LINUX_X64 -> x86_64-linux-gnu` `:123-125`
- `LINUX_AARCH64 -> aarch64-linux-gnu` `:125`
- `WINDOWS_X64 -> x86_64-windows-gnu` `:126`
- `MACOS_X64 -> x86_64-macos-none` `:127`
- `MACOS_AARCH64 -> aarch64-macos-none` `:128`

Source staging:
- Writes single C/header build to temp dir `:34-45`
- Also writes debug to `/tmp/neko_native_debug.c` `/tmp/neko_native_debug.h` `:40-41`

Cleanup: `deleteRecursive` empty `:169-171`

## 12) Configs (`configs/`)

Three files (`native-test.yml`, `native-obfusjack.yml`, `native-snake.yml`):
- `version: 1` `:1`
- `preset: STANDARD` `:2`
- All transforms `enabled: false` `:4-24`:
  - controlFlowFlattening, exceptionObfuscation, exceptionReturn, opaquePredicates, stringEncryption, numberEncryption, invokeDynamic, outliner, stackObfuscation, advancedJvm
- `native.enabled: true` `:26-27`
- `native.targets: [LINUX_X64]` `:28-29`
- `native.methods: ["**/*"]` `:30-31`
- `native.excludePatterns: []` `:32`
- `native.includeAnnotated: true` `:33`
- `native.skipOnError: true` `:34`
- `native.outputPrefix: "neko_impl_"` `:35`
- `native.resourceEncryption: false` `:36`
- `keys.masterSeed: 12345` `:38-39`

## Translator coverage summary

### Admitted by SafetyChecker today
- Constants: `ACONST_NULL`, `ICONST_*`, `LCONST_*`, `FCONST_*`, `DCONST_*`, `BIPUSH`, `SIPUSH`
- Primitive math/bitwise/shifts/conversions/comparisons
- Loads/stores incl `ALOAD/ASTORE`
- Stack ops
- Jumps/switches
- `GETFIELD`
- Primitive `GETSTATIC/PUTFIELD/PUTSTATIC`
- `INVOKESTATIC/INVOKESPECIAL` with primitive args + primitive/void return
- Constrained `ARETURN`

### Emitted in OpcodeTranslator today (broader than admitted)
- Arrays
- `ARRAYLENGTH`
- `ATHROW`
- `MONITORENTER/MONITOREXIT`
- `NEW/NEWARRAY/ANEWARRAY/MULTIANEWARRAY`
- `GET/PUT*` incl references
- `INSTANCEOF/CHECKCAST`
- `INVOKEDYNAMIC`
- `IDIV/IREM/LDIV/LREM`

**Direct wave planning implication**:
- Wave 4c/4b/4d main landing point primarily in `NativeTranslationSafetyChecker.java`
- Most opcodes already have emit code
- Remaining work: runtime correctness / exception / safepoint / write barriers / admission policy

## Wave landing anchor files (priority order)
1. `neko-native/.../translator/NativeTranslationSafetyChecker.java` — admission policy
2. `neko-native/.../translator/OpcodeTranslator.java` — emit corrections
3. `neko-native/.../codegen/emit/Wave2FieldLdcEmitter.java` — LDC + exception + string intern
4. `neko-native/.../codegen/emit/Wave4aRuntimeApiEmitter.java` — runtime API

Admission count assertions: add to `neko-test/.../*` aligned on `NativeObfuscationHelper` artifacts.
