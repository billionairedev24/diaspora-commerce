package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Set;

import com.afrotransact.catalog.domain.DepartmentScope;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.port.SellerScopes.OrgScope;
import com.afrotransact.platform.security.Actor;

/** A guard for tests that are not about department scope: everybody may list everywhere. */
final class UnrestrictedScopeGuard implements SellerScopeGuard {

    @Override
    public DepartmentScope scopeOf(ListingOwner owner) {
        return DepartmentScope.unrestricted();
    }

    @Override
    public DepartmentScope scopeOf(Actor actor) {
        return DepartmentScope.unrestricted();
    }

    @Override
    public OrgScope orgScopeOf(Actor actor) {
        return new OrgScope(true, Set.of(), List.of());
    }
}
