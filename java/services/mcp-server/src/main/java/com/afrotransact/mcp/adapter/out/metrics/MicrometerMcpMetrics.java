package com.afrotransact.mcp.adapter.out.metrics;

import java.time.Duration;

import com.afrotransact.mcp.port.McpMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** {@code mcp_tool_calls_seconds{tool,toolset,outcome}} (count, sum and histogram). */
@Component
class MicrometerMcpMetrics implements McpMetrics {

    private final MeterRegistry registry;

    MicrometerMcpMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void toolCall(String tool, String toolset, String outcome, Duration duration) {
        Timer.builder("mcp.tool.calls").description("MCP tool calls by outcome")
                .tag("tool", tool).tag("toolset", toolset.isEmpty() ? "none" : toolset).tag("outcome", outcome)
                .register(registry).record(duration);
    }
}
