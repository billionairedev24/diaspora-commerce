package com.afrotransact.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import com.afrotransact.ai.application.AiSpendCapPolicy.Cap;
import com.afrotransact.ai.application.AiSpendCapPolicy.Source;
import com.afrotransact.ai.domain.SpendCapSettings;
import org.junit.jupiter.api.Test;

class AiSpendCapPolicyTest {

    private static final long DEFAULT = 2_000_000;
    private static final long CEILING = 20_000_000;

    private final InMemorySpendCapStore store = new InMemorySpendCapStore();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));

    private AiSpendCapPolicyImpl policy(long def, long ceiling) {
        return new AiSpendCapPolicyImpl(store, new SpendCapSettings(def, ceiling, Duration.ofSeconds(10)), clock);
    }

    @Test
    void usesTheDeploymentDefaultUntilAValueIsSaved() {
        Cap cap = policy(DEFAULT, CEILING).current();

        assertThat(cap.capMicros()).isEqualTo(DEFAULT);
        assertThat(cap.source()).isEqualTo(Source.DEFAULT);
        assertThat(cap.version()).isZero();
        assertThat(cap.ceilingMicros()).isEqualTo(CEILING);
        assertThat(cap.changedBy()).isNull();
    }

    @Test
    void aSavedValueReplacesTheDefaultAndKeepsWhoAndWhy() {
        store.save(5_000_000, 0, "staff-1", "Launch week", clock.instant());

        Cap cap = policy(DEFAULT, CEILING).current();

        assertThat(cap.capMicros()).isEqualTo(5_000_000);
        assertThat(cap.source()).isEqualTo(Source.SAVED);
        assertThat(cap.version()).isEqualTo(1);
        assertThat(cap.changedBy()).isEqualTo("staff-1");
        assertThat(cap.reason()).isEqualTo("Launch week");
        assertThat(cap.defaultMicros()).isEqualTo(DEFAULT);
    }

    @Test
    void zeroIsAValidSavedValueAndMeansAiOff() {
        store.save(0, 0, "staff-1", "Pause AI", clock.instant());

        assertThat(policy(DEFAULT, CEILING).capMicros()).isZero();
    }

    @Test
    void aCeilingLoweredAfterASaveStillWins() {
        store.save(15_000_000, 0, "staff-1", "Big promotion", clock.instant());

        Cap cap = policy(DEFAULT, 4_000_000).current();

        assertThat(cap.capMicros()).isEqualTo(4_000_000);
        assertThat(cap.source()).isEqualTo(Source.SAVED);
    }

    @Test
    void keepsWhatItReadForTheCacheWindowThenLooksAgain() {
        AiSpendCapPolicyImpl policy = policy(DEFAULT, CEILING);
        assertThat(policy.capMicros()).isEqualTo(DEFAULT);
        assertThat(store.reads).isEqualTo(1);

        store.save(6_000_000, 0, "staff-1", "More", clock.instant());
        clock.advance(Duration.ofSeconds(9));
        assertThat(policy.capMicros()).as("still cached").isEqualTo(DEFAULT);
        assertThat(store.reads).isEqualTo(1);

        clock.advance(Duration.ofSeconds(2));
        assertThat(policy.capMicros()).as("a new cap is seen within the TTL").isEqualTo(6_000_000);
        assertThat(store.reads).isEqualTo(2);
    }

    @Test
    void invalidateMakesTheNextReadSeeTheNewCapAtOnce() {
        AiSpendCapPolicyImpl policy = policy(DEFAULT, CEILING);
        assertThat(policy.capMicros()).isEqualTo(DEFAULT);

        store.save(1_000_000, 0, "staff-1", "Lower", clock.instant());
        policy.invalidate();

        assertThat(policy.capMicros()).isEqualTo(1_000_000);
    }

    @Test
    void ifTheDatabaseCannotBeReadTheLastKnownValueIsKeptNeverUnlimited() {
        AiSpendCapPolicyImpl policy = policy(DEFAULT, CEILING);
        store.save(3_000_000, 0, "staff-1", "Three", clock.instant());
        assertThat(policy.capMicros()).isEqualTo(3_000_000);

        store.failing = true;
        clock.advance(Duration.ofSeconds(60));
        assertThat(policy.capMicros()).isEqualTo(3_000_000);

        policy.invalidate();
        assertThat(policy.capMicros()).as("nothing known: the default, still bounded").isEqualTo(DEFAULT);
    }

    @Test
    void theCacheWindowCannotExceedThirtySeconds() {
        assertThatThrownBy(
                () -> new SpendCapSettings(DEFAULT, CEILING, Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
