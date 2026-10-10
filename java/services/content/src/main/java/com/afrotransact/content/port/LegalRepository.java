package com.afrotransact.content.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.legal.LegalDocumentType;
import com.afrotransact.content.domain.legal.LegalVersion;
import com.afrotransact.content.domain.legal.SemanticVersion;

/** Legal document versions. */
public interface LegalRepository {

    Optional<LegalVersion> current(LegalDocumentType type);

    List<LegalVersion> allCurrent();

    /** All versions of a document, newest first. */
    List<LegalVersion> versions(LegalDocumentType type);

    Optional<LegalVersion> version(LegalDocumentType type, SemanticVersion version);

    void insert(LegalVersion version);

    /** Updates status, banner, effective date, bodies and archive time. */
    void update(LegalVersion version);

    /** Changes whenever any version changes (used in ETags). */
    long stamp();
}
