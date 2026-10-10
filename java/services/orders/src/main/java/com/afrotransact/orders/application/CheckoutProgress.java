package com.afrotransact.orders.application;

import java.util.List;

/** Where a checkout saga is (workflow query and result). */
public record CheckoutProgress(Phase phase, String orderId, String orderNumber, String paymentId,
                               String clientSecret, String failureCode, String failureMessage,
                               List<String> details) {

    public CheckoutProgress {
        details = details == null ? List.of() : List.copyOf(details);
    }

    /** Saga phases. */
    public enum Phase {
        VALIDATING,
        RESERVING,
        AUTHORIZING,
        AWAITING_PAYMENT,
        PLACING,
        PLACED,
        FAILED
    }

    static CheckoutProgress of(Phase phase, String orderId, String orderNumber) {
        return new CheckoutProgress(phase, orderId, orderNumber, null, null, null, null, List.of());
    }

    CheckoutProgress at(Phase next) {
        return new CheckoutProgress(next, orderId, orderNumber, paymentId, clientSecret, failureCode,
                failureMessage, details);
    }

    CheckoutProgress withPayment(PaymentRef payment) {
        return new CheckoutProgress(phase, orderId, orderNumber, payment.paymentId(), payment.clientSecret(),
                failureCode, failureMessage, details);
    }

    CheckoutProgress failed(String code, String message, List<String> moreDetails) {
        return new CheckoutProgress(Phase.FAILED, orderId, orderNumber, paymentId, clientSecret, code, message,
                moreDetails);
    }
}
