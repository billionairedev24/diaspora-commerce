package com.afrotransact.mcp.domain;

/**
 * What a shared (Valkey) rate limiter does when its store cannot be reached.
 *
 * <ul>
 *   <li>{@link #OPEN} (default): keep serving, degraded to a per-pod in-memory bucket with the same settings, so a
 *       Valkey outage never takes the MCP server down and each pod still caps a runaway client.</li>
 *   <li>{@link #CLOSED}: refuse every call as rate limited until the store is back (strict environments).</li>
 * </ul>
 */
public enum RateLimitFailureMode {
    OPEN,
    CLOSED
}
