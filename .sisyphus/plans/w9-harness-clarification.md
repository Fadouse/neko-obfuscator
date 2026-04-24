# W9 Harness Clarification

Date: 2026-04-24

## Summary

W9 recovery reverted the broken commits `3aa69c6` and `54cbe23`. The latter disabled the obfuscator's core native execution path by preventing entry patch installation and preserving Java bodies, which produced `dp 0/N` even though native libraries were still built and loaded.

The v17 verification round confirms the valid W9 scope when run with the v14 harness shape:

| Jar | Translated | Runtime patch count | Result |
|---|---:|---:|---|
| TEST.jar | 14/75 | `dp 1/14` | Matches v14 native-patching baseline; later intentional `LinkageError` fallback body |
| obfusjack-test21.jar | 17/84 | `dp 6/17` | Matches v14 native-patching baseline; later intentional `LinkageError` fallback body |
| SnakeGame.jar | 12/14 | `dp 2/12` | Native patching active on this host; local v14 artifact hit `HeadlessException` before native load |

## Canonical W9 debug harness

Use this runtime shape for W9 recovery/debug artifacts:

```bash
NEKO_DEBUG=1 java -jar verification/w1/final-v17/TEST-native-debug.jar
NEKO_DEBUG=1 java -jar verification/w1/final-v17/obfusjack-native-debug.jar
NEKO_DEBUG=1 timeout 5 java -jar verification/w1/final-v17/SnakeGame-native-debug.jar
```

Do not add either of these flags when comparing to v14:

- `-Dneko.native.debug=true`
- `-Djava.awt.headless=true`

The debug traces come from the compile-time `NEKO_DEBUG_ENABLED=1` native build, not from a runtime system property. The v15/v16 “corrected harness” changed the runtime envelope and led to misleading crash diagnosis. Future W9/W10 verification should compare against v14/v17-style invocations unless a later plan revision explicitly changes the harness and records why.

## Invariants preserved

- Strict no-JNI steady-state remains intact: `JNI_OnLoad`, one `vm->GetEnv`, Oracle 9 bootstrap throwable-cache JNI allowlist, and cached `env->Throw` only.
- `MANIFEST_ENTRY_SIZE = 88` remains sacred.
- No JVMTI was introduced.
- `NekoNativeLoader` shape remains `{loaded, LOCK}`.
- The native patch path uses `__atomic_*` stores; no pthread fallback was introduced.
