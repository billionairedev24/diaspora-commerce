package com.afrotransact.identity.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.UserSettings;

/** Job title and display preferences, one row per account. */
public interface UserSettingsRepository {

    Optional<UserSettings> find(UUID userId);

    void save(UserSettings settings);
}
