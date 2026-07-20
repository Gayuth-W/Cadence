package com.cadence.core.hash;

import java.nio.charset.StandardCharsets;

/**
 * MurmurHash3 x86 32-bit, from Austin Appleby's public-domain reference implementation.
 *
 * <p>Implemented here rather than pulled from Guava for one reason: this function defines the
 * traffic split. If the hash ever changes, every user in every in-flight rollout is silently
 * re-bucketed mid-release. Owning the ~40 lines makes that contract explicit and unbreakable
 * by a transitive dependency bump.
 *
 * <p>Chosen over {@code String.hashCode()} because Java's string hash has poor avalanche
 * behaviour on short, similar keys ("user-1", "user-2", ...), which would cluster sequential
 * user IDs into the same buckets and skew a 1% canary.
 */
public final class MurmurHash3 {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private MurmurHash3() {
    }

    public static int hash32(String data, int seed) {
        return hash32(data.getBytes(StandardCharsets.UTF_8), seed);
    }

    public static int hash32(byte[] data, int seed) {
        int h1 = seed;
        final int length = data.length;
        final int blocks = length >>> 2;

        for (int i = 0; i < blocks; i++) {
            int offset = i << 2;
            int k1 = (data[offset] & 0xff)
                    | ((data[offset + 1] & 0xff) << 8)
                    | ((data[offset + 2] & 0xff) << 16)
                    | ((data[offset + 3] & 0xff) << 24);

            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        // Tail: the trailing 1–3 bytes that did not fill a whole block.
        int k1 = 0;
        int tailStart = blocks << 2;
        switch (length - tailStart) {
            case 3:
                k1 ^= (data[tailStart + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 ^= (data[tailStart + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 ^= (data[tailStart] & 0xff);
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= C2;
                h1 ^= k1;
                break;
            default:
                break;
        }

        return fmix32(h1 ^ length);
    }

    /** Finalisation mix: forces all bits of the hash to avalanche. */
    private static int fmix32(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
