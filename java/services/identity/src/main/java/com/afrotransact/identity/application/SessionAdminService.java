package com.afrotransact.identity.application;

import java.util.UUID;

import com.afrotransact.identity.domain.AdminSession;

/**
 * Active sign-ins across the platform (S291). Every method needs {@code identity:admin}; revoking needs a reason and is
 * written to the access change log. A revoked authorization takes its refresh token with it, so the refresh stops
 * working at once; an access token already issued lives until it expires (minutes).
 */
public interface SessionAdminService {

    /** @param text user name or email, client id or name; blank = anything */
    PageView<AdminSession> search(String text, UUID userId, String clientId, int page, int size);

    AdminSession get(String sessionId);

    void revoke(String sessionId, String reason);

    /** Sign out everywhere: every sign-in of the user and their single sign-on browser sessions. */
    Revoked revokeAllOfUser(UUID userId, String reason);

    Revoked revokeAllOfClient(String clientId, String reason);

    /** @param browserSessions single sign-on browser sessions ended (user revocations only) */
    record Revoked(int sessions, int browserSessions) {
    }
}
