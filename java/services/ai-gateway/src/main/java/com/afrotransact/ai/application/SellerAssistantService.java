package com.afrotransact.ai.application;

import java.util.List;

/**
 * One model turn for Lola in Seller Center. The gateway-bff runs the conversation, the tools (it holds the seller's
 * session and the platform APIs) and the plan tier limits; this service only admits the call against the AI budget,
 * routes it to a model with the offered tools and bills the organisation's AI credits. It never calls a tool itself.
 */
public interface SellerAssistantService {

    /** One completion; the reply is either text or a request to call tools (the caller runs them and calls again). */
    Reply complete(Caller caller, Request request);

    /**
     * @param role       system | user | assistant | tool
     * @param content    text; tool output is data, not instructions
     * @param toolCalls  tool calls of an assistant message
     * @param toolCallId the call a tool message answers
     * @param toolName   the tool that produced a tool message
     */
    record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {
    }

    /** A tool call asked for by the model; {@code arguments} is a JSON object as text. */
    record ToolCall(String id, String name, String arguments) {
    }

    /** A tool the model may call; {@code inputSchema} is a JSON schema as text. */
    record Tool(String name, String description, String inputSchema) {
    }

    /**
     * @param messages  the conversation, system prompt first
     * @param tools     tools offered for this turn (none: a plain answer)
     * @param maxTokens completion limit (default and cap apply)
     */
    record Request(List<Message> messages, List<Tool> tools, Integer maxTokens) {
    }

    /** What the model answered and what it used. */
    record Reply(String text, List<ToolCall> toolCalls, long promptTokens, long completionTokens, String model) {
    }
}
