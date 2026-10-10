package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.OrderLine;

/**
 * Create the PaymentIntent of an order.
 *
 * @param customerId         Stripe customer id (cus_…) of the shopper, optional for guests
 * @param paymentMethodTypes explicit types (card, cashapp, affirm, us_bank_account); empty = automatic methods
 * @param placedAt           when the order was placed (selects the fee schedule version); null = now
 */
public record CreatePaymentCommand(String orderId, String customerId, Money amount, List<String> paymentMethodTypes,
                                   Instant placedAt, Map<String, String> metadata, List<OrderLine> lines) {

    public CreatePaymentCommand {
        paymentMethodTypes = paymentMethodTypes == null ? List.of() : List.copyOf(paymentMethodTypes);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
