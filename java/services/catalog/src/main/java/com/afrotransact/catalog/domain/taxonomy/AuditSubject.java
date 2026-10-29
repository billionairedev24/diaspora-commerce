package com.afrotransact.catalog.domain.taxonomy;

/** What a taxonomy audit entry is about. */
public enum AuditSubject {
    /** A department, category or product-type node of the tree. */
    NODE,
    /** A product-type schema (attributes, identifier rule, traits). */
    PRODUCT_TYPE,
    /** A seller's category request. */
    REQUEST
}
