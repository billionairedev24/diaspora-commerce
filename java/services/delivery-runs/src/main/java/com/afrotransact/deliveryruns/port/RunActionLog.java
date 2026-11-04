package com.afrotransact.deliveryruns.port;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.RunAction;

/** Append-only log of what was decided about a run (who, when, why). */
public interface RunActionLog {

    void append(RunAction action);

    /** The run's entries, oldest first. */
    List<RunAction> findByRun(UUID runId);
}
