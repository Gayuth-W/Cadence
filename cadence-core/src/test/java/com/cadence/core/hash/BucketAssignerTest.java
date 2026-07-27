package com.cadence.core.hash;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BucketAssignerTest {

    @Test
    void sameUserAlwaysLandsInTheSameBucket() {
        int first = BucketAssigner.bucket("checkout.v2", "user-42");
        for (int i = 0; i < 1_000; i++) {
            assertThat(BucketAssigner.bucket("checkout.v2", "user-42")).isEqualTo(first);
        }
    }

    /**
     * The property that makes a rollout safe to advance: raising the percentage may only ever add
     * users to the candidate. A user who was on the candidate at 10% must still be on it at 25%.
     */
    @Test
    void advancingTheRolloutNeverMovesAUserBackToBaseline() {
        for (int i = 0; i < 5_000; i++) {
            String userId = "user-" + i;
            boolean atTen = BucketAssigner.inRollout("checkout.v2", userId, 10);
            boolean atTwentyFive = BucketAssigner.inRollout("checkout.v2", userId, 25);
            if (atTen) {
                assertThat(atTwentyFive)
                        .as("user %s was in the 10%% cohort but dropped out at 25%%", userId)
                        .isTrue();
            }
        }
    }

    /** A user unlucky in one flag must not be unlucky in every flag. */
    @Test
    void bucketsAreIndependentAcrossFlags() {
        Set<Integer> buckets = new HashSet<>();
        for (String flag : new String[]{"flag.a", "flag.b", "flag.c", "flag.d", "flag.e"}) {
            buckets.add(BucketAssigner.bucket(flag, "user-42"));
        }
        assertThat(buckets).as("the same user hashed to identical buckets across flags").hasSizeGreaterThan(1);
    }

    /** A 10% rollout should land within a percentage point of 10% over a large population. */
    @Test
    void distributionIsUniformEnoughForACanary() {
        int inRollout = 0;
        int population = 100_000;
        for (int i = 0; i < population; i++) {
            if (BucketAssigner.inRollout("checkout.v2", "user-" + i, 10)) {
                inRollout++;
            }
        }
        double observed = (double) inRollout / population;
        assertThat(observed).isCloseTo(0.10, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void bucketsAreAlwaysInRange() {
        for (int i = 0; i < 10_000; i++) {
            assertThat(BucketAssigner.bucket("f", "u" + i))
                    .isBetween(0, BucketAssigner.TOTAL_BUCKETS - 1);
        }
    }

    @Test
    void zeroAndHundredPercentAreAbsolute() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(BucketAssigner.inRollout("f", "u" + i, 0)).isFalse();
            assertThat(BucketAssigner.inRollout("f", "u" + i, 100)).isTrue();
        }
    }

    /** Guards the hash itself: changing it silently re-buckets every in-flight rollout. */
    @Test
    void murmurHash3MatchesReferenceVectors() {
        assertThat(MurmurHash3.hash32("", 0)).isEqualTo(0);
        assertThat(MurmurHash3.hash32("", 1)).isEqualTo(0x514E28B7);
        assertThat(MurmurHash3.hash32("aaaa", 0x9747b28c)).isEqualTo(0x5A97808A);
        assertThat(MurmurHash3.hash32("Hello, world!", 0x9747b28c)).isEqualTo(0x24884CBA);
    }
}
