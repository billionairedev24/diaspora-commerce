package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class DefaultShelfLifePolicyTest {

    private final ShelfLifePolicy policy = new DefaultShelfLifePolicy(90, 0.5);
    private final LocalDate today = Lots.TODAY;

    @Test
    void acceptsLotWithEnoughRemainingLife() {
        ShelfLifeDecision d = policy.assess(today.minusDays(30), today.plusDays(500), 540, today);
        assertThat(d.outcome()).isEqualTo(ShelfLifeDecision.Outcome.ACCEPT);
        assertThat(d.requiredDays()).isEqualTo(270);
    }

    @Test
    void halfOfShelfLifeWinsWhenLongerThanNinetyDays() {
        // 18-month (548 day) product with 200 days left: needs 274 → quarantine
        ShelfLifeDecision d = policy.assess(today.minusDays(348), today.plusDays(200), 548, today);
        assertThat(d.outcome()).isEqualTo(ShelfLifeDecision.Outcome.QUARANTINE);
        assertThat(d.requiredDays()).isEqualTo(274);
        assertThat(d.reason()).contains("Short-dated");
    }

    @Test
    void ninetyDaysWinsForShortShelfLife() {
        // 120-day product: 50% = 60, so the 90-day floor applies; 80 days left → quarantine, 95 → accept
        assertThat(policy.assess(today.minusDays(40), today.plusDays(80), 120, today).outcome())
                .isEqualTo(ShelfLifeDecision.Outcome.QUARANTINE);
        assertThat(policy.assess(today.minusDays(25), today.plusDays(95), 120, today).outcome())
                .isEqualTo(ShelfLifeDecision.Outcome.ACCEPT);
    }

    @Test
    void derivesShelfLifeFromDatesWhenNotGiven() {
        ShelfLifeDecision d = policy.assess(today.minusDays(600), today.plusDays(200), null, today);
        assertThat(d.requiredDays()).isEqualTo(400);
        assertThat(d.outcome()).isEqualTo(ShelfLifeDecision.Outcome.QUARANTINE);
    }

    @Test
    void rejectsExpiredAndImpossibleDates() {
        assertThat(policy.assess(today.minusDays(400), today, 365, today).outcome())
                .isEqualTo(ShelfLifeDecision.Outcome.REJECT);
        assertThat(policy.assess(today.minusDays(400), today.minusDays(1), 365, today).outcome())
                .isEqualTo(ShelfLifeDecision.Outcome.REJECT);
        assertThat(policy.assess(today.plusDays(2), today.plusDays(300), 365, today).outcome())
                .isEqualTo(ShelfLifeDecision.Outcome.REJECT);
        assertThat(policy.assess(today.minusDays(1), today.minusDays(5), null, today).outcome())
                .isEqualTo(ShelfLifeDecision.Outcome.REJECT);
    }

    @Test
    void nonPerishableLotsAreAccepted() {
        assertThat(policy.assess(null, null, null, today).outcome()).isEqualTo(ShelfLifeDecision.Outcome.ACCEPT);
    }
}
