package com.afrotransact.seller.domain;

/**
 * What a reward rule measures.
 * <ul>
 *   <li>{@code GROSS_SALES}: delivered shipment totals in cents (orders.shipment.delivered).</li>
 *   <li>{@code COMPLETED_ORDERS}: delivered shipments (orders.shipment.delivered).</li>
 *   <li>{@code COMPLETED_BOOKINGS}: completed bookings (booking.completed, providers).</li>
 *   <li>{@code ACTIVE_LISTINGS}: live offers now (offers.offer.*), a gauge.</li>
 *   <li>{@code DAYS_SINCE_APPROVAL}: days since final approval, a gauge (daily job).</li>
 * </ul>
 */
public enum RewardMetric {
    GROSS_SALES(true, true),
    COMPLETED_ORDERS(true, true),
    COMPLETED_BOOKINGS(true, true),
    ACTIVE_LISTINGS(false, true),
    DAYS_SINCE_APPROVAL(false, false);

    private final boolean summed;
    private final boolean eventDriven;

    RewardMetric(boolean summed, boolean eventDriven) {
        this.summed = summed;
        this.eventDriven = eventDriven;
    }

    /** Summed per day over the rule's window (else a value read now). */
    public boolean summed() {
        return summed;
    }

    /** Re-evaluated as events arrive (all metrics are also evaluated by the daily job). */
    public boolean eventDriven() {
        return eventDriven;
    }

    /** "$640" for sales, "12" otherwise. */
    public String format(long value) {
        return this == GROSS_SALES ? PlanPricing.usd(value) : Long.toString(value);
    }

    /** "in sales", "active listings"… for "$640 of $1,000 in sales". */
    public String noun() {
        return switch (this) {
            case GROSS_SALES -> "in sales";
            case COMPLETED_ORDERS -> "completed orders";
            case COMPLETED_BOOKINGS -> "completed bookings";
            case ACTIVE_LISTINGS -> "active listings";
            case DAYS_SINCE_APPROVAL -> "days on AfroTransact";
        };
    }
}
