package com.afrotransact.orders.domain;

/**
 * What the customer asked for at checkout: handover, a safe place, free-text instructions (shown in the driver app)
 * and how to be contacted.
 */
public record DeliveryPreferences(HandoverPreference handover, String safePlace, String instructions,
                                  ContactPreference contact) {

    public static final int MAX_INSTRUCTIONS = 240;

    public DeliveryPreferences {
        handover = handover == null ? HandoverPreference.DOOR : handover;
        contact = contact == null ? ContactPreference.TEXT : contact;
        safePlace = blankToNull(safePlace);
        instructions = blankToNull(instructions);
        if (instructions != null && instructions.length() > MAX_INSTRUCTIONS) {
            throw new OrdersRuleViolation("orders.instructions-too-long",
                    "Delivery instructions are limited to " + MAX_INSTRUCTIONS + " characters");
        }
    }

    public static DeliveryPreferences defaults() {
        return new DeliveryPreferences(null, null, null, null);
    }

    public boolean handToCustomer() {
        return handover == HandoverPreference.HAND;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
