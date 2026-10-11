package com.afrotransact.seller.domain;

import java.math.BigDecimal;

/** Plain-English labels of rewards and grants (shown to sellers and staff, and in notifications). */
public final class RewardLabels {

    private RewardLabels() {
    }

    public static String reward(RewardType type, Integer freeDays, Long creditCents, BigDecimal percentOff,
                                Integer durationInvoices) {
        return switch (type) {
            case FREE_DAYS -> days(freeDays == null ? 0 : freeDays) + " free";
            case CREDIT -> PlanPricing.usd(creditCents == null ? 0 : creditCents) + " credit";
            case PERCENT_OFF -> (percentOff == null ? "0" : percentOff.stripTrailingZeros().toPlainString())
                    + "% off your next " + plural(durationInvoices == null ? 1 : durationInvoices, "invoice");
        };
    }

    /** 30 → "1 month", 60 → "2 months", 14 → "14 days". */
    public static String days(int days) {
        if (days > 0 && days % 30 == 0) {
            return plural(days / 30, "month");
        }
        return plural(days, "day");
    }

    static String plural(int n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }
}
