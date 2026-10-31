package com.afrotransact.platform.events.retention;

/**
 * Outcome of one purge run.
 *
 * @param inboxDeleted  inbox rows deleted
 * @param outboxDeleted published outbox rows deleted
 * @param complete      false when {@code max-batches} stopped a table with rows still due (the next run continues)
 */
public record PurgeResult(long inboxDeleted, long outboxDeleted, boolean complete) {
}
