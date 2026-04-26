# NekoObfuscator — Native Patcher Current TODO

> 更新日期：2026-04-26  
> 本文件记录当前 main 工作树的真实状态、已完成部分、失败尝试、假设和禁止重复试错的结论。

## 用户硬约束

- [x] **不使用 `native` 关键字 / 不设置 `ACC_NATIVE`。** 被混淆方法保留普通 Java 方法形态，Java body 是 `throw new LinkageError("please check your native library load correctly")`。
- [x] **`<clinit>` 只允许调用 `NekoNativeLoader.load()`。** 不允许 `nekoBootstrap`、`bindClass`、`nekoBindClass` 或其他 Java 层 bootstrap API 暴露。
- [x] **不生成 bridge/helper 方法。** 不能因为 native 化引入额外 Java 栈帧。
- [x] **不注入额外 runtime helper class。** 当前只保留 `dev/nekoobfuscator/runtime/NekoNativeLoader.class`。
- [x] **运行时逻辑迁移到 native C 层。** HotSpot probing、Unsafe/VM option/array/field offset helpers、manifest discovery 等均在 `JNI_OnLoad` / native runtime 内完成。
- [x] **HotSpot 集成走 VMStructs。** 通过 `gHotSpotVMStructs` / `gHotSpotVMTypes` / `gHotSpotVMIntConstants` 解析 `Method`、`JavaThread`、`CodeCache`、`CodeHeap`、`CodeBlob`、`JNIHandleBlock` 等布局。
- [x] **不使用 JVMTI。** 全仓库审计通过：`grep -rEn "jvmti|JVMTI|Agent_OnLoad|Agent_OnAttach|Agent_OnUnload|jvmtiEnv|jvmtiCapabilities|jvmtiEventCallbacks|JVMTI_VERSION|premain|agentmain|jdk\.internal\.agent"` 在所有模块 `src/` 树下零命中。Native runtime 只用 JNI（`JNI_OnLoad`、`GetEnv(JNI_VERSION_1_6)`、`AttachCurrentThread`）+ VMStructs 走 HotSpot 内部布局；不存在 `Agent_OnLoad`、`-agentlib`、`jvmtiEnv*` 任何形态。
- [ ] **三个 test jar 100% native 覆盖且行为一致：** `TEST.jar`（✅）、`SnakeGame.jar`（✅）、`obfusjack-test21.jar`（❌）。obfusjack 已越过 `oopMap.inline.hpp:124 missing saved register oops reg: rbp` guarantee 失败，但仍在 microbench 阶段触发新 SIGSEGV（详见下文）。
- [ ] **跨平台完成：** Windows x64 与 AArch64 SysV 的 trampoline Java 端已写出并编译通过；i2i + c2i 都做完了 anchor publication / thread state / safepoint poll / 与 SysV 一致的 thunk-aware synthetic frame。**剩余依赖**：`MethodPatcherEmitter` 中私有 CodeHeap 的 thunk 字节仍是 x86_64 (`push rbp; mov rsp,rbp; movabs r11; call *r11; pop rbp; ret`)，AArch64 后端运行时需要把它替换成 `stp x29,x30,[sp,#-16]!; mov x29,sp; movz/movk x16,…; blr x16; ldp x29,x30,[sp],#16; ret` 的 8-byte instruction stream。Windows 上 thunk 字节复用 SysV，但生成 libneko.dll 还需要 MinGW / Clang 等支持 `__asm__ volatile (…)` 的工具链，并且 `g_neko_off_*` 等全局符号链接方式不变。

## 已完成并验证过的部分

