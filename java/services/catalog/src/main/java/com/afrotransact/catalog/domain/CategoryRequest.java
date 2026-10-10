package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A seller asks for a category that does not exist yet. Products can be built against it immediately;
 * they publish only after catalog staff approve it (and map it to a real product type).
 */
public record CategoryRequest(UUID id, String reference, String requestedByOrg, String parentPath, String name,
                              String exampleProduct, String detailsNeeded, CategoryRequestStatus status,
                              UUID resolvedCategoryId, String decisionNote, Instant createdAt, Instant decidedAt,
                              long version) {

    public CategoryRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static CategoryRequest open(UUID id, String reference, String org, String parentPath, String name,
                                       String example, String details, Instant now) {
        requireText(parentPath, "Where it should go");
        requireText(name, "Category name");
        requireText(example, "Example product");
        requireText(details, "Details shoppers need");
        return new CategoryRequest(id, reference, org, parentPath.trim(), name.trim(), example.trim(),
                details.trim(), CategoryRequestStatus.PENDING_REVIEW, null, null, now, null, 0);
    }

    public String fullPath() {
        return parentPath + " › " + name;
    }

    public CategoryRequest approve(UUID categoryId, String note, Instant now) {
        ensurePending();
        return new CategoryRequest(id, reference, requestedByOrg, parentPath, name, exampleProduct, detailsNeeded,
                CategoryRequestStatus.APPROVED, categoryId, note, createdAt, now, version);
    }

    public CategoryRequest reject(String note, Instant now) {
        ensurePending();
        requireText(note, "Rejection note");
        return new CategoryRequest(id, reference, requestedByOrg, parentPath, name, exampleProduct, detailsNeeded,
                CategoryRequestStatus.REJECTED, null, note, createdAt, now, version);
    }

    private void ensurePending() {
        if (status != CategoryRequestStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Category request " + reference + " is already " + status);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.trim().length() < 3) {
            throw new CatalogRuleViolation(field + " needs at least 3 characters");
        }
    }
}
