package com.afrotransact.mcp.domain;

/** A tool was called with arguments the platform rules reject (returned to the agent as a tool error). */
public class McpRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public McpRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
