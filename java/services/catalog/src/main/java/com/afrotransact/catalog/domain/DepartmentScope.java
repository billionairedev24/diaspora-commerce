package com.afrotransact.catalog.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The departments one organisation may list in, over a snapshot of the category tree. AfroTransact Retail and staff
 * are {@link #unrestricted()}. Nothing here names a department: it works on node codes and the tree.
 */
public final class DepartmentScope {

    private final boolean unrestricted;
    private final Set<String> allowedCodes;
    private final Map<UUID, CategoryNode> byId = new HashMap<>();
    private final Map<String, CategoryNode> departmentsByPath = new LinkedHashMap<>();
    private final List<CategoryNode> nodes;

    private DepartmentScope(boolean unrestricted, Set<String> allowedCodes, List<CategoryNode> nodes) {
        this.unrestricted = unrestricted;
        this.allowedCodes = Set.copyOf(allowedCodes);
        this.nodes = List.copyOf(nodes);
        for (CategoryNode n : nodes) {
            byId.put(n.id(), n);
            if (n.level() == CategoryLevel.DEPARTMENT) {
                departmentsByPath.put(n.path().toLowerCase(Locale.ROOT), n);
            }
        }
    }

    /** May list in every department (AfroTransact Retail, staff). */
    public static DepartmentScope unrestricted() {
        return new DepartmentScope(true, Set.of(), List.of());
    }

    /** May list only in the departments with these node codes, over {@code tree} (every node, retired ones too). */
    public static DepartmentScope of(Set<String> allowedDepartmentCodes, List<CategoryNode> tree) {
        return new DepartmentScope(false, allowedDepartmentCodes, tree);
    }

    public boolean isUnrestricted() {
        return unrestricted;
    }

    /** True when the department (or the department of the node) is one the organisation may list in. */
    public boolean allows(CategoryNode node) {
        return unrestricted || departmentOf(node).map(d -> allowedCodes.contains(d.code())).orElse(false);
    }

    /** True when some node that uses this product type is in an allowed department. */
    public boolean allowsProductType(String productTypeCode) {
        return unrestricted || nodes.stream().anyMatch(n -> productTypeCode.equals(n.productTypeCode()) && allows(n));
    }

    /** True for a path whose first segment (the department) is allowed; a blank path is not about a department. */
    public boolean allowsPath(String path) {
        if (unrestricted || path == null || path.isBlank()) {
            return true;
        }
        return departmentOfPath(path).map(d -> allowedCodes.contains(d.code())).orElse(false);
    }

    /** The nodes below allowed departments (and those departments). */
    public List<CategoryNode> filter(Collection<CategoryNode> tree) {
        List<CategoryNode> out = new ArrayList<>();
        for (CategoryNode n : tree) {
            if (allows(n)) {
                out.add(n);
            }
        }
        return out;
    }

    public Optional<CategoryNode> departmentOf(CategoryNode node) {
        CategoryNode current = node;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.level() == CategoryLevel.DEPARTMENT) {
                return Optional.of(current);
            }
            current = current.parentId() == null ? null : byId.get(current.parentId());
        }
        return Optional.empty();
    }

    private Optional<CategoryNode> departmentOfPath(String path) {
        String first = path.replace('›', '>').split(">")[0].strip().toLowerCase(Locale.ROOT);
        return Optional.ofNullable(departmentsByPath.get(first));
    }

    /** Throws {@link DepartmentNotAllowed} unless the node is in an allowed department. */
    public void require(CategoryNode node) {
        if (!allows(node)) {
            throw refusal(departmentOf(node).map(CategoryNode::name).orElse(null));
        }
    }

    /** Throws unless at least one of these nodes is in an allowed department (a product type used in several). */
    public void requireAny(Collection<CategoryNode> candidates) {
        if (unrestricted || candidates.isEmpty() || candidates.stream().anyMatch(this::allows)) {
            return;
        }
        throw refusal(departmentOf(candidates.iterator().next()).map(CategoryNode::name).orElse(null));
    }

    /** Throws unless some node of this product type is in an allowed department. */
    public void requireProductType(String productTypeCode) {
        if (productTypeCode == null || allowsProductType(productTypeCode)) {
            return;
        }
        throw refusal(nodes.stream().filter(n -> productTypeCode.equals(n.productTypeCode())).findFirst()
                .flatMap(this::departmentOf).map(CategoryNode::name).orElse(null));
    }

    /** Throws unless the path starts in an allowed department. */
    public void requirePath(String path) {
        if (!allowsPath(path)) {
            throw refusal(departmentOfPath(path).map(CategoryNode::name).orElse(null));
        }
    }

    private DepartmentNotAllowed refusal(String departmentName) {
        if (allowedCodes.isEmpty()) {
            return new DepartmentNotAllowed("catalog.no-departments", "Your store has no departments set up yet, so "
                    + "it cannot list in the catalog. Finish seller onboarding, or ask for a department under "
                    + "Listings in Seller Center, and AfroTransact will review it.");
        }
        return new DepartmentNotAllowed("catalog.department-not-allowed", "Your store is not set up to sell in "
                + (departmentName == null ? "this department" : departmentName) + ". Choose “Need another "
                + "department? Request it” under Listings in Seller Center, and AfroTransact will review it.");
    }

    /** Codes of the allowed departments. */
    public Set<String> allowedCodes() {
        return allowedCodes;
    }
}
