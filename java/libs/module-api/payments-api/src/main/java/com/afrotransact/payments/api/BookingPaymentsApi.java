package com.afrotransact.payments.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the bookings module asks payments in-process: the deposit and balance payments of a service booking, its captures,
 * release, refunds, the connection fee schedule and the completion that starts the settlement hold. The calls behind
 * {@code /v1/payments...}, {@code /v1/fee-schedules/effective} and {@code /v1/settlements/{orderId}/delivered}
 * (scope {@code payments:service}). Every call runs under an in-process caller principal; payments' own permission checks apply
 * and a caller without the scope is refused with {@code com.afrotransact.module.ModuleApiException} status 403. Booking lines
 * are service lines ({@code kind SERVICE}, self-fulfilled, one unit, no shipping or tax) under the service's category.
 */
public interface BookingPaymentsApi {

    BookingPayment create(NewBookingPayment request, String idempotencyKey);

    BookingPayment get(UUID paymentId, boolean refresh);

    /** @param captureId the capture's id, sent as the shipment id (idempotent per payment and capture id) */
    void capture(UUID paymentId, String captureId, List<String> lineIds, long amountMinor, boolean finalCapture,
                 String idempotencyKey);

    void cancel(UUID paymentId, String reason, String idempotencyKey);

    /** Releases the remaining authorisation after a partial capture. */
    BookingPayment release(UUID paymentId, String reason, String idempotencyKey);

    /** Charges lines off-session on the customer's saved card (long-dated bookings). */
    BookingPayment chargeOffSession(UUID paymentId, String customerId, String paymentMethodId, List<String> lineIds,
                                    String idempotencyKey);

    /** Refunds one line's items amount. */
    void refund(UUID paymentId, String lineId, long amountMinor, String reason, String idempotencyKey);

    /** Service completed: starts the settlement hold (kind SERVICE). */
    void serviceCompleted(String orderId, Instant completedAt);

    /** The connection fee (kind SERVICE) and card-fee estimate in force for a service category and organisation. */
    ConnectionFees connectionFees(String category, String orgId);

    record NewBookingPayment(String orderId, String sellerOrg, long amountMinor, String currency, List<Line> lines,
                             Map<String, String> metadata, Instant placedAt, String shopperId, boolean saveForOffSession,
                             String category) {

        public NewBookingPayment {
            lines = lines == null ? List.of() : List.copyOf(lines);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record Line(String lineId, long amountMinor) {
    }

    record BookingPayment(UUID id, String orderId, String status, String clientSecret, String failureMessage,
                          long releasedMinor, UUID parentPaymentId) {
    }

    /** @param version the fee schedule version, null when none */
    record ConnectionFees(int commissionBps, String commissionSource, int cardPctBps, long cardFixedMinor, Integer version,
                          String currency, String kind) {
    }
}
