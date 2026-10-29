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
 * @param useCredit          S320: pay with the shopper's AfroTransact credit first (needs {@code shopperId}); the
 *                           card is charged only for the rest, and not at all when credit pays everything
 * @param membershipWaivers  S323: delivery fees the shopper's membership waived on this order (tier and benefit per
 *                           shipment), recorded with the payment as the benefits used
 * @param offSession         S272 late pre-order adds: confirm the intent now, off-session, on the shopper's default
 *                           saved card (needs {@code shopperId}). Late adds paid with a pay-now link are ordinary
 *                           intents (the customer confirms them on AfroTransact's pay page with Stripe Elements).
 */
public record CreatePaymentCommand(String orderId, String customerId, Money amount, List<String> paymentMethodTypes,
                                   Instant placedAt, Map<String, String> metadata, List<OrderLine> lines,
                                   String shopperId, boolean saveForOffSession, boolean offSession,
                                   boolean useCredit,
                                   List<MembershipBenefitsService.AppliedWaiver> membershipWaivers) {

    public CreatePaymentCommand {
        membershipWaivers = membershipWaivers == null ? List.of() : List.copyOf(membershipWaivers);
        paymentMethodTypes = paymentMethodTypes == null ? List.of() : List.copyOf(paymentMethodTypes);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Without membership waivers. */
    public CreatePaymentCommand(String orderId, String customerId, Money amount, List<String> paymentMethodTypes,
                                Instant placedAt, Map<String, String> metadata, List<OrderLine> lines,
                                String shopperId, boolean saveForOffSession, boolean offSession,
                                boolean useCredit) {
        this(orderId, customerId, amount, paymentMethodTypes, placedAt, metadata, lines, shopperId,
                saveForOffSession, offSession, useCredit, List.of());
    }

    /** Without credit. */
    public CreatePaymentCommand(String orderId, String customerId, Money amount, List<String> paymentMethodTypes,
                                Instant placedAt, Map<String, String> metadata, List<OrderLine> lines,
                                String shopperId, boolean saveForOffSession, boolean offSession) {
        this(orderId, customerId, amount, paymentMethodTypes, placedAt, metadata, lines, shopperId,
                saveForOffSession, offSession, false);
    }

    /** A checkout payment (the shopper confirms with the client secret). */
    public CreatePaymentCommand(String orderId, String customerId, Money amount, List<String> paymentMethodTypes,
                                Instant placedAt, Map<String, String> metadata, List<OrderLine> lines,
                                String shopperId, boolean saveForOffSession) {
        this(orderId, customerId, amount, paymentMethodTypes, placedAt, metadata, lines, shopperId,
                saveForOffSession, false);
    }
}
