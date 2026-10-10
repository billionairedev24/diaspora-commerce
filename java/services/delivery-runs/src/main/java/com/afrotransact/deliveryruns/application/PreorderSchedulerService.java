package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

/**
 * Time-driven pre-order steps for the scheduled job: {@code preorder.window.opened} and the ANNOUNCE comms at
 * opening, REMIND_48H at close − 48 h, LAST_CALL_6H at close − 6 h, {@code preorder.window.closed} with the CLOSED
 * comms and the hand-over of paid orders to a run at closing, {@code preorder.window.delivered} the day after
 * delivery, and the 90-day PII retention. Each step happens once per window ({@code preorder_comms_log}); each
 * {@code process} call is its own transaction under {@code FOR UPDATE SKIP LOCKED}, safe on every replica.
 */
public interface PreorderSchedulerService {

    /** Published windows that may have a step due (no lock). */
    List<UUID> candidates(int limit);

    /** @return steps done by this call */
    int process(UUID windowId);

    /** Deletes projected orders of windows delivered more than the retention period ago; returns rows deleted. */
    int purgeExpired();
}
