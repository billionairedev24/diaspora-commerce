package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.AdminReason;
import com.afrotransact.identity.domain.AdminSession;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OAuthClientAdminStore;
import com.afrotransact.identity.port.SessionAdminStore;
import com.afrotransact.identity.port.SessionStore;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SessionAdminServiceImpl implements SessionAdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SessionAdminStore sessions;
    private final SessionStore browserSessions;
    private final OAuthClientAdminStore clients;
    private final PermissionChecker permissions;
    private final AccessChangeLog changeLog;
    private final Clock clock;

    SessionAdminServiceImpl(SessionAdminStore sessions, SessionStore browserSessions, OAuthClientAdminStore clients,
                            PermissionChecker permissions, AccessChangeLog changeLog, Clock clock) {
        this.sessions = sessions;
        this.browserSessions = browserSessions;
        this.clients = clients;
        this.permissions = permissions;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<AdminSession> search(String text, UUID userId, String clientId, int page, int size) {
        permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageNo = Math.max(0, page);
        SessionAdminStore.Page p = sessions.search(userId == null ? null : userId.toString(), blankToNull(clientId),
                blankToNull(text), Instant.now(clock), pageSize, pageNo * pageSize);
        return new PageView<>(p.items(), pageNo, pageSize, p.total());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminSession get(String sessionId) {
        permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        return sessions.find(sessionId).orElseThrow(() -> new NotFoundException("identity.session-not-found",
                "That sign-in is no longer active"));
    }

    @Override
    @Transactional
    public void revoke(String sessionId, String reason) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        String why = AdminReason.require(reason);
        AdminSession s = sessions.find(sessionId).orElseThrow(() -> new NotFoundException(
                "identity.session-not-found", "That sign-in is no longer active"));
        sessions.revoke(sessionId);
        changeLog.append(actor.subject(), "session.revoked", uuid(s.userId()), null, s.clientId(),
                "reason=" + why + "; session=" + s.id());
    }

    @Override
    @Transactional
    public Revoked revokeAllOfUser(UUID userId, String reason) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        String why = AdminReason.require(reason);
        int revoked = sessions.revokeAllOfUser(userId.toString());
        int browser = browserSessions.endBrowserSessions(userId);
        changeLog.append(actor.subject(), "sessions.revoked-user", userId, null, userId.toString(),
                "reason=" + why + "; sessions=" + revoked + "; browserSessions=" + browser);
        return new Revoked(revoked, browser);
    }

    @Override
    @Transactional
    public Revoked revokeAllOfClient(String clientId, String reason) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        String why = AdminReason.require(reason);
        if (clients.find(clientId).isEmpty()) {
            throw new NotFoundException("identity.client-not-found", "Unknown OAuth client " + clientId);
        }
        int revoked = sessions.revokeAllOfClient(clientId);
        changeLog.append(actor.subject(), "sessions.revoked-client", null, null, clientId,
                "reason=" + why + "; sessions=" + revoked);
        return new Revoked(revoked, 0);
    }

    private static String blankToNull(String s) {
        return Optional.ofNullable(s).map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException notAUser) {
            return null;
        }
    }
}
