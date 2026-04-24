## Executive summary (3 sentences)

The C signature mismatch is not caused by manifest name-only lookup, descriptor loss, or `translateDirectInvoke`; the failing generated C comes from W7's virtual/interface inline-cache direct-stub path. The direct-cache stub has the JNI-style adapter signature `neko_icache_direct_stub(JNIEnv*, jobject, const jvalue*)`, but `Wave3InvokeStaticEmitter.renderIcacheDirectStub(...)` incorrectly forwards both `env` and `receiver` into `neko_impl_N`, whose raw native ABI is only `(_this, descriptor args...)`. Fix M is therefore to change the direct-cache stub emitter to adapt to the native implementation ABI by calling `neko_impl_N((void*) receiver, args...)`, with a descriptor invariant check; do not change manifest binding descriptors or native implementation prototypes.

## Empirical evidence inventory

### W7 diff/source evidence

- `f98fb46` only changed instance invoke null-receiver behavior to use `nullReceiverCheck(...)` / cached NPE. It did not alter native function prototypes, binding creation, or direct-cache stub ABI.
- `08bc916` activated `translateVirtualDispatchWithCache(mi)` for `INVOKEVIRTUAL` and `INVOKEINTERFACE` in `OpcodeTranslator.translateMethodInvoke(...)`, replacing the previous hard throw. This is the first W7 commit that routes the failing virtual call sites through inline-cache metadata/direct-stub generation.
- `a5583be` removed `NativeTranslationSafetyChecker`'s reference invoke signature deferrals and skips manifest-target enforcement for virtual/interface invokes. This widened admission enough for the failing methods to be selected, but it did not create or mutate C prototypes.
- Current `OpcodeTranslator.java:306-330` builds the W7 cache site, asks `directInvokeCacheCandidates(mi)` for a direct translated target, and passes `args = Type.getArgumentTypes(mi.desc)` plus `ret = Type.getReturnType(mi.desc)` into `codeGenerator.reserveInvokeCacheDirectStub(...)`.
- Current `OpcodeTranslator.java:353-359` looks up direct candidates by exact `owner#name+desc` key: `translatedBindings.get(bindingKey(mi.owner, mi.name, mi.desc))`. This is descriptor-sensitive, not name-only.
- Current `NativeTranslator.java:56-65` constructs every `NativeMethodBinding` with `selection.method().descriptor()`, and `NativeTranslator.java:98-110` constructs each `CFunction` parameter list from `Type.getArgumentTypes(method.descriptor())`. Binding creation preserves the JVM descriptor.
- Current `AssemblyStubEmitter.java:26-44` emits prototypes from `binding.descriptor()`: non-static `_this` plus every descriptor argument. The observed prototype `double neko_impl_8(void* _this);` is exactly what a non-static `()D` method should produce.
- Current `ImplBodyEmitter.java:11-23` emits native implementation definitions with only `CFunction` parameters and then obtains `JNIEnv *env = neko_current_env();` inside the body. Native implementations do not accept `JNIEnv*` as a parameter.
- Current `Wave3InvokeStaticEmitter.java:295-314` emits the broken direct-cache adapter call:
  ```java
  sb.append(stub.binding().cFunctionName()).append("(env, receiver");
  for (...) sb.append(", ").append("args[").append(i).append("]...");
  ```
  This is the concrete source of the extra argument.

### Actual failing C evidence: obfusjack-test21.jar

- The failing obfusjack build used `/tmp/neko_native_sources_10396029426509125646/`, as recorded in `verification/w1/final-v11/obf-obfusjack.log` at the native build command.
- The compiler error is the reported one:
  - `neko_native.c:5742`: `result.d = neko_impl_8(env, receiver);`
  - `neko_native.h:15`: `double neko_impl_8(void* _this);`
  - equivalent failures also occur for `neko_impl_9` and `neko_impl_10`.
- The generated header confirms the implementation ABI has no descriptor arguments and no `JNIEnv*`:
  ```c
  // /tmp/neko_native_sources_10396029426509125646/neko_native.h:15-17
  double neko_impl_8(void* _this);
  double neko_impl_9(void* _this);
  double neko_impl_10(void* _this);
  ```
- The generated manifest confirms those implementations are the record accessors with descriptor `()D`, not overloads with reference arguments:
  ```c
  // /tmp/neko_native_sources_10396029426509125646/neko_native.c:248-251
  { "org/example/Main$Triangle", "a", "()D", ..., (void*)&neko_impl_8, ... },
  { "org/example/Main$Triangle", "b", "()D", ..., (void*)&neko_impl_9, ... },
  { "org/example/Main$Triangle", "c", "()D", ..., (void*)&neko_impl_10, ... },
  { "org/example/Main", "isValidTriangle", "(Lorg/example/Main$Triangle;)Z", ..., (void*)&neko_impl_11, ... },
  ```
