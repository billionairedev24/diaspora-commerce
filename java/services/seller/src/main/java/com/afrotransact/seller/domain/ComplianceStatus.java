package com.afrotransact.seller.domain;

/** Account health as shown to sellers and staff. */
public enum ComplianceStatus {
    COMPLIANT,
    ACTION_NEEDED,
    RESTRICTED_SOON,
    RESTRICTED,
    VERIFYING
}
