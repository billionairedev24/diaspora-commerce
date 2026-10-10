package com.afrotransact.content.application;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.content.domain.ads.Campaign;
import com.afrotransact.content.domain.ads.CampaignContent;
import com.afrotransact.content.domain.ads.CampaignStatus;

/**
 * Sponsored-products campaigns (S127): sellers manage their own organisation's campaigns ({@code ads:manage}); staff
 * view ({@code content:ads.view}) or manage ({@code content:ads.edit}) every organisation's.
 */
public interface AdCampaignService {

    /** Sellers see their own organisation's campaigns only; staff may filter by organisation. */
    List<Campaign> list(String sellerOrgId, CampaignStatus status);

    Campaign get(String id);

    Campaign create(String sellerOrgId, CampaignContent content);

    Campaign update(String id, CampaignContent content, long expectedVersion);

    Campaign pause(String id);

    /** Resumes a manually paused campaign; one paused at zero budget needs a higher total budget first. */
    Campaign resume(String id);

    Campaign end(String id);

    /** Impressions, clicks, CTR, spend, CPC, attributed orders and sales (7-day click) and ROAS by day. */
    CampaignReport report(String id, LocalDate from, LocalDate to);
}
