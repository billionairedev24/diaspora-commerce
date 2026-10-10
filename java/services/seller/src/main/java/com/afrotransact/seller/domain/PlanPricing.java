package com.afrotransact.seller.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Monthly = list price; quarterly = 3 months less 5 %; annual = 10 months (2 months free). */
public final class PlanPricing {

    private static final BigDecimal QUARTERLY_FACTOR = new BigDecimal("0.95");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private PlanPricing() {
    }

    public static PlanQuote quote(Plan plan, Cadence cadence) {
        long m = plan.monthlyCents();
        long total = switch (cadence) {
            case MONTHLY -> m;
            case QUARTERLY -> BigDecimal.valueOf(m).multiply(BigDecimal.valueOf(3)).multiply(QUARTERLY_FACTOR)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            case ANNUAL -> m * 10;
        };
        long perMonth = BigDecimal.valueOf(total).divide(BigDecimal.valueOf(cadence.months()), 0, RoundingMode.HALF_UP)
                .longValueExact();
        long saving = m * cadence.months() - total;
        return new PlanQuote(plan.code(), cadence, total, perMonth, saving);
    }

    /** "Free for 30 days. First charge of $139.65 on Nov 9, then every 3 months. Cancel any time before then." */
    public static FirstCharge firstCharge(Plan plan, Cadence cadence, LocalDate today) {
        if (plan.isFree()) {
            return new FirstCharge(null, 0, "Free plan — no subscription fee.");
        }
        PlanQuote q = quote(plan, cadence);
        LocalDate on = today.plusDays(plan.trialDays());
        String amount = usd(q.totalCents());
        String sentence = plan.trialDays() > 0
                ? "Free for " + plan.trialDays() + " days. First charge of " + amount + " on " + DAY.format(on)
                        + ", then " + cadence.every() + ". Cancel any time before then."
                : "First charge of " + amount + " today, then " + cadence.every() + ".";
        return new FirstCharge(on, q.totalCents(), sentence);
    }

    public static String usd(long cents) {
        BigDecimal v = BigDecimal.valueOf(cents).movePointLeft(2);
        return "$" + (cents % 100 == 0 ? v.setScale(0, RoundingMode.UNNECESSARY).toPlainString() : v.toPlainString());
    }
}
