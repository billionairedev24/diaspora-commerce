package com.afrotransact.ai.application;

import com.afrotransact.ai.domain.guardrail.GroundingFacts;

/**
 * What a Lola tool may use: the caller (token relayed downstream), the session, the deliver-to ZIP and the turn's
 * grounding facts, which tools extend with every product and amount they return.
 */
public record ToolContext(Caller caller, String sessionId, String zip, GroundingFacts facts) {
}
