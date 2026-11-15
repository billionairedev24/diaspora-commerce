package com.afrotransact.seller.domain;

import java.time.Instant;

/**
 * A seller's storefront settings: the draft being edited and the published copy the public sees (null while
 * unpublished). Both are JSON documents whose schema belongs to gateway-bff; the seller service only stores them.
 */
public record StorePage(String orgId, String draft, String published, long version, Long publishedVersion,
                        Instant updatedAt, String updatedBy, Instant publishedAt) {

    public static final int MAX_JSON_BYTES = 32_768;

    public boolean isPublished() {
        return published != null;
    }

    public boolean hasUnpublishedChanges() {
        return published == null ? draft != null : publishedVersion == null || publishedVersion != version;
    }
}
