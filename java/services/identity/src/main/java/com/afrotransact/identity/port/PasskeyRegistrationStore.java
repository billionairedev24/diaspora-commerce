package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.PasskeyRegistration;

/** Pending passkey registrations of the first-party API (S016). */
public interface PasskeyRegistrationStore {

    void insert(PasskeyRegistration registration);

    /** SELECT … FOR UPDATE, so a registration completes once. */
    Optional<PasskeyRegistration> lock(UUID id);

    void complete(UUID id, Instant at);

    /** Housekeeping: deletes registrations that expired before {@code before}; returns how many. */
    int purgeExpired(Instant before);
}
