package com.afrotransact.catalog.domain;

/** Why a no-barcode item needs staff approval instead of getting its ATIN at once (S387). */
public enum ExemptionRequirement {
    /** The category is brand-gated (clothing, bags, electronics, perfume): a made-up "no barcode" hides a copy. */
    BRAND_GATED_CATEGORY,
    /** The brand already appears with barcodes on other pages, so a real product of it should have one. */
    KNOWN_GS1_BRAND
}
