package com.afrotransact.identity.application;

import com.afrotransact.identity.domain.AuthSettings;
import com.afrotransact.identity.domain.AuthSettingsOverrides;

/** Authentication settings stored in the identity database over the configured defaults (S291). */
public interface AuthSettingsService {

    /** The settings in force; read by sign-in, password and token code at runtime (cached for a few seconds). */
    AuthSettings effective();

    /** {@code identity:admin}: what is in force, what is stored and the defaults. */
    AuthSettingsView view();

    /**
     * {@code identity:admin}: replaces the stored overrides (a null field goes back to its default), validated and
     * written to the access change log with the reason and the before/after values.
     */
    AuthSettingsView update(AuthSettingsOverrides overrides, String reason);

    /** What the admin screen shows. */
    record AuthSettingsView(AuthSettings effective, AuthSettingsOverrides overrides, AuthSettings defaults) {
    }
}
