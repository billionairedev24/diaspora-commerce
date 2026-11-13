package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.ProductTakedown;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.tax.TaxDecision;
import com.afrotransact.catalog.domain.tax.TaxNotReady;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.catalog.port.ProductFlagRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductTakedownRepository;
import com.afrotransact.catalog.port.ProductTaxHoldRepository;
import com.afrotransact.catalog.port.SellingHoldRepository;
import com.afrotransact.catalog.port.TaxEvidenceRepository;
import com.afrotransact.catalog.port.TaxDecisionAuthority;
import com.afrotransact.catalog.port.TaxGateMetrics;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.catalog.testsupport.ImageGates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * One test per way a product can become live or stay live (docs/engineering/tax-gate-paths.md): each is run with the real
 * {@link ProductLiveGateImpl}, so it proves the path ends at the gate and a variant without a resolved decision never goes
 * live. Every path that is not covered here is a take-off-sale path (pause, reject, takedown, hold), which needs no gate.
 */
class TaxGatePathsTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final TaxDecision DECISION = new TaxDecision("EXEMPT", "AT.GROCERY.X", "S001", "0%", "v1", "r");
    private static final ListingOwner OWNER = new ListingOwner(ListingOwnerKind.SELLER, "org_1");

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductTaxHoldRepository taxHolds = mock(ProductTaxHoldRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ProductEvents events = mock(ProductEvents.class);
    private final SellingPublishGate sellingGate = mock(SellingPublishGate.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private ProductLiveGateImpl liveGate;

    @BeforeEach
    void setUp() {
        liveGate = new ProductLiveGateImpl(products, taxHolds, categories, events, sellingGate,
                mock(TaxGateMetrics.class), new TaxAuthorityCheck(mock(TaxDecisionAuthority.class),
                mock(TaxGateMetrics.class)), ImageGates.passthrough(), CLOCK);
        when(products.update(any())).thenAnswer(i -> i.getArgument(0));
        Actor staff = mock(Actor.class);
        when(staff.type()).thenReturn(PrincipalType.STAFF);
        when(permissions.require(any())).thenReturn(staff);
    }

    private static Variant variant(String atin, TaxStatus status, boolean decision) {
        return new Variant(UUID.randomUUID(), new Atin(atin), "SKU-" + atin, null, true, Map.of(), 100, "USD", 1, null,
                List.of(), false, new VariantTax(Map.of(), status, List.of(), decision ? DECISION : null));
    }

    private static Product product(ProductStatus status, Variant... variants) {
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), OWNER, null, null, "packaged-snacks", "Plantain chips",
                Map.of(), List.<ProductImage>of(), "", List.of(variants), status, null, NOW, NOW, 0);
    }

    private ProductServiceImpl productService() {
        return new ProductServiceImpl(products, mock(ProductWriter.class), permissions, mock(ResourceAccessChecker.class),
                mock(ActorResolver.class), mock(OwnerResolver.class), events, liveGate, CLOCK, sellingGate);
    }

    // --- staff approve (also trust-ops approve, which calls POST /v1/products/{id}/approve) ---------------------------------

    @Test
    void staffApproveRefusesAVariantWithoutAResolvedDecision() {
        Product p = product(ProductStatus.IN_REVIEW, variant("AT00000011", TaxStatus.PENDING_REVIEW, true));
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        assertThatThrownBy(() -> productService().approve(p.id())).isInstanceOf(TaxNotReady.class);
        verify(products, never()).update(any());
        verify(events, never()).publish(eq(CatalogEvents.PRODUCT_APPROVED), any());
    }

    @Test
    void staffApproveRefusesAReadyStatusThatHasNoStoredDecision() {
        Product p = product(ProductStatus.IN_REVIEW, variant("AT00000011", TaxStatus.READY, false));
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        assertThatThrownBy(() -> productService().approve(p.id())).isInstanceOf(TaxNotReady.class);
        verify(products, never()).update(any());
    }

    @Test
    void staffApproveMakesAResolvedProductLive() {
        Product p = product(ProductStatus.IN_REVIEW, variant("AT00000011", TaxStatus.READY, true));
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        assertThat(productService().approve(p.id()).status()).isEqualTo(ProductStatus.LIVE);
        verify(events).publish(eq(CatalogEvents.PRODUCT_APPROVED), any());
    }

    // --- relist / unpublish-republish: pause then resume ------------------------------------------------------------------

    @Test
    void aSellerCannotResumeAPausedListingWhoseTaxIsNotResolved() {
        Product p = product(ProductStatus.PAUSED, variant("AT00000011", TaxStatus.MISSING_FACTS, false));
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        assertThatThrownBy(() -> productService().resume(p.id())).isInstanceOf(TaxNotReady.class);
        verify(products, never()).update(any());
        verify(events, never()).publish(eq(CatalogEvents.PRODUCT_RESUMED), any());
    }

    @Test
    void aSellerResumesAPausedListingWhoseTaxIsResolved() {
        Product p = product(ProductStatus.PAUSED, variant("AT00000011", TaxStatus.READY, true));
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        assertThat(productService().resume(p.id()).status()).isEqualTo(ProductStatus.LIVE);
    }

    @Test
    void resumeGoesThroughTheGateForTheWholeTransition() {
        Product p = product(ProductStatus.PAUSED, variant("AT00000011", TaxStatus.MISSING_FACTS, false));
        assertThatThrownBy(() -> liveGate.resume(p, NOW)).isInstanceOf(TaxNotReady.class);
    }

    // --- rejecting a held product ends its tax hold (it never comes back on its own after a person decided) --------------

    @Test
    void rejectingAListingForgetsItsTaxHold() {
        Product p = product(ProductStatus.IN_REVIEW, variant("AT00000011", TaxStatus.PENDING_REVIEW, true));
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        productService().reject(p.id(), "Not allowed");
        verify(taxHolds).release(p.id(), NOW);
    }

    // --- selling approvals renewed: the hold sweep resumes paused listings ------------------------------------------------

    @Test
    void theSellingHoldSweepDoesNotResumeAListingWithoutAResolvedTaxDecision() {
        SellingHoldRepository holds = mock(SellingHoldRepository.class);
        Product p = product(ProductStatus.PAUSED, variant("AT00000011", TaxStatus.PENDING_REVIEW, true));
        when(holds.heldFor(any(), any(), any(), any(), anyInt())).thenReturn(List.of(p.id()));
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        when(holds.release(any(), any(), any())).thenReturn(true);
        SellingHoldServiceImpl service = new SellingHoldServiceImpl(holds, products, events, sellingGate, liveGate, CLOCK,
                mock(PlatformTransactionManager.class), 200);

        int resumed = service.restore(new SellingHoldService.Change("org_1", "CATEGORY", "grocery", "RENEWED", List.of()));

        assertThat(resumed).isZero();
        verify(products, never()).update(any());
    }

    @Test
    void theSellingHoldSweepResumesAListingWhoseTaxIsResolved() {
        SellingHoldRepository holds = mock(SellingHoldRepository.class);
        Product p = product(ProductStatus.PAUSED, variant("AT00000011", TaxStatus.READY, true));
        when(holds.heldFor(any(), any(), any(), any(), anyInt())).thenReturn(List.of(p.id()));
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        when(holds.release(any(), any(), any())).thenReturn(true);
        SellingHoldServiceImpl service = new SellingHoldServiceImpl(holds, products, events, sellingGate, liveGate, CLOCK,
                mock(PlatformTransactionManager.class), 200);

        assertThat(service.restore(new SellingHoldService.Change("org_1", "CATEGORY", "grocery", "RENEWED", List.of())))
                .isEqualTo(1);
        verify(events).publish(eq(CatalogEvents.PRODUCT_RESUMED), any());
    }

    // --- trust-ops restore after a takedown ---------------------------------------------------------------------------------

    @Test
    void restoringATakedownDoesNotPutAListingLiveWithoutAResolvedTaxDecision() {
        Product taken = product(ProductStatus.TAKEN_DOWN, variant("AT00000011", TaxStatus.PENDING_REVIEW, true));
        ProductTakedownRepository takedowns = mock(ProductTakedownRepository.class);
        ProductTakedown active = mock(ProductTakedown.class);
        when(active.previousStatus()).thenReturn(ProductStatus.LIVE);
        when(active.restore(any(), any(), any())).thenReturn(active);
        when(takedowns.findActive(taken.id())).thenReturn(Optional.of(active));
        when(products.findById(taken.id())).thenReturn(Optional.of(taken));
        Actor actor = mock(Actor.class);
        when(actor.subject()).thenReturn("staff_1");
        when(permissions.require(any())).thenReturn(actor);
        ListingModerationServiceImpl service = new ListingModerationServiceImpl(products, mock(ProductFlagRepository.class),
                takedowns, mock(ListingSuppressionRepository.class), permissions, mock(ActorResolver.class), events,
                liveGate, CLOCK);

        Product restored = service.restore(taken.id(), "reviewed");

        assertThat(restored.status()).isEqualTo(ProductStatus.IN_REVIEW);
        verify(events).publish(eq(CatalogEvents.PRODUCT_REINSTATED), any());
    }

    // --- tax.classification.changed consumer ------------------------------------------------------------------------------

    @Test
    void aClassificationThatTurnsStaleClosesTheLiveListingInTheSameTransaction() {
        Variant v = variant("AT00000011", TaxStatus.READY, true);
        Product live = product(ProductStatus.LIVE, v);
        Product stale = live.withVariants(List.of(v.withTax(v.tax().withStatus(TaxStatus.PENDING_REVIEW))));
        when(products.findByVariantAtin(new Atin("AT00000011"))).thenReturn(Optional.of(live));
        when(products.findById(live.id())).thenReturn(Optional.of(stale));
        ProductTaxServiceImpl service = new ProductTaxServiceImpl(products, categories, mock(TaxEvidenceRepository.class),
                mock(TaxGateEvaluator.class), events, liveGate, permissions, mock(ResourceAccessChecker.class), CLOCK);

        service.classificationChanged("AT00000011", "STALE");

        verify(taxHolds).hold(live.id(), "TAX_PENDING_REVIEW", NOW);
        verify(events).publishWithReason(eq(CatalogEvents.PRODUCT_PAUSED), any(), eq("TAX_PENDING_REVIEW"));
    }

    @Test
    void anApprovedClassificationBringsAHeldListingBackOnSale() {
        Variant v = variant("AT00000011", TaxStatus.PENDING_REVIEW, true);
        Product held = product(ProductStatus.IN_REVIEW, v);
        Product approved = held.withVariants(List.of(v.withTax(v.tax().withStatus(TaxStatus.READY))));
        when(products.findByVariantAtin(new Atin("AT00000011"))).thenReturn(Optional.of(held));
        when(products.findById(held.id())).thenReturn(Optional.of(approved));
        when(taxHolds.isHeld(held.id())).thenReturn(true);
        ProductTaxServiceImpl service = new ProductTaxServiceImpl(products, categories, mock(TaxEvidenceRepository.class),
                mock(TaxGateEvaluator.class), events, liveGate, permissions, mock(ResourceAccessChecker.class), CLOCK);

        service.classificationChanged("AT00000011", "APPROVED");

        verify(events).publish(eq(CatalogEvents.PRODUCT_RESUMED), any());
    }

    @Test
    void anApprovedClassificationNeverPublishesAListingThatWasNeverHeld() {
        Variant v = variant("AT00000011", TaxStatus.PENDING_REVIEW, true);
        Product imported = product(ProductStatus.IN_REVIEW, v);
        Product approved = imported.withVariants(List.of(v.withTax(v.tax().withStatus(TaxStatus.READY))));
        when(products.findByVariantAtin(new Atin("AT00000011"))).thenReturn(Optional.of(imported));
        when(products.findById(imported.id())).thenReturn(Optional.of(approved));
        when(taxHolds.isHeld(imported.id())).thenReturn(false);
        ProductTaxServiceImpl service = new ProductTaxServiceImpl(products, categories, mock(TaxEvidenceRepository.class),
                mock(TaxGateEvaluator.class), events, liveGate, permissions, mock(ResourceAccessChecker.class), CLOCK);

        service.classificationChanged("AT00000011", "APPROVED");

        verify(events, never()).publish(eq(CatalogEvents.PRODUCT_RESUMED), any());
        verify(events, never()).publish(eq(CatalogEvents.PRODUCT_APPROVED), any());
    }
}
