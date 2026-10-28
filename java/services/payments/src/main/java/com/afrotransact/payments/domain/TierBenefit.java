package com.afrotransact.payments.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One benefit of a tier with its numbers. Which numbers a benefit takes depends on its code; the rest stay null.
 *
 * @param minOrderMinor the goods subtotal an order needs for the benefit (free delivery, free shipping, cashback)
 * @param capMinor      the most the benefit is worth on one order, null for no cap
 * @param percentBps    hundredths of a percent: cashback share of the goods, or the extra on referral / gift rewards
 * @param hours         how long before the public opening members can order (early access)
 * @param amountMinor   the monthly credit
 */
public record TierBenefit(BenefitCode code, Long minOrderMinor, Long capMinor, Integer percentBps, Integer hours,
                          Long amountMinor) {

    public static final int MAX_CASHBACK_BPS = 2500;
    public static final int MAX_BOOST_BPS = 10_000;
    public static final int MAX_EARLY_HOURS = 720;
    public static final long MAX_MONTHLY_CREDIT_MINOR = 100_000L;

    public TierBenefit {
        if (code == null) {
            throw new PaymentsRuleViolation("payments.membership-benefit-invalid", "A benefit needs a code",
                    Map.of("benefits", "Name the benefit"));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        boolean orderRule = code == BenefitCode.FREE_DELIVERY || code == BenefitCode.FREE_SHIPPING
                || code == BenefitCode.CASHBACK;
        if (orderRule) {
            minOrderMinor = minOrderMinor == null ? 0L : minOrderMinor;
            if (minOrderMinor < 0) {
                errors.put("minOrderMinor", "Not negative");
            }
            if (capMinor != null && capMinor <= 0) {
                errors.put("capMinor", "Above zero, or no cap");
            }
        } else {
            reject(errors, "minOrderMinor", minOrderMinor != null);
            reject(errors, "capMinor", capMinor != null);
        }
        if (code == BenefitCode.CASHBACK || code == BenefitCode.BOOSTED_REWARDS) {
            int max = code == BenefitCode.CASHBACK ? MAX_CASHBACK_BPS : MAX_BOOST_BPS;
            if (percentBps == null || percentBps < 1 || percentBps > max) {
                errors.put("percentBps", "Between 0.01% and " + max / 100 + "%");
            }
        } else {
            reject(errors, "percentBps", percentBps != null);
        }
        if (code == BenefitCode.EARLY_ACCESS) {
            if (hours == null || hours < 1 || hours > MAX_EARLY_HOURS) {
                errors.put("hours", "Between 1 and " + MAX_EARLY_HOURS + " hours");
            }
        } else {
            reject(errors, "hours", hours != null);
        }
        if (code == BenefitCode.MONTHLY_CREDIT) {
            if (amountMinor == null || amountMinor < 1 || amountMinor > MAX_MONTHLY_CREDIT_MINOR) {
                errors.put("amountMinor", "Between 0.01 and " + MAX_MONTHLY_CREDIT_MINOR / 100 + ".00");
            }
        } else {
            reject(errors, "amountMinor", amountMinor != null);
        }
        if (!errors.isEmpty()) {
            throw new PaymentsRuleViolation("payments.membership-benefit-invalid", "Check the " + code
                    + " benefit", errors);
        }
    }

    private static void reject(Map<String, String> errors, String field, boolean present) {
        if (present) {
            errors.put(field, "Not used by this benefit");
        }
    }

    /** A benefit that takes no numbers. */
    public static TierBenefit flag(BenefitCode code) {
        return new TierBenefit(code, null, null, null, null, null);
    }

    public long minOrder() {
        return minOrderMinor == null ? 0 : minOrderMinor;
    }
}
