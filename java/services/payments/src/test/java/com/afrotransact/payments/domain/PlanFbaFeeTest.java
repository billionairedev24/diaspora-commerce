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

    @Test
    void aNegotiatedFbaFeeWinsOverThePlanFeeWhileActive() {
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        NegotiatedRate deal = NegotiatedRate.create(UUID.randomUUID(), "org_a", NegotiatedRateType.FBA_FEE, "fashion", 0,
                150L, null, now.plusSeconds(86_400), "Volume deal", null, null, "fin-1", now);
        assertThat(deal.category()).isEqualTo("*");
        assertThat(deal.rateBps()).isZero();
        FeeSchedule s = withPlanFba(Map.of("pro", 200L));
        assertThat(FeeSchedule.negotiatedFba("org_a", List.of(deal), now).map(NegotiatedRate::amountMinor)).contains(150L);
        assertThat(FeeSchedule.negotiatedFba("org_b", List.of(deal), now)).isEmpty();
        assertThat(FeeSchedule.negotiatedFba("org_a", List.of(deal), now.plusSeconds(2 * 86_400))).isEmpty();
        assertThat(s.effectiveFor(FeeKind.PRODUCT, "org_a", "fashion", "pro", List.of(deal), now, "retail")
                .fbaPerUnit().minor()).isEqualTo(150);
        assertThatThrownBy(() -> NegotiatedRate.create(UUID.randomUUID(), "org_a", NegotiatedRateType.FBA_FEE, null, 0,
                null, null, null, "x", null, null, "fin-1", now)).isInstanceOf(PaymentsRuleViolation.class);
    }
}
