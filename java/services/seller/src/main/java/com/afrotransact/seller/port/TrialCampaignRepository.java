package com.afrotransact.seller.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.TrialCampaign;

/** Trial campaigns (staff-managed). */
public interface TrialCampaignRepository {

    List<TrialCampaign> list();

    /** ACTIVE campaigns (the caller filters by window, plan and code). */
    List<TrialCampaign> active();

    Optional<TrialCampaign> find(UUID id);

    Optional<TrialCampaign> byPromoCode(String code);

    void insert(TrialCampaign c);

    /** Optimistic locking on {@code c.version()}; false when another change won. */
    boolean update(TrialCampaign c);

    /** Counts a redemption unless the campaign is full; false when it is. */
    boolean redeem(UUID id);
}
