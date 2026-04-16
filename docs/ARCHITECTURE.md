# 🧱  NekoObfuscator · 模型框架 / Architecture

> **状态提示 · Status notice**
> 本项目处于 **早期阶段 (Early Stage / Work in Progress)**。本文档描述的内部结构可能在任何版本发生不兼容变动。
> This project is in its **early-stage / work-in-progress** phase. Anything described below may change incompatibly in any commit.

> **作者 · Authors**
> 本文档由 **Anthropic Claude Opus 4.6 / 4.7** 与 **OpenAI GPT 5.4** 协作撰写，环境为 **opencode + ohmyopenagent (ohmyopencode)**。
> Co-authored by **Anthropic Claude Opus 4.6 / 4.7** and **OpenAI GPT 5.4** in the **opencode + ohmyopenagent (ohmyopencode)** environment.

---

## 目录 · Contents

- [1. 总体视图 · Big picture](#1-总体视图--big-picture)
- [2. 模块拓扑 · Module topology](#2-模块拓扑--module-topology)
- [3. 三层中间表示 · Three-level IR](#3-三层中间表示--three-level-ir)
  - [3.1 L1 — ASM 树层 · L1 — ASM-tree layer](#31-l1--asm-树层--l1--asm-tree-layer)
  - [3.2 L2 — CFG + SSA · L2 — CFG + SSA](#32-l2--cfg--ssa--l2--cfg--ssa)
  - [3.3 L3 — C-IR · L3 — C-IR](#33-l3--c-ir--l3--c-ir)
- [4. 流水线 · Pipeline](#4-流水线--pipeline)
- [5. Pass 模型 · Pass model](#5-pass-模型--pass-model)
- [6. 运行时模型 · Runtime model](#6-运行时模型--runtime-model)
- [7. 密钥模型 · Key model](#7-密钥模型--key-model)
- [8. 扩展点 · Extension points](#8-扩展点--extension-points)

---

## 1. 总体视图 · Big picture

```
        ┌────────────────────────────────────────────────────────────────┐
        │                         neko-cli                               │
        │                    (picocli entrypoint)                        │
        └───────────────┬────────────────────────────────┬───────────────┘
                        │                                │
                        ▼                                ▼
               ┌────────────────┐               ┌────────────────┐
               │   neko-config  │               │   neko-core    │
               │ YAML → Config  │               │ IR + Pipeline  │
               └────────┬───────┘               └───────┬────────┘
                        │                               │
                        ▼                               ▼
        ┌────────────────────────┐       ┌─────────────────────────────┐
        │      ObfuscationConfig  │       │   ObfuscationPipeline       │
        │  (preset, transforms,   │──────▶│  JarInput → IR → Passes →   │
        │   native, keys, rules)  │       │  JarOutput (+ runtime inj.) │
        └────────────────────────┘       └───────────┬─────────────────┘
                                                     │
                                     ┌───────────────┼────────────────┐
                                     ▼               ▼                ▼
                           ┌───────────────┐ ┌────────────────┐ ┌───────────────┐
                           │ neko-transforms│ │  neko-native   │ │ neko-runtime  │
                           │  (10+ passes) │ │ JVM→C→native   │ │ injected libs │
                           └───────────────┘ └────────────────┘ └───────────────┘
```

核心思想 · Core idea：

| 中文 | English |
|---|---|
| 把输入 JAR 解析到可结构化修改的 **L1 层**，在需要时提升到 **L2 层**（CFG + SSA）做分析、再降回 L1；选定方法则进一步翻译到 **L3 层**（C-IR）交给原生工具链。 | Parse the input JAR into the structurally-mutable **L1 layer**, lift to **L2** (CFG + SSA) when analysis is needed, lower back to L1; selected methods go further to **L3** (C-IR) for the native toolchain. |
| 所有变换都以 **Pass** 为单位，显式声明依赖与冲突，由 `PassScheduler` 做拓扑排序。 | Every transformation is a **Pass** with declared dependencies / conflicts, topologically scheduled by `PassScheduler`. |
| 输出 JAR 除了被混淆的类，还嵌入最小化的 **运行时类**（`NekoBootstrap`, `NekoKeyDerivation`, …），保证脱离混淆器即可独立运行。 | Output JARs embed minimal **runtime classes** (`NekoBootstrap`, `NekoKeyDerivation`, …) so that the resulting JAR runs standalone. |

---

## 2. 模块拓扑 · Module topology

```
neko-api  ──┬──▶ neko-config ─┐
            │                 │
            ├──▶ neko-core ───┼──▶ neko-transforms ─┐
            │                 │                     │
            │                 └──▶ neko-native ─────┤
            │                                       │
            └──▶ neko-runtime   (injected, no deps on others)
                                                    │
                                                    ▼
                                               neko-cli (shadow jar)
```

| 模块 · Module | 依赖 · Depends on | 关键入口 · Key entries |
|---|---|---|
| `neko-api` | — | `@Obfuscate`, `@DoNotObfuscate`, `@NativeTranslate`, `TransformPass`, `TransformContext`, `TransformPhase`, `IRLevel`, `ObfuscationConfig` |
| `neko-config` | `neko-api`, snakeyaml | `ConfigParser`, `ConfigValidator`, `PresetResolver`, `PatternMatcher` |
| `neko-core` | `neko-api`, ASM 9.7.1, slf4j | `ObfuscationPipeline`, `PassRegistry`, `PassScheduler`, `PipelineContext`, `L1ToL2Lifter`, `L2ToL1Lowerer`, `JarInput`, `JarOutput`, `ClassHierarchy` |
| `neko-transforms` | `neko-core` | 每个 Pass 一个类 · one class per pass (see [WHITEPAPER](./WHITEPAPER.md)) |
| `neko-native` | `neko-core` | `NativeTranslator`, `CCodeGenerator`, `NativeBuildEngine`, `OpcodeTranslator`, `ResourceEncryptor` |
| `neko-runtime` | —（仅 JDK API · JDK only） | `NekoBootstrap`, `NekoKeyDerivation`, `NekoStringDecryptor`, `NekoFlowException`, `NekoContext`, `NekoClassLoader`, `NekoNativeLoader`, `NekoResourceLoader` |
| `neko-cli` | 全部 · all of the above, picocli | `dev.nekoobfuscator.cli.Main` (`obfuscate`, `info`) |
| `neko-test` | `neko-cli` + JUnit 5 | `ObfuscationIntegrationTest` |

> **注 · Note**：`neko-runtime` 只使用 **Java 8** 级别的 API —— 这样即使目标程序运行在 Java 8 / 11 上，嵌入的运行时依然可用。
> `neko-runtime` is restricted to **Java 8** APIs so the injected runtime also works on Java 8/11 targets.

---

## 3. 三层中间表示 · Three-level IR

### 3.1 L1 — ASM 树层 · L1 — ASM-tree layer

| 中文 | English |
|---|---|
| **位置**：`neko-core/.../ir/l1/`（`L1Class`, `L1Method`, `L1Field`, `L1Annotation`）。 | **Location**: `neko-core/.../ir/l1/` (`L1Class`, `L1Method`, `L1Field`, `L1Annotation`). |
| **本质**：ASM `ClassNode` / `MethodNode` 的薄包装，保留原始字节与 `dirty` 标志。 | **Essence**: thin wrapper over ASM `ClassNode` / `MethodNode`, keeping original bytes and a `dirty` flag. |
| **特点**：可直接修改指令序列、字段、方法、注解；大部分 Pass 都在此层工作。 | **Traits**: direct mutation of instructions, fields, methods, annotations; most passes operate here. |
| **序列化**：通过 `ClassWriter`(COMPUTE_FRAMES \| COMPUTE_MAXS) 写回字节。 | **Serialisation**: written back with `ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS)`. |

### 3.2 L2 — CFG + SSA · L2 — CFG + SSA

| 中文 | English |
|---|---|
| **位置**：`neko-core/.../ir/l2/`。 | **Location**: `neko-core/.../ir/l2/`. |
| **组成**：`ControlFlowGraph`, `BasicBlock`, `CFGEdge`, `DominatorTree`, `PhiNode`, `SSAForm`, `SSAValue`, `DefUseChain`, `LivenessAnalysis`。 | **Members**: `ControlFlowGraph`, `BasicBlock`, `CFGEdge`, `DominatorTree`, `PhiNode`, `SSAForm`, `SSAValue`, `DefUseChain`, `LivenessAnalysis`. |
| **构建**：`L1ToL2Lifter` 遍历方法指令，以跳转/异常处理器/switch 作为边界切分基本块；支配树用标准迭代算法；SSA 使用 Cytron 等人的 **支配前沿 (dominance frontier)** 算法插入 φ-节点并做变量重命名。 | **Construction**: `L1ToL2Lifter` splits instructions into basic blocks (jumps / handlers / switches as boundaries); the dominator tree uses the classic iterative algorithm; SSA insertion / renaming follows **Cytron et al.'s dominance-frontier** algorithm. |
| **用途**：不透明谓词强度评估、控制流平坦化中的块排序 / 状态分配、活跃性分析辅助栈混淆与常量分析。 | **Usage**: opaque-predicate strength evaluation, block ordering / state assignment for CFF, liveness analysis supporting stack obfuscation and constant analysis. |
| **回落**：`L2ToL1Lowerer` 重新发射 JVM 指令序列回 L1。 | **Lowering**: `L2ToL1Lowerer` re-emits JVM instructions back into L1. |

> **注 · Note**：集成测试使用 `-Xss4m` 以容纳大方法上的递归 SSA 重命名。
> Integration tests run with `-Xss4m` to accommodate recursive SSA renaming on large methods.

### 3.3 L3 — C-IR · L3 — C-IR

| 中文 | English |
|---|---|
| **位置**：`neko-core/.../ir/l3/`（`CBlock`, `CExpression`, `CFunction`, `CStatement`, `CType`, `CVariable`）。 | **Location**: `neko-core/.../ir/l3/` (`CBlock`, `CExpression`, `CFunction`, `CStatement`, `CType`, `CVariable`). |
| **抽象**：一个 *子集* 的 C —— 变量、表达式、语句、函数、类型，可映射到 Java 原生类型与 `jobject`。 | **Shape**: a *C subset* — variables, expressions, statements, functions, types — mapping to Java primitives and `jobject`. |
| **消费者**：`neko-native` 中的 `CCodeGenerator` 产出 `.c` / `.h`，由 `NativeBuildEngine` 调用 **Zig** 交叉编译为 `.so` / `.dll`。 | **Consumer**: `CCodeGenerator` in `neko-native` emits `.c` / `.h`; `NativeBuildEngine` drives **Zig** as a cross-compiler to produce `.so` / `.dll`. |
| **映射规则**：`OpcodeTranslator` 把 JVM opcode 逐条翻译为 C 语句；JNI 签名由 `NativeTranslator.mangleJNIName()` 按 JVM 规范生成。 | **Mapping**: `OpcodeTranslator` converts JVM opcodes to C statements one-by-one; JNI signatures are produced by `NativeTranslator.mangleJNIName()` per the JVM spec. |
| **加载**：运行时由 `NekoNativeLoader` 解密资源并 `System.load(...)` 原生库。 | **Loading**: at runtime `NekoNativeLoader` decrypts the resource and `System.load(...)` the native library. |

---

## 4. 流水线 · Pipeline

`ObfuscationPipeline.execute(inputJar, outputJar)` 的大致流程 · The execute flow is roughly:

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. JarInput  : parse input → List<L1Class> + List<ResourceEntry>        │
│                                                                         │
│ 2. ClassHierarchy.build() (with ClasspathResolver if classpath provided)│
│                                                                         │
│ 3. PipelineContext ← { classes, hierarchy, resources, config,           │
│                        masterSeed = config.keys.masterSeed              │
│                                      ?: SecureRandom.nextLong() }       │
│                                                                         │
│ 4. passes = PassRegistry.all().filter(enabled(config))                  │
│    schedule = PassScheduler.schedule(passes, dependsOn, conflictsWith)  │
│                                                                         │
│ 5. for each pass p in schedule:                                         │
│        ctx = TransformContext(p, config, hierarchy, keys, …)            │
│        if p.operatesOn == L1:                                           │
│            for each class c (filtered by rules + isApplicable):         │
│                for each method m: p.transformMethod(c, m, ctx)          │
│        else:                                                            │
│            lift to L2, p.transformMethodL2, lower to L1                 │
│                                                                         │
│ 6. Post-pass cleanup per method:                                        │
│       - strip FrameNode (ClassWriter recomputes)                        │
│       - dead-code elimination via BFS from entry + handler starts       │
│       - drop try-catch entries with collapsed protected range           │
│       - recompute maxLocals                                             │
│                                                                         │
│ 7. Runtime injection:                                                   │
│       copy NekoBootstrap, NekoKeyDerivation (+ patch MASTER_SEED),      │
│            NekoStringDecryptor, NekoFlowException, NekoContext,         │
│            NekoClassLoader, NekoNativeLoader, NekoResourceLoader        │
│       into the output's dev/nekoobfuscator/runtime/ package.            │
│                                                                         │
│ 8. JarOutput.write(outputJar) → done.                                   │
└─────────────────────────────────────────────────────────────────────────┘
```

**关键点 · Highlights**：

- **PassScheduler** 通过拓扑排序处理 `@PassDependency` 中的 `dependsOn` / `conflictsWith`，确保例如 `advancedJvm` 在 `controlFlowFlattening` 之后执行。
  **PassScheduler** resolves `@PassDependency`'s `dependsOn` / `conflictsWith` by topological sort so e.g. `advancedJvm` always follows `controlFlowFlattening`.
- **MASTER_SEED patching**：默认种子为 `0x4E454B4F4F42464CL`（ASCII `"NEKOOBFL"`），Pipeline 将 `NekoKeyDerivation.MASTER_SEED` 字段的 `FieldNode.value` 以及 `<clinit>` 里的 `LDC` 常量都替换为当前构建的随机种子，**两次独立构建的输出互不兼容**。
  **MASTER_SEED patching**: the default constant is `0x4E454B4F4F42464CL` (ASCII `"NEKOOBFL"`); the pipeline rewrites both the `FieldNode.value` and the `LDC` inside `<clinit>` so **two independent builds are mutually incompatible**.

---

## 5. Pass 模型 · Pass model

```java
public interface TransformPass {
    String id();
    IRLevel operatesOn();                         // L1 | L2 | L3
    TransformPhase phase();                       // ANALYSIS | PRE_TRANSFORM | TRANSFORM | POST_TRANSFORM | FINALIZE
    PassDependency dependencies();                // dependsOn / conflictsWith
    boolean isApplicable(MethodNode m, TransformContext ctx);
    void transformClass(L1Class c, TransformContext ctx);
    void transformMethod(L1Class c, L1Method m, TransformContext ctx);
}
```

**Pass 属性 · Pass attributes**：

| 属性 · Attribute | 说明 · Description |
|---|---|
| `id()` | 与 YAML 中 `transforms.<id>` 对应（如 `controlFlowFlattening`） · Matches the YAML `transforms.<id>` key (e.g. `controlFlowFlattening`). |
| `phase()` | 执行阶段；同阶段内再按依赖排序 · Execution phase; within a phase ordering follows dependencies. |
| `isApplicable()` | 细粒度过滤：指令数、分支数、try-catch 存在性、敏感 API 调用等 · Fine-grained filtering: instruction count, branch count, try-catch presence, sensitive-API calls, etc. |
| `transformMethod()` | 真正的变换逻辑，可读取 `TransformContext` 中的密钥、层级信息 · Actual transformation; can read keys / layer info from `TransformContext`. |

**当前注册的 Pass · Currently registered passes**（详见 [WHITEPAPER](./WHITEPAPER.md)）:

```
controlFlowFlattening   → flow/ControlFlowFlatteningPass
exceptionObfuscation    → flow/ExceptionObfuscationPass
exceptionReturn         → flow/ExceptionReturnPass
opaquePredicates        → flow/OpaquePredicateGenerator
stringEncryption        → data/StringEncryptionPass
numberEncryption        → data/NumberEncryptionPass
invokeDynamic           → invoke/InvokeDynamicPass
outliner                → structure/OutlinerPass
stackObfuscation        → structure/StackObfuscationPass
advancedJvm             → advanced/AdvancedJvmPass
```

---

## 6. 运行时模型 · Runtime model

嵌入到输出 JAR 的 `dev/nekoobfuscator/runtime/` 包 · The `dev/nekoobfuscator/runtime/` package embedded into each output JAR:

| 类 · Class | 职责 · Responsibility |
|---|---|
| `NekoBootstrap` | 所有 INVOKEDYNAMIC 的 BSM 集散地；`bsmString(...)`, `bsmNumber(...)`, `bsmInvoke(...)` 等。 · Hub of all INVOKEDYNAMIC bootstrap methods: `bsmString(...)`, `bsmNumber(...)`, `bsmInvoke(...)`, … |
| `NekoKeyDerivation` | 分层派生 `CLASS → METHOD → INSTRUCTION → CONTROL_FLOW`，内含 SipHash 风格 mix + MurmurHash3 `fmix64`；`MASTER_SEED` 常量每次构建被 Pipeline 重写。 · Layered derivation, SipHash-style mix + MurmurHash3 `fmix64`; `MASTER_SEED` rewritten by the pipeline per build. |
| `NekoStringDecryptor` | 字符串解密，接收 BSM 注入的 salt / flowMode。 · String decryption with salt / flow mode injected by the BSM. |
| `NekoFlowException` | 作为控制流跳板的专用异常类型，`fillInStackTrace` 被抑制以减小开销。 · Dedicated jump exception; `fillInStackTrace` suppressed for low overhead. |
| `NekoContext` | 运行时单例：缓存派生密钥、类加载句柄、原生库句柄。 · Runtime singleton: caches derived keys, class-loader handles, native handles. |
| `NekoClassLoader` | 动态类加载器，支持懒解密嵌入类。 · Dynamic class loader, supports lazy decryption of embedded classes. |
| `NekoNativeLoader` | 加载 `.so`/`.dll`，解密资源，`System.load`。 · Loads `.so` / `.dll`, decrypts resources, `System.load`. |
| `NekoResourceLoader` | 解密资源（图片、配置等）。 · Decrypts resources (images, configs, …). |

**编译约束 · Build constraint**：`neko-runtime` 源码通过 Java 17 编译，但 **逻辑层面只使用 Java 8 API**，保证运行时 JAR 在 Java 8/11 上可执行。
`neko-runtime` is compiled with Java 17 but deliberately restricts itself to **Java 8-level APIs** so the injected runtime keeps working on Java 8/11 targets.

---

## 7. 密钥模型 · Key model

```
MASTER_SEED (64-bit, patched per build)
        │
        ▼
   ┌─────────────────────────────────────────┐
   │ SipHash-style mix                       │
   │   x *= 0x9E3779B97F4A7C15  // golden φ  │
   │   x  = rotl(x, 31)                      │
   │   x *= 0xBF58476D1CE4E5B9               │
   └──────────────────┬──────────────────────┘
                      ▼
   ┌─────────────────────────────────────────┐
   │ MurmurHash3 fmix64 finalizer            │
   │   x ^= x >>> 33                         │
   │   x *= 0xFF51AFD7ED558CCD               │
   │   x ^= x >>> 33                         │
   │   x *= 0xC4CEB9FE1A85EC53               │
   │   x ^= x >>> 33                         │
   └──────────────────┬──────────────────────┘
                      ▼
       ┌────────────────────────────────────┐
       │ Layered derivation                 │
       │ CLASS_KEY    = mix(MASTER, hash(class))        │
       │ METHOD_KEY   = mix(CLASS,  hash(name + desc))  │
       │ INSN_KEY     = mix(METHOD, salt)               │
       │ FLOW_KEY     = mix(METHOD, flowMode)           │
       └────────────────────────────────────┘
```

**为什么这样设计 · Why this design**：

- **不同层共享输入** 保证语义相同的代码在不同构建中得到不同密钥。
  **Sharing the master seed across layers** ensures semantically identical code gets different keys in different builds.
- **SipHash-style + fmix64** 组合便宜且抗平凡碰撞，足以支撑字符串/数字解密、控制流状态生成。
  The **SipHash-style + fmix64** combo is cheap yet resists trivial collisions — enough to support string/number decryption and CFG state generation.
- **`FLOW_KEY` 单独派生** 让同一方法内不同控制流模式（正常/异常/原生跳板）拥有独立密钥，降低 Pass 间串扰。
  Deriving `FLOW_KEY` separately allows distinct keys per flow mode (normal / exception / native trampoline) inside the same method, reducing cross-pass interference.

> **⚠️ 安全声明 · Security disclaimer**
> Neko 的密钥派生 **不是** 加密原语，而是 *伪随机扰动*。字符串解密密钥最终在 JVM 内存中明文存在，任何脱离静态分析的动态分析都可以恢复。
> Neko's key derivation is **not** a cryptographic primitive — it is a *pseudorandom scrambler*. String-decryption keys eventually live as plaintext in JVM memory; any dynamic analysis beyond pure static analysis can recover them.

---

## 8. 扩展点 · Extension points

| 扩展 · Extension | 推荐做法 · Recommended approach |
|---|---|
| 新增一个 Pass · Add a new pass | 继承 `TransformPass`，在 `neko-transforms` 下新建包，在 `neko-cli/.../ObfuscateCommand` 的 `registerPasses()` 中注册。 · Implement `TransformPass`, add a new package under `neko-transforms`, register it in `ObfuscateCommand.registerPasses()` in `neko-cli`. |
| 自定义预设 · Custom preset | 在 `PresetResolver` 中添加枚举分支；保持向后兼容。 · Add a branch in `PresetResolver`; keep backwards-compat. |
| 支持新的原生目标 · New native target | 扩展 `neko-native/.../codegen/NativeBuildEngine.targetTriple(...)` 与 `NativeConfig.Target`。 · Extend `NativeBuildEngine.targetTriple(...)` and `NativeConfig.Target`. |
| 替换运行时类 · Swap runtime class | 放入 `neko-runtime` 并在 `ObfuscationPipeline#injectRuntime()` 的拷贝列表中登记；仅依赖 Java 8 API。 · Put it under `neko-runtime`, register it in `ObfuscationPipeline#injectRuntime()`'s copy list; restrict to Java 8 API. |
| 新密钥混合函数 · New key-mix function | 修改 `KeyConfig.mixingAlgorithm` 枚举，同时更新 `neko-runtime/NekoKeyDerivation` 的对应实现（注意 JAR 内外需要一致的算法）。 · Extend `KeyConfig.mixingAlgorithm`; keep the `neko-runtime/NekoKeyDerivation` implementation in lock-step. |

---

<div align="center">

_📦 Keep the IR sharp; keep the pipeline boring; keep the runtime tiny._
_保持 IR 锋利，保持流水线无聊，保持运行时小巧。_

</div>
