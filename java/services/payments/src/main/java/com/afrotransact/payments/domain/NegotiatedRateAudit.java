package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit entry of a negotiated rate: who created, ended or expired it, when and why (append-only).
 *
 * @param action CREATED, ENDED or EXPIRED
 * @param actor  staff subject, or {@code system:negotiated-rate-expiry} for the expiry job
 */
public record NegotiatedRateAudit(UUID id, UUID rateId, String action, String actor, String reason, Instant at) {

    public static final String CREATED = "CREATED";
    public static final String ENDED = "ENDED";
    public static final String EXPIRED = "EXPIRED";
}
