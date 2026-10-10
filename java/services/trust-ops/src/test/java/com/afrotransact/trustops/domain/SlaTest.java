package com.afrotransact.trustops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class SlaTest {

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");
    private final SlaPolicy policy = new DefaultSlaPolicy(SlaTargets.defaults());

    @Test
    void timerGoesOnTrackThenAtRiskInLastQuarterThenBreached() {
        Sla sla = Sla.starting(T0, Duration.ofHours(24));

        assertThat(sla.state(T0)).isEqualTo(SlaState.ON_TRACK);
        assertThat(sla.state(T0.plus(Duration.ofHours(17)))).isEqualTo(SlaState.ON_TRACK);
        assertThat(sla.state(T0.plus(Duration.ofHours(18)))).isEqualTo(SlaState.AT_RISK);
        assertThat(sla.state(T0.plus(Duration.ofHours(23)))).isEqualTo(SlaState.AT_RISK);
        assertThat(sla.state(T0.plus(Duration.ofHours(24)))).isEqualTo(SlaState.BREACHED);
        assertThat(sla.breached(T0.plus(Duration.ofHours(24)))).isTrue();
    }

    @Test
    void remainingAndOverdueNeverGoNegative() {
        Sla sla = Sla.starting(T0, Duration.ofHours(4));

        assertThat(sla.remaining(T0.plus(Duration.ofHours(1)))).isEqualTo(Duration.ofHours(3));
        assertThat(sla.overdue(T0.plus(Duration.ofHours(1)))).isZero();
        assertThat(sla.remaining(T0.plus(Duration.ofHours(6)))).isZero();
        assertThat(sla.overdue(T0.plus(Duration.ofHours(6)))).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void dueBeforeStartIsRejected() {
        assertThatThrownBy(() -> new Sla(T0, T0.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyPicksTheTargetPerQueueAndPriority() {
        assertThat(policy.moderation(T0, ModerationPriority.NORMAL).dueAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(policy.moderation(T0, ModerationPriority.HIGH).dueAt()).isEqualTo(T0.plus(Duration.ofHours(4)));
        assertThat(policy.moderationEscalated(T0).dueAt()).isEqualTo(T0.plus(Duration.ofHours(8)));
        assertThat(policy.sellerResponse(T0).dueAt()).isEqualTo(T0.plus(Duration.ofHours(72)));
        assertThat(policy.claimDecision(T0).dueAt()).isEqualTo(T0.plus(Duration.ofDays(5)));
        assertThat(policy.claimEscalated(T0).dueAt()).isEqualTo(T0.plus(Duration.ofHours(48)));
        assertThat(policy.supportFirstResponse(T0).dueAt()).isEqualTo(T0.plus(Duration.ofHours(4)));
    }
}
