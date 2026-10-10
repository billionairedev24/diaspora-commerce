package com.afrotransact.trustops.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.afrotransact.trustops.domain.AuditEntry;

/** Append-only store of the hash-chained audit log (UPDATE / DELETE are rejected by the database). */
public interface AuditLogRepository {

    /** Serialises appends for the rest of the current transaction (one chain, one writer at a time). */
    void lockChain();

    Optional<AuditEntry> last();

    void insert(AuditEntry entry);

    boolean existsBySourceEvent(UUID sourceEventId);

    Optional<AuditEntry> findBySeq(long seq);

    /** Entries with {@code fromSeq <= seq <= toSeq} in seq order, at most {@code limit}. */
    List<AuditEntry> range(long fromSeq, long toSeq, int limit);

    /** Newest first. */
    Page<AuditEntry> search(AuditQuery query, int page, int size);

    /** Streams matching entries oldest first (for CSV export); returns how many were written. */
    int forEach(AuditQuery query, int max, Consumer<AuditEntry> sink);
}
