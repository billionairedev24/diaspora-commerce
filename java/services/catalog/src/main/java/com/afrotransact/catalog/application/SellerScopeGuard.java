package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.DepartmentScope;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.port.SellerScopes.OrgScope;
import com.afrotransact.platform.security.Actor;

/**
 * The departments a seller may list in, as seller-service records them. Every create, edit, draft, bulk-import and
 * size-chart path asks this first; the UI hides what is not allowed, this refuses it. AfroTransact Retail and staff
 * are unrestricted. When seller-service cannot be asked the call fails with
 * {@link com.afrotransact.catalog.port.SellerScopes.ScopeUnavailable} (503): the catalog never guesses.
 */
public interface SellerScopeGuard {

    /** The scope of the organisation that owns a listing (a bulk-upload job has an owner, not an actor). */
    DepartmentScope scopeOf(ListingOwner owner);

    /** The scope of the caller: staff and service clients are unrestricted, sellers get their organisation's. */
    DepartmentScope scopeOf(Actor actor);

    /** The caller's organisation scope as seller-service gave it, with its department requests (sellers only). */
    OrgScope orgScopeOf(Actor actor);
}
