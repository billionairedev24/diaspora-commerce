package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** Organisations whose listings seller-service has hidden (compliance enforcement). */
public interface ListingSuppressionRepository {

    /**
     * Records the decision unless a newer one ({@code changedAt}) is already stored.
     *
     * @return true when the organisation's hidden state flipped (first decision: true only when hiding)
     */
    boolean apply(String orgId, boolean hidden, String reason, Instant changedAt, Instant now);

    boolean isHidden(String orgId);

    /** The stored decision of each of these organisations (organisations that were never decided are absent). */
    Map<String, Decision> decisions(Collection<String> orgIds);

    /** The latest hide / restore decision of an organisation and when seller-service took it. */
    record Decision(boolean hidden, Instant changedAt) {
    }
}
