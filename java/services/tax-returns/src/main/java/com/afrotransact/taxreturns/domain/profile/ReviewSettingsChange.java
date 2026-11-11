package com.afrotransact.taxreturns.domain.profile;

import java.time.Instant;

/** One change of the tax review settings, kept for audit. {@code from} is null for the first change. */
public record ReviewSettingsChange(ReviewSettings from, ReviewSettings to, String reason, String changedBy,
                                   Instant changedAt) {
}
