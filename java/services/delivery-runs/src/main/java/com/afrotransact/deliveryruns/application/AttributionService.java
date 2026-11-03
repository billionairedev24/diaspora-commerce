package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.UtmTags;
import com.afrotransact.deliveryruns.port.AttributionRepository.Row;

/**
 * Attribution (S262, S263): which advertorial an order came from, and how many visits and sign-ups each UTM source,
 * medium and campaign brought to a location or run. A first-party sink of counters, so staff see it beside the run's
 * economics; the same tags are on every announcement link, so a product-analytics tool reading the storefront's page
 * views sees the same campaigns (docs/engineering/run-attribution.md).
 */
public interface AttributionService {

    /**
     * Public: a visit to a location page (or one of its runs) that carried UTM tags. An untagged visit is not counted.
     * A run that is not the location's is ignored (the visit counts for the location page).
     * An unknown or inactive location is a 404.
     */
    void visit(String locationSlug, UUID runId, UtmTags tags);

    /**
     * A subscription was requested with these tags. Active at once (a signed-in shopper): the sign-up is counted now;
     * pending confirmation (an email subscriber): the tags are kept and the sign-up is counted when it is confirmed.
     */
    void signupRequested(UUID subscriptionId, UUID locationId, UUID runId, UtmTags tags, boolean active);

    /** The subscription became active: counts the sign-up it was requested with, if it carried tags. */
    void signupActivated(UUID subscriptionId, UUID locationId);

    /** {@code orders.order.placed} carried an advertorial creative ({@code cr=adv-...}): remember it for the order. */
    void orderPlaced(String orderId, String creative, String src, Instant placedAt);

    /** Staff (delivery-runs:read): the counts of a location, or of one of its runs, over the last {@code days}. */
    Report report(UUID locationId, UUID runId, int days);

    /**
     * @param locationId the location
     * @param runId      the run, or null for the whole location
     * @param since      first day counted (UTC)
     * @param visits     tagged visits
     * @param signups    sign-ups that carried tags
     * @param rows       per UTM source, medium and campaign, most visits first
     */
    record Report(UUID locationId, UUID runId, LocalDate since, long visits, long signups, List<Row> rows) {
    }
}
