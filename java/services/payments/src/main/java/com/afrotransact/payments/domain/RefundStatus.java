package com.afrotransact.payments.domain;

/** Refund status: PENDING until Stripe confirms the money left the platform balance. */
public enum RefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
