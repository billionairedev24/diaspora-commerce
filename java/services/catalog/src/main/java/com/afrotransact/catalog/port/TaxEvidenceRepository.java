package com.afrotransact.catalog.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.tax.TaxEvidence;

/** Storage of the proof files of exemption facts (bytes in the table in v1, object storage behind this port later). */
public interface TaxEvidenceRepository {

    /** Stores the file as the next version of the variant's fact and returns it. */
    TaxEvidence store(TaxEvidence meta, byte[] content);

    /** Every version, newest first. */
    List<TaxEvidence> findByVariant(String variantAtin);

    /** The newest version of each fact of the variant. */
    List<TaxEvidence> latestByVariant(String variantAtin);

    Optional<TaxEvidence> findById(UUID id);

    Optional<byte[]> content(UUID id);

    int nextVersion(String variantAtin, String factId);
}
