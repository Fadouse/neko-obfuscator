# Configuration Reference

[Chinese version](./zh-CN/CONFIG.md)

## Parsed Top-Level Fields

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
  validationSinkHardening: { enabled: true }
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

There is no active preset resolver in the current parser. A `preset` field may appear in old YAML files, but the current `ConfigParser` does not apply preset defaults from it. Enable every required transform explicitly.

## CLI Overrides

```bash
java -jar neko-cli/build/libs/neko-cli-*-all.jar obfuscate \
  -c config.yml \
  -i path/to/input.jar \
  -o path/to/output.jar \
  -v
```

| CLI option | Effect |
|---|---|
| `-c`, `--config` | Required YAML file. |
| `-i`, `--input` | Overrides `input`. |
| `-o`, `--output` | Overrides `output`. |
| `-v`, `--verbose` | Prints full stack traces on failure. |

## Validation

`ConfigValidator` currently checks:

- `input` is present and exists.
- `output` is present.
- Every classpath entry exists.
- Every configured transform intensity is in `[0.0, 1.0]`.
- `native.targets` is non-empty when `native.enabled: true`.

## Transform Entries

Every transform entry accepts:

```yaml
transforms:
  <id>:
    enabled: true
    intensity: 1.0
    optionName: optionValue
```

Boolean shorthand is also accepted:

```yaml
transforms:
  keyDispatch: true
  controlFlowFlattening: false
```

The current CLI registers:

| Transform ID | Phase | Purpose | Common setting |
|---|---|---|---|
| `renamer` | `PRE_TRANSFORM` | Renames application classes, fields, and methods; writes a mapping file; rewrites common reflection/resource strings. | `{ enabled: true, packagePrefix: a/ }` |
| `keyDispatch` | `PRE_TRANSFORM` | Adds and propagates hidden method key material. | `{ enabled: true }` |
| `methodParameterObfuscation` | `PRE_TRANSFORM` | Packs eligible method parameters into one `Object[]` carrier after key dispatch. | `{ enabled: true }` |
| `controlFlowFlattening` | `TRANSFORM` | Rewrites eligible methods into keyed island dispatchers and publishes CFF metadata. | `{ enabled: true, intensity: 1.0 }` |
| `validationSinkHardening` | `TRANSFORM` | Rewrites fixed `String.equals` validation sinks into CFF-live keyed tag checks. | `{ enabled: true }` |
| `runtimeVariableObfuscation` | `TRANSFORM` | Moves protected primitive locals into CFF-keyed shadow slots, poisons original primitive slots after stores, and leaves references on the JVM local path without a runtime reference warehouse. | `{ enabled: true }` |
| `constantObfuscation` | `TRANSFORM` | Rewrites numeric constants using CFF live state and class key tables. | `{ enabled: true, intensity: 1.0 }` |
| `stringObfuscation` | `TRANSFORM` | Encrypts string literals and concat recipe constants using CFF live state, AES/DES, XOR, and class-local caches. | `{ enabled: true, intensity: 1.0 }` |

Unknown or unregistered transform IDs can be parsed, but they do nothing unless a pass with the same ID is registered in the CLI.

### JVM Transform Options

`renamer` recognizes:

| Option | Type | Default | Notes |
|---|---|---|---|
| `packagePrefix` | string | `a/` | Internal-name prefix for application classes. Use `/` separators. |
| `renameRuntime` | bool | `true` | Obfuscates injected runtime API classes after native/runtime stages. |
| `runtimeClassPrefix` | string | `r/` | Internal-name prefix for runtime classes when `renameRuntime` is true. |

Any transform can carry `strictCoverage: true`. If set on any transform, the pipeline validates the current hard-coded JVM bytecode coverage set for every application method that has code. In the current code this primarily covers `renamer`, `controlFlowFlattening`, and `constantObfuscation`; use targeted integration/static audits for key-dispatch, method-parameter, and string-specific guarantees.

Current JVM dependencies are:

```text
renamer
keyDispatch
methodParameterObfuscation -> keyDispatch
controlFlowFlattening      -> keyDispatch
validationSinkHardening    -> controlFlowFlattening
runtimeVariableObfuscation -> controlFlowFlattening, validationSinkHardening
constantObfuscation        -> controlFlowFlattening
stringObfuscation          -> controlFlowFlattening
```

