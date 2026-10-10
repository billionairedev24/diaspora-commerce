package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

/**
 * Time-driven run steps for the scheduled job: auto-open, the 24-hour cutoff reminder and the cutoff decision
 * (confirm / extend / cancel). {@code due…} lists candidates without locking; each {@code process…} call is its own
 * transaction that locks the run with {@code FOR UPDATE SKIP LOCKED} and re-checks it, so replicas never process a
 * run twice and one failing run does not block the others.
 */
public interface RunSchedulerService {

    List<UUID> dueToOpen(int limit);

    /** @return true when this call opened the run */
    boolean processOpen(UUID runId);

    List<UUID> dueForReminder(int limit);

    /** @return true when this call published the reminder */
    boolean processReminder(UUID runId);

    List<UUID> dueForCutoff(int limit);

    /** @return true when this call decided the run */
    boolean processCutoff(UUID runId);
}
