package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A node of the category tree. Leaves ({@link CategoryLevel#PRODUCT_TYPE}) reference a {@link ProductType}.
 *
 * @param code          stable machine code, unique across the tree ("fashion-women-dresses")
 * @param path          human path such as "Fashion › Women › Dresses"
 * @param active        false once retired: the node stays on the products that already use it but cannot be chosen
 *                      for new ones
 * @param version       optimistic-locking version, bumped by every staff change
 * @param retiredAt     when it was retired (null while active)
 * @param retiredReason why it was retired
 * @param compliance    evidence sellers must have on file to sell in this department ({@code FOOD_PERMIT},
 *                      {@code BEAUTY_LABELLING}); data on the node, empty below departments and for most of them
 * @param taxProfileId the tax profile (the category code of tax-returns) of a category node; null elsewhere
 * @param taxStatus   gate of that profile (ACTIVE, NEEDS_APPROVAL, BLOCKED_BUNDLE, REFINE_CODE, CONFLICT_OPEN); null
 *                    elsewhere. Sellers can publish into a category only when it is ACTIVE.
 */
public record CategoryNode(UUID id, UUID parentId, String code, String name, CategoryLevel level,
                           String productTypeCode, String path, int position, boolean active, long version,
                           Instant retiredAt, String retiredReason, Set<String> compliance,
                           String taxProfileId, String taxStatus) {

    public CategoryNode {
        compliance = compliance == null ? Set.of() : Set.copyOf(compliance);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(path, "path");
        if (level == CategoryLevel.PRODUCT_TYPE && productTypeCode == null) {
            throw new IllegalArgumentException("Product-type nodes need a product type code");
        }
    }

    /** A node without tax data. */
    public CategoryNode(UUID id, UUID parentId, String code, String name, CategoryLevel level,
                        String productTypeCode, String path, int position, boolean active, long version,
                        Instant retiredAt, String retiredReason, Set<String> compliance) {
        this(id, parentId, code, name, level, productTypeCode, path, position, active, version, retiredAt,
                retiredReason, compliance, null, null);
    }

    /** This node with its tax profile and gate status. */
    public CategoryNode withTax(String profileId, String status) {
        return new CategoryNode(id, parentId, code, name, level, productTypeCode, path, position, active, version,
                retiredAt, retiredReason, compliance, profileId, status);
    }

    /** A node without compliance data. */
    public CategoryNode(UUID id, UUID parentId, String code, String name, CategoryLevel level, String productTypeCode,
                        String path, int position, boolean active, long version, Instant retiredAt,
                        String retiredReason) {
        this(id, parentId, code, name, level, productTypeCode, path, position, active, version, retiredAt,
                retiredReason, Set.of());
    }

    /** A node as requests build it: code assigned on insert, first version, never retired. */
    public CategoryNode(UUID id, UUID parentId, String name, CategoryLevel level, String productTypeCode, String path,
                        int position, boolean active) {
        this(id, parentId, null, name, level, productTypeCode, path, position, active, 0, null, null);
    }

    public CategoryNode withCode(String newCode) {
        return new CategoryNode(id, parentId, newCode, name, level, productTypeCode, path, position, active, version,
                retiredAt, retiredReason, compliance, taxProfileId, taxStatus);
    }

    public CategoryNode withName(String newName, String newPath) {
        return new CategoryNode(id, parentId, code, newName, level, productTypeCode, newPath, position, active,
                version, retiredAt, retiredReason, compliance, taxProfileId, taxStatus);
    }

    public CategoryNode withPlacement(UUID newParentId, String newPath, int newPosition) {
        return new CategoryNode(id, newParentId, code, name, level, productTypeCode, newPath, newPosition, active,
                version, retiredAt, retiredReason, compliance, taxProfileId, taxStatus);
    }

    public CategoryNode withPath(String newPath) {
        return new CategoryNode(id, parentId, code, name, level, productTypeCode, newPath, position, active,
                version, retiredAt, retiredReason, compliance, taxProfileId, taxStatus);
    }

    public CategoryNode withPosition(int newPosition) {
        return new CategoryNode(id, parentId, code, name, level, productTypeCode, path, newPosition, active,
                version, retiredAt, retiredReason, compliance, taxProfileId, taxStatus);
    }

    public CategoryNode retired(String reason, Instant now) {
        return new CategoryNode(id, parentId, code, name, level, productTypeCode, path, position, false, version,
                now, reason, compliance, taxProfileId, taxStatus);
    }

    public CategoryNode restored() {
        return new CategoryNode(id, parentId, code, name, level, productTypeCode, path, position, true, version,
                null, null, compliance, taxProfileId, taxStatus);
    }
}
