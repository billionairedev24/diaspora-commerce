package com.afrotransact.ai.adapter.out.ai;

import com.afrotransact.ai.port.ToolSpec;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Exposes a Lola tool to the model through Spring AI tool calling. Execution is user-controlled: the model's tool calls
 * come back in the {@code ChatResponse} and the Lola agent runs them itself (so it can stream {@code tool_call} /
 * {@code tool_result} events, relay the shopper's token, ground results and turn write tools into proposals).
 * {@link #call(String)} therefore only answers if some generic Spring AI client tries to execute the tool directly.
 */
final class DefinitionOnlyToolCallback implements ToolCallback {

    private final ToolDefinition definition;

    DefinitionOnlyToolCallback(ToolSpec spec) {
        this.definition = ToolDefinition.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(spec.inputSchema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return "{\"error\":\"" + definition.name() + " is executed by the Lola agent loop, not by the model client\"}";
    }
}
