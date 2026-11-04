package com.afrotransact.content.application;

import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.legal.LegalDocumentType;
import com.afrotransact.content.domain.legal.LegalDraft;
import com.afrotransact.content.domain.legal.LegalVersion;

/**
 * How staff change a legal document without a developer: edit a working draft (nothing public changes), then publish
 * it as a new version. Publishing needs a second person: whoever saved the draft last cannot approve it.
 */
public interface LegalAuthoringService {

    /** Every document's current version and its working draft, if any (content:legal.view or edit). */
    List<Overview> overview();

    /**
     * One document in the staff overview.
     *
     * @param current the version shoppers read now (with who published it)
     * @param draft   the working draft, if any
     */
    record Overview(LegalVersion current, Optional<LegalDraft> draft) {
    }

    /** The working draft, if any (content:legal.view or edit). */
    Optional<LegalDraft> draft(LegalDocumentType type);

    /**
     * Saves the draft; the first save starts it from the current version. {@code expectedRowVersion} is null for the
     * first save and the version the editor loaded afterwards.
     */
    LegalDraft save(LegalDocumentType type, LegalDraft.Edit edit, Long expectedRowVersion);

    /** Starts a draft from an older version's text (rollback: it is published as a new, higher version). */
    LegalDraft restore(LegalDocumentType type, String version);

    /** Throws the draft away. */
    void discard(LegalDocumentType type);

    /**
     * Publishes the draft as a new version and archives the previous one. {@code expectedRowVersion} is the draft the
     * approver reviewed.
     */
    LegalVersion publish(LegalDocumentType type, String reason, long expectedRowVersion);

    /** Every published version, newest first, with who published it and when (content:legal.view or edit). */
    List<LegalVersion> history(LegalDocumentType type);

    /** One published version with its Markdown as written, placeholders unfilled (content:legal.view or edit). */
    LegalVersion source(LegalDocumentType type, String version);
}
