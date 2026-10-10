package com.afrotransact.ai.port;

/**
 * A tool call the model asked for.
 *
 * @param id        provider call id (echoed in the tool response)
 * @param name      tool name
 * @param arguments JSON arguments as produced by the model
 */
public record ToolCallRequest(String id, String name, String arguments) {

    public ToolCallRequest {
        arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
    }
}
