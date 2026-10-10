package com.afrotransact.ai.application;

import com.afrotransact.ai.domain.lola.ActionProposal;

/**
 * Result of one Lola tool call.
 *
 * @param success      false when the tool could not do its job (the model is told why)
 * @param kind         UI kind of {@code data}: products, offers, delivery, order, services, recipe, proposal …
 * @param data         structured result for the chat UI ({@code tool_result} event)
 * @param modelContent what the model reads: JSON, seller text sanitised, wrapped in an {@code <untrusted>} envelope
 * @param proposal     an action that needs the shopper's tap, or null
 * @param handoff      a hand-off to human support, or null
 */
public record ToolOutcome(boolean success, String kind, Object data, String modelContent, ActionProposal proposal,
                          HandoffRequest handoff) {

    /**
     * Hand-off requested by the model.
     *
     * @param reason  short reason
     * @param summary summary for the support agent
     */
    public record HandoffRequest(String reason, String summary) {
    }
}
