package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;

/**
 * Sellers list for their own organisation. Staff with {@code catalog:retail} list for AfroTransact Retail,
 * the first-party seller of record. Nobody picks "sold by" in a form.
 */
public class RetailAwareOwnerResolver implements OwnerResolver {

    public static final String RETAIL_PERMISSION = "catalog:retail";

    private final String retailOrgId;

    public RetailAwareOwnerResolver(String retailOrgId) {
        this.retailOrgId = retailOrgId;
    }

    @Override
    public ListingOwner ownerFor(Actor actor) {
        if (actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE) {
            if (!actor.can(RETAIL_PERMISSION)) {
                throw new ForbiddenActionException("catalog.not-retail", "Staff list products only for AfroTransact Retail");
            }
            return new ListingOwner(ListingOwnerKind.PLATFORM, retailOrgId);
        }
        String org = actor.orgId().orElseThrow(() ->
                new ForbiddenActionException("catalog.no-org", "Only approved seller organisations can list products"));
        return new ListingOwner(retailOrgId.equals(org) ? ListingOwnerKind.PLATFORM : ListingOwnerKind.SELLER, org);
    }
}
