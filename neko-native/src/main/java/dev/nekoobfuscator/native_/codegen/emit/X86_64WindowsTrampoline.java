package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Naked-function trampolines for x86_64 Windows.
 *
 * Windows x64 calling convention differs from SysV:
 *   GP args:  rcx, rdx, r8, r9 (then stack)
 *   FP args:  xmm0..3 (then stack)
 *   Caller reserves a 32-byte "shadow space" on the stack
 *   Callee-saved adds: rsi, rdi (in addition to rbx, rbp, r12-r15)
 *
 * HotSpot's interpreter still uses rbx=Method*, r13=sender_sp, r15=JavaThread*
 * on Windows JDK builds (HotSpot's interpreter calling convention is
 * platform-independent above the C-ABI boundary), so the lookup is identical.
 *
 * Argument shuffle and stack reservation differ from SysV.
 */
public final class X86_64WindowsTrampoline {

    public String render(int sigId, SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderI2i(sigId, shape));
        sb.append(renderC2i(sigId));
        return sb.toString();
    }

    private String renderI2i(int sigId, SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        char[] args = shape.argKinds();
        int receiverSlot = shape.isStatic() ? 0 : 1;
        int totalSlots = receiverSlot;
        for (char a : args) totalSlots += (a == 'J' || a == 'D') ? 2 : 1;

        sb.append("/* sig ").append(sigId).append(" i2i (Windows x64, ")
          .append(shape.isStatic() ? "static" : "instance").append(") */\n");
        sb.append("__attribute__((naked, used, visibility(\"hidden\")))\n");
        sb.append("void neko_sig_").append(sigId).append("_i2i(void) {\n");
        sb.append("    __asm__ volatile (\n");
        sb.append("        \"pushq %%rbp\\n\"\n");
        sb.append("        \"movq  %%rsp, %%rbp\\n\"\n");
        // 32-byte shadow + alignment + spill
        sb.append("        \"subq  $160, %%rsp\\n\"\n");
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
        sb.append("        \"leaq  g_neko_manifest_methods(%%rip), %%rcx\\n\"\n");
        sb.append("        \"imulq $").append(PatcherLayoutConstants.MANIFEST_METHOD_SIZE)
          .append(", %%rax, %%r9\\n\"\n");
        sb.append("        \"addq  %%r9, %%rcx\\n\"\n");

        int gpUsed = 1; // rcx (entry)
        int xmmUsed = 0;
        int extraStackSlot = 0;
        if (!shape.isStatic()) {
            int slotOffset = -8 * totalSlots;
            sb.append("        \"movq  ").append(slotOffset).append("(%%r13), %%rdx\\n\"\n");
            gpUsed++;
        }
        int slotsConsumed = receiverSlot;
        for (int i = 0; i < args.length; i++) {
            char a = args[i];
            int slotsForArg = (a == 'J' || a == 'D') ? 2 : 1;
            int slotIndex = totalSlots - slotsConsumed - slotsForArg;
            int slotOffset = -8 * (totalSlots - slotIndex);
            if (a == 'F' || a == 'D') {
                if (xmmUsed < 4) {
                    sb.append("        \"").append(a == 'F' ? "movss " : "movsd ")
                      .append(slotOffset).append("(%%r13), %%xmm").append(xmmUsed).append("\\n\"\n");
                    xmmUsed++;
                    gpUsed++; // Windows: each FP arg also consumes a GP slot
                } else {
                    sb.append("        \"movq  ").append(slotOffset).append("(%%r13), %%rax\\n\"\n");
                    sb.append("        \"movq  %%rax, ").append(32 + extraStackSlot * 8).append("(%%rsp)\\n\"\n");
                    extraStackSlot++;
                }
            } else {
                if (gpUsed < 4) {
                    sb.append("        \"movq  ").append(slotOffset).append("(%%r13), %").append(gpReg(gpUsed)).append("\\n\"\n");
                    gpUsed++;
                } else {
                    sb.append("        \"movq  ").append(slotOffset).append("(%%r13), %%rax\\n\"\n");
                    sb.append("        \"movq  %%rax, ").append(32 + extraStackSlot * 8).append("(%%rsp)\\n\"\n");
                    extraStackSlot++;
                }
            }
            slotsConsumed += slotsForArg;
        }
        sb.append("        \"call  neko_sig_").append(sigId).append("_dispatch\\n\"\n");
        sb.append("        \"9:\\n\"\n");
        sb.append("        \"addq  $160, %%rsp\\n\"\n");
        sb.append("        \"popq  %%rbp\\n\"\n");
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
            case 0 -> "%rcx";
            case 1 -> "%rdx";
            case 2 -> "%r8";
            case 3 -> "%r9";
            default -> throw new IllegalStateException("Win x64 GP regs exhausted: " + idx);
        };
    }
}
