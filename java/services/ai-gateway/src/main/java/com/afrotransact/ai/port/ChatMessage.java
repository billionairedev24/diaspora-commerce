package com.afrotransact.ai.port;

import java.util.List;

/**
 * Provider-neutral chat message.
 *
 * @param role       who wrote it
 * @param content    text (already redacted and, for tool output, filtered and quoted)
 * @param toolCalls  tool calls the assistant asked for (assistant messages only)
 * @param toolCallId the call this message answers (tool messages only)
 * @param toolName   the tool that produced this message (tool messages only)
 * @param images     images attached to a user message (vision models)
 */
public record ChatMessage(Role role, String content, List<ToolCallRequest> toolCalls, String toolCallId,
                          String toolName, List<ImagePart> images) {

    public ChatMessage {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        images = images == null ? List.of() : List.copyOf(images);
    }

    public ChatMessage(Role role, String content, List<ToolCallRequest> toolCalls, String toolCallId,
                       String toolName) {
        this(role, content, toolCalls, toolCallId, toolName, List.of());
    }

    /** A user message with images, for vision models. */
    public static ChatMessage user(String content, List<ImagePart> images) {
        return new ChatMessage(Role.USER, content, List.of(), null, null, images);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, List.of(), null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, List.of(), null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCallRequest> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, toolCalls, null, null);
    }

    public static ChatMessage tool(String toolCallId, String toolName, String content) {
        return new ChatMessage(Role.TOOL, content, List.of(), toolCallId, toolName);
    }

    /** Message author. */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }
}
