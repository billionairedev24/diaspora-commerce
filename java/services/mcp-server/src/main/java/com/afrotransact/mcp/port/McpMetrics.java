package com.afrotransact.mcp.port;

import java.time.Duration;

/** Prometheus metrics of MCP tool calls (tags: tool, toolset, outcome). */
public interface McpMetrics {

    void toolCall(String tool, String toolset, String outcome, Duration duration);
}
