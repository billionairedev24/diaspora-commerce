package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A product form saved before it is submitted (autosave and "Save draft"), or an unsaved edit of an existing
 * product. The payload is the form's product input as JSON, kept as the seller left it (it need not be valid).
 *
 * @param productId    the product being edited; null for a new product
 * @param title        the draft's title so far (for the drafts list)
 * @param categoryPath the chosen category's path so far; may be null
 */
public record SavedDraft(UUID id, String ownerOrg, String createdBy, UUID productId, String title,
                         String categoryPath, String payload, Instant createdAt, Instant updatedAt, long version) {

    public static final int MAX_PAYLOAD_CHARS = 256 * 1024;

    public SavedDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerOrg, "ownerOrg");
        Objects.requireNonNull(payload, "payload");
        title = title == null ? "" : title.strip();
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
    }

    public SavedDraft saved(String newTitle, String newCategoryPath, String newPayload, Instant now) {
        return new SavedDraft(id, ownerOrg, createdBy, productId, newTitle, newCategoryPath, newPayload, createdAt,
                now, version);
    }
}
