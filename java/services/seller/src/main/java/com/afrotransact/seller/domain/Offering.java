package com.afrotransact.seller.domain;

/** What the applicant will offer on AfroTransact. */
public enum Offering {
    PRODUCTS,
    SERVICES,
    BOTH;

    public boolean includesServices() {
        return this == SERVICES || this == BOTH;
    }

    public boolean includesProducts() {
        return this == PRODUCTS || this == BOTH;
    }
}