- [x] no-native-keyword 输出形态：方法不带 `ACC_NATIVE`，Java body 抛固定 `LinkageError`。
- [x] runtime loader 最小化：`NekoNativeLoader` 只负责抽取 native library 并 `System.load`，不暴露 bootstrap/bind API。
- [x] 删除/避免 JVM 层 helper：`noHelperMethodsInOutput`、`onlyNekoNativeLoaderInjected` 曾通过。
- [x] native manifest discovery：native `JNI_OnLoad` 中发现并 patch `Method*`，包括 custom loader `defineClass` 后 Method* alias 处理。
- [x] `ClassLoader.defineClass` hook / alias table：解决自定义 loader 场景下 Method* 不一致问题。
- [x] `Throwable.getStackTrace()` native shadow-stack intrinsic：用于 TEST ReTrace 栈语义。
- [x] `java.lang.reflect.Method.invoke(Object,Object[])` intrinsic：通过 JDK private adapter `Method.invoke(Object,Object[],Class)` 模拟反射栈语义。
- [x] `MethodHandles.lookup()` static intrinsic：修复 obfusjack 中无 caller 的 lookup 失败。
- [x] native build compile 问题：C forward declarations、字符串 `\0` escaping 等已修复。
- [x] SharedRuntime stale `JavaFrameAnchor` 早期崩溃已修复：进入 native 后正确发布/清理 anchor，避免 `resolve_static_call` 看到陈旧 Java frame。
- [x] 私有 CodeHeap / BufferBlob 机制：可注册私有 CodeHeap，分配合成 BufferBlob thunk，`find_blob_at(thunk_pc)` 可识别。
- [x] x86_64 SysV i2i/c2i trampoline 基础模板存在：包含 state transition、anchor publication、dispatcher 调用、return value 保存/恢复。
- [x] c2i Java GP 参数顺序修正为 HotSpot 编译调用约定：`rsi, rdx, rcx, r8, r9, rdi`。
- [x] c2i stack-spilled Java 参数偏移修正为 thunk/c2i frame 下的 `[B+32+n*8]`。
- [x] `_from_compiled_entry` patch 逻辑已加入：会为 c2i 分配 CodeHeap thunk 并写入 `Method::_from_compiled_entry`。

## 当前工作树状态（重要）

- `X86_64SysVTrampoline.java`：i2i 与 c2i 现在共用同一份 **synthetic BufferBlob anchor**：
  - `last_Java_fp = *(rbp)`（thunk 保存的 caller fp 槽地址）
  - `last_Java_pc = 8(rbp)`（thunk `pop rbp; ret` 的 PC，指向 BufferBlob 内部）
  - `last_Java_sp = rbp+8`（thunk `call` 推入的 return PC 槽地址）
  - i2i return tail **保持原样**：`mov rsp,rbp; pop rbp; movq 16(rsp),%r10; movq (rbp),rbp; mov %r13,rsp; jmp *%r10`，绕开 thunk 的 `pop rbp; ret`，直接通过 `r13` 回到 interpreter 的 invoke-return-entry。
  - c2i 仍是 **normal `ret` through thunk**，由 thunk `pop rbp; ret` 回到 compiled caller。
  - 关键差异（相对回退前）：i2i 不再发布 direct caller anchor。这是历史 D/E 与禁忌列表所禁的“sp=…”切换之外，第一次把“**anchor 形态**”和“**return protocol**”分开调整 —— 不属于禁忌列表 F（F 是同时改 anchor + return）。
- `X86_64WindowsTrampoline.java`：i2i + c2i 都已实写。GP regs 用 `rcx,rdx,r8,r9`，每个 FP arg 同时占用 GP shadow slot，stack 起点 `[rsp+32]`，callee-saved `rbx/rsi/rdi/rbp` 全部 push/pop。anchor 与 thread state 与 SysV 完全一致。
- `Aarch64SysVTrampoline.java`：i2i + c2i 都已实写。HotSpot AArch64 interpreter convention 走 `x12=Method*`、`x19=sender_sp`、`x20=esp`、`x28=rthread`、`x29=rfp`，args 从 `[esp - (slot+1)*8]` 取；anchor 用 adrp+ldr 装 `g_neko_off_*` 偏移再写入 `[x28, off]`；thread state 用 `dmb ish` 替代 mfence；safepoint poll 通过 `bl neko_handle_safepoint_poll`。i2i return 通过两个 `ldp x29,x30,[sp],#16` 依次剥离 naked + thunk 两层栈帧后 `mov sp, x19; ret`。
- `MethodPatcherEmitter.java`：private thunk 是 return-capable x86_64 字节序列：
  - `push rbp; mov rsp,rbp; movabs real,%r11; call *%r11; pop rbp; ret; int3...`
  - `BufferBlob::_frame_size = 3`，`_frame_complete_offset = 4`
  - **AArch64 上仍需要把这段 byte literal 替换为 AArch64 thunk**（`stp x29,x30,[sp,#-16]!; mov x29,sp; movz/movk x16,…; blr x16; ldp x29,x30,[sp],#16; ret`）。当前架构条件分支只覆盖 x86_64。

## 当前真实阻塞问题

### 已修复（debug-jdk 验证）

**1. JNIHandleBlock `_last == NULL` 解引用（fastdebug 直接捕获到 SIGSEGV at `JNIHandleBlock::allocate_handle+0x40`）**

