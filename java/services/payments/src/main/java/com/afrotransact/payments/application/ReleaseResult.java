package com.afrotransact.payments.application;

import com.afrotransact.payments.domain.Payment;

/** The payment after its remaining authorisation was released, and whether this answer is a replay. */
public record ReleaseResult(Payment payment, boolean replayed) {
}
