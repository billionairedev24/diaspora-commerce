package com.afrotransact.catalog.application;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryNode;

/** Which taxonomy nodes a brand request is about: the chosen node with its ancestors, and its department. */
final class BrandScopes {

    private final Map<UUID, CategoryNode> byId = new HashMap<>();
    private final List<CategoryNode> all;

    BrandScopes(List<CategoryNode> all) {
        this.all = all;
        all.forEach(n -> byId.put(n.id(), n));
    }

    /** The node a category parameter names: an id or a code; empty when it names none. */
    Optional<CategoryNode> node(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            return Optional.empty();
        }
        String v = idOrCode.trim();
        try {
            return Optional.ofNullable(byId.get(UUID.fromString(v)));
        } catch (IllegalArgumentException e) {
            return all.stream().filter(n -> v.equals(n.code())).findFirst();
        }
    }

    /** The node and every ancestor: a brand scoped to any of them applies. */
    Set<UUID> covering(CategoryNode node) {
        Set<UUID> out = new LinkedHashSet<>();
        CategoryNode at = node;
        while (at != null && out.add(at.id())) {
            at = at.parentId() == null ? null : byId.get(at.parentId());
        }
        return out;
    }

    /** The root (department) above the node, the node itself for a department. */
    CategoryNode department(CategoryNode node) {
        CategoryNode at = node;
        int guard = 0;
        while (at.parentId() != null && byId.containsKey(at.parentId()) && guard++ < 10) {
            at = byId.get(at.parentId());
        }
        return at;
    }

    /** The nodes of a request: the named node, or every node that uses the product type. */
    List<CategoryNode> nodesOf(String productType, String category) {
        Optional<CategoryNode> named = node(category);
        if (named.isPresent()) {
            return List.of(named.get());
        }
        if (category != null && !category.isBlank()) {
            throw new CatalogRuleViolation("Unknown category", Map.of("category", "Unknown category"));
        }
        if (productType == null || productType.isBlank()) {
            throw new CatalogRuleViolation("Choose a category or a product type",
                    Map.of("category", "Required"));
        }
        List<CategoryNode> used = all.stream().filter(n -> productType.trim().equals(n.productTypeCode())
                && n.active()).toList();
        if (used.isEmpty()) {
            throw new CatalogRuleViolation("Unknown product type", Map.of("productType", "Unknown product type"));
        }
        return used;
    }
}
