package com.afrotransact.platform.security;

/** Who is calling. Mirrors the identity service's principal types. */
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
