# NekoObfuscator — Native Trampoline Open Problems

> 最后更新：2026-04-27（**release 上 obfusjack 完整通过**；P1/P5 全部修复）
> 本文件聚焦“目前还没有干净修复”的具体技术问题：症状、根因、复现命令、已尝试方案与结论、关键内存/寄存器证据。所有结论都以 fastdebug build 的 GDB core 取证为依据。

## 2026-04-27 P5 根因与修复

P5 的真正根因不是 Path 2 stash/spill 副作用，而是 **`-XX:-PreserveFramePointer`（HotSpot 默认）下 C2 把 `rbp` 当通用寄存器，可能在 JavaCall 调用点把 oop 放进 rbp**。复现矩阵：

| 配置 | 结果 |
| --- | --- |
| `-Xint` | ✅ 通过 |
| `-XX:TieredStopAtLevel=1`（C1 only） | ✅ 通过 |
| `-XX:-TieredCompilation`（C2 only） | ✅ 通过 |
| `-XX:-Inline` | ✅ 通过 |
| `-XX:+PreserveFramePointer` | ✅ 通过 |
| 默认（C1+C2 tiered，无 PreserveFramePointer） | ❌ 49999/50000 |

只有 tiered + 内联且 rbp 可作 oop 这条最全的路径才会触发，说明 bug 是 GC 期间 rbp 中的 oop 被搬移、但我们 trampoline 恢复 rbp 时取的是 thunk 自己 push 的旧地址（GC 没更新），导致 caller resume 时 rbp 为 stale oop。

修复（`X86_64SysVTrampoline.java`）：path 2 i2i return tail 改成从 stash slot（`(16 + extraspace_bytes)(%rbp)`，正是 `saved_fp_addr = sender_sp - 16`）读取最新 caller rbp 值再恢复。GC 通过 accept 的 OopMap 把 `*saved_fp_addr` 重写为搬移后地址，我们读的是同一个 slot，自然拿到 GC 更新后的值。Path 1（解释器路径）走 `movq (%rbp), %rbp`，间接通过 thunk_rbp_value 读 `naked_rbp + 16`（== path 1 的 saved_fp_addr），同样跟 GC 同步，无需改动。

修复后所有 6 个 testsuite 49 testcase pass，release JDK 5 次连跑 obfusjack 100% 稳定。

## 2026-04-27 关键进展

P1 已大幅推进（不再是 immediate 崩溃）：
- 引入 `g_neko_sig_extraspace_words[]` 与 `g_neko_sig_i2i_path2[]`（per signature）。
- `Method::_i2i_entry` 现在指向**专门为 Path 2 准备的 i2i thunk**（`neko_sig_N_i2i_path2`），其 `BufferBlob._frame_size = 3 + extraspace_words`。`Method::_from_interpreted_entry` 仍指向原 `neko_sig_N_i2i`（`_frame_size = 3`，无 stash），保证解释器直接路径不受影响。
- Path 2 naked 内部多两步：(a) 把 receiver / 引用参数 spill 到 `-32(%rbp)`、`-40(%rbp)`、… 这些 naked 自有栈槽，dispatcher 读 spill 槽地址（绕开 args 区被 stash 覆盖）；(b) 在 anchor 发布块尾把 thunk 保存的 rbp 值写到 `(16 + extraspace_bytes)(%rbp)`，正好是 walker 计算出的 `saved_fp_addr = sender_sp - 16`。
- 合在一起：accept._sp 等于 `caller_pre_call_rsp`（修好 P1 原始 `assert(pc != nullptr)`），同时 accept._fp 指向真实的 saved-rbp 槽（修好 `Universe::is_in_heap` decode 失败）；TEST/SnakeGame 全绿，obfusjack 已能跑过 `Platform threads: 50000 tasks` 这一段（之前在该处第一次崩）。

新出现/未解决的问题（详见 P5）：
- microbench 的 `lambda$microbenchThreads$10` 用 `IntStream.range(0,50000).mapToObj(...).toList()`，release JDK 抛 `IllegalStateException: End size 49999 is less than fixed size 50000`；
- fastdebug 同一测试期间触发 `~UncommonTrapBlob` → `Deoptimization::create_vframeArray` → `StackValue::create_stack_value_from_narrowOop_location` 上的 `assert(oopDesc::is_oop_or_null(val))` 失败，该断言读取 accept compiledVFrame 的 expression slot；deopt 时机和我们 trampoline 之前的执行有耦合关系，但具体如何把 accept 的 expression 区污染掉还没定位。

## 环境

