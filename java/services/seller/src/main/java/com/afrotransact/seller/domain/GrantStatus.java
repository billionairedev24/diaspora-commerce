package com.afrotransact.seller.domain;

/** Recorded first ({@code PENDING}), then applied at Stripe; a failed application is retried by a job. */
public enum GrantStatus {
    PENDING,
    APPLIED,
    FAILED
}
