<div align="center">

# 🐱  NekoObfuscator

**先进的 Java 字节码混淆器 · Advanced Java bytecode obfuscator**

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](./LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-red.svg)](https://adoptium.net/)
[![Status](https://img.shields.io/badge/status-early--stage-orange.svg)](#-%E9%A1%B9%E7%9B%AE%E7%8A%B6%E6%80%81--project-status)
[![Gradle](https://img.shields.io/badge/build-Gradle-02303A.svg)](./gradle)

三层中间表示 · 控制流平坦化 · INVOKEDYNAMIC 字符串加密 · 原生代码翻译
<br/>
Three-level IR · Control-flow flattening · INVOKEDYNAMIC string encryption · JVM→native translation

</div>

---

## ⚠️  项目状态 · Project Status

> **🚧 本项目目前处于 _早期阶段 (Early Stage / Work in Progress)_。**
> 接口、配置格式、甚至 IR 形状都可能在任意版本发生不兼容变更，请勿用于生产。
>
> **🚧 This project is in its _early stage / work-in-progress_ phase.**
> APIs, configuration format, and even the IR shape may change incompatibly in any commit.
> **Not production-ready.**

## 🤖  关于作者 · About the Authors

| 中文 | English |
|---|---|
| 本仓库的代码、配置与文档主要由 AI 协作完成： | The source, configuration and documentation in this repository were co-authored by: |
| - **Anthropic Claude Opus 4.6 / 4.7** | - **Anthropic Claude Opus 4.6 / 4.7** |
| - **OpenAI GPT 5.4** | - **OpenAI GPT 5.4** |
| 运行环境：**opencode + ohmyopenagent (ohmyopencode)** | Runtime: **opencode + ohmyopenagent (ohmyopencode)** |
| 人类维护者负责架构取舍、代码审阅与最终合并。 | The human maintainer is responsible for architectural decisions, code review and final merges. |

---

## 📖  目录 · Table of Contents

- [特性 · Features](#-特性--features)
- [架构速览 · Architecture at a Glance](#-架构速览--architecture-at-a-glance)
- [快速开始 · Quick Start](#-快速开始--quick-start)
- [配置 · Configuration](#-配置--configuration)
- [模块 · Modules](#-模块--modules)
- [文档 · Documentation](#-文档--documentation)
- [路线图 · Roadmap](#-路线图--roadmap)
- [贡献 · Contributing](#-贡献--contributing)
- [许可 · License](#-许可--license)

---

## ✨  特性 · Features

| 中文 | English |
|---|---|
| **三层 IR 架构** — L1 (ASM 树) / L2 (CFG + SSA) / L3 (C-IR) | **Three-level IR** — L1 (ASM tree) / L2 (CFG + SSA) / L3 (C-IR) |
| **控制流平坦化** — ZKM 风格尾链、try-catch 加权、入口点增强 | **Control-flow flattening** — ZKM-style tail chains, try-catch weighting, entry-point boosting |
| **异常控制流** — 使用 `NekoFlowException` 伪装跳转 | **Exception-driven control flow** — jumps disguised via `NekoFlowException` |
| **不透明谓词** — 算术 / 数组 / HashCode / 线程 四类谓词 | **Opaque predicates** — arithmetic / array / hashCode / thread variants |
| **字符串加密** — 基于 INVOKEDYNAMIC + 动态密钥派生 + 合成字段 `__e<n>` | **String encryption** — INVOKEDYNAMIC + dynamic key derivation + synthetic fields `__e<n>` |
| **数字加密 / InvokeDynamic 调用重写** | **Number encryption / InvokeDynamic call rewriting** |
| **栈操作混淆** — 冗余 DUP / POP 插桩 | **Stack obfuscation** — redundant DUP / POP instrumentation |
| **高级 JVM 骚操作** — 死代码、重叠异常处理器、假源码名、本地变量表扰动 | **Advanced JVM tricks** — dead code, overlapping handlers, fake source names, LVT scrambling |
| **原生代码翻译** — 选定方法 → C-IR → Zig 工具链 → `.so` / `.dll` | **Native translation** — selected methods → C-IR → Zig toolchain → `.so` / `.dll` |
| **资源加密** — 默认 AES-256-GCM | **Resource encryption** — AES-256-GCM by default |
| **动态密钥派生** — SipHash 风格混合 + MurmurHash3 `fmix64`，按 CLASS → METHOD → INSTRUCTION → CONTROL_FLOW 分层派生 | **Dynamic key derivation** — SipHash-style mixing + MurmurHash3 `fmix64`, layered as CLASS → METHOD → INSTRUCTION → CONTROL_FLOW |
| **预设驱动** — LIGHT / STANDARD / AGGRESSIVE / PARANOID | **Preset-driven** — LIGHT / STANDARD / AGGRESSIVE / PARANOID |

---

## 🧱  架构速览 · Architecture at a Glance

```
┌────────────┐  parse   ┌────────────┐  lift   ┌────────────┐  translate  ┌────────────┐
│ Input .jar │ ───────▶ │   L1 IR    │ ──────▶ │   L2 IR    │ ─────────▶ │   L3 IR    │
│ (bytecode) │          │ ASM tree   │         │ CFG + SSA  │             │  C-IR      │
└────────────┘          └────────────┘         └────────────┘             └────────────┘
                               │                      │                         │
                               ▼                      ▼                         ▼
                    ┌──────────────────┐   ┌───────────────────┐     ┌────────────────────┐
                    │  Transform Passes│   │  Flow Analyses    │     │  C Code Generator  │
                    │  (L1-level)      │   │  dom / live / SSA │     │  + Zig toolchain   │
                    └──────────────────┘   └───────────────────┘     └────────────────────┘
                               │
                               ▼
                    ┌──────────────────────────────────────────────────────────────┐
                    │  Post-pass cleanup → Runtime injection → Output .jar         │
                    │  (MASTER_SEED patched per build, 8 runtime classes embedded) │
                    └──────────────────────────────────────────────────────────────┘
```

详见 · See [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) · [docs/WHITEPAPER.md](./docs/WHITEPAPER.md)

---

## 🚀  快速开始 · Quick Start

### 前置条件 · Prerequisites

| 中文 | English |
|---|---|
| JDK **17** 或更高 | JDK **17** or later |
| Gradle Wrapper 已随仓库提供（无需单独安装） | Gradle Wrapper ships with the repo (no separate install needed) |
| （可选）Zig **0.12+** — 仅当启用原生翻译时需要 | (optional) Zig **0.12+** — only when native translation is enabled |

### 构建 · Build

```bash
# Unix / macOS / WSL
./gradlew :neko-cli:shadowJar

# Windows (PowerShell / CMD)
.\gradlew.bat :neko-cli:shadowJar
```

产物 · Output:

```
neko-cli/build/libs/neko-cli-<version>-all.jar
```

### 运行 · Run

```bash
# 查看内置帮助 · Show built-in help
java -jar neko-cli/build/libs/neko-cli-*-all.jar --help

# 混淆一个 JAR · Obfuscate a JAR
java -jar neko-cli/build/libs/neko-cli-*-all.jar obfuscate \
     --config test-jars/full-obfuscation.yml \
     --input  path/to/input.jar \
     --output path/to/output.jar

# 查看配置 / 构建信息 · Inspect config / build info
java -jar neko-cli/build/libs/neko-cli-*-all.jar info \
     --config test-jars/full-obfuscation.yml
```

### 运行测试 · Run tests

```bash
./gradlew :neko-test:test
```

---

## ⚙️  配置 · Configuration

最小配置 · Minimal config（`config.yml`）:

```yaml
version: 1
preset: STANDARD       # LIGHT | STANDARD | AGGRESSIVE | PARANOID
transforms:
  controlFlowFlattening: { enabled: true,  intensity: 0.6 }
  stringEncryption:      { enabled: true,  intensity: 1.0 }
  opaquePredicates:      { enabled: true,  intensity: 0.5 }
  numberEncryption:      { enabled: true,  intensity: 0.4 }
  invokeDynamic:         { enabled: true,  intensity: 0.5 }
  stackObfuscation:      { enabled: false }
  advancedJvm:           { enabled: false }
native:
  enabled: false
keys:
  masterSeed: auto       # 每次构建随机生成；也可指定定值以获得可复现输出
                         # generated per build; set explicitly for reproducible output
```

更多字段、预设默认值与调优建议，详见 · For all fields, preset defaults and tuning advice, see
[`docs/CONFIG.md`](./docs/CONFIG.md).

仓库内提供多套示例 · Ready-made examples live in [`test-jars/`](./test-jars/):

| 文件 · File | 说明 · Description |
|---|---|
| `cf-only.yml` | 仅控制流平坦化 · Control-flow flattening only |
| `cf-exc-op.yml` | CFF + 异常混淆 + 不透明谓词 · CFF + exception obfuscation + opaque predicates |
| `string-only.yml` | 仅字符串加密 · String encryption only |
| `full-obfuscation.yml` | PARANOID 预设完整配置 · Full PARANOID preset |
| `diag.yml` | 诊断 / 调试用 · Diagnostic / debugging config |

---

## 🧩  模块 · Modules

| 模块 · Module | 职责 · Responsibility |
|---|---|
| `neko-api` | 注解 & 接口（`@Obfuscate`, `@DoNotObfuscate`, `@NativeTranslate`, `TransformPass`, …） · Annotations & interfaces |
| `neko-config` | YAML → `ObfuscationConfig` 解析 / 校验 / 预设解析 · YAML parsing, validation, preset resolution |
| `neko-core` | 三层 IR、JAR I/O、类层级、Pass 注册与调度 · Three-level IR, JAR I/O, class hierarchy, pass registry & scheduler |
| `neko-transforms` | 所有 L1 级 Pass · All L1-level transformation passes |
| `neko-native` | JVM 方法 → C 源码 → Zig 构建 → 原生库，含资源加密 · JVM method → C source → Zig build → native library, incl. resource encryption |
| `neko-runtime` | 注入到输出 JAR 的运行时（`NekoBootstrap`, `NekoKeyDerivation`, `NekoStringDecryptor`, …） · Runtime helpers embedded into the output JAR |
| `neko-cli` | picocli 驱动的命令行入口 · picocli-driven CLI entry point |
| `neko-test` | JUnit 5 集成测试 · JUnit 5 integration tests |

---

## 📚  文档 · Documentation

| 文档 · Document | 内容 · Content |
|---|---|
| [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | 模型框架：三层 IR、Pass 流水线、运行时模型 · Architecture: three-level IR, pass pipeline, runtime model |
| [`docs/WHITEPAPER.md`](./docs/WHITEPAPER.md) | 混淆技术白皮书：逐项 Pass 的原理 / 强度 / 风险 · Obfuscation-technique whitepaper: principle / intensity / risk for every pass |
| [`docs/CONFIG.md`](./docs/CONFIG.md) | YAML 配置字段速查 · YAML configuration reference |

---

## 🗺️  路线图 · Roadmap

- [ ] 完善原生翻译器（更多 opcode 覆盖） · Broaden the native translator (more opcode coverage)
- [ ] 可复现构建的种子快照 · Reproducible-build seed snapshots
- [ ] Pass 级耗时 & 规模统计报告 · Per-pass time & size telemetry
- [ ] 端到端反混淆抗性基准 · End-to-end deobfuscation-resistance benchmark
- [ ] 外部插件 SPI · External plugin SPI
- [ ] IntelliJ / Gradle 插件 · IntelliJ / Gradle plugin

## 🙌  贡献 · Contributing

| 中文 | English |
|---|---|
| 欢迎 issue 与 PR！由于处于早期阶段，请先开 issue 描述意图，避免重复劳动。 | Issues and PRs are welcome. Because the project is still early-stage, please open an issue describing your intent first to avoid duplicated work. |
| 提交前请运行 `./gradlew build` 与 `./gradlew :neko-test:test`。 | Please run `./gradlew build` and `./gradlew :neko-test:test` before submitting. |
| 代码风格：遵循现有模块的包结构与命名约定。 | Style: follow the existing module layout and naming conventions. |

## 📜  许可 · License

本项目基于 **GNU General Public License v3.0** 发布，详见 [`LICENSE`](./LICENSE)。
This project is released under the **GNU General Public License v3.0**; see [`LICENSE`](./LICENSE).

```
NekoObfuscator — Advanced Java bytecode obfuscator
Copyright (C) 2026  Fadouse and contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

---

<div align="center">

_🐾 Rolling the boulder, one pass at a time. · 一次一个 Pass，推着石头前进。_

</div>
