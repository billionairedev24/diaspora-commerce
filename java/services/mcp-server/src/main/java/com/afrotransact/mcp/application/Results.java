package com.afrotransact.mcp.application;

import java.util.Map;

import com.afrotransact.mcp.port.Downstream;

/** Turns downstream outcomes into tool results with agent-friendly messages. */
final class Results {

    private Results() {
    }

    static ToolResult from(Downstream d, String what) {
        return switch (d.status()) {
            case OK -> ToolResult.ok(d.body());
            case NOT_FOUND -> ToolResult.of("NOT_FOUND", "No " + what + " found");
            case FORBIDDEN -> ToolResult.of("FORBIDDEN", "The platform refused access to this " + what);
            case REJECTED -> new ToolResult("REJECTED", d.body(), "The platform rejected the request for " + what);
            case UNAVAILABLE -> ToolResult.of("UNAVAILABLE", capitalise(what)
                    + " is not available yet; do not guess — tell the user to try later or contact support");
        };
    }

    /** A proposal: something the agent must show to the user, who performs it with a tap. */
    static ToolResult proposal(String type, String label, String method, String path, Map<String, Object> body) {
        return new ToolResult("PROPOSED", Map.of("type", type, "label", label, "requiresConfirmation", true,
                "apiCall", Map.of("method", method, "path", path, "body", body)),
                "Nothing has been done yet. Show this to the user; only their confirmation performs it.");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object body) {
        return body instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static String text(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
