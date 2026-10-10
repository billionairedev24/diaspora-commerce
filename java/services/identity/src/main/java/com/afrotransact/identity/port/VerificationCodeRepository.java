package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.VerificationCode;
import com.afrotransact.identity.domain.VerificationPurpose;

/** Hashed one-time codes. */
public interface VerificationCodeRepository {

    void insert(VerificationCode code);

    void update(VerificationCode code);

    /** Most recent unconsumed code for the purpose. */
    Optional<VerificationCode> latest(UUID userId, VerificationPurpose purpose);

    /** Issue times since {@code since} (used for resend rate limits). */
    List<Instant> issuedSince(UUID userId, VerificationPurpose purpose, Instant since);
}
