package com.afrotransact.content.application;

import java.util.List;

/**
 * What the auction needs about one campaign.
 *
 * @param id             campaign
 * @param sellerOrgId    advertiser organisation
 * @param advertiser     name shown with the ad
 * @param bidMinor       maximum cost per click
 * @param remainingMinor budget that can still be spent now (smaller of lifetime and today's remaining budget)
 * @param currency       ISO 4217
 * @param keywords       targeting keywords
 * @param categories     targeting categories
 * @param atins          advertised products
 */
public record ServableCampaign(String id, String sellerOrgId, String advertiser, long bidMinor, long remainingMinor,
                               String currency, List<String> keywords, List<String> categories, List<String> atins) {
}
