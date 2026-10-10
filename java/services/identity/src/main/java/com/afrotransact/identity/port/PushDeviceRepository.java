package com.afrotransact.identity.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.PushApp;
import com.afrotransact.identity.domain.PushDevice;

/** Push device registrations. */
public interface PushDeviceRepository {

    /** Inserts the device, or moves an existing registration of the same token to this account/app. */
    PushDevice upsert(PushDevice device);

    Optional<PushDevice> findByTokenHash(String tokenHash);

    List<PushDevice> findByUser(UUID userId);

    /** @return true when the user's registration was removed */
    boolean delete(UUID userId, UUID deviceId);

    /** @return true when the user's registration of this token was removed */
    boolean deleteByTokenHash(UUID userId, String tokenHash);

    /** Removes the least recently seen registrations beyond {@code keep} for the user's app. */
    int trim(UUID userId, PushApp app, int keep);
}
