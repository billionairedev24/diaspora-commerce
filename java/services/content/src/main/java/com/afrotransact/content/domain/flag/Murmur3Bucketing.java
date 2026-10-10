package com.afrotransact.content.domain.flag;

import java.nio.charset.StandardCharsets;

/**
 * MurmurHash3 (x86, 32-bit, seed 0) of {@code flagKey + ":" + subject}, taken unsigned modulo 10,000. Hashing the
 * flag key with the subject keeps one subject's buckets independent across flags, and the same subject always
 * lands in the same bucket for a flag, so raising a rollout only ever adds subjects.
 */
public class Murmur3Bucketing implements Bucketing {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    @Override
    public int bucket(String flagKey, String subject) {
        int h = murmur3x86((flagKey + ":" + subject).getBytes(StandardCharsets.UTF_8), 0);
        return (int) (Integer.toUnsignedLong(h) % BUCKETS);
    }

    /** Reference MurmurHash3_x86_32. */
    public static int murmur3x86(byte[] data, int seed) {
        int h1 = seed;
        int len = data.length;
        int rounded = len & ~3;
        for (int i = 0; i < rounded; i += 4) {
            int k1 = data[i] & 0xff | (data[i + 1] & 0xff) << 8 | (data[i + 2] & 0xff) << 16 | data[i + 3] << 24;
            h1 ^= mixK1(k1);
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }
        int tail = len & 3;
        if (tail > 0) {
            int k1 = 0;
            if (tail == 3) {
                k1 ^= (data[rounded + 2] & 0xff) << 16;
            }
            if (tail >= 2) {
                k1 ^= (data[rounded + 1] & 0xff) << 8;
            }
            k1 ^= data[rounded] & 0xff;
            h1 ^= mixK1(k1);
        }
        h1 ^= len;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }

    private static int mixK1(int k1) {
        int k = k1 * C1;
        k = Integer.rotateLeft(k, 15);
        return k * C2;
    }
}
