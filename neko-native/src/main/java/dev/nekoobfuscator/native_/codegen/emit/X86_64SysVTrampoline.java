package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Naked-function trampolines for x86_64 SysV (Linux + macOS).
 *
 * HotSpot interpreter calling convention at {@code _i2i_entry} entry:
 *   rbx  = Method*
 *   r13  = sender_sp (interpreter stack just before args)
 *   r15  = JavaThread*
 *   args = on the interpreter expression stack at addresses below r13;
 *          arg0 occupies the highest slot (r13 - 8*totalSlots).
 *   The return address is on the C stack at (rsp).
 *
 * On {@code _from_compiled_entry} the JIT delivers args in the SysV C ABI
 * registers (rdi, rsi, rdx, rcx, r8, r9 for GP; xmm0..7 for FP). We reduce
 * c2i to an interpreter-conforming path by re-entering through i2i: not
 * implemented in this backend yet — c2i emits a {@code ret} so HotSpot's
 * c2i adapter chain will re-enter via the i2i path once the no-compile bits
 * we set keep callers in the interpreter.
 */
public final class X86_64SysVTrampoline {

    public String render(int sigId, SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderI2i(sigId, shape));
        sb.append(renderC2i(sigId));
        return sb.toString();
    }

    private String renderI2i(int sigId, SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        char ret = shape.returnKind();
        char[] args = shape.argKinds();
        int receiverSlot = shape.isStatic() ? 0 : 1;
        int totalSlots = receiverSlot;
        for (char a : args) totalSlots += (a == 'J' || a == 'D') ? 2 : 1;

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
        // scan g_neko_manifest_method_stars for matching rbx
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
        sb.append("        \"xorl  %%eax, %%eax\\n\"\n");
        sb.append("        \"pxor  %%xmm0, %%xmm0\\n\"\n");
        sb.append("        \"jmp   9f\\n\"\n");
        sb.append("        \"2:\\n\"\n");
        // entry = &g_neko_manifest_methods[idx]; first arg in rdi
        sb.append("        \"leaq  g_neko_manifest_methods(%%rip), %%rdi\\n\"\n");
        sb.append("        \"imulq $").append(PatcherLayoutConstants.MANIFEST_METHOD_SIZE)
          .append(", %%rax, %%rcx\\n\"\n");
        sb.append("        \"addq  %%rcx, %%rdi\\n\"\n");

        // Shuffle args from interpreter slots into SysV C ABI
        int gpUsed = 1; // rdi
        int xmmUsed = 0;
        int extraStackSlot = 0;
        if (!shape.isStatic()) {
            int slotOffset = -8 * totalSlots;
            sb.append("        \"movq  ").append(slotOffset).append("(%%r13), %%rsi\\n\"\n");
            gpUsed++;
        }
        int slotsConsumed = receiverSlot;
        for (int i = 0; i < args.length; i++) {
            char a = args[i];
            int slotsForArg = (a == 'J' || a == 'D') ? 2 : 1;
            int slotIndex = totalSlots - slotsConsumed - slotsForArg;
            int slotOffset = -8 * (totalSlots - slotIndex);
            if (a == 'F' || a == 'D') {
                if (xmmUsed < 8) {
                    if (a == 'F') sb.append("        \"movss ");
                    else sb.append("        \"movsd ");
                    sb.append(slotOffset).append("(%%r13), %%xmm").append(xmmUsed).append("\\n\"\n");
                    xmmUsed++;
                } else {
                    sb.append("        \"movq  ").append(slotOffset).append("(%%r13), %%rax\\n\"\n");
                    sb.append("        \"movq  %%rax, ").append(extraStackSlot * 8).append("(%%rsp)\\n\"\n");
                    extraStackSlot++;
                }
            } else {
                if (gpUsed < 6) {
                    sb.append("        \"movq  ").append(slotOffset).append("(%%r13), %").append(gpReg(gpUsed)).append("\\n\"\n");
                    gpUsed++;
                } else {
                    sb.append("        \"movq  ").append(slotOffset).append("(%%r13), %%rax\\n\"\n");
                    sb.append("        \"movq  %%rax, ").append(extraStackSlot * 8).append("(%%rsp)\\n\"\n");
                    extraStackSlot++;
                }
            }
            slotsConsumed += slotsForArg;
        }
        // Frame anchor: write last_Java_sp = sender_sp(r13), fp = saved rbp, pc = ret addr.
        // The original return addr is at [rbp + 8]. Saved rbp is in [rbp].
        sb.append("        \"cmpb $0, g_neko_frame_anchor_ready(%%rip)\\n\"\n");
        sb.append("        \"je   7f\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_sp(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq %%r13, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_fp(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq (%%rbp), %%r11\\n\"\n");
        sb.append("        \"movq %%r11, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_pc(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq 8(%%rbp), %%r11\\n\"\n");
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
        // Clear the frame anchor (only sp matters for HotSpot's "last_Java_sp != 0" tests).
        sb.append("        \"cmpb $0, g_neko_frame_anchor_ready(%%rip)\\n\"\n");
        sb.append("        \"je   8f\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_sp(%%rip), %%r10\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%r10, 1)\\n\"\n");
        sb.append("        \"8:\\n\"\n");
        sb.append("        \"movq -8(%%rbp), %%rax\\n\"\n");
        sb.append("        \"movq -16(%%rbp), %%xmm0\\n\"\n");
        sb.append("        \"9:\\n\"\n");
        // Restore rsp via rbp (we may have re-aligned).
        sb.append("        \"movq  %%rbp, %%rsp\\n\"\n");
        sb.append("        \"popq  %%rbp\\n\"\n");
        // Return to interpreter caller: pop return address, restore rsp from r13, jmp via rax.
        sb.append("        \"movq  (%%rsp), %%r10\\n\"\n");
        sb.append("        \"movq  %%r13, %%rsp\\n\"\n");
        sb.append("        \"jmp   *%%r10\\n\"\n");
        sb.append("        :\n        :\n        : \"memory\"\n");
        sb.append("    );\n}\n\n");
        return sb.toString();
    }

    private String renderC2i(int sigId) {
        return "__attribute__((naked, used, visibility(\"hidden\")))\n"
             + "void neko_sig_" + sigId + "_c2i(void) {\n"
             + "    __asm__ volatile (\n"
             + "        \"ret\\n\"\n"
             + "        :\n        :\n        : \"memory\"\n"
             + "    );\n}\n\n";
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
}
