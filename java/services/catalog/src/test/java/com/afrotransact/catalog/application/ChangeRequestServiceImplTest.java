package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import com.afrotransact.catalog.application.ChangeRequestService.ChangeRequestView;
import com.afrotransact.catalog.application.ChangeRequestService.DecisionCommand;
import com.afrotransact.catalog.application.ChangeRequestService.FileCommand;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.ChangeRequestInvalid;
import com.afrotransact.catalog.domain.ChangeRequestStatus;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SellerOffers;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChangeRequestServiceImplTest {

    // A Friday: two business days later is Tuesday.
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final CatalogPolicy POLICY = new CatalogPolicy(List.of("02"), "model", 2, 3, 1000, 1, List.of(), true,
            null, null, 0);

    private final ChangeRequestRepository requests = mock(ChangeRequestRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final SellerOffers sellerOffers = mock(SellerOffers.class);
    private final CatalogSettingsService settings = mock(CatalogSettingsService.class);
    private final ChangeRequestApplier applier = mock(ChangeRequestApplier.class);
    private final PageMerger merger = mock(PageMerger.class);
    private final PageVariantService pageVariants = mock(PageVariantService.class);
    private final ChangeRequestEvents events = mock(ChangeRequestEvents.class);
    private final ProductEvents productEvents = mock(ProductEvents.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private ChangeRequestServiceImpl service;
    private Product page;

    @BeforeEach
    void setUp() {
        page = page("AT00000001", "org_a");
        when(settings.current()).thenReturn(POLICY);
        when(products.findByAtin(any())).thenReturn(Optional.empty());
        when(products.findByAtin(new Atin("AT00000001"))).thenReturn(Optional.of(page));
        when(products.findById(page.id())).thenReturn(Optional.of(page));
        when(requests.insert(any())).thenAnswer(i -> i.getArgument(0));
        when(requests.update(any())).thenAnswer(i -> i.getArgument(0));
        service = new ChangeRequestServiceImpl(requests, products, sellerOffers, settings, applier, merger, pageVariants, events,
                productEvents, permissions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aSellerWithAnOfferSuggestsAnEditAndThePageIsNotChanged() {
        as(seller("org_b", CatalogPermissions.WRITE));
        when(sellerOffers.hasOpenOffer("org_b", "AT00000001")).thenReturn(true);

        ChangeRequestView view = service.file("at00000001", new FileCommand(ChangeRequestType.TITLE, null,
                Map.of("value", "Ankara print tote, large"), "The pack says large"));

        ProductChangeRequest r = view.request();
        assertThat(r.status()).isEqualTo(ChangeRequestStatus.PENDING);
        assertThat(r.requestedByOrg()).isEqualTo("org_b");
        assertThat(r.atin()).isEqualTo("AT00000001");
        assertThat(r.slaDueAt()).as("2 business days from a Friday").isEqualTo(Instant.parse("2026-11-17T10:00:00Z"));
        verify(events).created(eq(r), eq(page), any());
        verify(products, never()).update(any());
        verify(products, never()).updateWithVariants(any());
        verify(applier, never()).apply(any(), any(), any());
    }

    @Test
    void aSellerWithoutAnOfferOnThePageMayNotSuggestAChange() {
        as(seller("org_b", CatalogPermissions.WRITE));
        when(sellerOffers.hasOpenOffer("org_b", "AT00000001")).thenReturn(false);

        assertThatThrownBy(() -> service.file("AT00000001", new FileCommand(ChangeRequestType.TITLE, null,
                Map.of("value", "Another title"), null)))
                .isInstanceOfSatisfying(ForbiddenActionException.class, e -> assertThat(e.code())
                        .isEqualTo("catalog.not-your-offer"));
        verify(requests, never()).insert(any());
    }

    @Test
    void aWrongMatchOrDuplicateReportNeedsNoOffer() {
        as(seller("org_b", CatalogPermissions.WRITE));
        when(sellerOffers.hasOpenOffer(anyString(), anyString())).thenReturn(false);
        Product other = page("AT00000005", "org_c");
        when(products.findByAtin(new Atin("AT00000005"))).thenReturn(Optional.of(other));

        ChangeRequestView duplicate = service.file("AT00000001", new FileCommand(ChangeRequestType.DUPLICATE_PAGE, null,
                Map.of("duplicateOfAtin", "AT00000005"), null));
        ChangeRequestView wrong = service.file("AT00000001", new FileCommand(ChangeRequestType.WRONG_MATCH, null,
                Map.of("gtin", "012345678905", "variantAtin", "AT00000002"), "It is a different size"));

        assertThat(duplicate.request().status()).isEqualTo(ChangeRequestStatus.PENDING);
        assertThat(wrong.request().gtin14()).isEqualTo("00012345678905");
        assertThat(wrong.request().variantAtin()).isEqualTo("AT00000002");
    }

    @Test
    void aSecondOpenRequestOfTheSameKindIsRefused() {
        as(seller("org_b", CatalogPermissions.WRITE));
        when(sellerOffers.hasOpenOffer(anyString(), anyString())).thenReturn(true);
        when(requests.existsOpen(page.id(), ChangeRequestType.ATTRIBUTE, "material", "org_b")).thenReturn(true);

        assertThatThrownBy(() -> service.file("AT00000001", new FileCommand(ChangeRequestType.ATTRIBUTE, null,
                Map.of("key", "material", "value", "Cotton"), null)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.code())
                        .isEqualTo("catalog.change-request-exists"));
    }

    @Test
    void aRequestThatDoesNotFitItsTypeIsRefusedWithTheFieldsThatAreWrong() {
        as(seller("org_b", CatalogPermissions.WRITE));
        when(sellerOffers.hasOpenOffer(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.file("AT00000001", new FileCommand(ChangeRequestType.TITLE, null, Map.of(), null)))
                .isInstanceOfSatisfying(ChangeRequestInvalid.class,
                        e -> assertThat(e.errors()).containsKey("payload.value"));
    }

    @Test
    void aWrongMatchNeedsAValidBarcodeAndAVariantOfThePage() {
        as(seller("org_b", CatalogPermissions.WRITE));

        assertThatThrownBy(() -> service.file("AT00000001", new FileCommand(ChangeRequestType.WRONG_MATCH, null,
                Map.of("gtin", "4006381333932", "variantAtin", "AT00000002"), null)))
                .isInstanceOfSatisfying(ChangeRequestInvalid.class,
                        e -> assertThat(e.errors()).containsKey("payload.gtin"));
        assertThatThrownBy(() -> service.file("AT00000001", new FileCommand(ChangeRequestType.WRONG_MATCH, null,
                Map.of("gtin", "4006381333931", "variantAtin", "AT00000077"), null)))
                .isInstanceOfSatisfying(ChangeRequestInvalid.class,
                        e -> assertThat(e.errors()).containsKey("payload.variantAtin"));
    }

    @Test
    void anAnotherSellersRequestsAreHiddenFromTheOtherSellersButTheOwnerSeesAll() {
        ProductChangeRequest mine = pending(ChangeRequestType.TITLE, "org_b");
        when(requests.findByProduct(page.id(), "org_b")).thenReturn(List.of(mine));
        when(requests.findByProduct(page.id(), null)).thenReturn(List.of(mine, pending(ChangeRequestType.BRAND, "org_c")));

        as(seller("org_b", CatalogPermissions.WRITE));
        assertThat(service.forPage("AT00000001")).hasSize(1);

        as(seller("org_a", CatalogPermissions.WRITE));
        assertThat(service.forPage("AT00000001")).hasSize(2);
    }

    @Test
    void staffApproveAContentChangeWhichIsAppliedAndRecordedWithWhoAndWhy() {
        as(staff());
        ProductChangeRequest waiting = pending(ChangeRequestType.TITLE, "org_b");
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));
        Product changed = page("AT00000001", "org_a");
        when(applier.apply(eq(waiting), eq(page), any())).thenReturn(changed.withContent("New title", changed.attributes(),
                changed.images(), changed.details(), NOW));

        ChangeRequestView view = service.decide(waiting.id(), new DecisionCommand(true, "Matches the pack photo", null,
                null, null));

        assertThat(view.request().status()).isEqualTo(ChangeRequestStatus.APPLIED);
        assertThat(view.request().decidedBy()).isEqualTo("staff_1");
        assertThat(view.request().reason()).isEqualTo("Matches the pack photo");
        verify(events).decided(eq(view.request()), eq(page), eq(0L), eq((String) null), any());
    }

    @Test
    void aRejectionNeedsAReasonAndAppliesNothing() {
        as(staff());
        ProductChangeRequest waiting = pending(ChangeRequestType.TITLE, "org_b");
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));

        assertThatThrownBy(() -> service.decide(waiting.id(), new DecisionCommand(false, "no", null, null, null)))
                .isInstanceOf(CatalogRuleViolation.class);
        ChangeRequestView view = service.decide(waiting.id(), new DecisionCommand(false, "The title is correct as it is",
                null, null, null));

        assertThat(view.request().status()).isEqualTo(ChangeRequestStatus.REJECTED);
        assertThat(view.request().reason()).isEqualTo("The title is correct as it is");
        verify(applier, never()).apply(any(), any(), any());
    }

    @Test
    void aDecidedRequestCannotBeDecidedAgain() {
        as(staff());
        ProductChangeRequest done = pending(ChangeRequestType.TITLE, "org_b").rejected("staff_2", "Already answered", NOW);
        when(requests.findById(done.id())).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.decide(done.id(), new DecisionCommand(true, "Looks right", null, null, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-decided"));
    }

    @Test
    void aStaleVersionIsAConflict() {
        as(staff());
        ProductChangeRequest waiting = pending(ChangeRequestType.TITLE, "org_b");
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));

        assertThatThrownBy(() -> service.decide(waiting.id(), new DecisionCommand(false, "Not needed", null, null, 7L)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.concurrent-update"));
    }

    @Test
    void aNewVariantIsAddedThroughThePageVariantServiceAndTheNewAtinIsInTheAuditEvent() {
        as(staff());
        ProductChangeRequest waiting = pending(ChangeRequestType.NEW_VARIANT, "org_b");
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));
        when(pageVariants.addVariant(eq(waiting), eq(page), eq(true), any()))
                .thenReturn(new PageVariantService.Added(page, "AT00000042"));

        ChangeRequestView view = service.decide(waiting.id(), new DecisionCommand(true, "A real size", null,
                Map.of("holdPageForTax", true), null));

        assertThat(view.request().status()).isEqualTo(ChangeRequestStatus.APPLIED);
        assertThat(view.request().resolution()).isEqualTo("NEW_VARIANT");
        verify(events).decided(any(), eq(page), eq(page.version()), eq("AT00000042"), any());
        verify(applier, never()).apply(any(), any(), any());
    }

    @Test
    void aNewVariantTheTaxGateRefusesLeavesTheRequestWaiting() {
        as(staff());
        ProductChangeRequest waiting = pending(ChangeRequestType.NEW_VARIANT, "org_b");
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));
        when(pageVariants.addVariant(any(), any(), eq(false), any())).thenThrow(new BusinessRuleException(
                ChangeRequestApplierImpl.NEEDS_TAX_REVIEW, "The page is on sale"));

        assertThatThrownBy(() -> service.decide(waiting.id(), new DecisionCommand(true, "A real size", null, null, null)))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-needs-tax-review"));
        verify(requests, never()).update(any());
    }

    @Test
    void aWrongMatchIsResolvedByCorrectingTheBarcodeOnTheVariant() {
        as(staff());
        ProductChangeRequest waiting = ProductChangeRequest.filed(page.id(), "AT00000001", "AT00000002", "00012345678905",
                ChangeRequestType.WRONG_MATCH, "", Map.of("gtin", "012345678905", "variantAtin", "AT00000002"), null,
                "org_b", "user", NOW, NOW);
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));
        when(products.updateWithVariants(any())).thenAnswer(i -> i.getArgument(0));

        ChangeRequestView view = service.decide(waiting.id(), new DecisionCommand(true, "The seller is right",
                "CORRECT_GTIN", Map.of("gtin", "4006-3813-33931"), null));

        assertThat(view.request().resolution()).isEqualTo("CORRECT_GTIN");
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(products).updateWithVariants(saved.capture());
        assertThat(saved.getValue().variants().getFirst().gtin()).isEqualTo("4006381333931");
        assertThat(saved.getValue().status()).as("a correction never changes the status").isEqualTo(page.status());
    }

    @Test
    void aWrongMatchWithAnUnknownResolutionIsNotApplied() {
        as(staff());
        ProductChangeRequest waiting = wrongMatch();
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));

        assertThatThrownBy(() -> service.decide(waiting.id(), new DecisionCommand(true, "Do it", "DELETE_PAGE", null,
                null))).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.code()).isEqualTo("catalog.resolution-not-supported"));
        verify(requests, never()).update(any());
    }

    @Test
    void aWrongMatchCanMoveTheVariantToAnotherPage() {
        as(staff());
        Product target = page("AT00000005", "org_c");
        when(products.findByAtin(new Atin("AT00000005"))).thenReturn(Optional.of(target));
        ProductChangeRequest waiting = wrongMatch();
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));

        ChangeRequestView view = service.decide(waiting.id(), new DecisionCommand(true, "It is a size of the other page",
                "MOVE_VARIANT", Map.of("targetAtin", "at00000005", "holdPageForTax", "true"), null));

        assertThat(view.request().resolution()).isEqualTo("MOVE_VARIANT");
        verify(pageVariants).moveVariant(eq(page), eq("AT00000002"), eq(target), eq(true), eq("staff_1"),
                eq("It is a size of the other page"), eq(waiting.id()), any());
    }

    @Test
    void aMoveNeedsTheTargetPage() {
        as(staff());
        ProductChangeRequest waiting = wrongMatch();
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));

        assertThatThrownBy(() -> service.decide(waiting.id(), new DecisionCommand(true, "Move it", "MOVE_VARIANT",
                Map.of(), null))).isInstanceOf(CatalogRuleViolation.class);
        verify(pageVariants, never()).moveVariant(any(), any(), any(), eq(false), any(), any(), any(), any());
    }

    @Test
    void aWrongMatchCanSplitTheVariantOntoAPageOfItsOwn() {
        as(staff());
        ProductChangeRequest waiting = wrongMatch();
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));

        ChangeRequestView view = service.decide(waiting.id(), new DecisionCommand(true, "It is a different product",
                "SPLIT_VARIANT", null, null));

        assertThat(view.request().resolution()).isEqualTo("SPLIT_VARIANT");
        verify(pageVariants).splitVariant(eq(page), eq("AT00000002"), eq("staff_1"), eq("It is a different product"),
                eq(waiting.id()), any());
    }

    @Test
    void aDuplicatePageIsMergedIntoTheNamedSurvivor() {
        as(staff());
        Product other = page("AT00000005", "org_c");
        when(products.findByAtin(new Atin("AT00000005"))).thenReturn(Optional.of(other));
        ProductChangeRequest waiting = ProductChangeRequest.filed(page.id(), "AT00000001", null, null,
                ChangeRequestType.DUPLICATE_PAGE, "", Map.of("duplicateOfAtin", "AT00000005"), null, "org_b", "user", NOW,
                NOW);
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));

        ChangeRequestView view = service.decide(waiting.id(), new DecisionCommand(true, "Same product, two pages", "MERGE",
                Map.of("survivorAtin", "AT00000001", "variantMap", Map.of("AT00000099", "AT00000002")), null));

        assertThat(view.request().resolution()).isEqualTo("MERGE");
        verify(merger).merge(eq(other), eq(page), eq(Map.of("AT00000099", "AT00000002")), eq("staff_1"),
                eq("Same product, two pages"), eq(waiting.id()), any());
    }

    @Test
    void onlyTheRequesterCanWithdrawAWaitingRequest() {
        ProductChangeRequest waiting = pending(ChangeRequestType.TITLE, "org_b");
        when(requests.findById(waiting.id())).thenReturn(Optional.of(waiting));

        as(seller("org_c", CatalogPermissions.WRITE));
        assertThatThrownBy(() -> service.withdraw(waiting.id())).hasMessageContaining(waiting.id().toString());

        as(seller("org_b", CatalogPermissions.WRITE));
        assertThat(service.withdraw(waiting.id()).request().status()).isEqualTo(ChangeRequestStatus.WITHDRAWN);
    }

    private ProductChangeRequest wrongMatch() {
        return ProductChangeRequest.filed(page.id(), "AT00000001", "AT00000002", "00012345678905",
                ChangeRequestType.WRONG_MATCH, "", Map.of("gtin", "012345678905", "variantAtin", "AT00000002"), null,
                "org_b", "user", NOW, NOW);
    }

    private void as(Actor actor) {
        when(permissions.require(anyString())).thenReturn(actor);
    }

    private static Actor seller(String org, String permission) {
        return new Actor("user_" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of(), Set.of(permission));
    }

    private static Actor staff() {
        return new Actor("staff_1", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of(CatalogPermissions.MODERATE,
                CatalogPermissions.WRITE));
    }

    private ProductChangeRequest pending(ChangeRequestType type, String org) {
        Map<String, Object> payload = type == ChangeRequestType.TITLE ? Map.of("value", "New title") : Map.of();
        return ProductChangeRequest.filed(page.id(), "AT00000001", null, null, type, "", payload, null, org, "user_" + org,
                NOW, NOW.plusSeconds(172_800));
    }

    private static Product page(String atin, String owner) {
        Variant v = new Variant(UUID.randomUUID(), new Atin("AT00000001".equals(atin) ? "AT00000002" : "AT00000006"),
                "S-" + atin, null, true, Map.of(), 899, "USD", 5);
        Variant withGtin = "AT00000001".equals(atin) ? v.withGtin("012345678905") : v;
        return new Product(UUID.randomUUID(), new Atin(atin), new ListingOwner(ListingOwnerKind.SELLER, owner), null, null,
                "custom", "Ankara tote", Map.of(), List.of(), "", List.of(withGtin), ProductStatus.LIVE, null, NOW, NOW, 0);
    }
}
