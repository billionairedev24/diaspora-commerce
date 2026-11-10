package com.afrotransact.taxreturns.domain.profile;

import java.time.Instant;

/** What tax review asked the seller to provide before it can decide; cleared when the seller's facts change. */
public record InformationRequest(String message, String requestedBy, Instant requestedAt) {
}
