package com.afrotransact.content.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.content.domain.ads.AdClick;
import com.afrotransact.content.domain.ads.CampaignDay;

/** Delivery stats, charged clicks and order attribution of sponsored products. */
public interface AdStatsRepository {

    void addImpressions(String campaignId, LocalDate day, long count);

    void addClick(String campaignId, LocalDate day, long chargedMinor);

    void addAttributed(String campaignId, LocalDate day, long orders, long salesMinor);

    /** Charged on {@code day} per campaign (missing = 0). */
    Map<String, Long> spendOn(Collection<String> campaignIds, LocalDate day);

    boolean clickExists(String clickId);

    /** Stores a charged click; false when the click id is already recorded. */
    boolean insertClick(AdClick click, long chargedMinor);

    /** The shopper's last click in [from, to] on a product with that variant, advertised by that seller. */
    Optional<AttributableClick> lastClick(String customerId, String sellerOrgId, String variantAtin, Instant from,
                                          Instant to);

    /** Attributes an order line once; false when the line is already attributed. */
    boolean insertAttribution(String orderId, String lineId, AttributableClick click, long salesMinor, Instant at);

    /**
     * Charged spend per seller organisation, UTC day and currency for days in [from, to] (only non-zero rows), ordered
     * by organisation, day and currency.
     */
    List<OrgDaySpend> spendByOrg(LocalDate from, LocalDate to);

    /** Days in [from, to] that have data, oldest first. */
    List<CampaignDay> days(String campaignId, LocalDate from, LocalDate to);

    /**
     * Ads spend of one organisation on one UTC day.
     *
     * @param spendMinor charged clicks in minor units of {@code currency}
     */
    record OrgDaySpend(String sellerOrgId, LocalDate day, String currency, long clicks, long spendMinor) {
    }

    /**
     * A click an order line can be attributed to.
     *
     * @param clickId    click
     * @param campaignId campaign credited
     */
    record AttributableClick(String clickId, String campaignId) {
    }
}
