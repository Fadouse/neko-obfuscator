# 配置参考

[English](../CONFIG.md)

## 当前支持解析的顶层配置

```yaml
input: path/to/input.jar
output: path/to/output.jar
classpath:
  - libs/dependency.jar

transforms:
  renamer: { enabled: true, packagePrefix: a/ }
  keyDispatch: { enabled: true }
  methodParameterObfuscation: { enabled: true }
  controlFlowFlattening: { enabled: true, intensity: 1.0 }
  runtimeVariableObfuscation: { enabled: true }
  constantObfuscation: { enabled: true, intensity: 1.0 }
  stringObfuscation: { enabled: true, intensity: 1.0 }

native:
  enabled: false

keys:
  masterSeed: auto
  layers: [CLASS, METHOD, INSTRUCTION, CONTROL_FLOW]
  mixing: SIP_HASH

rules: []
```

⚠️ 注意：：当前的解析器 (Parser) 尚未启用预设解析器 (Preset Resolver)。虽然在旧版 YAML 配置中允许存在 `preset` 字段，但当前的 `ConfigParser` 不会据此自动填充默认的 Transform。所有需要使用的 Transform 必须在配置中被显式启用。

## CLI 参数覆盖 (Overrides)

```bash
java -jar neko-cli/build/libs/neko-cli-*-all.jar obfuscate \
  -c config.yml \
  -i path/to/input.jar \
  -o path/to/output.jar \
  -v
```

| CLI 选项            | 作用说明                          |
|-------------------|-------------------------------|
| `-c`, `--config`  | 指定 YAML 配置文件（必填）。             |
| `-i`, `--input`   | 覆盖配置中的 `input` 路径。            |
| `-o`, `--output`  | 覆盖配置中的 `output` 路径。           |
| `-v`, `--verbose` | 发生错误时打印完整的异常堆栈 (Stack Trace)。 |


## 配置校验

当前 `ConfigValidator` 会执行以下严格检查：

* `input` 字段已配置，且对应的文件在磁盘上确实存在。
* `output` 字段已配置。
* `classpath` 列表中的每个依赖路径都必须存在。
* 所有的 Transform 强度 (`intensity`) 必须在 `[0.0, 1.0]` 范围内。
* 当 `native.enabled` 设为 `true` 时，`native.targets` 列表不能为空。

## Transform 配置

每个 Transform 接受以下格式的详细配置：

```yaml
transforms:
  <id>:
    enabled: true
    intensity: 1.0
    optionName: optionValue
```

同时也支持布尔值简写形式：

```yaml
transforms:
  keyDispatch: true
  controlFlowFlattening: false
```

当前 CLI 默认注册了以下 Transform：

| Transform ID                 | 阶段 (Phase)      | 作用说明                                                                                          | 常用配置示例                                 |
|------------------------------|-----------------|-----------------------------------------------------------------------------------------------|----------------------------------------|
| `renamer`                    | `PRE_TRANSFORM` | 重命名应用代码的类、字段和方法；导出混淆映射 (Mapping)；重写常见的反射与资源字符串。                                               | `{ enabled: true, packagePrefix: a/ }` |
| `keyDispatch`                | `PRE_TRANSFORM` | 注入并传递隐藏的方法密钥 (Method Key)。                                                                    | `{ enabled: true }`                    |
| `methodParameterObfuscation` | `PRE_TRANSFORM` | 在密钥分发完成后，将目标方法的参数统一打包进一个 `Object[]` 载体 (Carrier) 中。                                           | `{ enabled: true }`                    |
| `controlFlowFlattening`      | `TRANSFORM`     | 将目标方法重写为带密钥的孤岛分发器 (Keyed Island Dispatcher)，并发布 CFF 动态元数据 (CFF Metadata)。                     | `{ enabled: true, intensity: 1.0 }`    |
| `runtimeVariableObfuscation` | `TRANSFORM`     | 将受保护 primitive local 迁移到由 CFF 动态密钥驱动的 shadow slot，在写入后污染原始 primitive slot；reference 仍保留在 JVM local 路径上，不创建运行时引用仓库。 | `{ enabled: true }`                    |
| `constantObfuscation`        | `TRANSFORM`     | 利用 CFF 的动态状态 (CFF Live State) 与类密钥表 (Class Key Table) 重写数值常量。                                 | `{ enabled: true, intensity: 1.0 }`    |
| `stringObfuscation`          | `TRANSFORM`     | 利用 CFF 动态状态 (CFF Live State)、AES/DES 算法、异或 (XOR) 操作以及类级别的缓存，加密字符串字面量与拼接配方 (Concat Recipe) 常量。 | `{ enabled: true, intensity: 1.0 }`    |

