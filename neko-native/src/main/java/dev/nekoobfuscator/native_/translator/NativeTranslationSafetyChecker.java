package dev.nekoobfuscator.native_.translator;

import dev.nekoobfuscator.core.ir.l1.L1Method;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.List;

/**
 * Rejects methods whose bytecode patterns are not yet safely supported by the native translator.
 */
public final class NativeTranslationSafetyChecker {
    public boolean isSafe(L1Method method, List<String> reasons) {
        if (method.isClassInit()) {
            reasons.add("class initializers are not translated");
        }
        if (method.isConstructor()) {
            reasons.add("constructors remain in bytecode form");
        }
        if ((method.access() & Opcodes.ACC_BRIDGE) != 0) {
            reasons.add("bridge methods are skipped");
        }
        if (method.isAbstract() || method.isNative() || !method.hasCode()) {
            reasons.add("method has no translatable bytecode body");
        }

        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            switch (opcode) {
                case Opcodes.JSR, Opcodes.RET -> reasons.add("JSR/RET bytecode is not supported");
                default -> {
                }
            }
        }

        return reasons.isEmpty();
    }
}
