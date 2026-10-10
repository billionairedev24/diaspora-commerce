package com.afrotransact.mcp.port;

import java.time.Duration;
import java.util.List;

/**
 * Durable, ordered holding area for audit events the broker did not accept. Shared by every replica, so a pod that
 * spooled events can disappear (scale-down, eviction) and another pod replays them.
 */
public interface AuditSpool {

    /** Appends at the tail. Throws when the spool itself is unavailable. */
    void append(AuditTrail.ToolInvoked event);

    /** Oldest entries first, at most {@code limit}. */
    List<Spooled> oldest(int limit);

    /** Removes one replayed entry. */
    void remove(String spoolId);

    /** Number of entries waiting. */
    long size();

    /** Takes the replay lease so only one replica drains (keeps the replay order); false when another holds it. */
    boolean tryLease(String owner, Duration ttl);

    /** Gives the lease back if {@code owner} still holds it. */
    void releaseLease(String owner);

    /**
     * One spooled event.
     *
     * @param spoolId position in the spool (monotonic)
     * @param event   the event
     */
    record Spooled(String spoolId, AuditTrail.ToolInvoked event) {
    }
}
