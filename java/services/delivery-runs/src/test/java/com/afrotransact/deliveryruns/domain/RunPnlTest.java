package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** S265: the run P&amp;L from the run's real orders, and the platform fees on goods as their own line. */
class RunPnlTest {

    private static final Instant T0 = Runs.NOW;

    /** $24/h, $0.60/mile, 30 min sweep, 6 min per stop; the platform keeps $2.00 on the goods of an order. */
    private static final EconomicsSettings SETTINGS = new EconomicsSettings(2400, 60, 30, 6, 30, 1, true, 200);
    private static final RouteEstimate BASTROP = new RouteEstimate(32, 40, "ROAD_OSRM");

    private final RunEconomicsCalculator economics = new DefaultRunEconomicsCalculator(SETTINGS);
    private final RunPnlCalculator pnls = new DefaultRunPnlCalculator(economics, () -> SETTINGS);

    private static RunOrder slot(Run run, String order, RunOrderStatus status, RunOrderStage stage,
                                 RunOrderMoney money) {
        return new RunOrder(UUID.randomUUID(), run.id(), order, "cust-" + order, 1, status, null, null, T0, T0, 0,
                stage, null, null, money);
    }

    private static RunOrder delivered(Run run, String order, long fee, long total, DeliveryKind kind) {
        return slot(run, order, RunOrderStatus.CONFIRMED, RunOrderStage.DELIVERED,
                RunOrderMoney.charged(fee, total, kind));
    }

    /** A run that went out and came back: confirmed, collecting at 8:00, out for delivery, completed at 11:00. */
    private static Run completed(int reserved, Instant collecting, Instant done) {
        Run r = Runs.withOrders(Runs.open(1, Capacity.UNLIMITED, CutoffPolicy.CANCEL), reserved);
        return r.cutOff(T0).confirm(T0).startCollecting(collecting).outForDelivery(collecting.plusSeconds(60))
                .complete(done);
    }

