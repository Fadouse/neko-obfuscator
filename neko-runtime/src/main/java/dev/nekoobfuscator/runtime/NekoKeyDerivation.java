package dev.nekoobfuscator.runtime;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * Runtime key derivation functions. Mirrors compile-time derivation exactly.
 * The MASTER_SEED is patched into the bytecode during obfuscation.
 */
public final class NekoKeyDerivation {

    // Patched at obfuscation time via FieldNode.value - different for every build
    // NOT final so the constant isn't inlined by javac
    private static long MASTER_SEED = 0x4E454B4F4F42464CL; // "NEKOOBFL"

    private NekoKeyDerivation() {}

    public static long classKey(Class<?> clazz) {
        long h = MASTER_SEED;
        h = mix(h, clazz.getName().replace('.', '/').hashCode());
        // Use internal name format (slashes) to match compile-time derivation
        Class<?> sup = clazz.getSuperclass();
        h = mix(h, sup != null ? sup.getName().replace('.', '/').hashCode() : 0);
        for (Class<?> iface : clazz.getInterfaces()) {
            h = mix(h, iface.getName().replace('.', '/').hashCode());
        }
        return finalize_(h);
    }

    public static long methodKey(long classKey, int methodContext) {
        return mix(classKey, methodContext);
    }

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

    public static byte[] longToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return bytes;
    }

    public static byte[] getEncField(Class<?> clazz, int fieldIdx) {
        return getBytesField(clazz, "__e" + fieldIdx);
    }

    public static byte[] getBytesField(Class<?> clazz, String fieldName) {
        try {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object value = f.get(null);
            if (value instanceof byte[] bytes) {
                return bytes;
            }
            if (value instanceof String text) {
                return text.getBytes(StandardCharsets.ISO_8859_1);
            }
            throw new IllegalStateException("Unsupported encrypted field type: " + f.getType().getName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
