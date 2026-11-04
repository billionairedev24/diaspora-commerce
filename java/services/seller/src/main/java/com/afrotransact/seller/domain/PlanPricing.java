package com.afrotransact.seller.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Quotes a plan's price per cadence (set per plan version) and the first-charge sentence. */
public final class PlanPricing {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private PlanPricing() {
    }

    public static PlanQuote quote(Plan plan, Cadence cadence) {
        long total = plan.priceCents(cadence);
        long perMonth = BigDecimal.valueOf(total).divide(BigDecimal.valueOf(cadence.months()), 0, RoundingMode.HALF_UP)
                .longValueExact();
        long saving = Math.max(0, plan.monthlyCents() * cadence.months() - total);
        return new PlanQuote(plan.code(), cadence, total, perMonth, saving);
    }

    /** The plan's own trial. */
    public static FirstCharge firstCharge(Plan plan, Cadence cadence, LocalDate today) {
        return firstCharge(plan, cadence, today, plan.trialDays());
    }

    /** "Free for 30 days. First charge of $139.65 on Nov 9, then every 3 months. Cancel any time before then." */
    public static FirstCharge firstCharge(Plan plan, Cadence cadence, LocalDate today, int trialDays) {
        if (plan.isFree()) {
            return new FirstCharge(null, 0, "Free plan — no subscription fee.");
        }
        PlanQuote q = quote(plan, cadence);
        LocalDate on = today.plusDays(trialDays);
        String amount = usd(q.totalCents());
        String sentence = trialDays > 0
                ? "Free for " + trialDays + " days. First charge of " + amount + " on " + DAY.format(on)
                        + ", then " + cadence.every() + ". Cancel any time before then."
                : "First charge of " + amount + " today, then " + cadence.every() + ".";
        return new FirstCharge(on, q.totalCents(), sentence);
    }

    /** "$39", "$139.65", "$1,000" (US grouping). */
    public static String usd(long cents) {
        BigDecimal v = BigDecimal.valueOf(cents).movePointLeft(2);
        return (cents < 0 ? "-$" : "$") + (cents % 100 == 0 ? String.format(Locale.US, "%,d", Math.abs(cents / 100))
                : String.format(Locale.US, "%,.2f", v.abs()));
    }
}
