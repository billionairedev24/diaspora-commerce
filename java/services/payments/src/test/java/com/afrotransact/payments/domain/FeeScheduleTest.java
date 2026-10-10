package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FeeScheduleTest {

    private static final Instant V1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant V2 = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void ordersUseTheVersionInForceWhenPlaced() {
        FeeSchedule v1 = Fixtures.schedule(1, V1, List.of());
        FeeSchedule v2 = Fixtures.schedule(2, V2, List.of());
        List<FeeSchedule> history = List.of(v2, v1);
        assertThat(FeeSchedule.activeAt(history, V1.minusSeconds(1))).isEmpty();
        assertThat(FeeSchedule.activeAt(history, V1)).contains(v1);
        assertThat(FeeSchedule.activeAt(history, V2.minusSeconds(1))).contains(v1);
        assertThat(FeeSchedule.activeAt(history, V2)).contains(v2);
        FeeSchedule v3sameTime = Fixtures.schedule(3, V2, List.of());
        assertThat(FeeSchedule.activeAt(List.of(v1, v2, v3sameTime), V2.plusSeconds(5))).contains(v3sameTime);
    }

    @Test
    void newVersionsCannotRewriteThePast() {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        Optional<FeeSchedule> latest = Optional.of(Fixtures.schedule(2, V2, List.of()));
        FeeSchedule.requireValidSuccessor(now, latest, now);
        FeeSchedule.requireValidSuccessor(now.plusSeconds(86_400), latest, now);
        assertThatThrownBy(() -> FeeSchedule.requireValidSuccessor(now.minusSeconds(1), latest, now))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.fee-schedule-backdated"));
        Optional<FeeSchedule> future = Optional.of(Fixtures.schedule(3, now.plusSeconds(3600), List.of()));
        assertThatThrownBy(() -> FeeSchedule.requireValidSuccessor(now.plusSeconds(60), future, now))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.fee-schedule-out-of-order"));
    }

    @Test
    void ratesAreValidatedAndCategoriesCaseInsensitive() {
        FeeSchedule s = Fixtures.schedule();
        assertThat(s.commissionBps("org_x", "GROCERIES")).isEqualTo(800);
        assertThat(s.commissionBps("org_x", "Unlisted")).isEqualTo(1000);
        assertThat(s.estimateCardFee(Fixtures.usd(10_000)).minor()).isEqualTo(320);
        assertThat(s.estimateCardFee(Fixtures.usd(0)).minor()).isZero();
        assertThatThrownBy(() -> new FeeSchedule(UUID.randomUUID(), 1, V1, "USD", Map.of("x", 10_001), 0,
                List.of(), 0, Fixtures.usd(0), Fixtures.usd(0), true, null, "t", V1))
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> new CommissionOverride("org", "*", -1)).isInstanceOf(PaymentsRuleViolation.class);
    }
}
