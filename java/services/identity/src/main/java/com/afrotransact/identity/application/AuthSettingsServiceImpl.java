package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.afrotransact.identity.domain.AdminReason;
import com.afrotransact.identity.domain.AuthSettings;
import com.afrotransact.identity.domain.AuthSettingsOverrides;
import com.afrotransact.identity.domain.AuthSettingsRules;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.AuthSettingsStore;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthSettingsServiceImpl implements AuthSettingsService {

    /** Other replicas see a change within this time. */
    private static final Duration CACHE = Duration.ofSeconds(5);

    private final AuthSettingsStore store;
    private final AuthSettings defaults;
    private final PermissionChecker permissions;
    private final AccessChangeLog changeLog;
    private final Clock clock;
    private volatile Cached cached;

    AuthSettingsServiceImpl(AuthSettingsStore store, AuthSettings defaults, PermissionChecker permissions,
                            AccessChangeLog changeLog, Clock clock) {
        this.store = store;
        this.defaults = defaults;
        this.permissions = permissions;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Override
    public AuthSettings effective() {
        Instant now = Instant.now(clock);
        Cached c = cached;
        if (c == null || c.at().plus(CACHE).isBefore(now)) {
            c = new Cached(now, AuthSettings.resolve(defaults, store.load()));
            cached = c;
        }
        return c.settings();
    }

    @Override
    @Transactional(readOnly = true)
    public AuthSettingsView view() {
        permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        AuthSettingsOverrides stored = store.load();
        return new AuthSettingsView(AuthSettings.resolve(defaults, stored), stored, defaults);
    }

    @Override
    @Transactional
    public AuthSettingsView update(AuthSettingsOverrides overrides, String reason) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        String why = AdminReason.require(reason);
        AuthSettings before = AuthSettings.resolve(defaults, store.load());
        AuthSettings after = AuthSettings.resolve(defaults, overrides);
        AuthSettingsRules.validate(overrides, after);
        store.save(overrides, Instant.now(clock), actor.subject());
        cached = null;
        changeLog.append(actor.subject(), "auth-settings.updated", null, null, "auth-settings",
                "reason=" + why + "; before=" + before.auditSummary() + "; after=" + after.auditSummary());
        return new AuthSettingsView(after, overrides, defaults);
    }

    private record Cached(Instant at, AuthSettings settings) {
    }
}
