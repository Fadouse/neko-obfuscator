package dev.nekoobfuscator.transforms.key;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.util.AsmUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Central engine for multi-layer dynamic key derivation.
 * Mirrors NekoKeyDerivation (runtime) at compile time.
 *
 * Three layers:
 *   Layer 1: Class key    = f(masterSeed, className, fieldCount, methodCount, superClass, interfaces)
 *   Layer 2: Method key   = f(classKey, methodName, descriptor, instructionCount)
 *   Layer 3: Instruction key = f(methodKey, insnIndex, salt, controlFlowState)
 */
public final class DynamicKeyDerivationEngine {
    private final long masterSeed;

    public DynamicKeyDerivationEngine(long masterSeed) {
        this.masterSeed = masterSeed;
    }

    public long masterSeed() { return masterSeed; }

    // ===== Compile-time key derivation =====

    public long deriveClassKey(L1Class clazz) {
        long h = masterSeed;
        h = mix(h, clazz.name().hashCode());
        // Use only stable properties - not field/method count which changes during obfuscation
        long superHash = (!clazz.isInterface() && clazz.superName() != null)
            ? clazz.superName().hashCode()
            : 0;
        h = mix(h, superHash);
        for (String iface : clazz.interfaces()) {
            h = mix(h, iface.hashCode());
        }
        return finalize_(h);
    }

    public long deriveMethodKey(L1Method method, long classKey) {
        long h = classKey;
        h = mix(h, method.name().hashCode());
        h = mix(h, method.descriptor().hashCode());
        h = mix(h, method.instructionCount());
        return h;
    }

    public long deriveInsnKey(long methodKey, int insnIndex, int salt) {
        long h = methodKey;
        h = mix(h, insnIndex);
        h = mix(h, salt);
        return h;
    }

    // ===== Key mixing function (SipHash-like) =====

    public static long mix(long state, long input) {
        state ^= input;
        state *= 0x9E3779B97F4A7C15L;
        state = Long.rotateLeft(state, 31);
        state *= 0xBF58476D1CE4E5B9L;
        return state;
    }

    public static long finalize_(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    // ===== Bytecode generation for runtime key derivation =====

    /**
     * Generate bytecode that computes the class key at runtime and stores in a static field.
     * Injected into the class's <clinit>.
     *
     * @param clazz target class
     * @param fieldName name of the static long field to store the key
     */
    public InsnList generateClassKeyInit(L1Class clazz, String fieldName) {
        InsnList insns = new InsnList();

        // long h = MASTER_SEED;
        insns.add(new LdcInsnNode(masterSeed));

        // h = mix(h, Class.getName().replace('.','/').hashCode())
        insns.add(new LdcInsnNode(clazz.name().replace('/', '.')));
        // We compute the hash at obfuscation time and embed as constant
        // This is equivalent to runtime computation but faster
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new LdcInsnNode((long) clazz.name().hashCode()));
        emitMix(insns);

        // h = mix(h, fieldCount)
        insns.add(new LdcInsnNode((long) clazz.fields().size()));
        emitMix(insns);

        // h = mix(h, methodCount)
        insns.add(new LdcInsnNode((long) clazz.methods().size()));
        emitMix(insns);

        // h = mix(h, superClass.hashCode())
        long superHash = clazz.superName() != null ? clazz.superName().hashCode() : 0;
        insns.add(new LdcInsnNode(superHash));
        emitMix(insns);

        // interfaces
        for (String iface : clazz.interfaces()) {
            insns.add(new LdcInsnNode((long) iface.hashCode()));
            emitMix(insns);
        }

        // finalize
        emitFinalize(insns);

        // Store to static field
        insns.add(new FieldInsnNode(Opcodes.PUTSTATIC, clazz.name(), fieldName, "J"));

        return insns;
    }

    /**
     * Emit bytecode for: top_of_stack = mix(top_of_stack_below, top_of_stack)
     * Expects stack: [..., long state, long input]
     * Leaves: [..., long mixed]
     */
    private void emitMix(InsnList insns) {
        // state ^= input
        insns.add(new InsnNode(Opcodes.LXOR));
        // state *= 0x9E3779B97F4A7C15L
        insns.add(new LdcInsnNode(0x9E3779B97F4A7C15L));
        insns.add(new InsnNode(Opcodes.LMUL));
        // state = Long.rotateLeft(state, 31) -- implemented as (state << 31) | (state >>> 33)
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(AsmUtil.pushIntAny(31));
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.DUP2_X2));
        insns.add(new InsnNode(Opcodes.POP2));
        insns.add(AsmUtil.pushIntAny(33));
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LOR));
        // state *= 0xBF58476D1CE4E5B9L
        insns.add(new LdcInsnNode(0xBF58476D1CE4E5B9L));
        insns.add(new InsnNode(Opcodes.LMUL));
    }

    private void emitFinalize(InsnList insns) {
        // h ^= h >>> 33
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(AsmUtil.pushIntAny(33));
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        // h *= 0xFF51AFD7ED558CCDL
        insns.add(new LdcInsnNode(0xFF51AFD7ED558CCDL));
        insns.add(new InsnNode(Opcodes.LMUL));
        // h ^= h >>> 33
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(AsmUtil.pushIntAny(33));
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        // h *= 0xC4CEB9FE1A85EC53L
        insns.add(new LdcInsnNode(0xC4CEB9FE1A85EC53L));
        insns.add(new InsnNode(Opcodes.LMUL));
        // h ^= h >>> 33
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(AsmUtil.pushIntAny(33));
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
    }

    // ===== Encryption helpers =====

    /**
     * Encrypt bytes using XOR with a key derived from the given long key.
     */
    public static byte[] encrypt(byte[] data, long key) {
        byte[] keyBytes = longToBytes(key);
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ keyBytes[i % 8]);
        }
        return result;
    }

    private static byte[] longToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return bytes;
    }
}
