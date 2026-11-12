package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Set;

import com.afrotransact.catalog.domain.DepartmentScope;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.port.SellerScopes.OrgScope;
import com.afrotransact.platform.security.Actor;
import org.springframework.stereotype.Component;

/**
 * Owner decision: a seller may sell in any department or category. Nothing is looked up in seller-service any more, so
 * the seller's category tree, drafts, the product form, bulk upload and offers can never fail (or refuse) because of
 * the departments a store registered for. What still gates a listing is its category's risk (selling approvals, the
 * SellingPublishGate) and the tax gate, neither of which reads a department scope.
 */
@Component
class SellerScopeGuardImpl implements SellerScopeGuard {

    private static final OrgScope EVERY_DEPARTMENT = new OrgScope(true, Set.of(), List.of());

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
        return EVERY_DEPARTMENT;
    }
}
