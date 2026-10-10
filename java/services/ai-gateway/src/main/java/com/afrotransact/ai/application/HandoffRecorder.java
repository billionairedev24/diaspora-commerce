package com.afrotransact.ai.application;

import java.util.List;

import com.afrotransact.ai.port.ConversationStore;
import com.afrotransact.ai.port.HandoffRepository;

/**
 * Hands a Lola conversation to human support: stores the hand-off and publishes {@code ai.handoff.requested} through
 * the outbox in one transaction. Summaries are redacted.
 */
public interface HandoffRecorder {

    /** Records a hand-off with a summary written by the model (handoff_to_support tool). */
    HandoffRepository.Handoff record(Caller caller, String sessionId, String reason, String summary);

    /**
     * Records a hand-off decided by rules: the summary is written by the fast INTENT model from the redacted
     * conversation, or built from the last turns when the model is unavailable.
     */
    HandoffRepository.Handoff recordWithSummary(Caller caller, String sessionId, String reason,
                                                List<ConversationStore.Turn> redactedTurns);
}
