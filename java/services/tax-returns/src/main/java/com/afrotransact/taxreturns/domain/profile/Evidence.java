package com.afrotransact.taxreturns.domain.profile;

import java.time.Instant;

/** A reference to an evidence file kept by catalog-service (label photo, Drug Facts...). */
public record Evidence(String factId, String fileName, String url, String uploadedBy, Instant uploadedAt) {
}
