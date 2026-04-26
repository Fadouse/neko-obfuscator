package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Naked-function trampolines for AArch64 (Linux, macOS, Windows on ARM).
 *
 * AArch64 calling convention (AAPCS64, mostly identical across OSes):
 *   GP args: x0..x7
 *   FP args: v0..v7 (s0..s7 for single, d0..d7 for double)
 *   Caller-saved: x9..x15
 *   Callee-saved: x19..x29 + d8..d15
 *
 * HotSpot AArch64 interpreter convention:
 *   x12 = Method* (rmethod alias)
 *   x13 = sender_sp (rscratch / rsender)
 *   x28 = JavaThread* (rthread alias)
 * Note: HotSpot's exact aliasing has shifted between major releases; this
 * backend reads Method* from x12 and sender_sp from x13.
 */
public final class Aarch64SysVTrampoline {

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

        sb.append("/* sig ").append(sigId).append(" i2i (AArch64, ")
          .append(shape.isStatic() ? "static" : "instance").append(") */\n");
        sb.append("__attribute__((naked, used, visibility(\"hidden\")))\n");
        sb.append("void neko_sig_").append(sigId).append("_i2i(void) {\n");
        sb.append("    __asm__ volatile (\n");
        // Save fp/lr; reserve stack
        sb.append("        \"stp x29, x30, [sp, #-16]!\\n\"\n");
        sb.append("        \"sub sp, sp, #128\\n\"\n");
        // Scan g_neko_manifest_method_stars[]: compare against x12 (Method*)
        sb.append("        \"adrp x9, g_neko_manifest_method_stars\\n\"\n");
        sb.append("        \"add  x9, x9, :lo12:g_neko_manifest_method_stars\\n\"\n");
        sb.append("        \"adrp x10, g_neko_manifest_method_count\\n\"\n");
        sb.append("        \"ldr  w11, [x10, :lo12:g_neko_manifest_method_count]\\n\"\n");
        sb.append("        \"mov  w10, #0\\n\"\n");
        sb.append("        \"1:\\n\"\n");
        sb.append("        \"cmp  w10, w11\\n\"\n");
        sb.append("        \"b.ge 3f\\n\"\n");
        sb.append("        \"ldr  x14, [x9, x10, lsl #3]\\n\"\n");
        sb.append("        \"cmp  x14, x12\\n\"\n");
        sb.append("        \"b.eq 2f\\n\"\n");
        sb.append("        \"add  w10, w10, #1\\n\"\n");
        sb.append("        \"b 1b\\n\"\n");
        sb.append("        \"3:\\n\"\n");
        sb.append("        \"mov  x0, xzr\\n\"\n");
        sb.append("        \"fmov d0, xzr\\n\"\n");
        sb.append("        \"b 9f\\n\"\n");
        sb.append("        \"2:\\n\"\n");
        // entry pointer in x0
        sb.append("        \"adrp x0, g_neko_manifest_methods\\n\"\n");
        sb.append("        \"add  x0, x0, :lo12:g_neko_manifest_methods\\n\"\n");
        sb.append("        \"mov  x14, #").append(PatcherLayoutConstants.MANIFEST_METHOD_SIZE).append("\\n\"\n");
        sb.append("        \"madd x0, x10, x14, x0\\n\"\n");

        int gpUsed = 1; // x0
        int fpUsed = 0;
        int extraStackSlot = 0;
        if (!shape.isStatic()) {
            int slotOffset = -8 * totalSlots;
            sb.append("        \"ldr  x1, [x13, #").append(slotOffset).append("]\\n\"\n");
            gpUsed++;
        }
        int slotsConsumed = receiverSlot;
        for (int i = 0; i < args.length; i++) {
            char a = args[i];
            int slotsForArg = (a == 'J' || a == 'D') ? 2 : 1;
            int slotIndex = totalSlots - slotsConsumed - slotsForArg;
            int slotOffset = -8 * (totalSlots - slotIndex);
            if (a == 'F' || a == 'D') {
                if (fpUsed < 8) {
                    if (a == 'F') sb.append("        \"ldr  s").append(fpUsed)
                      .append(", [x13, #").append(slotOffset).append("]\\n\"\n");
                    else sb.append("        \"ldr  d").append(fpUsed)
                      .append(", [x13, #").append(slotOffset).append("]\\n\"\n");
                    fpUsed++;
                } else {
                    sb.append("        \"ldr  x14, [x13, #").append(slotOffset).append("]\\n\"\n");
                    sb.append("        \"str  x14, [sp, #").append(extraStackSlot * 8).append("]\\n\"\n");
                    extraStackSlot++;
                }
            } else {
                if (gpUsed < 8) {
                    sb.append("        \"ldr  x").append(gpUsed)
                      .append(", [x13, #").append(slotOffset).append("]\\n\"\n");
                    gpUsed++;
                } else {
                    sb.append("        \"ldr  x14, [x13, #").append(slotOffset).append("]\\n\"\n");
                    sb.append("        \"str  x14, [sp, #").append(extraStackSlot * 8).append("]\\n\"\n");
                    extraStackSlot++;
                }
            }
            slotsConsumed += slotsForArg;
        }
        sb.append("        \"bl   neko_sig_").append(sigId).append("_dispatch\\n\"\n");
        sb.append("        \"9:\\n\"\n");
        sb.append("        \"add  sp, sp, #128\\n\"\n");
        sb.append("        \"ldp  x29, x30, [sp], #16\\n\"\n");
        // Return to interpreter caller: rsp <- x13 then branch to lr
        sb.append("        \"mov  sp, x13\\n\"\n");
        sb.append("        \"ret\\n\"\n");
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
}
