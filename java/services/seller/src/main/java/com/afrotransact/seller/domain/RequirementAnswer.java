package com.afrotransact.seller.domain;

import java.time.LocalDate;

/** A validated answer to a requirement, ready to forward to Stripe Accounts / Persons update. */
public record RequirementAnswer(String requirement, RequirementKind kind, String subject, String ein,
                                String idNumberToken, String documentType, String frontAssetId, String backAssetId,
                                String declarerName, LocalDate dateOfBirth, String title, String bankAccountToken,
                                String routingLast4, String url) {
}
