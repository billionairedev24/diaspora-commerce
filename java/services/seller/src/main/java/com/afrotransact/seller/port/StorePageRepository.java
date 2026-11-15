package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.StorePage;
import com.afrotransact.seller.domain.StorePageAuditEntry;

/** Storage of the storefront settings (one row per organisation) and its audit trail. */
public interface StorePageRepository {

    Optional<StorePage> find(String orgId);

    /** Creates the row with this draft at version 1; false when it already exists. */
    boolean insert(String orgId, String draft, Instant at, String actor);

    /** Replaces the draft when {@code expectedVersion} is still the stored one; false when it is stale. */
    boolean updateDraft(String orgId, String draft, long expectedVersion, Instant at, String actor);

    /** Copies the draft to the published slot when {@code expectedVersion} is the stored draft version. */
    boolean publish(String orgId, long expectedVersion, Instant at, String actor);

    /** Clears the published slot. */
    void unpublish(String orgId, Instant at, String actor);

    void audit(String orgId, String action, String actor, long version, Instant at);

    List<StorePageAuditEntry> audit(String orgId, int limit);

    /** Published storefronts of approved sellers and providers (kind, organisation, address, when published), by address. */
    List<PublishedStore> published(int limit);

    record PublishedStore(String kind, String orgId, String slug, Instant publishedAt) {
    }
}
