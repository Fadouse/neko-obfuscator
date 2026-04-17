package dev.nekoobfuscator.native_.resource;

import dev.nekoobfuscator.core.jar.ResourceEntry;
import dev.nekoobfuscator.core.util.ByteUtil;
import dev.nekoobfuscator.core.util.RandomUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.util.*;

/**
 * Encrypts JAR resources with AES-256-GCM using per-resource keys derived from HKDF.
 */
public final class ResourceEncryptor {
    private static final Logger log = LoggerFactory.getLogger(ResourceEncryptor.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final byte[] masterKey;

    public ResourceEncryptor(long masterSeed) {
        this.masterKey = deriveKey(masterSeed);
    }

    public List<ResourceEntry> encryptResources(List<ResourceEntry> resources) {
        List<ResourceEntry> encrypted = new ArrayList<>();
        for (ResourceEntry entry : resources) {
            if (shouldEncrypt(entry.name())) {
                try {
                    byte[] enc = encrypt(entry.data(), entry.name());
                    String hashedName = "neko/resources/" + ByteUtil.toHex(sha256(entry.name().getBytes())) + ".neko";
                    // Prepend original name length + name for runtime lookup
                    byte[] nameBytes = entry.name().getBytes();
                    byte[] payload = new byte[4 + nameBytes.length + enc.length];
                    System.arraycopy(ByteUtil.intToBytes(nameBytes.length), 0, payload, 0, 4);
                    System.arraycopy(nameBytes, 0, payload, 4, nameBytes.length);
                    System.arraycopy(enc, 0, payload, 4 + nameBytes.length, enc.length);
                    encrypted.add(new ResourceEntry(hashedName, payload));
                    log.debug("Encrypted resource: {} -> {}", entry.name(), hashedName);
                } catch (Exception e) {
                    log.warn("Failed to encrypt resource {}: {}", entry.name(), e.getMessage());
                    encrypted.add(entry);
                }
            } else {
                encrypted.add(entry);
            }
        }
        return encrypted;
    }

    private byte[] encrypt(byte[] data, String resourcePath) throws Exception {
        byte[] resourceKey = deriveResourceKey(resourcePath);
        SecretKeySpec keySpec = new SecretKeySpec(resourceKey, "AES");
        byte[] iv = RandomUtil.secureBytes(IV_LENGTH);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        byte[] encryptedData = cipher.doFinal(data);
        // Prepend IV
        byte[] result = new byte[IV_LENGTH + encryptedData.length];
        System.arraycopy(iv, 0, result, 0, IV_LENGTH);
        System.arraycopy(encryptedData, 0, result, IV_LENGTH, encryptedData.length);
        return result;
    }

    private byte[] deriveResourceKey(String path) {
        // Simple HKDF-like derivation
        byte[] pathBytes = path.getBytes();
        byte[] combined = new byte[masterKey.length + pathBytes.length];
        System.arraycopy(masterKey, 0, combined, 0, masterKey.length);
        System.arraycopy(pathBytes, 0, combined, masterKey.length, pathBytes.length);
        return Arrays.copyOf(sha256(combined), 32);
    }

    private byte[] deriveKey(long seed) {
        return Arrays.copyOf(sha256(ByteUtil.longToBytes(seed)), 32);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean shouldEncrypt(String name) {
        return !name.startsWith("META-INF/")
            && !name.startsWith("neko/native/")
            && !name.startsWith("neko/resources/")
            && !name.endsWith(".MF")
            && !name.endsWith(".SF")
            && !name.endsWith(".RSA")
            && !name.endsWith(".DSA");
    }
}
