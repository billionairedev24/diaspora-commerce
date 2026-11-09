package com.afrotransact.taxreturns.domain.profile;

/** A fact still needed before a decision can be made, with the message in plain words for the seller. */
public record MissingFact(String factId, String label, String message) {
}