**提示**：配置文件可以解析未知或未注册的 Transform ID，但除非 CLI 环境中注册了同名的 Pass，否则这些配置不会产生任何实际效果。

### JVM Transform 选项

`renamer` 支持以下专属选项：

| 选项                   | 类型     | 默认值    | 说明                                          |
|----------------------|--------|--------|---------------------------------------------|
| `packagePrefix`      | string | `a/`   | 应用类的内部名称 (Internal Name) 前缀；包名之间需使用 `/` 分隔。 |
| `renameRuntime`      | bool   | `true` | 在 Native/Runtime 阶段结束后，对注入的运行时 API 类进行名称混淆。 |
| `runtimeClassPrefix` | string | `r/`   | 当 `renameRuntime` 为 true 时，作为注入运行时类的内部名称前缀。 |

任何一个 Transform 均可配置 `strictCoverage: true`。只要流水线中存在任意一个开启了此选项的 Transform，系统就会基于当前硬编码的 JVM 字节码覆盖率集合，对应用中每一个包含代码的方法进行严格校验。
目前在代码实现中，该校验主要覆盖 `renamer`、`controlFlowFlattening` 和 `constantObfuscation`；而针对密钥分发、方法参数和字符串特异性的逻辑，依然建议通过专门的集成测试与静态审计来保障。

当前的 JVM Pass 依赖关系如下：

```text
renamer
keyDispatch
methodParameterObfuscation -> keyDispatch
controlFlowFlattening      -> keyDispatch
runtimeVariableObfuscation -> controlFlowFlattening
constantObfuscation        -> controlFlowFlattening
stringObfuscation          -> controlFlowFlattening
```

## Native 编译配置

```yaml
native:
  enabled: false
  targets: [LINUX_X64, WINDOWS_X64]
  zigPath: zig
  resourceEncryption: true
  encryptionAlgorithm: AES_256_GCM
  methods: ["**/*"]
  excludePatterns: []
  includeAnnotated: true
  skipOnError: false
  outputPrefix: neko_impl_
  obfuscateJniSlotDispatch: false
  cacheJniIds: false
```

| 字段                         | 类型     | 默认值                        | 说明                                                              |
|----------------------------|--------|----------------------------|-----------------------------------------------------------------|
| `enabled`                  | bool   | `false`                    | 是否开启 Native 代码翻译。                                               |
| `targets`                  | list   | `[LINUX_X64, WINDOWS_X64]` | 当 Native 开启时不能为空；目标平台名称交由 `NativeBuildEngine` 处理。               |
| `zigPath`                  | string | `zig`                      | Zig 工具链可执行文件的路径或命令名称。                                           |
| `resourceEncryption`       | bool   | `true`                     | 在打包 Native 动态库后，是否对非 Class 资源进行加密。                              |
| `encryptionAlgorithm`      | string | `AES_256_GCM`              | 资源加密所采用的算法。                                                     |
| `methods`                  | list   | `["**/*"]`                 | 通过 Glob 模式选择目标类或方法，格式支持 `classInternalName#methodName`。         |
| `excludePatterns`          | list   | `[]`                       | 从 Glob 选择结果中排除特定的类或方法。                                          |
| `includeAnnotated`         | bool   | `true`                     | 自动选中带有 `@NativeTranslate` 注解的类或方法。                              |
| `skipOnError`              | bool   | API 默认 `true`              | 设为 true 时，若 Native 编译失败会回退到非 Native 的纯字节码输出；如需严格验证，请设为 `false`。 |
| `outputPrefix`             | string | `neko_impl_`               | 兼容性保留字段；当前的翻译器已固定生成 `neko_native_*` 格式的名称。                      |
| `obfuscateJniSlotDispatch` | bool   | `false`                    | 兼容性保留字段；当前的构造器会接收该值，但不会将其应用于命名混淆中。                              |
| `cacheJniIds`              | bool   | `false`                    | 兼容性保留字段；当前主线逻辑已改为直接依赖 HotSpot 元数据。                              |

解析器仍兼容旧版的嵌套资源加密配置：

```yaml
native:
  resources:
    encrypt: true
    algorithm: AES_256_GCM
```

但建议优先使用顶层的 `resourceEncryption` 和 `encryptionAlgorithm` 字段。

## Native 选择规则

选择顺序：

