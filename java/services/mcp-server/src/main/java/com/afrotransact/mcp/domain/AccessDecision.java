package com.afrotransact.mcp.domain;

/**
 * Whether a caller may see and call a tool.
 *
 * @param allowed true when allowed
 * @param reason  why not (for the audit log and the error message), empty when allowed
 */
public record AccessDecision(boolean allowed, String reason) {

    public static final AccessDecision ALLOW = new AccessDecision(true, "");

    public static AccessDecision deny(String reason) {
        return new AccessDecision(false, reason);
    }
}
