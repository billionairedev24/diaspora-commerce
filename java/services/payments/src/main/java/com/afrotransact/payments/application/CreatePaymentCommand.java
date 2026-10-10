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
 * @param shopperId          AfroTransact customer id (token subject); needed with {@code saveForOffSession}
 * @param saveForOffSession  attach the confirmed method to the shopper's Stripe Customer (setup_future_usage =
 *                           off_session) so later balances can be charged off-session (long-dated bookings)
 * @param offSession         S272 late pre-order adds: confirm the intent now, off-session, on the shopper's default
 *                           saved card (needs {@code shopperId})
 * @param paymentLinkHours   S272 late pre-order adds: instead of a client secret, create a hosted payment link that
 *                           expires after this many hours (1–24); null = no link
 */
public record CreatePaymentCommand(String orderId, String customerId, Money amount, List<String> paymentMethodTypes,
                                   Instant placedAt, Map<String, String> metadata, List<OrderLine> lines,
                                   String shopperId, boolean saveForOffSession, boolean offSession,
                                   Integer paymentLinkHours) {

    public CreatePaymentCommand {
        paymentMethodTypes = paymentMethodTypes == null ? List.of() : List.copyOf(paymentMethodTypes);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** A checkout payment (the shopper confirms with the client secret). */
    public CreatePaymentCommand(String orderId, String customerId, Money amount, List<String> paymentMethodTypes,
                                Instant placedAt, Map<String, String> metadata, List<OrderLine> lines,
                                String shopperId, boolean saveForOffSession) {
        this(orderId, customerId, amount, paymentMethodTypes, placedAt, metadata, lines, shopperId,
                saveForOffSession, false, null);
    }

    /** True when a hosted payment link is wanted. */
    public boolean paymentLink() {
        return paymentLinkHours != null;
    }
}
