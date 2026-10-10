package com.afrotransact.ai.adapter.out.ai;

import java.util.ArrayList;
import java.util.List;

import com.afrotransact.ai.domain.TokenUsage;
import com.afrotransact.ai.port.ChatMessage;
import com.afrotransact.ai.port.ToolCallRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** Maps provider-neutral messages to Spring AI messages and back. */
final class SpringAiMessages {

    private SpringAiMessages() {
    }

    static List<Message> toSpringAi(List<ChatMessage> messages) {
        List<Message> out = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            out.add(switch (m.role()) {
                case SYSTEM -> new SystemMessage(m.content());
                case USER -> new UserMessage(m.content());
                case ASSISTANT -> AssistantMessage.builder()
                        .content(m.content())
                        .toolCalls(m.toolCalls().stream()
                                .map(c -> new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.arguments()))
                                .toList())
                        .build();
                case TOOL -> ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(m.toolCallId(), m.toolName(),
                                m.content())))
                        .build();
            });
        }
        return out;
    }

    /** Text of all generations of one (possibly partial) response. */
    static String text(ChatResponse response) {
        StringBuilder b = new StringBuilder();
        for (Generation g : response.getResults()) {
            if (g != null && g.getOutput() != null && g.getOutput().getText() != null) {
                b.append(g.getOutput().getText());
            }
        }
        return b.toString();
    }

    static List<ToolCallRequest> toolCalls(ChatResponse response) {
        List<ToolCallRequest> calls = new ArrayList<>();
        for (Generation g : response.getResults()) {
            if (g != null && g.getOutput() != null && g.getOutput().hasToolCalls()) {
                g.getOutput().getToolCalls().forEach(c -> calls.add(new ToolCallRequest(c.id(), c.name(),
                        c.arguments())));
            }
        }
        return calls;
    }

    static TokenUsage usage(ChatResponse response) {
        if (response.getMetadata() == null) {
            return TokenUsage.NONE;
        }
        return usage(response.getMetadata().getUsage());
    }

    static TokenUsage usage(Usage usage) {
        if (usage == null) {
            return TokenUsage.NONE;
        }
        long prompt = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long completion = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        return new TokenUsage(Math.max(prompt, 0), Math.max(completion, 0));
    }
}
