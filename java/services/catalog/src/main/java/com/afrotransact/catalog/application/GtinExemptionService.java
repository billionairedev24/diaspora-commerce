package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.DuplicateHints;
import com.afrotransact.catalog.domain.ExemptionRequirement;
import com.afrotransact.catalog.domain.ExemptionStatus;
import com.afrotransact.catalog.domain.GtinExemptionRequest;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.rules.CategoryRules;

/**
 * Items without a barcode (S387). Where the rules allow no barcode and the category is open, the page gets its ATIN at once
 * (after a brand plus part number match is tried). In a brand-gated category, or for a brand that carries barcodes on
 * other pages, staff accept the missing barcode first: the page waits as PENDING_EXEMPTION and is not live. ISBN product
 * types allow no exemption at all (the listing rules refuse it).
 */
public interface GtinExemptionService {

    /**
     * Whether staff must approve a no-barcode item: a brand-gated category (the listing rules say {@code brand_gated}, or
     * the category is in the staff-edited list), or a brand with barcodes on other pages. Empty when the item may get its
     * ATIN at once. No permission check: the product writer asks while it builds a listing.
     */
    Optional<ExemptionRequirement> requirement(CategoryRules rules, String productTypeCode, Map<String, String> attributes);

    /**
     * The page that already exists for this brand and part number, if any: an exempt item tries this match first. No
     * permission check.
     */
    Optional<Product> pageWithSamePartNumber(Map<String, String> attributes);

    /** Records that a page waits for staff (called by the writer in the transaction that stores the page). */
    GtinExemptionRequest requested(Product page, String sellerReason, ExemptionRequirement requirement);

    /** The organisation's own requests (catalog:write). */
    List<ExemptionView> mine();

    /** The staff queue, oldest first, with duplicate hints (catalog:moderate). */
    PageView<ExemptionView> queue(ExemptionStatus status, int page, int size);

    /**
     * Staff approve (the page moves to IN_REVIEW, never LIVE) or reject (the page is rejected with the reason) with a reason
     * of 3 or more characters (catalog:moderate). Publishes {@code catalog.gtin_exemption.decided}.
     */
    ExemptionView decide(UUID id, boolean approve, String reason);

    /**
     * @param sellerMessage what the seller reads while it waits ("We are checking that this item has no barcode.")
     */
    record ExemptionView(GtinExemptionRequest request, String title, String brand, String mpn,
                         List<DuplicateHints.Hint> duplicateHints, boolean overdue, long ageHours,
                         String sellerMessage) {

        public ExemptionView {
            duplicateHints = List.copyOf(duplicateHints);
        }
    }
}
