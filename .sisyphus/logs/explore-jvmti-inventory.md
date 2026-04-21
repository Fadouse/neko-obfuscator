# JVMTI Usage Inventory — worktree/dev-impl

Source: bg_0091e7c1 (explore, ses_251d38fb9ffeJR5x6z6SHyOyIO, 3m56s)
Scope: `/mnt/d/Code/Security/NekoObfuscator/worktree/dev-impl/` only
Goal: support strict zero-JVMTI removal plan

## Files containing JVMTI references

1. `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/BootstrapEmitter.java` — JVMTI body: capability, ClassPrepare, GetLoadedClasses prewarm, method/class signature discovery, RawMonitor, VMStructs
2. `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/JniOnLoadEmitter.java` — `JNI_OnLoad` obtains `jvmtiEnv*` and wires Wave 1 class discovery
3. `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/Wave2FieldLdcEmitter.java` — LDC Class slow-path additional JVMTI `GetLoadedClasses`/`GetClassSignature`
4. `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java` — generates C source with `#include <jvmti.h>`
5. `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/Wave1RuntimeEmitter.java` — `Class.forName(name, false, loader)` fallback implementation
6. `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/NativeBuildEngine.java` — native build entry; no explicit `jvmti` string, compiles generated C with JDK include path
7. `neko-runtime/src/main/java/dev/nekoobfuscator/runtime/NekoBootstrap.java` — Java-side `Class.forName(..., loader)` runtime class resolution
8. `neko-runtime/src/main/java/dev/nekoobfuscator/runtime/NekoIndyDispatch.java` — Java-side descriptor/handle decode with multiple `Class.forName`
9. `neko-runtime/src/main/java/dev/nekoobfuscator/runtime/NekoJarLauncher.java` — Java-side entry class load `Class.forName(mainClass, false, cl)`
10. `neko-test/src/test/java/dev/nekoobfuscator/test/CCodeGeneratorTest.java` — asserts generated source contains `JNI_OnLoad`
11. `TODO.md` — design intent text: confirms Wave 1 state as `GetLoadedClasses prewarm + ClassPrepare + Class.forName slow path`

## 1) JVMTI API function calls (17 total across 3 files)

### BootstrapEmitter.java (14)
- `:1097` `neko_jvmti_deallocate` — `(*jvmti)->Deallocate(...)` — release JVMTI allocated buffer
- `:1103` `neko_log_jvmti_error` — `(*jvmti)->GetErrorName(...)` — parse JVMTI error name
- `:1113` `neko_manifest_lock_enter` — `(*g_neko_jvmti)->RawMonitorEnter(...)` — enter manifest native lock
- `:1119` `neko_manifest_lock_exit` — `(*g_neko_jvmti)->RawMonitorExit(...)` — exit manifest native lock
- `:1155` `neko_install_class_prepare_callback` — `jvmtiEventCallbacks callbacks` — declare event callbacks
- `:1159` `neko_install_class_prepare_callback` — `callbacks.ClassPrepare = &neko_class_prepare_cb`
- `:1160` `neko_install_class_prepare_callback` — `(*jvmti)->SetEventCallbacks(...)`
- `:1165` `neko_install_class_prepare_callback` — `(*jvmti)->SetEventNotificationMode(..., JVMTI_EVENT_CLASS_PREPARE, ...)`
- `:1220` `neko_capture_well_known_klass` — `jvmtiError err` + `g_neko_jvmti` dep
- `:1224` `neko_capture_well_known_klass` — `(*g_neko_jvmti)->GetClassSignature(...)`
- `:1483` `neko_discover_class` — entry with `jvmtiEnv *jvmti` parameter — Wave 1 single class discovery
- `:1492` `neko_discover_class` — `(*jvmti)->GetClassSignature(...)` — internal name
- `:1510` `neko_discover_class` — `(*jvmti)->GetClassMethods(...)` — enumerate methods
- `:1521` `neko_discover_class` — `(*jvmti)->GetMethodName(...)` — method name + descriptor
- `:1553` `neko_discover_class_via_reflection` — `jvmtiEnv *jvmti` parameter — reflection supplement path
- `:1596` `neko_discover_class_via_reflection` — `(*jvmti)->GetMethodName(...)` — from reflected jmethodID
- `:1621` `neko_class_prepare_cb` — `static void JNICALL ... (jvmtiEnv *jvmti, ...)` — ClassPrepare callback body
- `:1628` `neko_init_jvmti` — `jvmtiCapabilities capabilities` — capability struct
- `:1637` `neko_init_jvmti` — `(*jvmti)->AddCapabilities(...)` — request JVMTI capabilities
- `:1642` `neko_init_jvmti` — `(*jvmti)->CreateRawMonitor(...)` — create manifest raw monitor
- `:1650` `neko_discover_loaded_classes` — `(*jvmti)->GetLoadedClasses(...)` — full class prewarm
- `:1668` `neko_discover_manifest_owners` — `jvmtiEnv *jvmti` parameter — manifest owner reflection supplement

