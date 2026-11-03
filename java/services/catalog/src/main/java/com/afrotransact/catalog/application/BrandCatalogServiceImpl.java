package com.afrotransact.catalog.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandNames;
import com.afrotransact.catalog.domain.brand.BrandOption;
import com.afrotransact.catalog.port.BrandRepository;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BrandCatalogServiceImpl implements BrandCatalogService {

    private static final int MAX_LIMIT = 50;

    private final BrandRepository brands;
    private final CategoryRepository categories;
    private final BrandResolver resolver;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final OwnerResolver owners;

    BrandCatalogServiceImpl(BrandRepository brands, CategoryRepository categories, BrandResolver resolver,
                            PermissionChecker permissions, ResourceAccessChecker access, OwnerResolver owners) {
        this.brands = brands;
        this.categories = categories;
        this.resolver = resolver;
        this.permissions = permissions;
        this.access = access;
        this.owners = owners;
    }

    @Override
    @Transactional(readOnly = true)
    public BrandChoices choices(String productType, String category, String q, int limit) {
        ListingOwner owner = owner(CatalogRelations.ORG_VIEW_PRODUCTS);
        BrandScopes scopes = new BrandScopes(categories.findAll());
        List<CategoryNode> nodes = scopes.nodesOf(productType, category);
        Set<UUID> covering = new LinkedHashSet<>();
        Set<UUID> departments = new LinkedHashSet<>();
        for (CategoryNode n : nodes) {
            covering.addAll(scopes.covering(n));
            departments.add(scopes.department(n).id());
        }
        List<BrandOption> options = brands.options(covering, owner.orgId(), BrandNames.normalise(q),
                Math.clamp(limit, 1, MAX_LIMIT));
        CategoryNode department = departments.size() == 1
                ? scopes.department(nodes.getFirst()) : null;
        return new BrandChoices(department == null ? null : department.id(),
                department == null ? null : department.name(),
                options.stream().map(o -> choice(o, owner.orgId())).toList());
    }

    @Override
    @Transactional
    public BrandCreated create(String name, String productType, String category) {
        ListingOwner owner = owner(CatalogRelations.ORG_MANAGE_PRODUCTS);
        BrandScopes scopes = new BrandScopes(categories.findAll());
        List<CategoryNode> nodes = scopes.nodesOf(productType, category);
        Set<UUID> covering = new LinkedHashSet<>();
        Set<UUID> departments = new LinkedHashSet<>();
        for (CategoryNode n : nodes) {
            covering.addAll(scopes.covering(n));
            departments.add(scopes.department(n).id());
        }
        BrandResolver.Matched match = resolver.matchOrCreate(owner.orgId(), covering, departments, name);
        Brand b = match.brand();
        long own = brands.options(covering, owner.orgId(), BrandNames.normalise(b.name()), MAX_LIMIT).stream()
                .filter(o -> o.id().equals(b.id())).mapToLong(BrandOption::ownProducts).findFirst().orElse(0);
        return new BrandCreated(new BrandChoice(b.id(), b.name(), b.status(), owner.orgId().equals(b.createdByOrg()),
                own, 0), match.created());
    }

    private static BrandChoice choice(BrandOption o, String orgId) {
        return new BrandChoice(o.id(), o.name(), o.status(), orgId.equals(o.createdByOrg()) || o.ownProducts() > 0,
                o.ownProducts(), o.products());
    }

    private ListingOwner owner(String relation) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ListingOwner owner = owners.ownerFor(actor);
        access.requireForOrg(CatalogPermissions.WRITE, relation, owner.orgId());
        return owner;
    }
}
