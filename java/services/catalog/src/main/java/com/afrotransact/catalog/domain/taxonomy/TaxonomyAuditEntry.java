package com.afrotransact.catalog.domain.taxonomy;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One staff change to the taxonomy.
 *
 * @param action       what was done, for example {@code NODE_RENAMED}
 * @param subjectId    node id, product-type code or request id
 * @param subjectLabel the path, product-type label or request reference as it read at the time
 * @param details      what changed (before and after values)
 */
public record TaxonomyAuditEntry(UUID id, Instant occurredAt, String actorId, String action, AuditSubject subjectKind,
                                 String subjectId, String subjectLabel, String reason, Map<String, Object> details) {

    public TaxonomyAuditEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(subjectKind, "subjectKind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(reason, "reason");
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
