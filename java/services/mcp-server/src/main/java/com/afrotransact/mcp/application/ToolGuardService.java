package com.afrotransact.mcp.application;

import java.time.Duration;
import java.util.List;

import com.afrotransact.mcp.domain.CallerProfile;
import com.afrotransact.mcp.domain.ToolDescriptor;

/**
 * Cross-cutting guard of the MCP endpoint: which tools a caller sees, whether a call is admitted (scope, permission,
 * per-client token bucket) and the audit event of every call.
 */
public interface ToolGuardService {

    List<ToolDescriptor> visibleTools(CallerProfile caller);

    Admission admit(CallerProfile caller, String toolName);

    /** Throws {@code ForbiddenActionException} unless the caller may use the tool (defence in depth for use cases). */
    void require(CallerProfile caller, String toolName);

    void audit(CallerProfile caller, String toolName, String canonicalArguments, String outcome, Duration duration);

    /**
     * Admission of one call.
     *
     * @param verdict    ALLOWED, UNKNOWN_TOOL, FORBIDDEN or RATE_LIMITED
     * @param message    reason when not allowed
     * @param retryAfter for RATE_LIMITED
     */
    record Admission(Verdict verdict, String message, Duration retryAfter) {

        public boolean allowed() {
            return verdict == Verdict.ALLOWED;
        }
    }

    /** Admission verdicts. */
    enum Verdict {
        ALLOWED,
        UNKNOWN_TOOL,
        FORBIDDEN,
        RATE_LIMITED
    }
}
