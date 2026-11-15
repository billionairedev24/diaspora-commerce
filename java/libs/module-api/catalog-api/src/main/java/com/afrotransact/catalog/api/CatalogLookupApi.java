package com.afrotransact.catalog.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What other modules may ask catalog in-process, beyond {@link VariantFactsApi}: the reads offers and seller make over HTTP
 * today. Each method is the use case behind one endpoint, entered under the calling module's principal; the scope the
 * endpoint needs is the scope the method needs, and a caller without it is refused with the platform's
 * {@code ForbiddenActionException} (HTTP 403).
 */
public interface CatalogLookupApi {

    /** {@code GET /v1/public/variants/{atin}} (no scope): the live variant with its product, empty when unknown or not live (404). */
    Optional<LiveVariant> liveVariant(String variantAtin);

    /**
     * {@code GET /v1/identifiers/gtin/{code}} (scope {@code catalog:read} or {@code catalog:write}) and then the live product
     * page that uses it: the live variant carrying the barcode, empty when the barcode is invalid, unused, or the page is not live.
     */
    Optional<LiveVariant> liveVariantByGtin(String gtin);

    /**
     * {@code POST /v1/catalog/offer-eligibility} (scope {@code catalog:read} or {@code catalog:write}): may the organisation
     * attach an offer to the variant. A refusal is {@code canOffer=false} with blockers; empty when the variant is unknown (404).
     */
    Optional<OfferEligibility> offerEligibility(String variantAtin, String sellerOrgId);

    /** {@code GET /v1/categories} (public): the active nodes of level DEPARTMENT, in the tree's order. */
    List<Department> departments();

    /** {@code GET /v1/internal/bakery-claims} (scope {@code catalog:bakery-claims.read}): organisations whose listings claim to be a bakery. */
    List<String> bakeryClaimOrganisations();

    /**
     * @param productId       the product page's id
     * @param productAtin     the page ATIN
     * @param variantAtin     the variant ATIN
     * @param gtin            the variant's barcode, null when it has none
     * @param categoryId      the page's category, null while it has none
     * @param sellerOrgId     the organisation that owns the page
     */
    record LiveVariant(UUID productId, String productAtin, String variantAtin, String gtin, UUID categoryId, String title,
                       String sellerOrgId) {
    }

    /** @param allowedConditions NEW only unless the category allows used items */
    record OfferEligibility(boolean canOffer, List<Blocker> blockers, List<String> allowedConditions) {

        public OfferEligibility {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            allowedConditions = allowedConditions == null ? List.of() : List.copyOf(allowedConditions);
        }
    }

    record Blocker(String code, String message) {
    }

    /** @param compliance the compliance requirements of the department (names), for example {@code FOOD_SERVICE} */
    record Department(String code, String name, Set<String> compliance) {

        public Department {
            compliance = compliance == null ? Set.of() : Set.copyOf(compliance);
        }
    }
}
