package com.afrotransact.seller.domain;

/** A Stripe requirement error, e.g. {@code verification_document_expired} on a person's ID. */
public record RequirementError(String requirement, String code, String reason) {
}
