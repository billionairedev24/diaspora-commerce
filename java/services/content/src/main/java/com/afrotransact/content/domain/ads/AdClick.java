package com.afrotransact.content.domain.ads;

import java.time.Instant;
import java.util.List;

/**
 * A sponsored-product click reported by search-service (ads.click.recorded).
 *
 * @param clickId      stable per served ad (one billable click per ad token)
 * @param campaignId   campaign charged
 * @param atin         advertised product
 * @param variantAtins the product's variants (order lines carry variant ATINs; used for attribution)
 * @param customerId   signed-in shopper, when known (attribution)
 * @param priceMinor   second price the auction set
 * @param market       storefront market
 * @param query        search that showed the ad
 * @param occurredAt   click time
 */
public record AdClick(String clickId, String campaignId, String atin, List<String> variantAtins, String customerId,
                      long priceMinor, String market, String query, Instant occurredAt) {

    public AdClick {
        variantAtins = variantAtins == null ? List.of() : List.copyOf(variantAtins);
    }
}
