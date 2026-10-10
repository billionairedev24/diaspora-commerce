package com.afrotransact.ai.port;

import java.util.List;

import com.afrotransact.ai.domain.TokenUsage;

/**
 * Answer of the model that finally served the call.
 *
 * @param text          answer text (may be empty when the model only called tools)
 * @param toolCalls     tool calls requested by the model
 * @param usage         provider-reported usage
 * @param model         model id that answered
 * @param attempts      total attempts across retries and fallbacks
 * @param failedModels  models that failed before this one answered (fallback order)
 */
public record ModelReply(String text, List<ToolCallRequest> toolCalls, TokenUsage usage, String model, int attempts,
                         List<String> failedModels) {

    public ModelReply {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? TokenUsage.NONE : usage;
        failedModels = failedModels == null ? List.of() : List.copyOf(failedModels);
    }

    public boolean fellBack() {
        return !failedModels.isEmpty();
    }
}
