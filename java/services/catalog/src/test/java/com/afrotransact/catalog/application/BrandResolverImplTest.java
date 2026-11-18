package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandStatus;
import com.afrotransact.catalog.domain.rules.RuleAwareType;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyAuditEntry;
import com.afrotransact.catalog.port.BrandRepository;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.TaxonomyAuditRepository;
import com.afrotransact.catalog.testsupport.RulesFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Adding a brand the seller typed: no duplicates, no hijack, limits, review trail, and where "no brand" is allowed. */
class BrandResolverImplTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final UUID FASHION = UUID.randomUUID();
    private static final UUID BOOKS = UUID.randomUUID();
    private static final ListingOwner SELLER = new ListingOwner(ListingOwnerKind.SELLER, "org-1");

    private final BrandRepository brands = mock(BrandRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final TaxonomyAuditRepository audit = mock(TaxonomyAuditRepository.class);
    private BrandResolverImpl resolver;

    @BeforeEach
    void setUp() {
        resolver = new BrandResolverImpl(brands, categories, audit, Clock.fixed(NOW, ZoneOffset.UTC), 3);
        when(brands.insert(any(Brand.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static Brand brand(String name, BrandStatus status, String org, UUID node) {
        return Brand.create(name, status, org, Set.of(node), NOW.minusSeconds(3600));
    }

    @Test
    void aTypedNameThatIsAlreadyInTheRegistryIsReusedWhateverTheCaseAccentsOrPunctuation() {
        Brand nike = brand("Nike", BrandStatus.APPROVED, null, FASHION);
        when(brands.findLiveByKey("nike")).thenReturn(List.of(nike));

        BrandResolver.Matched m = resolver.matchOrCreate("org-1", Set.of(FASHION), Set.of(FASHION), "  N.I.K.E ");

        assertThat(m.created()).isFalse();
        assertThat(m.brand()).isSameAs(nike);
        verify(brands, never()).insert(any());
    }

    @Test
    void anotherSellersPendingBrandIsNeitherReusedNorLeaked() {
        Brand theirs = brand("Adire House", BrandStatus.PENDING, "org-2", FASHION);
        when(brands.findLiveByKey("adirehouse")).thenReturn(List.of(theirs));

        BrandResolver.Matched m = resolver.matchOrCreate("org-1", Set.of(FASHION), Set.of(FASHION), "Adire House");

        assertThat(m.created()).isTrue();
        assertThat(m.brand().createdByOrg()).isEqualTo("org-1");
        assertThat(m.brand().id()).isNotEqualTo(theirs.id());
    }

    @Test
    void aVerifiedBrandIsReusedNotCopiedSoNobodyCanSquatItsName() {
        Brand verified = brand("Gucci", BrandStatus.APPROVED, null, FASHION);
        when(brands.findLiveByKey("gucci")).thenReturn(List.of(verified));

        BrandResolver.Matched m = resolver.matchOrCreate("org-1", Set.of(FASHION), Set.of(FASHION), "GUCCI");

        assertThat(m.brand().status()).isEqualTo(BrandStatus.APPROVED);
        assertThat(m.created()).isFalse();
    }

    @Test
    void aSameNameInAnUnrelatedDepartmentIsADifferentBrand() {
        Brand apple = brand("Apple", BrandStatus.APPROVED, null, UUID.randomUUID());
        when(brands.findLiveByKey("apple")).thenReturn(List.of(apple));

        BrandResolver.Matched m = resolver.matchOrCreate("org-1", Set.of(BOOKS), Set.of(BOOKS), "Apple");

        assertThat(m.created()).isTrue();
    }

    @Test
    void aNewBrandIsPendingAttributedToTheOrganisationAndAudited() {
        Brand nike = brand("Nike", BrandStatus.APPROVED, null, FASHION);
        when(brands.findLiveWithKeyLength(anyInt(), anyInt())).thenReturn(List.of(nike));

        BrandResolver.Matched m = resolver.matchOrCreate("org-1", Set.of(FASHION), Set.of(FASHION), "  Nikee ");

        assertThat(m.created()).isTrue();
        assertThat(m.brand().name()).isEqualTo("Nikee");
        assertThat(m.brand().status()).isEqualTo(BrandStatus.PENDING);
        assertThat(m.brand().createdByOrg()).isEqualTo("org-1");
        verify(brands).recordSimilar(m.brand().id(), List.of(nike.id()));
        ArgumentCaptor<TaxonomyAuditEntry> entry = ArgumentCaptor.forClass(TaxonomyAuditEntry.class);
        verify(audit).append(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("BRAND_PROPOSED");
        assertThat(entry.getValue().actorId()).isEqualTo("org:org-1");
        assertThat(entry.getValue().subjectLabel()).isEqualTo("Nikee");
        assertThat(entry.getValue().details()).containsEntry("similar", List.of("Nike"));
    }

    @Test
    void theDailyCapStopsAFloodButNeverBlocksAnExistingBrand() {
        when(brands.countCreatedSince(anyString(), any(Instant.class))).thenReturn(3L);
        Brand nike = brand("Nike", BrandStatus.APPROVED, null, FASHION);
        when(brands.findLiveByKey("nike")).thenReturn(List.of(nike));

        assertThat(resolver.matchOrCreate("org-1", Set.of(FASHION), Set.of(FASHION), "Nike").created()).isFalse();
        assertThatThrownBy(() -> resolver.matchOrCreate("org-1", Set.of(FASHION), Set.of(FASHION), "Brand New"))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fieldErrors().get("attributes.brand"))
                        .contains("3 new brands a day"));
        verify(brands, never()).insert(any());
    }

    @Test
    void theDailyWindowIsTheLast24Hours() {
        resolver.matchOrCreate("org-1", Set.of(FASHION), Set.of(FASHION), "Brand New");

        verify(brands).countCreatedSince("org-1", NOW.minusSeconds(86_400));
    }

    @Test
    void tooManyBrandsAwaitingReviewStopsNewOnes() {
        when(brands.countPendingOf("org-1")).thenReturn((long) BrandResolverImpl.MAX_PENDING_PER_ORG);

        assertThatThrownBy(() -> resolver.matchOrCreate("org-1", Set.of(FASHION), Set.of(FASHION), "Brand New"))
                .isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void linksEmailsAndProfanityAreRefusedBeforeAnythingIsStored() {
        for (String bad : List.of("shop@x.com", "www.cheap.ng", "Fuck Brand")) {
            assertThatThrownBy(() -> resolver.matchOrCreate("org-1", Set.of(FASHION), Set.of(FASHION), bad))
                    .isInstanceOf(CatalogRuleViolation.class);
        }
        verify(brands, never()).insert(any());
        verify(audit, never()).append(any());
    }

    @Test
    void similarNamesAreOnlyThoseTheOrganisationMaySeeInTheCategory() {
        Brand nike = brand("Nike", BrandStatus.APPROVED, null, FASHION);
        Brand theirs = brand("Nikes Shop", BrandStatus.PENDING, "org-2", FASHION);
        Brand otherDepartment = brand("Nikea", BrandStatus.APPROVED, null, BOOKS);
        when(brands.findLiveWithKeyLength(anyInt(), anyInt()))
                .thenReturn(List.of(nike, theirs, otherDepartment));

        assertThat(resolver.similar("org-1", Set.of(FASHION), "nikee")).containsExactly(nike);
    }

    @Test
    void noBrandIsAllowedWhereTheRuleAllowsItAndRefusedWhereTheBrandIsRequired() {
        ProductType grocery = RuleAwareType.apply(typeOf("food"), RulesFixtures.book().forCategory("rice-grains").orElseThrow());
        ProductType electronics = RuleAwareType.apply(typeOf("electronics"),
                RulesFixtures.book().forCategory("phones").orElseThrow());

        Map<String, String> ok = resolver.resolve(SELLER, null, grocery, Map.of("brand_reason", "UNBRANDED"), Map.of());
        assertThat(ok).containsEntry("brand_reason", "UNBRANDED");
        assertThatThrownBy(() -> resolver.resolve(SELLER, null, electronics, Map.of("brand_reason", "UNBRANDED"), Map.of()))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fieldErrors().get("attributes.brand"))
                        .contains("add it as a new brand"));
        assertThatThrownBy(() -> resolver.resolve(SELLER, null, electronics, Map.of("brand", "Generic"), Map.of()))
                .isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void aBookKeepsNoBrandAtAll() {
        ProductType book = RuleAwareType.apply(typeOf("book"), RulesFixtures.book().forCategory("printed-books").orElseThrow());

        assertThat(resolver.resolve(SELLER, null, book, Map.of("brand", "Penguin", "brand_id", "x"), Map.of()))
                .doesNotContainKeys("brand", "brand_id");
    }

    private static ProductType typeOf(String code) {
        return new ProductType(code, code, List.of(), List.of(), IdentifierRule.GTIN);
    }
}