- 仓库根：`/mnt/d/Code/Security/NekoObfuscator`
- Release JDK：`/usr/lib/jvm/java-21-openjdk` (21.0.10+7)
- 自建 fastdebug JDK：`tmp/openjdk-jdk21u/build/linux-x86_64-server-fastdebug/images/jdk`
  - 编译参数：`--with-debug-level=fastdebug --with-jvm-variants=server --with-native-debug-symbols=internal`
  - 构建补丁：`tmp/openjdk-jdk21u/src/hotspot/share/utilities/globalDefinitions.hpp` 中 `uabs` 重命名为 `g_uabs` 并 `#define uabs g_uabs`，避免 GCC 15 / glibc 2.42 stdlib.h 冲突。
- 目标 jar：`neko-test/build/test-native/obfusjack-native.jar`
- 关键 trampoline 文件：
  - `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/X86_64SysVTrampoline.java`
  - `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/MethodPatcherEmitter.java`

---

## P1 — `obfusjack` Microbench 阶段 GC 触发 `frame::sender_for_compiled_frame` `assert(pc != nullptr)` 失败

### 症状（fastdebug）

```
Internal Error (frame_x86.inline.hpp:135), pid=4007728, tid=4007870
assert(pc != nullptr) failed: no pc?
V  [libjvm.so+0x84c1af]  frame::sender_for_compiled_frame(RegisterMap*) const+0x62f
V  [libjvm.so+0xa213c8]  frame::sender_raw(RegisterMap*) const+0x258
V  [libjvm.so+0xf48e6e]  JavaThread::oops_do_frames(...)+0xbe
V  [libjvm.so+0x199bad9]  Thread::oops_do(...)+0x89
V  [libjvm.so+0x19b4bd4]  Threads::possibly_parallel_oops_do(...)+0x1a4
V  [libjvm.so+0xd987ad]  G1RootProcessor::process_java_roots(...)+0x6d
```

被 G1 walk 的目标 Java 线程帧链：
```
~RuntimeStub::_new_instance_Java
J 749 c2 java.lang.invoke.MethodType.makeImpl
J 784 c2 java.lang.invoke.MethodHandle.invokeWithArguments
~StubRoutines::call_stub
~BufferBlob::neko_trampoline
J 790 c2 java.util.stream.IntPipeline$1$1.accept(I)V
```

Release JDK 上同一处：`Internal Error (frame.cpp:1158), Error: ShouldNotReachHere()` —— 因为 release 跳过 `pc != nullptr` assert，把 NULL pc 带进 `find_blob`，在 `oops_do` 派发时 `is_interpreted_frame()/is_entry_frame()/is_upcall_stub_frame()/CodeCache::contains(pc())` 全 false → `ShouldNotReachHere()`。

### 根因（已通过 GDB core dump 锁定）

被走的 frame 是我们 i2i 的 BufferBlob，其 sender 是 `J 790 IntPipeline$1$1.accept(I)V`（C2 编译）。

调用链是 **Path 2**：
1. `accept`（C2 编译）的 call site 在 `_from_compiled_entry` 被 patch **之前**就被 resolve 了，因此它 `call <HotSpot 的 c2i adapter for our method>`。
2. HotSpot 的 `gen_c2i_adapter`（`sharedRuntime_x86_64.cpp:607-655`）执行：
   ```
   lea r13, [rsp + 8]      ; r13 = caller_pre_call_rsp
   pop rax                 ; rax = post_call_pc
   sub rsp, extraspace     ; 为 interpreter slot 区让出空间
   push rax                ; 把 PC 推到新位置
   ; 把 args 从 compiled regs 写到 [rsp + 8 .. rsp + 8 + extraspace]
   jmp Method::_interpreter_entry
   ```
   其中 `Method::_interpreter_entry == _i2i_entry`（见 `method.hpp:671`）。我们 patch 了 `_i2i_entry`，所以 jmp 落在我们的 i2i thunk。
3. 我们的 thunk（`push rbp; mov rsp,rbp; movabs r11; call *r11`）+ naked function（`push rbp; mov rsp,rbp; sub rsp,256; and rsp,-16`）在 anchor 发布点把 BufferBlob frame 暴露给 HotSpot：
   - `last_Java_sp = rbp + 8`
   - `last_Java_pc = *(rbp + 8) = thunk_after_call_pc`
   - `last_Java_fp = *(rbp + 0) = thunk_rbp_value`
   - BufferBlob `_frame_size = 3`（24 字节）

`sender_for_compiled_frame` on BufferBlob：
- `sender_sp = unextended_sp + frame_size*8 = (rbp + 8) + 24 = rbp + 32`
- 设 `T_entry = rsp at thunk entry`，则 `naked_rbp = T_entry - 24`，`rbp + 32 = T_entry + 8`。
- `sender_pc = *(sender_sp - 8) = *(T_entry)`