1. 系统永远不会翻译 `dev/nekoobfuscator/runtime/` 命名空间下的运行时包。
2. 当 `includeAnnotated: true` 时，所有带有 `@NativeTranslate` 注解的类或方法将被无条件选中。
3. `excludePatterns`（排除列表）的检查先于 `methods`（包含列表）执行。
4. 包含 `#` 字符的模式将被视为精细匹配，格式为 `classInternalName#methodName`。
5. 不包含 `#` 字符的模式将被视为对类内部名称 (Internal Name) 的全局匹配。

⚠️ 注意：构造器、类初始化器 (`<clinit>`)、抽象方法、Native 声明方法以及桥接 (Bridge) 方法均不会被翻译。

## 密钥 (Keys) 配置

```yaml
keys:
  masterSeed: auto
  layers: [CLASS, METHOD, INSTRUCTION, CONTROL_FLOW]
  mixing: SIP_HASH
```

| 字段           | 类型                  | 默认值            | 说明                                                           |
|--------------|---------------------|----------------|--------------------------------------------------------------|
| `masterSeed` | long, `auto` 或 null | `0`            | 设为 `0`、`auto` 或 null 代表自动生成构建种子；指定具体的 long 整数可实现可复现的构建密钥。    |
| `layers`     | list                | `[CLASS, ...]` | 会被解析进内部模型；目前重构后的 JVM Pass 会基于 master seed 进行独立的方法级派生。        |
| `mixing`     | string              | `SIP_HASH`     | 解析为底层的 `mixingAlgorithm`；当前 JVM Pass 采用类似于 SplitMix64 的混合算法。 |

当前 JVM Pass 采用的混合算法 (Mix) 逻辑如下：

```text
mix(state, value):
  z = state + value + 0x9E3779B97F4A7C15
  z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9
  z = (z ^ (z >>> 27)) * 0x94D049BB133111EB
  return z ^ (z >>> 31)
```

⚠️ 安全声明：上述算法仅用于快速打乱和生成混淆所需的密钥材料 (Key Material)，并非用于抵御密码分析的高强度密码学原语 (Cryptographic Primitive)。
## 规则 (Rules) 配置

配置列表会被解析为 `ClassRule` 模型：

```yaml
rules:
  - match: "com.example.**"
    exclude: false
    transforms:
      controlFlowFlattening: { enabled: true, intensity: 1.0 }
```

`match` 支持点号类名或 JVM internal name 形式的通配符，例如
`com.example.**` 或 `com/example/**`。`*` 匹配单个包/名称片段，`**`
可以跨包分隔符匹配。

顶层 `transforms` 是默认值。规则按文件顺序对每个 class 求值；后匹配的规则
会覆盖前面规则中同一个 transform ID 的设置。Transform 配置按 ID 合并，
所以只声明 `stringObfuscation` 的规则不会改变该 class 的 `renamer` 有效设置。
最后一个匹配规则的 `exclude` 值生效：最终 `exclude: true` 会禁用该 class
的全部 JVM transforms；后续 `exclude: false` 会重新启用合并后的有效
transform 设置。

被排除或未启用的 class 仍保留在全局 class 图中，用于名称占用、继承层级、
override 和链接分析，但它们不是该 scoped transform 的修改目标。Inner、
local 和 anonymous class 会按真实 JVM class entry 独立匹配，例如
`com/example/Outer$Inner` 或 `com/example/Outer$1`；`com.example.Outer`
不会自动匹配这些条目。

## 最小纯 JVM 混淆示例

```yaml
input: app.jar
output: app-obf.jar

transforms:
  renamer: { enabled: true, packagePrefix: a/ }
  keyDispatch: { enabled: true }
  methodParameterObfuscation: { enabled: true }
  controlFlowFlattening: { enabled: true, intensity: 1.0 }
  constantObfuscation: { enabled: true, intensity: 1.0 }
  stringObfuscation: { enabled: true, intensity: 1.0 }

native:
  enabled: false

keys:
  masterSeed: auto
```

## 严格 Native 混淆示例

```yaml
input: app.jar
output: app-native.jar

transforms:
  renamer: { enabled: true, packagePrefix: a/ }
  keyDispatch: { enabled: true }
  methodParameterObfuscation: { enabled: true }
  controlFlowFlattening: { enabled: true, intensity: 1.0 }
  constantObfuscation: { enabled: true, intensity: 1.0 }
  stringObfuscation: { enabled: true, intensity: 1.0 }

native:
  enabled: true
  targets: [LINUX_X64]
  zigPath: zig
  methods:
    - "com/example/**"
  excludePatterns:
    - "com/example/debug/**"
  includeAnnotated: true
  skipOnError: false
  resourceEncryption: true
  encryptionAlgorithm: AES_256_GCM

keys:
  masterSeed: auto
```
