package com.afrotransact.deliveryruns.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AttributionKind;
import com.afrotransact.deliveryruns.domain.UtmTags;

/**
 * Attribution of orders to advertorials (S263) and of visits and sign-ups to UTM tags (S262). Counters only: nothing
 * here identifies a visitor.
 */
public interface AttributionRepository {

    /** The run key of counts for the location page itself. */
    UUID NO_RUN = new UUID(0L, 0L);

    /**
     * Adds one to the counter of this day, location, run (null: the location page) and tags. A location already
     * counting {@code maxCombinations} different tag sets on that day counts further new ones under
     * {@link UtmTags#overflow()}, so the table cannot be filled with made-up tags.
     */
    void count(LocalDate day, UUID locationId, UUID runId, UtmTags tags, AttributionKind kind, int maxCombinations);

    /** Visits and sign-ups per tag set since {@code since}; one run's, or (no run) all of the location's. */
    List<Row> rows(UUID locationId, UUID runId, LocalDate since);

    /** Keeps the tags a not yet active subscription arrived with (replacing earlier ones). */
    void remember(UUID subscriptionId, UUID runId, UtmTags tags);

    /** The remembered tags of this subscription, removed as they are read (so a sign-up is counted once). */
    Optional<Remembered> take(UUID subscriptionId);

    /** Records which advertorial an order came from (the first record of an order stands). */
    void recordOrder(String orderId, String creative, String src, Instant placedAt);

    /**
     * Orders still holding a slot on a run, per advertorial creative (creatives without any are absent). A cancelled
     * run's orders are refunded, so they are not counted.
     */
    Map<String, Integer> runOrdersByCreative(Collection<String> creatives);

    /**
     * @param tags    the UTM tags
     * @param visits  visits that carried them
     * @param signups sign-ups that carried them
     */
    record Row(UtmTags tags, long visits, long signups) {
    }

    /**
     * @param runId the run the shopper was looking at, or null
     * @param tags  the UTM tags
     */
    record Remembered(UUID runId, UtmTags tags) {
    }
}
