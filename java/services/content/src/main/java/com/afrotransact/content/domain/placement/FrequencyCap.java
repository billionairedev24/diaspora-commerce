package com.afrotransact.content.domain.placement;

import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * Per-visitor frequency cap: at most {@code impressions} views per {@code windowHours}. Zero impressions means
 * uncapped. Clients count impressions and pass them back as {@code seen}; the edge enforces the cap.
 *
 * @param impressions maximum impressions per window (0 = unlimited)
 * @param windowHours window length in hours
 */
public record FrequencyCap(int impressions, int windowHours) {

    public FrequencyCap {
        if (impressions < 0 || windowHours < 0 || impressions > 0 && windowHours == 0 || windowHours > 24 * 90) {
            throw new ContentRuleViolation("content.invalid-frequency-cap",
                    "Frequency cap needs a positive impression count and a window of 1 hour to 90 days",
                    Map.of("frequencyCap", "Invalid"));
        }
    }

    public static FrequencyCap unlimited() {
        return new FrequencyCap(0, 0);
    }

    public boolean capped() {
        return impressions > 0;
    }

    /** True while the visitor has seen the placement fewer times than the cap within the window. */
    public boolean allows(int seenInWindow) {
        return !capped() || seenInWindow < impressions;
    }
}
