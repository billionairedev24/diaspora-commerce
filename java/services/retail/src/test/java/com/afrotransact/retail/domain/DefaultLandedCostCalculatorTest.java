package com.afrotransact.retail.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class DefaultLandedCostCalculatorTest {

    private final LandedCostCalculator calculator = new DefaultLandedCostCalculator(new DefaultCostAllocator());

    /** SHP-2610-GH04 from the design: jollof 900 × $1.30 (3.2% duty), shito 400 × $2.25 (6.4% duty). */
    private static List<LandedCostInput> gh04() {
        return List.of(new LandedCostInput(1, "AT-JOL-6", 900, 117_000, 200, new BigDecimal("3.2")),
                new LandedCostInput(2, "AT-SHI-12", 400, 90_000, 400, new BigDecimal("6.4")));
    }

    private static LandedCharge usd(ChargeType type, long amount, AllocationBasis basis) {
        return new LandedCharge(type, amount, "USD", basis, null);
    }

    @Test
    void reproducesTheDesignsValueBasedLandedCost() {
        List<LandedCharge> charges = List.of(usd(ChargeType.FREIGHT, 238_000, AllocationBasis.VALUE),
                usd(ChargeType.INSURANCE, 6_000, AllocationBasis.VALUE),
                usd(ChargeType.FEES, 4_000, AllocationBasis.VALUE),
                usd(ChargeType.BROKERAGE, 18_000, AllocationBasis.VALUE),
                usd(ChargeType.DRAYAGE, 22_000, AllocationBasis.VALUE),
                usd(ChargeType.RECEIVING, 14_000, AllocationBasis.VALUE));
        LandedCost lc = calculator.calculate("USD", gh04(), charges);

        assertThat(lc.fobMinor()).isEqualTo(207_000);
        assertThat(lc.dutyMinor()).isEqualTo(3_744 + 5_760);
        assertThat(lc.chargesMinor()).isEqualTo(302_000);
        assertThat(lc.totalMinor()).isEqualTo(207_000 + 9_504 + 302_000);
        LandedCostLine jollof = lc.line(1);
        assertThat(jollof.dutyMinor()).isEqualTo(3_744);
        assertThat(jollof.charges()).hasSize(6);
        assertThat(jollof.totalMinor()).isEqualTo(117_000 + 3_744 + 170_692);
        assertThat(jollof.unitCostMinor()).isEqualTo(324);
        LandedCostLine shito = lc.line(2);
        assertThat(shito.totalMinor()).isEqualTo(90_000 + 5_760 + 131_308);
        assertThat(shito.unitCostMinor()).isEqualTo(568);
        assertThat(lc.lines().stream().mapToLong(LandedCostLine::totalMinor).sum()).isEqualTo(lc.totalMinor());
    }

    @Test
    void allocatesByWeightAndByQuantity() {
        List<LandedCharge> charges = List.of(usd(ChargeType.FREIGHT, 100_000, AllocationBasis.WEIGHT),
                usd(ChargeType.RECEIVING, 13_000, AllocationBasis.QUANTITY));
        LandedCost lc = calculator.calculate("USD", gh04(), charges);
        // weights: 900 × 200 g = 180 kg vs 400 × 400 g = 160 kg; quantities 900 vs 400
        assertThat(lc.line(1).charges()).extracting(ChargeShare::amountMinor).containsExactly(52_941L, 9_000L);
        assertThat(lc.line(2).charges()).extracting(ChargeShare::amountMinor).containsExactly(47_059L, 4_000L);
        assertThat(lc.chargesMinor()).isEqualTo(113_000);
    }

    @Test
    void defaultBasisFollowsTheChargeType() {
        LandedCharge freight = new LandedCharge(ChargeType.FREIGHT, 1, "USD", null, null);
        LandedCharge duty = new LandedCharge(ChargeType.DUTY, 1, "USD", null, null);
        LandedCharge receiving = new LandedCharge(ChargeType.RECEIVING, 1, "USD", null, null);
        assertThat(freight.basis()).isEqualTo(AllocationBasis.WEIGHT);
        assertThat(duty.basis()).isEqualTo(AllocationBasis.VALUE);
        assertThat(receiving.basis()).isEqualTo(AllocationBasis.QUANTITY);
    }

    @Test
    void explicitDutyFromTheCustomsEntryReplacesTheHtsEstimate() {
        LandedCost lc = calculator.calculate("USD", gh04(), List.of(usd(ChargeType.DUTY, 10_000, AllocationBasis.VALUE)));
        assertThat(lc.dutyMinor()).isEqualTo(10_000);
        assertThat(lc.line(1).dutyMinor()).isEqualTo(5_652);
        assertThat(lc.line(2).dutyMinor()).isEqualTo(4_348);
        assertThat(lc.chargesMinor()).isZero();
    }

    @Test
    void withoutChargesLandedCostIsFobPlusEstimatedDuty() {
        LandedCost lc = calculator.calculate("USD", gh04(), List.of());
        assertThat(lc.totalMinor()).isEqualTo(207_000 + 9_504);
        assertThat(lc.line(1).unitCostMinor()).isEqualTo(134); // (117000 + 3744) / 900 = 134.16
    }

    @Test
    void zeroQuantityLinesHaveNoUnitCost() {
        LandedCost lc = calculator.calculate("USD",
                List.of(new LandedCostInput(1, "A", 0, 0, 0, BigDecimal.ZERO),
                        new LandedCostInput(2, "B", 10, 1_000, 0, BigDecimal.ZERO)),
                List.of(usd(ChargeType.FEES, 500, AllocationBasis.VALUE)));
        assertThat(lc.line(1).unitCostMinor()).isZero();
        assertThat(lc.line(1).totalMinor()).isZero();
        assertThat(lc.line(2).totalMinor()).isEqualTo(1_500);
    }

    @Test
    void chargesMustBeInTheBaseCurrency() {
        assertThatThrownBy(() -> calculator.calculate("USD", gh04(),
                List.of(new LandedCharge(ChargeType.FREIGHT, 100, "GHS", null, null))))
                .isInstanceOf(RetailRuleViolation.class).hasMessageContaining("USD");
    }

    @Test
    void weightBasisNeedsWeights() {
        List<LandedCostInput> noWeights = List.of(new LandedCostInput(1, "A", 5, 500, 0, null));
        assertThatThrownBy(() -> calculator.calculate("USD", noWeights,
                List.of(usd(ChargeType.FREIGHT, 100, AllocationBasis.WEIGHT))))
                .isInstanceOf(RetailRuleViolation.class);
    }

    @Test
    void rejectsNegativeChargesAndEmptyInput() {
        assertThatThrownBy(() -> usd(ChargeType.FEES, -1, null)).isInstanceOf(RetailRuleViolation.class);
        assertThatThrownBy(() -> calculator.calculate("USD", List.of(), List.of()))
                .isInstanceOf(RetailRuleViolation.class);
    }
}
