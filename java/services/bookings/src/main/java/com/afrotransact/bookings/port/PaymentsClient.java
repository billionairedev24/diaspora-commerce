package com.afrotransact.bookings.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * payments-service as bookings uses it (client-credentials client {@code bookings-service}, scope
 * {@code payments:service}). One manual-capture PaymentIntent per booking, keyed by the booking reference, with a
 * {@code deposit} line and a {@code balance} line; for long-dated bookings the balance is charged later as an
 * off-session child payment of the same order. Every call carries an idempotency key derived from the booking,
 * so retries never move money twice.
 */
public interface PaymentsClient {

    Intent createIntent(CreateIntent request, String idempotencyKey);

    /** Reads the intent; {@code refresh} asks payments to re-read Stripe first. */
    Intent get(UUID paymentId, boolean refresh);

    void capture(UUID paymentId, String captureId, List<String> lineIds, long amountMinor, boolean finalCapture,
                 String idempotencyKey);

    /** Cancels the uncaptured intent: the authorisation is released. */
    void cancel(UUID paymentId, String reason, String idempotencyKey);

    /**
     * Releases the uncaptured rest of the authorisation after the deposit capture ({@code POST …/release}). The
     * payment ends CAPTURED (or CANCELED without captures) with {@link Intent#releasedMinor()}.
     */
    Intent release(UUID paymentId, String reason, String idempotencyKey);

    /**
     * Charges lines of the booking off-session on the customer's saved card ({@code POST …/off-session-charges});
     * returns the new child payment: AUTHORIZED, REQUIRES_ACTION (with its client secret) or FAILED.
     */
    Intent chargeOffSession(UUID paymentId, OffSessionCharge request, String idempotencyKey);

    void refund(UUID paymentId, String lineId, long amountMinor, String reason, String idempotencyKey);

    /** Service completed (or deposit forfeited): starts the settlement hold (kind SERVICE). Idempotent. */
    void serviceCompleted(String orderId, Instant completedAt);

    /**
     * The connection fee and card fees payments applies to the organisation's bookings of a service category
     * ({@code GET /v1/fee-schedules/effective?kind=SERVICE}).
     */
    EffectiveFees effectiveFees(String category, String orgId);

    /**
     * New PaymentIntent.
     *
     * @param orderId           the booking reference (payments keys everything by it)
     * @param sellerOrg         the provider organisation receiving the net
     * @param shopperId         the customer's token subject (owner of the saved card)
     * @param saveForOffSession save the confirmed card for a later off-session balance charge
     * @param category          the service's category: payments charges the provider's connection fee for it
     *                          (lines are sent as kind SERVICE)
     */
    record CreateIntent(String orderId, String sellerOrg, long amountMinor, String currency, List<Line> lines,
                        Map<String, String> metadata, Instant placedAt, String shopperId, boolean saveForOffSession,
                        String category) {

        public CreateIntent {
            lines = List.copyOf(lines);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            category = category == null || category.isBlank() ? "services" : category;
        }
    }

    /** One intent line (deposit or balance). */
    record Line(String lineId, long amountMinor) {
    }

    /**
     * An off-session charge.
     *
     * @param customerId      the customer's token subject
     * @param paymentMethodId saved card {@code pm_…}, or null for the customer's default method
     */
    record OffSessionCharge(String customerId, String paymentMethodId, List<String> lineIds) {

        public OffSessionCharge {
            lineIds = List.copyOf(lineIds);
        }
    }

    /**
     * The intent as payments reports it.
     *
     * @param status          payments' status: REQUIRES_PAYMENT_METHOD … REQUIRES_ACTION, AUTHORIZED,
     *                        PARTIALLY_CAPTURED, CAPTURED, CANCELED, FAILED
     * @param releasedMinor   authorised but released without capture
     * @param parentPaymentId set for an off-session charge: the booking's original payment
     */
    record Intent(UUID id, String orderId, String status, String clientSecret, String failureMessage,
                  long releasedMinor, UUID parentPaymentId) {
    }

    /**
     * Effective fees of an organisation and service category.
     *
     * @param commissionBps    the connection fee (payments' commissionBps for kind SERVICE)
     * @param commissionSource OVERRIDE (negotiated), PLAN, CATEGORY, DEFAULT or RETAIL
     * @param version          fee-schedule version in force
     * @param kind             SERVICE when payments priced a connection fee; null from a payments without
     *                         connection fees (it ignores kind and answers the category's product commission)
     */
    record EffectiveFees(int commissionBps, String commissionSource, int cardPctBps, long cardFixedMinor,
                         Integer version, String currency, String kind) {

        /** Fees without a kind (configured fallbacks, older payments). */
        public EffectiveFees(int commissionBps, String commissionSource, int cardPctBps, long cardFixedMinor,
                             Integer version, String currency) {
            this(commissionBps, commissionSource, cardPctBps, cardFixedMinor, version, currency, null);
        }

        /** payments answered for kind SERVICE (it knows connection fees). */
        public boolean connectionFees() {
            return "SERVICE".equals(kind);
        }
    }
}
