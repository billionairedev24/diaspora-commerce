package com.afrotransact.ai.port;

import java.util.List;

import com.afrotransact.ai.domain.TaskType;

/**
 * One model call.
 *
 * @param task        selects the route (models, timeout, retries)
 * @param messages    conversation, system prompt first
 * @param tools       tools the model may call (empty for plain completions)
 * @param degrade     use only the cheapest model of the route (budget soft limit reached)
 * @param maxTokens   completion limit (null = route default)
 * @param temperature sampling temperature (null = provider default)
 */
public record ModelRequest(TaskType task, List<ChatMessage> messages, List<ToolSpec> tools, boolean degrade,
                           Integer maxTokens, Double temperature) {

    public ModelRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static ModelRequest of(TaskType task, List<ChatMessage> messages) {
        return new ModelRequest(task, messages, List.of(), false, null, null);
    }

    public ModelRequest withDegrade(boolean value) {
        return new ModelRequest(task, messages, tools, value, maxTokens, temperature);
    }
}
