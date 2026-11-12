package com.afrotransact.catalog.domain.images;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * How one listing's main image stands against the rules, as stored by the back-fill job (S392).
 *
 * @param productId    the listing
 * @param ownerOrg     the seller organisation
 * @param atin         the listing's ATIN (shown to staff)
 * @param title        the listing title (shown on the task)
 * @param state        OK, IMPROVE or FAIL
 * @param reasons      the findings that explain the state, in plain English
 * @param mainImageUrl the main image that was measured
 * @param checkedAt    when it was measured
 * @param failingSince when the listing first failed (the grace period runs from here); null unless it fails
 * @param hiddenAt     when the hide step limited the listing; null when it is not hidden
 */
public record ImageQualityRecord(UUID productId, String ownerOrg, String atin, String title, ImageQuality state,
                                 List<ImageFinding> reasons, String mainImageUrl, Instant checkedAt,
                                 Instant failingSince, Instant hiddenAt) {

    public ImageQualityRecord {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** The task is shown while the main image fails or passes with a warning. */
    public boolean needsAttention() {
        return state != ImageQuality.OK;
    }

    /** When the grace period ends for a failing listing; null when it does not fail. */
    public Instant hideAfter(int graceDays) {
        return failingSince == null ? null : failingSince.plus(Duration.ofDays(graceDays));
    }

    /** Whole days left of the grace period (0 when over); null when the listing does not fail. */
    public Integer daysLeft(int graceDays, Instant now) {
        Instant end = hideAfter(graceDays);
        if (end == null) {
            return null;
        }
        long days = Duration.between(now, end).toDays();
        return (int) Math.max(0, days);
    }

    /** What the grace period was measured from: the previous record's clock is kept while the listing keeps failing. */
    public static Instant failingClock(ImageQuality next, ImageQualityRecord previous, Instant now) {
        if (next != ImageQuality.FAIL) {
            return null;
        }
        return previous != null && previous.failingSince() != null ? previous.failingSince() : now;
    }
}
