package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
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

import com.afrotransact.catalog.application.GtinExemptionService.ExemptionView;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.ExemptionRequirement;
import com.afrotransact.catalog.domain.ExemptionStatus;
import com.afrotransact.catalog.domain.GtinExemptionRequest;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.port.GtinExemptionRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** S387: items without a barcode. Who needs staff approval, the brand plus part number match, and the decision. */
class GtinExemptionServiceImplTest {

    // A Friday: two business days later is Tuesday.
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final String BRAND_ID = UUID.randomUUID().toString();
    private static final CatalogPolicy POLICY = new CatalogPolicy(List.of("02"), "mpn", 2, 3, 200, 3,
            List.of("PERFUME"), true, null, null, 0);

    private final ProductRepository products = mock(ProductRepository.class);
    private final GtinExemptionRepository exemptions = mock(GtinExemptionRepository.class);
    private final CatalogSettingsService settings = mock(CatalogSettingsService.class);
    private final ChangeRequestEvents events = mock(ChangeRequestEvents.class);
    private final ProductEvents productEvents = mock(ProductEvents.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private GtinExemptionServiceImpl service;

    @BeforeEach
    void setUp() {
        when(settings.current()).thenReturn(POLICY);
        when(exemptions.insert(any())).thenAnswer(i -> i.getArgument(0));
        when(exemptions.update(any())).thenAnswer(i -> i.getArgument(0));
        when(products.update(any())).thenAnswer(i -> i.getArgument(0));
        service = new GtinExemptionServiceImpl(products, exemptions, settings, events, productEvents, permissions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- who needs staff approval ---------------------------------------------------------------------------------

    @Test
    void anOpenCategoryAndABrandWithoutBarcodesElsewhereGetsItsAtinAtOnce() {
        when(products.countPagesWithBarcode(BRAND_ID)).thenReturn(0L);

        assertThat(service.requirement(rules("HANDMADE", "open"), "custom", Map.of(BrandKeys.ID, BRAND_ID))).isEmpty();
    }

    @Test
    void aBrandGatedCategoryNeedsStaffApproval() {
        assertThat(service.requirement(rules("CLO", "brand_gated"), "dress", Map.of()))
                .contains(ExemptionRequirement.BRAND_GATED_CATEGORY);
    }

    @Test
    void aCategoryOrProductTypeStaffAddedToTheGatedListNeedsStaffApproval() {
        assertThat(service.requirement(rules("PERFUME", "open"), "fragrance", Map.of()))
                .contains(ExemptionRequirement.BRAND_GATED_CATEGORY);
        assertThat(service.requirement(rules("BEAUTY", "open"), "PERFUME", Map.of()))
                .contains(ExemptionRequirement.BRAND_GATED_CATEGORY);
    }

    @Test
    void aKnownGs1BrandNeedsStaffApprovalFromTheConfiguredNumberOfPages() {
        when(products.countPagesWithBarcode(BRAND_ID)).thenReturn(3L);

        assertThat(service.requirement(rules("HANDMADE", "open"), "custom", Map.of(BrandKeys.ID, BRAND_ID)))
                .contains(ExemptionRequirement.KNOWN_GS1_BRAND);

        when(products.countPagesWithBarcode(BRAND_ID)).thenReturn(2L);
        assertThat(service.requirement(rules("HANDMADE", "open"), "custom", Map.of(BrandKeys.ID, BRAND_ID))).isEmpty();
    }

    @Test
    void theKnownBrandRuleCanBeSwitchedOffAndNeedsABrand() {
        when(settings.current()).thenReturn(new CatalogPolicy(List.of(), "mpn", 2, 3, 200, 1, List.of(), false, null, null, 0));
        when(products.countPagesWithBarcode(BRAND_ID)).thenReturn(50L);

        assertThat(service.requirement(rules("HANDMADE", "open"), "custom", Map.of(BrandKeys.ID, BRAND_ID))).isEmpty();

        when(settings.current()).thenReturn(POLICY);
        assertThat(service.requirement(rules("HANDMADE", "open"), "custom", Map.of())).isEmpty();
        assertThat(service.requirement(rules("HANDMADE", "open"), "custom", Map.of(BrandKeys.ID, " "))).isEmpty();
    }

    // ---- brand plus part number match -----------------------------------------------------------------------------

    @Test
    void theSamePartNumberOfTheSameBrandFindsTheExistingPage() {
        Product existing = page(ProductStatus.LIVE, "AT10000001");
        when(products.findByBrandAndMpn(BRAND_ID, "mpn", "AB123")).thenReturn(List.of(existing));

        assertThat(service.pageWithSamePartNumber(Map.of(BrandKeys.ID, BRAND_ID, "mpn", "ab-123"))).contains(existing);
    }

    @Test
    void noMatchWithoutABrandOrAPartNumber() {
        assertThat(service.pageWithSamePartNumber(Map.of("mpn", "AB123"))).isEmpty();
        assertThat(service.pageWithSamePartNumber(Map.of(BrandKeys.ID, BRAND_ID))).isEmpty();
        assertThat(service.pageWithSamePartNumber(Map.of(BrandKeys.ID, BRAND_ID, "mpn", " - "))).isEmpty();
        verify(products, never()).findByBrandAndMpn(any(), any(), any());
    }

    // ---- the request ----------------------------------------------------------------------------------------------

    @Test
    void aRequestIsFiledWithTheTwoBusinessDaySlaAndAnnounced() {
        Product page = page(ProductStatus.PENDING_EXEMPTION, "AT10000001");

        GtinExemptionRequest saved = service.requested(page, "  Handmade, no barcode  ", ExemptionRequirement.KNOWN_GS1_BRAND);

        assertThat(saved.status()).isEqualTo(ExemptionStatus.PENDING);
        assertThat(saved.requestedByOrg()).isEqualTo("org_a");
        assertThat(saved.sellerReason()).isEqualTo("Handmade, no barcode");
        assertThat(saved.slaDueAt()).isEqualTo(Instant.parse("2026-11-17T10:00:00Z"));
        verify(events).exemptionRequested(saved, NOW);
    }

    @Test
    void aBlankSellerReasonIsStoredAsNone() {
        assertThat(service.requested(page(ProductStatus.PENDING_EXEMPTION, "AT10000001"), "  ",
                ExemptionRequirement.BRAND_GATED_CATEGORY).sellerReason()).isNull();
    }

    // ---- the decision ---------------------------------------------------------------------------------------------

    @Test
    void approvingMovesThePageToReviewNeverToLiveAndRecordsWhoAndWhy() {
        as(staff());
        Product page = page(ProductStatus.PENDING_EXEMPTION, "AT10000001");
        GtinExemptionRequest waiting = waiting(page);

        ExemptionView view = service.decide(waiting.id(), true, "Handmade item, checked the maker's site");

        assertThat(view.request().status()).isEqualTo(ExemptionStatus.APPROVED);
        assertThat(view.request().decidedBy()).isEqualTo("staff_1");
        assertThat(view.request().reason()).isEqualTo("Handmade item, checked the maker's site");
        ArgumentCaptor<Product> stored = ArgumentCaptor.forClass(Product.class);
        verify(products).update(stored.capture());
        assertThat(stored.getValue().status()).isEqualTo(ProductStatus.IN_REVIEW);
        verify(productEvents).publish(eq(CatalogEvents.PRODUCT_UPDATED), any(Product.class));
        verify(events).exemptionDecided(view.request(), NOW);
    }

    @Test
    void rejectingRejectsThePageWithTheReasonForTheSeller() {
        as(staff());
        Product page = page(ProductStatus.PENDING_EXEMPTION, "AT10000001");
        GtinExemptionRequest waiting = waiting(page);

        ExemptionView view = service.decide(waiting.id(), false, "This brand always has a barcode");

        assertThat(view.request().status()).isEqualTo(ExemptionStatus.REJECTED);
        ArgumentCaptor<Product> stored = ArgumentCaptor.forClass(Product.class);
        verify(products).update(stored.capture());
        assertThat(stored.getValue().status()).isEqualTo(ProductStatus.REJECTED);
        assertThat(stored.getValue().moderationNote()).isEqualTo("This brand always has a barcode");
        verify(productEvents).publish(eq(CatalogEvents.PRODUCT_REJECTED), any(Product.class));
    }

    @Test
    void aDecisionNeedsAReasonOfThreeCharacters() {
        as(staff());
        Product page = page(ProductStatus.PENDING_EXEMPTION, "AT10000001");
        GtinExemptionRequest waiting = waiting(page);

        for (String reason : new String[] {null, "", "  ", "ok"}) {
            assertThatThrownBy(() -> service.decide(waiting.id(), true, reason)).isInstanceOfSatisfying(
                    CatalogRuleViolation.class, e -> assertThat(e.fieldErrors()).containsKey("reason"));
        }
        verify(exemptions, never()).update(any());
        verify(products, never()).update(any());
    }

    @Test
    void aRequestThatWasAlreadyDecidedIsAConflictAndTheSecondDecisionChangesNothing() {
        as(staff());
        Product page = page(ProductStatus.IN_REVIEW, "AT10000001");
        GtinExemptionRequest done = waiting(page).approved("staff_2", "Fine by me", NOW);
        when(exemptions.findById(done.id())).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.decide(done.id(), false, "Changed my mind")).isInstanceOfSatisfying(
                ConflictException.class, e -> assertThat(e.code()).isEqualTo("catalog.exemption-decided"));
        verify(products, never()).update(any());
    }

    @Test
    void twoStaffDecidingAtTheSameTimeLeaveOneDecision() {
        as(staff());
        Product page = page(ProductStatus.PENDING_EXEMPTION, "AT10000001");
        GtinExemptionRequest waiting = waiting(page);
        when(exemptions.update(any())).thenThrow(new StaleVersionException("decided meanwhile"));

        assertThatThrownBy(() -> service.decide(waiting.id(), true, "Handmade item")).isInstanceOfSatisfying(
                ConflictException.class, e -> assertThat(e.code()).isEqualTo("catalog.exemption-decided"));
        verify(products, never()).update(any());
    }

    @Test
    void anUnknownRequestIsNotFound() {
        as(staff());
        UUID id = UUID.randomUUID();
        when(exemptions.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decide(id, true, "Handmade item")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void decidingNeedsTheModeratePermission() {
        when(permissions.require(CatalogPermissions.MODERATE)).thenThrow(new ForbiddenActionException("forbidden", "no"));

        assertThatThrownBy(() -> service.decide(UUID.randomUUID(), true, "Handmade item"))
                .isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> service.queue(ExemptionStatus.PENDING, 0, 20)).isInstanceOf(ForbiddenActionException.class);
        verify(exemptions, never()).update(any());
        verify(exemptions, never()).search(any(), anyInt(), anyInt());
    }

    // ---- queues ---------------------------------------------------------------------------------------------------

    @Test
    void theStaffQueueShowsNamesAgeOverdueAndDuplicateHints() {
        as(staff());
        Product page = new Product(UUID.randomUUID(), new Atin("AT10000001"), new ListingOwner(ListingOwnerKind.SELLER, "org_a"),
                null, null, "custom", "Hand carved stool teak", Map.of(BrandKeys.ID, BRAND_ID, BrandKeys.NAME, "Adire Lagos",
                "mpn", "AB-123"), List.of(), "", List.of(variant()), ProductStatus.PENDING_EXEMPTION, null, NOW, NOW, 0);
        Product twin = new Product(UUID.randomUUID(), new Atin("AT10000009"), new ListingOwner(ListingOwnerKind.SELLER, "org_c"),
                null, null, "custom", "Hand carved teak stool", Map.of(BrandKeys.ID, BRAND_ID, "mpn", "ab123"), List.of(), "",
                List.of(variant()), ProductStatus.LIVE, null, NOW, NOW, 0);
        GtinExemptionRequest old = GtinExemptionRequest.filed(page.id(), "AT10000001", "org_a", "Handmade",
                ExemptionRequirement.KNOWN_GS1_BRAND, NOW.minusSeconds(5 * 86_400), NOW.minusSeconds(3 * 86_400));
        when(products.findById(page.id())).thenReturn(Optional.of(page));
        when(products.findByBrand(eq(BRAND_ID), anyInt())).thenReturn(List.of(page, twin));
        when(exemptions.search(ExemptionStatus.PENDING, 0, 20))
                .thenReturn(new GtinExemptionRepository.Page(List.of(old), 1, 0, 20));

        PageView<ExemptionView> queue = service.queue(ExemptionStatus.PENDING, 0, 20);

        assertThat(queue.total()).isEqualTo(1);
        ExemptionView row = queue.items().getFirst();
        assertThat(row.title()).isEqualTo("Hand carved stool teak");
        assertThat(row.brand()).isEqualTo("Adire Lagos");
        assertThat(row.mpn()).isEqualTo("AB-123");
        assertThat(row.overdue()).isTrue();
        assertThat(row.ageHours()).isPositive();
        assertThat(row.sellerMessage()).isEqualTo("We are checking that this item has no barcode.");
        assertThat(row.duplicateHints()).singleElement().satisfies(h -> {
            assertThat(h.atin()).isEqualTo("AT10000009");
            assertThat(h.matchedOn()).contains("BRAND_MPN");
        });
    }

    @Test
    void aSellerSeesOnlyTheirOwnRequestsWithoutHints() {
        Actor seller = new Actor("user_a", PrincipalType.SELLER_MEMBER, Optional.of("org_a"), Set.of(), Set.of("catalog:write"));
        when(permissions.require(CatalogPermissions.WRITE)).thenReturn(seller);
        Product page = page(ProductStatus.PENDING_EXEMPTION, "AT10000001");
        when(products.findById(page.id())).thenReturn(Optional.of(page));
        GtinExemptionRequest request = waiting(page);
        when(exemptions.findByOrg("org_a")).thenReturn(List.of(request));

        List<ExemptionView> mine = service.mine();

        assertThat(mine).singleElement().satisfies(v -> {
            assertThat(v.duplicateHints()).isEmpty();
            assertThat(v.sellerMessage()).isEqualTo("We are checking that this item has no barcode.");
        });
        verify(products, never()).findByBrand(anyString(), anyInt());
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private GtinExemptionRequest waiting(Product page) {
        GtinExemptionRequest r = GtinExemptionRequest.filed(page.id(), page.atin().value(), "org_a", "Handmade",
                ExemptionRequirement.BRAND_GATED_CATEGORY, NOW, NOW.plusSeconds(172_800));
        when(exemptions.findById(r.id())).thenReturn(Optional.of(r));
        when(products.findById(page.id())).thenReturn(Optional.of(page));
        return r;
    }

    private void as(Actor actor) {
        when(permissions.require(anyString())).thenReturn(actor);
    }

    private static Actor staff() {
        return new Actor("staff_1", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of(CatalogPermissions.MODERATE));
    }

    private static CategoryRules rules(String code, String approval) {
        return new CategoryRules(code, code, "Fashion", "CLO", "Clothing", approval, null, false, List.of());
    }

    private static Variant variant() {
        return new Variant(UUID.randomUUID(), new Atin("AT10000002"), "S-1", null, true, Map.of(), 899, "USD", 5);
    }

    private static Product page(ProductStatus status, String atin) {
        return new Product(UUID.randomUUID(), new Atin(atin), new ListingOwner(ListingOwnerKind.SELLER, "org_a"), null, null,
                "custom", "Hand carved stool", Map.of(BrandKeys.ID, BRAND_ID), List.of(), "", List.of(variant()), status, null,
                NOW, NOW, 0);
    }
}