- 自建 OpenJDK 21u fastdebug build（`tmp/openjdk-jdk21u/build/linux-x86_64-server-fastdebug/images/jdk`），用 `addr2line` + objdump 把 release `libjvm.so+0x10786c0` 解析到 `_ZN14JNIHandleBlock15allocate_handleEP10JavaThread3oopN17AllocFailStrategy13AllocFailEnumE+0x40`，对应 `jniHandles.cpp:451` 的 `_last->_top` 读取。
- 根因：`neko_handle_push` 直接 `handles[top++] = oop` 让 `_top != 0`，但从未同步设置 `_last = this`。HotSpot 在 `_top != 0` 分支跳过「首次分配」初始化，直接解 `_last`（NULL）→ SEGV at `0x100`。
- 修复：`MethodPatcherEmitter.neko_handle_push` 每次 push 都同步 `_last = block`。VMStructs 不导出 `_last`，但 JDK 21 字段顺序稳定（`_top, _allocate_before_rebuild, _next, _last, _pop_frame_link, _free_list`），`_last = _next + 8`。**仅写 `_last`** —— 之前一版同时写 `_pop_frame_link` 和 `_free_list` 把 `PushLocalFrame` 链 / handle 自由表写坏，必须避免。

**2. CodeHeap walk 在 fastdebug 0xCC poison 区越界**
- `neko_walk_codeheap` 之前用 `info.length == 0` 当终止条件，fastdebug 把未分配段填 `0xCC`，length 变成 `0xCCCCCCCC...` 不会停。
- `MethodPatcherEmitter.neko_read_heapblock` 增加 `_used` 字节合法性校验（必须为 0 或 1，否则停），且 `neko_walk_codeheap` 增加「length 不能超过剩余 segments」的越界判断。

### 仍阻塞：obfusjack microbench `sender_for_compiled_frame+0x62f assert(pc != nullptr) failed: no pc?`

fastdebug 复现 `hs_err_fastdebug_pid4007728.log`：
```
Internal Error (frame_x86.inline.hpp:135), assert(pc != nullptr) failed: no pc?
V  [libjvm.so+0x84c1af]  frame::sender_for_compiled_frame(RegisterMap*) const+0x62f
V  [libjvm.so+0xa213c8]  frame::sender_raw(RegisterMap*) const+0x258
V  [libjvm.so+0xf48e6e]  JavaThread::oops_do_frames(...)+0xbe
V  [libjvm.so+0x199bad9]  Thread::oops_do(...)+0x89
V  [libjvm.so+0x19b4bd4]  Threads::possibly_parallel_oops_do(...)+0x1a4
V  [libjvm.so+0xd987ad]  G1RootProcessor::process_java_roots(...)+0x6d
```

栈链：
```
~RuntimeStub::_new_instance_Java
J 749 c2 java.lang.invoke.MethodType.makeImpl
J 784 c2 java.lang.invoke.MethodHandle.invokeWithArguments
~StubRoutines::call_stub
~BufferBlob::neko_trampoline
J 790 c2 java.util.stream.IntPipeline$1$1.accept(I)V
```

GC walker 走到 BufferBlob → `sender_for_compiled_frame`：
- `unextended_sp = anchor.last_Java_sp = naked_rbp + 8`
- `sender_sp = unextended_sp + frame_size*8 = naked_rbp + 32`
- `sender_pc = *(sender_sp - 8) = *(naked_rbp + 24)` → **NULL**
- `frame(sender_sp, ..., NULL)` constructor 触发 `assert(pc != nullptr, "no pc?")`

`naked_rbp + 24` 按设计应是 caller (accept) 的 `call` 指令推入的 return PC。理论上 always non-null：
- 直接解释器调用：`prepare_invoke` 推入 invoke_return_entry table PC
- HotSpot c2i adapter（`sharedRuntime_x86_64.cpp::gen_c2i_adapter`）：先 `pop rax; sub rsp, extraspace; push rax` 保留原 PC

**2026-04-26 GDB core 取证更正**：失败的 transition **不是** BufferBlob → accept（这一步成功，因为 *(naked_rbp + 24) = c2i adapter 重新 push 的 post_call_pc，非 NULL，HotSpot 顺利构造 accept frame），而是 **accept → 它的 sender** 这一步。原因：accept 的 `_sp` 被我们的 anchor 错误地设成了 `T_entry + 8 = caller_pre_call_rsp - extraspace`，导致 `accept_sender_sp = accept._sp + accept_frame_size*8` 落在错误位置。详细取证写在 `problems.md` P1。

— 旧推测“存在第三种入口路径”不正确，根因是 c2i adapter `extraspace` shift 让 `accept._sp` 偏离 `caller_pre_call_rsp` 一个 extraspace。

### 当前阻塞：obfusjack microbench 期间在 GC stack walk 中触发 SIGSEGV / `ShouldNotReachHere` / `assert(pc != nullptr)`

