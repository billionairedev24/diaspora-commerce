package com.afrotransact.seller.domain;

/** Trial campaigns and reward rules: drafts are not applied; paused ones stop; ended ones are kept for the record. */
public enum CampaignStatus {
    ACTIVE,
    PAUSED,
    ENDED
}
