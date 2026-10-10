package com.afrotransact.catalog.port;

import java.time.Instant;

/** Organisations whose listings seller-service has hidden (compliance enforcement). */
public interface ListingSuppressionRepository {

    /**
     * Records the decision unless a newer one ({@code changedAt}) is already stored.
     *
     * @return true when the organisation's hidden state flipped (first decision: true only when hiding)
     */
    boolean apply(String orgId, boolean hidden, String reason, Instant changedAt, Instant now);

    boolean isHidden(String orgId);
}
