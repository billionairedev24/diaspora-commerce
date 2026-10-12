package com.afrotransact.catalog.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.SavedDraft;

/**
 * Saved drafts of the one-step product form (S307): autosave and "Save draft" for a new product, or an edit of an
 * existing one. Members of the owning organisation with catalog:write only. Submitting the form with the draft's id
 * creates or updates the product and deletes the draft in the same transaction.
 */
public interface ProductDraftService {

    /** The organisation's drafts, most recently saved first (at most 100). */
    List<SavedDraft> list();

    SavedDraft get(UUID id);

    /**
     * Saves a new draft. With {@code productId} it is the unsaved edit of that product; a product has at most one
     * such draft, so saving another replaces it.
     */
    SavedDraft create(UUID productId, String title, String categoryPath, String payload);

    /** Autosave: replaces the payload if {@code version} still matches (409 otherwise). */
    SavedDraft save(UUID id, long version, String title, String categoryPath, String payload);

    void delete(UUID id);
}
