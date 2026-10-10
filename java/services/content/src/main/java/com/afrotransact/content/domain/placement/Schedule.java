package com.afrotransact.content.domain.placement;

import java.time.Instant;
import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * Schedule window. {@code endsAt} is optional (open-ended).
 *
 * @param startsAt first instant the placement may be served
 * @param endsAt   first instant it may no longer be served, or null
 */
public record Schedule(Instant startsAt, Instant endsAt) {

    public Schedule {
        if (startsAt == null) {
            throw new ContentRuleViolation("content.invalid-schedule", "A start time is required",
                    Map.of("schedule.startsAt", "Required"));
        }
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new ContentRuleViolation("content.invalid-schedule", "The end must be after the start",
                    Map.of("schedule.endsAt", "Must be after the start"));
        }
    }

    public boolean startedBy(Instant now) {
        return !now.isBefore(startsAt);
    }

    public boolean endedBy(Instant now) {
        return endsAt != null && !now.isBefore(endsAt);
    }

    public boolean activeAt(Instant now) {
        return startedBy(now) && !endedBy(now);
    }
}
