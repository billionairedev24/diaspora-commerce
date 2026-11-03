package com.afrotransact.identity.application;

import java.util.List;
import java.util.Map;

import com.afrotransact.identity.domain.SessionAudience;
import com.afrotransact.identity.domain.SessionPolicy;
import com.afrotransact.identity.domain.SessionPolicyOverrides;

/** When each audience is signed out for inactivity and for session length, editable by staff. */
public interface SessionPolicyService {

    /**
     * The policy in force for every audience. A SERVICE principal with {@code sessions:policy.read} (the gateway-bff)
     * or staff with {@code identity:admin}.
     */
    Map<SessionAudience, SessionPolicy> effective();

    /** {@code identity:admin}: per audience what is in force, what is stored and the built-in default. */
    List<SessionPolicyEntry> view();

    /**
     * {@code identity:admin}: replaces one audience's stored overrides (a null field goes back to its default),
     * validated and written to the access change log with the reason and the before/after values.
     */
    SessionPolicyEntry update(SessionAudience audience, SessionPolicyOverrides overrides, String reason);

    /** One row of the admin screen. */
    record SessionPolicyEntry(SessionAudience audience, SessionPolicy effective, SessionPolicyOverrides overrides,
                              SessionPolicy defaults) {
    }
}
