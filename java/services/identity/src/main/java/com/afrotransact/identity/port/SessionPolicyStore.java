package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.Map;

import com.afrotransact.identity.domain.SessionAudience;
import com.afrotransact.identity.domain.SessionPolicyOverrides;

/** The stored sign-out policy overrides: only audiences an admin changed have a row. */
public interface SessionPolicyStore {

    Map<SessionAudience, SessionPolicyOverrides> loadAll();

    /** Stores the overrides of one audience; empty overrides remove its row ("use the default"). */
    void save(SessionAudience audience, SessionPolicyOverrides overrides, Instant at, String by);
}
