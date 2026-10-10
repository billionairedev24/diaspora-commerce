package com.afrotransact.ai.domain.lola;

import java.util.List;
import java.util.Map;

/**
 * What Lola streams to the chat, in order. SSE event names are {@link #type()}.
 * <ul>
 *   <li>{@code token} – answer text as it is written (already grounded and moderated)</li>
 *   <li>{@code tool_call} – what Lola is doing ("Searching for jollof seasoning")</li>
 *   <li>{@code tool_result} – structured results: product cards, offers, delivery promise, order status, services</li>
 *   <li>{@code action_proposal} – an action that needs the shopper's tap</li>
 *   <li>{@code handoff} – the chat was handed to human support</li>
 *   <li>{@code done} – final grounded message, cards, proposals and suggestion chips</li>
 *   <li>{@code error} – the turn failed</li>
 * </ul>
 */
public sealed interface LolaEvent {

    String type();

    /** Answer text fragment. */
    record Token(String text) implements LolaEvent {
        @Override
        public String type() {
            return "token";
        }
    }

    /** A tool Lola is calling, with a human-readable label. */
    record ToolCall(String id, String tool, String label, Map<String, Object> arguments) implements LolaEvent {
        @Override
        public String type() {
            return "tool_call";
        }
    }

    /**
     * Structured tool output for the UI.
     *
     * @param kind {@code products}, {@code offers}, {@code delivery}, {@code order}, {@code services},
     *             {@code proposal}, {@code handoff} or {@code error}
     */
    record ToolResult(String id, String tool, String kind, Object data) implements LolaEvent {
        @Override
        public String type() {
            return "tool_result";
        }
    }

    /** An action that needs confirmation. */
    record Proposal(ActionProposal proposal) implements LolaEvent {
        @Override
        public String type() {
            return "action_proposal";
        }
    }

    /** Hand-off to the support console. */
    record Handoff(String handoffId, String reason, String summary) implements LolaEvent {
        @Override
        public String type() {
            return "handoff";
        }
    }

    /** End of the turn. */
    record Done(String sessionId, String message, List<ProductCard> products, List<ActionProposal> proposals,
                List<String> suggestions, List<Map<String, String>> dropped, List<String> models,
                long promptTokens, long completionTokens) implements LolaEvent {

        public Done {
            products = List.copyOf(products);
            proposals = List.copyOf(proposals);
            suggestions = List.copyOf(suggestions);
            dropped = List.copyOf(dropped);
            models = List.copyOf(models);
        }

        @Override
        public String type() {
            return "done";
        }
    }

    /** The turn failed; {@code code} is a stable problem code. */
    record Failure(String code, String detail) implements LolaEvent {
        @Override
        public String type() {
            return "error";
        }
    }
}
