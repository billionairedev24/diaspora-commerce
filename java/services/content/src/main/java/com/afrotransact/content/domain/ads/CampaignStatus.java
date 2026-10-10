package com.afrotransact.content.domain.ads;

/** Sponsored-products campaign status. Only ACTIVE campaigns inside their schedule with budget left serve. */
public enum CampaignStatus {
    ACTIVE,
    PAUSED,
    ENDED
}
