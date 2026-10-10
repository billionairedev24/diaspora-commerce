package com.afrotransact.deliveryruns.domain;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The editable part of a {@link Location}.
 *
 * @param slug               URL key of the location page, e.g. {@code bastrop-tx}
 * @param name               town / area name shown to shoppers, e.g. "Bastrop"
 * @param market             market code (content-service markets), e.g. {@code US}
 * @param state              two-letter state, e.g. {@code TX}
 * @param timeZone           IANA zone used to format windows and cutoffs in messages
 * @param area               polygon (geo zone) or centre + radius
 * @param origin             where runs leave from (FC / hub) for economics; null = the configured default origin
 * @param defaultFeeMinor    default run delivery fee in minor units (e.g. 799)
 * @param currency           ISO 4217 currency
 * @param defaultMinOrders   default minimum orders for a run to go ahead
 * @param defaultCapacity    default run capacity
 * @param dropMode           default drop mode
 * @param pickupPoint        default community pickup point (required for {@link DropMode#PICKUP_POINT})
 * @param whatsappInviteUrl  the location's WhatsApp community invite link (optional)
 * @param socialLinks        social pages by network, e.g. {@code facebook -> https://facebook.com/groups/...}
 * @param defaultCutoffPolicy what runs do when the minimum is not met at cutoff
 * @param defaultExtendDays  how many days an {@link CutoffPolicy#EXTEND} run moves by
 * @param active             inactive locations get no new runs and are hidden from public reads
 */
public record LocationDetails(String slug, String name, String market, String state, ZoneId timeZone, RunArea area,
                              GeoPoint origin, long defaultFeeMinor, String currency, int defaultMinOrders,
                              Capacity defaultCapacity, DropMode dropMode, PickupPoint pickupPoint,
                              String whatsappInviteUrl, Map<String, String> socialLinks,
                              CutoffPolicy defaultCutoffPolicy, int defaultExtendDays, boolean active) {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public LocationDetails {
        if (slug == null || !SLUG.matcher(slug).matches() || slug.length() > 80) {
            throw new RuleViolation("delivery-runs.invalid-slug",
                    "Slug must be lower-case words joined by hyphens, e.g. bastrop-tx");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new RuleViolation("delivery-runs.invalid-name", "Town or area name is required (max 120)");
        }
        if (market == null || market.isBlank()) {
            throw new RuleViolation("delivery-runs.invalid-market", "Market is required");
        }
        market = market.trim().toUpperCase(Locale.ROOT);
        state = state == null || state.isBlank() ? null : state.trim().toUpperCase(Locale.ROOT);
        if (timeZone == null || area == null) {
            throw new RuleViolation("delivery-runs.invalid-location", "Time zone and area are required");
        }
        if (defaultFeeMinor < 0) {
            throw new RuleViolation("delivery-runs.invalid-fee", "The run fee cannot be negative");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new RuleViolation("delivery-runs.invalid-currency", "Currency must be an ISO 4217 code");
        }
        if (defaultMinOrders < 1) {
            throw new RuleViolation("delivery-runs.invalid-minimum", "Minimum orders must be at least 1");
        }
        defaultCapacity = defaultCapacity == null ? Capacity.UNLIMITED : defaultCapacity;
        dropMode = dropMode == null ? DropMode.DOORSTEP : dropMode;
        if (dropMode == DropMode.PICKUP_POINT && pickupPoint == null) {
            throw new RuleViolation("delivery-runs.pickup-point-required", "Pickup-point drops need a pickup point");
        }
        whatsappInviteUrl = blankToNull(whatsappInviteUrl);
        if (whatsappInviteUrl != null && !whatsappInviteUrl.startsWith("https://")) {
            throw new RuleViolation("delivery-runs.invalid-link", "The WhatsApp invite link must be an https URL");
        }
        socialLinks = socialLinks == null ? Map.of() : Map.copyOf(socialLinks);
        for (String url : socialLinks.values()) {
            if (url == null || !url.startsWith("https://")) {
                throw new RuleViolation("delivery-runs.invalid-link", "Social links must be https URLs");
            }
        }
        defaultCutoffPolicy = defaultCutoffPolicy == null ? CutoffPolicy.EXTEND : defaultCutoffPolicy;
        if (defaultExtendDays < 1 || defaultExtendDays > 30) {
            throw new RuleViolation("delivery-runs.invalid-extension", "Extend days must be between 1 and 30");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
