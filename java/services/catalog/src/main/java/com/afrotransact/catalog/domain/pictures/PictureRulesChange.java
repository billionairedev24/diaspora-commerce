package com.afrotransact.catalog.domain.pictures;

import java.time.Instant;
import java.util.List;

/**
 * One saved change of the picture rules (the audit trail).
 *
 * @param previousProfile null for the first save (the environment default was in force before)
 * @param overridden      the knobs overridden after the change
 */
public record PictureRulesChange(long version, PictureProfile previousProfile, PictureProfile profile,
                                 List<String> overridden, String changedBy, Instant changedAt, String reason) {
}
