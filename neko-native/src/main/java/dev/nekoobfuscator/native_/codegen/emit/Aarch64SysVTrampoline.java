package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Naked-function trampolines for AArch64 (Linux, macOS, Windows on ARM).
 *
 * AAPCS64 calling convention (effectively identical across the supported OSes):
 *   GP args: x0..x7
 *   FP args: v0..v7 (s0..s7 / d0..d7)
 *   Caller-saved: x9..x15
 *   Callee-saved: x19..x29 + d8..d15
 *   Return: x0 (or v0 for FP)
 *
 * HotSpot AArch64 interpreter convention (per assembler_aarch64.hpp):
 *   x12 = Method*           (rmethod)
 *   x19 = sender_sp         (r19_sender_sp)
 *   x20 = expression stack  (esp)
 *   x22 = bytecode pointer  (rbcp)
 *   x28 = JavaThread*       (rthread)
 *   x29 = frame pointer     (rfp)
 *   x30 = link register     (lr — set by the caller before `br` to _i2i_entry)
 *
 * Args at i2i entry live on the interpreter expression stack indexed
 * downward from {@code esp} (x20); the first-pushed slot (receiver for
 * instance methods) is at the largest offset. The interpreter caller does
 * NOT push a return PC — it loads the invoke-return-entry table address
 * into {@code lr} and uses {@code br}, so the callee returns through
 * {@code lr}.
 *
 * The same private-CodeHeap synthetic-BufferBlob anchor used on x86_64 is
 * emitted here: the thunk frame (saved fp/lr at [sp, sp+8] plus the naked
 * function's matching prologue) is treated as a 3-word HotSpot compiled
 * frame so {@code frame::sender_for_compiled_frame} resolves to the real
 * Java caller and seeds the saved-fp slot in the RegisterMap.
 */
public final class Aarch64SysVTrampoline {

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

        sb.append("/* sig ").append(sigId).append(" i2i (AArch64, ")
          .append(shape.isStatic() ? "static" : "instance")
          .append(", args=\"");
        for (char a : args) sb.append(a);
        sb.append("\", ret=").append(ret).append(") */\n");
        sb.append("__attribute__((naked, used, visibility(\"hidden\")))\n");
        sb.append("void neko_sig_").append(sigId).append("_i2i(void) {\n");
        sb.append("    __asm__ volatile (\n");
        // Standard AAPCS64 prologue: save fp/lr, set up new frame pointer.
        sb.append("        \"stp x29, x30, [sp, #-16]!\\n\"\n");
        sb.append("        \"mov x29, sp\\n\"\n");
        // Reserve scratch + arg-spill area. Keep sp 16-byte aligned.
        sb.append("        \"sub sp, sp, #256\\n\"\n");

        // Manifest scan: compare x12 (Method*) against primary then alias tables.
        sb.append("        \"adrp x9,  g_neko_manifest_method_stars\\n\"\n");
        sb.append("        \"add  x9,  x9, :lo12:g_neko_manifest_method_stars\\n\"\n");
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
        sb.append("        \"adrp x9,  g_neko_manifest_alias_method_stars\\n\"\n");
        sb.append("        \"add  x9,  x9, :lo12:g_neko_manifest_alias_method_stars\\n\"\n");
        sb.append("        \"adrp x14, g_neko_manifest_alias_count\\n\"\n");
        sb.append("        \"ldr  w11, [x14, :lo12:g_neko_manifest_alias_count]\\n\"\n");
        sb.append("        \"mov  w10, #0\\n\"\n");
        sb.append("        \".Lneko_a64_i2i_alias_loop_%=:\\n\"\n");
        sb.append("        \"cmp  w10, w11\\n\"\n");
        sb.append("        \"b.ge .Lneko_a64_i2i_miss_%=\\n\"\n");
        sb.append("        \"ldr  x14, [x9, x10, lsl #3]\\n\"\n");
        sb.append("        \"cmp  x14, x12\\n\"\n");
        sb.append("        \"b.eq .Lneko_a64_i2i_alias_hit_%=\\n\"\n");
        sb.append("        \"add  w10, w10, #1\\n\"\n");
        sb.append("        \"b .Lneko_a64_i2i_alias_loop_%=\\n\"\n");
        sb.append("        \".Lneko_a64_i2i_alias_hit_%=:\\n\"\n");
        sb.append("        \"adrp x14, g_neko_manifest_alias_indices\\n\"\n");
        sb.append("        \"add  x14, x14, :lo12:g_neko_manifest_alias_indices\\n\"\n");
        sb.append("        \"ldr  w10, [x14, x10, lsl #2]\\n\"\n");
        sb.append("        \"b 2f\\n\"\n");
        sb.append("        \".Lneko_a64_i2i_miss_%=:\\n\"\n");
        sb.append("        \"mov  x0, xzr\\n\"\n");
        sb.append("        \"fmov d0, xzr\\n\"\n");
        sb.append("        \"b 9f\\n\"\n");
        sb.append("        \"2:\\n\"\n");
        // entry pointer in x0
        sb.append("        \"adrp x0, g_neko_manifest_methods\\n\"\n");
        sb.append("        \"add  x0, x0, :lo12:g_neko_manifest_methods\\n\"\n");
        sb.append("        \"mov  x14, #").append(PatcherLayoutConstants.MANIFEST_METHOD_SIZE).append("\\n\"\n");
        sb.append("        \"madd x0, x10, x14, x0\\n\"\n");

        // Args layout to dispatcher (AAPCS64):
        //   x0=entry, x1=thread (=x28), x2=receiver_slot_addr (if instance), x3..=args
        // Interpreter args live on the expression stack (esp = x20).
        // Slot N from the top sits at [x20 - (slotN+1)*8] in HotSpot's
        // expression-grows-down convention: the topmost arg is at [x20 - 8],
        // the next at [x20 - 16], etc. The first-pushed (receiver/first arg)
        // is at the LARGEST offset.
        sb.append("        \"mov  x1, x28\\n\"\n");
        int gpUsed = 2; // x0=entry, x1=thread
        int fpUsed = 0;
        int extraStackSlot = 0;
        if (!shape.isStatic()) {
            int slotOffsetFromTop = (totalSlots - receiverIndex) * 8;
            sb.append("        \"sub  x").append(gpUsed).append(", x20, #").append(slotOffsetFromTop).append("\\n\"\n");
            gpUsed++;
        }
        for (int i = 0; i < args.length; i++) {
            char a = args[i];
            int slotOffsetFromTop = (totalSlots - argSlotIndex[i]) * 8;
            if (a == 'F' || a == 'D') {
                if (fpUsed < 8) {
                    String w = (a == 'F') ? "s" : "d";
                    sb.append("        \"sub  x14, x20, #").append(slotOffsetFromTop).append("\\n\"\n");
                    sb.append("        \"ldr  ").append(w).append(fpUsed).append(", [x14]\\n\"\n");
                    fpUsed++;
                } else {
                    sb.append("        \"sub  x14, x20, #").append(slotOffsetFromTop).append("\\n\"\n");
                    sb.append("        \"ldr  x14, [x14]\\n\"\n");
                    sb.append("        \"str  x14, [sp, #").append(extraStackSlot * 8).append("]\\n\"\n");
                    extraStackSlot++;
                }
            } else if (a == 'L') {
                if (gpUsed < 8) {
                    sb.append("        \"sub  x").append(gpUsed).append(", x20, #").append(slotOffsetFromTop).append("\\n\"\n");
                    gpUsed++;
                } else {
                    sb.append("        \"sub  x14, x20, #").append(slotOffsetFromTop).append("\\n\"\n");
                    sb.append("        \"str  x14, [sp, #").append(extraStackSlot * 8).append("]\\n\"\n");
                    extraStackSlot++;
                }
            } else {
                if (gpUsed < 8) {
                    sb.append("        \"sub  x14, x20, #").append(slotOffsetFromTop).append("\\n\"\n");
                    sb.append("        \"ldr  x").append(gpUsed).append(", [x14]\\n\"\n");
                    gpUsed++;
                } else {
                    sb.append("        \"sub  x14, x20, #").append(slotOffsetFromTop).append("\\n\"\n");
                    sb.append("        \"ldr  x14, [x14]\\n\"\n");
                    sb.append("        \"str  x14, [sp, #").append(extraStackSlot * 8).append("]\\n\"\n");
                    extraStackSlot++;
                }
            }
        }
        // Frame anchor: thunk-aware synthetic BufferBlob anchor (frame_size=3).
        //   last_Java_fp = (naked_fp + 0)   = thunk's saved fp value
        //   last_Java_pc = [naked_fp + 8]   = ldp instruction inside thunk
        //   last_Java_sp = naked_fp + 8     = address of ldp PC slot
        emitAnchorPublish(sb, "i2i");
        // Thread state: _thread_in_Java -> _thread_in_native.
        emitThreadStateInNative(sb);
        sb.append("        \"bl   neko_sig_").append(sigId).append("_dispatch\\n\"\n");
        // Save return value (x0 or v0) across transition-back.
        sb.append("        \"str  x0, [x29, #-8]\\n\"\n");
        sb.append("        \"str  d0, [x29, #-16]\\n\"\n");
        // Transition back with safepoint poll.
        emitThreadStateInJavaWithPoll(sb);
        emitAnchorClear(sb, "i2i");
        sb.append("        \"ldr  x0, [x29, #-8]\\n\"\n");
        sb.append("        \"ldr  d0, [x29, #-16]\\n\"\n");
        sb.append("        \"9:\\n\"\n");
        // Restore stack and tail-jump back to interpreter via x19 (sender_sp).
        // The naked function's epilogue ldp restores fp/lr to the values we
        // pushed; on return we jump through lr but with sp = x19 (interpreter
        // sender_sp) so the interpreter's invoke-return-entry sees the
        // expected SP.
        sb.append("        \"mov  sp, x29\\n\"\n");
        sb.append("        \"ldp  x29, x30, [sp], #16\\n\"\n");
        // After ldp: x30 = ldp_pc (return into thunk). We don't want that —
        // we want the original interpreter return PC the caller pre-loaded
        // into x30 BEFORE its `br`. That value was preserved by the thunk's
        // own stp (at [thunk_fp + 8] = [old_naked_fp + 16 + 8] = naked_fp + 24
        // before ldp; after ldp it lives at the slot just popped from the
        // thunk's frame). To recover it, peel one more pair (the thunk's).
        sb.append("        \"ldp  x29, x30, [sp], #16\\n\"\n");
        // Restore real interpreter sender SP and tail-branch.
        sb.append("        \"mov  sp, x19\\n\"\n");
        sb.append("        \"ret\\n\"\n");
        sb.append("        :\n        :\n        : \"memory\"\n");
        sb.append("    );\n}\n\n");
        return sb.toString();
    }

    /**
     * c2i adapter for AArch64. Compiled callers deliver:
     *   x12 = Method*
     *   x0..x7 = JIT GP args (x0 = receiver for instance, else x0 = first)
     *   v0..v7 = JIT FP args
     *   [sp + i*8] = stack-spilled args (when register banks are exhausted)
     *   x30 (lr) = JIT return PC
     *
     * Same shape as SysV c2i: spill ref args (and receiver) to stable stack
     * slots, pass slot addresses to the dispatcher, publish the same
     * thunk-aware anchor as i2i so HotSpot's stack walk seeds saved-fp
     * correctly across the JNI boundary.
     */
    private String renderC2i(int sigId, SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        char ret = shape.returnKind();
        char[] args = shape.argKinds();
        boolean isStatic = shape.isStatic();

        int dispatcherGpUsed = 2 + (isStatic ? 0 : 1);
        int dispatcherFpUsed = 0;
        int dispatcherStackSlots = 0;
        for (char a : args) {
            if (a == 'F' || a == 'D') {
                if (dispatcherFpUsed < 8) dispatcherFpUsed++;
                else dispatcherStackSlots++;
            } else if (dispatcherGpUsed < 8) {
                dispatcherGpUsed++;
            } else {
                dispatcherStackSlots++;
            }
        }

        int refCount = isStatic ? 0 : 1;
        for (char a : args) if (a == 'L') refCount++;
        int outgoingStackBytes = ((dispatcherStackSlots * 8 + 15) & ~15);
        int localBase = outgoingStackBytes;
        int refBase = localBase;
        int entryOffset = refBase + refCount * 8;
        int gpSaveBase = entryOffset + 8;
        int retXmmOffset = gpSaveBase + 64; // 8 GP regs saved
        int retX0Offset = retXmmOffset + 8;
        int localBytes = retX0Offset + 8;
        int frameBytes = ((localBytes + 16 + 15) & ~15);

        sb.append("/* sig ").append(sigId).append(" c2i (AArch64, ").append(isStatic ? "static" : "instance")
          .append(", args=\"");
        for (char a : args) sb.append(a);
        sb.append("\", ret=").append(ret).append(", refCount=").append(refCount).append(") */\n");
        sb.append("__attribute__((naked, used, visibility(\"hidden\")))\n");
        sb.append("void neko_sig_").append(sigId).append("_c2i(void) {\n");
        sb.append("    __asm__ volatile (\n");
        sb.append("        \"stp x29, x30, [sp, #-16]!\\n\"\n");
        sb.append("        \"mov x29, sp\\n\"\n");
        sb.append("        \"sub sp, sp, #").append(frameBytes).append("\\n\"\n");

        // Save all 8 compiled-call GP arg regs to stable slots.
        for (int i = 0; i < 8; i++) {
            sb.append("        \"str  x").append(i).append(", [sp, #").append(gpSaveBase + i * 8).append("]\\n\"\n");
        }

        int spillIndex = 0;
        if (!isStatic) {
            // Receiver was in x0 (instance method, first arg).
            sb.append("        \"ldr  x14, [sp, #").append(gpSaveBase).append("]\\n\"\n");
            sb.append("        \"str  x14, [sp, #").append(refBase + spillIndex * 8).append("]\\n\"\n");
            spillIndex++;
        }
        int gpSrc = isStatic ? 0 : 1;
        int fpSrc = 0;
        int stackSrc = 0;
        int[] jitGpReg = new int[args.length];
        int[] jitFpReg = new int[args.length];
        int[] jitStackOff = new int[args.length];
        java.util.Arrays.fill(jitGpReg, -1);
        java.util.Arrays.fill(jitFpReg, -1);
        java.util.Arrays.fill(jitStackOff, -1);
        for (int i = 0; i < args.length; i++) {
            char a = args[i];
            if (a == 'F' || a == 'D') {
                if (fpSrc < 8) jitFpReg[i] = fpSrc++;
                else { jitStackOff[i] = 16 + stackSrc * 8; stackSrc++; }
            } else {
                if (gpSrc < 8) jitGpReg[i] = gpSrc++;
                else { jitStackOff[i] = 16 + stackSrc * 8; stackSrc++; }
            }
        }
        int[] argSpillSlot = new int[args.length];
        java.util.Arrays.fill(argSpillSlot, -1);
        for (int i = 0; i < args.length; i++) {
            if (args[i] != 'L') continue;
            argSpillSlot[i] = spillIndex;
            int dst = refBase + spillIndex * 8;
            if (jitGpReg[i] >= 0) {
                sb.append("        \"ldr  x14, [sp, #").append(gpSaveBase + jitGpReg[i] * 8).append("]\\n\"\n");
                sb.append("        \"str  x14, [sp, #").append(dst).append("]\\n\"\n");
            } else {
                sb.append("        \"ldr  x14, [x29, #").append(jitStackOff[i]).append("]\\n\"\n");
                sb.append("        \"str  x14, [sp, #").append(dst).append("]\\n\"\n");
            }
            spillIndex++;
        }

        // Manifest scan
        sb.append("        \"adrp x9,  g_neko_manifest_method_stars\\n\"\n");
        sb.append("        \"add  x9,  x9, :lo12:g_neko_manifest_method_stars\\n\"\n");
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
        sb.append("        \"adrp x9,  g_neko_manifest_alias_method_stars\\n\"\n");
        sb.append("        \"add  x9,  x9, :lo12:g_neko_manifest_alias_method_stars\\n\"\n");
        sb.append("        \"adrp x14, g_neko_manifest_alias_count\\n\"\n");
        sb.append("        \"ldr  w11, [x14, :lo12:g_neko_manifest_alias_count]\\n\"\n");
        sb.append("        \"mov  w10, #0\\n\"\n");
        sb.append("        \".Lneko_a64_c2i_alias_loop_%=:\\n\"\n");
        sb.append("        \"cmp  w10, w11\\n\"\n");
        sb.append("        \"b.ge .Lneko_a64_c2i_miss_%=\\n\"\n");
        sb.append("        \"ldr  x14, [x9, x10, lsl #3]\\n\"\n");
        sb.append("        \"cmp  x14, x12\\n\"\n");
        sb.append("        \"b.eq .Lneko_a64_c2i_alias_hit_%=\\n\"\n");
        sb.append("        \"add  w10, w10, #1\\n\"\n");
        sb.append("        \"b .Lneko_a64_c2i_alias_loop_%=\\n\"\n");
        sb.append("        \".Lneko_a64_c2i_alias_hit_%=:\\n\"\n");
        sb.append("        \"adrp x14, g_neko_manifest_alias_indices\\n\"\n");
        sb.append("        \"add  x14, x14, :lo12:g_neko_manifest_alias_indices\\n\"\n");
        sb.append("        \"ldr  w10, [x14, x10, lsl #2]\\n\"\n");
        sb.append("        \"b 2f\\n\"\n");
        sb.append("        \".Lneko_a64_c2i_miss_%=:\\n\"\n");
        sb.append("        \"mov  x0, xzr\\n\"\n");
        sb.append("        \"fmov d0, xzr\\n\"\n");
        sb.append("        \"b 9f\\n\"\n");
        sb.append("        \"2:\\n\"\n");
        // w10 holds the manifest index; x9 will hold base; x14 the per-entry size.
        sb.append("        \"adrp x9,  g_neko_manifest_methods\\n\"\n");
        sb.append("        \"add  x9,  x9, :lo12:g_neko_manifest_methods\\n\"\n");
        sb.append("        \"mov  x14, #").append(PatcherLayoutConstants.MANIFEST_METHOD_SIZE).append("\\n\"\n");
        // entry = idx * size + base. madd treats w10 as zero-extended to 64-bit.
        sb.append("        \"madd x9,  x10, x14, x9\\n\"\n");
        // Spill entry pointer to stable slot.
        sb.append("        \"str  x9,  [sp, #").append(entryOffset).append("]\\n\"\n");

        // Build dispatcher arg list (AAPCS64: x0..x7 then stack starting at sp+0).
        sb.append("        \"mov  x1, x28\\n\"\n");
        int dispGpUsed = 2; // x0, x1 taken
        int dispFpUsed = 0;
        int dispStackSlots = 0;
        if (!isStatic) {
            sb.append("        \"add  x2, sp, #").append(refBase).append("\\n\"\n");
            dispGpUsed++;
        }
        for (int i = 0; i < args.length; i++) {
            char a = args[i];
            if (a == 'L') {
                int srcOff = refBase + argSpillSlot[i] * 8;
                if (dispGpUsed < 8) {
                    sb.append("        \"add  x").append(dispGpUsed).append(", sp, #").append(srcOff).append("\\n\"\n");
                    dispGpUsed++;
                } else {
                    sb.append("        \"add  x14, sp, #").append(srcOff).append("\\n\"\n");
                    sb.append("        \"str  x14, [sp, #").append(dispStackSlots * 8).append("]\\n\"\n");
                    dispStackSlots++;
                }
            } else if (a == 'F' || a == 'D') {
                if (dispFpUsed < 8) {
                    if (jitFpReg[i] >= 0 && jitFpReg[i] != dispFpUsed) {
                        if (a == 'F') sb.append("        \"fmov s").append(dispFpUsed).append(", s").append(jitFpReg[i]).append("\\n\"\n");
                        else sb.append("        \"fmov d").append(dispFpUsed).append(", d").append(jitFpReg[i]).append("\\n\"\n");
                    } else if (jitFpReg[i] < 0) {
                        if (a == 'F') sb.append("        \"ldr  s").append(dispFpUsed).append(", [x29, #").append(jitStackOff[i]).append("]\\n\"\n");
                        else sb.append("        \"ldr  d").append(dispFpUsed).append(", [x29, #").append(jitStackOff[i]).append("]\\n\"\n");
                    }
                    dispFpUsed++;
                } else {
                    if (jitFpReg[i] >= 0) {
                        sb.append("        \"str  d").append(jitFpReg[i]).append(", [sp, #").append(dispStackSlots * 8).append("]\\n\"\n");
                    } else {
                        sb.append("        \"ldr  x14, [x29, #").append(jitStackOff[i]).append("]\\n\"\n");
                        sb.append("        \"str  x14, [sp, #").append(dispStackSlots * 8).append("]\\n\"\n");
                    }
                    dispStackSlots++;
                }
            } else {
                if (dispGpUsed < 8) {
                    if (jitGpReg[i] >= 0) {
                        sb.append("        \"ldr  x").append(dispGpUsed).append(", [sp, #").append(gpSaveBase + jitGpReg[i] * 8).append("]\\n\"\n");
                    } else {
                        sb.append("        \"ldr  x").append(dispGpUsed).append(", [x29, #").append(jitStackOff[i]).append("]\\n\"\n");
                    }
                    dispGpUsed++;
                } else {
                    if (jitGpReg[i] >= 0) {
                        sb.append("        \"ldr  x14, [sp, #").append(gpSaveBase + jitGpReg[i] * 8).append("]\\n\"\n");
                    } else {
                        sb.append("        \"ldr  x14, [x29, #").append(jitStackOff[i]).append("]\\n\"\n");
                    }
                    sb.append("        \"str  x14, [sp, #").append(dispStackSlots * 8).append("]\\n\"\n");
                    dispStackSlots++;
                }
            }
        }
        // Finally load x0 = entry.
        sb.append("        \"ldr  x0, [sp, #").append(entryOffset).append("]\\n\"\n");

        // Frame anchor (thunk-aware), thread state, dispatcher call, transition back.
        emitAnchorPublish(sb, "c2i");
        emitThreadStateInNative(sb);
        sb.append("        \"bl   neko_sig_").append(sigId).append("_dispatch\\n\"\n");
        sb.append("        \"str  x0, [sp, #").append(retX0Offset).append("]\\n\"\n");
        sb.append("        \"str  d0, [sp, #").append(retXmmOffset).append("]\\n\"\n");
        emitThreadStateInJavaWithPoll(sb);
        emitAnchorClear(sb, "c2i");
        sb.append("        \"ldr  x0, [sp, #").append(retX0Offset).append("]\\n\"\n");
        sb.append("        \"ldr  d0, [sp, #").append(retXmmOffset).append("]\\n\"\n");
        sb.append("        \"9:\\n\"\n");
        // Restore stack, fp/lr, return through thunk normally (compiled caller
        // expects a real ret). Compiled callers deliver lr; our naked function
        // restored it via ldp. The thunk's own ldp will restore the caller's lr.
        sb.append("        \"mov  sp, x29\\n\"\n");
        sb.append("        \"ldp  x29, x30, [sp], #16\\n\"\n");
        sb.append("        \"ret\\n\"\n");
        sb.append("        :\n        :\n        : \"memory\"\n");
        sb.append("    );\n}\n\n");
        return sb.toString();
    }

    /** Emit anchor publication: last_Java_fp/pc/sp pointing at the thunk frame. */
    private void emitAnchorPublish(StringBuilder sb, String tag) {
        sb.append("        \"adrp x14, g_neko_frame_anchor_ready\\n\"\n");
        sb.append("        \"ldrb w14, [x14, :lo12:g_neko_frame_anchor_ready]\\n\"\n");
        sb.append("        \"cbz  w14, .Lneko_a64_").append(tag).append("_anchor_skip_%=\\n\"\n");
        // last_Java_fp = (x29) [thunk's saved fp slot value]
        sb.append("        \"adrp x9, g_neko_off_last_Java_fp\\n\"\n");
        sb.append("        \"ldr  x9, [x9, :lo12:g_neko_off_last_Java_fp]\\n\"\n");
        sb.append("        \"ldr  x10, [x29]\\n\"\n");
        sb.append("        \"str  x10, [x28, x9]\\n\"\n");
        // last_Java_pc = (x29 + 8) [return-into-thunk PC]
        sb.append("        \"adrp x9, g_neko_off_last_Java_pc\\n\"\n");
        sb.append("        \"ldr  x9, [x9, :lo12:g_neko_off_last_Java_pc]\\n\"\n");
        sb.append("        \"ldr  x10, [x29, #8]\\n\"\n");
        sb.append("        \"str  x10, [x28, x9]\\n\"\n");
        // last_Java_sp = address of (x29 + 8) — store last so it's the
        // commit point for HotSpot's anchor->walkable() check.
        sb.append("        \"adrp x9, g_neko_off_last_Java_sp\\n\"\n");
        sb.append("        \"ldr  x9, [x9, :lo12:g_neko_off_last_Java_sp]\\n\"\n");
        sb.append("        \"add  x10, x29, #8\\n\"\n");
        sb.append("        \"str  x10, [x28, x9]\\n\"\n");
        sb.append("        \".Lneko_a64_").append(tag).append("_anchor_skip_%=:\\n\"\n");
    }

    /** Emit anchor clear: zero out sp first (validity flag), then fp and pc. */
    private void emitAnchorClear(StringBuilder sb, String tag) {
        sb.append("        \"adrp x14, g_neko_frame_anchor_ready\\n\"\n");
        sb.append("        \"ldrb w14, [x14, :lo12:g_neko_frame_anchor_ready]\\n\"\n");
        sb.append("        \"cbz  w14, .Lneko_a64_").append(tag).append("_anchor_clear_skip_%=\\n\"\n");
        sb.append("        \"adrp x9, g_neko_off_last_Java_sp\\n\"\n");
        sb.append("        \"ldr  x9, [x9, :lo12:g_neko_off_last_Java_sp]\\n\"\n");
        sb.append("        \"str  xzr, [x28, x9]\\n\"\n");
        sb.append("        \"adrp x9, g_neko_off_last_Java_fp\\n\"\n");
        sb.append("        \"ldr  x9, [x9, :lo12:g_neko_off_last_Java_fp]\\n\"\n");
        sb.append("        \"str  xzr, [x28, x9]\\n\"\n");
        sb.append("        \"adrp x9, g_neko_off_last_Java_pc\\n\"\n");
        sb.append("        \"ldr  x9, [x9, :lo12:g_neko_off_last_Java_pc]\\n\"\n");
        sb.append("        \"str  xzr, [x28, x9]\\n\"\n");
        sb.append("        \".Lneko_a64_").append(tag).append("_anchor_clear_skip_%=:\\n\"\n");
    }

    /** Emit transition: _thread_in_Java -> _thread_in_native. */
    private void emitThreadStateInNative(StringBuilder sb) {
        sb.append("        \"adrp x14, g_neko_thread_state_ready\\n\"\n");
        sb.append("        \"ldrb w14, [x14, :lo12:g_neko_thread_state_ready]\\n\"\n");
        sb.append("        \"cbz  w14, .Lneko_a64_state_skip1_%=\\n\"\n");
        sb.append("        \"adrp x9, g_neko_off_thread_state\\n\"\n");
        sb.append("        \"ldr  x9, [x9, :lo12:g_neko_off_thread_state]\\n\"\n");
        sb.append("        \"adrp x10, g_neko_thread_state_in_native\\n\"\n");
        sb.append("        \"ldr  w10, [x10, :lo12:g_neko_thread_state_in_native]\\n\"\n");
        sb.append("        \"str  w10, [x28, x9]\\n\"\n");
        sb.append("        \"dmb  ish\\n\"\n");
        sb.append("        \".Lneko_a64_state_skip1_%=:\\n\"\n");
    }

    /** Emit transition: _thread_in_native -> _thread_in_Java with safepoint poll. */
    private void emitThreadStateInJavaWithPoll(StringBuilder sb) {
        sb.append("        \"adrp x14, g_neko_thread_state_ready\\n\"\n");
        sb.append("        \"ldrb w14, [x14, :lo12:g_neko_thread_state_ready]\\n\"\n");
        sb.append("        \"cbz  w14, .Lneko_a64_state_skip2_%=\\n\"\n");
        sb.append("        \"adrp x9, g_neko_off_thread_state\\n\"\n");
        sb.append("        \"ldr  x9, [x9, :lo12:g_neko_off_thread_state]\\n\"\n");
        sb.append("        \"adrp x10, g_neko_thread_state_in_native_trans\\n\"\n");
        sb.append("        \"ldr  w10, [x10, :lo12:g_neko_thread_state_in_native_trans]\\n\"\n");
        sb.append("        \"str  w10, [x28, x9]\\n\"\n");
        sb.append("        \"dmb  ish\\n\"\n");
        // Poll word check.
        sb.append("        \"adrp x10, g_neko_off_thread_polling_word\\n\"\n");
        sb.append("        \"ldr  x10, [x10, :lo12:g_neko_off_thread_polling_word]\\n\"\n");
        sb.append("        \"cbz  x10, .Lneko_a64_poll_skip_%=\\n\"\n");
        sb.append("        \"ldr  x11, [x28, x10]\\n\"\n");
        sb.append("        \"cbz  x11, .Lneko_a64_poll_skip_%=\\n\"\n");
        sb.append("        \"bl   neko_handle_safepoint_poll\\n\"\n");
        sb.append("        \".Lneko_a64_poll_skip_%=:\\n\"\n");
        // Switch back to _thread_in_Java.
        sb.append("        \"adrp x9, g_neko_off_thread_state\\n\"\n");
        sb.append("        \"ldr  x9, [x9, :lo12:g_neko_off_thread_state]\\n\"\n");
        sb.append("        \"adrp x10, g_neko_thread_state_in_java\\n\"\n");
        sb.append("        \"ldr  w10, [x10, :lo12:g_neko_thread_state_in_java]\\n\"\n");
        sb.append("        \"str  w10, [x28, x9]\\n\"\n");
        sb.append("        \".Lneko_a64_state_skip2_%=:\\n\"\n");
    }
}
