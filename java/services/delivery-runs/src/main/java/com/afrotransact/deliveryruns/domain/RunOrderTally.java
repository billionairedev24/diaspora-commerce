package com.afrotransact.deliveryruns.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A run's orders counted the one way every S265 report uses (the run P&amp;L, the location history), so that
 * "orders", "cancelled" and "refunded" mean the same everywhere.
 *
 * <ul>
 *   <li><b>carried</b>: slots that hold, or held until the run ended, a place on the run (RESERVED or CONFIRMED);
 *   <li><b>failed</b>: carried orders the driver could not deliver (stage FAILED or RETURNED);
 *   <li><b>delivered</b>: carried minus failed. An order of a run that has been completed is delivered unless
 *       the driver reported otherwise (staff may complete a run without per-order delivery events);
 *   <li><b>cancelled after reservation</b>: slots given back by the customer (RELEASED) - they leave the run and
 *       earn and cost nothing;
 *   <li><b>run cancelled</b>: slots a cancelled run closed (CANCELLED): each is refunded in full;
 *   <li><b>refunded</b>: the run-cancelled slots, plus carried orders money was handed back on (a refund after
 *       delivery). Cancelled-after-reservation slots are not counted: the customer was not charged for them.
 * </ul>
 *
 * @param carried                    orders carried by the run
 * @param delivered                  of them, delivered
 * @param failed                     of them, not delivered
 * @param cancelledAfterReservation  slots the customers gave back
 * @param runCancelled               slots closed by the run's cancellation
 * @param refunded                   orders money was handed back on (see above)
 * @param deliveredReported          delivered orders whose delivery the driver reported (stage DELIVERED)
 * @param feeMix                     the fees charged on the delivered orders, per way of delivery
 * @param deliveryFeesMinor          delivery fees charged on the delivered orders
 * @param refundedFeesOnDeliveredMinor of them, handed back
 * @param feesEstimated              some delivered order had no reported fee: the run's fee stands in for it
 * @param customersPaidMinor         what the delivered orders' customers paid in all (reported totals only)
 * @param totalsComplete             every delivered order's total was reported
 * @param refundsMinor               money handed back on the delivered orders
 * @param cancelledRefundFeesMinor   delivery fees handed back by the cancellation of the run
 * @param cancelledRefundFeesEstimated some cancelled order had no reported fee: the run's fee stands in for it
 * @param commissionReportedMinor    platform commission on the goods of the delivered orders as payments reported it
 * @param commissionReportedOrders   delivered orders payments reported it for
 */
