package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.identity.domain.AdminReason;
import com.afrotransact.identity.domain.SessionAudience;
import com.afrotransact.identity.domain.SessionPolicy;
import com.afrotransact.identity.domain.SessionPolicyDefaults;
import com.afrotransact.identity.domain.SessionPolicyOverrides;
import com.afrotransact.identity.domain.SessionPolicyRules;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.SessionPolicyStore;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SessionPolicyServiceImpl implements SessionPolicyService {

    private final SessionPolicyStore store;
    private final PermissionChecker permissions;
    private final CurrentUser current;
    private final AccessChangeLog changeLog;
    private final Clock clock;

    SessionPolicyServiceImpl(SessionPolicyStore store, PermissionChecker permissions, CurrentUser current,
                             AccessChangeLog changeLog, Clock clock) {
        this.store = store;
        this.permissions = permissions;
        this.current = current;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<SessionAudience, SessionPolicy> effective() {
        Actor actor = current.actor();
        if (!actor.can(IdentityPermissions.SESSIONS_POLICY_READ) && !actor.can(IdentityPermissions.IDENTITY_ADMIN)) {
            throw new ForbiddenActionException("identity.session-policy-read-only",
                    "Reading the session policy needs a service token with sessions:policy.read");
        }
        Map<SessionAudience, SessionPolicyOverrides> stored = store.loadAll();
        Map<SessionAudience, SessionPolicy> out = new EnumMap<>(SessionAudience.class);
        for (SessionAudience a : SessionAudience.values()) {
            out.put(a, SessionPolicy.resolve(SessionPolicyDefaults.of(a),
                    stored.getOrDefault(a, SessionPolicyOverrides.none())));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionPolicyEntry> view() {
        permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        Map<SessionAudience, SessionPolicyOverrides> stored = store.loadAll();
        return List.of(SessionAudience.values()).stream()
                .map(a -> entry(a, stored.getOrDefault(a, SessionPolicyOverrides.none())))
                .toList();
    }

    @Override
    @Transactional
    public SessionPolicyEntry update(SessionAudience audience, SessionPolicyOverrides overrides, String reason) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        String why = AdminReason.require(reason);
        SessionPolicy defaults = SessionPolicyDefaults.of(audience);
        SessionPolicyOverrides stored = overrides.withoutDefaults(defaults);
        SessionPolicy before = SessionPolicy.resolve(defaults,
                store.loadAll().getOrDefault(audience, SessionPolicyOverrides.none()));
        SessionPolicy after = SessionPolicy.resolve(defaults, stored);
        SessionPolicyRules.validate(audience, overrides, SessionPolicy.resolve(defaults, overrides));
        store.save(audience, stored, Instant.now(clock), actor.subject());
        changeLog.append(actor.subject(), "session-policy.updated", null, null, "session-policy:" + audience.key(),
                "reason=" + why + "; before=" + before.auditSummary() + "; after=" + after.auditSummary());
        return new SessionPolicyEntry(audience, after, stored, defaults);
    }

    private static SessionPolicyEntry entry(SessionAudience a, SessionPolicyOverrides stored) {
        SessionPolicy defaults = SessionPolicyDefaults.of(a);
        return new SessionPolicyEntry(a, SessionPolicy.resolve(defaults, stored), stored, defaults);
    }
}
