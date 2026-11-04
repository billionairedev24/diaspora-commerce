package com.afrotransact.catalog.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.DraftListItem;
import com.afrotransact.catalog.domain.DraftSortField;
import com.afrotransact.catalog.domain.SavedDraft;

/** Saved product-form drafts (optimistic locking on {@code version}). */
public interface ProductDraftRepository {

    SavedDraft insert(SavedDraft draft);

    /** Saves the draft if {@code draft.version()} still matches; returns it with version + 1. */
    SavedDraft update(SavedDraft draft);

    Optional<SavedDraft> findById(UUID id);

    /** The organisation's drafts, most recently saved first. */
    List<SavedDraft> findByOwnerOrg(String orgId, int limit);

    /** One page of the organisation's drafts (the drafts table): searched, ordered and counted in the database. */
    DraftPage search(DraftQuery query);

    void delete(UUID id);

    /**
     * @param text    matches the title or the category path, case-insensitively; blank means everything
     * @param newOnly only drafts of products not yet created (not the unsaved edits of existing products)
     */
    record DraftQuery(String orgId, String text, boolean newOnly, DraftSortField sort, boolean descending, int page,
                      int size) {
    }

    /** One page of drafts and how many drafts match in all. */
    record DraftPage(List<DraftListItem> items, long total, int page, int size) {
    }
}
