# 🛡️  NekoObfuscator · 混淆技术白皮书 / Obfuscation Techniques Whitepaper

> **状态提示 · Status notice**
> 本项目处于 **早期阶段 (Early Stage / Work in Progress)**。本文档中描述的 Pass 强度参数、阈值、默认值可能在任意版本调整，部分实验性技巧可能被移除或重写。
> This project is in its **early-stage / work-in-progress** phase. The intensities, thresholds and defaults below may change in any commit, and some experimental tricks may be rewritten or removed.

> **作者 · Authors**
> 本文档由 **Anthropic Claude Opus 4.6 / 4.7** 与 **OpenAI GPT 5.4** 协作撰写，环境为 **opencode + ohmyopenagent (ohmyopencode)**。
> Co-authored by **Anthropic Claude Opus 4.6 / 4.7** and **OpenAI GPT 5.4**, environment: **opencode + ohmyopenagent (ohmyopencode)**.

> **⚠️ 免责声明 · Disclaimer**
> 混淆 **不是** 加密，只是提高静态分析成本。使用本工具不会使你的代码变得安全，仅会让逆向更耗时。请勿将混淆视为防盗版/DRM 的唯一手段。
> Obfuscation **is not** encryption; it only raises the cost of static analysis. This tool will not make your code secure — it merely makes reverse engineering slower. Do not rely on obfuscation as a sole anti-piracy / DRM mechanism.

---

## 目录 · Contents