    @Test
    void anAfterRunIsBuiltFromTheFeesEachOrderPaidNotOrdersTimesOneFee() {
        Run run = completed(5, T0, T0.plus(Duration.ofMinutes(30)));
        List<RunOrder> slots = new ArrayList<>(List.of(
                delivered(run, "door-1", 799, 4799, DeliveryKind.DOOR),
                delivered(run, "door-2", 999, 3999, DeliveryKind.DOOR),
                delivered(run, "pickup", 0, 3000, DeliveryKind.PICKUP),
                delivered(run, "carrier", 1299, 5299, DeliveryKind.CARRIER),
                slot(run, "refunded", RunOrderStatus.CONFIRMED, RunOrderStage.DELIVERED,
                        RunOrderMoney.charged(799L, 3799L, DeliveryKind.DOOR).withRefund(2299, 799, T0)),
                slot(run, "gave-back", RunOrderStatus.RELEASED, RunOrderStage.PENDING,
                        RunOrderMoney.charged(799L, 2500L, DeliveryKind.DOOR))));

        RunPnl pnl = pnls.pnl(run, BASTROP, slots, null);

        assertThat(pnl.phase()).isEqualTo(RunPnl.Phase.AFTER);
        RunActuals a = pnl.actuals();
        assertThat(a.ordersCarried()).isEqualTo(5);
        assertThat(a.ordersDelivered()).isEqualTo(5);
        assertThat(a.ordersCancelled()).as("the released slot is shown apart, it earns and costs nothing").isEqualTo(1);
        assertThat(a.ordersRefunded()).isEqualTo(1);
        assertThat(a.feeMix()).extracting(RunOrderTally.FeeLine::kind, RunOrderTally.FeeLine::orders,
                RunOrderTally.FeeLine::feeMinor).containsExactly(
                tuple(DeliveryKind.DOOR, 3, 2597L),
                tuple(DeliveryKind.PICKUP, 1, 0L),
                tuple(DeliveryKind.CARRIER, 1, 1299L));
        assertThat(a.deliveryFeesMinor()).as("799 + 999 + 0 + 1299 + 799, not 5 x 799").isEqualTo(3896);
        assertThat(a.refundedFeesMinor()).isEqualTo(799);
        assertThat(a.netDeliveryFeesMinor()).isEqualTo(3097);
        assertThat(a.feesEstimated()).isFalse();
        assertThat(a.customersPaidMinor()).isEqualTo(4799 + 3999 + 3000 + 5299 + 3799);
        assertThat(a.totalsComplete()).isTrue();
        assertThat(a.refundsMinor()).isEqualTo(2299);
        assertThat(pnl.ordersRefunded()).isEqualTo(1);
        assertThat(pnl.refundedFeesMinor()).isEqualTo(799);

        // cost: 2 x 40 + 30 = 110 min fixed at $40/min = 4400, 64 miles x 60 = 3840, 5 stops x 6 min x 40 = 1200
        assertThat(a.costBasis()).isEqualTo(RunActuals.CostBasis.ESTIMATE);
        assertThat(a.estimatedCostMinor()).isEqualTo(4400 + 3840 + 1200);
        assertThat(a.contributionMarginMinor()).isEqualTo(3097 - 9440);
        assertThat(pnl.economics().atOrders().orders()).isEqualTo(5);
        assertThat(pnl.economics().atOrders().contributionMarginMinor()).isEqualTo(3097 - 9440);
        assertThat(pnl.economics().atOrders().commissionShareMinor()).as("never mixed into the delivery P&L").isNull();

        // break-even at the fee the orders actually paid (3097 / 5 = 619 per order), not at the run's 799
        assertThat(a.averageFeeMinor()).isEqualTo(619.4);
        assertThat(pnl.economics().breakEvenOrders()).isEqualTo(15);
        assertThat(a.breakEvenOrders()).isEqualTo(economics.breakEvenOrders(619, economics.costModel(BASTROP,
                DropMode.DOORSTEP)));
        assertThat(a.breakEvenOrders()).isGreaterThan(15);
        assertThat(a.breakEvenCovered()).isFalse();
    }

    @Test
    void anOrderWithoutAReportedFeeFallsBackToTheRunsFeeAndSaysSo() {
        Run run = completed(2, T0, T0.plus(Duration.ofMinutes(30)));
        List<RunOrder> slots = List.of(delivered(run, "a", 999, 3000, DeliveryKind.DOOR),
                slot(run, "b", RunOrderStatus.CONFIRMED, RunOrderStage.DELIVERED, RunOrderMoney.NONE));

        RunActuals a = pnls.pnl(run, BASTROP, slots, null).actuals();

        assertThat(a.deliveryFeesMinor()).isEqualTo(999 + 799);
        assertThat(a.feesEstimated()).isTrue();
        assertThat(a.totalsComplete()).isFalse();
        assertThat(a.feeMix()).extracting(RunOrderTally.FeeLine::kind).containsExactly(DeliveryKind.DOOR);
    }

    @Test
    void ordersTheDriverCouldNotDeliverEarnNothingAndAreShownApart() {
        Run run = completed(3, T0, T0.plus(Duration.ofMinutes(30)));
        List<RunOrder> slots = List.of(delivered(run, "a", 799, 3000, DeliveryKind.DOOR),
                slot(run, "failed", RunOrderStatus.CONFIRMED, RunOrderStage.FAILED, RunOrderMoney.charged(799L, null,
                        DeliveryKind.DOOR)),
                slot(run, "returned", RunOrderStatus.CONFIRMED, RunOrderStage.RETURNED,
                        RunOrderMoney.charged(799L, null, DeliveryKind.DOOR)));

        RunActuals a = pnls.pnl(run, BASTROP, slots, null).actuals();

        assertThat(a.ordersCarried()).isEqualTo(3);
        assertThat(a.ordersDelivered()).isEqualTo(1);
        assertThat(a.ordersFailed()).isEqualTo(2);
        assertThat(a.deliveryFeesMinor()).isEqualTo(799);
        assertThat(a.estimatedCostMinor()).isEqualTo(4400 + 3840 + 240);
    }