**2026-04-26 SESSION 末尾的最新认知（与 P1 同步）**：
- 真实 root cause：HotSpot 的 c2i adapter 把 rsp 向下偏 `extraspace = align_up(args_total_slots*8, 16)` 后才 jmp 到我们的 `_i2i_entry`（因为 stale call site）。我们的 anchor sp = `naked_rbp + 8` 在 BufferBlob 里使 `accept._sp = T_entry + 8`，但 accept 真实 `_sp = caller_pre_call_rsp = T_entry + extraspace + 8`。差 extraspace 字节，导致 walking accept → its sender 时 `*(sender_sp - 8)` 读到 NULL / 错位。
- 两个可信但都失败的 fix（详见 problems.md P1 方案 H/I）：
  - **H**：anchor sp 用 `r13 - 24`。GDB 实测 r13 = T_entry（**而非源码暗示的 T_entry + 8**），导致 `r13 - 24 = naked_rbp`，`*(sender_sp - 8) = stack 地址`，`jni_FindClass → security_get_caller_class` 在 release & fastdebug 都崩。
  - **I**：每签名定制 BufferBlob `_frame_size = (extraspace + 24) / 8`。c2i adapter 路径 ✓，但解释器 / call_stub 路径 `*(T_entry + extraspace)` 不是 PC 直接 `find_blob` 返回 NULL。

下面这一段保留作历史推测，但 root cause 已经更精确了。



- 复现路径：`Platform vs Virtual Threads` microbench 起步即崩；`MethodHandle.invokeWithArguments → call_stub → ~BufferBlob::neko_trampoline → IntPipeline$1$1.accept` 之后 GC Thread 在 libjvm 内部某个 frame validator 处 NULL 解引用（指令 `cmpl $0x841f0f,(%r14)`，`r14=0`）。
- 该指令在 `libjvm.so+0x2f4ed1..0x2f51xx` 区域，位于一个内部 frame walker 函数（与 `AsyncGetCallTrace`-类签名，其实是 GC 路径里被复用的 walker），会校验候选 PC 处是否为 HotSpot patchable NOP（`0F 1F 84 00`）作为 inline-cache call site fingerprint。
- TEST.jar / SnakeGame.jar 不再退化（仍 ✅）。说明本身 anchor 机制是正确的；只是 obfusjack 的 microbench 触发的某个具体 OopMap 表项或 register tracking 还有遗留问题。
- 关键观察：即便 release `libjvm.so` 已 stripped，hs_err 已能展示 `~BufferBlob::neko_trampoline` 这个 frame name —— 说明我们写入 BufferBlob `_name = "neko_trampoline"` 也被 HotSpot 印出来了。也就是说 `_size`、`_header_size`、`_data_offset`、`_code_begin/_end`、`_content_begin/_data_end` 五个偏移 + vtable 都正确生效。
- 推测方向：
  1. `update_register_map(this, map)` 仅在 `_cb->oop_maps() != NULL` 时调用；我们的 BufferBlob `_oop_maps == NULL` —— 这条不应跑。
  2. 但 GC walker 后续可能依赖 `_unwind_handler_offset` / `_caller_must_gc_arguments` 等次要 flag。memset 0 默认值在大多数 release 路径上无害，但某个新增的 G1/Shenandoah 路径可能要求精确为 false。
  3. 也可能是我们 anchor 构造的 frame 上 `_unextended_sp` 与 `_sp` 在某个 path 出现误用（参见 frame ctor 三参数 vs 四参数）。

### 已确认机制（保留供后续 debug 参考）

- `frame_x86.inline.hpp::sender_for_compiled_frame()` 正常从 compiled callee 走到 caller 时会执行：
  - `update_map_with_saved_link(map, saved_fp_addr)`
  - 这会把 caller 的 saved `rbp` slot 注册进 `RegisterMap`。
- `frame_x86.cpp::sender_for_entry_frame(RegisterMap*)` 对 JavaCall/call_stub entry frame 会：
  - 从 `JavaCallWrapper::_anchor` 取 `last_Java_sp/fp/pc`
  - 调 `jfa->make_walkable()`
  - **调用 `map->clear()`**
  - 然后直接返回 `frame(last_Java_sp, last_Java_fp, last_Java_pc)`
- `javaCalls.cpp::JavaCallWrapper` constructor 会复制当前 `JavaThread::frame_anchor()` 到 wrapper `_anchor`，并清空 thread anchor。

**关键结论：**

