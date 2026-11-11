package com.afrotransact.catalog.application;

import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.port.SellingApprovals.Status;

/**
 * The publish gate of selling approvals (docs/engineering/listing-rules.md section 2): a listing goes live only when
 * the seller sells without approval in its scope (SELL) or holds an approved, unexpired approval for every scope that
 * needs one. Anything else leaves the listing a draft with a plain message and a link to apply. AfroTransact's own
 * listings (platform owner, Retail) are exempt; seller-service decides, the catalog enforces.
 */
public interface SellingPublishGate {

    /** What seller-service says about this listing for this owner (SELL for platform listings). */
    Status evaluate(ListingOwner owner, Facts facts);

    /** @throws SellingApprovalRequired when the answer is APPLY or NOT_AVAILABLE; SellingUnavailable fails closed */
    void requirePublishable(ListingOwner owner, Facts facts);

    /** Staff approval of a listing: the seller must (still) be allowed to sell it. */
    void requireApprovable(Product product);

    /** A seller resumes a paused listing: refused while a selling approval holds it, and unless the seller may sell it. */
    void requireResumable(Product product);

    /** The facts of an existing listing (its category and ancestors, brand, product type, material, first barcode). */
    Facts factsOf(Product product);

    /** Whether the gate is on ({@code at.catalog.selling-gate.enabled}). */
    boolean enabled();

    /**
     * @param categoryId  the category node the listing sits in (null while it waits for a requested category)
     * @param attributes  the listing's attributes: brand, material
     */
    record Facts(UUID categoryId, String productTypeCode, Map<String, String> attributes, String gtin) {

        public Facts {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
