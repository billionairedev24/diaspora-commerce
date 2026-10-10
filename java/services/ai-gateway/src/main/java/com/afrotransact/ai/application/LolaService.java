package com.afrotransact.ai.application;

import java.util.List;

import com.afrotransact.ai.domain.lola.LolaEvent;
import com.afrotransact.ai.port.ConversationStore;

/**
 * Lola, the shopping agent. One call is one shopper turn: topic limits, PII redaction, conversation memory, routed
 * model rounds with tool calls (shown as they happen), grounding of every product and price, action proposals for
 * anything that needs the shopper's tap, and hand-off to human support.
 */
public interface LolaService {

    /** Pre-checks (budget) so a streaming endpoint can still answer with a problem before the stream opens. */
    void admit(Caller caller);

    /** Runs one turn, emitting events in order, and returns the final {@code done} event. */
    LolaEvent.Done chat(Caller caller, LolaRequest request, LolaEventSink sink);

    /** Stored (redacted) turns of one of the caller's sessions, oldest first. */
    List<ConversationStore.Turn> history(Caller caller, String sessionId);
}
