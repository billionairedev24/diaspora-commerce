package com.afrotransact.bookings.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * payments-service as bookings uses it (client-credentials client {@code bookings-service}, scope
 * {@code payments:service}). One manual-capture PaymentIntent per booking, keyed by the booking reference, with a
 * {@code deposit} line and a {@code balance} line. Every call carries an idempotency key derived from the booking,
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

    void refund(UUID paymentId, String lineId, long amountMinor, String reason, String idempotencyKey);

    /** Service completed (or deposit forfeited): starts the settlement hold (kind SERVICE). Idempotent. */
    void serviceCompleted(String orderId, Instant completedAt);

    /**
     * New PaymentIntent.
     *
     * @param orderId   the booking reference (payments keys everything by it)
     * @param sellerOrg the provider organisation receiving the net
     */
    record CreateIntent(String orderId, String sellerOrg, long amountMinor, String currency, List<Line> lines,
                        Map<String, String> metadata, Instant placedAt) {

        public CreateIntent {
            lines = List.copyOf(lines);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /** One intent line (deposit or balance). */
    record Line(String lineId, long amountMinor) {
    }

    /**
     * The intent as payments reports it.
     *
     * @param status payments' status: REQUIRES_PAYMENT_METHOD … AUTHORIZED, PARTIALLY_CAPTURED, CAPTURED, CANCELED,
     *               FAILED
     */
    record Intent(UUID id, String orderId, String status, String clientSecret, String failureMessage) {
    }
}
