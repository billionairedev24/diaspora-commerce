package com.afrotransact.catalog.api;

import java.util.Map;
import java.util.Optional;

/**
 * What other modules may ask catalog in-process: the public, anonymous read of a live variant that tax-returns makes over HTTP
 * today ({@code GET /v1/public/variants/{atin}}). No scope is needed over HTTP, so none is needed here; the call still runs under
 * an in-process caller principal so that the audit trail names who asked.
 */
public interface VariantFactsApi {

    /** @return the live variant's facts, or empty when the ATIN is unknown or not live (HTTP 404) */
    Optional<VariantFacts> liveVariantFacts(String variantAtin);

    /**
     * @param attributes      the product's attribute answers
     * @param categoryCode    the tax profile (category) code, null while the category has none
     * @param taxFacts        the variant's typed tax facts by fact id
     * @param sellerBehaviour the owner listing's behaviour facts
     * @param ownerOrgId      the organisation that owns the listing
     */
    record VariantFacts(String atin, String productType, Map<String, String> attributes, String categoryCode,
                        Map<String, Object> taxFacts, Map<String, Object> sellerBehaviour, String ownerOrgId) {

        public VariantFacts {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            taxFacts = taxFacts == null ? Map.of() : Map.copyOf(taxFacts);
            sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        }
    }
}
