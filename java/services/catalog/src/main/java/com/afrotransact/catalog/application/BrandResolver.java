package com.afrotransact.catalog.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.brand.Brand;

/**
 * Settles the brand of a product being saved, for the form and for the bulk upload alike: a chosen registry brand is
 * checked against the category, a typed name is matched to the registry (case, accents and punctuation ignored) or
 * becomes a PENDING brand, "no brand" words become a reason instead of a made-up brand, and a type without a brand
 * attribute keeps no brand at all.
 */
interface BrandResolver {

    /**
     * @param node       the product's category node, null while it waits on a category request
     * @param attributes the submitted attributes: {@code brand} (a name), {@code brand_id} and {@code brand_reason}
     * @param current    the attributes the product holds now (empty for a new product): a brand it already holds may
     *                   stay even if it is no longer offered
     * @return the attributes with the brand settled: canonical {@code brand} and {@code brand_id}, or {@code brand_reason}
     */
    Map<String, String> resolve(ListingOwner owner, CategoryNode node, ProductType type,
                                Map<String, String> attributes, Map<String, String> current);

    /**
     * The brand the organisation sees for this name in these nodes, or a new PENDING one scoped to the departments.
     *
     * @param covering    the category node and its ancestors
     * @param departments the departments a new brand is scoped to
     */
    Matched matchOrCreate(String orgId, Set<UUID> covering, Set<UUID> departments, String name);

    record Matched(Brand brand, boolean created) {
    }
}
