package com.afrotransact.catalog.port;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandOption;
import com.afrotransact.catalog.domain.brand.BrandStatus;
import com.afrotransact.catalog.domain.brand.BrandUsage;

/** Read/write port for the brand registry and the products that hold a brand. */
public interface BrandRepository {

    Optional<Brand> findById(UUID id);

    /** Approved and pending brands with this comparison key (the ones a typed name can match). */
    List<Brand> findLiveByKey(String normalisedName);

    Brand insert(Brand brand);

    /**
     * Writes name, status, merge target, decision and scope when {@code brand.version()} still matches and returns the
     * stored copy with version + 1.
     *
     * @throws com.afrotransact.catalog.domain.StaleVersionException when the stored version is different
     */
    Brand update(Brand brand);

    /** Serialises concurrent creation of the same name (held to the end of the transaction). */
    void lockKey(String normalisedName);

    long countPendingOf(String orgId);

    /**
     * Brands for the product form: approved brands whose scope covers one of {@code nodeIds}, and the organisation's
     * own pending ones; best first (the organisation's use, then popularity, then name).
     *
     * @param needle comparison key to match (empty for all): prefix or inside a word of the name
     */
    List<BrandOption> options(Set<UUID> nodeIds, String orgId, String needle, int limit);

    /** Brands filtered by status and name for staff, newest first, with their usage. */
    List<BrandUsage> search(Set<BrandStatus> statuses, String needle, UUID scopeNodeId, int limit, int offset);

    long count(Set<BrandStatus> statuses, String needle, UUID scopeNodeId);

    Optional<BrandUsage> usage(UUID id);

    /** Counts of brands by status (for the queue badges). */
    List<BrandCount> countByStatus();

    /**
     * Points every product that holds {@code fromId} at {@code toId} and its display name; returns the product ids
     * (so the caller can republish them).
     */
    List<UUID> repointProducts(UUID fromId, UUID toId, String toName);

    /** The brands of the ids (merge chains, display). */
    List<Brand> findAllById(Collection<UUID> ids);

    /** Brands that were merged into this one. */
    List<Brand> findMergedInto(UUID id);

    record BrandCount(BrandStatus status, long count) {
    }
}
