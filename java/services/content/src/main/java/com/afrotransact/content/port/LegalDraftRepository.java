package com.afrotransact.content.port;

import java.util.Optional;

import com.afrotransact.content.domain.legal.LegalDocumentType;
import com.afrotransact.content.domain.legal.LegalDraft;

/** The working draft of each legal document (at most one per document). */
public interface LegalDraftRepository {

    Optional<LegalDraft> find(LegalDocumentType type);

    /** Stores a draft that does not exist yet; false when someone started one in the meantime. */
    boolean insert(LegalDraft draft);

    /** Replaces the draft when its stored row version equals {@code expectedRowVersion}; false when it changed. */
    boolean update(LegalDraft draft, long expectedRowVersion);

    void delete(LegalDocumentType type);
}
