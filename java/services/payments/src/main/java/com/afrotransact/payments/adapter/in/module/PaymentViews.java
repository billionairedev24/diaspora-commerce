package com.afrotransact.payments.adapter.in.module;

import com.afrotransact.payments.api.OrderPaymentsApi;
import com.afrotransact.payments.domain.Payment;

/** The published view of a payment: what orders reads (status, client secret, amounts, decline message, credit). */
final class PaymentViews {

    private PaymentViews() {
    }

    static OrderPaymentsApi.PaymentInfo of(Payment p) {
        return new OrderPaymentsApi.PaymentInfo(String.valueOf(p.id()), p.status().name(), p.clientSecret(),
                p.amount().minor(), p.failureMessage(), p.credit().minor());
    }
}
