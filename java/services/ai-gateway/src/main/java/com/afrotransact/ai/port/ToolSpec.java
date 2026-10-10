package com.afrotransact.ai.port;

/**
 * A tool offered to the model (mapped to a Spring AI {@code ToolCallback} definition by the model adapter).
 *
 * @param name        snake_case tool name
 * @param description what the tool does and when to use it
 * @param inputSchema JSON schema of the arguments
 */
public record ToolSpec(String name, String description, String inputSchema) {
}
