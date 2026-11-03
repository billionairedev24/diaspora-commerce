package com.afrotransact.orders.domain;

/**
 * A shipping fee that the customer's membership waived (S323, ADR 0006): which tier and benefit did it and how much
 * the fee was. The shipment's {@code shippingMinor} is what the customer was charged after it. Recorded at checkout and
 * never recomputed, like the fee rule snapshot.
 *
 * @param tierCode     the membership tier (payments' tier code)
 * @param tierName     its name when the order was placed ("Plus")
 * @param benefit      FREE_DELIVERY or FREE_SHIPPING
 * @param waivedMinor  the fee not charged
 */
public record MembershipWaiver(String tierCode, String tierName, String benefit, long waivedMinor) {

    public MembershipWaiver {
        if (tierCode == null || tierCode.isBlank() || benefit == null || benefit.isBlank()) {
            throw new IllegalArgumentException("tierCode and benefit are required");
        }
        if (waivedMinor < 0) {
            throw new OrdersRuleViolation("orders.invalid-amount", "A waived fee cannot be negative");
        }
    }
}
