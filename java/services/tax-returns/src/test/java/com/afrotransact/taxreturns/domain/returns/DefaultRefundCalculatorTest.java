package com.afrotransact.taxreturns.domain.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class DefaultRefundCalculatorTest {

    private final DefaultRefundCalculator calc = new DefaultRefundCalculator();

    private static RefundableLine line(String id, String shipment, int qty, long items, long shipping, long tax) {
        return new RefundableLine(id, shipment, qty, items, shipping, tax, 0, 0, 0, 0);
    }

    private static RefundLineAmount amount(RefundBreakdown b, String lineId) {
        return b.lines().stream().filter(l -> l.lineId().equals(lineId)).findFirst().orElseThrow();
    }

    @Test
    void fullReturnWithoutFaultRefundsItemsAndTaxButNotShipping() {
        RefundBreakdown b = calc.forReturn(List.of(line("a", "s1", 2, 5_000, 799, 413)),
                List.of(new RefundSelection("a", 2, false)), 0);
        assertThat(amount(b, "a")).isEqualTo(new RefundLineAmount("a", 2, 5_000, 0, 413, 0));
        assertThat(b.totalMinor()).isEqualTo(5_413);
    }

    @Test
    void partialReturnsNeverDriftFromTheChargedTax() {
        RefundableLine l = line("a", "s1", 3, 3_000, 0, 248);
        RefundBreakdown first = calc.forReturn(List.of(l), List.of(new RefundSelection("a", 1, false)), 0);
        assertThat(amount(first, "a").taxMinor()).isEqualTo(83); // 82.67 → 83
        assertThat(amount(first, "a").itemsMinor()).isEqualTo(1_000);

        RefundableLine afterFirst = new RefundableLine("a", "s1", 3, 3_000, 0, 248, 1, 1_000, 0, 83);
        RefundBreakdown second = calc.forReturn(List.of(afterFirst), List.of(new RefundSelection("a", 1, false)), 0);
        assertThat(amount(second, "a").taxMinor()).isEqualTo(83);

        RefundableLine afterSecond = new RefundableLine("a", "s1", 3, 3_000, 0, 248, 2, 2_000, 0, 166);
        RefundBreakdown last = calc.forReturn(List.of(afterSecond), List.of(new RefundSelection("a", 1, false)), 0);
        assertThat(amount(last, "a").taxMinor()).as("last unit takes exactly what is left").isEqualTo(82);
        assertThat(83 + 83 + 82).isEqualTo(248);
    }

    @Test
    void itemsProrateHalfUpForUnevenPrices() {
        // 3 units for 1000 (discounted line): 333.33 per unit
        RefundBreakdown b = calc.forReturn(List.of(line("a", "s1", 3, 1_000, 0, 83)),
                List.of(new RefundSelection("a", 2, false)), 0);
        assertThat(amount(b, "a").itemsMinor()).isEqualTo(667); // 666.67 → 667
        assertThat(amount(b, "a").taxMinor()).isEqualTo(55);    // 55.33 → 55
    }

    @Test
    void restockingFeeOnNoFaultReturnsReducesItemsOnly() {
        RefundBreakdown b = calc.forReturn(List.of(line("a", "s1", 1, 5_000, 0, 413)),
                List.of(new RefundSelection("a", 1, false)), 1_500);
        assertThat(amount(b, "a")).isEqualTo(new RefundLineAmount("a", 1, 4_250, 0, 413, 750));
        assertThat(b.restockingFeeMinor()).isEqualTo(750);
        assertThat(b.totalMinor()).isEqualTo(4_663);
    }

    @Test
    void restockingFeeRoundsHalfUp() {
        RefundBreakdown b = calc.forReturn(List.of(line("a", "s1", 1, 1_999, 0, 0)),
                List.of(new RefundSelection("a", 1, false)), 1_000);
        assertThat(amount(b, "a").restockingFeeMinor()).isEqualTo(200); // 199.9 → 200
        assertThat(amount(b, "a").itemsMinor()).isEqualTo(1_799);
    }

    @Test
    void sellerFaultRefundsShippingOfTheWholeShipmentAndNoFee() {
        RefundBreakdown b = calc.forReturn(List.of(line("a", "s1", 1, 5_000, 799, 413)),
                List.of(new RefundSelection("a", 1, true)), 1_500);
        assertThat(amount(b, "a")).isEqualTo(new RefundLineAmount("a", 1, 5_000, 799, 413, 0));
        assertThat(b.totalMinor()).isEqualTo(6_212);
    }

    @Test
    void sellerFaultOnPartOfAShipmentRefundsProportionalShippingFromTheLineThatCarriesIt() {
        List<RefundableLine> charged = List.of(line("a", "s1", 1, 6_000, 1_000, 495),
                line("b", "s1", 1, 4_000, 0, 330));
        RefundBreakdown b = calc.forReturn(charged, List.of(new RefundSelection("b", 1, true)), 0);
        assertThat(amount(b, "b")).isEqualTo(new RefundLineAmount("b", 1, 4_000, 0, 330, 0));
        assertThat(amount(b, "a")).as("shipping sits on the first line of the shipment")
                .isEqualTo(new RefundLineAmount("a", 0, 0, 400, 0, 0));
        assertThat(b.shippingMinor()).isEqualTo(400);

        // the rest of the shipment comes back later, also seller fault: what is left of the shipping
        List<RefundableLine> after = List.of(new RefundableLine("a", "s1", 1, 6_000, 1_000, 495, 0, 0, 400, 0),
                new RefundableLine("b", "s1", 1, 4_000, 0, 330, 1, 4_000, 0, 330));
        RefundBreakdown rest = calc.forReturn(after, List.of(new RefundSelection("a", 1, true)), 0);
        assertThat(amount(rest, "a")).isEqualTo(new RefundLineAmount("a", 1, 6_000, 600, 495, 0));
    }

    @Test
    void mixedFaultLinesOnlyChargeTheFeeOnNoFaultOnes() {
        List<RefundableLine> charged = List.of(line("a", "s1", 1, 2_000, 500, 165),
                line("b", "s1", 1, 2_000, 0, 165));
        RefundBreakdown b = calc.forReturn(charged, List.of(new RefundSelection("a", 1, true),
                new RefundSelection("b", 1, false)), 1_000);
        assertThat(amount(b, "a")).isEqualTo(new RefundLineAmount("a", 1, 2_000, 500, 165, 0));
        assertThat(amount(b, "b")).isEqualTo(new RefundLineAmount("b", 1, 1_800, 0, 165, 200));
    }

    @Test
    void faultInOneShipmentDoesNotRefundAnotherShipmentsShipping() {
        List<RefundableLine> charged = List.of(line("a", "s1", 1, 2_000, 500, 0), line("b", "s2", 1, 3_000, 700, 0));
        RefundBreakdown b = calc.forReturn(charged, List.of(new RefundSelection("a", 1, true)), 0);
        assertThat(b.lines()).extracting(RefundLineAmount::lineId).containsExactly("a");
        assertThat(b.shippingMinor()).isEqualTo(500);
    }

    @Test
    void noShippingLeftMeansNoShippingRefund() {
        RefundableLine refundedShipping = new RefundableLine("a", "s1", 2, 4_000, 500, 330, 1, 2_000, 500, 165);
        RefundBreakdown b = calc.forReturn(List.of(refundedShipping), List.of(new RefundSelection("a", 1, true)), 0);
        assertThat(amount(b, "a")).isEqualTo(new RefundLineAmount("a", 1, 2_000, 0, 165, 0));
    }

    @Test
    void refusesMoreThanRemainsUnknownAndDuplicateLines() {
        RefundableLine partlyRefunded = new RefundableLine("a", "s1", 2, 4_000, 0, 330, 1, 2_000, 0, 165);
        assertThatThrownBy(() -> calc.forReturn(List.of(partlyRefunded), List.of(new RefundSelection("a", 2, false)),
                0)).isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("only 1 unit");
        assertThatThrownBy(() -> calc.forReturn(List.of(partlyRefunded), List.of(new RefundSelection("x", 1, false)),
                0)).isInstanceOf(ReturnRuleViolation.class);
        assertThatThrownBy(() -> calc.forReturn(List.of(partlyRefunded), List.of(new RefundSelection("a", 1, false),
                new RefundSelection("a", 1, false)), 0)).isInstanceOf(ReturnRuleViolation.class);
        assertThatThrownBy(() -> calc.forReturn(List.of(partlyRefunded), List.of(new RefundSelection("a", 0, false)),
                0)).isInstanceOf(ReturnRuleViolation.class);
        assertThatThrownBy(() -> calc.forReturn(List.of(partlyRefunded), List.of(new RefundSelection("a", 1, false)),
                10_001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adjustmentRefundsProportionalTax() {
        RefundableLine l = line("a", "s1", 1, 5_000, 799, 413);
        assertThat(calc.adjustment(l, 1_000, 0)).isEqualTo(new RefundLineAmount("a", 0, 1_000, 0, 83, 0)); // 82.6
        assertThat(calc.adjustment(l, 0, 799)).isEqualTo(new RefundLineAmount("a", 0, 0, 799, 0, 0));
        assertThat(calc.adjustment(l, 5_000, 0).taxMinor()).isEqualTo(413);
        RefundableLine after = new RefundableLine("a", "s1", 1, 5_000, 799, 413, 0, 4_000, 0, 330);
        assertThat(calc.adjustment(after, 1_000, 0).taxMinor()).as("the rest of the items takes the rest of the tax")
                .isEqualTo(83);
    }

    @Test
    void adjustmentLimits() {
        RefundableLine l = new RefundableLine("a", "s1", 1, 5_000, 799, 413, 0, 4_500, 799, 372);
        assertThatThrownBy(() -> calc.adjustment(l, 600, 0)).isInstanceOf(ReturnRuleViolation.class);
        assertThatThrownBy(() -> calc.adjustment(l, 0, 1)).isInstanceOf(ReturnRuleViolation.class);
        assertThatThrownBy(() -> calc.adjustment(l, 0, 0)).isInstanceOf(ReturnRuleViolation.class);
        assertThatThrownBy(() -> calc.adjustment(l, -1, 0)).isInstanceOf(ReturnRuleViolation.class);
        assertThat(calc.adjustment(l, 500, 0).taxMinor()).isEqualTo(41);
    }

    @Test
    void prorateRoundsHalfUp() {
        assertThat(DefaultRefundCalculator.prorate(5, 1, 2)).isEqualTo(3);
        assertThat(DefaultRefundCalculator.prorate(5, 1, 3)).isEqualTo(2);
        assertThat(DefaultRefundCalculator.prorate(1, 1, 2)).isEqualTo(1);
        assertThat(DefaultRefundCalculator.prorate(1, 1, 3)).isZero();
        assertThat(DefaultRefundCalculator.prorate(100, 1, 0)).isZero();
        assertThat(DefaultRefundCalculator.prorate(Long.MAX_VALUE / 2, 2, 2)).isEqualTo(Long.MAX_VALUE / 2);
    }
}