- The generated direct-cache stubs are the only bad call sites:
  ```c
  // /tmp/neko_native_sources_10396029426509125646/neko_native.c:5740-5755
  static jvalue neko_icache_stub_6_0_0(JNIEnv *env, jobject receiver, const jvalue *args) {
      jvalue result = {0};
      result.d = neko_impl_8(env, receiver);
      return result;
  }
  // same pattern for neko_impl_9 and neko_impl_10
  ```
- The generated inline-cache metadata for those sites is descriptor-correct:
  ```c
  // /tmp/neko_native_sources_10396029426509125646/neko_native.c:5760-5762
  static const neko_icache_meta neko_icache_meta_6_0_0 = {"a", "()D", &g_cls_5, neko_icache_stub_6_0_0, JNI_FALSE};
  static const neko_icache_meta neko_icache_meta_6_0_1 = {"b", "()D", &g_cls_5, neko_icache_stub_6_0_1, JNI_FALSE};
  static const neko_icache_meta neko_icache_meta_6_0_2 = {"c", "()D", &g_cls_5, neko_icache_stub_6_0_2, JNI_FALSE};
  ```
- Reading the original bytecode verifies the caller's `mi.desc` also is `()D`:
  ```text
  // javap -classpath test-jars/obfusjack-test21.jar -c -p org.example.Main
  static boolean isValidTriangle(org.example.Main$Triangle);
      0: aload_0
      1: invokevirtual #47 // Method org/example/Main$Triangle.a:()D
      5: aload_0
      6: invokevirtual #50 // Method org/example/Main$Triangle.b:()D
     10: aload_0
     11: invokevirtual #53 // Method org/example/Main$Triangle.c:()D
  ```
- The generated C for `neko_impl_11` also proves `OpcodeTranslator` popped no descriptor arguments for these invokes (`jvalue * __args = NULL`) and used the same `()D` metadata:
  ```c
  // /tmp/neko_native_sources_10396029426509125646/neko_native.c:6051-6062
  { jvalue * __args = NULL; jobject __recv = POP_O(); ...
    jmethodID mid = neko_bound_method(env, g_mid_0, "org/example/Main$Triangle", "a", "()D", JNI_FALSE); ...
    jvalue __ic_result = neko_icache_dispatch(env, &neko_icache_6_0_0, &neko_icache_meta_6_0_0, __recv, mid, __args); ... }
  ```

### Actual failing C evidence: SnakeGame.jar

- The failing SnakeGame build used `/tmp/neko_native_sources_1925899773678204215/`, as recorded in `verification/w1/final-v11/obf-snake.log` at the native build command.
- The compiler reports the same shape:
  - `neko_native.c:5755`: `neko_impl_5(env, receiver);`
  - `neko_native.h:12`: `void neko_impl_5(void* _this);`
  - `neko_native.c:5761`: `neko_impl_6(env, receiver);`
  - `neko_native.h:13`: `void neko_impl_6(void* _this);`
- The generated manifest identifies these methods as zero-arg instance methods:
  ```c
  // /tmp/neko_native_sources_1925899773678204215/neko_native.c:291-294
  { "ThreadsController", "run", "()V", ..., (void*)&neko_impl_3, ... },
  { "ThreadsController", "moveExterne", "()V", ..., (void*)&neko_impl_5, ... },
  { "ThreadsController", "deleteTail", "()V", ..., (void*)&neko_impl_6, ... },
  ```
- The direct-cache stubs again add `env` incorrectly:
  ```c
  // /tmp/neko_native_sources_1925899773678204215/neko_native.c:5753-5762
  static jvalue neko_icache_stub_3_3_2(JNIEnv *env, jobject receiver, const jvalue *args) {
      jvalue result = {0};
      neko_impl_5(env, receiver);
      return result;
  }
  static jvalue neko_icache_stub_3_3_3(JNIEnv *env, jobject receiver, const jvalue *args) {
      jvalue result = {0};
      neko_impl_6(env, receiver);
      return result;
  }
  ```
- Reading the original bytecode verifies the caller's descriptors match the manifest bindings:
  ```text
  // javap -classpath test-jars/SnakeGame.jar -c -p ThreadsController
  public void run();
     11: aload_0
     12: invokevirtual #83 // Method moveExterne:()V
     15: aload_0
     16: invokevirtual #86 // Method deleteTail:()V
  ```
