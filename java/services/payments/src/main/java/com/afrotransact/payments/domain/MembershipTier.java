package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A membership tier staff define in Settings (S323): the price per month and per year, the trial, and which benefits
 * it has from the menu. Past members keep the price they signed up at; benefit changes apply to every member at once.
 *
 * @param sortOrder lower is the entry tier; a higher order is an upgrade
 */
public record MembershipTier(String code, String name, String description, TierStatus status, int sortOrder,
                             String currency, long monthlyPriceMinor, long yearlyPriceMinor, int trialDays,
                             List<TierBenefit> benefits, Instant createdAt, String updatedBy, Instant updatedAt,
                             long version) {

    public static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{1,38}$");
    public static final long MAX_PRICE_MINOR = 100_000L;
    public static final int MAX_TRIAL_DAYS = 90;

    public MembershipTier {
        benefits = benefits == null ? List.of() : List.copyOf(benefits);
        description = description == null ? "" : description.trim();
        name = name == null ? null : name.trim();
        validate(code, name, description, status, currency, monthlyPriceMinor, yearlyPriceMinor, trialDays, benefits);
    }

    private static void validate(String code, String name, String description, TierStatus status, String currency,
                                 long monthly, long yearly, int trialDays, List<TierBenefit> benefits) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (code == null || !CODE.matcher(code).matches()) {
            errors.put("code", "Lower-case letters, digits and dashes, starting with a letter (2 to 39 characters)");
        }
        if (name == null || name.isEmpty() || name.length() > 80) {
            errors.put("name", "1 to 80 characters");
        }
        if (description.length() > 500) {
            errors.put("description", "At most 500 characters");
        }
        if (status == null) {
            errors.put("status", "Required");
        }
        if (currency == null || currency.length() != 3) {
            errors.put("currency", "A three-letter currency code");
        }
        if (monthly < 1 || monthly > MAX_PRICE_MINOR) {
            errors.put("monthlyPriceMinor", "Between 0.01 and " + MAX_PRICE_MINOR / 100 + ".00");
        }
        if (yearly < 1 || yearly > MAX_PRICE_MINOR * 12) {
            errors.put("yearlyPriceMinor", "Between 0.01 and " + MAX_PRICE_MINOR * 12 / 100 + ".00");
        } else if (monthly >= 1 && yearly > monthly * 12) {
            errors.put("yearlyPriceMinor", "A year must not cost more than twelve months");
        }
        if (trialDays < 0 || trialDays > MAX_TRIAL_DAYS) {
            errors.put("trialDays", "Between 0 and " + MAX_TRIAL_DAYS + " days");
        }
        Set<BenefitCode> seen = EnumSet.noneOf(BenefitCode.class);
        for (TierBenefit b : benefits) {
            if (!seen.add(b.code())) {
                errors.put("benefits", "Each benefit once (" + b.code() + " is listed twice)");
            }
        }
        if (status == TierStatus.PUBLISHED && benefits.isEmpty()) {
            errors.put("benefits", "A published tier has at least one benefit");
        }
        if (!errors.isEmpty()) {
            throw new PaymentsRuleViolation("payments.membership-tier-invalid", "Invalid membership tier", errors);
        }
    }

    public Optional<TierBenefit> benefit(BenefitCode code) {
        return benefits.stream().filter(b -> b.code() == code).findFirst();
    }

    public boolean has(BenefitCode code) {
        return benefit(code).isPresent();
    }

    public long priceMinor(BillingInterval interval) {
        return interval == BillingInterval.YEARLY ? yearlyPriceMinor : monthlyPriceMinor;
    }

    public Money price(BillingInterval interval) {
        return Money.of(priceMinor(interval), currency);
    }

    public boolean joinable() {
        return status == TierStatus.PUBLISHED;
    }

    /** The tier changed by staff: a new version with a reason kept in the history. */
    public MembershipTier changed(String name, String description, TierStatus status, int sortOrder,
                                  long monthlyPriceMinor, long yearlyPriceMinor, int trialDays,
                                  List<TierBenefit> benefits, String by, Instant at) {
        return new MembershipTier(code, name, description, status, sortOrder, currency, monthlyPriceMinor,
                yearlyPriceMinor, trialDays, benefits, createdAt, by, at, version);
    }
}
