package com.afrotransact.identity.application;

import java.time.Duration;
import java.util.Optional;

/**
 * Brute-force protection shared by the hosted sign-in pages and the first-party API: failure counters per account
 * (normalised email) and per IP, temporary escalating lockouts, an append-only audit trail and
 * {@code identity.sign-in.locked} events. Failures are recorded in their own transaction, so they stick even when
 * the caller's transaction rolls back.
 */
public interface SignInProtectionService {

    /** Remaining lockout of the IP or, when {@code email} is set, the account (the longer one). */
    Optional<Duration> lockedFor(String email, String ip);

    /**
     * Throws {@code RateLimitExceeded} ({@code identity.sign-in-locked}, HTTP 429 + Retry-After) while locked, and
     * audits the blocked attempt.
     */
    void requireNotLocked(SignInAttempt attempt);

    /** Counts a failed attempt; returns the lockout when this failure (or an earlier one) locked a key. */
    Optional<Duration> recordFailure(SignInAttempt attempt);

    /** A completed sign-in: clears the account counter and audits it. */
    void recordSuccess(SignInAttempt attempt);

    /** Audits an attempt without counting it (MFA_REQUIRED, TICKET_REDEEMED, TICKET_REJECTED, …). */
    void audit(SignInAttempt attempt, String outcome);
}