## Native Section

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

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `false` | Enables native translation. |
| `targets` | list | `[LINUX_X64, WINDOWS_X64]` | Must be non-empty when native mode is enabled. Supported target names are handled by `NativeBuildEngine`. |
| `zigPath` | string | `zig` | Zig executable path or command. |
| `resourceEncryption` | bool | `true` | Encrypt non-class resources after native libraries are added. |
| `encryptionAlgorithm` | string | `AES_256_GCM` | Current resource encryption algorithm. |
| `methods` | list | `["**/*"]` | Class or `classInternalName#methodName` glob selection. |
| `excludePatterns` | list | `[]` | Excludes classes or methods from glob selection. |
| `includeAnnotated` | bool | `true` | Selects classes/methods annotated with `@NativeTranslate`. |
| `skipOnError` | bool | `true` in API model | If true, native failures can fall back to non-native output. Use `false` for strict validation. |
| `outputPrefix` | string | `neko_impl_` | Kept for compatibility; current translator emits fixed `neko_native_*` names. |
| `obfuscateJniSlotDispatch` | bool | `false` | Compatibility field; current constructor accepts it but does not use it for naming. |
| `cacheJniIds` | bool | `false` | Compatibility field; current constructor accepts it but direct HotSpot metadata is the main path. |

Legacy nested resource keys are still parsed:

```yaml
native:
  resources:
    encrypt: true
    algorithm: AES_256_GCM
```

Prefer top-level `resourceEncryption` and `encryptionAlgorithm`.

## Native Selection

Selection order:

1. Runtime package `dev/nekoobfuscator/runtime/` is never translated.
2. If `includeAnnotated: true`, a class or method with `@NativeTranslate` is selected.
3. `excludePatterns` are checked before `methods`.
4. Patterns containing `#` match `classInternalName#methodName`.
5. Patterns without `#` match class internal names.

Constructors, class initializers, abstract methods, native methods, and bridge methods are not translated.

## Keys Section

```yaml
keys:
  masterSeed: auto
  layers: [CLASS, METHOD, INSTRUCTION, CONTROL_FLOW]
  mixing: SIP_HASH
```

| Field | Type | Default | Notes |
|---|---|---|---|
| `masterSeed` | long, `auto`, or null | `0` | `0`, `auto`, and null mean auto-generated build seed. A concrete long gives reproducible key material. |
| `layers` | list | `[CLASS, METHOD, INSTRUCTION, CONTROL_FLOW]` | Parsed into the model; current rebuilt JVM passes use their own method-level derivation from the master seed. |
| `mixing` | string | `SIP_HASH` | Parsed as `mixingAlgorithm`; current JVM pass mix is SplitMix64-style. |

Current JVM pass mix:

```text
mix(state, value):
  z = state + value + 0x9E3779B97F4A7C15
  z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9
  z = (z ^ (z >>> 27)) * 0x94D049BB133111EB
  return z ^ (z >>> 31)
```

This is obfuscation key material, not a cryptographic primitive.

## Rules Section

Rules are parsed into `ClassRule`:

```yaml
rules:
  - match: "com.example.**"
    exclude: false
    transforms:
      controlFlowFlattening: { enabled: true, intensity: 1.0 }
```

Rule `match` values accept class-name globs in either dotted or internal JVM
form, for example `com.example.**` or `com/example/**`. `*` matches one
package/name segment and `**` matches across package separators.

Top-level `transforms` are the default. Rules are evaluated in file order
against each class; later matching rules override earlier matching rules for
the same transform ID. Transform entries merge by ID, so a rule that mentions
only `stringObfuscation` does not change the effective `renamer` setting. The
last matching rule's `exclude` value is authoritative: a final `exclude: true`
disables all JVM transforms for the class, while a later `exclude: false`
re-enables the merged effective transform settings.

Excluded and non-enabled classes remain in the global class graph for name
reservation, hierarchy, override, and linkage analysis, but they are not
mutation targets for the scoped transform. Inner, local, and anonymous classes
are matched as independent JVM class entries by their actual names, such as
`com/example/Outer$Inner` or `com/example/Outer$1`; `com.example.Outer` does
not automatically match those entries.

## Minimal JVM-Only Example

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

## Strict Native Example

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
