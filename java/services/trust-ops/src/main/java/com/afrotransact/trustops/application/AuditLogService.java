package com.afrotransact.trustops.application;

import java.util.function.Consumer;

import com.afrotransact.trustops.domain.AuditEntry;
import com.afrotransact.trustops.domain.ChainVerification;
import com.afrotransact.trustops.port.AuditQuery;
import com.afrotransact.trustops.port.Page;

/** S119 immutable audit log: ingest privileged actions from other services, query, export and verify the chain. */
public interface AuditLogService {

    /**
     * Appends an audit-worthy consumed event (configured event types; inbox-deduplicated by event id).
     *
     * @return true when an entry was appended
     */
    boolean ingest(IncomingEvent event);

    /** Newest first; filter by actor, entity, action, organisation and date (audit:read). */
    Page<AuditEntry> search(AuditQuery query, int page, int size);

    AuditEntry get(long seq);

    /** Writes matching entries oldest first to {@code sink} (capped); returns how many were written (audit:read). */
    int export(AuditQuery query, Consumer<AuditEntry> sink);

    /**
     * Re-computes the chain between {@code fromSeq} (default 1) and {@code toSeq} (default head): detects edited,
     * re-hashed, re-ordered and missing entries (audit:read).
     */
    ChainVerification verify(Long fromSeq, Long toSeq);
}
