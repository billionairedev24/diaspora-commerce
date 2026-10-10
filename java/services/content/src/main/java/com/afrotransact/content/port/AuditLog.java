package com.afrotransact.content.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Append-only audit trail of every back-office change. */
public interface AuditLog {

    void append(Entry entry);

    /** Entries for an entity (or every entity of the type when {@code entityId} is null), newest first. */
    List<Entry> find(String entityType, String entityId, int limit);

    /**
     * One audited change.
     *
     * @param at         when
     * @param actor      who
     * @param action     what ({@code placement.approved}, {@code site-config.updated}…)
     * @param entityType placement, market, site-config, legal, flag, job, cookie
     * @param entityId   id
     * @param detail     JSON-serialisable detail (before / after, changed sections…)
     */
    record Entry(Instant at, String actor, String action, String entityType, String entityId,
                 Map<String, Object> detail) {
    }
}
