package com.afrotransact.deliveryruns.port;

import java.util.Collection;
import java.util.Map;

import com.afrotransact.deliveryruns.domain.PreorderResolver.ProductFacts;

/**
 * catalog-service (local port 8101): category and pre-order flags of variants ({@code GET /v1/public/variants/{atin}}).
 * Throws {@link UpstreamUnavailableException} when catalog cannot answer.
 */
public interface CatalogGateway {

    /** Facts per ATIN; ATINs catalog does not know are absent. */
    Map<String, ProductFacts> facts(Collection<String> atins);
}