- [0. 威胁模型 · Threat model](#0-威胁模型--threat-model)
- [1. 控制流平坦化 · Control-Flow Flattening (CFF)](#1-控制流平坦化--control-flow-flattening-cff)
- [2. 异常控制流 · Exception-driven control flow](#2-异常控制流--exception-driven-control-flow)
- [3. 异常返回 · Exception-as-Return](#3-异常返回--exception-as-return)
- [4. 不透明谓词 · Opaque predicates](#4-不透明谓词--opaque-predicates)
- [5. 字符串加密 · String encryption](#5-字符串加密--string-encryption)
- [6. 数字加密 · Number encryption](#6-数字加密--number-encryption)
- [7. INVOKEDYNAMIC 调用重写 · InvokeDynamic call rewriting](#7-invokedynamic-调用重写--invokedynamic-call-rewriting)
- [8. Outliner · Function outlining](#8-outliner--function-outlining)
- [9. 栈操作混淆 · Stack obfuscation](#9-栈操作混淆--stack-obfuscation)
- [10. 高级 JVM 技巧 · Advanced JVM tricks](#10-高级-jvm-技巧--advanced-jvm-tricks)
- [11. 原生代码翻译 · Native translation](#11-原生代码翻译--native-translation)
- [12. 资源加密 · Resource encryption](#12-资源加密--resource-encryption)
- [13. 动态密钥派生 · Dynamic key derivation](#13-动态密钥派生--dynamic-key-derivation)
- [14. Pass 依赖与排序 · Pass dependencies & ordering](#14-pass-依赖与排序--pass-dependencies--ordering)
- [15. 已知抗性与局限 · Known resilience & limitations](#15-已知抗性与局限--known-resilience--limitations)

---

## 0. 威胁模型 · Threat model

| 级别 · Tier | 中文 | English |
|---|---|---|
| T1 | 随手 `javap`、IDE 反编译：强力防御。 | Casual `javap` / IDE decompile: **strong defence**. |
| T2 | 成熟反编译器（CFR / Procyon / Vineflower）+ 人工阅读：**显著增加成本**。 | Mature decompilers (CFR / Procyon / Vineflower) + manual reading: **significantly costly**. |
| T3 | 专门反混淆工具（Deobfuscator、Krakatau 脚本、自定义 bytecode pattern matcher）：**部分抗性**。 | Dedicated deobfuscation tools (Deobfuscator, Krakatau scripts, custom pattern matchers): **partial resilience**. |
| T4 | 动态分析 / 插桩 / Frida / Instrument agent：**无抗性**，请配合完整性检查与原生代码。 | Dynamic instrumentation / Frida / Java agents: **no resilience**; combine with integrity checks and native code. |
| T5 | 有针对性的专业团队、长期投入：**无抗性**，请考虑技术之外的手段。 | Targeted professional team, long-term effort: **no resilience**; consider non-technical measures. |

> NekoObfuscator 的设计目标是稳定地对 **T1-T2** 提供强防御，对 **T3** 提供摩擦，不声称 **T4-T5** 的防御。
> NekoObfuscator targets solid defence against **T1-T2**, added friction against **T3**, and explicitly does **not** claim defence against **T4-T5**.

---

## 1. 控制流平坦化 · Control-Flow Flattening (CFF)

**Pass ID**：`controlFlowFlattening` · `neko-transforms/flow/ControlFlowFlatteningPass`

### 思路 · Idea

| 中文 | English |
|---|---|
| 把方法体拆成若干基本块，用一个 `state` 局部变量 + `switch` 作为 **分发器 (dispatcher)**。原本 `A → B → C` 的线性控制流被改写成：「初始 state = `s_A`；`dispatcher` 按 state 跳到块；每块末尾更新 state 再 `goto dispatcher`。」 | Split the method into basic blocks; drive execution with a `state` local + `switch` **dispatcher**. The original `A → B → C` becomes: "start with `state = s_A`; dispatcher switches on state to the target block; each block updates `state` and `goto` back to the dispatcher." |
| 进一步引入 **ZKM 风格尾链 (tail chains)**：多个块被链式串联，中间插入不透明谓词、假分支、状态扰动，让反编译器无法恢复出 "结构化" 控制流。 | With **ZKM-style tail chains** multiple blocks are chained together with opaque predicates, fake branches and state perturbations, preventing decompilers from recovering a "structured" CFG. |
| **入口点增强**：`main`、`<clinit>`、公开 API 的方法拥有额外加权（更多块数、更多 try-catch bonus、更长尾链）。 | **Entry-point boosting**: `main`, `<clinit>` and public APIs receive extra weight (more blocks, higher try-catch bonus, longer tail chains). |
| **try-catch 加权**：若方法包含 try-catch，则按 `tryCatchTailChainMultiplier` 调整尾链强度，避免破坏异常语义。 | **Try-catch weighting**: if the method contains try-catch, `tryCatchTailChainMultiplier` scales the chain density to preserve exception semantics. |

### 关键配置 · Key knobs

| YAML 字段 · YAML key | 作用 · Effect |
|---|---|
| `intensity` (0.0–1.0) | 总体强度；影响块切分粒度与尾链长度 · overall intensity; affects block splitting and chain length |
| `zkmStyle` | 启用 ZKM 风格尾链与状态扰动 · enables ZKM-style chains & perturbations |
| `tailChainIntensity` | 尾链生成概率 · tail-chain generation probability |
| `tryCatchTailChainMultiplier` | 在 try-catch 方法上的尾链乘数 · multiplier for try-catch methods |
| `entrypointTailChainMultiplier` | 在入口点方法上的尾链乘数（通常 `< 1` 以避免过慢） · multiplier for entry-point methods |
| `maxApplicableInstructionCount` | 方法指令数上限，超过则跳过 · skip methods larger than this |
| `maxBranchCount` / `maxBackwardBranches` | 分支/回跳上限 · branch / back-edge ceilings |
| `allowTryCatchMethods` / `allowSwitchMethods` / `allowMonitorMethods` | 是否允许进入特定方法 · permit entering specific method shapes |

### 安全 / 正确性考虑 · Safety considerations

| 中文 | English |
|---|---|
| 同步块 (`monitorenter`/`monitorexit`) 必须成对出现，平坦化会破坏这一属性；默认 `allowMonitorMethods: false`。 | `monitorenter` / `monitorexit` must be balanced; flattening can break this, so `allowMonitorMethods: false` by default. |
| 包含反向跳转（循环）的方法需要显式 allow；过多回跳会让调度器膨胀。 | Methods with back-edges (loops) need explicit allow; too many back-edges blow up the dispatcher. |
| try-catch 覆盖范围被块切分重排，Pipeline 的后处理会丢弃被压缩为空的 handler。 | Try-catch ranges are rearranged; the pipeline's post-pass drops handlers whose range collapsed. |

### 抗性与成本 · Resilience & cost

- **抗静态分析**：非常强；反编译器通常输出一堆 `switch(state)` 块。
  **Static resilience**: very strong; decompilers spit out `switch(state)` soup.
- **抗动态分析**：中等；dispatcher 一旦被识别，脚本可以追踪 state 变量。
  **Dynamic resilience**: moderate; once the dispatcher is identified, scripts can follow the `state` variable.
- **运行时成本**：每方法 +20% ~ +300%（视 intensity），主要来自 dispatcher 分发。
  **Runtime cost**: +20% … +300% per method depending on intensity, mostly dispatcher overhead.

---

## 2. 异常控制流 · Exception-driven control flow

**Pass ID**：`exceptionObfuscation` · `neko-transforms/flow/ExceptionObfuscationPass`

### 思路 · Idea

| 中文 | English |
|---|---|
| 将部分 `GOTO` 改写为 `throw new NekoFlowException(targetId)` + 外层 `try { … } catch (NekoFlowException e) { switch (e.id) { … } }`。 | Rewrite selected `GOTO`s as `throw new NekoFlowException(targetId)` wrapped by `try { … } catch (NekoFlowException e) { switch (e.id) { … } }`. |
| `NekoFlowException` 的 `fillInStackTrace` 被重写为空，保证抛出成本极低。 | `NekoFlowException#fillInStackTrace` is overridden to a no-op, keeping throw cost low. |
| 反编译器很难把这个模式还原成 `goto`，通常会留下显眼的 `try`/`catch`/`switch` 结构。 | Decompilers struggle to revert this to a `goto`, usually leaving a prominent `try`/`catch`/`switch`. |

### 关键配置 · Key knobs

| YAML 字段 | 作用 |
|---|---|
| `flattenedIntensityMultiplier` | 与 CFF 同时启用时的强度折扣 · intensity discount when combined with CFF |
| `skipMethodsWithTryCatch` / `skipMethodsWithSwitches` / `skipMethodsWithMonitors` | 语义保护开关 · semantic-preservation guards |
| `skipNonVoidMethods` | 非 void 返回值的方法默认跳过 · skip non-void methods by default |
| `skipBackwardGotos` | 跳过反向 goto，避免循环异常开销 · skip back-edges to avoid loop overhead |
| `maxApplicableInstructionCount` / `maxEligibleGotos` | 阈值上限 · thresholds |

---

## 3. 异常返回 · Exception-as-Return

**Pass ID**：`exceptionReturn` · `neko-transforms/flow/ExceptionReturnPass`

- 把部分 `return` 语句替换为抛出携带返回值的异常，在方法入口 catch 后返回。
  Replaces selected `return`s with throws carrying the return value, caught at method entry to return.
- 与 `exceptionObfuscation` 互补；默认关闭，用于极端强度配置。
  Complements `exceptionObfuscation`; off by default, used only in extreme intensity configs.

---

## 4. 不透明谓词 · Opaque predicates

**Pass ID**：`opaquePredicates` · `neko-transforms/flow/OpaquePredicateGenerator`

恒真 / 恒假但难以静态求解的条件表达式 · Constant-valued conditions that are hard to evaluate statically:

| 变体 · Variant | 表达式样例 · Example | 说明 · Note |
|---|---|---|
| Arithmetic | `(x*x + x) % 2 == 0` | 整数 parity 性质，恒真 · parity identity, always true |
| Array-length | `arr.length >= 0` | JVM 保证数组长度非负 · JVM guarantees non-negative length |
| HashCode | `"literal".hashCode() == <precomputed>` | 编译时预计算，运行时恒真 · computed at build time |
| Thread | `Thread.currentThread() != null` | 恒真但引入外部依赖，难以内联分析 · always true, but pulls in an external dep that is hard to inline |

不透明谓词用作 CFF dispatcher 的守卫、假分支条件、死代码入口条件等。
Opaque predicates act as dispatcher guards, fake-branch conditions, and dead-code entry conditions.

---

## 5. 字符串加密 · String encryption

**Pass ID**：`stringEncryption` · `neko-transforms/data/StringEncryptionPass`

### 思路 · Idea

```
原始 · Original                  混淆后 · After pass
────────────────                 ───────────────────────────────────────────
LDC "Hello"                      INVOKEDYNAMIC  bsmString(fieldIdx, nameHash,
                                                           descHash, insnSalt,
                                                           flowMode)
                                 ↓
                                 NekoBootstrap.bsmString(Lookup, name, type,
                                                         int fieldIdx,
                                                         int methodNameHash,
                                                         int methodDescHash,
                                                         int insnSalt,
                                                         int flowMode)
                                 ↓
                                 读取合成字段 __e<fieldIdx> (encrypted bytes)
                                 → NekoKeyDerivation.derive(CLASS, METHOD,
                                                             INSN, FLOW)
                                 → NekoStringDecryptor.decrypt(bytes, key)
                                 → CallSite 返回明文字符串
```

### Bootstrap 签名 · Bootstrap signature

```java
public static CallSite bsmString(
        MethodHandles.Lookup lookup,
        String               name,
        MethodType           type,
        int                  fieldIdx,
        int                  methodNameHash,
        int                  methodDescHash,
        int                  insnSalt,
        int                  flowMode);
```

### 设计要点 · Design notes

| 中文 | English |
|---|---|
| 加密后的字节以合成字段 `__e<n>` 存储在 **所在类** 上，按 `fieldIdx` 索引，减少字段数量膨胀。 | Ciphertext bytes live as synthetic fields `__e<n>` on the **owning class**, indexed by `fieldIdx` to limit field-count growth. |
| `methodNameHash` / `methodDescHash` 绑定了调用点的方法身份，类/方法被重命名后密钥派生会失败 —— 提高反混淆脚本的工作量。 | `methodNameHash` / `methodDescHash` tie the callsite to the method identity; if a tool renames the enclosing method the key derivation fails, raising deobfuscator work. |
| `insnSalt` 随机化同一方法内多个字符串的密钥。 | `insnSalt` randomises keys across strings in the same method. |
| `flowMode` 与 `NekoKeyDerivation` 的 `FLOW_KEY` 挂钩，支持未来对不同流模式下的字符串做不同解密策略。 | `flowMode` feeds into `FLOW_KEY`, enabling future per-flow-mode decryption strategies. |
| 首次调用后 BSM 返回 **ConstantCallSite**，JIT 可以把解密结果当作常量内联；无持续运行时成本。 | The BSM returns a **ConstantCallSite** so the JIT can inline the decrypted string as a constant; no per-call cost after warmup. |

### 风险 · Risks

- 动态分析可以直接 hook `NekoStringDecryptor.decrypt` 获取明文批量导出。
  Dynamic analysis can hook `NekoStringDecryptor.decrypt` and dump every plaintext.
- BSM 参数表较长，显著增加常量池大小。
  The BSM's parameter list noticeably grows the constant pool.

---

## 6. 数字加密 · Number encryption

**Pass ID**：`numberEncryption` · `neko-transforms/data/NumberEncryptionPass`

- 把常量 `ICONST_*` / `LDC` 常量改成 `XOR` / `ADD` / `MUL` 等简单算术链，最终等价于原值。
  Rewrites constant `ICONST_*` / `LDC` into simple XOR / ADD / MUL chains that evaluate to the original value.
- 内部循环中的小常量默认跳过 (`skipSmallLoopConstants`, `maxPlainLoopConstant: 16`)，避免破坏 JIT 热点优化。
  Small constants inside tight loops are skipped (`skipSmallLoopConstants`, `maxPlainLoopConstant: 16`) to preserve JIT hotness.
- 对 `Math.*` / `Thread.sleep(long)` 等敏感 API 参数保留明文 (`skipSensitiveApiMethods`)。
  Parameters of sensitive APIs (`Math.*`, `Thread.sleep(long)`, …) stay plain (`skipSensitiveApiMethods`).

---

## 7. INVOKEDYNAMIC 调用重写 · InvokeDynamic call rewriting

**Pass ID**：`invokeDynamic` · `neko-transforms/invoke/InvokeDynamicPass`

- 把普通 `INVOKEVIRTUAL` / `INVOKESTATIC` / `INVOKEINTERFACE` 改写为 `INVOKEDYNAMIC` + `NekoBootstrap.bsmInvoke(...)`。
  Replaces regular `INVOKE*` instructions with `INVOKEDYNAMIC` + `NekoBootstrap.bsmInvoke(...)`.
- BSM 根据构建期传入的哈希参数，在运行时用 `MethodHandles.Lookup` 还原真实方法。
  The BSM reconstructs the real method with `MethodHandles.Lookup` using build-time hash parameters.
- 效果：调用图不再能直接读出 target owner/method/desc —— 需要执行 BSM 才能得到完整调用关系。
  Effect: the call graph no longer exposes target owner/method/desc directly; running the BSM is required.
- 与 CFF/异常混淆兼容；对带有 try-catch、switch、monitor 的方法默认跳过以保证正确性。
  Compatible with CFF / exception obfuscation; methods with try-catch / switch / monitor are skipped by default for correctness.

---

## 8. Outliner · Function outlining

**Pass ID**：`outliner` · `neko-transforms/structure/OutlinerPass`

- 把多次出现的指令序列 **抽出来** 成为一个新的合成私有方法（与函数内联相反）。
  **Lifts** repeated instruction sequences into a new synthetic private method (the opposite of inlining).
- 增加跨方法阅读难度，并为其他 Pass（例如 CFF）提供更多调用点。
  Raises cross-method reading cost and exposes more call sites for other passes (e.g. CFF).
- 默认强度保守，过度 outlining 会恶化 JIT 内联并膨胀方法数。
  Conservatively-tuned by default; over-outlining hurts JIT inlining and inflates the method count.

---

## 9. 栈操作混淆 · Stack obfuscation

**Pass ID**：`stackObfuscation` · `neko-transforms/structure/StackObfuscationPass`

- 在变量加载/存储附近插入 `DUP` / `POP` / `SWAP` / `DUP2` 等冗余栈操作，同时保持最终栈状态不变。
  Inserts redundant `DUP` / `POP` / `SWAP` / `DUP2` around variable loads/stores while preserving final stack state.
- 扰动 JVM 栈状态，阻挠基于简单数据流的反编译。
  Perturbs JVM stack state, frustrating simple data-flow-based decompilation.
- 强度受 `intensity` 控制；`ClassWriter.COMPUTE_FRAMES` 会重新计算 StackMap，避免校验失败。
  Intensity-gated; `ClassWriter.COMPUTE_FRAMES` recomputes StackMap to avoid verifier failures.

---

## 10. 高级 JVM 技巧 · Advanced JVM tricks

**Pass ID**：`advancedJvm` · `neko-transforms/advanced/AdvancedJvmPass`

| 技巧 · Trick | 中文 | English |
|---|---|---|
| Dead code insertion | 插入可到达但无副作用的指令块，让反编译器把它们当作真实逻辑 | Inserts reachable but side-effect-free blocks that decompilers render as real logic |
| Overlapping exception handlers | 让多个 handler 的保护范围重叠 / 边界微调 1 字节，触发反编译器 bug | Makes multiple handler ranges overlap / shifts boundaries by one byte, tripping decompiler bugs |
| Fake source file names | 覆盖 `SourceFile` 属性为像 `😺.java` 的名称 | Replaces the `SourceFile` attribute with names like `😺.java` |
| LVT scrambling | 本地变量表使用非法 / Unicode / 同名变量，误导 IDE / 反编译 | Rewrites the local-variable table with invalid / Unicode / duplicate names to mislead IDEs |

**依赖 · Dependencies**：`controlFlowFlattening`, `stringEncryption`, `invokeDynamic`, `outliner`, `stackObfuscation` —— 这保证高级 Pass 跑在已经重塑过的字节码上，避免它自身的插入被后续 Pass "抹平"。
**Dependencies**: `controlFlowFlattening`, `stringEncryption`, `invokeDynamic`, `outliner`, `stackObfuscation` — this ensures the advanced tricks run on already-reshaped bytecode so later passes do not flatten them away.

---

## 11. 原生代码翻译 · Native translation

**模块 · Module**：`neko-native`（`NativeTranslator`, `CCodeGenerator`, `NativeBuildEngine`, `OpcodeTranslator`）

### 流水线 · Flow

```
L1 method marked @NativeTranslate
        │
        ▼
 L1 → L2 lift (CFG + SSA)         ← 现阶段只在分析时使用 / currently analysis-only
        │
        ▼
 L2 → L3 lowering (C-IR)
        │
        ▼
 CCodeGenerator → .c / .h + mangled JNI names
        │
        ▼
 NativeBuildEngine (Zig cc <target triple>)
        │
        ▼
 Shared library: libNekoNative_<hash>.so / NekoNative_<hash>.dll
        │
        ▼
 Embedded into output JAR under /native/<target>/
        │
        ▼
 NekoNativeLoader.load() at runtime
```

### 支持的目标 · Supported targets

| 目标 · Target | Triple (Zig) | 备注 · Notes |
|---|---|---|
| `LINUX_X64` | `x86_64-linux-gnu` | Glibc compatible |
| `WINDOWS_X64` | `x86_64-windows-gnu` | MinGW ABI |

### 覆盖范围 · Coverage

| 中文 | English |
|---|---|
| 当前 `OpcodeTranslator` 覆盖了多数 **arithmetic / load-store / branch / invoke / return** opcode，尚未完全支持 `invokedynamic`、复杂 monitor、复杂异常跳跃。 | Current `OpcodeTranslator` covers most **arithmetic / load-store / branch / invoke / return** opcodes; `invokedynamic`, complex monitors, and elaborate exception flows are not fully supported yet. |
| 被翻译的方法在 Java 端保留一个 `native` 存根，调用时跳转到 JNI 入口。 | A `native` stub remains on the Java side; calls dispatch to the JNI entry. |
| 对关键小方法启用原生翻译可以让常规反编译器 "丢失轨迹"。 | Translating small critical methods natively makes typical decompilers "lose the trail". |

### 风险 · Risks

- **多平台发布**：需要为每个支持平台产出一份原生库，否则运行时加载失败。
  **Multi-platform release**: you must ship one native library per supported platform or loading fails at runtime.
- **动态调试**：原生代码对 `gdb` / `lldb` / IDA 是透明的；需要额外加壳。
  **Dynamic debug**: native code is fully open to `gdb` / `lldb` / IDA; additional packing required.
- **尺寸成本**：每个原生 target 会显著增加输出 JAR 大小。
  **Size cost**: each native target measurably inflates the output JAR.

---

## 12. 资源加密 · Resource encryption

**模块 · Module**：`neko-native/.../resource/ResourceEncryptor` + `neko-runtime/NekoResourceLoader`

- 默认算法 **AES-256-GCM**（可在 `NativeConfig.encryptionAlgorithm` 配置）。
  Default algorithm: **AES-256-GCM** (configurable via `NativeConfig.encryptionAlgorithm`).
- 会选择性地加密 `resources/` 下的非 `.class` 资源（图片、配置、本地化文件）。
  Selectively encrypts non-class resources under `resources/` (images, configs, localisation files).
- 运行时在 `NekoResourceLoader.open(resourcePath)` 中按需解密并返回 `InputStream`。
  At runtime `NekoResourceLoader.open(resourcePath)` decrypts on demand and returns an `InputStream`.
- 密钥派生共用 `NekoKeyDerivation`，与字符串加密共享 `MASTER_SEED`。
  Key derivation shares `NekoKeyDerivation` and the same `MASTER_SEED` as string encryption.

---

## 13. 动态密钥派生 · Dynamic key derivation

**模块 · Module**：`neko-transforms/key/DynamicKeyDerivationEngine`（构建期） + `neko-runtime/NekoKeyDerivation`（运行期）

- **构建期** 计算每次插桩需要的编码参数（`methodNameHash`, `insnSalt`, `flowMode` 等）。
  **Build-time** computes the encoding parameters used by each instrumentation site (`methodNameHash`, `insnSalt`, `flowMode`, …).
- **运行期** 使用相同算法在首次调用时派生解密密钥；派生结果缓存于 `NekoContext`。
  **Runtime** re-derives the matching keys on first use; results are cached in `NekoContext`.
- **混合函数** 与 [`ARCHITECTURE §7`](./ARCHITECTURE.md#7-密钥模型--key-model) 一致：SipHash 风格 + MurmurHash3 `fmix64`。
  **Mix function** matches [`ARCHITECTURE §7`](./ARCHITECTURE.md#7-密钥模型--key-model): SipHash-style + MurmurHash3 `fmix64`.
- **分层**：`CLASS → METHOD → INSTRUCTION → CONTROL_FLOW`，可在 `KeyConfig.layers` 中裁剪。
  **Layering**: `CLASS → METHOD → INSTRUCTION → CONTROL_FLOW`, trimmable via `KeyConfig.layers`.

---

## 14. Pass 依赖与排序 · Pass dependencies & ordering

```
                   opaquePredicates
                          │
      ┌───────────────────┼────────────────────────────────┐
      ▼                   ▼                                ▼
controlFlowFlattening  stringEncryption           numberEncryption
      │                   │                                │
      ▼                   ▼                                ▼
exceptionObfuscation    invokeDynamic                 stackObfuscation
      │                   │                                │
      └──────┬────────────┼──────────────┬─────────────────┘
             ▼            ▼              ▼
                       outliner
                          │
                          ▼
                      advancedJvm        ← final L1 touch-up
                          │
                          ▼
              (post-pass cleanup + runtime injection)
                          │
                          ▼
                 [optional] native translation pass
```

由 `PassScheduler` 结合 `@PassDependency.dependsOn / conflictsWith` 与 `TransformPhase` 计算拓扑序。
`PassScheduler` composes the topological order from `@PassDependency.dependsOn / conflictsWith` plus `TransformPhase`.

---

## 15. 已知抗性与局限 · Known resilience & limitations

| 领域 · Area | 现状 · Status |
|---|---|
| 反编译 (CFR / Procyon / Vineflower) | 对 **T1-T2** 有明显摩擦；典型反编译输出为嵌套 `switch`-tree。 · Noticeable friction against **T1-T2**; typical output is a nested `switch` tree. |
| JD-GUI / Luyten | 常见出现 **解析失败**；被迫 fall back 到反汇编。 · Frequently triggers **parse failures**, forcing fallback to disassembly. |
| 通用反混淆工具 | **部分抗性**；多数 CFF 模式可被识别，但字符串 / 数字 / invokeDynamic 需要逐一脚本化。 · **Partial resilience**; most CFF patterns can be recognised, but strings / numbers / invokeDynamic need per-pattern scripting. |
| 动态插桩 (Java Agent / Frida) | **无抗性**：请在关键路径使用原生翻译或完整性检查。 · **No resilience**: combine with native translation or integrity checks. |
| JIT 性能 | 中等影响：CFF 与异常混淆最贵；字符串 indy 在 warmup 后接近零开销。 · Moderate impact: CFF and exception obfuscation are the most expensive; string indy approaches zero cost after warmup. |
| 启动时间 | 额外 `MethodHandle.Lookup` / BSM 调用会增加首次调用延迟。 · Additional `MethodHandle.Lookup` / BSM invocations add first-call latency. |
| 可调试性 | 被混淆后的堆栈几乎不可读；建议保留一份未混淆构建用于故障分析。 · Obfuscated stack traces are nearly unreadable; keep an un-obfuscated build for incident analysis. |

---

<div align="center">

_🔐 Obfuscation buys time — not safety. · 混淆买的是时间，不是安全。_

</div>
