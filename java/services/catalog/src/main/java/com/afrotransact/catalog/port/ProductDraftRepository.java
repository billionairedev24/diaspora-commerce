package com.afrotransact.catalog.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.SavedDraft;

/** Saved product-form drafts (optimistic locking on {@code version}). */
public interface ProductDraftRepository {

    SavedDraft insert(SavedDraft draft);

    /** Saves the draft if {@code draft.version()} still matches; returns it with version + 1. */
    SavedDraft update(SavedDraft draft);

    Optional<SavedDraft> findById(UUID id);

    /** The organisation's drafts, most recently saved first. */
    List<SavedDraft> findByOwnerOrg(String orgId, int limit);

    void delete(UUID id);
}
