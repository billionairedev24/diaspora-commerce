package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.UUID;

/** Audit row written whenever staff view an applicant's document or unmasked PII. */
public record DocumentAccess(UUID id, String applicationId, String assetId, String kind, String viewerId,
                             Instant accessedAt) {
}