    @Test
    void anOrderCancelledOnceConfirmedIsRefundedInFullAndEarnsNothing() {
        Run run = completed(2, T0, T0.plus(Duration.ofMinutes(30)));
        List<RunOrder> slots = List.of(delivered(run, "a", 799, 3000, DeliveryKind.DOOR),
                slot(run, "cancelled", RunOrderStatus.CANCELLED, RunOrderStage.PENDING,
                        RunOrderMoney.charged(999L, 4000L, DeliveryKind.DOOR)));

        RunPnl pnl = pnls.pnl(run, BASTROP, slots, null);

        assertThat(pnl.actuals().ordersCancelled()).isEqualTo(1);
        assertThat(pnl.actuals().ordersDelivered()).isEqualTo(1);
        assertThat(pnl.ordersRefunded()).as("its authorisation was released or refunded in full").isEqualTo(1);
        assertThat(pnl.refundedFeesMinor()).as("the fee it was actually charged").isEqualTo(999);
        assertThat(pnl.actuals().netDeliveryFeesMinor()).isEqualTo(799);
    }

    @Test
    void theMinutesTheDriverWasOutReplaceTheEstimateWhenTheyAreCapturedAndPlausible() {
        Instant start = T0;
        Run run = completed(1, start, start.plus(Duration.ofMinutes(180)));
        List<RunOrder> slots = List.of(delivered(run, "a", 799, 3000, DeliveryKind.DOOR));

        RunActuals a = pnls.pnl(run, BASTROP, slots, null).actuals();

        assertThat(a.costBasis()).isEqualTo(RunActuals.CostBasis.ACTUAL_TIME);
        assertThat(a.actualMinutes()).isEqualTo(180.0);
        // 180 min at $40/min = 7200, plus the 64 estimated miles at 60 = 3840
        assertThat(a.estimatedCostMinor()).isEqualTo(7200 + 3840);

        Run tooQuick = completed(1, start, start.plus(Duration.ofMinutes(5)));
        assertThat(pnls.pnl(tooQuick, BASTROP, slots, null).actuals().costBasis()).as("a click, not a drive")
                .isEqualTo(RunActuals.CostBasis.ESTIMATE);
        Run leftOpen = completed(1, start, start.plus(Duration.ofDays(3)));
        assertThat(pnls.pnl(leftOpen, BASTROP, slots, null).actuals().costBasis()).isEqualTo(
                RunActuals.CostBasis.ESTIMATE);
    }

    @Test
    void aRunNotYetDrivenIsPricedAtItsOrdersSoFar() {
        Run run = Runs.withOrders(Runs.open(2, Capacity.UNLIMITED, CutoffPolicy.CANCEL), 2);
        List<RunOrder> slots = List.of(
                slot(run, "a", RunOrderStatus.RESERVED, RunOrderStage.PENDING,
                        RunOrderMoney.charged(999L, 3000L, DeliveryKind.DOOR)),
                slot(run, "b", RunOrderStatus.RESERVED, RunOrderStage.PENDING,
                        RunOrderMoney.charged(0L, 2000L, DeliveryKind.PICKUP)));

        RunPnl pnl = pnls.pnl(run, BASTROP, slots, null);

        assertThat(pnl.phase()).isEqualTo(RunPnl.Phase.BEFORE);
        assertThat(pnl.economics().atOrders().orders()).isEqualTo(2);
        assertThat(pnl.economics().atOrders().feeRevenueMinor()).isEqualTo(999);
        assertThat(pnl.actuals().costBasis()).isEqualTo(RunActuals.CostBasis.ESTIMATE);
    }