- `_from_compiled_entry` 已 patch（`NEKO_PATCH_DEBUG=1` 验证）。
- native C 编译成功。
- TEST loader/reflection/retrace 的老问题已修复且未回归。
- 现在的阻塞是 HotSpot 内部某个 walker 的辅助校验，不是 anchor 本身错。

## 已经尝试过且不要再无意义重复的方案

### A. 原始 thunk anchor：`sp=B+8`, `pc=8(B)`, `fp=*B`, `frame_size=3`

- 目的：让 HotSpot 把 private CodeHeap thunk 当作 walkable BufferBlob frame。
- 结果：obfusjack 进入 microbench 后 `frame.cpp:1158 ShouldNotReachHere`。
- 典型 frames：
  - `~BufferBlob::neko_trampoline`
  - compiled `IntPipeline$1$1.accept(I)V`
  - bogus `C 0x0000000549110c20` / `C 0x0`
- 结论：synthetic frame 可以被识别，但 sender SP/PC 链接不对，走到 caller 后继续变成 bogus C frame。

### B. Direct anchor：`fp=16(B)`, `pc=24(B)`, `sp=B+32+totalSlots*8`

- 目的：绕过 BufferBlob，直接发布真实 Java caller frame。
- 结果：不再显示 `~BufferBlob`，但仍 `frame.cpp:1158`，在 `Main$$Lambda.apply(I)` 后出现 bogus C sender。
- 结论：直接 PC/fp 对了，但 SP 不是 HotSpot 真实 unextended SP；根据 totalSlots 推算会错过 compiled-entry padding/alignment。

### C. Direct anchor：`fp=16(B)`, `pc=24(B)`, `sp=%r13`

- 目的：使用 HotSpot preserved caller/unextended SP。
- 结果：PC chain 正确，不再 bogus PC；但 GC/JavaCall 场景触发 `oopMap.inline.hpp:124 missing saved register`，`oops reg: rbp`。
- 结论：这是目前“最接近正确”的 i2i 形态，但 JavaCallWrapper + `sender_for_entry_frame(map->clear())` 会丢失 `rbp` RegisterMap location。
- 当前代码已回到这个形态，因为它比 normal-return/synthetic i2i 更稳定。

### D. Synthetic thunk anchor：`sp=%r13-24`, `pc=8(B)`, `fp=*B`, `frame_size=3`

- 目的：让 `sender_sp = sp + frame_size = %r13`。
- 结果：早期在 `Unsafe.copyMemory` / `lambda$static$0` 附近 SIGSEGV，Java frames 为 `~BufferBlob` 后接 stack 地址 / `0xcccc...`。
- 结论：`%r13[-1]` 并不保证是 Java return pc；把 `%r13` 当作可反推 return-slot 的地址是不成立的。

### E. 在 `%r13[-16]` / `%r13[-8]` staging saved rbp / Java return pc

- 目的：给 HotSpot compiled-frame walker 准备 `[sender_sp-2]` 和 `[sender_sp-1]`。
- 结果：更早崩溃，Java frames 只剩 raw C stack address；疑似写坏 interpreter / adapter scratch。
- 结论：不能写 `%r13` 下方的“死区”；这些 word 不是安全 scratch。

### F. i2i normal return through thunk + synthetic anchor

- 修改：i2i anchor 改为 `fp=*B`, `pc=8(B)`, `sp=B+8`，返回尾改为 `mov rbp,rsp; pop rbp; ret`，让 return-capable thunk `pop rbp; ret` 回 Java。
- 目的：让 anchor 与真实 call/return chain 一致，并借 synthetic frame seed RegisterMap。
- 结果：
  - TEST 实际输出完整 PASS，但 Java 子进程不干净退出，JUnit 2s timeout。
  - obfusjack 严重回退，在 `Optional=246` 后多线程 SIGSEGV。
  - `hs_err_pid3541437.log` 显示 `RuntimeStub::ic_miss_stub` → compiled `IntPipeline$4$1.accept` → bogus C frames。
- 结论：i2i normal-return/synthetic anchor 会破坏 interpreter/compiled continuation，不可继续重复。

### G. “只 patch `_from_compiled_entry` 到 c2i thunk”不足以解决

- 已完成 `_from_compiled_entry` patch、c2i thunk、c2i Java GP order/stack offset 修正。
- `NEKO_PATCH_DEBUG=1` 证明 patch 成功，相关 microbench methods/lambdas 都 patch 了。
- 但 crash 的 Java frames 中没有 libneko/BufferBlob，说明当前 fatal 是 JavaCall/call_stub 使用复制的 direct anchor 回到 compiled caller 后缺 RegisterMap，而不是 compiled caller 没走 c2i。

## 已尝试且经过 GDB 核心转储验证的假设（继续 debug 时不要重复）

