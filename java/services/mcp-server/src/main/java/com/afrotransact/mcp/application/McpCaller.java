package com.afrotransact.mcp.application;

import com.afrotransact.mcp.domain.CallerProfile;

/**
 * The caller of a tool and the access token relayed to platform APIs.
 *
 * @param profile     who is calling
 * @param bearerToken incoming access token (null in the local profile)
 */
public record McpCaller(CallerProfile profile, String bearerToken) {

    @Override
    public String toString() {
        return "McpCaller[" + profile.subject() + "@" + profile.clientId() + ", token=" + (bearerToken == null ? "none"
                : "***") + "]";
    }
}
