package com.afrotransact.deliveryruns.domain;

/**
 * Community pickup point of a {@link DropMode#PICKUP_POINT} run.
 *
 * @param name       place name shown to customers, e.g. "Bastrop Public Library car park"
 * @param line1      street address
 * @param city       city
 * @param state      two-letter state
 * @param postalCode ZIP
 * @param hours      free-text hours on the delivery day, e.g. "Sat 2–5 PM"
 * @param notes      optional instructions (where to park, what to bring)
 */
public record PickupPoint(String name, String line1, String city, String state, String postalCode, String hours,
                          String notes) {

    public PickupPoint {
        if (isBlank(name) || isBlank(line1) || isBlank(city) || isBlank(hours)) {
            throw new RuleViolation("delivery-runs.invalid-pickup-point",
                    "A pickup point needs a name, an address line, a city and hours");
        }
    }

    /** One line for messages: "Bastrop Public Library, 1100 Church St, Bastrop TX 78602". */
    public String display() {
        StringBuilder b = new StringBuilder(name).append(", ").append(line1).append(", ").append(city);
        if (!isBlank(state)) {
            b.append(' ').append(state);
        }
        if (!isBlank(postalCode)) {
            b.append(' ').append(postalCode);
        }
        return b.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
