package com.cadence.core.hash;

/**
 * Maps a (flag, user) pair to a stable bucket in {@code [0, 10_000)}.
 *
 * <p>
 * Two properties matter and both are load-bearing:
 *
 * <ol>
 * <li><b>Consistency.</b> The same user always lands in the same bucket for the
 * same flag, so a
 * user never flips between baseline and candidate across requests. Advancing
 * the rollout from
 * 5% to 10% only ever <i>adds</i> users to the candidate; it never reshuffles
 * the existing ones.</li>
 * <li><b>Independence across flags.</b> The flag key is salted into the hash,
 * so a user who is
 * unlucky enough to land in the first 1% of one flag is not automatically in
 * the first 1% of
 * every other flag. Without this, the same small cohort absorbs every canary in
 * the system.</li>
 * </ol>
 *
 * <p>
 * Resolution is 10 000 buckets rather than 100, so a rollout can express
 * fractional percentages
 * (0.01% granularity) without changing the wire format later.
 */
public final class BucketAssigner {

    public static final int TOTAL_BUCKETS = 10_000;
    private static final int SEED = 0x9747b28c;

    private BucketAssigner() {
    }

    /** @return a stable bucket in {@code [0, 10000)} for this flag/user pair. */
    public static int bucket(String flagKey, String userId) {
        int hash = MurmurHash3.hash32(flagKey + ":" + userId, SEED);
        // Math.floorMod, not %, because hash32 can be negative and % would yield a
        // negative bucket.
        return Math.floorMod(hash, TOTAL_BUCKETS);
    }

    /**
     * @param rolloutPercentage 0–100
     * @return true when this user falls inside the rollout slice
     */
    public static boolean inRollout(String flagKey, String userId, int rolloutPercentage) {
        if (rolloutPercentage <= 0) {
            return false;
        }
        if (rolloutPercentage >= 100) {
            return true;
        }
        return bucket(flagKey, userId) < rolloutPercentage * (TOTAL_BUCKETS / 100);
    }
}
