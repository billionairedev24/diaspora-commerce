package com.afrotransact.catalog.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.DepartmentScope;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.SellerScopes.OrgScope;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellerCatalogScopeServiceImpl implements SellerCatalogScopeService {

    private final SellerScopeGuard guard;
    private final CategoryRepository categories;
    private final PermissionChecker permissions;

    SellerCatalogScopeServiceImpl(SellerScopeGuard guard, CategoryRepository categories,
                                  PermissionChecker permissions) {
        this.guard = guard;
        this.categories = categories;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public ScopeView mine() {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        DepartmentScope scope = guard.scopeOf(actor);
        List<CategoryNode> tree = categories.findAllActive();
        DepartmentScope index = DepartmentScope.of(Set.of(), tree);
        Set<String> sized = sizedTypes();
        Map<UUID, Boolean> departmentSized = new HashMap<>();
        for (CategoryNode n : tree) {
            if (n.level() == CategoryLevel.PRODUCT_TYPE && sized.contains(n.productTypeCode())) {
                index.departmentOf(n).ifPresent(d -> departmentSized.put(d.id(), true));
            }
        }
        List<DepartmentView> departments = tree.stream().filter(n -> n.level() == CategoryLevel.DEPARTMENT)
                .filter(scope::allows)
                .map(d -> new DepartmentView(d.id(), d.code(), d.name(), departmentSized.getOrDefault(d.id(), false)))
                .toList();
        boolean exempt = scope.isUnrestricted();
        List<RequestView> requests = exempt || actor.type() == PrincipalType.STAFF ? List.of() : requests(actor, tree);
        return new ScopeView(exempt, departments, departments.stream().anyMatch(DepartmentView::hasSizing), requests);
    }

    private List<RequestView> requests(Actor actor, List<CategoryNode> tree) {
        OrgScope org = guard.orgScopeOf(actor);
        Map<String, String> names = new HashMap<>();
        tree.stream().filter(n -> n.level() == CategoryLevel.DEPARTMENT).forEach(d -> names.put(d.code(), d.name()));
        return org.requests().stream().map(r -> new RequestView(r.departmentCode(),
                names.getOrDefault(r.departmentCode(), r.departmentCode()), r.status(), r.needs(), r.createdAt()))
                .toList();
    }

    private Set<String> sizedTypes() {
        Set<String> codes = new HashSet<>();
        for (ProductType t : categories.findAllProductTypes()) {
            if (t.sizing().isPresent()) {
                codes.add(t.code());
            }
        }
        return codes;
    }
}
