package com.afrotransact.catalog.port;

import java.util.List;

import com.afrotransact.catalog.domain.taxonomy.TaxonomyAuditEntry;

/** Append-only log of staff changes to the taxonomy. */
public interface TaxonomyAuditRepository {

    void append(TaxonomyAuditEntry entry);

    /** Newest first; {@code subjectId} null means every subject. */
    List<TaxonomyAuditEntry> find(String subjectId, int limit);
}
