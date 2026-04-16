package dev.nekoobfuscator.runtime;

import java.io.*;
import java.security.MessageDigest;

/**
 * Custom ClassLoader that loads encrypted classes from the JAR.
 * Encrypted class files are stored as .neko files and decrypted at load time.
 */
public class NekoClassLoader extends ClassLoader {
    private final byte[] decryptionKey;

    public NekoClassLoader(ClassLoader parent) {
        super(parent);
        this.decryptionKey = NekoKeyDerivation.longToBytes(
            NekoKeyDerivation.classKey(NekoClassLoader.class));
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String resourcePath = "neko/" + name.replace('.', '/') + ".neko";
        InputStream is = getResourceAsStream(resourcePath);
        if (is == null) {
            throw new ClassNotFoundException(name);
        }
        try {
            byte[] encrypted = readAllBytes(is);
            byte[] classKey = deriveClassKey(name);
            byte[] classBytes = decrypt(encrypted, classKey);
            return defineClass(name, classBytes, 0, classBytes.length);
        } catch (Exception e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    private byte[] deriveClassKey(String className) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(decryptionKey);
            md.update(className.getBytes("UTF-8"));
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] decrypt(byte[] data, byte[] key) {
        // XOR decryption (matching ResourceEncryptor's encryption)
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return result;
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    // Utility used by NekoKeyDerivation
    static byte[] longToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return bytes;
    }
}
