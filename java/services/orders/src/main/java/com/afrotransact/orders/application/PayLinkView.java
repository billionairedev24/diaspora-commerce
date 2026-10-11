package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The pay page of a late pre-order add.
 *
 * @param status          AWAITING_PAYMENT (pay with the client secret) or PAID (refused: nothing left to pay)
 * @param clientSecret    the order's PaymentIntent client secret, only while AWAITING_PAYMENT
 * @param failureMessage  the last decline, if the customer's previous attempt failed (they may try again)
 * @param expiresAt       when the link stops working (the order is then voided and its PaymentIntent cancelled)
 * @param deliveryFeeMinor the pre-order delivery fee included in the total
 */
public record PayLinkView(String orderNumber, String status, long totalMinor, long deliveryFeeMinor, String currency,
                          String clientSecret, String failureMessage, Instant expiresAt, String windowName,
                          LocalDate deliversOn, String fulfilment, List<Line> lines) {

    public PayLinkView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One line of the order. */
    public record Line(String title, int quantity, long totalMinor) {
    }
}
