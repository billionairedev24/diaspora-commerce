package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What staff edit on a pre-order window (wizard: Details, Comms). Drafts may be incomplete: completeness is checked
 * by {@link PreorderWindow#publishChecks}; this record only enforces formats and lengths.
 *
 * @param name              e.g. "Lubbock Pre-order #7" (≤ 120)
 * @param targets           where: {@code country/region} or {@code country/region/city} keys
 * @param opensAt           ordering opens
 * @param closesAt          ordering closes (inclusive)
 * @param deliversOn        delivery day in the market's time zone
 * @param timeZone          market time zone (dates shown and the delivery day)
 * @param ribbon            header ribbon text (≤ 80)
 * @param headline          hero headline (≤ 140)
 * @param smsCopy           announcement SMS (one segment: 160 GSM-7 septets)
 * @param categories        catalog categories in the window (Pantry, Spices …)
 * @param door              door delivery offered
 * @param pickup            pickup point offered
 * @param pickupPointId     pickup point (optional)
 * @param pickupAddress     pickup point address shown to shoppers (optional, ≤ 200)
 * @param showCount         show the live pre-order count (not while upcoming)
 * @param socialBudget      paid social budget
 * @param comms             communications switched on
 * @param doorFeeOverride   door fee for this window (null = platform setting)
 * @param pickupFeeOverride pickup fee for this window (null = platform setting)
 * @param locationId        delivery-runs location the closing run is planned for (null = matched by city)
 * @param minOrders         fewest orders the window needs (staff-set, 0 = no minimum); below it at the close date
 *                          the window waits for a staff decision (ADR 0005 §4)
 */
public record WindowDetails(String name, List<AreaKey> targets, Instant opensAt, Instant closesAt,
                            LocalDate deliversOn, ZoneId timeZone, String ribbon, String headline, String smsCopy,
                            Set<String> categories, boolean door, boolean pickup, String pickupPointId,
                            String pickupAddress, boolean showCount, Money socialBudget, Set<CommKind> comms,
                            Money doorFeeOverride, Money pickupFeeOverride, UUID locationId,
                            int minOrders) {

    /** Most orders a minimum can ask for. */
    public static final int MAX_MIN_ORDERS = 100_000;

    /** Details without a minimum (0). */
    public WindowDetails(String name, List<AreaKey> targets, Instant opensAt, Instant closesAt,
                         LocalDate deliversOn, ZoneId timeZone, String ribbon, String headline, String smsCopy,
                         Set<String> categories, boolean door, boolean pickup, String pickupPointId,
                         String pickupAddress, boolean showCount, Money socialBudget, Set<CommKind> comms,
                         Money doorFeeOverride, Money pickupFeeOverride, UUID locationId) {
        this(name, targets, opensAt, closesAt, deliversOn, timeZone, ribbon, headline, smsCopy, categories, door,
                pickup, pickupPointId, pickupAddress, showCount, socialBudget, comms, doorFeeOverride,
                pickupFeeOverride, locationId, 0);
    }

    /** Delivery-day reference time: dates without a time are read as local noon (the design's {@code T()}). */
    public static final LocalTime DELIVERY_REFERENCE = LocalTime.NOON;

    /** Separator of catalog category paths ("Groceries › Pantry › Grains & rice"). */
    private static final String PATH_SEPARATOR = Pattern.quote("›");

    public WindowDetails {
        name = trim(name);
        max(name, 120, "delivery-runs.invalid-name", "Name is at most 120 characters");
        List<AreaKey> unique = new ArrayList<>();
        for (AreaKey k : targets == null ? List.<AreaKey>of() : targets) {
            if (k != null && unique.stream().noneMatch(k::sameAs)) {
                unique.add(k);
            }
        }
        if (unique.size() > 50) {
            throw new RuleViolation("delivery-runs.invalid-targets", "A pre-order targets at most 50 areas");
        }
        targets = List.copyOf(unique);
        if (opensAt == null || closesAt == null || deliversOn == null) {
            throw new RuleViolation("delivery-runs.invalid-dates", "Opening, closing and delivery dates are required");
        }
        timeZone = timeZone == null ? ZoneId.of("America/Chicago") : timeZone;
        ribbon = trim(ribbon);
        max(ribbon, 80, "delivery-runs.invalid-ribbon", "Ribbon text is at most 80 characters");
        headline = trim(headline);
        max(headline, 140, "delivery-runs.invalid-headline", "Headline is at most 140 characters");
        smsCopy = trim(smsCopy);
        if (!Gsm7.fitsOneSegment(smsCopy)) {
            throw new RuleViolation("delivery-runs.invalid-sms",
                    "SMS copy must fit one message: 160 GSM-7 characters (70 with emoji or other symbols)");
        }
        Set<String> cats = new LinkedHashSet<>();
        for (String c : categories == null ? Set.<String>of() : categories) {
            String t = trim(c);
            if (!t.isEmpty()) {
                max(t, 60, "delivery-runs.invalid-categories", "Category names are at most 60 characters");
                cats.add(t);
            }
        }
        if (cats.size() > 30) {
            throw new RuleViolation("delivery-runs.invalid-categories", "At most 30 categories");
        }
        categories = Set.copyOf(cats);
        pickupPointId = blankToNull(pickupPointId);
        max(pickupPointId, 80, "delivery-runs.invalid-pickup-point", "Pickup point id is at most 80 characters");
        pickupAddress = blankToNull(pickupAddress);
        max(pickupAddress, 200, "delivery-runs.invalid-pickup-point", "Pickup address is at most 200 characters");
        comms = comms == null || comms.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(comms));
        String currency = Markets.currencyOf(targets.isEmpty() ? null : targets.get(0).country());
        socialBudget = socialBudget == null ? Money.zero(currency) : socialBudget;
        if (minOrders < 0 || minOrders > MAX_MIN_ORDERS) {
            throw new RuleViolation("delivery-runs.invalid-minimum",
                    "The minimum order count is between 0 and " + MAX_MIN_ORDERS);
        }
        PaymentSettings.requireFee(doorFeeOverride);
        PaymentSettings.requireFee(pickupFeeOverride);
        if (!targets.isEmpty() && ((doorFeeOverride != null && !doorFeeOverride.currency().equals(currency))
                || (pickupFeeOverride != null && !pickupFeeOverride.currency().equals(currency)))) {
            throw new RuleViolation("delivery-runs.invalid-fee", "Fee overrides must be in " + currency);
        }
    }

    /** Currency of the window (its first target's country; USD without targets). */
    public String currency() {
        return Markets.currencyOf(targets.isEmpty() ? null : targets.get(0).country());
    }

    /** The same details closing at {@code closes} (closing early). */
    public WindowDetails withClosesAt(Instant closes) {
        return new WindowDetails(name, targets, opensAt, closes, deliversOn, timeZone, ribbon, headline, smsCopy,
                categories, door, pickup, pickupPointId, pickupAddress, showCount, socialBudget, comms,
                doorFeeOverride, pickupFeeOverride, locationId, minOrders);
    }

    /** The delivery day at local noon. */
    public Instant deliversAt() {
        return deliversOn.atTime(DELIVERY_REFERENCE).atZone(timeZone).toInstant();
    }

    /**
     * Whether a category (case-insensitive) is in the window: a name, or a catalog path ("Groceries › Pantry › Grains
     * & rice") of which any level is one of the window's categories, so "Pantry" covers everything under it.
     */
    public boolean includesCategory(String category) {
        if (category == null) {
            return false;
        }
        Set<String> wanted = categories.stream().map(x -> x.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (String level : category.split(PATH_SEPARATOR)) {
            if (wanted.contains(level.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void max(String s, int max, String code, String message) {
        if (s != null && s.length() > max) {
            throw new RuleViolation(code, message);
        }
    }
}
