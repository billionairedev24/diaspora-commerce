package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.application.OfferEligibilityService.Eligibility;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.DepartmentScope;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandStatus;
import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.domain.rules.FieldRule;
import com.afrotransact.catalog.domain.rules.FieldStatus;
import com.afrotransact.catalog.domain.rules.RuleField;
import com.afrotransact.catalog.domain.tax.TaxDecision;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.BrandRepository;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SellingApprovals.SellingUnavailable;
import com.afrotransact.catalog.port.SellingApprovals.Status;
import com.afrotransact.catalog.testsupport.RulesFixtures;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** S383: what the offers service asks before it attaches an offer to somebody else's page. */
class OfferEligibilityServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final UUID DEPT = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final BrandRepository brands = mock(BrandRepository.class);
    private final ListingSuppressionRepository suppressions = mock(ListingSuppressionRepository.class);
    private final SellerScopeGuard scopeGuard = mock(SellerScopeGuard.class);
    private final SellingPublishGate gate = mock(SellingPublishGate.class);
    private final ListingRulesService rules = mock(ListingRulesService.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final CategoryNode fashion = new CategoryNode(DEPT, null, "fashion", "Fashion & Accessories",
            CategoryLevel.DEPARTMENT, null, "Fashion & Accessories", 0, true, 0, null, null);
    private final CategoryNode bags = new CategoryNode(NODE, DEPT, "bags-purses", "Bags & purses",
            CategoryLevel.PRODUCT_TYPE, "custom", "Fashion & Accessories › Bags › Bags & purses", 0, true, 0, null, null);
    private OfferEligibilityServiceImpl service;
    private Product page;

    @BeforeEach
    void setUp() {
        page = livePage();
        when(products.findByVariantAtin(any())).thenReturn(Optional.of(page));
        when(categories.findById(NODE)).thenReturn(Optional.of(bags));
        when(brands.findById(BRAND)).thenReturn(Optional.of(brand(BrandStatus.APPROVED)));
        when(suppressions.isHidden(any())).thenReturn(false);
        when(scopeGuard.scopeOf(any(ListingOwner.class))).thenReturn(DepartmentScope.unrestricted());
        when(gate.enabled()).thenReturn(true);
        when(gate.factsOf(any())).thenReturn(new SellingPublishGate.Facts(NODE, "custom", page.attributes(), null));
        when(gate.evaluate(any(), any())).thenReturn(new Status("SELL", null, List.of(), List.of()));
        when(rules.forNode(any())).thenReturn(RulesFixtures.permissive());
        when(actors.current()).thenReturn(new Actor("offers-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of(CatalogPermissions.READ)));
        service = new OfferEligibilityServiceImpl(products, categories, brands, suppressions, scopeGuard, gate, rules,
                actors, "org_retail");
    }

    @Test
    void aLivePageOfAnApprovedBrandMayBeOffered() {
        Eligibility e = service.eligibility(page.variants().getFirst().atin().value(), "org_b");

        assertThat(e.canOffer()).isTrue();
        assertThat(e.blockers()).isEmpty();
        assertThat(e.productAtin()).isEqualTo(page.atin().value());
        assertThat(e.pageOwnerOrgId()).isEqualTo("org_a");
    }

    @Test
    void aPageThatIsNotLiveCannotBeOffered() {
        page = withStatus(page, ProductStatus.IN_REVIEW);
        when(products.findByVariantAtin(any())).thenReturn(Optional.of(page));

        Eligibility e = service.eligibility(page.variants().getFirst().atin().value(), "org_b");

        assertThat(e.canOffer()).isFalse();
        assertThat(e.blockers()).extracting(Blocker::code).containsExactly("catalog.page-not-live");
    }

    @Test
    void aLivePageWhoseVariantHasNoResolvedTaxDecisionIsTreatedAsNotLive() {
        Variant unresolved = new Variant(UUID.randomUUID(), new Atin("AT00000099"), "S-1", null, true, Map.of(), 899,
                "USD", 5);
        page = withVariants(livePage(), List.of(unresolved));
        when(products.findByVariantAtin(any())).thenReturn(Optional.of(page));

        Eligibility e = service.eligibility("AT00000099", "org_b");

        assertThat(e.blockers()).extracting(Blocker::code).containsExactly("catalog.page-not-live");
    }

    @Test
    void aRetiredOrRejectedBrandNeverMatchesForANewOffer() {
        for (BrandStatus status : List.of(BrandStatus.RETIRED, BrandStatus.REJECTED)) {
            when(brands.findById(BRAND)).thenReturn(Optional.of(brand(status)));

            Eligibility e = service.eligibility(page.variants().getFirst().atin().value(), "org_b");

            assertThat(e.canOffer()).as(status.name()).isFalse();
            assertThat(e.blockers()).extracting(Blocker::code).containsExactly("catalog.brand-unavailable");
        }
    }

    @Test
    void aDepartmentOutsideTheSellersScopeIsABlocker() {
        when(scopeGuard.scopeOf(any(ListingOwner.class))).thenReturn(DepartmentScope.of(Set.of("food"),
                List.of(fashion, bags)));

        Eligibility e = service.eligibility(page.variants().getFirst().atin().value(), "org_b");

        assertThat(e.canOffer()).isFalse();
        assertThat(e.blockers()).extracting(Blocker::code).containsExactly("catalog.department-not-allowed");
    }

    @Test
    void withoutASellingApprovalTheOfferIsRefusedWithAPlainMessage() {
        when(gate.evaluate(any(), any())).thenReturn(new Status("APPLY", null, List.of(), List.of()));

        Eligibility e = service.eligibility(page.variants().getFirst().atin().value(), "org_b");

        assertThat(e.canOffer()).isFalse();
        assertThat(e.blockers()).extracting(Blocker::code).containsExactly("offers.selling-approval-required");
        assertThat(e.blockers().getFirst().message()).isEqualTo("You need approval to sell this brand. Apply under Listings.");
    }

    @Test
    void sellerServiceBeingDownFailsClosedWithABlocker() {
        when(gate.evaluate(any(), any())).thenThrow(new SellingUnavailable("down", null));

        Eligibility e = service.eligibility(page.variants().getFirst().atin().value(), "org_b");

        assertThat(e.canOffer()).isFalse();
        assertThat(e.blockers()).extracting(Blocker::code).containsExactly("catalog.selling-unavailable");
    }

    @Test
    void usedConditionsAreAllowedOnlyWhereTheCategoryRulesShowTheCondition() {
        assertThat(service.eligibility(page.variants().getFirst().atin().value(), "org_b").allowedConditions())
                .containsExactlyElementsOf(Arrays.asList(ItemCondition.values()));

        List<FieldRule> fields = RulesFixtures.permissive().fields().stream()
                .map(f -> f.field() == RuleField.CONDITION ? new FieldRule(f.field(), "", f.label(), FieldStatus.HIDDEN, "") : f)
                .toList();
        CategoryRules food = new CategoryRules("food", "Food", "Food", "PKG", "Packaged", "open", "", false, fields);
        when(rules.forNode(any())).thenReturn(food);

        assertThat(service.eligibility(page.variants().getFirst().atin().value(), "org_b").allowedConditions())
                .containsExactly(ItemCondition.NEW);
    }

    @Test
    void aSellerAsksAsItselfWhateverOrganisationItNames() {
        when(actors.current()).thenReturn(new Actor("u", PrincipalType.SELLER_MEMBER, Optional.of("org_mine"), Set.of(),
                Set.of(CatalogPermissions.WRITE)));
        when(scopeGuard.scopeOf(any(ListingOwner.class))).thenAnswer(i -> {
            ListingOwner o = i.getArgument(0);
            return o.orgId().equals("org_mine") ? DepartmentScope.unrestricted()
                    : DepartmentScope.of(Set.of("food"), List.of(fashion, bags));
        });

        Eligibility e = service.eligibility(page.variants().getFirst().atin().value(), "org_someone_without_scope");

        assertThat(e.canOffer()).isTrue();
    }

    @Test
    void anUnknownVariantIsNotFound() {
        when(products.findByVariantAtin(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.eligibility("AT00000001", "org_b")).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("AT00000001");
    }

    private Product livePage() {
        VariantTax ready = new VariantTax(Map.of(), TaxStatus.READY, List.of(),
                new TaxDecision("EXEMPT", "AT.X", "S001", "0%", "v1", "r"));
        Variant v = new Variant(UUID.randomUUID(), new Atin("AT00000002"), "S-2", "4006381333931", false, Map.of(), 899,
                "USD", 5, null, List.of(), false, ready);
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), new ListingOwner(ListingOwnerKind.SELLER, "org_a"),
                NODE, null, "custom", "Ankara tote", Map.of("brand_id", BRAND.toString(), "brand", "Adire House"),
                List.of(), "", List.of(v), ProductStatus.LIVE, null, NOW, NOW, 0);
    }

    private static Product withStatus(Product p, ProductStatus status) {
        return new Product(p.id(), p.atin(), p.owner(), p.categoryId(), p.categoryRequestId(), p.productTypeCode(),
                p.title(), p.attributes(), p.images(), p.variationTheme(), p.variants(), status, null, NOW, NOW, 0);
    }

    private static Product withVariants(Product p, List<Variant> variants) {
        return p.withVariants(variants);
    }

    private static Brand brand(BrandStatus status) {
        return new Brand(BRAND, "Adire House", "adirehouse", status, null, null, NOW, NOW, 0, null, null, null, Set.of());
    }
}
