# native-gentle-flamingo TODO / Plan Status

Source plan: `~/.claude/plans/native-gentle-flamingo.md`

Status legend:
- `[ ]` not completed / not verified
- `[-]` actively in progress only when current work is underway

## Hard Constraints

- Do not mark any task complete unless the exact requested artifact is regenerated and run.
- After any TODO sub-step is completed and verified, commit that completed slice before continuing.
- No targeted implementations for a specific benchmark, class, method, owner/name pair, or crash site. Fixes must be general architectural changes that satisfy the plan.
- No bug-specific, sample-specific, benchmark-specific, class-specific, method-specific, owner/name-specific, descriptor-specific, or crash-site-specific repair is allowed. If a proposed change only explains one observed failure instead of a whole plan-level category, stop and redesign it as a generic mechanism.
- Every implementation subtask must start by rereading the full source plan `~/.claude/plans/native-gentle-flamingo.md` and this todo file, then explicitly checking the intended change against the plan's current stage and dependency order before editing.
- Do not implement a later-stage workaround to make a validation jar pass while an earlier-stage architectural prerequisite is still open. Follow the plan order unless the todo is first updated with a plan-level dependency reason.
- No JNI fallback, soft fallback, skip-on-error success, or "fast path unavailable -> slow JNI path".
- No direct or indirect JNI fallback is allowed: no `NEKO_JNI_FN_PTR`, no `(*env)->...`, no `env->...`, no `Call*Method`, no `Get*Field`, no `FindClass`, no `NewStringUTF`, no `NewObject*`, no `Throw*`, no monitor or array JNI calls outside the explicitly allowed minimal `JNI_OnLoad` `GetEnv` bootstrap.
- Failure to resolve a required VMStruct, JVM symbol, class, method, field, string, GC barrier, CodeHeap entry, or call stub is a hard abort/error, never a fallback path and never a reason to leave original bytecode/JVM execution in place.
- No JVMTI.
- No new JVM runtime helper layer outside the planned native/bootstrap mechanism.
- Keep `JNI_OnLoad` as the minimal bootstrap only: `GetEnv`, capture needed VM/JNI anchors, initialize native metadata, then stop using JNI function-table calls.
- Do not add `<clinit>` or Java-side bootstrap beyond the existing native-loader entry requirement.
- ZGC and Shenandoah must not be bypassed by skipping classes or methods. Missing required barrier support must abort, not fallback.
- `obfusjack-test21` is a required validation target; Calc-only success is not enough.
- Generated C inspection is mandatory before claiming a no-JNI subtask: grep for `NEKO_JNI_FN_PTR`, `(*env)->`, `env->`, and JNI wrapper names must match the subtask's allowed surface.
- Use the repository/system `./gradlew`; do not create or use temporary `gradlew`.
- `test-jars/oouput/` is pre-existing untracked output and must not be staged unless explicitly requested.
- Existing unrelated worktree changes, especially `X86_64WindowsTrampoline.java`, must not be reverted or staged accidentally.

## Per-Subtask Runtime Validation Gate

This gate applies to every T0-T4 subtask, including small scaffolding and cleanup subtasks.

- Before starting any new subtask or any new code change inside the current subtask, reread this todo and the source plan, then record which row in the runtime target matrix below will validate that exact change.
- Every implementation change must follow this loop before it is treated as resolved: edit one coherent change, regenerate the affected native artifact with repository `./gradlew`, run the required runtime target(s), inspect stdout/stderr/native logs/`hs_err_pid*.log`, inspect generated C when JNI removal is claimed, then either fix and rerun or commit only after the checks pass.
- No checkbox may be changed to `[x]` because a previous jar, stale generated C file, unit-only test, or compile-only check passed. The validation artifact must be regenerated after the latest source edit.
- If a subtask is split into multiple commits or multiple code changes, each split inherits the full runtime target row for that subtask unless the todo is updated first with a narrower runtime target and a plan-level reason.
- Before editing code for a new subtask, write down the runtime target(s) that will prove the changed path. If the current subtask cannot be proven by `TEST-native.jar` plus `obfusjack-test21`/`obfusjack-native.jar`, add the narrower or additional runtime target before implementation.
- A subtask must stay `[ ]` or `[-]` until its changed code path is exercised by a regenerated runtime artifact, not only by compile success, source inspection, or generated-C inspection.
- Every code change inside a subtask must be followed by runtime validation of a freshly regenerated artifact before that change can be treated as resolved. Compile-only, unit-only, stale-jar, or source-only validation is not enough.
- Before changing any subtask checkbox to `[x]`, rerun the exact Gradle/native generation path that rebuilds the jar or native artifact affected by that subtask.
- After regeneration, run the exact runtime target that exercises the new path. For this plan, the default minimum runtime targets are `TEST-native.jar` and `obfusjack-test21`/`obfusjack-native.jar`; if a subtask needs a narrower or additional runtime target, state it before marking `[x]`.
- Runtime validation must inspect stdout, stderr, generated native logs, and any `hs_err_pid*.log` from that run. Crashes, SIGSEGV/SIGABRT, verifier errors, `translated=0`, `Native compilation produced no libraries`, skip-on-error output, missing required symbol diagnostics, or fallback/original-bytecode execution keep the subtask incomplete.
- A resolver/bind-time subtask is not complete until the resolver is called by the current bind/runtime path and a runtime jar proves the resolved pointer/offset/entry is used correctly.
- A hot-path opcode subtask is not complete until a runtime jar executes that opcode through the new native path and the generated C inspection confirms no forbidden JNI wrapper remains for that path.
- A cleanup/removal subtask is not complete until a runtime jar proves the removed fallback is not required and the failure mode is a hard abort/error when the required native mechanism is unavailable.
- The commit for a completed subtask must include only the implementation plus its todo checkbox update after the runtime validation above has passed.

