package com.afrotransact.payments.application;

import com.afrotransact.payments.domain.Payment;

/** A payment and whether this answer replays an earlier identical request. */
public record PaymentResult(Payment payment, boolean replayed) {
}