public record RunOrderTally(int carried, int delivered, int failed, int cancelledAfterReservation, int runCancelled,
                            int refunded, int deliveredReported, List<FeeLine> feeMix, long deliveryFeesMinor,
                            long refundedFeesOnDeliveredMinor, boolean feesEstimated, long customersPaidMinor,
                            boolean totalsComplete, long refundsMinor, long cancelledRefundFeesMinor,
                            boolean cancelledRefundFeesEstimated, long commissionReportedMinor,
                            int commissionReportedOrders) {

    /**
     * The delivery fees charged on the orders delivered one way.
     *
     * @param kind     how they were delivered
     * @param orders   delivered orders
     * @param feeMinor fees charged on them
     */
    public record FeeLine(DeliveryKind kind, int orders, long feeMinor) {
    }

    public RunOrderTally {
        feeMix = List.copyOf(feeMix);
    }

    /** Fees kept on the delivered orders: charged minus handed back. */
    public long netDeliveryFeesMinor() {
        return Math.max(0, deliveryFeesMinor - refundedFeesOnDeliveredMinor);
    }

    /** What the cancelled-run slots count as, for a run that has none recorded: nothing. */
    public static RunOrderTally empty() {
        return of(null, List.of());
    }

    /**
     * A run whose slots are not recorded (only its counter is): its reserved orders, every amount the run's fee
     * standing in for what was charged, labelled as an estimate.
     */
    public static RunOrderTally counted(Run run) {
        int n = run.reservedOrders();
        long fee = run.terms().feeMinor();
        DeliveryKind kind = run.terms().dropMode() == DropMode.PICKUP_POINT ? DeliveryKind.PICKUP : DeliveryKind.DOOR;
        if (run.status() == RunStatus.CANCELLED) {
            return new RunOrderTally(0, 0, 0, 0, n, n, 0, List.of(), 0, 0, false, 0, false, 0, n * fee, n > 0, 0, 0);
        }
        return new RunOrderTally(n, n, 0, 0, 0, 0, 0, n == 0 ? List.of() : List.of(new FeeLine(kind, n, n * fee)),
                n * fee, 0, n > 0, 0, false, 0, 0, false, 0, 0);
    }

    /**
     * Counts a run's slots.
     *
     * @param run   the run (its fee and drop mode fill in for amounts that were never reported); null = no fallback
     * @param slots the run's slots, whatever their status
     */
    public static RunOrderTally of(Run run, List<RunOrder> slots) {
        long runFee = run == null ? 0 : run.terms().feeMinor();
        DeliveryKind defaultKind = run != null && run.terms().dropMode() == DropMode.PICKUP_POINT
                ? DeliveryKind.PICKUP : DeliveryKind.DOOR;
        int carried = 0;
        int failed = 0;
        int released = 0;
        int cancelled = 0;
        int refunded = 0;
        int reported = 0;
        int delivered = 0;
        long fees = 0;
        long refundedFees = 0;
        long paid = 0;
        long refunds = 0;
        long cancelledFees = 0;
        long commission = 0;
        int commissionOrders = 0;
        boolean feesEstimated = false;
        boolean cancelledEstimated = false;
        boolean totalsComplete = true;
        Map<DeliveryKind, int[]> mix = new EnumMap<>(DeliveryKind.class);
        Map<DeliveryKind, long[]> mixFees = new EnumMap<>(DeliveryKind.class);
        for (RunOrder o : slots) {
            RunOrderMoney m = o.money();
            switch (o.status()) {
                case RELEASED -> released++;
                case CANCELLED -> {
                    cancelled++;
                    refunded++;
                    long fee = m.refundedFeeMinor() > 0 ? m.refundedFeeMinor() : m.feeKnown() ? m.feeMinor() : runFee;
                    cancelledEstimated |= m.refundedFeeMinor() == 0 && !m.feeKnown();
                    cancelledFees += fee;
                }
                default -> {
                    carried++;
                    if (o.stage() == RunOrderStage.FAILED || o.stage() == RunOrderStage.RETURNED) {
                        failed++;
                        continue;
                    }
                    delivered++;
                    reported += o.stage() == RunOrderStage.DELIVERED ? 1 : 0;
                    long fee = m.feeKnown() ? m.feeMinor() : runFee;
                    feesEstimated |= !m.feeKnown();
                    fees += fee;
                    refundedFees += Math.min(fee, m.refundedFeeMinor());
                    if (m.totalMinor() == null) {
                        totalsComplete = false;
                    } else {
                        paid += m.totalMinor();
                    }
                    refunds += m.refundedMinor();
                    refunded += m.refunded() ? 1 : 0;
                    if (m.commissionMinor() != null) {
                        commission += m.commissionMinor();
                        commissionOrders++;
                    }
                    DeliveryKind kind = m.kind() == null ? defaultKind : m.kind();
                    mix.computeIfAbsent(kind, k -> new int[1])[0]++;
                    mixFees.computeIfAbsent(kind, k -> new long[1])[0] += fee;
                }
            }
        }
        List<FeeLine> lines = new ArrayList<>();
        for (DeliveryKind kind : DeliveryKind.values()) {
            if (mix.containsKey(kind)) {
                lines.add(new FeeLine(kind, mix.get(kind)[0], mixFees.get(kind)[0]));
            }
        }
        return new RunOrderTally(carried, delivered, failed, released, cancelled, refunded, reported, lines, fees,
                refundedFees, feesEstimated, paid, totalsComplete && delivered > 0, refunds, cancelledFees,
                cancelledEstimated, commission, commissionOrders);
    }
}
