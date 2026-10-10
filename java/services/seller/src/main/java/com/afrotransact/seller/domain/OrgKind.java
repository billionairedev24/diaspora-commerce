package com.afrotransact.seller.domain;

/** Sellers list products; service providers offer bookable services. */
public enum OrgKind {
    SELLER("seller"),
    PROVIDER("provider");

    private final String rolePrefix;

    OrgKind(String rolePrefix) {
        this.rolePrefix = rolePrefix;
    }

    /** Role-key prefix used by identity-service, e.g. {@code seller.owner}. */
    public String rolePrefix() {
        return rolePrefix;
    }

    public String ownerRole() {
        return rolePrefix + ".owner";
    }
}
