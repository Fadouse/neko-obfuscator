package dev.nekoobfuscator.runtime;

import java.io.*;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and decrypts encrypted resources from the JAR at runtime.
 * Caches decrypted resources using weak references.
 */
public final class NekoResourceLoader {
    private static final ConcurrentHashMap<String, WeakReference<byte[]>> cache = new ConcurrentHashMap<>();
    private static byte[] masterKey;

    private NekoResourceLoader() {}

    public static void init(long masterSeed) {
        masterKey = sha256(longToBytes(masterSeed));
    }

    /**
     * Load and decrypt a resource by its original path.
     */
    public static InputStream getResource(String originalPath, ClassLoader loader) {
        // Check cache
        WeakReference<byte[]> ref = cache.get(originalPath);
        if (ref != null) {
            byte[] cached = ref.get();
            if (cached != null) return new ByteArrayInputStream(cached);
        }

        // Find encrypted resource
        String hashedName = "neko/resources/" + toHex(sha256(originalPath.getBytes())) + ".neko";
        InputStream is = loader.getResourceAsStream(hashedName);
        if (is == null) return null;

        try {
            byte[] payload = readAllBytes(is);
            // Parse: [4 bytes name length][name][encrypted data]
            int nameLen = bytesToInt(payload, 0);
            int dataStart = 4 + nameLen;
            byte[] encrypted = new byte[payload.length - dataStart];
            System.arraycopy(payload, dataStart, encrypted, 0, encrypted.length);

            // Derive key and decrypt
            byte[] resourceKey = deriveResourceKey(originalPath);
            byte[] decrypted = decrypt(encrypted, resourceKey);

            cache.put(originalPath, new WeakReference<>(decrypted));
            return new ByteArrayInputStream(decrypted);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] decrypt(byte[] data, byte[] key) {
        // AES-GCM: first 12 bytes are IV, rest is ciphertext+tag
        int ivLen = 12;
        if (data.length <= ivLen) return data;
        byte[] iv = new byte[ivLen];
        System.arraycopy(data, 0, iv, 0, ivLen);
        byte[] ciphertext = new byte[data.length - ivLen];
        System.arraycopy(data, ivLen, ciphertext, 0, ciphertext.length);

        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(128, iv);
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                java.util.Arrays.copyOf(key, 32), "AES");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, spec);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Resource decryption failed", e);
        }
    }

    private static byte[] deriveResourceKey(String path) {
        byte[] pathBytes = path.getBytes();
        byte[] combined = new byte[masterKey.length + pathBytes.length];
        System.arraycopy(masterKey, 0, combined, 0, masterKey.length);
        System.arraycopy(pathBytes, 0, combined, masterKey.length, pathBytes.length);
        return java.util.Arrays.copyOf(sha256(combined), 32);
    }

    private static byte[] sha256(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] longToBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) { b[i] = (byte)(v & 0xFF); v >>= 8; }
        return b;
    }

    private static int bytesToInt(byte[] b, int off) {
        return ((b[off]&0xFF)<<24) | ((b[off+1]&0xFF)<<16) | ((b[off+2]&0xFF)<<8) | (b[off+3]&0xFF);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v & 0xFF));
        return sb.toString();
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }
}
