package com.afrotransact.orders.domain;

import java.util.Locale;

/** How a pre-order package reaches the customer on the window's delivery date (S272). */
public enum PreorderFulfilment {
    /** Delivered to the door by the run's driver; the window's door fee (default $7.99). */
    DOOR,
    /** Collected at the window's pickup point; the window's pickup fee (default free). */
    PICKUP;

    /** Parses DOOR / PICKUP (also the checkout options door, delivery, afrotransact_delivery, pickup). */
    public static PreorderFulfilment parse(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "door", "delivery", "afrotransact_delivery", "local" -> DOOR;
            case "pickup" -> PICKUP;
            default -> throw new OrdersRuleViolation("orders.invalid-delivery-selection",
                    "Pre-order packages are delivered to the door or picked up, not " + value);
        };
    }

    /** The shipment's delivery method. */
    public DeliveryMethod method() {
        return this == DOOR ? DeliveryMethod.AFROTRANSACT_DELIVERY : DeliveryMethod.PICKUP;
    }
}
