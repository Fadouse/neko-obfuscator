# 配置速查 · Configuration Reference

> **双语文档 · Bilingual document**
> 中文在前，英文在后。字段名、类型与默认值对两种语言都有效。
> Chinese first, English second. Field names, types and defaults are identical in both.

> ⚠️ **项目状态 · Project Status**：本项目仍处于**早期阶段 (Early Stage / Work in Progress)**，配置格式随时可能变更。
> This project is in its **early stage / work-in-progress** phase; the configuration format may change at any time.

> 🤖 **关于作者 · About the Authors**
> 本文档由 **Anthropic Claude Opus 4.6 / 4.7** 与 **OpenAI GPT 5.4** 在
> **opencode + ohmyopenagent (ohmyopencode)** 环境下协作撰写。
> Authored by **Anthropic Claude Opus 4.6 / 4.7** and **OpenAI GPT 5.4**
> under the **opencode + ohmyopenagent (ohmyopencode)** environment.

---

## 目录 · Table of Contents

- [1. 顶层结构 · Top-level Structure](#1-顶层结构--top-level-structure)
- [2. 预设 · Presets](#2-预设--presets)
- [3. Transform 通用字段 · Common Transform Fields](#3-transform-通用字段--common-transform-fields)
- [4. 每个 Transform 的选项 · Per-Transform Options](#4-每个-transform-的选项--per-transform-options)
  - [4.1 `controlFlowFlattening`](#41-controlflowflattening)
  - [4.2 `exceptionObfuscation`](#42-exceptionobfuscation)
  - [4.3 `exceptionReturn`](#43-exceptionreturn)
  - [4.4 `controlFlowObfuscation`](#44-controlflowobfuscation)
  - [4.5 `opaquePredicates`](#45-opaquepredicates)
  - [4.6 `stringEncryption`](#46-stringencryption)
  - [4.7 `numberEncryption`](#47-numberencryption)
  - [4.8 `invokeDynamic`](#48-invokedynamic)
  - [4.9 `outliner`](#49-outliner)
  - [4.10 `stackObfuscation`](#410-stackobfuscation)
  - [4.11 `advancedJvm`](#411-advancedjvm)
- [5. `native` 节 · Native Section](#5-native-节--native-section)
- [6. `keys` 节 · Keys Section](#6-keys-节--keys-section)
- [7. `rules` 节 · Rules Section](#7-rules-节--rules-section)
- [8. 字段总览 · Field Summary](#8-字段总览--field-summary)
- [9. 命令行覆盖 · CLI Overrides](#9-命令行覆盖--cli-overrides)
- [10. 完整示例 · Full Examples](#10-完整示例--full-examples)

---

## 1. 顶层结构 · Top-level Structure

```yaml
version: 1                       # 约定字段，目前仅作元信息 · Convention only; currently informational
input: path/to/input.jar         # 可被 --input 覆盖 · Can be overridden by --input
output: path/to/output.jar       # 可被 --output 覆盖 · Can be overridden by --output
classpath:                       # 额外类路径；用于类层级分析 · Additional classpath for hierarchy analysis
  - libs/some-dependency.jar
preset: STANDARD                 # LIGHT | STANDARD | AGGRESSIVE | PARANOID
transforms: { ... }              # 每个 Pass 的开关与参数 · Per-pass switches and parameters
native:    { ... }               # 原生翻译与资源加密 · Native translation & resource encryption
keys:      { ... }               # 密钥派生 · Key derivation
rules:     [ ... ]               # 按类名/方法名覆写 · Class/method-specific overrides
```

**字段解析关键点 · Parsing notes**

| 字段 · Field | 是否必填 · Required | 说明 · Notes |
|---|---|---|
| `version` | 否 · no | 解析器忽略；保留用于未来兼容性。 · Ignored by the parser; reserved for future compatibility. |
| `input` / `output` | 至少由 YAML 或 CLI 提供之一 · at least one source (YAML or CLI) | CLI 的 `--input` / `--output` 会覆盖 YAML。 · `--input` / `--output` override the YAML values. |
| `classpath` | 否 · no | 若缺少运行时依赖导致类层级解析不完全，请在这里补齐。 · Provide missing runtime deps here if class hierarchy resolution is incomplete. |
| `preset` | 否 · no | 默认 `STANDARD`；用于填充未显式声明的 transform。 · Defaults to `STANDARD`; fills in transforms not explicitly declared. |
| `transforms` | 否 · no | 显式 transform 块覆盖预设默认值。 · Explicit transform entries override preset defaults. |

> ⚠️ 未在 `transforms` 里声明的 transform **会按预设默认值自动启用**；若要强制关闭，必须显式写 `enabled: false`。
> ⚠️ Transforms absent from `transforms` are **auto-enabled using preset defaults**; set `enabled: false` explicitly to force-disable.

---

## 2. 预设 · Presets

预设是一套 `(transform → TransformConfig)` 的默认映射，在 YAML 解析最后阶段被
`PresetResolver.applyDefaults()` 填充到 **尚未显式配置** 的 transform 上。
Presets are default `(transform → TransformConfig)` maps applied by
`PresetResolver.applyDefaults()` at the end of YAML parsing, filling in **any transform not explicitly configured**.

| Transform ID | LIGHT | STANDARD | AGGRESSIVE | PARANOID |
|---|---|---|---|---|
| `stringEncryption`       | 1.0 | 1.0 | 1.0 | 1.0 |
| `numberEncryption`       | 1.0 | 1.0 | 1.0 | 1.0 |
| `controlFlowFlattening`  | — | 0.6 | 0.8 | 1.0 |
| `opaquePredicates`       | — | 0.6 | 0.8 | 1.0 |
| `invokeDynamic`          | — | 0.6 | 0.8 | 1.0 |
| `exceptionObfuscation`   | — | — | 0.6 | 0.8 |
| `exceptionReturn`        | — | — | 0.6 | 0.8 |
| `outliner`               | — | — | 0.5 | 0.8 |
| `stackObfuscation`       | — | — | 0.5 | 1.0 |
| `advancedJvm`            | — | — | — | 1.0 |

> “—” 表示该预设未启用该 transform（不会自动启用；但你仍可显式开启）。
> “—” means the preset does not enable this transform; you can still enable it explicitly.

选择建议 · Rule of thumb:

- **LIGHT** — 仅字符串/数字加密，调试友好。 · String/number encryption only, debug-friendly.
- **STANDARD** — 常规强度，生产可用。 · Regular strength, production-ready.
- **AGGRESSIVE** — 启用异常控制流与结构变换；调试/异常栈变难。 · Enables exception-driven CF + structural passes; stack traces become less readable.
- **PARANOID** — 开启一切，体积/速度代价大。 · Everything on, large size/performance cost.

---

## 3. Transform 通用字段 · Common Transform Fields

每个 transform 项都是一个 mapping，最少接受以下三个字段 · Every transform entry is a mapping accepting at least these three fields:

```yaml
transforms:
  <transformId>:
    enabled:   <true|false>        # 开关 · switch
    intensity: <0.0..1.0>          # 强度 (0=最轻 / 1=最重) · intensity (0=lightest / 1=heaviest)
    # 其它字段均视为 transform-specific 选项，塞进 TransformConfig.options
    # All other keys go into TransformConfig.options as transform-specific options
```

**简写 · Shorthands**

```yaml
transforms:
  stringEncryption: true           # 等价于 { enabled: true, intensity: 1.0 }
  outliner: false                  # 等价于 { enabled: false, intensity: 1.0 }
```

**校验 · Validation** (`ConfigValidator`)

- `intensity` 必须在 `[0.0, 1.0]` 区间，否则报错。 · `intensity` must be in `[0.0, 1.0]`, otherwise a validation error is produced.
- `input` 必须存在且指向真实文件。 · `input` must exist and point to a real file.
- `output` 必填。 · `output` is required.
- 若 `native.enabled: true` 则 `native.targets` 不能为空。 · If `native.enabled: true`, `native.targets` must be non-empty.

---

## 4. 每个 Transform 的选项 · Per-Transform Options

### 4.1 `controlFlowFlattening`

将方法控制流平坦化为 switch-dispatched 状态机；支持 ZKM 风格尾链与 try-catch 加权。
Flattens method control flow into a switch-dispatched state machine; supports ZKM-style tail chains and try-catch weighting.

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | 见预设 · per preset | 总开关 · Master switch |
| `intensity` | double | 见预设 · per preset | 每方法应用概率基线 · Baseline application probability per method |
| `zkmStyle` | bool | `false` | 启用 ZKM 风格尾链分叉 · Enable ZKM-style tail chain branching |
| `tailChainIntensity` | double | `0.85` | 尾链生成概率 · Tail-chain generation probability |
| `tryCatchTailChainMultiplier` | double | `0.35` | 在含 try-catch 的方法里尾链的系数 · Multiplier for tail chains inside try-catch methods |
| `allowTryCatchMethods` | bool | `true` | 是否允许处理含 try-catch 的方法 · Whether to flatten methods with try-catch |
| `tryCatchMainOnly` | bool | `true` | 仅对 `main`-like 入口里的 try-catch 生效 · Only applies inside `main`-style entry methods |
| `maxTryCatchBlocks` | int | `18` | 非入口方法中的 try-catch 数量上限 · Max try-catch blocks in non-entry methods |
| `tryCatchBranchBonus` | int | `2` | 含 try-catch 方法额外加权分支数 · Extra branch weight for try-catch methods |
| `tryCatchInstructionBonus` | int | `160` | 含 try-catch 方法额外加权指令数 · Extra instruction weight for try-catch methods |
| `entrypointTailChainMultiplier` | double | `0.08` | 入口方法的尾链系数 · Tail-chain multiplier for entry methods |
| `entrypointMaxTryCatchBlocks` | int | `64` | 入口方法的 try-catch 上限 · Entry-method try-catch cap |
| `entrypointBranchBonus` | int | `96` | 入口方法的分支加权 · Entry-method branch bonus |
| `entrypointInstructionBonus` | int | `640` | 入口方法的指令加权 · Entry-method instruction bonus |
| `allowSwitchMethods` | bool | `false` | 是否处理已含 `tableswitch/lookupswitch` 的方法 · Whether to flatten methods already containing switch |
| `allowMonitorMethods` | bool | `false` | 是否处理含 `monitorenter/exit` 的方法 · Whether to flatten methods with monitors |
| `maxApplicableInstructionCount` | int | `180` | 单方法指令数阈值 · Per-method instruction-count threshold |
| `maxBackwardBranches` | int | `2` | 最大回边数 · Max backward branches |
| `maxBranchCount` | int | `16` | 单方法分支数阈值 · Per-method branch-count threshold |

调优建议 · Tuning tips:

- 对 "main-like" 入口方法开启 `entrypoint*` 一组参数能显著增强入口可见性对抗。 · Enabling `entrypoint*` parameters on "main-like" entry methods significantly raises analysis cost at the visible entry points.
- 若目标方法较大或含复杂 try-catch，降低 `intensity` 或调高 `maxApplicableInstructionCount`。 · For larger / try-catch-heavy methods, drop `intensity` or raise `maxApplicableInstructionCount`.

### 4.2 `exceptionObfuscation`

用 `NekoFlowException` 改写正常控制流为异常跳转。
Rewrites normal control flow into exception-driven jumps using `NekoFlowException`.

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | 见预设 · per preset | — |
| `intensity` | double | 见预设 · per preset | — |
| `flattenedIntensityMultiplier` | double | `0.55` | 已平坦化方法的强度系数 · Intensity multiplier for already-flattened methods |
| `skipFlattenedMethods` | bool | `false` | 跳过已平坦化方法 · Skip already-flattened methods |
| `skipMethodsWithTryCatch` | bool | `true` | 跳过已有 try-catch 的方法 · Skip methods that already have try-catch |
| `skipMethodsWithSwitches` | bool | `true` | 跳过含 switch 的方法 · Skip methods with switches |
| `skipMethodsWithMonitors` | bool | `true` | 跳过含 monitor 的方法 · Skip methods with monitors |
| `skipNonVoidMethods` | bool | `true` | 跳过非 `void` 方法 · Skip non-`void` methods |
| `skipBackwardGotos` | bool | `true` | 跳过带回跳的方法 · Skip methods with backward gotos |
| `maxApplicableInstructionCount` | int | `260` | 指令数阈值 · Instruction-count threshold |
| `maxEligibleGotos` | int | `24` | 单方法可选 goto 数上限 · Max eligible gotos per method |

> ⚠️ 异常控制流会极大影响可读堆栈和异步分析，请务必保留一份未混淆输入以便调试。
> ⚠️ Exception-driven control flow heavily affects readable stack traces; always keep an unobfuscated copy for debugging.

### 4.3 `exceptionReturn`

将 `return` 改写为异常返回。默认预设里仅 AGGRESSIVE/PARANOID 启用。
Rewrites `return` as exception-based returns. Only enabled by AGGRESSIVE/PARANOID.

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | 见预设 · per preset | — |
| `intensity` | double | 见预设 · per preset | — |

目前无额外选项；若未来扩展，会列在此。
No additional options today; future knobs will be documented here.

### 4.4 `controlFlowObfuscation`

传统控制流扰动（非平坦化），作为 `controlFlowFlattening` 的轻量替代或补充。
Classic control-flow obfuscation (non-flattening), a lightweight alternative or complement to `controlFlowFlattening`.

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | `false` | 非预设默认；需显式开启 · Not on by default; opt in explicitly |
| `intensity` | double | `1.0` | 扰动强度 · Disturbance intensity |

### 4.5 `opaquePredicates`

在控制流中插入不透明谓词，包括算术、数组长度、`hashCode`、线程态四类生成器。
Injects opaque predicates: arithmetic, array-length, `hashCode`, and thread-based generators.

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | 见预设 · per preset | — |
| `intensity` | double | 见预设 · per preset | — |

### 4.6 `stringEncryption`

对所有 `LDC` 字符串加密，改为 `INVOKEDYNAMIC` + 合成字段 `__e<n>` + `NekoBootstrap.bsmString`。
Encrypts every `LDC` string and rewrites it to `INVOKEDYNAMIC` + synthetic field `__e<n>` + `NekoBootstrap.bsmString`.

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | 见预设 · per preset | — |
| `intensity` | double | 见预设 · per preset | 按类/方法应用概率 · Per-class/method application probability |

> 运行时开销：首次调用 `bsmString` 时做一次解密并缓存为 `ConstantCallSite`，之后命中 JIT 内联。
> Runtime cost: one-time decrypt on first `bsmString` invocation, then cached as a `ConstantCallSite` and JIT-inlined.

### 4.7 `numberEncryption`

用可逆运算与动态派生密钥替换数字常量。
Replaces numeric constants with reversible operations keyed by dynamic derivation.

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | 见预设 · per preset | — |
| `intensity` | double | 见预设 · per preset | — |
| `skipMethodsWithTryCatch` | bool | `true` | 跳过含 try-catch 的方法 · Skip try-catch methods |
| `skipMethodsWithSwitches` | bool | `true` | 跳过含 switch 的方法 · Skip switch methods |
| `skipMethodsWithMonitors` | bool | `true` | 跳过含 monitor 的方法 · Skip monitor methods |
| `skipSensitiveApiMethods` | bool | `true` | 跳过命中敏感 API 列表的方法 · Skip methods hitting the sensitive-API list |
| `skipSmallLoopConstants` | bool | `true` | 跳过循环中的小常量 · Skip small constants used in loops |
| `maxPlainLoopConstant` | int | `16` | “小常量”阈值 · "Small constant" threshold |
| `maxApplicableInstructionCount` | int | `220` | 指令数阈值 · Instruction-count threshold |
| `maxBranchCount` | int | `18` | 分支数阈值 · Branch-count threshold |

### 4.8 `invokeDynamic`

将普通方法调用（`INVOKESTATIC/VIRTUAL/INTERFACE`）重写为 `INVOKEDYNAMIC` + 动态 bootstrap。
Rewrites regular method calls (`INVOKESTATIC/VIRTUAL/INTERFACE`) to `INVOKEDYNAMIC` + dynamic bootstrap.

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | 见预设 · per preset | — |
| `intensity` | double | 见预设 · per preset | — |
| `skipMethodsWithTryCatch` | bool | `true` | 跳过含 try-catch 的方法 · Skip try-catch methods |
| `skipMethodsWithSwitches` | bool | `true` | 跳过含 switch 的方法 · Skip switch methods |
| `skipMethodsWithMonitors` | bool | `true` | 跳过含 monitor 的方法 · Skip monitor methods |
| `skipSensitiveApiMethods` | bool | `true` | 跳过敏感 API 调用 · Skip sensitive-API calls |
| `skipPrimitiveLoopCalls` | bool | `true` | 跳过纯基本类型循环里的调用 · Skip calls inside primitive-only loops |
| `maxApplicableInstructionCount` | int | `260` | 指令数阈值 · Instruction-count threshold |
| `maxBranchCount` | int | `24` | 分支数阈值 · Branch-count threshold |

### 4.9 `outliner`

把重复指令块外提到合成方法，减小体积并打散局部语义。
Outlines repeated instruction blocks into synthetic methods, shrinking bytecode and breaking local semantics.

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | 见预设 · per preset | — |
| `intensity` | double | 见预设 · per preset | — |

### 4.10 `stackObfuscation`

在 `xLOAD` / `xSTORE` 之前插入冗余的 `DUP/POP/SWAP` 模式。
Inserts redundant `DUP/POP/SWAP` patterns around `xLOAD` / `xSTORE`.

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | 见预设 · per preset | — |
| `intensity` | double | 见预设 · per preset | 受 intensity 节流的插入概率 · Insertion probability throttled by intensity |

### 4.11 `advancedJvm`

收尾的重量级变换集合：死代码、重叠异常处理器、假 `SourceFile`、局部变量表扰动等。
Heavy catch-all pass: dead code, overlapping exception handlers, fake `SourceFile`, LVT scrambling, etc.

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | 见预设 · per preset | — |
| `intensity` | double | 见预设 · per preset | — |

依赖 · Depends on: `controlFlowFlattening`, `stringEncryption`, `invokeDynamic`, `outliner`, `stackObfuscation`（建议全部启用后再开 `advancedJvm` · enable those first, then turn on `advancedJvm`）。

---

## 5. `native` 节 · Native Section

```yaml
native:
  enabled: false
  targets: [LINUX_X64, WINDOWS_X64]   # 默认值；另外还支持 LINUX_AARCH64 / MACOS_X64 / MACOS_AARCH64
  zigPath: zig                        # 要求 zig 可执行文件可被找到
  resourceEncryption: true            # 首选写法 · preferred YAML key
  encryptionAlgorithm: AES_256_GCM    # 目前仅实现 AES_256_GCM
  methods: ["**/*"]                  # 类级或 class#method 级 glob
  excludePatterns: []
  includeAnnotated: true
  skipOnError: true
  outputPrefix: neko_impl_
  obfuscateJniSlotDispatch: false
  cacheJniIds: false
```

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `enabled` | bool | `false` | 是否启用原生翻译 · Turn native translation on/off |
| `targets` | list<string> | `[LINUX_X64, WINDOWS_X64]` | 目标平台；启用后不能为空。当前支持 `LINUX_X64`、`LINUX_AARCH64`、`WINDOWS_X64`、`MACOS_X64`、`MACOS_AARCH64`。 · Target platforms; must be non-empty when `enabled: true`. Supported targets today: `LINUX_X64`, `LINUX_AARCH64`, `WINDOWS_X64`, `MACOS_X64`, `MACOS_AARCH64`. |
| `zigPath` | string | `zig` | Zig 可执行文件的路径或命令名。 · Path/command name of the Zig executable |
| `resourceEncryption` | bool | `true` | 是否对资源加密。 · Whether to encrypt embedded resources. |
| `encryptionAlgorithm` | string | `AES_256_GCM` | 资源加密算法（目前仅 `AES_256_GCM`）。 · Resource-encryption algorithm (only `AES_256_GCM` today). |
| `methods` | list<string> | `["**/*"]` | 选择要翻译的类/方法 glob；方法级写法使用 `classInternalName#methodName`。 · Class/method glob patterns for translation; method-level patterns use `classInternalName#methodName`. |
| `excludePatterns` | list<string> | `[]` | 从 pattern 选择中排除的类/方法 glob。 · Class/method glob patterns excluded from pattern-based selection. |
| `includeAnnotated` | bool | `true` | 是否把带 `@NativeTranslate` 的类/方法直接纳入翻译。 · Whether classes/methods annotated with `@NativeTranslate` are selected automatically. |
| `skipOnError` | bool | `true` | 原生翻译 / 编译失败时是否回退到纯 JVM 输出。 · Whether native-translation / compilation failures fall back to pure JVM output. |
| `outputPrefix` | string | `neko_impl_` | 生成的原生实现 / JNI 绑定前缀。 · Prefix used for generated native implementations / JNI bindings. |
| `obfuscateJniSlotDispatch` | bool | `false` | 是否对 JNI slot dispatch 生成额外扰动。 · Whether to add extra obfuscation to JNI slot dispatch generation. |
| `cacheJniIds` | bool | `false` | 是否缓存 JNI ID 查找结果。 · Whether to cache resolved JNI IDs. |

> 方法选择顺序 · Selection order：若 `includeAnnotated: true`，带 `@NativeTranslate` 的类/方法会被**直接选中**；否则再按 `excludePatterns` 与 `methods` 做 glob 匹配。运行时包 `dev/nekoobfuscator/runtime/` 下的类始终不会被翻译。
> Selection order: when `includeAnnotated: true`, classes/methods carrying `@NativeTranslate` are **selected immediately**; otherwise selection proceeds through `excludePatterns` and `methods` glob matching. Classes under `dev/nekoobfuscator/runtime/` are never translated.

> 兼容性说明 · Compatibility note：YAML 中首选使用 `resourceEncryption` / `encryptionAlgorithm`。旧写法 `native.resources.encrypt` / `native.resources.algorithm` 仍然被解析，以兼容已有配置。
> Prefer the top-level YAML keys `resourceEncryption` / `encryptionAlgorithm`. The legacy `native.resources.encrypt` / `native.resources.algorithm` block is still accepted for backwards compatibility.

> 产物命名 · Output naming：生成的原生库会作为 `/neko/native/libneko_<platform>_<arch>.<ext>` 资源打包进输出 JAR，并由 `NekoNativeLoader` 在运行时提取加载。
> Generated native libraries are packaged into the output JAR as `/neko/native/libneko_<platform>_<arch>.<ext>` resources and extracted by `NekoNativeLoader` at runtime.

---

## 6. `keys` 节 · Keys Section

```yaml
keys:
  masterSeed: auto                           # 或 12345678 · or a concrete long
  layers: [CLASS, METHOD, INSTRUCTION, CONTROL_FLOW]
  mixing: SIP_HASH
```

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `masterSeed` | long \| `auto` \| `null` | `0` (= auto) | `0`/`auto`/`null` 表示构建时随机生成；显式整数值可用于**可复现构建**。 · `0`/`auto`/`null` means "randomize at build time"; an explicit integer enables **reproducible builds**. |
| `layers` | list<string> | `[CLASS, METHOD, INSTRUCTION, CONTROL_FLOW]` | 派生层级顺序。 · Derivation layer order. |
| `mixing` | string | `SIP_HASH` | 混合算法；目前只实现 SipHash-style + MurmurHash3 `fmix64`。 · Mixing algorithm; only SipHash-style + MurmurHash3 `fmix64` is implemented. |

密钥链 · Key chain:

```
MASTER_SEED ─▶ CLASS_KEY ─▶ METHOD_KEY ─▶ INSTRUCTION_KEY ─▶ CONTROL_FLOW_KEY
             (mix64)      (mix64)        (mix64)           (mix64 + fmix64)
```

常量 · Constants:

- 黄金比例 · golden ratio: `0x9E3779B97F4A7C15`
- SipHash 风格乘子 · SipHash-style multiplier: `0xBF58476D1CE4E5B9`
- MurmurHash3 `fmix64` 常量 · MurmurHash3 `fmix64` constants:
  - `0xFF51AFD7ED558CCD`
  - `0xC4CEB9FE1A85EC53`

> ⚠️ **这不是一个密码学原语。** 密钥派生只是一个混淆层，不用于敏感数据保护；攻击者拿到 JAR 即可静态解密所有字符串/数字/调用点。
> ⚠️ **This is not a cryptographic primitive.** Key derivation is an obfuscation layer, not a data-protection mechanism; an attacker with the JAR can statically decrypt every string, number and call-site.

---

## 7. `rules` 节 · Rules Section

```yaml
rules:
  - match: "com.example.sensitive.**"
    exclude: false
    transforms:
      controlFlowFlattening: { enabled: true, intensity: 1.0 }
      stringEncryption:      { enabled: true, intensity: 1.0 }

  - match: "com.example.model.*"
    exclude: true                        # 完全跳过 · Skip completely
```

每条规则 · Each rule:

| 字段 · Field | 类型 · Type | 默认值 · Default | 说明 · Notes |
|---|---|---|---|
| `match` | string | — | glob pattern；支持 `**`（任意包深度）、`*`（单段）、`?`（单字符）。 · glob pattern; supports `**` (any package depth), `*` (single segment), `?` (single char). |
| `exclude` | bool | `false` | `true` 表示该规则下的类/方法完全不被处理。 · `true` means classes/methods matched are left untouched. |
| `transforms` | map | `{}` | 对匹配到的类/方法覆写 transform 配置（仅覆盖提到的字段）。 · Per-match transform overrides (only the listed fields are overridden). |

**匹配语法 · Pattern syntax** (`PatternMatcher`):

- `**` → 匹配任意包深度（可跨 `.`）· matches any package depth (including `.`).
- `*`  → 匹配单个段（不跨 `.`）· matches a single segment (no `.` crossing).
- `?`  → 单字符。 · single char.
- 支持类级 pattern，如 `com.example.**`；也支持方法级 `com.example.Foo.bar(...)`（当前实现仅匹配类 + 方法名，忽略描述符细节）。 · Supports class patterns (`com.example.**`) and method patterns (`com.example.Foo.bar(...)`; current impl matches class + method name, ignoring descriptor details).

---

## 8. 字段总览 · Field Summary

```yaml
# === 顶层 · Top-level =======================================================
version: 1                                 # informational
input:   <path>                            # or --input
output:  <path>                            # or --output
classpath: [<path>, ...]                   # optional
preset:   LIGHT | STANDARD | AGGRESSIVE | PARANOID   # default STANDARD

# === transform 列表 · Transform list =======================================
transforms:
  controlFlowFlattening:   { enabled, intensity, ...CFF options... }
  exceptionObfuscation:    { enabled, intensity, ...EO options... }
  exceptionReturn:         { enabled, intensity }
  controlFlowObfuscation:  { enabled, intensity }
  opaquePredicates:        { enabled, intensity }
  stringEncryption:        { enabled, intensity }
  numberEncryption:        { enabled, intensity, ...NE options... }
  invokeDynamic:           { enabled, intensity, ...ID options... }
  outliner:                { enabled, intensity }
  stackObfuscation:        { enabled, intensity }
  advancedJvm:             { enabled, intensity }

# === 原生翻译 · Native =====================================================
native:
  enabled:  false
  targets:  [LINUX_X64, WINDOWS_X64]
  zigPath:  zig
  resourceEncryption: true
  encryptionAlgorithm: AES_256_GCM
  methods:  ["**/*"]
  excludePatterns: []
  includeAnnotated: true
  skipOnError: true
  outputPrefix: neko_impl_
  obfuscateJniSlotDispatch: false
  cacheJniIds: false

# === 密钥 · Keys ===========================================================
keys:
  masterSeed: auto                         # or long
  layers:     [CLASS, METHOD, INSTRUCTION, CONTROL_FLOW]
  mixing:     SIP_HASH

# === 规则 · Rules ==========================================================
rules:
  - match:  "com.example.**"
    exclude: false
    transforms:
      controlFlowFlattening: { enabled: true, intensity: 0.8 }
```

---

## 9. 命令行覆盖 · CLI Overrides

`neko-cli` 支持 `obfuscate` 和 `info` 两个子命令：
`neko-cli` ships two subcommands, `obfuscate` and `info`:

```bash
# 混淆 · Obfuscate
java -jar neko-cli/build/libs/neko-cli-*-all.jar obfuscate \
     -c config.yml \
     -i path/to/input.jar \        # 覆盖 YAML 中的 input · overrides YAML input
     -o path/to/output.jar \       # 覆盖 YAML 中的 output · overrides YAML output
     -v                            # verbose

# 打印 JAR 信息 · Print JAR info
java -jar neko-cli/build/libs/neko-cli-*-all.jar info path/to/input.jar
```

| CLI 选项 · CLI option | 覆盖的 YAML 字段 · Overrides |
|---|---|
| `-i`, `--input`  | `input` |
| `-o`, `--output` | `output` |
| `-v`, `--verbose` | 打印完整堆栈 · Print full stack traces on error |

---

## 10. 完整示例 · Full Examples

### 10.1 最小（仅字符串加密） · Minimal (string encryption only)

```yaml
# test-jars/string-only.yml
version: 1
preset: LIGHT

transforms:
  controlFlowFlattening: { enabled: false }
  exceptionObfuscation:  { enabled: false }
  exceptionReturn:       { enabled: false }
  opaquePredicates:      { enabled: false }
  stringEncryption:      { enabled: true, intensity: 1.0 }
  numberEncryption:      { enabled: false }
  invokeDynamic:         { enabled: false }
  outliner:              { enabled: false }
  stackObfuscation:      { enabled: false }
  advancedJvm:           { enabled: false }

native: { enabled: false }

keys:
  masterSeed: 12345678
```

### 10.2 仅控制流 · CFF only

```yaml
# test-jars/cf-only.yml
version: 1
preset: LIGHT

transforms:
  controlFlowFlattening:
    enabled: true
    intensity: 1.0
    allowSwitchMethods: false
    allowMonitorMethods: false
    maxApplicableInstructionCount: 180
    maxBackwardBranches: 2
    maxBranchCount: 16
  # 其他 transform 全部显式关闭 · everything else disabled explicitly
  exceptionObfuscation:  { enabled: false }
  exceptionReturn:       { enabled: false }
  opaquePredicates:      { enabled: false }
  stringEncryption:      { enabled: false }
  numberEncryption:      { enabled: false }
  invokeDynamic:         { enabled: false }
  outliner:              { enabled: false }
  stackObfuscation:      { enabled: false }
  advancedJvm:           { enabled: false }

keys:
  masterSeed: 12345
```

### 10.3 PARANOID 全量 · Full PARANOID

```yaml
# test-jars/full-obfuscation.yml（节选 · excerpt）
version: 1
preset: PARANOID

transforms:
  controlFlowFlattening:
    enabled: true
    intensity: 1.0
    zkmStyle: true
    tailChainIntensity: 0.85
    tryCatchTailChainMultiplier: 0.35
    allowTryCatchMethods: true
    tryCatchMainOnly: true
    maxTryCatchBlocks: 18
    tryCatchBranchBonus: 2
    tryCatchInstructionBonus: 160
    entrypointTailChainMultiplier: 0.08
    entrypointMaxTryCatchBlocks: 64
    entrypointBranchBonus: 96
    entrypointInstructionBonus: 640
    allowSwitchMethods: false
    allowMonitorMethods: false
    maxApplicableInstructionCount: 180
    maxBackwardBranches: 2
    maxBranchCount: 16
  exceptionObfuscation:
    enabled: true
    intensity: 1.0
    flattenedIntensityMultiplier: 0.55
    skipFlattenedMethods: false
    skipMethodsWithTryCatch: true
    skipMethodsWithSwitches: true
    skipMethodsWithMonitors: true
    skipNonVoidMethods: true
    skipBackwardGotos: true
    maxApplicableInstructionCount: 260
    maxEligibleGotos: 24
  exceptionReturn:  { enabled: false }
  opaquePredicates: { enabled: true, intensity: 1.0 }
  stringEncryption: { enabled: true, intensity: 1.0 }
  numberEncryption:
    enabled: true
    intensity: 1.0
    skipMethodsWithTryCatch: true
    skipMethodsWithSwitches: true
    skipMethodsWithMonitors: true
    skipSensitiveApiMethods: true
    skipSmallLoopConstants: true
    maxPlainLoopConstant: 16
    maxApplicableInstructionCount: 220
    maxBranchCount: 18
  invokeDynamic:
    enabled: true
    intensity: 1.0
    skipMethodsWithTryCatch: true
    skipMethodsWithSwitches: true
    skipMethodsWithMonitors: true
    skipSensitiveApiMethods: true
    skipPrimitiveLoopCalls: true
    maxApplicableInstructionCount: 260
    maxBranchCount: 24
  outliner:         { enabled: false }
  stackObfuscation: { enabled: true, intensity: 1.0 }
  advancedJvm:      { enabled: true, intensity: 1.0 }

native: { enabled: false }

keys:
  masterSeed: auto
```

完整的预置配置见 · See full ready-made configs at [`test-jars/`](../test-jars/)；原生翻译示例见 [`configs/`](../configs/)。

---

<div align="center">

_🐾 Config is the shape of your boulder. · 配置决定了你的石头长什么样。_

</div>
