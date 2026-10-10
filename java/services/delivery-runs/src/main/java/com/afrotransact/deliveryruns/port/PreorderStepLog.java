package com.afrotransact.deliveryruns.port;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Scheduler steps done per window (comms due, opened, closed, delivered): each happens once. */
public interface PreorderStepLog {

    /** Steps already done for the window. */
    Set<String> done(UUID windowId);

    /** Records the step in the caller's transaction; false when it was already done. */
    boolean markDone(UUID windowId, String step, Instant dueAt, Instant now);
}
