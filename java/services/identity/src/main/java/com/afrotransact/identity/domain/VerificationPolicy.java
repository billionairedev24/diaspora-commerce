package com.afrotransact.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One-time code rules: 6 digits, 10-minute expiry, resend at most once per 30 seconds and 5 times per hour. */
public interface VerificationPolicy {

    int CODE_LENGTH = 6;
    Duration TIME_TO_LIVE = Duration.ofMinutes(10);
    Duration MIN_RESEND_INTERVAL = Duration.ofSeconds(30);
    int MAX_PER_HOUR = 5;
    int MAX_ATTEMPTS = 5;

    /** Throws {@link RateLimitExceeded} when a new code may not be sent yet. */
    void checkCanIssue(List<Instant> issuedInLastHour, Instant now);

    /** A fresh random numeric code. */
    String newCode();

    String hash(UUID codeId, String code);

    VerificationOutcome check(VerificationCode stored, String submitted, Instant now);
}
