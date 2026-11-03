package com.afrotransact.deliveryruns.port;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.WindowDecision;

/** Append-only log of what was decided about a pre-order window (who, when, why). */
public interface PreorderDecisionLog {

    void append(WindowDecision decision);

    /** The window's entries, oldest first. */
    List<WindowDecision> findByWindow(UUID windowId);
}
