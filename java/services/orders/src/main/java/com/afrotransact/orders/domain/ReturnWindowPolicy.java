package com.afrotransact.orders.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Whether a shipment offers "Return or replace items", and until when. tax-returns owns the RMA lifecycle and the
 * authoritative per-line eligibility (seller policies, open RMAs); orders only links to it.
 */
public interface ReturnWindowPolicy {

    /** End of the return window of a delivered shipment; empty when it has no returnable line or isn't delivered. */
    Optional<Instant> windowEndsAt(Shipment shipment);

    /** True when the delivered shipment has only perishable / non-returnable lines (report a problem instead). */
    boolean problemReportOnly(Shipment shipment);
}
