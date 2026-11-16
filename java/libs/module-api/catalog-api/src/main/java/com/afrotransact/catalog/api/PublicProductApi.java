package com.afrotransact.catalog.api;

import java.util.Map;
import java.util.Optional;

/**
 * The anonymous public product read the AI shopping agent (Lola) makes: {@code GET /v1/public/variants/{atin}} and, when the ATIN
 * is not a variant, {@code GET /v1/public/products/{atin}}. No scope is needed over HTTP, so none is needed here; the call still
 * runs under an in-process caller principal.
 */
public interface PublicProductApi {

    /**
     * A live item by ATIN: the variant when the ATIN names one, else the product page (its first variant gives the price).
     *
     * @return empty when the ATIN is unknown or not live (HTTP 404)
     */
    Optional<PublicItem> find(String atin);

    /**
     * @param atin        the ATIN asked for
     * @param productAtin the product page's ATIN
     * @param title       the title (seller text)
     * @param attributes  product attributes with the variant's options on top (seller text)
     * @param priceMinor  the variant's price in minor units, 0 when the page has no variant
     * @param currency    the variant's currency, USD when the page has no variant
     * @param sellerOrgId the owning organisation
     */
    record PublicItem(String atin, String productAtin, String title, Map<String, String> attributes, long priceMinor,
                      String currency, String sellerOrgId) {

        public PublicItem {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
