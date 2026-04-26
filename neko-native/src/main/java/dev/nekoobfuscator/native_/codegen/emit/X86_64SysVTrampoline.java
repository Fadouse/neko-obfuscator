package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Naked-function trampolines for x86_64 SysV (Linux + macOS).
 *
 * HotSpot interpreter calling convention at {@code _i2i_entry} entry:
 *   rbx  = Method*
 *   r13  = sender_sp (preserved for HotSpot's interpreter return protocol)
 *   r15  = JavaThread*
 *   args = at POSITIVE offsets from the entry stack pointer plus one word
 *          (return-pc slot). The last-pushed (rightmost) arg is at
 *          [entry_rsp + 8], and the first-pushed arg (receiver for instance
 *          methods) is at the highest offset. Long/double values occupy 2
 *          slots but the 64-bit value lives in the lower-index slot.
 *   The return address is on the C stack at [entry_rsp].
 *
 * On {@code _from_compiled_entry} the JIT delivers args in the SysV C ABI
 * registers (rdi, rsi, rdx, rcx, r8, r9 for GP; xmm0..7 for FP). The c2i
 * path reshuffles that compiled calling convention to the JNI dispatcher
 * signature and brackets the dispatcher call with HotSpot's JavaThread state
 * and JavaFrameAnchor updates.
 */
public final class X86_64SysVTrampoline {

    public String render(int sigId, SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderI2i(sigId, shape));
        sb.append(renderC2i(sigId, shape));
        return sb.toString();
    }

    private String renderI2i(int sigId, SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        char ret = shape.returnKind();
        char[] args = shape.argKinds();
        int receiverSlot = shape.isStatic() ? 0 : 1;
        int totalSlots = receiverSlot;
        for (char a : args) totalSlots += (a == 'J' || a == 'D') ? 2 : 1;

        // HotSpot interpreter slot indexing: walk left-to-right, peel slots
        // off the top. First arg (receiver, if instance) gets the HIGHEST
        // slot index; last arg gets index 0 (top of operand stack).
        int[] argSlotIndex = new int[args.length];
        int receiverIndex = -1;
        {
            int remaining = totalSlots;
            if (!shape.isStatic()) {
                receiverIndex = remaining - 1;
                remaining -= 1;
            }
            for (int i = 0; i < args.length; i++) {
                int slots = (args[i] == 'J' || args[i] == 'D') ? 2 : 1;
                argSlotIndex[i] = remaining - slots;
                remaining -= slots;
            }
        }

        sb.append("/* sig ").append(sigId).append(" i2i (").append(shape.isStatic() ? "static" : "instance")
          .append(", args=\"");
        for (char a : args) sb.append(a);
        sb.append("\", ret=").append(ret).append(") */\n");
        sb.append("__attribute__((naked, used, visibility(\"hidden\")))\n");
        sb.append("void neko_sig_").append(sigId).append("_i2i(void) {\n");
        sb.append("    __asm__ volatile (\n");
        sb.append("        \"pushq %%rbp\\n\"\n");
        sb.append("        \"movq  %%rsp, %%rbp\\n\"\n");
        // HotSpot's interpreter does not guarantee 16-byte rsp alignment,
        // but SysV C ABI does. Reserve 256 bytes (overshoots 128 to allow
        // alignment plus arg-spill region) and force 16-byte alignment.
        sb.append("        \"subq  $256, %%rsp\\n\"\n");
        sb.append("        \"andq  $-16, %%rsp\\n\"\n");
        // scan primary and alias Method* tables for matching rbx
        sb.append("        \"leaq  g_neko_manifest_method_stars(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl  g_neko_manifest_method_count(%%rip), %%r11d\\n\"\n");
        sb.append("        \"xorl  %%eax, %%eax\\n\"\n");
        sb.append("        \"1:\\n\"\n");
        sb.append("        \"cmpl  %%r11d, %%eax\\n\"\n");
        sb.append("        \"jge   3f\\n\"\n");
        sb.append("        \"cmpq  %%rbx, (%%r10, %%rax, 8)\\n\"\n");
        sb.append("        \"je    2f\\n\"\n");
        sb.append("        \"incl  %%eax\\n\"\n");
        sb.append("        \"jmp   1b\\n\"\n");
        sb.append("        \"3:\\n\"\n");
        sb.append("        \"leaq  g_neko_manifest_alias_method_stars(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl  g_neko_manifest_alias_count(%%rip), %%r11d\\n\"\n");
        sb.append("        \"xorl  %%eax, %%eax\\n\"\n");
        sb.append("        \".Lneko_i2i_alias_loop_%=:\\n\"\n");
        sb.append("        \"cmpl  %%r11d, %%eax\\n\"\n");
        sb.append("        \"jge   .Lneko_i2i_miss_%=\\n\"\n");
        sb.append("        \"cmpq  %%rbx, (%%r10, %%rax, 8)\\n\"\n");
        sb.append("        \"je    .Lneko_i2i_alias_hit_%=\\n\"\n");
        sb.append("        \"incl  %%eax\\n\"\n");
        sb.append("        \"jmp   .Lneko_i2i_alias_loop_%=\\n\"\n");
        sb.append("        \".Lneko_i2i_alias_hit_%=:\\n\"\n");
        sb.append("        \"leaq  g_neko_manifest_alias_indices(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl  (%%r10, %%rax, 4), %%eax\\n\"\n");
        sb.append("        \"jmp   2f\\n\"\n");
        sb.append("        \".Lneko_i2i_miss_%=:\\n\"\n");
        sb.append("        \"xorl  %%eax, %%eax\\n\"\n");
        sb.append("        \"pxor  %%xmm0, %%xmm0\\n\"\n");
        sb.append("        \"jmp   9f\\n\"\n");
        sb.append("        \"2:\\n\"\n");
        // entry = &g_neko_manifest_methods[idx]; first arg in rdi
        sb.append("        \"leaq  g_neko_manifest_methods(%%rip), %%rdi\\n\"\n");
        sb.append("        \"imulq $").append(PatcherLayoutConstants.MANIFEST_METHOD_SIZE)
          .append(", %%rax, %%rcx\\n\"\n");
        sb.append("        \"addq  %%rcx, %%rdi\\n\"\n");

        // Shuffle args from interpreter slots into SysV C ABI. Use the actual
        // entry stack layout, not r13. HotSpot MethodHandle linkTo* stubs may
        // remove their trailing MemberName by adjusting rsp before tail-jumping
        // to the target Method::_from_interpreted_entry, while r13 still carries
        // the sender_sp needed for the eventual interpreter return protocol.
        // For reference args (and the receiver), pass the ADDRESS of the
        // interpreter slot rather than its contents. HotSpot's JNI handle
        // representation is a pointer to an oop cell, and JNIHandles::resolve
        // is a single deref — making &locals[i] a perfectly valid jobject.
        // This avoids needing libjvm-internal JNIHandles::make_local symbols
        // (which are stripped from JDK 21+ release builds).
        // Args layout to dispatcher: rdi=entry, rsi=thread (=r15), then receiver (if any), then args.
        sb.append("        \"movq  %%r15, %%rsi\\n\"\n");
        int gpUsed = 2; // rdi = entry, rsi = thread
        int xmmUsed = 0;
        int extraStackSlot = 0;
        if (!shape.isStatic()) {
            int slotOffset = receiverIndex * 8;
            sb.append("        \"leaq  ").append(slotOffset + 32).append("(%%rbp), %").append(gpReg(gpUsed)).append("\\n\"\n");
            gpUsed++;
        }
        for (int i = 0; i < args.length; i++) {
            char a = args[i];
            int slotOffset = argSlotIndex[i] * 8;
            if (a == 'F' || a == 'D') {
                if (xmmUsed < 8) {
                    if (a == 'F') sb.append("        \"movss ");
                    else sb.append("        \"movsd ");
                    sb.append(slotOffset + 32).append("(%%rbp), %%xmm").append(xmmUsed).append("\\n\"\n");
                    xmmUsed++;
                } else {
                    sb.append("        \"movq  ").append(slotOffset + 32).append("(%%rbp), %%rax\\n\"\n");
                    sb.append("        \"movq  %%rax, ").append(extraStackSlot * 8).append("(%%rsp)\\n\"\n");
                    extraStackSlot++;
                }
            } else if (a == 'L') {
                // Reference arg: pass slot address as jobject.
                if (gpUsed < 6) {
                    sb.append("        \"leaq  ").append(slotOffset + 32).append("(%%rbp), %").append(gpReg(gpUsed)).append("\\n\"\n");
                    gpUsed++;
                } else {
                    sb.append("        \"leaq  ").append(slotOffset + 32).append("(%%rbp), %%rax\\n\"\n");
                    sb.append("        \"movq  %%rax, ").append(extraStackSlot * 8).append("(%%rsp)\\n\"\n");
                    extraStackSlot++;
                }
            } else {
                // Primitive (I/J): pass slot value.
                if (gpUsed < 6) {
                    sb.append("        \"movq  ").append(slotOffset + 32).append("(%%rbp), %").append(gpReg(gpUsed)).append("\\n\"\n");
                    gpUsed++;
                } else {
                    sb.append("        \"movq  ").append(slotOffset + 32).append("(%%rbp), %%rax\\n\"\n");
                    sb.append("        \"movq  %%rax, ").append(extraStackSlot * 8).append("(%%rsp)\\n\"\n");
                    extraStackSlot++;
                }
            }
        }
        // Frame anchor: thunk-aware synthetic BufferBlob (frame_size=3) so
        // sender_sp = rbp+8 + 24 = rbp+32 = caller's pre-call sp; saved fp
        // at rbp+16 is what the thunk pushed; return pc at rbp+24 is what
        // the interpreter's prepare_invoke / compiled `call` left on the
        // stack. NOTE: in the rare path where this i2i thunk is reached via
        // HotSpot's c2i adapter (extraspace > 0), naked_rbp+32 is shifted
        // by extraspace and the GC walker reads a wrong sender_pc — that
        // case is documented in todo.md (microbench-only failure).
        sb.append("        \"cmpb $0, g_neko_frame_anchor_ready(%%rip)\\n\"\n");
        sb.append("        \"je   7f\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_fp(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq (%%rbp), %%r11\\n\"\n");
        sb.append("        \"movq %%r11, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_pc(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq 8(%%rbp), %%r11\\n\"\n");
        sb.append("        \"movq %%r11, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_sp(%%rip), %%r10\\n\"\n");
        sb.append("        \"leaq 8(%%rbp), %%r11\\n\"\n");
        sb.append("        \"movq %%r11, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"7:\\n\"\n");
        // Thread-state transition: _thread_in_Java -> _thread_in_native.
        // r15 holds the JavaThread*; off and state values come from runtime globals.
        sb.append("        \"cmpb $0, g_neko_thread_state_ready(%%rip)\\n\"\n");
        sb.append("        \"je   4f\\n\"\n");
        sb.append("        \"movq g_neko_off_thread_state(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl g_neko_thread_state_in_native(%%rip), %%r11d\\n\"\n");
        sb.append("        \"movl %%r11d, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"mfence\\n\"\n");
        sb.append("        \"4:\\n\"\n");
        sb.append("        \"call  neko_sig_").append(sigId).append("_dispatch\\n\"\n");
        // Save the return value across the transition-back so we don't clobber it.
        sb.append("        \"movq %%rax, -8(%%rbp)\\n\"\n");
        sb.append("        \"movq %%xmm0, -16(%%rbp)\\n\"\n");
        // Transition _thread_in_native -> _thread_in_Java. If a safepoint is
        // requested (polling word non-zero) call neko_handle_safepoint_poll.
        sb.append("        \"cmpb $0, g_neko_thread_state_ready(%%rip)\\n\"\n");
        sb.append("        \"je   5f\\n\"\n");
        sb.append("        \"movq g_neko_off_thread_state(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl g_neko_thread_state_in_native_trans(%%rip), %%r11d\\n\"\n");
        sb.append("        \"movl %%r11d, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"mfence\\n\"\n");
        // Check polling word
        sb.append("        \"movq g_neko_off_thread_polling_word(%%rip), %%r10\\n\"\n");
        sb.append("        \"testq %%r10, %%r10\\n\"\n");
        sb.append("        \"je   6f\\n\"\n");
        sb.append("        \"movq (%%r15, %%r10, 1), %%r11\\n\"\n");
        sb.append("        \"testq %%r11, %%r11\\n\"\n");
        sb.append("        \"je   6f\\n\"\n");
        sb.append("        \"call  neko_handle_safepoint_poll\\n\"\n");
        sb.append("        \"6:\\n\"\n");
        sb.append("        \"movq g_neko_off_thread_state(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl g_neko_thread_state_in_java(%%rip), %%r11d\\n\"\n");
        sb.append("        \"movl %%r11d, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"5:\\n\"\n");
        // Clear the full frame anchor: clear sp first (valid flag), then fp/pc
        // so a later HotSpot resolve stub never inherits our stale pc.
        sb.append("        \"cmpb $0, g_neko_frame_anchor_ready(%%rip)\\n\"\n");
        sb.append("        \"je   8f\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_sp(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_fp(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_pc(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"8:\\n\"\n");
        sb.append("        \"movq -8(%%rbp), %%rax\\n\"\n");
        sb.append("        \"movq -16(%%rbp), %%xmm0\\n\"\n");
        sb.append("        \"9:\\n\"\n");
        // Return to interpreter caller: save return pc, restore rsp from the
        // HotSpot-provided sender_sp in r13, and tail-jump to the continuation.
        sb.append("        \"movq  %%rbp, %%rsp\\n\"\n");
        sb.append("        \"popq  %%rbp\\n\"\n");
        sb.append("        \"movq  16(%%rsp), %%r10\\n\"\n");
        sb.append("        \"movq  (%%rbp), %%rbp\\n\"\n");
        sb.append("        \"movq  %%r13, %%rsp\\n\"\n");
        sb.append("        \"jmp   *%%r10\\n\"\n");
        sb.append("        :\n        :\n        : \"memory\"\n");
        sb.append("    );\n}\n\n");
        return sb.toString();
    }

    /**
     * Generate a c2i adapter for one signature. The c2i is invoked by JIT-
     * compiled callers via {@code call _from_compiled_entry}. On entry:
     *
     *   rbx              = Method*
     *   rsi/rdx/rcx/r8/r9/rdi = JIT GP args (rsi = receiver for instance,
     *                          else rsi = first arg)
     *   xmm0..xmm7       = JIT FP args (xmm0 = first FP arg, etc.)
     *   [rbp + 32 + i*8] = stack-spilled args (when GP/FP regs exhausted)
     *   (rsp)            = JIT return PC
     *
     * Strategy: standalone function. The dispatcher signature matches i2i's,
     * so we spill ref args (and receiver) to known stack slots and pass slot
     * addresses; primitives go through unchanged. Before calling into the C/JNI
     * dispatcher, publish a JavaFrameAnchor pointing at the compiled caller and
     * transition the JavaThread out of _thread_in_Java.
     *
     * Manifest scan finds the entry by rbx (Method*). On miss returns zero.
     */
    private String renderC2i(int sigId, SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        char ret = shape.returnKind();
        char[] args = shape.argKinds();
        boolean isStatic = shape.isStatic();

        int dispatcherGpUsed = 2 + (isStatic ? 0 : 1); // entry, thread, receiver
        int dispatcherXmmUsed = 0;
        int dispatcherStackSlots = 0;
        for (char a : args) {
            if (a == 'F' || a == 'D') {
                if (dispatcherXmmUsed < 8) dispatcherXmmUsed++;
                else dispatcherStackSlots++;
            } else if (dispatcherGpUsed < 6) {
                dispatcherGpUsed++;
            } else {
                dispatcherStackSlots++;
            }
        }

        // Stack layout at %rsp after alignment:
        //   [0, outgoingStackBytes)          C ABI stack args for dispatcher
        //   [localBase, ...)                 receiver/ref spills + saves
        // Keeping those regions separate prevents overflow dispatcher args
        // from overwriting jobject slot cells.
        int refCount = isStatic ? 0 : 1;
        for (char a : args) if (a == 'L') refCount++;
        int outgoingStackBytes = dispatcherStackSlots * 8;
        int localBase = outgoingStackBytes;
        int refBase = localBase;
        int entryOffset = refBase + refCount * 8;
        int gpSaveBase = entryOffset + 8;
        int retRaxOffset = gpSaveBase + 48;
        int retXmmOffset = retRaxOffset + 8;
        int localBytes = retXmmOffset + 8;
        int frameBytes = ((localBytes + 32 + 15) & ~15);

        sb.append("/* sig ").append(sigId).append(" c2i (").append(isStatic ? "static" : "instance")
          .append(", args=\"");
        for (char a : args) sb.append(a);
        sb.append("\", ret=").append(ret).append(", refCount=").append(refCount).append(") */\n");
        sb.append("__attribute__((naked, used, visibility(\"hidden\")))\n");
        sb.append("void neko_sig_").append(sigId).append("_c2i(void) {\n");
        sb.append("    __asm__ volatile (\n");
        sb.append("        \"pushq %%rbp\\n\"\n");
        sb.append("        \"movq  %%rsp, %%rbp\\n\"\n");
        // Save callee-saved regs we'll clobber: rbx (input). r12-r15 we don't
        // touch here (r15=thread is preserved by C ABI, others we don't use).
        sb.append("        \"pushq %%rbx\\n\"\n");
        // Reserve frame for outgoing C stack args, ref spills, saved source
        // GP regs, return-value saves, scratch, and alignment.
        sb.append("        \"subq  $").append(frameBytes).append(", %%rsp\\n\"\n");
        sb.append("        \"andq  $-16, %%rsp\\n\"\n");

        // Save every compiled-call GP arg before any dispatcher shuffle. Some
        // dispatcher destination registers overlap later compiled source
        // registers (for example arg0 rdi -> rdx can clobber arg2), so all GP
        // loads below read from these stable copies.
        for (int i = 0; i < 6; i++) {
            sb.append("        \"movq  %").append(javaGpReg(i)).append(", ")
                .append(gpSaveBase + i * 8).append("(%%rsp)\\n\"\n");
        }

        // Spill ref args (and receiver) to stable stack slots so we can pass
        // slot addresses to the dispatcher. spill[0] = receiver (if instance),
        // spill[1..] = each ref arg in order.
        int spillIndex = 0;
        if (!isStatic) {
            sb.append("        \"movq  ").append(gpSaveBase).append("(%%rsp), %%rax\\n\"\n");
            sb.append("        \"movq  %%rax, ").append(refBase + spillIndex * 8).append("(%%rsp)\\n\"\n");
            spillIndex++;
        }
        // Track which JIT register each arg came from. Receiver took one GP slot.
        int gpSrc = isStatic ? 0 : 1;
        int xmmSrc = 0;
        int stackSrc = 0;
        // Per-arg source register/stack info (computed first; used twice).
        // jitGpReg[i] >= 0 means the arg was in GP reg jitGpReg[i].
        // jitXmmReg[i] >= 0 means the arg was in xmm jitXmmReg[i].
        // jitStackOff[i] >= 0 means the arg was at [rbp + jitStackOff[i]].
        int[] jitGpReg = new int[args.length];
        int[] jitXmmReg = new int[args.length];
        int[] jitStackOff = new int[args.length];
        java.util.Arrays.fill(jitGpReg, -1);
        java.util.Arrays.fill(jitXmmReg, -1);
        java.util.Arrays.fill(jitStackOff, -1);
        for (int i = 0; i < args.length; i++) {
            char a = args[i];
            if (a == 'F' || a == 'D') {
                if (xmmSrc < 8) {
                    jitXmmReg[i] = xmmSrc++;
                } else {
                    jitStackOff[i] = 32 + stackSrc * 8;
                    stackSrc++;
                }
            } else {
                if (gpSrc < 6) {
                    jitGpReg[i] = gpSrc++;
                } else {
                    jitStackOff[i] = 32 + stackSrc * 8;
                    stackSrc++;
                }
            }
        }
        // Spill ref-arg values to stack slots for slot-addr passing.
        int[] argSpillSlot = new int[args.length];
        java.util.Arrays.fill(argSpillSlot, -1);
        for (int i = 0; i < args.length; i++) {
            if (args[i] != 'L') continue;
            argSpillSlot[i] = spillIndex;
            int dst = refBase + spillIndex * 8;
            if (jitGpReg[i] >= 0) {
                sb.append("        \"movq  ").append(gpSaveBase + jitGpReg[i] * 8)
                    .append("(%%rsp), %%rax\\n\"\n");
                sb.append("        \"movq  %%rax, ").append(dst).append("(%%rsp)\\n\"\n");
            } else {
                sb.append("        \"movq  ").append(jitStackOff[i]).append("(%%rbp), %%rax\\n\"\n");
                sb.append("        \"movq  %%rax, ").append(dst).append("(%%rsp)\\n\"\n");
            }
            spillIndex++;
        }

        // Manifest scan: find entry index for rbx in primary or alias Method* tables.
        sb.append("        \"leaq  g_neko_manifest_method_stars(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl  g_neko_manifest_method_count(%%rip), %%r11d\\n\"\n");
        sb.append("        \"xorl  %%eax, %%eax\\n\"\n");
        sb.append("        \"1:\\n\"\n");
        sb.append("        \"cmpl  %%r11d, %%eax\\n\"\n");
        sb.append("        \"jge   3f\\n\"\n");
        sb.append("        \"cmpq  %%rbx, (%%r10, %%rax, 8)\\n\"\n");
        sb.append("        \"je    2f\\n\"\n");
        sb.append("        \"incl  %%eax\\n\"\n");
        sb.append("        \"jmp   1b\\n\"\n");
        sb.append("        \"3:\\n\"\n");
        sb.append("        \"leaq  g_neko_manifest_alias_method_stars(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl  g_neko_manifest_alias_count(%%rip), %%r11d\\n\"\n");
        sb.append("        \"xorl  %%eax, %%eax\\n\"\n");
        sb.append("        \".Lneko_c2i_alias_loop_%=:\\n\"\n");
        sb.append("        \"cmpl  %%r11d, %%eax\\n\"\n");
        sb.append("        \"jge   .Lneko_c2i_miss_%=\\n\"\n");
        sb.append("        \"cmpq  %%rbx, (%%r10, %%rax, 8)\\n\"\n");
        sb.append("        \"je    .Lneko_c2i_alias_hit_%=\\n\"\n");
        sb.append("        \"incl  %%eax\\n\"\n");
        sb.append("        \"jmp   .Lneko_c2i_alias_loop_%=\\n\"\n");
        sb.append("        \".Lneko_c2i_alias_hit_%=:\\n\"\n");
        sb.append("        \"leaq  g_neko_manifest_alias_indices(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl  (%%r10, %%rax, 4), %%eax\\n\"\n");
        sb.append("        \"jmp   2f\\n\"\n");
        sb.append("        \".Lneko_c2i_miss_%=:\\n\"\n");
        sb.append("        \"xorl  %%eax, %%eax\\n\"\n");
        sb.append("        \"pxor  %%xmm0, %%xmm0\\n\"\n");
        sb.append("        \"jmp   9f\\n\"\n");
        sb.append("        \"2:\\n\"\n");
        sb.append("        \"leaq  g_neko_manifest_methods(%%rip), %%r10\\n\"\n");
        sb.append("        \"imulq $").append(PatcherLayoutConstants.MANIFEST_METHOD_SIZE)
          .append(", %%rax, %%rcx\\n\"\n");
        sb.append("        \"addq  %%rcx, %%r10\\n\"\n");
        // r10 = entry pointer. We need rdi=entry, rsi=thread, then receiver-slot-addr (if instance), then args.
        // We'll move r10 into rdi LAST to avoid stomping the receiver/arg moves.
        // But the dispatcher's calling convention puts receiver_slot in rdx — so we need to
        // shuffle arguments. Simplest: spill r10 (entry pointer) to a known stack slot first.
        sb.append("        \"movq  %%r10, ").append(entryOffset).append("(%%rsp)\\n\"\n");

        // Now build dispatcher arg list. C ABI: rdi, rsi, rdx, rcx, r8, r9, then stack.
        // Plan layout:
        //   rdi = entry
        //   rsi = thread (=r15)
        //   rdx = &spilled_receiver (if instance)
        //   then for each arg i: ref → &spilled[argSpillSlot[i]], primitive → original value
        // For simplicity, build the call frame arg-by-arg using spill slots.
        //
        // Since we may have many JIT regs/stack/xmm to recombine, and we want
        // to keep this finite, we limit to: receiver (1 ref) + args.

        // Move thread into rsi BEFORE we clobber rdi (which is current receiver in instance methods,
        // but we already spilled it).
        sb.append("        \"movq  %%r15, %%rsi\\n\"\n");
        // rdx = &spilled_receiver (instance) or first arg slot (static handled below).
        int dispGpUsed = 2; // rdi, rsi taken
        int dispXmmUsed = 0;
        int dispStackSlots = 0;
        if (!isStatic) {
            sb.append("        \"leaq  ").append(refBase).append("(%%rsp), %%rdx\\n\"\n");
            dispGpUsed++;
        }
        // For each arg, place value/slot-addr into the right dispatcher slot.
        for (int i = 0; i < args.length; i++) {
            char a = args[i];
            if (a == 'L') {
                int srcOff = refBase + argSpillSlot[i] * 8;
                if (dispGpUsed < 6) {
                    sb.append("        \"leaq  ").append(srcOff).append("(%%rsp), %").append(gpReg(dispGpUsed)).append("\\n\"\n");
                    dispGpUsed++;
                } else {
                    sb.append("        \"leaq  ").append(srcOff).append("(%%rsp), %%rax\\n\"\n");
                    sb.append("        \"movq  %%rax, ").append(dispStackSlots * 8).append("(%%rsp)\\n\"\n");
                    dispStackSlots++;
                }
            } else if (a == 'F' || a == 'D') {
                if (dispXmmUsed < 8) {
                    if (jitXmmReg[i] >= 0 && jitXmmReg[i] != dispXmmUsed) {
                        // Move xmm[jitXmmReg[i]] -> xmm[dispXmmUsed].
                        if (a == 'F') sb.append("        \"movss %%xmm").append(jitXmmReg[i]).append(", %%xmm").append(dispXmmUsed).append("\\n\"\n");
                        else sb.append("        \"movsd %%xmm").append(jitXmmReg[i]).append(", %%xmm").append(dispXmmUsed).append("\\n\"\n");
                    } else if (jitXmmReg[i] < 0) {
                        if (a == 'F') sb.append("        \"movss ").append(jitStackOff[i]).append("(%%rbp), %%xmm").append(dispXmmUsed).append("\\n\"\n");
                        else sb.append("        \"movsd ").append(jitStackOff[i]).append("(%%rbp), %%xmm").append(dispXmmUsed).append("\\n\"\n");
                    }
                    // Else jitXmmReg == dispXmmUsed, no move needed.
                    dispXmmUsed++;
                } else {
                    // FP overflow → stack
                    if (jitXmmReg[i] >= 0) {
                        sb.append("        \"movq  %%xmm").append(jitXmmReg[i]).append(", ").append(dispStackSlots * 8).append("(%%rsp)\\n\"\n");
                    } else {
                        sb.append("        \"movq  ").append(jitStackOff[i]).append("(%%rbp), %%rax\\n\"\n");
                        sb.append("        \"movq  %%rax, ").append(dispStackSlots * 8).append("(%%rsp)\\n\"\n");
                    }
                    dispStackSlots++;
                }
            } else {
                // GP primitive (I/J)
                if (dispGpUsed < 6) {
                    if (jitGpReg[i] >= 0) {
                        sb.append("        \"movq  ").append(gpSaveBase + jitGpReg[i] * 8)
                            .append("(%%rsp), %").append(gpReg(dispGpUsed)).append("\\n\"\n");
                    } else if (jitGpReg[i] < 0) {
                        sb.append("        \"movq  ").append(jitStackOff[i]).append("(%%rbp), %").append(gpReg(dispGpUsed)).append("\\n\"\n");
                    }
                    dispGpUsed++;
                } else {
                    if (jitGpReg[i] >= 0) {
                        sb.append("        \"movq  ").append(gpSaveBase + jitGpReg[i] * 8)
                            .append("(%%rsp), %%rax\\n\"\n");
                        sb.append("        \"movq  %%rax, ").append(dispStackSlots * 8).append("(%%rsp)\\n\"\n");
                    } else {
                        sb.append("        \"movq  ").append(jitStackOff[i]).append("(%%rbp), %%rax\\n\"\n");
                        sb.append("        \"movq  %%rax, ").append(dispStackSlots * 8).append("(%%rsp)\\n\"\n");
                    }
                    dispStackSlots++;
                }
            }
        }
        // Finally load rdi = entry.
        sb.append("        \"movq  ").append(entryOffset).append("(%%rsp), %%rdi\\n\"\n");

        // Frame anchor: same private-CodeHeap thunk frame as i2i. The custom
        // c2i path is not installed into Method::_from_compiled_entry today,
        // but keep its anchor coherent for diagnostics/future use.
        sb.append("        \"cmpb $0, g_neko_frame_anchor_ready(%%rip)\\n\"\n");
        sb.append("        \"je   7f\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_fp(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq (%%rbp), %%r11\\n\"\n");
        sb.append("        \"movq %%r11, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_pc(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq 8(%%rbp), %%r11\\n\"\n");
        sb.append("        \"movq %%r11, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_sp(%%rip), %%r10\\n\"\n");
        sb.append("        \"leaq 8(%%rbp), %%r11\\n\"\n");
        sb.append("        \"movq %%r11, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"7:\\n\"\n");
        // Transition _thread_in_Java -> _thread_in_native before C/JNI calls.
        sb.append("        \"cmpb $0, g_neko_thread_state_ready(%%rip)\\n\"\n");
        sb.append("        \"je   4f\\n\"\n");
        sb.append("        \"movq g_neko_off_thread_state(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl g_neko_thread_state_in_native_trans(%%rip), %%r11d\\n\"\n");
        sb.append("        \"movl %%r11d, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"mfence\\n\"\n");
        sb.append("        \"movl g_neko_thread_state_in_native(%%rip), %%r11d\\n\"\n");
        sb.append("        \"movl %%r11d, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"mfence\\n\"\n");
        sb.append("        \"4:\\n\"\n");
        sb.append("        \"call  neko_sig_").append(sigId).append("_dispatch\\n\"\n");
        // Save the return value across the transition-back and safepoint poll.
        sb.append("        \"movq %%rax, ").append(retRaxOffset).append("(%%rsp)\\n\"\n");
        sb.append("        \"movq %%xmm0, ").append(retXmmOffset).append("(%%rsp)\\n\"\n");
        // Transition _thread_in_native -> _thread_in_Java. If the polling
        // word is non-zero, go through the JNI safepoint helper before
        // publishing _thread_in_Java.
        sb.append("        \"cmpb $0, g_neko_thread_state_ready(%%rip)\\n\"\n");
        sb.append("        \"je   5f\\n\"\n");
        sb.append("        \"movq g_neko_off_thread_state(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl g_neko_thread_state_in_native_trans(%%rip), %%r11d\\n\"\n");
        sb.append("        \"movl %%r11d, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"mfence\\n\"\n");
        sb.append("        \"movq g_neko_off_thread_polling_word(%%rip), %%r10\\n\"\n");
        sb.append("        \"testq %%r10, %%r10\\n\"\n");
        sb.append("        \"je   6f\\n\"\n");
        sb.append("        \"movq (%%r15, %%r10, 1), %%r11\\n\"\n");
        sb.append("        \"testq %%r11, %%r11\\n\"\n");
        sb.append("        \"je   6f\\n\"\n");
        sb.append("        \"call  neko_handle_safepoint_poll\\n\"\n");
        sb.append("        \"6:\\n\"\n");
        sb.append("        \"movq g_neko_off_thread_state(%%rip), %%r10\\n\"\n");
        sb.append("        \"movl g_neko_thread_state_in_java(%%rip), %%r11d\\n\"\n");
        sb.append("        \"movl %%r11d, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"5:\\n\"\n");
        sb.append("        \"cmpb $0, g_neko_frame_anchor_ready(%%rip)\\n\"\n");
        sb.append("        \"je   8f\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_sp(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_fp(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_pc(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"8:\\n\"\n");
        sb.append("        \"movq ").append(retRaxOffset).append("(%%rsp), %%rax\\n\"\n");
        sb.append("        \"movq ").append(retXmmOffset).append("(%%rsp), %%xmm0\\n\"\n");
        sb.append("        \"9:\\n\"\n");
        // Restore stack and ret.
        sb.append("        \"movq  %%rbp, %%rsp\\n\"\n");
        sb.append("        \"subq  $8, %%rsp\\n\"\n");
        sb.append("        \"popq  %%rbx\\n\"\n");
        sb.append("        \"popq  %%rbp\\n\"\n");
        sb.append("        \"ret\\n\"\n");
        sb.append("        :\n        :\n        : \"memory\"\n");
        sb.append("    );\n}\n\n");
        return sb.toString();
    }

    private String gpReg(int idx) {
        return switch (idx) {
            case 0 -> "%rdi";
            case 1 -> "%rsi";
            case 2 -> "%rdx";
            case 3 -> "%rcx";
            case 4 -> "%r8";
            case 5 -> "%r9";
            default -> throw new IllegalStateException("gp regs exhausted: " + idx);
        };
    }

    private String javaGpReg(int idx) {
        return switch (idx) {
            case 0 -> "%rsi";
            case 1 -> "%rdx";
            case 2 -> "%rcx";
            case 3 -> "%r8";
            case 4 -> "%r9";
            case 5 -> "%rdi";
            default -> throw new IllegalStateException("java gp regs exhausted: " + idx);
        };
    }
}