### H. r13-anchored anchor sp（i2i 改 `r13 - 24`）— 验证后 REVERTED

- 改动：i2i naked `last_Java_sp = r13 - 24` 替代原 `naked_rbp + 8`。
- 假设：r13 在调用 i2i thunk 前一定被 caller 设为 sender_sp（HotSpot 源码：`prepare_to_jump_from_interpreted` 做 `lea r13, [rsp + 8]`；call_stub 做 `mov r13, rsp`；c2i adapter 做 `lea r13, [rsp + 8]; pop; sub; push; jmp _i2i_entry`）。
- **GDB core 验证（fastdebug build, /tmp/neko_fd_core2）**：
  - last_Java_fp = `0x7f95d44e0120`（= thunk_rbp_value，由 thunk 的 `push rbp; mov rsp, rbp` 写入）。
  - last_Java_pc = `0x7f9598029691`（= thunk after-call PC）。
  - last_Java_sp = `0x7f95d44e0110`（= 我们写的 `r13 - 0x18`）。
  - 反推：T_entry = naked_rbp + 24 = `0x7f95d44e0128`，r13 = anchor_sp + 24 = `0x7f95d44e0128`。
  - **r13 实际 == T_entry**，并不是 HotSpot 源码看上去的 `T_entry + 8`。差 8 字节的来源还没定位（但 thunk 与 naked prologue 都未触碰 r13）。
- 结果：对 GC walker 路径（compiled accept 经 c2i adapter 进入 i2i）**有所改善**（progress 通过 `Files.mismatch`），但同步路径中 `jni_FindClass` 内部 `JavaThread::security_get_caller_class` 走 `sender_for_compiled_frame` 时读 `*(sender_sp - 8)` 拿到 stack 地址（不是 PC），命中 `assert(cb != nullptr) failed: must be`。
- 结论：基于 r13 的方案不能同时兼顾 GC walker 与 FindClass walker 两条路径，已回退。

### I. 更改 BufferBlob `_frame_size` 以补偿 c2i extraspace（理论分析后弃用）

- 想法：每个签名按 `extraspace = align_up(args_total_slots * 8, 16)` 计算 `_frame_size = (extraspace + 24) / 8`，让 sender_sp = naked_rbp + 8 + frame_size*8 = T_entry + extraspace + 8 = caller_pre_call_rsp（路径 2 正确）。
- 路径分析：
  - 路径 2（c2i adapter）：✓ 修复。
  - 解释器：`*(T_entry + extraspace)` 落在 args 区上方的解释器 operand stack，**不是** PC，`find_blob` 返回 NULL → assert 触发。
  - call_stub：`*(T_entry + extraspace)` 落在 call_stub 的 push args 循环之上，同样**不是** PC。
- 结论：除非能在 runtime 区分调用路径，否则修一个会破坏其它两个，因此弃用。

## 当前假设 / 下一步只应该围绕这些方向

### 假设 0（NEW）：在 i2i naked function 里 runtime 检测路径，仅对 c2i-adapter 路径调整 anchor sp

- 通过 `extraspace_dynamic = r13 - naked_rbp - 32` 估算 c2i adapter shift。
- 解释器 / call_stub：理论上 `extraspace_dynamic == 0`，anchor 走原 `naked_rbp + 8`。
- c2i adapter：`extraspace_dynamic == extraspace`，anchor 改为 `naked_rbp + 8 + extraspace`。
- **但 GDB 验证表明 r13 实际 = T_entry（不是 T_entry + 8）**，即上式算出的 `extraspace_dynamic = -8`。也就是说当前观测到的 r13 不符合 HotSpot 源码 `lea r13, [rsp + 8]` 的预期。
- 下一步：在 fastdebug 内部用 GDB 对解释器 invokestatic 模板的实际机器码进行 disasm，确认 `prepare_to_jump_from_interpreted` 实际生成了什么；如果 r13 真的是 `T_entry`，则改用 `extraspace_dynamic = r13 - naked_rbp - 24` 重试。

### 假设 1：需要给 JavaCallWrapper 保存一个“可 seed RegisterMap 的 anchor”，但不能破坏真实 return chain

- direct i2i anchor 对普通返回是最稳定的。
- JavaCallWrapper 复制 direct anchor 后，entry-frame sender 会 `map->clear()`，导致 compiled top frame 缺 `rbp` location。
- 需要找一种只影响 JavaCallWrapper / GC walk 的 synthetic callee representation，而不改变 i2i 实际 return protocol，也不能写 `%r13` 下方。

可能方向：