对 Path 2，由于 c2i adapter 把 `post_call_pc` 重新 push 到了 `T_entry`：`*(T_entry) = post_call_pc`（在 accept nmethod 内部），`find_blob` 命中 accept nmethod，于是 BufferBlob → accept 这一步**成功**（这就是为什么 hs_err 的链条里能看到 `J 790 c2 accept`）。

接下来 `sender_for_compiled_frame` 在 accept 上：
- `sender_sp_accept = accept._sp + accept_frame_size * 8`
- 但**我们告诉 HotSpot 的 `accept._sp = T_entry + 8`，而 accept 的真实 `_sp = caller_pre_call_rsp = T_entry + extraspace + 8`**，差 `extraspace` 字节。
- 因此 `sender_sp_accept` 落在错误位置；该位置正好读到 NULL（accept_pre_rsp + N*8 - 8 - extraspace 在我们 fastdebug core 实测里就是 0）。
- `frame(sender_sp, unextended, *fp_addr, NULL)` 触发 `assert(pc != nullptr, "no pc?")`。

### Core dump 实测数据（hs_err_fd_pid4119478 / fastdebug core /tmp/neko_fd_core）

JavaThread "main"（GC 期间被走的线程）的 anchor：
```
{_last_Java_sp = 0x7f95d44e0110,
 _last_Java_pc = 0x7f9598029691,    // BufferBlob 内 `pop rbp; ret`
 _last_Java_fp = 0x7f95d44e0120}
```

`naked_rbp` 周围 stack：
```
0x7f95d44e0110:  0x00007f95d44e0120  0x00007f9598029691   ; rbp+0(thunk_rbp), rbp+8(after-call PC)
0x7f95d44e0120:  0x00007f95d44e0180  0x00007f95bf81f248   ; rbp+16(saved old rbp), rbp+24(post_call_pc)
0x7f95d44e0130:  0x000000054b4da2f8  0x00007f95d44e0138   ; rbp+32(arg slot 0), rbp+40
```

`*(naked_rbp + 24) = 0x7f95bf81f248`（解释器 invoke_return_entry codelet PC，this run 的 main 线程其实是经解释器调用我们的 method，但 GC 失败 case 是另一次 microbench 中 c2 编译调用 Path 2）。

### 已尝试且无效的方案（按出现顺序）

#### 方案 A：原始 thunk anchor（`sp=B+8, pc=8(B), fp=*B, frame_size=3`）
- 现状代码即为此方案。
- 结果：interpreter / call_stub / Path 1 (compiled→our_c2i_thunk) **正常**，唯独 Path 2 (compiled→hotspot_c2i_adapter→our_i2i) 触发本问题。

#### 方案 B：直接 anchor `fp=16(B), pc=24(B), sp=B+32+totalSlots*8`
- 旧实验，已弃用。
- 结果：bogus C frame after Lambda.apply。

#### 方案 C：直接 anchor `fp=16(B), pc=24(B), sp=%r13`
- 旧实验，已弃用。
- 结果：`oopMap.inline.hpp:124 missing saved register, oops reg: rbp`。

#### 方案 D：synthetic thunk anchor `sp=%r13-24, pc=8(B), fp=*B, frame_size=3`
- 旧实验，已弃用。
- 结果：早期 SIGSEGV in `Unsafe.copyMemory` / `lambda$static$0`。

#### 方案 E：在 `%r13[-16]` / `%r13[-8]` staging
- 旧实验，已弃用。
- 结果：写坏 VM/interpreter scratch，更早崩。

#### 方案 F：i2i normal return through thunk + synthetic anchor
- 旧实验，已弃用。
- 结果：TEST 不干净退出，obfusjack 更早崩。

#### 方案 G：仅 patch `_from_compiled_entry`
- 已经做了，并 verify `NEKO_PATCH_DEBUG=1` 显示 patch 生效。
- 不足：不能阻止 HotSpot 已经 cache 的 c2i adapter 跳到 `_i2i_entry`（即 Path 2），因此本问题还在。

#### 方案 H（本次新增 / 已 REVERT）：i2i naked 改 `last_Java_sp = r13 - 24`
- 假设：`r13` 在所有上层路径都是 caller 的 sender_sp（HotSpot 源码：interpreter `prepare_to_jump_from_interpreted: lea r13,[rsp+8]`；call_stub `mov r13, rsp`；c2i adapter `lea r13,[rsp+8]; pop; sub; push; jmp`）。
- 改动后用 fastdebug + core dump 实测：
  - `last_Java_sp = 0x7f95d44e0110`，由 `r13 - 0x18` 得 `r13 = 0x7f95d44e0128`。
  - 由 thunk_rbp 反推 `T_entry = naked_rbp + 24 = 0x7f95d44e0128`。
  - **r13 == T_entry**，并不是源码暗示的 `T_entry + 8`。差 8 字节的来源未定位（thunk + naked prologue 都没碰 r13）。