    @Test
    void aRunWhoseSlotsWereNeverRecordedFallsBackToItsCounterAndSaysSo() {
        Run run = Runs.withOrders(Runs.open(1, Capacity.UNLIMITED, CutoffPolicy.CANCEL), 3);

        RunPnl pnl = pnls.pnl(run, BASTROP, List.of(), null);

        assertThat(pnl.economics().atOrders().orders()).isEqualTo(3);
        assertThat(pnl.economics().atOrders().feeRevenueMinor()).isEqualTo(3 * 799);
        assertThat(pnl.actuals().feesEstimated()).isTrue();
    }

    @Test
    void aCancelledRunRefundsTheFeesItsOrdersWereActuallyCharged() {
        Run run = Runs.withOrders(Runs.open(5, Capacity.UNLIMITED, CutoffPolicy.CANCEL), 3)
                .cancel("Storm warning", T0);
        List<RunOrder> slots = List.of(
                slot(run, "a", RunOrderStatus.CANCELLED, RunOrderStage.PENDING,
                        RunOrderMoney.charged(799L, 3000L, DeliveryKind.DOOR)),
                slot(run, "b", RunOrderStatus.CANCELLED, RunOrderStage.PENDING,
                        RunOrderMoney.charged(0L, 2000L, DeliveryKind.PICKUP)),
                slot(run, "c", RunOrderStatus.CANCELLED, RunOrderStage.PENDING,
                        RunOrderMoney.charged(1299L, 5000L, DeliveryKind.CARRIER)));

        RunPnl pnl = pnls.pnl(run, BASTROP, slots, null);

        assertThat(pnl.phase()).isEqualTo(RunPnl.Phase.CANCELLED);
        assertThat(pnl.ordersRefunded()).isEqualTo(3);
        assertThat(pnl.refundedFeesMinor()).as("799 + 0 + 1299, not 3 x 799").isEqualTo(2098);
        assertThat(pnl.refundedFeesEstimated()).isFalse();
        assertThat(pnl.economics().atOrders().revenueMinor()).isZero();
        assertThat(pnl.economics().atOrders().estimatedCostMinor()).isZero();
        assertThat(pnl.actuals()).isNull();
        assertThat(pnl.platformFees()).isNull();

        List<RunOrder> unreported = List.of(
                slot(run, "a", RunOrderStatus.CANCELLED, RunOrderStage.PENDING, RunOrderMoney.NONE),
                slot(run, "b", RunOrderStatus.CANCELLED, RunOrderStage.PENDING, RunOrderMoney.NONE));
        RunPnl estimated = pnls.pnl(run, BASTROP, unreported, null);
        assertThat(estimated.ordersRefunded()).isEqualTo(2);
        assertThat(estimated.refundedFeesMinor()).isEqualTo(2 * 799);
        assertThat(estimated.refundedFeesEstimated()).as("the run's fee stands in for what was charged").isTrue();
    }

    @Test
    void ordersRefundedMeansTheSameInTheTallyAndTheRunPnl() {
        Run run = completed(3, T0, T0.plus(Duration.ofMinutes(30)));
        List<RunOrder> slots = List.of(delivered(run, "a", 799, 3000, DeliveryKind.DOOR),
                slot(run, "r", RunOrderStatus.CONFIRMED, RunOrderStage.DELIVERED,
                        RunOrderMoney.charged(799L, 3000L, DeliveryKind.DOOR).withRefund(3000, 799, T0)),
                slot(run, "released", RunOrderStatus.RELEASED, RunOrderStage.PENDING, RunOrderMoney.NONE));

        assertThat(RunOrderTally.forRun(run, slots).refunded()).isEqualTo(pnls.pnl(run, BASTROP, slots, null)
                .ordersRefunded()).isEqualTo(1);
    }

    // ---- platform fees on goods ----------------------------------------------------------------------------------

