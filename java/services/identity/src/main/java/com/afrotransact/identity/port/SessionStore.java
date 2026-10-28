package com.afrotransact.identity.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.UserSession;

/** Active sign-ins (OAuth authorizations with refresh tokens) per user. */
public interface SessionStore {

    /** @param currentAccessToken the caller's bearer token, used to flag the current session */
    List<UserSession> list(UUID userId, Optional<String> currentAccessToken);

    boolean revoke(UUID userId, String sessionId);

    /** Revokes every session except the one holding {@code keepAccessToken}; returns how many were revoked. */
    int revokeAll(UUID userId, Optional<String> keepAccessToken);

    /**
     * Ends every identity browser session (single sign-on cookie) the user is signed in with, so the hosted pages ask
     * for credentials again everywhere; returns how many were ended.
     */
    int endBrowserSessions(UUID userId);
}