- 结果：
  - GC walker 路径：obfusjack 跑得更远（过 `Files.mismatch index=2`，进入 Microbench）。
  - 同步路径：`jni_FindClass → security_get_caller_class → frame::sender_for_compiled_frame` 命中 `assert(cb != nullptr)`（codeCache.inline.hpp:49）。`sender_pc = *(sender_sp - 8) = stack address` 不在 CodeCache 中。
- 结论：**与 OLD 方案各自破坏一种 walker**，已 REVERT。

#### 方案 I（本次新增 / 理论分析后弃用）：BufferBlob `_frame_size` 按签名加上 extraspace
- 想法：`_frame_size = (extraspace_per_sig + 24) / 8`，其中 `extraspace = align_up(args_total_slots * 8, 16)`。`sender_sp = (rbp + 8) + frame_size*8 = T_entry + extraspace + 8 = caller_pre_call_rsp` ✓ for Path 2。
- 解释器路径分析：`*(T_entry + extraspace) = args 区上方的 operand stack`，**不是 PC**，`find_blob` 返回 NULL → assert。
- call_stub 路径：同样落到 push args 循环之上的 stack，**不是 PC**。
- 结论：除非能在运行时区分调用路径，否则 fix 一边坏另一边，弃用。

### Path 2 与其他路径的不可区分性

| 路径 | r13 含义 | T_entry 与 caller_pre_rsp 关系 | `*(T_entry)` 内容 | `*(T_entry+8)` 内容 |
| --- | --- | --- | --- | --- |
| 解释器 invokestatic | sender_sp = X+8（源码） / X（empirical） | `T_entry = X`（X = rsp 推完 return PC） | invoke_return_entry codelet PC | 第一个 arg slot 值 |
| call_stub | rsp_at_call（源码） | `T_entry = rsp_at_call - 8` | call_stub post_call_pc | call_stub args 起始 |
| c2i adapter（Path 2） | caller_pre_call_rsp | `T_entry = caller_pre_call_rsp - extraspace - 8` | post_call_pc（adapter 重新 push） | adapter 写入的第一个 interpreter arg slot |
| 直接 c2i thunk（Path 1，**走 c2i naked**，不在本问题范围） | undefined | `T_entry = caller_pre_call_rsp - 8` | post_call_pc | caller stack |

观察到的不可区分性：
- 在 Path 2 与解释器路径里 `*(T_entry)` 都是合法 PC（虽然语义不同：一个是 compiled accept 的 post_call_pc，一个是 interpreter codelet 的 return entry）。
- `*(T_entry + 8)` 都是 arg-like 值，不能用来 fingerprint。
- `r13` 实测上不严格等于源码所声称的 `T_entry + 8`，因此把 `r13` 当作 caller_sp 不可靠。

### 下一步候选（**未尝试**，仅记录）

1. **Disasm HotSpot 的实际 invokestatic codelet**：找到 `prepare_to_jump_from_interpreted` 的 5 字节 `lea r13, [rsp+8]` 编码（`4C 8D 6C 24 08`），确认它真的存在；如果不存在，看看 HotSpot 实际生成的是什么——这会解释 r13 = T_entry 的 8 字节差。
2. **Path 2 fingerprint via memory pattern**：比较 `*(T_entry)` 与 `*(T_entry + extraspace + 8)`。Path 2 下两者都是 post_call_pc（`pop rax` 不抹原地址，`push rax` 把同一值写到新 slot）；解释器/call_stub 下两者不同。如果该比较稳定，可在 i2i naked 用一段 inline asm 检测 → 决定 anchor sp。
3. **Replace HotSpot 的 c2i adapter for our method**：找到 `Method::_adapter` 字段，构造一份指向我们 c2i thunk 的 `AdapterHandlerEntry`，让 stale call site 直接落到我们的 c2i naked function（args 已在 compiled regs，无 extraspace shift）。需要 VMStructs 暴露 `Method::_adapter` 偏移 + `AdapterHandlerEntry` 布局；如果允许 reflection-style metadata，性价比较高。
4. **完全绕过 BufferBlob walker**：把 anchor 设成直接指向 Java caller frame（pc = `*(T_entry)`，sp = `T_entry + 8 + extraspace_dynamic`，fp = `*(naked_rbp + 16)`），但需要可靠的 extraspace_dynamic（见候选 2）。
5. **GDB 单步 c2i adapter**：在 fastdebug 下 `b sharedRuntime_x86_64.cpp:gen_c2i_adapter` 然后 stepi 跑一次，记录 `r13` 的实际写入位置。这是最直接的验证手段，仍未做（每次都需要复现到 Microbench → GC 触发的瞬间，时间成本高）。

