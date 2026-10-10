package com.afrotransact.orders.port;

import java.util.Collection;
import java.util.Map;

/** catalog-service {@code GET /v1/public/variants/{atin}}: product type (perishable?) and commission category. */
public interface ProductFactsClient {

    /** Facts per variant ATIN; ATINs the catalog does not answer for are missing from the map. */
    Map<String, Facts> facts(Collection<String> variantAtins);

    /** What orders needs to know about a product. */
    record Facts(String variantAtin, String productType, boolean perishable, String category) {
    }
}
