package com.afrotransact.content.application;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.content.domain.ads.CampaignDay;

/**
 * A campaign's delivery report.
 *
 * @param campaignId     campaign
 * @param currency       currency of money columns
 * @param from           first day
 * @param to             last day
 * @param days           one row per day of the window (zeros included)
 * @param total          totals of the window
 * @param spentMinor     lifetime spend
 * @param remainingMinor lifetime budget left
 */
public record CampaignReport(String campaignId, String currency, LocalDate from, LocalDate to, List<CampaignDay> days,
                             CampaignDay total, long spentMinor, long remainingMinor) {
}