- 在 libneko 自己的 stack frame 内构造一个稳定 synthetic frame，使 `sender_for_entry_frame` 返回 synthetic BufferBlob frame，随后由 synthetic frame 的 sender walk seed `rbp` 并返回真实 compiled caller。
- 但必须保证 synthetic frame 的 `[sender_sp-2]` / `[sender_sp-1]` 都位于 libneko 自己保留的安全 stack memory，而不是 `%r13` 下方或 interpreter slot 区。
- 需要重新推导：`last_Java_sp + frame_size` 应落到一个由 libneko 自己布置的 synthetic sender record，再由该 record 返回真实 Java frame。

### 假设 2：直接改 native JNI upcall 策略，减少 JavaCall/call_stub 在 translated native 期间发生

- 当前 fatal frames 包含 `MethodHandle.invokeWithArguments`、`call_stub`、stream lambda 等，说明 translated native 内触发了 JNI upcall。
- 如果某些 intrinsics 可以继续 native 化（避免 JNI JavaCall），可减少触发面，但不能作为 fallback，也不能牺牲 100% native 覆盖。
- 这只是降低触发概率，不是根治 RegisterMap。

### 假设 3：需要 HotSpot debug symbols 或 debug JDK 验证具体 crash path

- release `libjvm.so` stripped，`addr2line` 基本无效。
- 如果继续底层修，建议装 debug symbols 或 build debug OpenJDK 21u，用同一 test jar 重现并断在：
  - `frame::oops_do_internal`
  - `OopMapSet::oops_do`
  - `frame::sender_for_entry_frame`
  - `frame::sender_for_compiled_frame`
  - `RegisterMap::set_location / clear`

## 当前测试状态

> 截至 2026-04-26 重跑（debug-jdk 修复 JNIHandleBlock `_last == NULL` + CodeHeap walk 越界 + i2i thunk-aware anchor + Windows / AArch64 trampoline Java 端补齐）：

| 测试 / 场景 | 当前状态 |
| --- | --- |
| no JVMTI / no Agent_OnLoad（仓库审计） | ✅ 全模块零命中 |
| no native keyword / no ACC_NATIVE shape | ✅ 通过 |
| no helper classes / only NekoNativeLoader | ✅ 通过 |
| native library resource present | ✅ 通过 |
| `nativeObfuscation_TEST_calcUnder150ms` | ✅ 本次重跑通过 |
| `nativeObfuscation_SnakeGame_headlessExceptionOnly` | ✅ 本次重跑通过 |
| `nativeObfuscation_noHelperMethodsInOutput` | ✅ 本次重跑通过 |
| `nativeObfuscation_onlyNekoNativeLoaderInjected` | ✅ 本次重跑通过 |
| `nativeObfuscation_sharedLibraryPresent` | ✅ 本次重跑通过 |
| obfusjack native build / cold compile | ✅ 通过 |
| obfusjack runtime completion | ❌ microbench 阶段 GC `sender_for_compiled_frame` `assert(pc != nullptr)` 失败 |
| obfusjack on **fastdebug** JDK 21u（手测） | ✅ 跑到 `=== All tests completed ===`（exit 0；release 在同一处仍崩，差异点尚未定位） |
| Windows x64 backend Java 编译 | ✅ 通过（运行链路待物理 Windows 环境验证） |
| AArch64 backend Java 编译 | ✅ 通过（运行链路阻塞在 MethodPatcherEmitter thunk 字节仍是 x86_64） |
| final full bundle | ❌ 未通过 |

## 关键文件索引

| 文件 | 角色 |
| --- | --- |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/X86_64SysVTrampoline.java` | x86_64 SysV i2i/c2i naked asm 模板；当前核心问题所在 |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/MethodPatcherEmitter.java` | VMStructs、private CodeHeap/BufferBlob/thunk、Method entry patching |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/SignatureDispatcherEmitter.java` | JNI-style dispatcher / local handles / translated impl 调用 |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java` | native runtime helpers、HotSpot probing、intrinsics support |
| `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java` | intrinsics：class literal、Throwable stack trace、Method.invoke、MethodHandles.lookup 等 |
| `neko-runtime/src/main/java/dev/nekoobfuscator/runtime/NekoNativeLoader.java` | 最小 loader，只抽取并 System.load native library |
| `tmp/openjdk-jdk21u/src/hotspot/cpu/x86/frame_x86.cpp` | `sender_for_entry_frame` / x86 frame walk |
| `tmp/openjdk-jdk21u/src/hotspot/cpu/x86/frame_x86.inline.hpp` | `sender_for_compiled_frame` / `update_map_with_saved_link` |
| `tmp/openjdk-jdk21u/src/hotspot/share/runtime/javaCalls.cpp` | `JavaCallWrapper` anchor copy / clear behavior |
| `tmp/openjdk-jdk21u/src/hotspot/share/runtime/registerMap.hpp` | RegisterMap saved register location tracking |

