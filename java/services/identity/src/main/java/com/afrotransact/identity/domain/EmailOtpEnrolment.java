package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** Email codes turned on as a second factor (codes go to the account's verified email). */
public record EmailOtpEnrolment(UUID userId, boolean enabled, Instant enrolledAt, Instant lastUsedAt) {
}
