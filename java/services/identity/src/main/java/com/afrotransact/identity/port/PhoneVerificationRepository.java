package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The phone number each account confirmed with an SMS code (S234). */
public interface PhoneVerificationRepository {

    /** The E.164 number the user last confirmed, if any. */
    Optional<String> verifiedPhone(UUID userId);

    void markVerified(UUID userId, String phoneE164, Instant at);

    /** Forgets the confirmation (the account's phone number changed). */
    void clear(UUID userId);
}
