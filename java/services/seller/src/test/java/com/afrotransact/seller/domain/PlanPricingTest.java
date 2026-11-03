package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanPricingTest {

    private static final Plan ESSENTIALS = plan("seller_essentials", 0, 0);
    private static final Plan GROWTH = plan("seller_growth", 3900, 30);
    private static final Plan PRO = plan("seller_pro", 12900, 14);
    private static final Plan STUDIO = Plan.standard("provider_studio", OrgKind.PROVIDER, "Provider Studio", null, 4900,
            30, List.of(), 20);

    @Test
    void monthlyIsTheListPrice() {
        PlanQuote q = PlanPricing.quote(GROWTH, Cadence.MONTHLY);
        assertThat(q.totalCents()).isEqualTo(3900);
        assertThat(q.perMonthCents()).isEqualTo(3900);
        assertThat(q.savingCents()).isZero();
    }

    @Test
    void quarterlyIsFivePercentOff() {
        PlanQuote q = PlanPricing.quote(STUDIO, Cadence.QUARTERLY);
        assertThat(q.totalCents()).isEqualTo(13965);
        assertThat(q.perMonthCents()).isEqualTo(4655);
        assertThat(q.savingCents()).isEqualTo(735);
        assertThat(PlanPricing.quote(GROWTH, Cadence.QUARTERLY).totalCents()).isEqualTo(11115);
        assertThat(PlanPricing.quote(PRO, Cadence.QUARTERLY).totalCents()).isEqualTo(36765);
    }

    @Test
    void annualIsTwoMonthsFree() {
        PlanQuote q = PlanPricing.quote(PRO, Cadence.ANNUAL);
        assertThat(q.totalCents()).isEqualTo(129000);
        assertThat(q.perMonthCents()).isEqualTo(10750);
        assertThat(q.savingCents()).isEqualTo(25800);
    }

    @Test
    void freePlanCostsNothingOnEveryCadence() {
        for (Cadence c : Cadence.values()) {
            PlanQuote q = PlanPricing.quote(ESSENTIALS, c);
            assertThat(q.totalCents()).isZero();
            assertThat(q.savingCents()).isZero();
        }
        assertThat(PlanPricing.firstCharge(ESSENTIALS, Cadence.MONTHLY, LocalDate.of(2026, 10, 10)).chargeOn())
                .isNull();
    }

    @Test
    void firstChargeSentenceIncludesTrialAmountAndCadence() {
        FirstCharge f = PlanPricing.firstCharge(STUDIO, Cadence.QUARTERLY, LocalDate.of(2026, 10, 10));
        assertThat(f.chargeOn()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(f.amountCents()).isEqualTo(13965);
        assertThat(f.sentence()).isEqualTo("Free for 30 days. First charge of $139.65 on Nov 9, then every 3 months. "
                + "Cancel any time before then.");
        assertThat(PlanPricing.usd(3900)).isEqualTo("$39");
    }

    @Test
    void upgradesAreImmediateDowngradesAndCadenceChangesWaitForRenewal() {
        assertThat(PlanChange.decide(GROWTH, Cadence.MONTHLY, PRO, Cadence.MONTHLY))
                .isEqualTo(PlanChange.IMMEDIATE_UPGRADE);
        assertThat(PlanChange.decide(PRO, Cadence.MONTHLY, GROWTH, Cadence.MONTHLY)).isEqualTo(PlanChange.AT_RENEWAL);
        assertThat(PlanChange.decide(GROWTH, Cadence.MONTHLY, GROWTH, Cadence.ANNUAL)).isEqualTo(PlanChange.AT_RENEWAL);
        assertThat(PlanChange.decide(GROWTH, Cadence.MONTHLY, PRO, Cadence.ANNUAL)).isEqualTo(PlanChange.AT_RENEWAL);
        assertThat(PlanChange.decide(GROWTH, Cadence.QUARTERLY, GROWTH, Cadence.QUARTERLY))
                .isEqualTo(PlanChange.NO_CHANGE);
    }

    @Test
    void cadenceParsingIsLenient() {
        assertThat(Cadence.parse("quarter")).isEqualTo(Cadence.QUARTERLY);
        assertThat(Cadence.parse("Annually")).isEqualTo(Cadence.ANNUAL);
        assertThat(Cadence.parse("month")).isEqualTo(Cadence.MONTHLY);
    }

    private static Plan plan(String code, long cents, int trial) {
        return Plan.standard(code, OrgKind.SELLER, code, null, cents, trial, List.of(), 0);
    }
}
