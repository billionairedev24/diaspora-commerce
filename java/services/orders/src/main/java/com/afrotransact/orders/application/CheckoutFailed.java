package com.afrotransact.orders.application;

import java.util.List;

/** The checkout saga ended without an order; {@code code} is a {@link CheckoutCodes} value. */
public class CheckoutFailed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String orderNumber;
    private final transient List<String> details;

    public CheckoutFailed(String code, String message, String orderNumber, List<String> details) {
        super(message);
        this.code = code;
        this.orderNumber = orderNumber;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    /**
     * The failure as stored on the checkout request: the message, and for {@link CheckoutCodes#TOTAL_CHANGED} the new
     * numbers the saga attached as {@code (key=value, ...)}, split off so the problem can carry them.
     */
    public static CheckoutFailed of(String code, String stored, String orderNumber) {
        if (CheckoutCodes.TOTAL_CHANGED.equals(code) && stored != null && stored.endsWith(")") && stored.contains(" (")) {
            int at = stored.lastIndexOf(" (");
            return new CheckoutFailed(code, stored.substring(0, at), orderNumber,
                    List.of(stored.substring(at + 2, stored.length() - 1).split(", ")));
        }
        return new CheckoutFailed(code, stored, orderNumber, List.of());
    }

    public String code() {
        return code;
    }

    public String orderNumber() {
        return orderNumber;
    }

    public List<String> details() {
        return details;
    }
}