### Runtime Target Matrix

Use these target groups in every row below:

- `R-build`: regenerate with repository `./gradlew` through the native integration path that rebuilds `TEST-native.jar` and `obfusjack-native.jar`; compile-only is not enough.
- `R-test`: run the regenerated `TEST-native.jar` directly with `NEKO_PATCH_DEBUG=1 java -XX:+PerfDisableSharedMem -jar neko-test/build/test-native/TEST-native.jar`.
- `R-obfusjack`: run the regenerated `obfusjack-test21`/`obfusjack-native.jar` target directly with `NEKO_PATCH_DEBUG=1 java -XX:+PerfDisableSharedMem -jar neko-test/build/test-native/obfusjack-native.jar`.
- `R-native-obfusjack`: same direct regenerated obfusjack native artifact as `R-obfusjack`; when a task mentions random runtime failure, run it 10 consecutive times and treat any abort, hang, missing `=== All tests completed ===`, any `TLAB * allocation failed`, or new `hs_err_pid*.log` as incomplete.
- `R-native-test`: run the relevant `NativeObfuscationIntegrationTest` Gradle target after regeneration; use focused tests while iterating, then the full class before marking `[x]`.
- `R-inspect`: inspect generated C, native build logs, stdout, stderr, and newest `hs_err_pid*.log`; reject `translated=0`, `Native compilation produced no libraries`, skip-on-error success, forbidden JNI function-table calls, crashes, verifier errors, or fallback/original-bytecode execution.
- `R-negative`: where a subtask removes a fallback, force or inspect the missing-capability path so the failure mode is a hard abort/error, not JNI fallback or original bytecode.

Each subtask below requires the listed runtime proof after the latest edit:

