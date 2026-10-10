package com.afrotransact.mcp.domain;

import java.util.Set;

/**
 * Who is calling an MCP tool.
 *
 * @param subject       user id (or client id for client-credentials tokens)
 * @param clientId      OAuth client the agent authenticated as (rate limits are per client and subject)
 * @param principalType {@code CUSTOMER}, {@code SELLER_MEMBER}, {@code STAFF}, {@code AI_AGENT} …
 * @param orgId         organisation the caller acts for, may be null
 * @param permissions   permissions and scopes from the access token
 */
public record CallerProfile(String subject, String clientId, String principalType, String orgId,
                            Set<String> permissions) {

    public CallerProfile {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        clientId = clientId == null || clientId.isBlank() ? "unknown-client" : clientId;
        principalType = principalType == null ? "CUSTOMER" : principalType;
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean can(String permission) {
        return permissions.contains(permission) || permissions.contains("*");
    }

    public boolean staff() {
        return "STAFF".equals(principalType) || permissions.contains("*");
    }

    /** Rate-limit key: one bucket per client and subject. */
    public String bucketKey() {
        return clientId + "|" + subject;
    }
}