### 复现命令

```bash
# release JDK，10/11 测试通过
rm -f neko-test/build/test-native/obfusjack-native.jar
./gradlew :neko-test:test --tests "NativeObfuscationIntegrationTest.nativeObfuscation_obfusjack_reachesCompletion" -q

# fastdebug 复现，留下 hs_err + core
mkdir -p /tmp/neko_fd
ulimit -c unlimited
$(pwd)/tmp/openjdk-jdk21u/build/linux-x86_64-server-fastdebug/images/jdk/bin/java \
    -XX:ErrorFile=/tmp/neko_fd/hs_err_%p.log \
    -jar neko-test/build/test-native/obfusjack-native.jar
coredumpctl dump <pid> -o /tmp/neko_fd_core

# GDB 取证
$(pwd)/tmp/openjdk-jdk21u/build/linux-x86_64-server-fastdebug/images/jdk/bin/java
gdb --batch \
    -ex "set pagination off" \
    -ex "p ((JavaThread*) 0x<main thread addr from hs_err>)->_anchor" \
    -ex "x/16gx 0x<naked_rbp>" \
    $JAVA /tmp/neko_fd_core
```

---

## P5 — [已修复 2026-04-27] microbench 阶段 `IntStream.range(0,50000).mapToObj(::lambda$microbenchThreads$9).toList()` 少 1 个元素 + 同一时刻 fastdebug deopt 断言失败

### 症状

release JDK 21.0.10:
```
--- Microbench: Platform vs Virtual Threads ---
Platform threads: 84 ms  (50000 tasks)
Exception in thread "main" java.lang.IllegalStateException: End size 49999 is less than fixed size 50000
    at java.base/java.util.stream.Nodes$FixedNodeBuilder.end(Nodes.java:1241)
    at java.base/java.util.stream.Sink$ChainedInt.end(Sink.java:293)
    ...
    at java.base/java.util.stream.ReferencePipeline.toArray(ReferencePipeline.java:622)
    at java.base/java.util.stream.ReferencePipeline.toList(ReferencePipeline.java:627)
```

fastdebug JDK 21u（同一测试位置）：
```
Internal Error (stackValue.cpp:145)
assert(oopDesc::is_oop_or_null(val)) failed: bad oop found at 0x00007f0bee4df360 in_cont: 0 compressed: 0
V  [libjvm.so+0x18510bb]  StackValue::create_stack_value_from_narrowOop_location(...)+0x38b
V  [libjvm.so+0x1853765]  StackValue::create_stack_value<RegisterMap>(...)+0x395
V  [libjvm.so+0x1a5c91d]  compiledVFrame::create_stack_value(ScopeValue*) const+0x10d
V  [libjvm.so+0x1a5dde0]  compiledVFrame::expressions() const+0x130
V  [libjvm.so+0x1a5888d]  vframeArrayElement::fill_in(compiledVFrame*, bool)+0x68d
V  [libjvm.so+0x1a5a2d6]  vframeArray::fill_in(...)+0x66
V  [libjvm.so+0x1a5a4da]  vframeArray::allocate(...)+0xfa
V  [libjvm.so+0xb36af9]  Deoptimization::create_vframeArray(...)+0x139
V  [libjvm.so+0xb373c2]  Deoptimization::fetch_unroll_info_helper(...)+0x572
V  [libjvm.so+0xb3a83f]  Deoptimization::uncommon_trap(...)+0x2f

Java frames:
v  ~UncommonTrapBlob 0x00007f0bd7937e41
J 1004 c2 java.util.stream.IntPipeline$1$1.accept(I)V
j  java.util.stream.Streams$RangeIntSpliterator.forEachRemaining
... ForkJoinTask chain ...
```

### 已知

- `lambda$microbenchThreads$10` 字节码：用 `IntStream.range(0, totalTasks=50000).mapToObj(this::ld9).toList()` 创建 Callable 列表。`ld9` = `lambda$microbenchThreads$9(int)` 静态返回 Callable。
- 对应的 obfuscated method 签名 `(I)Ljava/util/concurrent/Callable;` static → totalSlots=1, total_args_passed=1, extraspace_bytes=16, extraspace_words=2。
- accept(I)V 是 java.base 内 stream sink，C2 编译。它 invokedynamic apply 里跳到我们的 trampoline（Path 2）。
- Path 2 naked 没有 ref 参数（int 是基础类型），所以本签名只走 stash 不走 spill；stash 写到 `rbp + 32` = T_entry+8 = int 槽。dispatcher 早已把 int 值 load 到 rcx，stash 覆盖它无害。返回值 (Callable) 通过 `%rax` + `-8(%rbp)` 槽保存；目前未发现该槽与 stash/spill 冲突。
- 在 release 上不崩，只是丢了一项；fastdebug 直接挂在 `compiledVFrame::expressions`/`StackValue::create_stack_value_from_narrowOop_location`。这意味着 accept 内某个被 OopMap 标为 narrowOop 的栈槽，被读出来不是合法 oop。
- 之前 P1 的两次崩（`assert(pc != nullptr)` 和 `Universe::is_in_heap`）都是“GC walker → BB → accept”路径上的；P5 是 `Deoptimization` 路径，accept 自己运行时遇到 uncommon trap 才触发。两者都在同一段 microbench，且在同一行 `IntPipeline$1$1.accept` 上看到，但执行轨迹不同。