- The generated translated `run` body uses `__args = NULL` and metadata `"moveExterne", "()V"` / `"deleteTail", "()V"`, so the descriptor path is internally consistent. The only mismatch is the direct-cache adapter adding `env` before `receiver`.

## Hypothesis testing

### H1: rejected

H1 predicts that the implementation binding is `()D` while the caller's bytecode is an unrelated `(Ljava/lang/Object;)D`, likely due to name-only manifest lookup. The actual obfusjack caller bytecode is `INVOKEVIRTUAL org/example/Main$Triangle.a:()D`, `b:()D`, and `c:()D`, and the manifest bindings for `neko_impl_8/9/10` are the same `()D` descriptors. The extra C argument is `env`, not a descriptor argument from `mi.desc`, and `directInvokeCacheCandidates(...)` uses `owner#name+desc`, not name-only lookup.

### H2: partially confirmed as the trigger, rejected as the mechanism

The W7 `translateVirtualDispatchWithCache(...)` path is involved: it is the path that reserves `neko_icache_stub_*` and attaches it to `neko_icache_meta_*`. However, it does not emit `neko_impl_N(...)` directly for non-manifest methods; `directInvokeCacheCandidates(mi)` returns a binding only for exact `owner#name+desc`, and `reserveInvokeCacheMeta(...)` receives `translatedStubSymbol = null` when no direct candidate exists. The failing cases all have exact manifest candidates; the bug is that the generated direct stub calls that exact candidate with the wrong ABI.

### H3: rejected

`NativeTranslator` preserves descriptors when constructing bindings. The generated C corroborates this: manifest entries, implementation `NEKO_TRACE` signatures, header prototypes, and bytecode descriptors all agree (`Triangle.a/b/c:()D`, `ThreadsController.moveExterne/deleteTail:()V`). There is no evidence of a binding being created as `()<ret>` for a method that actually has `(ref)<ret>`.

### H4: rejected for descriptor mismatch; adjacent gate noted

The described `canDirectInvoke(...)` descriptor mismatch is not the failing path: W7 virtual dispatch does not call `canDirectInvoke(...)`; it calls `directInvokeCacheCandidates(...)`, which fetches by exact descriptor-bearing key. `directCallSafe()` did enable these stubs because `Triangle` is final and the SnakeGame targets are private/safely direct-callable, but the selected bindings' descriptors still match the bytecode. The adjacent observation is that `directInvokeCacheCandidates(...)` does not share every guard from the older `canDirectInvoke(...)` helper, but that is not the source of the C signature error observed here.

## Root cause

The root cause is a stale ABI assumption in `Wave3InvokeStaticEmitter.renderIcacheDirectStub(...)`. The inline-cache direct-stub function itself must have the cache ABI:

```c
typedef jvalue (*neko_icache_direct_stub)(JNIEnv *env, jobject receiver, const jvalue *args);
```

but it is only an adapter. The translated native implementation ABI is generated from the Java method descriptor and does not include `JNIEnv*`; instance implementations are:

```c
ret neko_impl_N(void* _this, descriptor_args...);
```

`renderIcacheDirectStub(...)` currently confuses these two ABIs and emits:

```c
neko_impl_N(env, receiver, descriptor_args...);
```

where it must emit:

```c
neko_impl_N((void*) receiver, descriptor_args...);
```

This explains both jars exactly: zero-argument instance methods should pass one native argument (`_this`) but the stub passes two (`env`, `receiver`), producing the compiler's “expected single argument `_this`, have 2 arguments”.

## Fix M prescription

Primary fix: change `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/Wave3InvokeStaticEmitter.java`, not `AssemblyStubEmitter` or `NativeTranslator`.

1. In `renderIcacheDirectStub(IcacheDirectStubRef stub)`, add a descriptor invariant before emitting C:
   ```java
   String siteDescriptor = Type.getMethodDescriptor(stub.returnType(), stub.args());
   if (!stub.binding().descriptor().equals(siteDescriptor)) {
       throw new IllegalStateException("inline-cache direct stub descriptor mismatch: "
           + stub.binding().ownerInternalName() + '#'
           + stub.binding().methodName() + stub.binding().descriptor()
           + " vs " + siteDescriptor);
   }
   if (stub.binding().isStatic()) {
       throw new IllegalStateException("inline-cache direct stub requires instance binding: "
           + stub.binding().ownerInternalName() + '#'
           + stub.binding().methodName() + stub.binding().descriptor());
   }
   ```

2. In the same method, mark unused adapter parameters without changing the stub typedef:
   ```java
   sb.append("    (void)env;\n");
   if (stub.args().length == 0) {
       sb.append("    (void)args;\n");
   }
   ```

