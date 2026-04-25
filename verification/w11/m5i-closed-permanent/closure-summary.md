# W11-M5i Closed-Permanent Summary

M5i is CLOSED-PERMANENT per Oracle 16. Oracle 15's two correctness fixes remain landed: `neko_env_from_thread` derives the inline `JNIEnv` address correctly, and cached pending exception writes decode JNI global handle cells as wide oops. Those fixes remove the `libjvm+0xd2f1b5` SIGSEGV class and are retained independently of the fidelity verdict.

The stack-trace fidelity fixture is structurally impossible under strict-no-JNI because translated C stubs do not create HotSpot Java/interpreted/native-wrapper frames with `Method*` metadata. `JVM_FillInStackTrace` can only walk real HotSpot frames, so it cannot synthesize `pack.stacktrace.StackVictim.probe` for a translated C stub. Synthetic `StackTraceElement[]` or backtrace mutation would cross the same GC-barrier and allocation invariants that permanently closed M5h.

Canonical references:

- `.sisyphus/plans/oracle-14-final-deferral-resolution.md` — M5h and M5f CLOSED-PERMANENT.
- `.sisyphus/plans/oracle-15-m5i-decoder-crash.md` — JNIEnv derivation and wide-oop JNI handle fixes.
- `.sisyphus/plans/oracle-16-m5i-fidelity-walkable-frame.md` — M5i CLOSED-PERMANENT fidelity verdict.

Verification performed on 2026-04-25:

- `./gradlew :neko-native:build --no-daemon` exited `0`.
- `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest --no-daemon` exited `0`.
- `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.NativeObfAdmissionGateTest --no-daemon` exited `0` as an admission-count adjunct.
- Admission counts remain `TEST.jar=14`, `obfusjack-test21.jar=17`, `SnakeGame.jar=12`.
- `NativeObfStackTraceTest` is re-disabled with the Oracle 16 rationale.
