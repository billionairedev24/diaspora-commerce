package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EconomicsTest {

    /** $24/h, $0.60/mile, 30 min sweep, 6 min per doorstep stop, 30 min at a pickup point, 1 min per handover. */
    private final RunEconomicsCalculator calc = new DefaultRunEconomicsCalculator(
            new EconomicsSettings(2400, 60, 30, 6, 30, 1, true));

    /** Austin → Bastrop: about 32 road miles, 40 minutes. */
    private final RouteEstimate bastrop = new RouteEstimate(32, 40, "ROAD_OSRM");

    @Test
    void doorstepCostModel() {
        CostModel cost = calc.costModel(bastrop, DropMode.DOORSTEP);
        assertThat(cost.driveMiles()).isEqualTo(64);
        assertThat(cost.fixedMinutes()).isEqualTo(110);
        // 110 min x 40/min = 4400 + 64 mi x 60 = 3840
        assertThat(cost.fixedCostMinor()).isEqualTo(8240);
        // 6 min x 40/min
        assertThat(cost.perOrderCostMinor()).isEqualTo(240);
        assertThat(cost.costFor(10)).isEqualTo(10_640);
    }

    @Test
    void breakEvenAtTheFlatFee() {
        CostModel cost = calc.costModel(bastrop, DropMode.DOORSTEP);
        // 8240 / (799 - 240) = 14.74 -> 15 orders
        assertThat(calc.breakEvenOrders(799, cost)).isEqualTo(15);
        assertThat(15 * 799L).isGreaterThanOrEqualTo(cost.costFor(15));
        assertThat(14 * 799L).isLessThan(cost.costFor(14));
    }

    @Test
    void pickupPointIsCheaperPerOrder() {
        CostModel cost = calc.costModel(bastrop, DropMode.PICKUP_POINT);
        assertThat(cost.fixedMinutes()).isEqualTo(140);
        assertThat(cost.perOrderCostMinor()).isEqualTo(40);
        // (140 x 40 + 3840) / (799 - 40) = 9440 / 759 = 12.4 -> 13
        assertThat(calc.breakEvenOrders(799, cost)).isEqualTo(13);
    }

    @Test
    void noBreakEvenWhenTheFeeDoesNotCoverAStop() {
        CostModel cost = calc.costModel(bastrop, DropMode.DOORSTEP);
        assertThat(calc.breakEvenOrders(200, cost)).isNull();
        assertThat(calc.breakEvenOrders(0, cost)).isNull();
    }

    @Test
    void evaluationReportsMinimumCapacityAndOrders() {
        RunEconomics e = calc.evaluate(bastrop, DropMode.DOORSTEP, 799, "USD", 10, 40, 22, 1500L);
        assertThat(e.breakEvenOrders()).isEqualTo(15);
        assertThat(e.atMinimum().orders()).isEqualTo(10);
        assertThat(e.atMinimum().contributionMarginMinor()).isEqualTo(7990 - 10_640);
        assertThat(e.atCapacity().orders()).isEqualTo(40);
        assertThat(e.atOrders().feeRevenueMinor()).isEqualTo(22 * 799);
        assertThat(e.atOrders().commissionShareMinor()).isEqualTo(1500);
        assertThat(e.atOrders().revenueMinor()).isEqualTo(22 * 799 + 1500);
        assertThat(e.atOrders().contributionMarginMinor())
                .isEqualTo(22 * 799 + 1500 - e.atOrders().estimatedCostMinor());
    }

    @Test
    void oneWayWhenNotRoundTrip() {
        RunEconomicsCalculator oneWay = new DefaultRunEconomicsCalculator(
                new EconomicsSettings(2400, 60, 0, 0, 0, 0, false));
        CostModel cost = oneWay.costModel(bastrop, DropMode.DOORSTEP);
        assertThat(cost.driveMiles()).isEqualTo(32);
        assertThat(cost.costFor(0)).isEqualTo(40 * 40 + 32 * 60);
        assertThat(oneWay.evaluate(bastrop, DropMode.DOORSTEP, 799, "USD", 1, null, 0, null).atCapacity()).isNull();
    }
}