### JniOnLoadEmitter.java (1)
- `:6` `JNI_OnLoad` — `jvmtiEnv *jvmti = NULL` — holds JVMTI env
- `:24` `JNI_OnLoad` — `(*vm)->GetEnv(..., JVMTI_VERSION_1_2)` — obtain JVMTI env

### Wave2FieldLdcEmitter.java (2)
- `:639` `neko_ensure_ldc_class_site_resolved` — `jvmtiEnv *jvmti` local — LDC Class slow-path
- `:657` `neko_ensure_ldc_class_site_resolved` — `(*jvmti)->GetLoadedClasses(...)` — LDC Class secondary scan
- `:662` `neko_ensure_ldc_class_site_resolved` — `(*jvmti)->GetClassSignature(...)` — match target class by signature

## 2) Pure references / types / constants

- `BootstrapEmitter.java:56` — `g_neko_jvmti` — global JVMTI env cache
- `BootstrapEmitter.java:1156` — `jvmtiEventCallbacks` — event callbacks struct
- `BootstrapEmitter.java:1629` — `jvmtiCapabilities` — capabilities struct
- `JniOnLoadEmitter.java:24` — `JVMTI_VERSION_1_2` — version constant
- `BootstrapEmitter.java:1165` — `JVMTI_ENABLE` / `JVMTI_EVENT_CLASS_PREPARE` — event constants
- `BootstrapEmitter.java:{1105,1161,1166,1225,1493,1511,1522,1597,1638,1643,1654}` — `JVMTI_ERROR_NONE` — JVMTI error checks
- `TODO.md:94` — document reference to `JVMTI GetLoadedClasses prewarm + ClassPrepare callback`

## 3) Capability struct population (3 fields)

`BootstrapEmitter.java:1628-1647` — `neko_init_jvmti`:
- `:1632` `memset(&capabilities, 0, sizeof(capabilities));`
- `:1633` `capabilities.can_get_bytecodes = 0;`
- `:1634` `capabilities.can_generate_all_class_hook_events = 1;`
- `:1635` `capabilities.can_generate_compiled_method_load_events = 0;`
- `:1636` `capabilities.can_redefine_classes = 0;`
- `:1637` `(*jvmti)->AddCapabilities(jvmti, &capabilities)`
- `:1642` `(*jvmti)->CreateRawMonitor(jvmti, "neko_manifest_lock", &g_neko_manifest_lock)`

## 4) Build-time JVMTI linkage

- `CCodeGenerator.java:266` — `sb.append("#include <jvmti.h>\n");` — generated source include
- `build.gradle.kts`, `neko-native/build.gradle.kts`, CMake, build.zig — NO explicit `jvmti` string
- `NativeBuildEngine.java:71-95` — `zig cc` with `java.home/include` + platform includes; jvmti.h visible via JDK include; no separate linker args

## 5) Java-side JVMTI agent loading

- `VirtualMachine.loadAgent` — NOT FOUND
- `com.sun.tools.attach.VirtualMachine` — NOT FOUND
- `-agentlib:` — NOT FOUND
- `-agentpath:` — NOT FOUND
- Conclusion: NO dynamic attach/loadAgent code; JVMTI source is exclusively generated-native-library `JNI_OnLoad`

## 6) Class discovery call chain

### Bootstrap chain
`JniOnLoadEmitter.java:39-53`:
- `JNI_OnLoad`
  - `-> neko_init_jvmti`
  - `-> neko_discover_loaded_classes`
  - `-> neko_discover_manifest_owners`
  - `-> neko_install_class_prepare_callback`

### Prewarm chain
`BootstrapEmitter.java:1650-1665` — `neko_discover_loaded_classes`:
- `GetLoadedClasses`
- loop `classes[i]`
- `-> neko_discover_class`

`BootstrapEmitter.java:1483-1550` — `neko_discover_class`:
- `GetClassSignature`
- `GetClassMethods`
- loop `GetMethodName`
- `-> neko_record_manifest_match`
- `-> neko_resolve_discovered_invoke_sites`
- `-> neko_resolve_prepared_class_field_sites`
- `-> neko_publish_prepared_ldc_class_site`
- `-> neko_capture_well_known_klass`

### Manifest owner supplement chain
`BootstrapEmitter.java:1668-1690` — `neko_discover_manifest_owners`:
- loop `g_neko_manifest_owners`
- `-> neko_load_class_noinit`
- `-> neko_discover_class_via_reflection`
- `-> neko_resolve_prepared_class_field_sites`
- `-> neko_capture_well_known_klass`

`BootstrapEmitter.java:1553-1618` — `neko_discover_class_via_reflection`:
- `Class.getDeclaredMethods`
- `FromReflectedMethod`
- `GetMethodName`
- `-> neko_record_manifest_match`
- `-> neko_resolve_discovered_invoke_sites`

