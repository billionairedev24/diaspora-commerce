package com.afrotransact.identity.domain;

/**
 * Kind of organisation a member can act for. {@code BOTH} (S215) is an organisation that sells products and offers
 * services: its members may hold {@code seller.*} or {@code provider.*} roles and Seller Center shows both groups.
 */
public enum OrgKind {
    SELLER,
    PROVIDER,
    BOTH;

    /** Whether this organisation acts as {@code kind} (BOTH acts as seller and as provider). */
    public boolean actsAs(OrgKind kind) {
        return this == kind || this == BOTH && kind != BOTH;
    }

    /** Owner role granted on approval; for BOTH the portal it onboarded through decides ({@code primary}). */
    public String ownerRole(OrgKind primary) {
        OrgKind k = this == BOTH ? (primary == PROVIDER ? PROVIDER : SELLER) : this;
        return k == PROVIDER ? RoleKeys.PROVIDER_OWNER : RoleKeys.SELLER_OWNER;
    }
}
