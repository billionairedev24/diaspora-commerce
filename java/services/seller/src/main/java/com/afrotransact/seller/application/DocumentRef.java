package com.afrotransact.seller.application;

/** An uploaded document referenced by an application, e.g. FORMATION_DOCUMENT, LICENCE, INSURANCE. */
public record DocumentRef(String assetId, String kind, String label) {
}
