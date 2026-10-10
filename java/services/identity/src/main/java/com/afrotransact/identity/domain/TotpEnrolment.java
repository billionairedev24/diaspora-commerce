package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** Authenticator-app enrolment; {@code confirmedAt} is null until the first code is verified. */
public record TotpEnrolment(UUID userId, String secret, Instant createdAt, Instant confirmedAt, long lastUsedStep) {

    public boolean confirmed() {
        return confirmedAt != null;
    }

    public TotpEnrolment confirm(Instant now, long step) {
        return new TotpEnrolment(userId, secret, createdAt, now, step);
    }

    public TotpEnrolment used(long step) {
        return new TotpEnrolment(userId, secret, createdAt, confirmedAt, step);
    }
}