## 复现命令

```bash
# 重建 CLI
./gradlew :neko-cli:installDist -q

# 强制 cold rebuild 某个 fixture
rm -f neko-test/build/test-native/obfusjack-native.jar
./gradlew :neko-test:test --tests "NativeObfuscationIntegrationTest.nativeObfuscation_obfusjack_reachesCompletion" -q

# patch debug
rm -f neko-test/build/test-native/obfusjack-native.jar
NEKO_PATCH_DEBUG=1 ./gradlew :neko-cli:installDist -q
NEKO_PATCH_DEBUG=1 ./gradlew :neko-test:test --tests "NativeObfuscationIntegrationTest.nativeObfuscation_obfusjack_reachesCompletion" -q

# 手动运行 jar
NEKO_PATCH_DEBUG=1 java -jar neko-test/build/test-native/obfusjack-native.jar
java -Djava.awt.headless=true -jar neko-test/build/test-native/SnakeGame-native.jar
```

## 不要再做的事

- [x] 不要再在 i2i 的 `sp=B+8`、`sp=%r13-24`、`sp=B+32+slots` 之间盲目来回切。**说明**：现行 i2i SP=`rbp+8`（thunk-aware）只在“同步 c2i anchor”意义下触发，且 i2i 的实际 return protocol 仍是 `r13` tail jump；不属于本条禁忌。
- [x] 不要写 `%r13[-16]` / `%r13[-8]`；实验证明会破坏 VM/interpreter scratch。
- [x] 不要把 i2i 改成 normal `ret` through thunk；实验证明 TEST 不干净退出且 obfusjack 更早崩（即历史方案 F：anchor + return 同改）。
- [x] 不要认为 `_from_compiled_entry` 没 patch；debug log 已证明 patch 和 thunk allocation 有效。
- [x] 不要用 fallback / exclusion / JVMTI / Java loader helper 绕过问题；都违反用户约束。
- [x] 不要再引入新的 JVM runtime helper 类；当前 `NekoNativeLoader` 是唯一允许的 Java 层 runtime，且只负责抽取 + `System.load`。
- [x] 不要往 `neko-runtime` 加任何 bootstrap/bind/link helper；`onlyNekoNativeLoaderInjected` 测试是硬约束。

## 如果继续推进，建议的最小路径

1. **保持** 现行 i2i + c2i thunk-aware anchor 形态。这一形态已让 GC 第一次 walk 进 `~BufferBlob::neko_trampoline`，原 RegisterMap rbp guarantee 已根除；不要回退。
2. 在 debug JDK / debug symbols 下复现 `hs_err_pid3677260` 类 crash（GC walker 在 `libjvm.so+0x2f5167` NULL 解引用 `(%r14)`），重点观察 `frame::oops_do_internal` → 内部 walker → `find_blob_unsafe`/inline-cache fingerprint 校验路径，看是哪一段假设我们的 BufferBlob 拥有它当前不具备的元数据。
3. 候选修补方向（非禁忌）：
   - 在 `MethodPatcherEmitter` 的 BufferBlob 初始化里把 `_caller_must_gc_arguments`、`_unwind_handler_offset` 等次要 flag 显式赋值（当前依赖 memset 0 默认值），看是否消除 walker 的辅助校验。
   - 给 BufferBlob 挂一个空 `_oop_maps` set（new ImmutableOopMapSet(0, 0)）以让 `OopMapSet::oops_do` / `update_register_map` 显式跑空表，而不是依赖 NULL 短路。
   - 检查 `_unextended_sp` 与 `_sp` 在 sender 链上是否有路径让 HotSpot 把它们当作 NULL/未初始化使用。
4. AArch64 后端落地的真正门槛在 `MethodPatcherEmitter` 的 thunk 字节常量（当前为 x86_64 specific）。引入 `arch_thunk_bytes()` 的两套实现（runtime 用 `#if defined(__aarch64__)`），thunk 大小调到 24 字节即可。
5. 任何下一步代码改动都必须先确认：
   - 不引入 JVMTI（无 `Agent_OnLoad` / `jvmtiEnv*` / `GetEnv(JVMTI_*)`）。
   - 不引入新的 Java runtime helper class（保持 `NekoNativeLoader` 是唯一）。
   - 不在 `<clinit>` 暴露除 `NekoNativeLoader.load()` 之外的任何 bootstrap 入口。
