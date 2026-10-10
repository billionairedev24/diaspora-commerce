package com.afrotransact.ai.application;

import java.util.List;
import java.util.Map;

import com.afrotransact.ai.port.ToolCallRequest;
import com.afrotransact.ai.port.ToolSpec;

/**
 * Lola's tools. Read tools call the platform's public APIs with the shopper's relayed token; write tools never execute:
 * they return an {@code ActionProposal} the shopper confirms with a tap.
 */
public interface LolaToolkit {

    /** Tool definitions offered to the model (names, descriptions, JSON schemas). */
    List<ToolSpec> specs();

    /** Human-readable label and parsed arguments, emitted as {@code tool_call} before the tool runs. */
    Invocation describe(ToolCallRequest call);

    ToolOutcome execute(ToolCallRequest call, ToolContext context);

    /**
     * What Lola is doing.
     *
     * @param label     for example "Searching for jollof seasoning"
     * @param arguments parsed arguments
     */
    record Invocation(String label, Map<String, Object> arguments) {
    }
}
