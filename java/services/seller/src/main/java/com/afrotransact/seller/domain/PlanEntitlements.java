package com.afrotransact.seller.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a plan lets an organisation do, configured per plan by staff in the plan's {@code limits}:
 * <ul>
 *   <li>{@code listings}: the most live listings (a number, or {@code unlimited}; absent = unlimited);</li>
 *   <li>{@code aiListingWriter}: {@code true} when the AI listing writer is included;</li>
 *   <li>{@code lolaTier}: {@code ESSENTIALS}, {@code GROWTH} or {@code PRO} — what Lola, the seller assistant, can do.</li>
 * </ul>
 * There is no limit on shops or users. Any other key in {@code limits} is shown as plan information only.
 *
 * @param listings the live-listing cap, null when unlimited
 */
public record PlanEntitlements(Long listings, boolean aiListingWriter, LolaTier lolaTier) {

    public static final String LISTINGS = "listings";
    public static final String AI_LISTING_WRITER = "aiListingWriter";
    public static final String LOLA_TIER = "lolaTier";
    public static final String UNLIMITED = "unlimited";
    /** The largest listing cap staff can set (use {@code unlimited} beyond it). */
    public static final long MAX_LISTINGS = 10_000_000L;

    /** Lola's tiers. */
    public enum LolaTier { ESSENTIALS, GROWTH, PRO }

    /** What a plan with no entitlements configured gives: nothing capped, no extras, Lola Essentials. */
    public static final PlanEntitlements NONE = new PlanEntitlements(null, false, LolaTier.ESSENTIALS);

    public PlanEntitlements {
        lolaTier = lolaTier == null ? LolaTier.ESSENTIALS : lolaTier;
    }

    /** Reads the entitlements from a plan's limits; a missing or malformed value takes the safe default. */
    public static PlanEntitlements of(Map<String, String> limits) {
        if (limits == null || limits.isEmpty()) {
            return NONE;
        }
        return new PlanEntitlements(parseListings(limits.get(LISTINGS)),
                "true".equalsIgnoreCase(limits.get(AI_LISTING_WRITER)), parseTier(limits.get(LOLA_TIER)));
    }

    /** Whether {@code live} listings leave room for another one. */
    public boolean allowsAnotherListing(long live) {
        return listings == null || live < listings;
    }

    /** Field errors of the entitlement keys in {@code limits} (empty when they are valid). */
    public static Map<String, String> validate(Map<String, String> limits) {
        Map<String, String> errors = new LinkedHashMap<>();
        String l = limits.get(LISTINGS);
        if (l != null && !UNLIMITED.equalsIgnoreCase(l.trim())) {
            Long n = number(l);
            if (n == null || n < 0 || n > MAX_LISTINGS) {
                errors.put("limits.listings", "A number from 0 to " + MAX_LISTINGS + ", or unlimited");
            }
        }
        String ai = limits.get(AI_LISTING_WRITER);
        if (ai != null && !"true".equalsIgnoreCase(ai) && !"false".equalsIgnoreCase(ai)) {
            errors.put("limits.aiListingWriter", "true or false");
        }
        String tier = limits.get(LOLA_TIER);
        if (tier != null && parseTier(tier) == null) {
            errors.put("limits.lolaTier", "ESSENTIALS, GROWTH or PRO");
        }
        return errors;
    }

    private static Long parseListings(String raw) {
        if (raw == null || UNLIMITED.equalsIgnoreCase(raw.trim())) {
            return null;
        }
        Long n = number(raw);
        return n == null || n < 0 ? null : n;
    }

    private static Long number(String raw) {
        try {
            return Long.parseLong(raw.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LolaTier parseTier(String raw) {
        if (raw == null) {
            return LolaTier.ESSENTIALS;
        }
        try {
            return LolaTier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
