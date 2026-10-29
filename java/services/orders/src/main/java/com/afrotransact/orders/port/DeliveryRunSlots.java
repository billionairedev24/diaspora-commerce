package com.afrotransact.orders.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.orders.domain.FeeRuleSnapshot;

/**
 * delivery-runs and the delivery runs staff schedule (S260, S261, ADR 0005): the runs open for an address
 * ({@code GET /v1/public/runs/available?zip=}, no sign-in) and the slot an order holds on one
 * ({@code POST /v1/service/runs/{id}/reservations}, {@code DELETE …/{orderId}}, orders-service token with
 * {@code delivery-runs:reserve}). Throws {@link UpstreamFailure} when delivery-runs is unavailable.
 */
public interface DeliveryRunSlots {

    /** Open runs covering the address (a 5-digit ZIP), earliest cutoff first; full ones are included, flagged. */
    List<OpenRun> openRuns(String zip);

    /**
     * The same for a member with early access (S323): announced runs opening within {@code earlyAccessHours} are
     * listed too. Implementations that cannot pass the hours answer as without them.
     */
    default List<OpenRun> openRuns(String zip, int earlyAccessHours) {
        return openRuns(zip);
    }

    /**
     * Takes the order's slot on exactly this run (never rolled to the next run: the shopper was quoted this run's date
     * and fee). Idempotent per order: the same order again answers the slot it holds. Throws {@link RunUnavailable}
     * when the run is closed or full.
     */
    void reserve(String runId, String orderId, String customerId);

    /** The same for a member with early access: the run may still be announced, opening within the hours. */
    default void reserve(String runId, String orderId, String customerId, int earlyAccessHours) {
        reserve(runId, orderId, customerId);
    }

    /**
     * The same, telling delivery-runs what the order is charged (S265) so the run's P&amp;L is built from the fees
     * orders actually paid. Implementations that cannot pass it reserve without.
     */
    default void reserve(String runId, String orderId, String customerId, int earlyAccessHours, RunCharge charge) {
        reserve(runId, orderId, customerId, earlyAccessHours);
    }

    /**
     * What an order of a run is charged.
     *
     * @param feeMinor   the delivery fee
     * @param totalMinor everything the customer pays for the shipment, delivery fee and tax included
     * @param kind       {@code DOOR}, {@code PICKUP} or {@code CARRIER}
     */
    record RunCharge(long feeMinor, long totalMinor, String kind) {
    }

    /**
     * Gives the order's slot back (cancelled, refunded or failed order). Idempotent: an order without a slot, or one
     * whose slot is already released or cancelled, is fine.
     */
    void release(String runId, String orderId);

    /**
     * One open run as checkout needs it.
     *
     * @param runId         delivery-runs run
     * @param town          the location's name, e.g. Bastrop
     * @param cutoffAt      last moment to order; the customer may cancel for a full refund until then
     * @param deliversOn    the delivery day (in the location's time zone)
     * @param timeZone      the location's IANA time zone
     * @param feeMinor      the run's delivery fee
     * @param currency      its currency
     * @param door          doorstep delivery
     * @param pickup        collection at the run's pickup point
     * @param pickupPointId the run's pickup point (null when none or not sent)
     * @param feeRule       the rule behind the fee (ADR 0005 §3), kept on the order; null when not sent
     * @param minOrders     orders the run needs to go ahead
     * @param ordersSoFar   orders holding a slot now
     * @param full          no room left for another order
     */
    record OpenRun(String runId, String town, Instant cutoffAt, LocalDate deliversOn, String timeZone, long feeMinor,
                   String currency, boolean door, boolean pickup, String pickupPointId, FeeRuleSnapshot feeRule,
                   int minOrders, int ordersSoFar, boolean full) {
    }

    /** The run no longer takes this order: its cutoff passed ({@code run-closed}) or it is full ({@code run-full}). */
    class RunUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        public RunUnavailable(String code, String message) {
            super(message);
            this.code = code;
        }

        /** {@code orders.run-closed} or {@code orders.run-full}. */
        public String code() {
            return code;
        }
    }
}
