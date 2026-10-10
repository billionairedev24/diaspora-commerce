package com.afrotransact.orders.domain;

import java.util.Collection;

/**
 * Order money in minor units. Invariant: {@code grandTotal = items + shipping + tax − discount}, and the order totals
 * equal the sum of its shipments.
 */
public record OrderTotals(long itemsMinor, long shippingMinor, long taxMinor, long discountMinor,
                          long grandTotalMinor) {

    public OrderTotals {
        if (itemsMinor < 0 || shippingMinor < 0 || taxMinor < 0 || discountMinor < 0) {
            throw new OrdersRuleViolation("orders.invalid-amount", "Order amounts cannot be negative");
        }
        if (grandTotalMinor != itemsMinor + shippingMinor + taxMinor - discountMinor) {
            throw new IllegalArgumentException("grand total " + grandTotalMinor + " != items + shipping + tax - "
                    + "discount");
        }
        if (grandTotalMinor < 0) {
            throw new OrdersRuleViolation("orders.invalid-amount", "Discounts cannot exceed the order total");
        }
    }

    public static OrderTotals of(long items, long shipping, long tax, long discount) {
        return new OrderTotals(items, shipping, tax, discount, items + shipping + tax - discount);
    }

    /** Sums the shipments' items, shipping, tax and line discounts. */
    public static OrderTotals ofShipments(Collection<Shipment> shipments) {
        long items = 0;
        long shipping = 0;
        long tax = 0;
        long discount = 0;
        for (Shipment s : shipments) {
            items += s.itemsMinor();
            shipping += s.shippingMinor();
            tax += s.taxMinor();
            discount += s.discountMinor();
        }
        return of(items, shipping, tax, discount);
    }
}
