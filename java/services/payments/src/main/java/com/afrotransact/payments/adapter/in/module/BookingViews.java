package com.afrotransact.payments.adapter.in.module;

import com.afrotransact.payments.api.BookingPaymentsApi.BookingPayment;
import com.afrotransact.payments.domain.Payment;

/** The published view of a payment for bookings: id, order, status, client secret, decline message, released amount, parent. */
final class BookingViews {

    private BookingViews() {
    }

    static BookingPayment of(Payment p) {
        return new BookingPayment(p.id(), p.orderId(), p.status().name(), p.clientSecret(), p.failureMessage(),
                p.released().minor(), p.parentPaymentId());
    }
}
