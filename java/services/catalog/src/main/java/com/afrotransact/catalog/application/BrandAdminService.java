package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.brand.BrandStatus;
import com.afrotransact.catalog.domain.brand.BrandUsage;

/**
 * Staff administration of the brand registry (permission {@code catalog:taxonomy}): the review queue of brands sellers
 * typed in, approving, rejecting, merging, editing the name and scope, adding brands one by one or in bulk, and
 * retiring. Every change carries a reason, is audited with the taxonomy changes, bumps the brand's version (edits name
 * the version they loaded; a stale one is refused) and publishes {@code catalog.taxonomy.changed}.
 */
public interface BrandAdminService {

    /** Brands by status, name and scope, newest first, with how many listings and sellers use each. */
    BrandPage list(BrandFilter filter);

    BrandView get(UUID id);

    /** Adds an approved brand to the registry for the given scope. 409 when it already exists there. */
    BrandView create(String name, List<UUID> scope, String reason);

    /** Adds many approved brands at once (one per entry); entries that exist or are invalid are reported, not fatal. */
    BulkResult createMany(List<String> names, List<UUID> scope, String reason);

    /** Approves a pending brand (or brings a rejected or retired one back). */
    BrandView approve(UUID id, Long expectedVersion, String reason);

    /** Rejects a pending brand: it is not offered; listings that hold it must pick another brand when edited. */
    BrandView reject(UUID id, Long expectedVersion, String reason);

    /** Withdraws an approved brand from new listings (existing ones keep it). */
    BrandView retire(UUID id, Long expectedVersion, String reason);

    /** Folds the brand into another approved one: listings are re-pointed and show the target's name. */
    BrandView merge(UUID id, Long expectedVersion, UUID targetId, String reason);

    /** Changes the name and/or the scope; at least one must change. A rename follows to the listings. */
    BrandView update(UUID id, Long expectedVersion, String name, List<UUID> scope, String reason);

    record BrandFilter(Set<BrandStatus> statuses, String q, UUID scopeNodeId, int limit, int offset) {
    }

    /** @param counts brands per status, for the queue badges */
    record BrandPage(List<BrandView> brands, long total, Map<BrandStatus, Long> counts) {
    }

    /**
     * @param mergedIntoName the brand it was merged into, for display
     * @param scope          where it applies, as taxonomy paths
     */
    record BrandView(BrandUsage usage, String mergedIntoName, List<ScopeNode> scope) {
    }

    record ScopeNode(UUID nodeId, String path, String level) {
    }

    record BulkResult(List<BulkOutcome> results, int created, int existing, int invalid) {
    }

    /** @param outcome CREATED, EXISTS or INVALID */
    record BulkOutcome(String name, String outcome, UUID brandId, String message) {
    }
}