### 候选解释（**未验证**）

1. accept 在 deopt 时使用自己的 runtime 状态读 expression 区；如果该区某槽因为我们 trampoline 之前一次执行 corrupt 了 caller 一直没察觉的某个变量（例如 boxed Integer 的某个野值），后续走到这个 PC 时 assertion 失败。
2. 我们的 spill 区在 `-32(%rbp)` 之后一连串负偏移；如果因为某段 inline asm 的 `subq $256` 后再做了一次 `andq $-16` 让 rsp 漂移到 spill 区上方（理论上不会），spill 写就有可能落在 caller 真正还在用的栈区。需要拿 release dump 验证 `naked_rbp - 32` 这段确实在 `subq $256` 里。
3. 并行 stream 内部用 ForkJoinPool，多个 worker 同时跑 accept→our_trampoline。我们的 anchor / spill / stash 都依赖 per-thread `r15=JavaThread*` 与 per-thread stack，**理论上**没有共享状态；但 `g_neko_priv_thunks[]`（一个 process-wide 数组）是单线程在 `JNI_OnLoad` 阶段填充的，run 期间只读，应该 race-safe。再次确认。

### 复现

```bash
# release JDK
rm -f neko-test/build/test-native/obfusjack-native.jar
./gradlew :neko-test:test --tests "NativeObfuscationIntegrationTest.nativeObfuscation_obfusjack_reachesCompletion" -q

# fastdebug
mkdir -p /tmp/neko_fd
ulimit -c unlimited
$(pwd)/tmp/openjdk-jdk21u/build/linux-x86_64-server-fastdebug/images/jdk/bin/java \
  -XX:ErrorFile=/tmp/neko_fd/hs_err_%p.log \
  -jar neko-test/build/test-native/obfusjack-native.jar
```

### 未尝试方向

- 在 release 下用 `-XX:+UnlockDiagnosticVMOptions -XX:+TraceDeoptimization -XX:+PrintCompilation` 看 accept 在哪一处被 deopt；如果 PC=某个具体 bci，对照 accept 的 OopMap 找出哪个 stack slot 被认错。
- 把 spill 区抬高到 `(rsp + 200)` 以下 absolute offsets，确保不和 `subq $256` reserved 区交错。
- 临时把 `Method::_i2i_entry` 还原成 path-1 thunk（即 `t_i2i_interp = t_i2i_path2`），看 P5 是否消失（用以确认是 Path 2 stash/spill 的副作用，而不是 Path 1 / 解释器路径本身的回归）。

---

## P2 — `r13` 寄存器在我们 i2i naked function 入口处与 HotSpot 源码预期不一致（empirical vs theoretical）

### 症状

依赖 r13 = HotSpot caller 的 sender_sp 来计算 anchor sp 的方案（P1 的方案 H）实测失败。

### Empirical 取证（fastdebug, core dump）

- `last_Java_sp = anchor_sp = r13 - 0x18 = 0x7f95d44e0110`
- 由 thunk_rbp_value（= `*(naked_rbp + 0) = T_entry - 8 = 0x7f95d44e0120`）反推 `T_entry = 0x7f95d44e0128`
- 因此 `r13 = T_entry`

### HotSpot 源码（jdk21u）

- `interp_masm_x86.cpp:812` `InterpreterMacroAssembler::prepare_to_jump_from_interpreted`：
  ```cpp
  lea(_bcp_register, Address(rsp, wordSize));
  ```
  其中 `_bcp_register = r13`（LP64），`wordSize = 8` → `lea r13, [rsp + 8]`，所以 `r13 = T_entry + 8`。
- `stubGenerator_x86_64.cpp:309` `generate_call_stub`：`__ mov(r13, rsp); __ call(c_rarg1);` → `r13 = rsp_pre_call = T_entry + 8`。
- `sharedRuntime_x86_64.cpp:638` `gen_c2i_adapter`：`__ lea(r13, Address(rsp, wordSize));` 然后 pop/sub/push → `r13 = caller_pre_call_rsp`。

按源码逻辑 `r13` 在 Path 2 是 `caller_pre_call_rsp`，在 interpreter / call_stub 是 `T_entry + 8`。但 empirical 给的是 `T_entry`，少 8 字节。

