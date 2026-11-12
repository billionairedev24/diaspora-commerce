package com.afrotransact.catalog.application;

import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.domain.ExemptionRequirement;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.rules.CategoryRules;

/**
 * What the product writer asks before it creates a new page (S385, S387): a barcode under review as a wrong match, an
 * item without a barcode that already has a page, and whether staff must accept the missing barcode first.
 */
public interface NewPageGuard {

    /**
     * Refuses a new page for a barcode while a WRONG_MATCH report on it is open: 409 {@code catalog.wrong-match-open}
     * ("We are checking this barcode. We will tell you within 2 business days."; the days are a setting).
     */
    void refuseWhileWrongMatchOpen(String gtinDigits);

    /**
     * For a listing with a no-barcode variant: tries the brand plus part number match first (409
     * {@code catalog.page-exists} with the ATIN of the page that has it), then says whether staff must approve the
     * exemption. Empty for a listing that has barcodes, or an open category and a brand without barcodes elsewhere.
     */
    Optional<ExemptionRequirement> assessExemption(CategoryRules rules, String productTypeCode,
                                                   Map<String, String> attributes, boolean anyVariantWithoutBarcode);

    /** Records the exemption request of a page that waits for staff (same transaction as the page). */
    void exemptionRequested(Product page, String sellerReason, ExemptionRequirement requirement);
}
