package com.afrotransact.deliveryruns.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * The run P&amp;L from the run's real orders (see {@link RunOrderTally}, {@link RunActuals}). The cost is the
 * estimate of {@link RunEconomicsCalculator} (route time and miles x the staff-set driver cost) at the orders
 * delivered; once the run has been driven and the minutes the driver was out were captured (collecting or
 * out-for-delivery to completed), those minutes replace the estimated ones.
 */
public class DefaultRunPnlCalculator implements RunPnlCalculator {

    /** A captured span longer than this is a run left open, not a drive. */
    private static final double MAX_ACTUAL_MINUTES = 24 * 60;

    private final RunEconomicsCalculator calculator;
    private final Supplier<EconomicsSettings> settings;

    public DefaultRunPnlCalculator(RunEconomicsCalculator calculator, Supplier<EconomicsSettings> settings) {
        this.calculator = calculator;
        this.settings = settings;
    }

    @Override
    public RunPnl pnl(Run run, RouteEstimate route, List<RunOrder> slots, Long manualPlatformFeesMinor) {
        RunTerms t = run.terms();
        RunOrderTally tally = slots.isEmpty() && run.reservedOrders() > 0 ? RunOrderTally.counted(run)
                : RunOrderTally.of(run, slots);
        Integer capacity = t.capacity().maxOrders();
        if (run.status() == RunStatus.CANCELLED) {
            RunEconomics planned = calculator.evaluate(route, t.dropMode(), t.feeMinor(), t.currency(), t.minOrders(),
                    capacity, 0, null);
            // Nobody drove it: no fees kept, no cost (the fixed cost of a trip is only incurred by taking it).
            RunEconomics none = new RunEconomics(planned.route(), planned.cost(), planned.feeMinor(),
                    planned.currency(), planned.breakEvenOrders(), planned.atMinimum(), planned.atCapacity(),
                    new EconomicsSnapshot(0, 0, null, 0, 0, 0));
            return new RunPnl(none, run.status(), RunPnl.Phase.CANCELLED, run.cancelReason(), tally.refunded(),
                    tally.cancelledRefundFeesMinor(), tally.cancelledRefundFeesEstimated(), null, null);
        }
        RunPnl.Phase phase = RunPnl.phaseOf(run.status());
        EconomicsSettings model = settings.get();
        int orders = tally.delivered();
        CostModel cost = calculator.costModel(route, t.dropMode());
        Double minutes = phase == RunPnl.Phase.AFTER ? actualMinutes(run, route) : null;
        long estimated = cost.costFor(orders);
        RunActuals.CostBasis basis = RunActuals.CostBasis.ESTIMATE;
        if (minutes != null) {
            basis = RunActuals.CostBasis.ACTUAL_TIME;
            estimated = Math.round(minutes / 60.0 * model.driverCostPerHourMinor()
                    + cost.driveMiles() * model.driverCostPerMileMinor());
        }
        long net = tally.netDeliveryFeesMinor();
        EconomicsSnapshot atOrders = new EconomicsSnapshot(orders, net, null, net, estimated, net - estimated);
        RunEconomics base = calculator.evaluate(route, t.dropMode(), t.feeMinor(), t.currency(), t.minOrders(),
                capacity, orders, null);
        RunEconomics economics = new RunEconomics(base.route(), base.cost(), base.feeMinor(), base.currency(),
                base.breakEvenOrders(), base.atMinimum(), base.atCapacity(), atOrders);
        Double average = orders == 0 ? null : (double) net / orders;
        Integer breakEven = orders == 0 ? null : calculator.breakEvenOrders(Math.round(average), cost);
        RunActuals actuals = new RunActuals(tally.carried(), orders, tally.failed(),
                tally.cancelledAfterReservation(), tally.refunded(), tally.feeMix(), tally.deliveryFeesMinor(),
                tally.refundedFeesOnDeliveredMinor(), net, tally.feesEstimated(), tally.customersPaidMinor(),
                tally.totalsComplete(), tally.refundsMinor(), average, basis, minutes, estimated, net - estimated,
                breakEven, breakEven == null ? null : orders >= breakEven);
        PlatformFees fees = manualPlatformFeesMinor != null ? PlatformFees.manual(manualPlatformFeesMinor, orders)
                : PlatformFees.of(tally.commissionReportedMinor(), tally.commissionReportedOrders(), orders,
                model.platformFeePerOrderMinor());
        return new RunPnl(economics, run.status(), phase, null, tally.refunded(),
                tally.refundedFeesOnDeliveredMinor() + tally.cancelledRefundFeesMinor(),
                tally.cancelledRefundFeesEstimated(), actuals, fees);
    }

    /** Minutes the driver was out, when captured and plausible (at least the one-way drive, at most a day). */
    private static Double actualMinutes(Run run, RouteEstimate route) {
        RunMilestones m = run.milestones();
        Instant start = m.collectingAt() != null ? m.collectingAt() : m.outForDeliveryAt();
        Instant end = m.completedAt();
        if (start == null || end == null) {
            return null;
        }
        double minutes = Duration.between(start, end).toSeconds() / 60.0;
        if (minutes < route.driveMinutes() || minutes > MAX_ACTUAL_MINUTES || minutes <= 0) {
            return null;
        }
        return Math.round(minutes * 100.0) / 100.0;
    }
}