### 已审核但未发现的 r13 写入

- thunk 字节流（`MethodPatcherEmitter.java:875-882`）：`55 48 89 e5 49 bb XX*8 41 ff d3 5d c3 cc...` 不动 r13。
- naked function prologue（manifest 扫描、alias 扫描、setup dispatcher args、anchor publication 前）：用 `r10/r11/eax/rdi/rsi/rdx/rcx/r8/r9/rbx/r15`，**不动 r13**。
- 反汇编 verify（`/tmp/neko_fd_core` GDB `x/40i 0x<naked_entry>`）：anchor 发布点 `lea r11, [r13 - 0x18]; mov [r15 + r10], r11` 之前没有任何 `mov r13, ...` / `pop r13` / `add r13, ...`。

### 最可能的解释（**未验证**）

1. HotSpot 源码与我们调用的实际 codelet/stub 不一致：jdk21u 在 release/fastdebug build 中 `prepare_to_jump_from_interpreted` 实际生成的是 `lea r13, [rsp]`（无 +8）。需要 GDB disasm 实际的 invokestatic codelet 来确认。
2. `Address(rsp, wordSize)` 在某条 macro 重载里被解释为 `[rsp + 0]`（不太可能，但 MacroAssembler 重载多）。
3. 进入路径不是 prepare_to_jump_from_interpreted，而是别的 path，例如 `invokebasic` / `linkToStatic` / MethodHandle 内部的 LambdaForm bytecode 直接 jmp 到 _i2i_entry，没有走标准 invoke 模板。

### 验证方法（**未做**）

- GDB `b *0x<thunk_entry>` 在 fastdebug 复现，捕一次 r13；同时 `bt` 看真实调用方。
- 或者用 `find /b 0x7f95bf800000, 0x7f95cf800000, 0x4c, 0x8d, 0x6c, 0x24, 0x08`（`lea r13, [rsp+8]` 字节码）确定它在 interpreter codelet 区是否存在。本次 search 未找到，但搜索 range 可能不全。

---

## P3 — 跨平台 trampoline 仅完成 Java 代码层

### Windows x64

- `X86_64WindowsTrampoline.java`：i2i + c2i 都已写。GP `rcx/rdx/r8/r9`，FP `xmm0..3`，shadow 32 字节，callee-saved `rbx/rsi/rdi/rbp`。anchor / thread state / safepoint poll 与 SysV 一致。
- **未验证**：需要 MinGW/Clang 在 Windows 物理机/CI 上 build `libneko.dll`，然后跑 `:neko-test:test`。本次未跑。
- 兼容性疑虑：
  - `__attribute__((naked, used, visibility("hidden")))` 在 MinGW 下行为；
  - `__asm__ volatile (... : : : "memory")` 在 MS ABI 下 r10/r11 是否真的 caller-saved 不被调用方破坏；
  - `g_neko_off_*` 通过 `(%%rip)` 寻址在 PE/COFF 下是否生成正确的 RIP-relative reloc。

### AArch64 SysV (Linux ARM64)

- `Aarch64SysVTrampoline.java`：i2i + c2i 都已写。HotSpot AArch64 interpreter 约定 `x12=Method*, x19=sender_sp, x20=esp, x28=rthread, x29=rfp`；args 从 `[esp - (slot+1)*8]` 取；anchor 用 `adrp+ldr` 装 `g_neko_off_*` 偏移再写 `[x28, off]`；thread state `dmb ish` 替代 `mfence`；safepoint poll `bl neko_handle_safepoint_poll`。i2i return 经两个 `ldp x29, x30, [sp], #16` 剥栈帧后 `mov sp, x19; ret`。
- **未跑通**：`MethodPatcherEmitter.java:875-882` 的私有 thunk 字节流仍是 x86_64 specific (`55 48 89 e5 49 bb XX*8 41 ff d3 5d c3 cc...`)。AArch64 上需要替换为：
  ```
  stp x29, x30, [sp, #-16]!     ; A9BF7BFD
  mov x29, sp                   ; 910003FD
  movz x16, #imm0, lsl #0       ; D2800010
  movk x16, #imm1, lsl #16      ; F2A00010
  movk x16, #imm2, lsl #32      ; F2C00010
  movk x16, #imm3, lsl #48      ; F2E00010
  blr x16                       ; D63F0200
  ldp x29, x30, [sp], #16       ; A8C17BFD
  ret                           ; D65F03C0
  ```
  共 9 条指令，36 字节，需要把 thunk_bytes 从 32 字节调到至少 40 字节并按段大小对齐。
