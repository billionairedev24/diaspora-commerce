package com.afrotransact.mcp.application;

/**
 * What every tool returns (serialised as the JSON text content of the MCP result).
 *
 * @param status  OK, PROPOSED (needs the user's confirmation), NOT_FOUND, FORBIDDEN, REJECTED or UNAVAILABLE
 * @param data    result data (JSON tree), may be null
 * @param message human-readable note for the agent
 */
public record ToolResult(String status, Object data, String message) {

    public static ToolResult ok(Object data) {
        return new ToolResult("OK", data, "");
    }

    public static ToolResult of(String status, String message) {
        return new ToolResult(status, null, message);
    }
}
