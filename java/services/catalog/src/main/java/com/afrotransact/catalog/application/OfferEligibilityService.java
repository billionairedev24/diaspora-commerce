package com.afrotransact.catalog.application;

import java.util.List;

import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.Variant;

/**
 * May an organisation attach an offer to a product variant? The page is on sale, its brand is not retired or rejected,
 * the department is in the organisation's scope and the selling approval for the brand or category is granted (the gate
 * the product writer uses). Offers asks before it stores an offer; the match lookup shows the same answer.
 */
public interface OfferEligibilityService {

    /**
     * The answer for one variant ATIN (catalog:read or catalog:write). Sellers always ask for their own organisation;
     * service and staff tokens name it in {@code sellerOrgId} (null asks only the page-level questions).
     *
     * @throws com.afrotransact.platform.web.error.NotFoundException {@code catalog.variant-not-found}
     */
    Eligibility eligibility(String variantAtin, String sellerOrgId);

    /** The same answer for a variant already read, with no permission check (callers have done theirs). */
    Eligibility evaluate(Product product, Variant variant, String sellerOrgId);

    /**
     * @param allowedConditions what the category allows: every condition where used items are allowed (listing rule
     *                          D9), else NEW only
     */
    record Eligibility(String atin, String productAtin, boolean canOffer, List<Blocker> blockers,
                       List<ItemCondition> allowedConditions, String pageOwnerOrgId) {

        public Eligibility {
            blockers = List.copyOf(blockers);
            allowedConditions = List.copyOf(allowedConditions);
        }
    }
}