- 任务：`MethodPatcherEmitter` 引入 `arch_thunk_bytes()` 抽象，按 `#if defined(__aarch64__)` 选用不同字节流；同时把 `BufferBlob._frame_size` 调整为对应的 word 数（AArch64 上 fp+lr 占 2 word）。
- VMStructs / BufferBlob vtable harvest 在 AArch64 上**未验证**：HotSpot 的 `_BufferBlob` vtable 偏移、`_frame_size` 单位（word）应该一致，但 ARM 上 word size 仍是 8 字节。

---

## P4 — 单元测试 `OpcodeTranslatorUnitTest.opcodeTranslator_returnsAndNoopEmitTerminalStatements` 已跟上 shadow-stack lowering（**已修**）

- 之前 expected `return POP_I();` 等纯 C 形式，但 `OpcodeTranslator` 现在为 IRETURN/LRETURN/RETURN 生成 `{ jint __ret = POP_I(); neko_shadow_pop(); return __ret; }` 之类的形式（commit `e4a3cab fix(native): model translated reflection stack semantics` 引入）。
- 修复：把 expected 字面量同步成实际生成的 snippet。23/23 单元测试通过。
- commit: `60869e3 docs(native): record r13 anchor investigation; fix translator unit test`

---

## 当前测试矩阵

| 用例 | release JDK 21.0.10 | fastdebug 21.0.5 |
| --- | --- | --- |
| no JVMTI / no agent（grep audit） | ✅ | ✅ |
| no native keyword | ✅ | ✅ |
| no helper classes | ✅ | ✅ |
| native library resource | ✅ | ✅ |
| `nativeObfuscation_TEST_calcUnder150ms` | ✅ | ✅ |
| `nativeObfuscation_SnakeGame_headlessExceptionOnly` | ✅ | ✅ |
| `nativeObfuscation_obfusjack_reachesCompletion` | ❌ Microbench GC walker | ✅ 跑到 “=== All tests completed ===” |
| `OpcodeTranslatorUnitTest`（23 tests） | ✅ | ✅ |
| Windows x64 build → run | ❓ 未跑 | ❓ |
| AArch64 build → run | ❓ thunk byte 仍是 x86_64 | ❓ |

---

## 关键文件指纹

| 文件 | 行数 | 角色 |
| --- | --- | --- |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/X86_64SysVTrampoline.java` | ~610 | 当前 P1 / P2 的核心问题所在 |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/X86_64WindowsTrampoline.java` | ~480 | P3 Windows |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/Aarch64SysVTrampoline.java` | ~490 | P3 AArch64 |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/MethodPatcherEmitter.java` | ~1200 | thunk 字节、BufferBlob 注册、Method entry patch、JNIHandleBlock _last 修复 |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java` | ~3500 | runtime helpers、HotSpot probing、intrinsics |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java` | ~5000 | intrinsics（Throwable.getStackTrace、Method.invoke、MethodHandles.lookup） |
| `tmp/openjdk-jdk21u/src/hotspot/cpu/x86/frame_x86.inline.hpp:135` | – | `assert(pc != nullptr, "no pc?")` 的 4-arg ctor |
| `tmp/openjdk-jdk21u/src/hotspot/cpu/x86/frame_x86.inline.hpp:392-441` | – | `sender_for_compiled_frame` 实现 |
| `tmp/openjdk-jdk21u/src/hotspot/cpu/x86/sharedRuntime_x86_64.cpp:607-755` | – | `gen_c2i_adapter` 实现 |
| `tmp/openjdk-jdk21u/src/hotspot/cpu/x86/interp_masm_x86.cpp:812-841` | – | `prepare_to_jump_from_interpreted`、`jump_from_interpreted` |
| `tmp/openjdk-jdk21u/src/hotspot/cpu/x86/stubGenerator_x86_64.cpp:308-310` | – | call_stub 的 `mov r13, rsp; call` |

---

## 不再需要重复尝试的红线

- 不要在 i2i 的 `sp=B+8`、`sp=%r13-24`、`sp=%r13-16`、`sp=B+32+slots` 之间盲目来回切。所有这些都被 GDB core 取证过（见 P1 方案 A/D/H）。
- 不要写 `%r13[-16]` / `%r13[-8]`（方案 E 已破坏 VM scratch）。
- 不要把 i2i 改成 normal `ret` through thunk（方案 F 已破坏 TEST 干净退出）。
- 不要相信 `_from_compiled_entry` patch 一定让 compiled caller 走 Path 1 —— stale call site 仍会走 HotSpot 的 c2i adapter（→ Path 2）。
- 不要用 fallback / exclusion / JVMTI / Java loader helper 绕过问题（违反硬约束）。
- 不要往 `neko-runtime` 添加任何 bootstrap/bind/link helper（`onlyNekoNativeLoaderInjected` 测试是硬约束）。
