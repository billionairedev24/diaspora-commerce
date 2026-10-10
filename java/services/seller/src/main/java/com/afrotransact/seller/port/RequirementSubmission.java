package com.afrotransact.seller.port;

import java.time.Instant;

import com.afrotransact.seller.domain.RequirementAnswer;

/** A validated requirement answer plus uploaded Stripe file ids and the acceptance context (ToS, declarations). */
public record RequirementSubmission(RequirementAnswer answer, String frontFileId, String backFileId, String ip,
                                    String userAgent, Instant at) {
}