### ClassPrepare chain
`BootstrapEmitter.java:1155-1170` — `neko_install_class_prepare_callback`:
- `SetEventCallbacks`
- `SetEventNotificationMode(... CLASS_PREPARE ...)`

`BootstrapEmitter.java:1621-1626` — `neko_class_prepare_cb`:
- `-> neko_discover_class`

### LDC Class slow-path chain
`Wave2FieldLdcEmitter.java:639-705` — `neko_ensure_ldc_class_site_resolved`:
- fast path `cached_klass`
- `g_neko_jvmti`
- `GetLoadedClasses`
- loop `GetClassSignature`
- match `neko_ldc_site_matches_loaded_class`
- fallback `owner_class = *site->owner_class_slot`
- `loader = neko_owner_class_loader(...)`
- `binary_name = neko_ldc_site_binary_name(site)`
- `klass_obj = neko_load_class_noinit_with_loader(env, binary_name, loader)` — `Class.forName(name, false, ownerLoader)` slow path
- success `cached_klass = neko_class_klass_pointer(klass_obj)`

## 7) SystemDictionary / Universe / VMStructs walks

- `BootstrapEmitter.java:13-38` — `NEKO_REQUIRED_VM_SYMBOLS` — enumerates `gHotSpotVMStructs` / VMTypes / const roots
- `BootstrapEmitter.java:{640,644,648,662,671}` — `neko_resolve_vm_symbols` region — locates `gHotSpotVMStructs`
- `BootstrapEmitter.java:897-945` — `neko_parse_vm_layout` region — reads VMStructs/VMTypes/constant roots + stride
- `BootstrapEmitter.java:945` — `for (const uint8_t *entry = vmstructs; ; entry += struct_stride)` — main VMStructs traversal loop
- `BootstrapEmitter.java:1032-1041` — `Universe` / `CompressedOops` / `CompressedKlassPointers` field resolution — narrow oop/klass base+shift
- `SystemDictionary` — NO existing walk
- `Universe` — field resolution present
- `VMStructs` — full table walk present

## 8) Class resolution fallback

### Native slow path
- `Wave1RuntimeEmitter.java:377-395` — `neko_load_class_noinit_with_loader`
  - `Class.forName(String, boolean, ClassLoader)` JNI wrapper
  - `initialize=false`
- `Wave1RuntimeEmitter.java:367-375` — `neko_load_class_noinit`
  - obtain system class loader, delegate to `neko_load_class_noinit_with_loader`

### Trigger point
- `Wave2FieldLdcEmitter.java:679-694` — `neko_ensure_ldc_class_site_resolved`
  - JVMTI scan misses
  - `owner_class_slot` available
  - `Class.forName(..., false, loader)` with owner loader

### Java runtime `Class.forName` sites
- `neko-runtime/.../NekoBootstrap.java:62-63` — `Class.forName(owner.replace('/', '.'), true, callerClass.getClassLoader())`
- `neko-runtime/.../NekoIndyDispatch.java:33` — `Class.forName(bsmOwner.replace('/', '.'))`
- `neko-runtime/.../NekoIndyDispatch.java:78` — descriptor -> class
- `neko-runtime/.../NekoIndyDispatch.java:85` — handle owner -> class
- `neko-runtime/.../NekoJarLauncher.java:20` — entry main class load
- `neko-core/.../pipeline/ObfuscationPipeline.java:158` — `NativeCompilationStage` reflective load

## 9) Tests depending on JVMTI

### Direct
- `neko-test/.../CCodeGeneratorTest.java:76` — `hotspotProbeEmitted` asserts generated source contains `JNI_OnLoad`

### Indirect (high-relevance)
- `CCodeGeneratorTest.java:72-76` — constructs `CCodeGenerator.generateSource(...)` result and checks native bootstrap entry; strict-zero-JVMTI will require synchronizing this assertion

### Other
- NO other `jvmti` / `GetLoadedClasses` / `ClassPrepare` / `loadAgent` / `jvmti.h` hits in test tree

## 10) Inventory summary

- 17 JVMTI / JVMTI-dependent call sites across 3 source generator files
  - `BootstrapEmitter.java` — 14
  - `JniOnLoadEmitter.java` — 1
  - `Wave2FieldLdcEmitter.java` — 2
- 1 `jvmti.h` include generation point in 1 file
- 3 capability field populations
- 1 additional JVMTI resource initialization (`CreateRawMonitor`)
- 0 `Agent_OnLoad`
- 0 `Agent_OnAttach`
- 1 `JNI_OnLoad`
- 0 Java-side `VirtualMachine.loadAgent` / `-agentlib:` / `-agentpath:` hits
- 0 build.gradle/CMake/build.zig explicit `jvmti` link items
- 1 test file directly depending on JVMTI shape
- 1 existing VMStructs walk main path in `neko_parse_vm_layout`
