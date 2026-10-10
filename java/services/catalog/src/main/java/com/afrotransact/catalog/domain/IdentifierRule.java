package com.afrotransact.catalog.domain;

/** Which manufacturer identifier a product type requires on each variant. */
public enum IdentifierRule {
    /** Any GTIN; sellers may request an exemption (ATIN issued instead). */
    GTIN,
    /** ISBN-13 (978/979 prefix) required; no exemption. */
    ISBN
}
