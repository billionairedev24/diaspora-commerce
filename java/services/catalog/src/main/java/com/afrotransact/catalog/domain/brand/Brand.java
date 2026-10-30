package com.afrotransact.catalog.domain.brand;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A brand of the registry.
 *
 * @param normalisedName comparison key of the name ({@link BrandNames#normalise})
 * @param mergedIntoId   the brand it was folded into (status MERGED_INTO), else null
 * @param createdByOrg   the seller organisation that typed it in; null for brands staff listed
 * @param scope          the taxonomy nodes (department, category or product type) the brand applies to, with everything
 *                       below them
 * @param version        optimistic-locking version, bumped by every change
 */
public record Brand(UUID id, String name, String normalisedName, BrandStatus status, UUID mergedIntoId,
                    String createdByOrg, Instant createdAt, Instant updatedAt, long version, String decidedBy,
                    Instant decidedAt, String decisionReason, Set<UUID> scope) {

    public Brand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        normalisedName = normalisedName == null ? BrandNames.normalise(name) : normalisedName;
        scope = scope == null ? Set.of() : Set.copyOf(scope);
    }

    /** A new brand: first version, undecided. */
    public static Brand create(String name, BrandStatus status, String createdByOrg, Set<UUID> scope, Instant now) {
        return new Brand(UUID.randomUUID(), name, BrandNames.normalise(name), status, null, createdByOrg, now, now, 0,
                null, null, null, scope);
    }

    /** Whether the seller organisation may pick it: approved for everyone in scope, pending only for its author. */
    public boolean offeredTo(String orgId) {
        return status == BrandStatus.APPROVED
                || status == BrandStatus.PENDING && orgId != null && orgId.equals(createdByOrg);
    }

    /** The brand after a staff decision. */
    public Brand decided(BrandStatus newStatus, UUID newMergedInto, String actor, String reason, Instant now) {
        return new Brand(id, name, normalisedName, newStatus, newMergedInto, createdByOrg, createdAt, now, version,
                actor, now, reason, scope);
    }

    public Brand renamed(String newName, Instant now) {
        return new Brand(id, newName, BrandNames.normalise(newName), status, mergedIntoId, createdByOrg, createdAt, now,
                version, decidedBy, decidedAt, decisionReason, scope);
    }

    public Brand withScope(Set<UUID> newScope, Instant now) {
        return new Brand(id, name, normalisedName, status, mergedIntoId, createdByOrg, createdAt, now, version,
                decidedBy, decidedAt, decisionReason, newScope);
    }
}
