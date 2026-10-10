package com.afrotransact.payments.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Capture;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.Refund;

/** Payments with their lines, captures and refunds. */
public interface PaymentRepository {

    /** Inserts the payment and its lines; false when the order already has a payment. */
    boolean claim(Payment payment);

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByOrderId(String orderId);

    Optional<Payment> findByIntentId(String intentId);

    /** SELECT … FOR UPDATE. */
    Optional<Payment> lockById(UUID id);

    Optional<Payment> lockByIntentId(String intentId);

    /** Writes header and line state (optimistic: version must match) and returns it with version + 1. */
    Payment update(Payment payment);

    void insertCapture(Capture capture);

    Optional<Capture> findCapture(UUID captureId);

    Optional<Capture> findCapture(UUID paymentId, String shipmentId);

    List<Capture> captures(UUID paymentId);

    void insertRefund(Refund refund);

    void updateRefund(Refund refund);

    Optional<Refund> findRefund(UUID refundId);

    List<Refund> refunds(UUID paymentId);
}
