package com.afrotransact.seller.domain;

/** Kinds of Stripe requirements we can collect in our own Account health UI. */
public enum RequirementKind {
    TAX_ID,
    ID_NUMBER,
    VERIFICATION_DOCUMENT,
    OWNERSHIP_DECLARATION,
    REPRESENTATIVE_DOB,
    REPRESENTATIVE_TITLE,
    TOS_ACCEPTANCE,
    BANK_ACCOUNT,
    BUSINESS_URL,
    UNSUPPORTED
}
