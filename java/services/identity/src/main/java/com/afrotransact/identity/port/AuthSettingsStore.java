package com.afrotransact.identity.port;

import java.time.Instant;

import com.afrotransact.identity.domain.AuthSettingsOverrides;

/** The stored authentication settings (one row; null columns fall back to the configured defaults). */
public interface AuthSettingsStore {

    AuthSettingsOverrides load();

    void save(AuthSettingsOverrides overrides, Instant at, String by);
}
