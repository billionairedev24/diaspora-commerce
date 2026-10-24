package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.identity.domain.AdminSession;

/** Every active sign-in (OAuth authorization) on the platform, for the admin console. */
public interface SessionAdminStore {

    /**
     * Active sign-ins, newest first.
     *
     * @param userId   only this user's (null = anyone)
     * @param clientId only this client's (null = any)
     * @param text     matches the user's name or email, or the client's id or name (null = anything)
     */
    Page search(String userId, String clientId, String text, Instant now, int limit, int offset);

    Optional<AdminSession> find(String id);

    /** Deletes the authorization, so its refresh token stops working at once. Returns how many were removed. */
    int revoke(String id);

    int revokeAllOfUser(String userId);

    int revokeAllOfClient(String clientId);

    record Page(List<AdminSession> items, long total) {
    }
}
