package com.afrotransact.seller.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.domain.SellerFacts;

/** Metrics reward rules read, recorded from events, and the facts about an organisation. */
public interface SellerMetricRepository {

    /** Adds {@code value} to the day's sum of a summed metric. */
    void add(String orgId, RewardMetric metric, LocalDate day, long value);

    /** Sum of a summed metric over [from, to]. */
    long sum(String orgId, RewardMetric metric, LocalDate from, LocalDate to);

    /** Sums for many organisations at once (rule preview), keyed by org id. */
    Map<String, Long> sums(RewardMetric metric, LocalDate from, LocalDate to);

    /** Records an offer's live state; true when the state changed (newer events only). */
    boolean setListing(String offerId, String orgId, boolean active, Instant at);

    long activeListings(String orgId);

    Map<String, Long> activeListingsByOrg();

    /** Kind, plan, approval time and whether a reward can be applied (a paid Stripe subscription). */
    Optional<SellerFacts> facts(String orgId);

    /** Facts of every approved organisation with a subscription (rule preview and the daily job). */
    List<SellerFacts> allFacts();
}
