package com.afrotransact.bookings.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A cancellation policy as the design defines it (Flexible, Moderate, Strict or Custom). Every booking keeps a copy
 * of the policy it was made under, so later policy changes never change existing bookings.
 *
 * @param freeHours        free cancellation window (hours before the start)
 * @param tiers            refund tiers, highest window first; cancelling later than every tier keeps the deposit
 * @param noShow           what a no-show costs
 * @param rescheduleHours  free reschedule window (hours before the start)
 * @param maxReschedules   customer reschedules allowed per booking
 */
public record CancellationPolicy(PolicyKind kind, int freeHours, List<RefundTier> tiers, NoShowRule noShow,
                                 int rescheduleHours, int maxReschedules) {

    public CancellationPolicy {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(noShow, "noShow");
        List<RefundTier> sorted = new ArrayList<>(tiers == null ? List.of() : tiers);
        sorted.sort(Comparator.comparingInt(RefundTier::hoursBefore).reversed());
        tiers = List.copyOf(sorted);
    }

    public static CancellationPolicy flexible() {
        return new CancellationPolicy(PolicyKind.FLEXIBLE, 24, List.of(new RefundTier(24, 10_000)),
                NoShowRule.DEPOSIT, 24, 2);
    }

    public static CancellationPolicy moderate() {
        return new CancellationPolicy(PolicyKind.MODERATE, 48,
                List.of(new RefundTier(48, 10_000), new RefundTier(24, 5_000)), NoShowRule.DEPOSIT, 48, 2);
    }

    public static CancellationPolicy strict() {
        return new CancellationPolicy(PolicyKind.STRICT, 168, List.of(new RefundTier(168, 10_000)),
                NoShowRule.DEPOSIT, 168, 2);
    }

    public static CancellationPolicy preset(PolicyKind kind) {
        return switch (kind) {
            case FLEXIBLE -> flexible();
            case MODERATE -> moderate();
            case STRICT -> strict();
            case CUSTOM -> custom(CustomPolicySettings.defaults());
        };
    }

    /** Builds a Custom policy, validating the settings the provider entered. */
    public static CancellationPolicy custom(CustomPolicySettings s) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (s.freeHours() < 0 || s.freeHours() > 720) {
            errors.put("freeHours", "Between 0 and 720 hours");
        }
        if (s.partialRefund()) {
            if (s.partialFromHours() < 0 || s.partialFromHours() >= s.freeHours()) {
                errors.put("partialFromHours", "Must be below the free-cancellation window");
            }
            if (s.partialPercent() < 1 || s.partialPercent() > 99) {
                errors.put("partialPercent", "Between 1 and 99 %");
            }
        }
        if (s.rescheduleHours() < 0 || s.rescheduleHours() > 720) {
            errors.put("rescheduleHours", "Between 0 and 720 hours");
        }
        if (s.maxReschedules() < 0 || s.maxReschedules() > 10) {
            errors.put("maxReschedules", "Between 0 and 10");
        }
        if (s.noShow() == null) {
            errors.put("noShow", "Required");
        }
        if (!errors.isEmpty()) {
            throw new BookingsRuleViolation("bookings.invalid-policy", "Custom policy is invalid", errors);
        }
        List<RefundTier> tiers = new ArrayList<>();
        tiers.add(new RefundTier(s.freeHours(), 10_000));
        if (s.partialRefund()) {
            tiers.add(new RefundTier(s.partialFromHours(), s.partialPercent() * 100));
        }
        return new CancellationPolicy(PolicyKind.CUSTOM, s.freeHours(), tiers, s.noShow(), s.rescheduleHours(),
                s.maxReschedules());
    }

    public String label() {
        return kind.label();
    }

    /** Share of the deposit (basis points) refunded when cancelling {@code untilStart} before the appointment. */
    public int refundShareBps(Duration untilStart) {
        long minutes = untilStart.toMinutes();
        for (RefundTier t : tiers) {
            if (minutes >= t.hoursBefore() * 60L) {
                return t.shareBps();
            }
        }
        return 0;
    }

    /** True when a customer may still move the appointment for free. */
    public boolean allowsReschedule(Duration untilStart, int reschedulesSoFar) {
        return untilStart.toMinutes() >= rescheduleHours * 60L && reschedulesSoFar < maxReschedules;
    }

    /** The sentence customers read on the booking summary (wording from the design). */
    public String text() {
        return switch (kind) {
            case FLEXIBLE -> "Free cancellation or reschedule up to 24 hours before. After that the deposit is kept.";
            case MODERATE -> "Free up to 48 hours before. 24–48 hours: half the deposit is refunded. "
                    + "Under 24 hours: deposit kept.";
            case STRICT -> "Free up to 7 days before. After that the deposit is kept.";
            case CUSTOM -> customText();
        };
    }

    private String customText() {
        StringBuilder sb = new StringBuilder("Free cancellation up to ").append(hours(freeHours)).append(" before.");
        if (tiers.size() > 1) {
            RefundTier partial = tiers.get(1);
            sb.append(' ').append(hours(partial.hoursBefore())).append('–').append(hours(freeHours))
                    .append(" before: ").append(partial.shareBps() / 100).append("% of the deposit back.");
        }
        sb.append(" Later than that the deposit is kept.");
        if (noShow == NoShowRule.FULL) {
            sb.append(" No-shows are charged the full price.");
        }
        sb.append(" Reschedule free up to ").append(hours(rescheduleHours)).append(" before (max ")
                .append(maxReschedules).append(").");
        return sb.toString();
    }

    /** "24 hours", "2 days", "7 days" — the design's hrsLabel. */
    static String hours(int h) {
        if (h >= 48 && h % 24 == 0) {
            return h / 24 + " days";
        }
        return h + " hours";
    }
}
