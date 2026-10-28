package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.UUID;

/** Issued step-up proofs, by the SHA-256 of the proof (the proof itself is never stored). */
public interface StepUpStore {

    void save(String tokenHash, UUID userId, Instant createdAt, Instant expiresAt);

    /** True when this user holds an unexpired proof with this hash. */
    boolean isValid(String tokenHash, UUID userId, Instant now);

    /** Deletes the proofs that have expired; returns how many. */
    int deleteExpired(Instant now);
}
