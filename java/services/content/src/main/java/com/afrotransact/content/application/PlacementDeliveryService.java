package com.afrotransact.content.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.PlacementContext;
import com.afrotransact.content.domain.placement.SlotKey;

/** Public, cacheable placement delivery for storefront, apps and the CDN. */
public interface PlacementDeliveryService {

    ResolvedPlacements resolve(PlacementContext ctx);

    PlacementBundle bundle(String market);

    /**
     * Placements for one context.
     *
     * @param market       market
     * @param placements   ordered placements
     * @param etag         fingerprint of the answer
     * @param personalised the context carried per-visitor frequency data (do not share-cache)
     */
    record ResolvedPlacements(String market, List<Placement> placements, String etag, boolean personalised) {
    }

    /**
     * A compiled per-market bundle.
     *
     * @param market      market
     * @param generatedAt when
     * @param slots       servable placements per slot (with targeting for edge-side filtering)
     * @param etag        fingerprint
     */
    record PlacementBundle(String market, Instant generatedAt, Map<SlotKey, List<Placement>> slots, String etag) {
    }
}
