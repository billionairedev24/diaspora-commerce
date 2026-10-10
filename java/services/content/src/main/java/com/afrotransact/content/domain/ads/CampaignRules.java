package com.afrotransact.content.domain.ads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;

/** Validation and normalisation of campaign content (published ad rules). */
public final class CampaignRules {

    /** Minimum cost per click (minor units). */
    public static final long MIN_BID_MINOR = 10;
    /** Minimum lifetime budget (minor units). */
    public static final long MIN_BUDGET_MINOR = 500;
    public static final int MAX_KEYWORDS = 50;
    public static final int MAX_CATEGORIES = 20;
    public static final int MAX_ATINS = 50;

    private static final Pattern ATIN = Pattern.compile("^AT[0-9A-Z]{8}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private CampaignRules() {
    }

    /** Normalises (trim, lowercase keywords, upper-case market/currency, de-duplicate) or throws a 422. */
    public static CampaignContent normalise(CampaignContent c) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = trim(c.name());
        String advertiser = trim(c.advertiser());
        String market = trim(c.market()).toUpperCase(Locale.ROOT);
        String currency = trim(c.currency()).toUpperCase(Locale.ROOT);
        if (name.length() < 2 || name.length() > 120) {
            errors.put("name", "2 to 120 characters");
        }
        if (advertiser.length() < 2 || advertiser.length() > 120) {
            errors.put("advertiser", "2 to 120 characters");
        }
        if (!COUNTRY.matcher(market).matches()) {
            errors.put("market", "ISO country code such as US");
        }
        if (!CURRENCY.matcher(currency).matches()) {
            errors.put("currency", "ISO currency code such as USD");
        }
        if (c.bidMinor() < MIN_BID_MINOR) {
            errors.put("bidMinor", "at least " + MIN_BID_MINOR);
        }
        if (c.totalBudgetMinor() < MIN_BUDGET_MINOR) {
            errors.put("totalBudgetMinor", "at least " + MIN_BUDGET_MINOR);
        } else if (c.bidMinor() > c.totalBudgetMinor()) {
            errors.put("bidMinor", "cannot exceed the total budget");
        }
        if (c.dailyBudgetMinor() != null) {
            if (c.dailyBudgetMinor() < c.bidMinor()) {
                errors.put("dailyBudgetMinor", "at least one click (the bid)");
            } else if (c.dailyBudgetMinor() > c.totalBudgetMinor()) {
                errors.put("dailyBudgetMinor", "cannot exceed the total budget");
            }
        }
        List<String> keywords = phrases(c.keywords(), true);
        List<String> categories = phrases(c.categories(), false);
        List<String> atins = new ArrayList<>(new LinkedHashSet<>(c.atins().stream().map(CampaignRules::trim).toList()));
        if (keywords.size() > MAX_KEYWORDS || keywords.stream().anyMatch(k -> k.length() > 80)) {
            errors.put("keywords", "at most " + MAX_KEYWORDS + " keywords of up to 80 characters");
        }
        if (categories.size() > MAX_CATEGORIES || categories.stream().anyMatch(k -> k.length() > 200)) {
            errors.put("categories", "at most " + MAX_CATEGORIES + " categories");
        }
        if (keywords.isEmpty() && categories.isEmpty()) {
            errors.put("keywords", "add at least one keyword or category");
        }
        if (atins.isEmpty() || atins.size() > MAX_ATINS || !atins.stream().allMatch(a -> ATIN.matcher(a).matches())) {
            errors.put("atins", "1 to " + MAX_ATINS + " product ATINs");
        }
        if (c.startsAt() == null) {
            errors.put("startsAt", "required");
        } else if (c.endsAt() != null && !c.endsAt().isAfter(c.startsAt())) {
            errors.put("endsAt", "must be after startsAt");
        }
        ContentRuleViolation.throwIfAny("content.invalid-campaign", "The campaign is not valid", errors);
        return new CampaignContent(name, advertiser, market, c.bidMinor(), c.dailyBudgetMinor(), c.totalBudgetMinor(),
                currency, keywords, categories, atins, c.startsAt(), c.endsAt());
    }

    private static List<String> phrases(List<String> values, boolean lower) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            String p = String.join(" ", trim(v).split("\\s+")).trim();
            if (!p.isEmpty()) {
                out.add(lower ? p.toLowerCase(Locale.ROOT) : p);
            }
        }
        return List.copyOf(out);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