3. Replace the broken implementation call emission:
   ```java
   sb.append(stub.binding().cFunctionName()).append("(env, receiver");
   ```
   with native-ABI forwarding:
   ```java
   sb.append(stub.binding().cFunctionName()).append("((void*)receiver");
   ```
   Keep the existing loop that appends descriptor arguments from `args[i]`, because those are the descriptor arguments and should still be forwarded after `_this`.

4. Do not add `JNIEnv*` to `AssemblyStubEmitter.renderPrototype(...)`, `NativeTranslator.translateMethod(...)`, or `ImplBodyEmitter`; doing so would corrupt the raw native implementation ABI and the assembly signature dispatch path. The implementations already obtain `JNIEnv *env` internally via `neko_current_env()`.

5. No functional change is required in `NativeTranslationSafetyChecker` for this compile failure. Its W7 admission relaxation exposed the bug, but did not create the bad C call shape.

6. Optional hardening in `OpcodeTranslator.directInvokeCacheCandidates(...)`: keep the exact `bindingKey(mi.owner, mi.name, mi.desc)` lookup, and add an explicit `binding.descriptor().equals(mi.desc)` assertion only as a defensive invariant. It should never fail with the current map, but it would make any future non-exact lookup regression fail before C generation.

Expected generated C after the fix:

```c
static jvalue neko_icache_stub_6_0_0(JNIEnv *env, jobject receiver, const jvalue *args) {
    jvalue result = {0};
    (void)env;
    (void)args;
    result.d = neko_impl_8((void*)receiver);
    return result;
}
```

For a one-argument virtual target, the expected shape is:

```c
neko_impl_N((void*)receiver, args[0].<kind>);
```

not:

```c
neko_impl_N(env, receiver, args[0].<kind>);
```

## Verification plan

1. Re-run the obfusjack and SnakeGame obfuscation/build that produced the failing temp dirs. The previous errors in `verification/w1/final-v11/obf-obfusjack.log` and `verification/w1/final-v11/obf-snake.log` should disappear: no `too many arguments to function call` diagnostics for `neko_impl_8/9/10` or `neko_impl_5/6`.

2. Inspect generated C for the direct-stub ABI:
   ```sh
   grep -R "neko_impl_[0-9][0-9]*(env, receiver" /tmp/neko_native_sources_*/neko_native.c
   ```
   Expected: empty.

3. Confirm the specific formerly failing stubs changed shape:
   ```sh
   grep -nE 'neko_icache_stub_6_0_0|neko_impl_8\(\(void\*\)receiver\)' /tmp/neko_native_sources_*/neko_native.c
   grep -nE 'neko_icache_stub_3_3_2|neko_impl_5\(\(void\*\)receiver\)' /tmp/neko_native_sources_*/neko_native.c
   ```
   Expected: stubs still have `(JNIEnv *env, jobject receiver, const jvalue *args)` externally, but native implementation calls start with `(void*)receiver` and no `env` argument.

4. Confirm prototypes remain descriptor-derived and unchanged:
   ```sh
   grep -n 'double neko_impl_8(void\* _this);' /tmp/neko_native_sources_*/neko_native.h
   grep -n 'void neko_impl_5(void\* _this);' /tmp/neko_native_sources_*/neko_native.h
   ```
   Expected: still present. If these prototypes grow `JNIEnv*`, the fix was applied in the wrong layer.

5. Add or update a generator unit test around `Wave3InvokeStaticEmitter.renderIcacheDirectStubs()` / `CCodeGenerator.generateSource(...)` for a direct-call-safe instance `()D` target. The test should assert the source contains `result.d = neko_impl_...((void*)receiver);` and does not contain `neko_impl_...(env, receiver)`.

6. After compile succeeds, run the normal W7 behavior gates for the two affected jars. This diagnosis only explains the C compile blocker; it does not prove W7 runtime equivalence or strict no-JNI compliance.

## Master plan amendments if any

- Amend W7 with an explicit ABI rule: inline-cache direct stubs are adapters from `neko_icache_direct_stub(JNIEnv*, jobject, const jvalue*)` to the raw native implementation ABI `(receiver, descriptor args...)`; `JNIEnv*` is never part of a `neko_impl_N` signature.
- Add a W7 verification grep: generated C must have zero `neko_impl_[0-9]+(env, receiver` call sites, while direct stubs must still expose the cache ABI typedef.
- If W7-F strict no-JNI is still a hard gate, W7 needs a separate refinement beyond this compile fix: the current `neko_icache_dispatch(...)` fallback path still uses JNI method-resolution/call helpers, so the master plan should either mark that fallback as temporary/non-gateable or require the strict JavaCalls/metadata replacement before W7 can be considered complete.
