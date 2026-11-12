package com.afrotransact.offers.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** catalog-service lookups used to validate the product an offer is attached to. */
public interface CatalogClient {

    /** The live variant with this variant ATIN. */
    Optional<CatalogVariant> liveVariant(String atin);

    /** The live variant that carries this GTIN (UPC/EAN/ISBN digits). */
    Optional<CatalogVariant> liveVariantByGtin(String gtin);

    /**
     * May this organisation attach an offer to the variant? The page is on sale, its brand is not retired or rejected, the
     * department is in the seller's scope and the selling approval is granted (the gate the product writer uses). Asked
     * with offers' own service token; a refusal is {@code canOffer = false} with plain blockers.
     *
     * @throws CatalogUnavailableException when catalog-service cannot be asked: callers do not guess
     */
    Eligibility eligibility(String variantAtin, String sellerOrgId);

    /**
     * @param blockers          why not, by stable code (catalog.brand-unavailable, offers.selling-approval-required,
     *                          catalog.department-not-allowed, catalog.page-not-live, catalog.selling-unavailable)
     * @param allowedConditions the conditions the category allows (NEW only unless used items are allowed)
     */
    record Eligibility(boolean canOffer, List<Blocker> blockers, List<String> allowedConditions) {

        public Eligibility {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            allowedConditions = allowedConditions == null ? List.of() : List.copyOf(allowedConditions);
        }
    }

    /** One reason an offer is refused, in plain words. */
    record Blocker(String code, String message) {
    }

    /** A sellable catalog variant and the product page it belongs to. */
    record CatalogVariant(UUID productId, String productAtin, String variantAtin, String gtin, UUID categoryId,
                          String title, String ownerOrgId) {
    }

    /** catalog-service could not be reached or answered with a server error. */
    class CatalogUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public CatalogUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
