package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.DefaultProductDraftPolicy;
import com.afrotransact.catalog.domain.DepartmentNotAllowed;
import com.afrotransact.catalog.domain.DepartmentScope;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.sizing.DefaultSizeChartPolicy;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.CategoryRequestRepository;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.ProductDraftRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SellerScopes.OrgScope;
import com.afrotransact.catalog.port.SizeChartRepository;
import com.afrotransact.catalog.port.TaxonomyAuditRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import org.junit.jupiter.api.Test;

/**
 * A food-only seller is refused, in the seller's words, on every path that creates or changes something in the
 * Fashion department: the product form, drafts, category requests and the product tree they pick from.
 */
class ScopedOperationsTest {

    private static final UUID FASHION = UUID.randomUUID();
    private static final UUID WOMEN = UUID.randomUUID();
    private static final UUID DRESSES = UUID.randomUUID();
    private static final UUID GROCERIES = UUID.randomUUID();
    private static final ListingOwner OWNER = new ListingOwner(ListingOwnerKind.SELLER, "org_food");

    private static final List<CategoryNode> TREE = List.of(
            new CategoryNode(FASHION, null, "fashion", "Fashion", CategoryLevel.DEPARTMENT, null, "Fashion", 0, true, 0,
                    null, null),
            new CategoryNode(WOMEN, FASHION, "fashion-women", "Women", CategoryLevel.CATEGORY, null, "Fashion › Women",
                    0, true, 0, null, null),
            new CategoryNode(DRESSES, WOMEN, "fashion-women-dresses", "Dresses", CategoryLevel.PRODUCT_TYPE, "dress",
                    "Fashion › Women › Dresses", 0, true, 0, null, null),
            new CategoryNode(GROCERIES, null, "groceries", "Groceries", CategoryLevel.DEPARTMENT, null, "Groceries", 1,
                    true, 0, null, null));

    private final SellerScopeGuard guard = new FixedScopeGuard(DepartmentScope.of(Set.of("groceries"), TREE));
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final Actor actor = new Actor("user_1", PrincipalType.SELLER_MEMBER, Optional.of("org_food"),
            Set.of("seller.owner"), Set.of(CatalogPermissions.WRITE));
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC);

    ScopedOperationsTest() {
        when(permissions.require(CatalogPermissions.WRITE)).thenReturn(actor);
        when(categories.findById(DRESSES)).thenReturn(Optional.of(TREE.get(2)));
        when(categories.findAllActive()).thenReturn(TREE);
    }

    private CreateProductCommand create(UUID categoryId) {
        return new CreateProductCommand(categoryId, null, "A dress", Map.of(), List.of(), "", List.of(), "USD", null,
                null, null, null);
    }

    @Test
    void theProductFormRefusesACategoryOfAnotherDepartment() {
        ProductRepository products = mock(ProductRepository.class);
        ProductWriterImpl writer = new ProductWriterImpl(products, categories, mock(CategoryRequestRepository.class),
                mock(ProductDraftRepository.class), mock(IdentifierIssuer.class), new DefaultProductDraftPolicy(),
                new DefaultSizeChartPolicy(), mock(SizeChartRepository.class), mock(ProductEvents.class),
                mock(BrandResolver.class), guard, clock);
        assertThatThrownBy(() -> writer.create(OWNER, create(DRESSES))).isInstanceOfSatisfying(
                DepartmentNotAllowed.class, e -> assertThat(e.getMessage())
                        .contains("not set up to sell in Fashion"));
        verify(products, never()).insert(any());
    }

    @Test
    void aDraftCannotBeSavedInAnotherDepartment() {
        ProductDraftRepository drafts = mock(ProductDraftRepository.class);
        OwnerResolver owners = mock(OwnerResolver.class);
        when(owners.ownerFor(actor)).thenReturn(OWNER);
        ProductDraftServiceImpl service = new ProductDraftServiceImpl(drafts, mock(ProductRepository.class),
                permissions, mock(ResourceAccessChecker.class), owners, guard, clock);
        assertThatThrownBy(() -> service.create(null, "Draft", "Fashion › Women", "{}"))
                .isInstanceOf(DepartmentNotAllowed.class);
        verify(drafts, never()).insert(any());
    }

    @Test
    void aCategoryRequestInsideAnotherDepartmentGoesThroughTheDepartmentRequestInstead() {
        CategoryRequestRepository requests = mock(CategoryRequestRepository.class);
        CategoryServiceImpl service = new CategoryServiceImpl(categories, requests, mock(ProductRepository.class),
                mock(IdentifierIssuer.class), permissions, mock(OwnerResolver.class), mock(DomainEventPublisher.class),
                mock(ProductEvents.class), mock(TaxonomyAdminService.class), mock(TaxonomyAuditRepository.class),
                guard, clock);
        assertThatThrownBy(() -> service.request("Fashion › Women", "Kaftans", "A kaftan", "Hand sewn"))
                .isInstanceOf(DepartmentNotAllowed.class);
        verify(requests, never()).insert(any());
    }

    @Test
    void theSellersTreeAndProductTypesHoldOnlyTheirDepartments() {
        when(categories.findAllProductTypes()).thenReturn(List.of());
        CategoryServiceImpl service = new CategoryServiceImpl(categories, mock(CategoryRequestRepository.class),
                mock(ProductRepository.class), mock(IdentifierIssuer.class), permissions, mock(OwnerResolver.class),
                mock(DomainEventPublisher.class), mock(ProductEvents.class), mock(TaxonomyAdminService.class),
                mock(TaxonomyAuditRepository.class), guard, clock);
        assertThat(service.treeForSeller()).extracting(CategoryNode::code)
                .containsExactly("groceries");
    }

    /** Hands out one fixed scope. */
    private record FixedScopeGuard(DepartmentScope scope) implements SellerScopeGuard {

        @Override
        public DepartmentScope scopeOf(ListingOwner owner) {
            return scope;
        }

        @Override
        public DepartmentScope scopeOf(Actor actor) {
            return scope;
        }

        @Override
        public OrgScope orgScopeOf(Actor actor) {
            return new OrgScope(false, scope.allowedCodes(), List.of());
        }
    }
}
