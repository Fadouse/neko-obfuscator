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
        sb.append("        \"subq  $128, %%rsp\\n\"\n");
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
        sb.append("        \"call  neko_sig_").append(sigId).append("_dispatch\\n\"\n");
        sb.append("        \"9:\\n\"\n");
        sb.append("        \"addq  $128, %%rsp\\n\"\n");
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