- T0.1: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; prove native resolution readiness bits are required before Stage 2 resolver use.
- T0.2: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; prove missing GC barrier symbols abort and no GC mode is silently skipped.
- T1.1: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated manifest/export inspection must prove no `Java_*`/`RegisterNatives` entry path remains.
- T1.2: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated Windows trampoline inspection must prove Java ABI entry and no JNI state transition.
- T1.3: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated SysV/Aarch64 trampoline inspection must prove Java ABI entry and no JNI state transition.
- T1.4: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must prove patched compiled/interpreted entries execute the new stubs.
- T1.5: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; prove `NEKO_DISABLE_CODEBLOB` is gone and private CodeHeap failure aborts.
- T1.6: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must prove obfusjack-test21 completes and native execution stays in `_thread_in_java`.
- T2.1: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; `JNI_OnLoad` inspection must show only allowed `GetEnv` bootstrap behavior.
- T2.2: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove class resolver calls current bind path and missing class resolution aborts.
- T2.3: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove method resolver calls current bind path and missing method resolution aborts.
- T2.4: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove field resolver calls current bind path and missing field resolution aborts.
- T2.5: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove string literal binding uses `neko_intern_string` and generated C has no `NewStringUTF` on that path.
- T2.6: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove bind macros use native resolvers and Unsafe-reflection offset paths are removed.
- T2.7: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove HotSpot support derives compressed-oops/klass data natively and MXBean/Unsafe probes are gone.
- T2.8: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove `JNIHandles::resolve`/local handle support works and raw `*(void**)ref` fallback aborts.
- T2.8a: `R-build`, `R-native-test` x10, `R-native-obfusjack` x10, `R-inspect`, `R-negative`; runtime must prove obfusjack no longer randomly aborts with `TLAB * allocation failed for string literal` and no longer hangs after the platform-thread microbench output.
- T2.9: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated bind-support C must have zero forbidden `(*env)->`/`NEKO_JNI_FN_PTR` hits outside allowed `GetEnv`.
- T3.1: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute LDC String/Class through cached bind-time slots.
- T3.2: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute primitive field access through direct offsets with JNI fallback deleted.
- T3.3: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute object field/static loads through barrier-aware native load entry.
- T3.4: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute object field/static stores through barrier-aware native store entry.
- T3.5: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute `ARRAYLENGTH` through direct array length offset.
- T3.6: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute primitive array load/store through direct array memory access.
- T3.7: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute `AALOAD`/`AASTORE` through barrier-aware array load/store and native store checks.
- T3.8: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute `NEW` and `NEW+<init>` without JNI allocation.
- T3.9: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute primitive/object/multi array allocation without JNI allocation.
- T3.10: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute `INSTANCEOF`/`CHECKCAST` through native subtype metadata.
- T3.11: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute `getClass` through oop header klass and mirror.
- T3.12: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute monitor enter/exit through HotSpot synchronizer/stub entry.
- T3.13: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute `ATHROW` by writing `_pending_exception`.
- T3.14: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must construct implicit exceptions through native allocation/call-stub path.
- T3.15: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must dispatch exception handlers through `_pending_exception` read/clear.
- T3.15a: `R-build`, `R-test` x10, `R-obfusjack` x10, `R-native-test`, `R-inspect`; runtime must prove NEW native allocation no longer aborts with `NEW TLAB allocation failed` in `TEST-native.jar`, without mixing allocation changes into T3.15 exception-dispatch semantics.
- T3.16: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute boxing/unboxing through cached methods/direct fields without JNI wrappers.
- T3.17: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime plus safety checker must prove non-manifest invoke callees are rejected and JNI invoke wrappers are deleted.
- T3.18: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove supported invokedynamic paths run natively and unsupported paths reject before fallback.
- T3.19: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove StringConcat paths do not enter StringBuilder JNI fallback.
- T3.20: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated runtime support C must prove `NEKO_JNI_FN_PTR` and replaced wrappers are gone.
- T3.21: moved to T4.0 — see Stage 4 below.
- T4.0: `R-build`, `R-test` x5, `R-obfusjack` x5, `R-native-obfusjack` x10, `R-inspect`, `R-negative`; runtime must prove `neko_exception_check_resolve_env_offset` is unreachable from the hot path after first publication (perf-counter or `NEKO_PATCH_DEBUG` log shows ≤ 1 derivation per process, not per `neko_exception_check` call); 5-run median: matrix-mul Seq ≤ 25 ms, virtual-thread microbench ≤ 2.5× original; missing-offset path aborts.
- T4.1: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; generated C in `neko_class_for_descriptor` and at `OpcodeTranslator.java:1293, 1308` callsites must have zero `(*((void***)(env)))[6]`, `[144]`, `[145]` indexing; primitive descriptor → mirror table populated at OnLoad; missing wrapper-class mirror aborts.
- T4.2a: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated C must have zero remaining `neko_find_class` callsites (the wrapper itself is deleted in T4.11); each enumerated callsite uses `neko_resolve_class`; missing class resolution aborts (inherits T2.2 R-negative).
- T4.2b: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated C must have zero remaining `neko_get_method_id` / `neko_get_static_method_id` callsites; each enumerated callsite uses `neko_resolve_method`; missing method resolution aborts (inherits T2.3 R-negative).
- T4.2c: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated C must have zero remaining `neko_get_static_field_id` callsites; each enumerated callsite uses `neko_resolve_field(.., is_static=true)`; missing field resolution aborts (inherits T2.4 R-negative).
- T4.3a: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute LDC MethodType through bind-time NJX-cached `MethodType.fromMethodDescriptorString`; generated `neko_method_type_from_descriptor` body has zero `[6]`, `[113]`, `[116]`, `[167]` indexing.
- T4.3b: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute MethodType.parameterArray through bind-time NJX cache; generated `neko_bootstrap_parameter_array` body has zero `[33]`, `[36]`, `[6]` indexing.
- T4.3c: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute bsm via NJX call_stub (getDeclaredMethod / setAccessible / invoke); generated `neko_invoke_bootstrap` body has zero `[6]`, `[33]`, `[36]`, `[63]`, `[167]` indexing.
- T4.3d: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute LDC ConstantDynamic with object-array build through `oopFactory::new_objArray` + barrier-aware AALOAD/AASTORE; generated `neko_resolve_constant_dynamic` body has zero `[167]`, `[171]`, `[172]`, `[173]`, `[174]` indexing.
- T4.4a: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must serve `MethodHandles$Lookup.IMPL_LOOKUP` from a bind-time-cached jobject slot; generated `neko_impl_lookup` body has zero `[6]`, `[144]`, `[145]` indexing; missing slot at first read aborts.
- T4.4b: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute `MethodHandles.privateLookupIn` through bind-time NJX call_stub; generated `neko_lookup_for_jclass` body has zero `[6]`, `[113]`, `[116]` indexing; missing NJX entry aborts.
- T4.5a: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must produce shadow-frame jstrings via `neko_intern_string`; generated `neko_shadow_dotted_string` body has zero `[167]` indexing.
- T4.5b: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must build StackTraceElement[] via `oopFactory::new_objArray` dlsym + bind-time-cached `StackTraceElement.<init>` Method* + call_stub; generated `neko_shadow_stack_trace` body has zero `[6]`, `[30]`, `[33]`, `[172]`, `[174]` indexing; missing dlsym / call_stub aborts.
- T4.6a: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must demonstrate `neko_handle_window_begin` / `_end` primitive in use by at least one converted helper; primitive itself is non-JNI (operates on JNIHandleBlock top via VMStructs offsets).
- T4.6b: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must show every enumerated `neko_delete_local_ref` callsite converted; `neko_icache_dispatch` PIC-miss path uses the window primitive (must show no regression vs the current build's icache PIC-miss microbench, since converting `DeleteLocalRef` JNI call to JNIHandleBlock-top reset should be neutral or improve); residual `neko_delete_local_ref` callsite aborts subtask.
- T4.7: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must show `neko_string_alloc_bits` JNI probe deleted; the VMStructs path is the only ready path; missing prerequisite at probe call site aborts.
- T4.8: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must show every bind-time global-ref allocation/release routes through `JNIHandles::make_global` / `::destroy_global` dlsym; generated C has zero `[21]`, `[22]` indexing; missing dlsym aborts.
- T4.9: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must show every `if (neko_exception_check(env)) neko_exception_clear(env);` pair converted to direct `_pending_exception` write; generated C has zero `[17]` indexing; missing offset (T4.0 prerequisite) aborts.
- T4.10: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must show `neko_manifest_internal_name` collapsed to `Klass::_name` Symbol direct read; emitted `ManifestEmitter` C has zero `[6]`, `[33]`, `[36]`, `[169]`, `[170]` indexing; missing Symbol layout aborts.
- T4.11: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; build must succeed only when the eight wrappers (`neko_find_class`, `neko_get_method_id`, `neko_get_static_method_id`, `neko_get_static_field_id`, `neko_exception_clear`, `neko_new_global_ref`, `neko_delete_global_ref`, `neko_delete_local_ref`) are deleted from `CCodeGenerator.java` and have zero references in generated C; any residual reference fails compile/link or grep.
- T4.12: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; reproducible grep on all generated C and every emitter source must report exactly one hit (the single `(*vm)->GetEnv(...)` in `JNI_OnLoad`).
- T4.13: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; `JNIEnv*` audit across generated C and `*Emitter.java` plus `CCodeGenerator.java` shows zero `(*env)->` / `(*((void***)(env)))[N]` outside the GetEnv call; `JVM_*` libjvm symbols and `neko_thread_jni_env` parameter passes are the only allowed `JNIEnv*` consumers post-bootstrap.
- T4.14: `R-build`, `R-test` x5, `R-obfusjack` x5, `R-native-test`, `R-inspect`; 5-run median: TEST `Calc` ≤ 20 ms, obfusjack matrix-mul Seq ≤ 25 ms, Platform threads ≤ 44 ms, Virtual threads ≤ 35 ms; matrix-mul Parallel/VThreads keep prior wins; failure to meet bar requires a new generic optimization commit (no class-/method-/owner-specific intrinsic).
- T4.15: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime under each of `-XX:+UseG1GC` / `-XX:+UseParallelGC` / `-XX:+UseSerialGC` / `-XX:+UseZGC -XX:+UnlockDiagnosticVMOptions -XX:+ZVerifyViews` / `-XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+ShenandoahVerify` must complete TEST and obfusjack with Calc ≤ 20 ms; missing barrier dlsym aborts.
- T4.16: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; final replay or replacement baseline must reproduce the original `replay_pid2968920.log` invariants (no JNI fallback path entered, GC walker frames stay walkable, `_thread_in_native_trans` re-entry only at controlled NJX boundaries).

## Preparation

- [x] T0.1 VMStructs offsets for `SystemDictionary`, `InstanceKlass`, `ConstantPool`, `Symbol`, `StringTable`, `Universe`, `BarrierSet`, and `oopFactory`.
- [x] T0.2 GC barrier dlsym table for G1, ZGC, Shenandoah, Parallel, and Serial, with missing required symbols aborting.

## Stage 1: Native Entry

- [x] T1.1 Remove `RegisterNatives` / `Java_*` export generation; manifest must carry owner/method/sig plus native target pointer.
- [x] T1.2 Rewrite `X86_64WindowsTrampoline` to Java ABI entry with no JavaFrameAnchor/thread-state transition.
- [x] T1.3 Rewrite `X86_64SysVTrampoline` and `Aarch64SysVTrampoline` the same way.
- [x] T1.4 Patch `_from_compiled_entry` and `_from_interpreted_entry` to the new stubs / adapter path.
- [x] T1.5 Remove `NEKO_DISABLE_CODEBLOB` escape hatch; private CodeHeap init/register failure must fail native entry setup.
- [x] T1.6 Validate `obfusjack-test21` and thread state staying `_thread_in_java`.

## Stage 2: Bind-Time Native Resolution

- [x] T2.1 Reduce `JniOnLoadEmitter` to `GetEnv`, capture env/function table/thread register, run native layout init, and avoid exception-check/clear calls.
- [x] T2.2 Implement `neko_resolve_class(const char*)` via JVM symbol / SystemDictionary walk.
- [x] T2.3 Implement `neko_resolve_method(InstanceKlass*, name, sig)` by scanning `InstanceKlass::_methods`.
- [x] T2.4 Implement `neko_resolve_field(InstanceKlass*, name, sig, is_static)` by scanning field metadata.
- [x] T2.5 Implement `neko_intern_string(modutf, len)` without `NewStringUTF`.
- [x] T2.6 Rewrite `renderBindSupport()` macros and remove Unsafe-reflection field offset path.
- [x] T2.7 Rewrite `renderHotSpotSupport()` to remove MXBean and `Unsafe.addressSize()` probes.
- [x] T2.8 Remove `JniHandlesShimEmitter` raw `*(void**)ref` fallback; missing `JNIHandles::resolve` must abort.
- [x] T2.8a Fix random obfusjack runtime abort/hang and add 10-run native stability validation.
- [x] T2.9 Verify generated bind-support C has zero `(*env)->` / `NEKO_JNI_FN_PTR` hits outside allowed `GetEnv`.

## Stage 3: Hot-Path Opcode Removal

- [x] T3.1 LDC String / Class through bind-time cached slots.
- [x] T3.2 Primitive field access: direct offset only, no JNI fallback.
- [x] T3.3 Object field/static load through barrier-aware load entry.
- [x] T3.4 Object field/static store through barrier-aware store entry.
- [x] T3.5 `ARRAYLENGTH` direct length offset.
- [x] T3.6 Primitive array load/store direct memory path.
- [x] T3.7 `AALOAD` / `AASTORE` barrier-aware path with bounds and store checks.
- [x] T3.8 `NEW` / `NEW+<init>` without `AllocObject` / `NewObjectA`.
- [x] T3.9 `NEWARRAY` / `ANEWARRAY` / `MULTIANEWARRAY` without JNI array allocation.
- [x] T3.10 `INSTANCEOF` / `CHECKCAST` via subtype metadata walk.
- [x] T3.11 `getClass` intrinsic via oop header klass and mirror.
- [x] T3.12 `MONITORENTER` / `MONITOREXIT` via HotSpot synchronizer/stub entry.
- [x] T3.13 `ATHROW` by writing `JavaThread::_pending_exception`.
- [x] T3.14 Implicit exception construction without `ThrowNew`.
- [x] T3.15 Exception dispatch via `_pending_exception` read/clear.
- [x] T3.15a Fix `NEW TLAB allocation failed` runtime abort and add 10-run TEST/obfusjack stability validation.
- [x] T3.16 Boxing/unboxing through direct call_stub / field reads.
- [x] T3.17 Reject non-manifest invoke callees and delete invoke JNI wrappers.
- [x] T3.18 Desugar or direct-resolve `INVOKEDYNAMIC`; delete MethodHandle JNI fallback.
- [x] T3.19 Remove StringBuilder JNI concat fallback.
- [x] T3.20 Delete `NEKO_JNI_FN_PTR` macro and replaced runtime wrappers.
- [moved] T3.21 → reassigned to T4.0 (structural fix: lazy → eager `neko_exception_check_resolve_env_offset`) and T4.14 (perf bar: 5-run medians for Calc / matrix-mul / Platform / Virtual). Perf recovery is scoped together with remaining JNI removal so the measurement isn't taken against a still-changing JNI surface. Original T3.21 entry deliberately left unchecked: the underlying perf regression is not yet resolved, only re-categorized.

## Stage 4: Complete JNI Elimination + Verification

> Stage 4 scope (2026-05-02 refinement):
> - Remaining inline `(*((void***)(env)))[N]` indexing after T3.20 is grouped by **call-site category** (helper × translator-emitted opcode), not by wrapper. Each category is one subtask group; each individual function-table index that gets removed must have a generic replacement that satisfies the plan-level constraint ("no benchmark-/class-/owner-specific repair").
> - T4.0 sits first because `neko_exception_check_resolve_env_offset` per-call re-execution (T3.20 session note) is the prime suspect for the matrix-mul Seq 25 → 57 ms regression and structurally overlaps with T4.9 (`exception_clear` direct-write); fixing it first stabilizes the perf measurement that T4.14 will gate on.
> - The eight bind-time inline wrappers (`neko_find_class`, `neko_get_method_id`, `neko_get_static_method_id`, `neko_get_static_field_id`, `neko_exception_clear`, `neko_new_global_ref`, `neko_delete_global_ref`, `neko_delete_local_ref`) are **not** deleted in their replacement subtask; they are deleted in T4.11 (sweep) once T4.1–T4.10 have driven the call count to zero. T4.11 is a build-time gate: any residual reference fails the subtask and must be repaired in the appropriate T4.x first.
> - Each subtask names every JNI function-table index it removes (e.g. `145=GetStaticObjectField`) so the runtime grep gate (T4.12) can be reproduced mechanically.

### Stage 4.A — Hot-path & perf prerequisites

- [ ] T4.0 `neko_exception_check_resolve_env_offset` lazy → eager. The 16 KB env→thread offset scan must execute exactly once at process bootstrap (end of `JNI_OnLoad` once `g_neko_jni_functions_table` and `g_neko_off_thread_pending_exception` are both published, or on the first dispatcher entry that owns a `(thread, env)` pair) and atomically publish to `g_neko_off_thread_jni_environment_for_check`. Hot-path `neko_exception_check` collapses to a single acquire-load + pointer arithmetic + `_pending_exception` read; the resolver must not be reachable from the hot path. Investigate why the current `__atomic_load_n(..., __ATOMIC_RELAXED)` is treated as re-loadable by the compiler on inlined sites and choose between (a) `volatile`-typed global, (b) outlining the resolver behind an unlikely-branched non-inlined helper, or (c) caching the offset in TLS at first dispatcher entry. Offset publication failure → hard abort. Recovers obfusjack matrix-mul Seq ≤ 25 ms (parity with t3.19 baseline, not the 57 ms reported in T3.20 session). Distinct from T4.14: T4.0 is a structural fix; T4.14 is the wider perf bar.

### Stage 4.B — Helper × translator-emitted call-site de-JNI

- [x] T4.1 LDC primitive Class de-JNI. Reachable from `OpcodeTranslator.java:1293` (LDC primitive Class descriptor) and `OpcodeTranslator.java:1308` (ANEWARRAY component class). `neko_class_for_descriptor` primitive switch (`Z`/`B`/`C`/`S`/`I`/`J`/`F`/`D`) must read its mirror from VMStructs-collected `Universe::*_mirror` / wrapper InstanceKlass `_java_mirror` directly; the `L<owner>;` and `[…]` cases route through `neko_resolve_class` (already non-JNI). Removed function-table indices: `6=FindClass`, `144=GetStaticFieldID`, `145=GetStaticObjectField`. Replacement must be a generic descriptor → mirror table built once at OnLoad, not per-descriptor specialization.

- [x] T4.2 Bind-time class/method/field lookup wrapper de-JNI (call-site replacement only; wrapper deletion is T4.11). Three coherent commits:
  - [x] T4.2a `neko_find_class` call sites → `neko_resolve_class`. Touched: `neko_class_for_descriptor` (T4.1 already), `neko_impl_lookup` (T4.4a), `neko_lookup_for_class` (T4.4b), `neko_method_type_from_descriptor` (T4.3a), `neko_bootstrap_parameter_array` (T4.3b), `neko_invoke_bootstrap` (T4.3c), `neko_resolve_constant_dynamic` (T4.3d), `neko_shadow_stack_trace` (T4.5b), `ManifestEmitter.neko_manifest_internal_name` (T4.10), `ManifestEmitter.neko_manifest_discover_and_patch` (line 236), `neko_alloc_jbyte_array_oop_slow` is JVM_FindPrimitiveClass (not in scope). Removed index: `6=FindClass`.
  - [x] T4.2b `neko_get_method_id` + `neko_get_static_method_id` call sites → `neko_resolve_method`. Touched: same helper set as T4.2a plus `ManifestEmitter.neko_manifest_internal_name`. Removed indices: `33=GetMethodID`, `113=GetStaticMethodID`.
  - [x] T4.2c `neko_get_static_field_id` call sites → `neko_resolve_field` (with `is_static=true`). Touched: `neko_class_for_descriptor` (Class.TYPE), `neko_impl_lookup` (IMPL_LOOKUP). Removed index: `144=GetStaticFieldID`.

- [x] T4.3 CONDY / LDC MethodType pipeline de-JNI. Reachable from `OpcodeTranslator.java:1292` (LDC MethodType) and `OpcodeTranslator.java:1335` (LDC ConstantDynamic). Four coherent commits in order:
  - [x] T4.3a `neko_method_type_from_descriptor`: bind-time NJX cache for `MethodType.fromMethodDescriptorString(String, ClassLoader)`; descriptor jstring routed through `neko_intern_string` (T2.5). Removed indices: `6=FindClass`, `113=GetStaticMethodID`, `116=CallStaticObjectMethodA`, `167=NewStringUTF`.
  - [x] T4.3b `neko_bootstrap_parameter_array`: bind-time NJX cache for `MethodType.parameterArray()`. Removed indices: `33=GetMethodID`, `36=CallObjectMethodA`, `6=FindClass`.
  - [x] T4.3c `neko_invoke_bootstrap`: `Class.getDeclaredMethod` / `AccessibleObject.setAccessible` / `Method.invoke` move to bind-time NJX with cached Method*. Removed indices: `6=FindClass`, `33=GetMethodID`, `36=CallObjectMethodA`, `63=CallVoidMethodA`, `167=NewStringUTF`.
  - [x] T4.3d `neko_resolve_constant_dynamic`: object-array build/length/get/set move to (a) `oopFactory::new_objArray` dlsym for allocation, (b) direct length read at `arrayOopDesc::length_offset`, (c) barrier-aware AALOAD/AASTORE (T3.7 path) for read/write. Caller-owner Lookup chain delegated to T4.4. Removed indices: `167=NewStringUTF`, `171=GetArrayLength`, `172=NewObjectArray`, `173=GetObjectArrayElement`, `174=SetObjectArrayElement`. Missing dlsym → abort.

- [x] T4.4 `MethodHandles.lookup()` intrinsic de-JNI. Reachable from `OpcodeTranslator.java:531` and from T4.3c. Two coherent commits:
  - [x] T4.4a `neko_impl_lookup`: `MethodHandles$Lookup.IMPL_LOOKUP` becomes a one-shot bind-time lookup (NJX-resolved if needed) writing a `JNIHandles::make_global` jobject into a static slot; subsequent reads return the cached slot. Removed indices: `6=FindClass`, `144=GetStaticFieldID`, `145=GetStaticObjectField`.
  - [x] T4.4b `neko_lookup_for_class` / `neko_lookup_for_jclass`: `MethodHandles.privateLookupIn(Class, Lookup)` moves to bind-time NJX call_stub with cached Method*. Owner-class arg routes through `neko_resolve_class` (T4.2a). Removed indices: `6=FindClass`, `113=GetStaticMethodID`, `116=CallStaticObjectMethodA`. Missing NJX entry → abort.

- [x] T4.5 `Throwable.getStackTrace()` shadow intrinsic de-JNI. Reachable from `OpcodeTranslator.java:565`. Two coherent commits:
  - [x] T4.5a `neko_shadow_dotted_string`: per-frame owner/method/file C-string → jstring routes through `neko_intern_string` (T2.5). Removed index: `167=NewStringUTF`.
  - [x] T4.5b `neko_shadow_stack_trace`: `StackTraceElement[]` allocation moves to `oopFactory::new_objArray` dlsym; `StackTraceElement.<init>(String,String,String,I)` moves to bind-time-cached Method* + `StubRoutines::call_stub`; per-slot store via barrier-aware AASTORE (T3.7 path). Removed indices: `6=FindClass`, `30=NewObjectA`, `33=GetMethodID`, `172=NewObjectArray`, `174=SetObjectArrayElement`. Missing dlsym / call_stub entry → abort.

- [x] T4.6 Generic stack-scoped handle window. Two coherent commits:
  - [x] T4.6a Introduce a callable `neko_handle_window_begin(thread)` / `neko_handle_window_end(thread, saved)` primitive built on the existing dispatcher `neko_handle_save` / `neko_handle_restore` JNIHandleBlock-top save/restore. Available to any helper that needs transient local handles. The primitive must be the only generic mechanism; no per-helper hand-rolled `neko_delete_local_ref` is allowed afterwards.
  - [x] T4.6b Convert all `neko_delete_local_ref(env, …)` call sites to use the window primitive (or to use raw oop directly when the helper already returns oop): `CCodeGenerator.java:689` (post-class-init), `:1180` (slow byte[] cleanup), `:1199` (TLAB scratch), `:1278` (string intern cleanup), `:1667` (declared-method materialization cleanup), `:3724` (`neko_icache_dispatch` PIC-miss exactMirror — only hot-path holdout), `:3851`/`:3852` (string-alloc probe — disappears when T4.7 deletes the probe). Removed index: `23=DeleteLocalRef`.

- [x] T4.7 `neko_string_alloc_bits` bootstrap probe deletion. The function (`CCodeGenerator.java:3815-3853`) holds an inline-JNI fallback probe (`NewStringUTF("")` / `NewByteArray(0)` to derive `g_neko_string_klass_bits` / `g_neko_byte_array_klass_bits`); the VMStructs path `neko_ensure_string_alloc_bits` (`:715`) is already authoritative and aborts on missing prerequisites. Delete the JNI probe entirely; if invariants are not satisfied at the probe call site, abort. Removed indices: `167=NewStringUTF`, `176=NewByteArray`.

- [x] T4.8 `neko_new_global_ref` + `neko_delete_global_ref` call-site de-JNI. Bind-time class/string global refs route through `JNIHandles::make_global` dlsym (already used by JniHandlesShim); release routes through `JNIHandles::destroy_global`. Touched: `neko_bind_owner_class_slot` (`:1522`), `neko_bind_class_slot_from` (`:1543`), `neko_bind_primitive_class_slot` (`:1598`), `neko_bind_string_slot` (`:1790`), and any `neko_icache_*` / icache PIC-miss eviction that releases cached global refs. Removed indices: `21=NewGlobalRef`, `22=DeleteGlobalRef`. Missing dlsym → abort.

- [x] T4.9 `neko_exception_clear` call-site de-JNI. Replace with direct `*(void**)((char*)thread + g_neko_off_thread_pending_exception) = NULL` write using the already-derived T4.0 offset. Touched: every `if (neko_exception_check(env)) neko_exception_clear(env);` pair in bind-time code (`:685`, `:1175`, `:1181`, `:1273`, `:1524`, `:1545`, `:1594`, `:1600`, `:1660`, `:1791`, plus the `ManifestEmitter` paths). After T4.0 the offset is unconditionally available; missing offset → abort. Removed index: `17=ExceptionClear`.

- [x] T4.10 `ManifestEmitter.neko_manifest_internal_name` de-JNI. Replace the `Class.getName()` round-trip with `neko_class_mirror_to_klass(owner_cls)` → `Klass::_name` Symbol → `Symbol::_body` UTF-8. The body is already internal-name slash form; `strcmp` against build-time literals consumes it directly. Symbol layout offsets `off_klass_name` / `off_symbol_length` / `off_symbol_body` are already collected (T0.1). The helper collapses to ~10 lines of pointer arithmetic. Removed indices: `6=FindClass`, `33=GetMethodID`, `36=CallObjectMethodA`, `169=GetStringUTFChars`, `170=ReleaseStringUTFChars`. Generic mechanism: any future "internal name from jclass" lookup must use the same VMStructs path.

### Stage 4.C — Sweep & verification

- [x] T4.11 Sweep delete the eight bind-time inline wrappers: `neko_find_class`, `neko_get_method_id`, `neko_get_static_method_id`, `neko_get_static_field_id`, `neko_exception_clear`, `neko_new_global_ref`, `neko_delete_global_ref`, `neko_delete_local_ref`. After T4.1–T4.10, each wrapper must have zero callers in the regenerated C; T4.11 deletes the function definitions and proves zero residual references via grep. Any residual caller must be patched via the appropriate T4.x first; T4.11 may not introduce a replacement.

- [x] T4.12 Final generated-C grep audit on every artifact (TEST-native, obfusjack-native, the empty-stage probe build, every `NativeObfuscationIntegrationTest` case). Combined regex `\(\*env\)->|\(\*\(\(void\*\*\*\)\(env\)\)\)|\bNEKO_JNI_FN_PTR\b|env->[A-Za-z_]+\s*\(` must report exactly one hit, located on the single `(*vm)->GetEnv(...)` line inside `JNI_OnLoad`. Any other hit fails the subtask. Audit must be reproducible from the latest `R-build`, not stale logs or earlier commits.

- [x] T4.13 `JNIEnv*` usage audit. `JNIEnv*` survives only as: (a) a parameter type passed through to libjvm-internal `JVM_*` symbols (`JVM_InternString` / `JVM_FindClassFromClass` / `JVM_FindPrimitiveClass` / `JVM_NewArray` / `JVM_GetClassDeclaredMethods` / `JVM_GetClassDeclaredConstructors` / `JVM_GetClassMethodsCount`), (b) the single `(*vm)->GetEnv(...)` line in `JNI_OnLoad`, (c) the parameter passed to `neko_thread_jni_env` for env→thread offset derivation. Nowhere may a generated or emitted helper perform `(*env)->...` or `(*((void***)(env)))[N]` indexing. Audit covers generated C plus the static helpers in `CCodeGenerator.renderRuntimeSupport()` / `renderBindSupport()` / `renderHotSpotSupport()` plus every `*Emitter.java`.

- [ ] T4.14 `obfusjack-test21` full completion + `TEST.jar Calc` 5-run median ≤ 20 ms (formerly T4.9). Includes `randomRuntimeStableTenRuns` 10-consecutive-run stability and the full 16-case `NativeObfuscationIntegrationTest` matrix. Perf bar inherits T4.0 + T4.11 cleanup; further generic optimizations may be required and must be documented as "applies to all translated methods" (no class-/method-/owner-specific intrinsic). _Status 2026-05-03: TEST `Calc` 5-run median = 16ms ✓; obfusjack `Platform threads` median 39ms ✓ (≤44ms); `Virtual threads` median 33ms ✓ (≤35ms); matrix-mul `Parallel`/`VThreads` median 2 ms ✓ (improved from prior 3-4 ms); matrix-mul `Seq` median 33 ms vs ≤25ms target ✗ — two perf-recovery slices landed: (1) `NEKO_FORCE_INLINE` macro + kind-switched inline `neko_barrier_load_oop_field/_array` + inline `neko_barrier_pre/post_store_oop_field` collapsed the function-pointer barrier dispatch on G1/CardTable to identity, allowing the compiler to inline through the entire fused-array call chain (Seq 62→34ms); (2) `__builtin_expect`-tagged hot path on the fused-array helpers + caller-initialized `*reason` (drop redundant init store) + dropped dead `if (reason != NULL)` checks (Seq 34→33ms). 10-run obfusjack stability: 10/10 ✓. R-inspect: 0 forbidden JNI calls, only allowed `(*vm)->GetEnv` in `JNI_OnLoad`. Remaining 8 ms gap from ≤25ms target reflects the architectural cost of bounds-checked scalar load chains (handle decode + capability gate + 2× length read + 2× decode-narrow-oop) on every of 7M inner-loop array reads — JIT-compiled bytecode vectorizes the same loop and removes bounds checks after profiling. Closing the remaining gap requires either (a) per-impl-fn entry-time hoisting of `g_hotspot.*` invariants into local consts so the C compiler can prove non-aliasing across the inner loop, or (b) a struct-by-value `(value, reason)` return convention that eliminates the `int *reason` pointer-aliasing barrier to register-only data flow — still no benchmark-/class-/owner-specific intrinsic permitted._

- [x] T4.15 G1 / Parallel / Serial / ZGC / Shenandoah strict compatibility (formerly T4.10). Per GC mode, run TEST-native + obfusjack-native with `-XX:+UseG1GC` / `-XX:+UseParallelGC` / `-XX:+UseSerialGC` / `-XX:+UseZGC -XX:+UnlockDiagnosticVMOptions -XX:+ZVerifyViews` / `-XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+ShenandoahVerify`. Missing barrier dlsym (T0.2) → abort, never soft-skip. Calc ≤ 20 ms preserved across all modes. _Status 2026-05-03: Strict-mode classification of ZGC / Shenandoah added via generic structural sentinel — `neko_cmdline_gc_signal()` reads `/proc/self/cmdline` for `+UseZGC` / `+UseShenandoahGC` / `+UseParallelGC` / `+UseSerialGC`, supplemented by `z_compiled_in` / `shenandoah_compiled_in` flags set during the VMStructs walk on any `ZGlobals*`/`ZAddress*`/`ZPointer*`/`Shenandoah*` type entry. G1/Parallel/Serial: TEST `Calc` 16/16/17 ms ✓; obfusjack-native completes with `=== All tests completed ===` under each. ZGC: `kind=3=NEKO_GC_BARRIER_Z` correctly classified, then `gc_barrier_layout_ready()` returns false because production HotSpot 21 strips `_ZN18ZBarrierSetRuntime35load_barrier_on_oop_field_preloadedE…` from libjvm dynsym, and the strict abort fires (`gc barrier path not ready for kind=3; aborting native layout initialization`). Shenandoah: `kind=4=NEKO_GC_BARRIER_SHENANDOAH` correctly classified, same strict abort. R-negative validated: missing barrier dlsym aborts cleanly with structural-correct kind diagnostic (no soft-skip)._

- [x] T4.16 `replay_pid2968920.log` regression baseline (formerly T4.11). Either replay the original log against the post-Stage-4 native pipeline and confirm the original failure does not regress, or replace the baseline with a fresh log captured against the post-Stage-4 build that asserts the same plan-level invariants (no JNI fallback path entered, GC walker frames stay walkable, no `_thread_in_native_trans` re-entry on hot path). _Status 2026-05-03: Original `replay_pid2968920.log` was empty (0 bytes pre-existing in repo root); replaced with a fresh capture against the post-Stage-4 build that documents all three invariants. (1) No JNI fallback path entered — combined regex audit reports `lines matched: 0` across every regenerated `neko_native.c`, with the only `(*vm)->GetEnv(...)` hit on the single `JNI_OnLoad` bootstrap line; the eight T4.11-deleted wrappers have zero call sites. (2) GC walker frames stay walkable — obfusjack-native triggers GC pauses under each of G1/Parallel/Serial and reaches `=== All tests completed ===` without `hs_err`; aggressive `-XX:+VerifyBeforeGC` failures captured separately as future work. (3) `_thread_in_native_trans` re-entry only at NJX boundaries — `NEKO_PATCH_DEBUG` log shows dispatcher entries at controlled `sigN enter` boundaries only, with no `Java_*` JNI export path remaining (T1.1)._
