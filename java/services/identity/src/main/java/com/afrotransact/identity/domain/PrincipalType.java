package com.afrotransact.identity.domain;

/** Value of the {@code pt} token claim. Mirrors {@code com.afrotransact.platform.security.PrincipalType}. */
public enum PrincipalType {
    CUSTOMER,
    SELLER_MEMBER,
    PROVIDER_MEMBER,
    STAFF,
    DRIVER,
    PARTNER_APP,
    AI_AGENT,
    SERVICE
}
