package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.DepartmentScope;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.SellerScopes;
import com.afrotransact.catalog.port.SellerScopes.OrgScope;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.springframework.stereotype.Component;

@Component
class SellerScopeGuardImpl implements SellerScopeGuard {

    private final SellerScopes sellers;
    private final CategoryRepository categories;
    private final OwnerResolver owners;

    SellerScopeGuardImpl(SellerScopes sellers, CategoryRepository categories, OwnerResolver owners) {
        this.sellers = sellers;
        this.categories = categories;
        this.owners = owners;
    }

    @Override
    public DepartmentScope scopeOf(ListingOwner owner) {
        if (owner.kind() == ListingOwnerKind.PLATFORM) {
            return DepartmentScope.unrestricted();
        }
        OrgScope scope = sellers.of(owner.orgId());
        if (scope.exempt()) {
            return DepartmentScope.unrestricted();
        }
        return DepartmentScope.of(scope.departments(), categories.findAll());
    }

    @Override
    public DepartmentScope scopeOf(Actor actor) {
        if (exempt(actor)) {
            return DepartmentScope.unrestricted();
        }
        return scopeOf(owners.ownerFor(actor));
    }

    @Override
    public OrgScope orgScopeOf(Actor actor) {
        ListingOwner owner = owners.ownerFor(actor);
        return sellers.of(owner.orgId());
    }

    private static boolean exempt(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
    }
}
