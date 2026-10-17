package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanFbaFeeTest {

    private static FeeSchedule withPlanFba(Map<String, Long> planFba) {
        Instant from = Instant.parse("2020-01-01T00:00:00Z");
        return new FeeSchedule(UUID.randomUUID(), 1, from, "USD", Map.of("Groceries", 800), 1000, List.of(), 290,
                Money.of(30, "USD"), Money.of(325, "USD"), true, null, "test", from, null, null, Map.of(), null,
                planFba);
    }

    @Test
    void plansWithoutTheirOwnFeePayTheDefault() {
        FeeSchedule s = withPlanFba(Map.of("Pro", 200L));
        assertThat(s.fbaPerUnitFor("pro").minor()).isEqualTo(200);
        assertThat(s.fbaPerUnitFor("PRO ").minor()).isEqualTo(200);
        assertThat(s.fbaPerUnitFor("starter").minor()).isEqualTo(325);
        assertThat(s.fbaPerUnitFor(null).minor()).isEqualTo(325);
    }

    @Test
    void negativePlanFeesAreRejected() {
        assertThatThrownBy(() -> withPlanFba(Map.of("pro", -1L))).isInstanceOf(PaymentsRuleViolation.class);
    }
}
