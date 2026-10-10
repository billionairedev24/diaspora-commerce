package com.afrotransact.ai.application;

import com.afrotransact.ai.domain.lola.LolaEvent;

/** Receives Lola's events in order (an SSE stream, or a collector for the non-streaming endpoint). */
@FunctionalInterface
public interface LolaEventSink {

    void emit(LolaEvent event);
}
