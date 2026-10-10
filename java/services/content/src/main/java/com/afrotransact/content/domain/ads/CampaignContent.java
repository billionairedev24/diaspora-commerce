package com.afrotransact.content.domain.ads;

import java.time.Instant;
import java.util.List;

/**
 * The editable part of a campaign.
 *
 * @param name             campaign name shown in Seller Center
 * @param advertiser       name shown with the ad ("Sponsored · advertiser"), normally the store name
 * @param market           ISO country of the storefront (US)
 * @param bidMinor         maximum cost per click in minor units (second price: usually less is charged)
 * @param dailyBudgetMinor optional daily cap
 * @param totalBudgetMinor lifetime budget; the campaign pauses when it is spent
 * @param currency         ISO 4217
 * @param keywords         search phrases that trigger the ad (every word must be in the query)
 * @param categories       departments or category paths ("Groceries › Pantry") whose searches trigger the ad
 * @param atins            advertised products (the seller must hold their buy box for the ad to show)
 * @param startsAt         start of the schedule
 * @param endsAt           optional end of the schedule
 */
public record CampaignContent(String name, String advertiser, String market, long bidMinor, Long dailyBudgetMinor,
                              long totalBudgetMinor, String currency, List<String> keywords, List<String> categories,
                              List<String> atins, Instant startsAt, Instant endsAt) {

    public CampaignContent {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        categories = categories == null ? List.of() : List.copyOf(categories);
        atins = atins == null ? List.of() : List.copyOf(atins);
    }
}
