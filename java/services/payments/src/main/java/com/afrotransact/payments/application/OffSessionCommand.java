package com.afrotransact.payments.application;

import java.util.List;

/**
 * Charge lines of an order off-session on a saved payment method.
 *
 * @param customerId      AfroTransact customer id (token subject) who owns the saved method
 * @param paymentMethodId saved card (pm_…); null = the customer's default method
 * @param lineIds         lines of the primary payment to charge; empty = every line not captured yet
 */
public record OffSessionCommand(String customerId, String paymentMethodId, List<String> lineIds) {

    public OffSessionCommand {
        lineIds = lineIds == null ? List.of() : List.copyOf(lineIds);
    }
}