    @Test
    void platformFeesComeFromPaymentsWhereReportedAndTheConfiguredEstimateForTheRest() {
        Run run = completed(3, T0, T0.plus(Duration.ofMinutes(30)));
        RunOrder reportedA = delivered(run, "a", 799, 3000, DeliveryKind.DOOR);
        reportedA = reportedA.withMoney(reportedA.money().withCommission(450), T0);
        RunOrder reportedB = delivered(run, "b", 799, 3000, DeliveryKind.DOOR);
        reportedB = reportedB.withMoney(reportedB.money().withCommission(380), T0);
        RunOrder unreported = delivered(run, "c", 799, 3000, DeliveryKind.DOOR);

        PlatformFees mixed = pnls.pnl(run, BASTROP, List.of(reportedA, reportedB, unreported), null).platformFees();
        assertThat(mixed.source()).isEqualTo(PlatformFees.Source.MIXED);
        assertThat(mixed.commissionMinor()).isEqualTo(450 + 380 + 200);
        assertThat(mixed.ordersReported()).isEqualTo(2);
        assertThat(mixed.orders()).isEqualTo(3);

        PlatformFees all = pnls.pnl(run, BASTROP, List.of(reportedA, reportedB), null).platformFees();
        assertThat(all.source()).isEqualTo(PlatformFees.Source.PAYMENTS);
        assertThat(all.commissionMinor()).isEqualTo(830);

        PlatformFees estimate = pnls.pnl(run, BASTROP, List.of(unreported), null).platformFees();
        assertThat(estimate.source()).isEqualTo(PlatformFees.Source.ESTIMATE);
        assertThat(estimate.commissionMinor()).isEqualTo(200);

        PlatformFees typed = pnls.pnl(run, BASTROP, List.of(unreported), 1234L).platformFees();
        assertThat(typed.source()).isEqualTo(PlatformFees.Source.MANUAL);
        assertThat(typed.commissionMinor()).isEqualTo(1234);
    }

    @Test
    void platformFeesAreNeverPartOfTheDeliveryMargin() {
        Run run = completed(1, T0, T0.plus(Duration.ofMinutes(30)));
        RunOrder a = delivered(run, "a", 799, 3000, DeliveryKind.DOOR);
        RunOrder withCommission = a.withMoney(a.money().withCommission(5000), T0);

        RunPnl without = pnls.pnl(run, BASTROP, List.of(a), null);
        RunPnl with = pnls.pnl(run, BASTROP, List.of(withCommission), null);

        assertThat(with.actuals().contributionMarginMinor()).isEqualTo(without.actuals().contributionMarginMinor());
        assertThat(with.economics().atOrders().revenueMinor()).isEqualTo(without.economics().atOrders().revenueMinor());
        assertThat(with.platformFees().commissionMinor()).isEqualTo(5000);
    }

    @Test
    void withNothingReportedAndNoEstimateThereIsNoFigureNotAZero() {
        PlatformFees none = PlatformFees.of(0, 0, 4, 0);
        assertThat(none.source()).isEqualTo(PlatformFees.Source.NONE);
        assertThat(none.commissionMinor()).isNull();
        PlatformFees partial = PlatformFees.of(500, 1, 4, 0);
        assertThat(partial.source()).as("only the reported part is known").isEqualTo(PlatformFees.Source.MIXED);
        assertThat(partial.commissionMinor()).isEqualTo(500);
        assertThat(partial.ordersReported()).isEqualTo(1);
    }

    @Test
    void moneyOnASlotAddsUpAndNeverGoesNegative() {
        RunOrderMoney m = RunOrderMoney.charged(799L, 3000L, DeliveryKind.DOOR).withCommission(100).withCommission(50)
                .withRefund(500, 100, T0).withRefund(300, 0, T0);
        assertThat(m.commissionMinor()).isEqualTo(150);
        assertThat(m.refundedMinor()).isEqualTo(800);
        assertThat(m.refundedFeeMinor()).isEqualTo(100);
        assertThatThrownBy(() -> RunOrderMoney.charged(-1L, null, null))
                .isInstanceOf(RuleViolation.class);
    }
}
