package com.afrotransact.content.domain.ads;

import java.time.LocalDate;

/**
 * One day of a campaign's delivery.
 *
 * @param day                  UTC day
 * @param impressions          ads served
 * @param clicks               billable clicks
 * @param spendMinor           charged
 * @param attributedOrders     orders with a line attributed to the campaign (7-day click)
 * @param attributedSalesMinor sales of attributed lines
 */
public record CampaignDay(LocalDate day, long impressions, long clicks, long spendMinor, long attributedOrders,
                          long attributedSalesMinor) {

    public static CampaignDay empty(LocalDate day) {
        return new CampaignDay(day, 0, 0, 0, 0, 0);
    }

    public CampaignDay plus(CampaignDay o) {
        return new CampaignDay(day, impressions + o.impressions, clicks + o.clicks, spendMinor + o.spendMinor,
                attributedOrders + o.attributedOrders, attributedSalesMinor + o.attributedSalesMinor);
    }

    /** Click-through rate (clicks / impressions), four decimals. */
    public double ctr() {
        return ratio(clicks, impressions);
    }

    /** Return on ad spend (attributed sales / spend), two decimals. */
    public double roas() {
        return spendMinor == 0 ? 0 : Math.round(attributedSalesMinor * 100.0 / spendMinor) / 100.0;
    }

    /** Average cost per click in minor units. */
    public long cpcMinor() {
        return clicks == 0 ? 0 : Math.round((double) spendMinor / clicks);
    }

    private static double ratio(long n, long d) {
        return d == 0 ? 0 : Math.round(n * 10000.0 / d) / 10000.0;
    }
}
