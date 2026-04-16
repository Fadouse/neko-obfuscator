package dev.nekoobfuscator.runtime;

/**
 * String decryption stub using dynamic keys.
 * Decrypts encrypted byte arrays into Strings using the provided key.
 */
public final class NekoStringDecryptor {

    private NekoStringDecryptor() {}

    public static String decrypt(byte[] encrypted, long key) {
        byte[] keyBytes = longToBytes(key);
        byte[] decrypted = new byte[encrypted.length];
        for (int i = 0; i < encrypted.length; i++) {
            decrypted[i] = (byte) (encrypted[i] ^ keyBytes[i % 8]);
        }
        // Remove padding
        int len = decrypted.length;
        while (len > 0 && decrypted[len - 1] == 0) len--;
        try {
            return new String(decrypted, 0, len, "UTF-8");
        } catch (Exception e) {
            return new String(decrypted, 0, len);
        }
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
