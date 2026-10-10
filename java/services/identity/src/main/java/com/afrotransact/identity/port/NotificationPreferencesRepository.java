package com.afrotransact.identity.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.NotificationPreferences;

/** Account-level notification switches. */
public interface NotificationPreferencesRepository {

    Optional<NotificationPreferences> find(UUID userId);

    void save(NotificationPreferences preferences);
}
