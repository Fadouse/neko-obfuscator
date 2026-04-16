package dev.nekoobfuscator.core.util;

import java.security.SecureRandom;

/**
 * Cryptographically strong random number generation for obfuscation.
 */
public final class RandomUtil {
    private static final SecureRandom SECURE = new SecureRandom();

    private final SecureRandom random;

    public RandomUtil() {
        this.random = new SecureRandom();
    }

    public RandomUtil(long seed) {
        this.random = new SecureRandom();
        this.random.setSeed(seed);
    }

    public int nextInt() { return random.nextInt(); }
    public int nextInt(int bound) { return random.nextInt(bound); }
    public long nextLong() { return random.nextLong(); }
    public byte[] nextBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    public int nextIntRange(int min, int max) {
        return min + random.nextInt(max - min);
    }

    public boolean nextBoolean() { return random.nextBoolean(); }
    public double nextDouble() { return random.nextDouble(); }

    /**
     * Generate a random int that is NOT in the given set.
     */
    public int nextIntExcluding(java.util.Set<Integer> exclude) {
        int v;
        do { v = random.nextInt(); } while (exclude.contains(v));
        return v;
    }

    /**
     * Generate a random permutation of [0, n).
     */
    public int[] randomPermutation(int n) {
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
        }
        return perm;
    }

    public static long secureLong() { return SECURE.nextLong(); }
    public static byte[] secureBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE.nextBytes(bytes);
        return bytes;
    }
}
