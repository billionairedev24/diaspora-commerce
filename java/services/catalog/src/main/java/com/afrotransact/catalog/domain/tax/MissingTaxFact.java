package com.afrotransact.catalog.domain.tax;

/** A fact the seller still has to give before a variant can go live, in plain words. */
public record MissingTaxFact(String factId, String label, String message) {
}
