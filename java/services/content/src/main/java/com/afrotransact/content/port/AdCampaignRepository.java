package com.afrotransact.content.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.ads.Campaign;
import com.afrotransact.content.domain.ads.CampaignStatus;

/** Sponsored-products campaigns. */
public interface AdCampaignRepository {

    void insert(Campaign campaign);

    /** Saves when the stored version equals {@code campaign.version()}; false on a concurrent change. */
    boolean update(Campaign campaign);

    Optional<Campaign> findById(String id);

    /** Reads and row-locks the campaign for the rest of the transaction (serialises click charging). */
    Optional<Campaign> lock(String id);

    /** Newest first; null filters mean "any". */
    List<Campaign> find(String sellerOrgId, CampaignStatus status, int limit);

    /** ACTIVE campaigns of a market (schedule and daily budget are checked by the caller). */
    List<Campaign> findActive(String market);
}
