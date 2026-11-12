package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.tax.TaxDecision;
import com.afrotransact.catalog.domain.tax.TaxNotReady;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductTaxHoldRepository;
import com.afrotransact.catalog.port.SellingApprovals;
import com.afrotransact.catalog.port.TaxGateMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The choke point for going live: a product is never put on sale unless every variant has a stored, resolved tax decision,
 * and a live product that loses one is closed with a hold, an event and a plain note (docs/engineering/tax-gate-paths.md).
 */
class ProductLiveGateImplTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final TaxDecision DECISION = new TaxDecision("EXEMPT", "AT.GROCERY.X", "S001", "0%", "v1", "r");
    private static final ListingOwner OWNER = new ListingOwner(ListingOwnerKind.SELLER, "org_1");

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductTaxHoldRepository holds = mock(ProductTaxHoldRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ProductEvents events = mock(ProductEvents.class);
    private final SellingPublishGate selling = mock(SellingPublishGate.class);
    private final TaxGateMetrics metrics = mock(TaxGateMetrics.class);
    private ProductLiveGateImpl gate;

    @BeforeEach
    void setUp() {
        gate = new ProductLiveGateImpl(products, holds, categories, events, selling, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(products.update(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static Variant variant(String atin, VariantTax tax) {
        return new Variant(UUID.randomUUID(), new Atin(atin), "SKU-" + atin, null, true, Map.of(), 100, "USD", 1, null,
                List.of(), false, tax);
    }

    private static VariantTax resolved() {
        return new VariantTax(Map.of(), TaxStatus.READY, List.of(), DECISION);
    }

    private static VariantTax pending() {
        return new VariantTax(Map.of(), TaxStatus.PENDING_REVIEW, List.of(), DECISION);
    }

    private static VariantTax readyWithoutDecision() {
        return new VariantTax(Map.of(), TaxStatus.READY, List.of(), null);
    }

    private static Product product(ProductStatus status, Variant... variants) {
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), OWNER, null, null, "packaged-snacks", "Plantain chips",
                Map.of(), List.<ProductImage>of(), "", List.of(variants), status, null, NOW, NOW, 0);
    }

    @Test
    void approveRefusesAProductWhoseVariantHasNoResolvedDecision() {
        Product p = product(ProductStatus.IN_REVIEW, variant("AT00000011", resolved()), variant("AT00000012", pending()));
        assertThatThrownBy(() -> gate.approve(p, NOW)).isInstanceOf(TaxNotReady.class)
                .hasMessageContaining("tax details are not complete");
    }

    @Test
    void aReadyStatusWithoutAStoredDecisionIsNotTrusted() {
        Product p = product(ProductStatus.IN_REVIEW, variant("AT00000011", readyWithoutDecision()));
        assertThatThrownBy(() -> gate.approve(p, NOW)).isInstanceOf(TaxNotReady.class);
    }

    @Test
    void aProductWithNoVariantsCannotGoLive() {
        assertThatThrownBy(() -> gate.approve(product(ProductStatus.IN_REVIEW), NOW)).isInstanceOf(TaxNotReady.class);
    }

    @Test
    void approveMakesAResolvedProductLiveAndForgetsAnyTaxHold() {
        Product p = product(ProductStatus.IN_REVIEW, variant("AT00000011", resolved()));
        Product live = gate.approve(p, NOW);
        assertThat(live.status()).isEqualTo(ProductStatus.LIVE);
        verify(holds).release(p.id(), NOW);
    }

    @Test
    void resumeRefusesWithoutAResolvedDecisionAndWorksWithOne() {
        Product notReady = product(ProductStatus.PAUSED, variant("AT00000011", pending()));
        assertThatThrownBy(() -> gate.resume(notReady, NOW)).isInstanceOf(TaxNotReady.class);
        Product ready = product(ProductStatus.PAUSED, variant("AT00000011", resolved()));
        assertThat(gate.resume(ready, NOW).status()).isEqualTo(ProductStatus.LIVE);
    }

    @Test
    void aTakedownReversedToLiveWithoutAResolvedDecisionReturnsHeldNotLive() {
        Product taken = product(ProductStatus.TAKEN_DOWN, variant("AT00000011", pending()));
        Product back = gate.restoreFromTakedown(taken, ProductStatus.LIVE, NOW);
        assertThat(back.status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(back.moderationNote()).isEqualTo(Product.TAX_HOLD_NOTE);
        verify(holds).hold(taken.id(), "TAX_PENDING_REVIEW", NOW);
        verify(events).publishWithReason(eq(CatalogEvents.PRODUCT_PAUSED), any(), eq("TAX_PENDING_REVIEW"));
    }

    @Test
    void aTakedownReversedToLiveWithResolvedDecisionsIsLiveAgain() {
        Product taken = product(ProductStatus.TAKEN_DOWN, variant("AT00000011", resolved()));
        assertThat(gate.restoreFromTakedown(taken, ProductStatus.LIVE, NOW).status()).isEqualTo(ProductStatus.LIVE);
        verify(holds, never()).hold(any(), any(), any());
    }

    @Test
    void aTakedownReversedToPausedStaysPausedWhateverTheTaxState() {
        Product taken = product(ProductStatus.TAKEN_DOWN, variant("AT00000011", pending()));
        assertThat(gate.restoreFromTakedown(taken, ProductStatus.PAUSED, NOW).status()).isEqualTo(ProductStatus.PAUSED);
    }

    @Test
    void closeIfNotReadyTakesALiveListingOffSaleWithAReasonAnEventAndAPlainNote() {
        Product live = product(ProductStatus.LIVE, variant("AT00000011", resolved()), variant("AT00000012", pending()));
        Product held = gate.closeIfNotReady(live, NOW);
        assertThat(held.status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(held.moderationNote()).isEqualTo("We are checking the tax details for this item.");
        verify(holds).hold(live.id(), "TAX_PENDING_REVIEW", NOW);
        verify(events).publishWithReason(eq(CatalogEvents.PRODUCT_PAUSED), any(), eq("TAX_PENDING_REVIEW"));
        verify(metrics).closed("TAX_PENDING_REVIEW");
    }

    @Test
    void closeIfNotReadyLeavesAResolvedOrNotLiveProductAlone() {
        Product live = product(ProductStatus.LIVE, variant("AT00000011", resolved()));
        assertThat(gate.closeIfNotReady(live, NOW)).isSameAs(live);
        Product draft = product(ProductStatus.IN_REVIEW, variant("AT00000011", pending()));
        assertThat(gate.closeIfNotReady(draft, NOW)).isSameAs(draft);
        verify(holds, never()).hold(any(), any(), any());
    }

    @Test
    void reconcileClosesAStoredLiveProductThatNoLongerHasAResolvedDecision() {
        Product live = product(ProductStatus.LIVE, variant("AT00000011", pending()));
        when(products.findById(live.id())).thenReturn(Optional.of(live));
        Product after = gate.reconcile(live.id());
        assertThat(after.status()).isEqualTo(ProductStatus.IN_REVIEW);
        verify(products).update(any());
    }

    @Test
    void reconcileBringsBackAListingHeldForTaxOnceEveryVariantIsResolved() {
        Product held = product(ProductStatus.IN_REVIEW, variant("AT00000011", resolved()));
        when(products.findById(held.id())).thenReturn(Optional.of(held));
        when(holds.isHeld(held.id())).thenReturn(true);
        Product after = gate.reconcile(held.id());
        assertThat(after.status()).isEqualTo(ProductStatus.LIVE);
        verify(holds).release(held.id(), NOW);
        verify(events).publish(eq(CatalogEvents.PRODUCT_RESUMED), any());
        verify(metrics).restored();
    }

    @Test
    void reconcileNeverPublishesAListingThatWasNotHeldForTax() {
        // A new or imported product waits for a person: its tax becoming ready does not make it live.
        Product imported = product(ProductStatus.IN_REVIEW, variant("AT00000011", resolved()));
        when(products.findById(imported.id())).thenReturn(Optional.of(imported));
        when(holds.isHeld(imported.id())).thenReturn(false);
        assertThat(gate.reconcile(imported.id()).status()).isEqualTo(ProductStatus.IN_REVIEW);
        verify(products, never()).update(any());
    }

    @Test
    void reconcileKeepsAHeldListingClosedWhileTheSellerMayNotSellIt() {
        Product held = product(ProductStatus.IN_REVIEW, variant("AT00000011", resolved()));
        when(products.findById(held.id())).thenReturn(Optional.of(held));
        when(holds.isHeld(held.id())).thenReturn(true);
        when(selling.enabled()).thenReturn(true);
        when(selling.evaluate(any(), any())).thenReturn(new SellingApprovals.Status("APPLY", "not approved", List.of(),
                List.of()));
        assertThat(gate.reconcile(held.id()).status()).isEqualTo(ProductStatus.IN_REVIEW);
        verify(products, never()).update(any());
    }

    @Test
    void reconcileKeepsAHeldListingClosedWhenSellingApprovalsCannotBeChecked() {
        Product held = product(ProductStatus.IN_REVIEW, variant("AT00000011", resolved()));
        when(products.findById(held.id())).thenReturn(Optional.of(held));
        when(holds.isHeld(held.id())).thenReturn(true);
        when(selling.enabled()).thenReturn(true);
        when(selling.evaluate(any(), any())).thenThrow(new IllegalStateException("seller-service down"));
        assertThat(gate.reconcile(held.id()).status()).isEqualTo(ProductStatus.IN_REVIEW);
    }

    @Test
    void reconcileKeepsAHeldListingClosedWhileADecisionIsStillMissing() {
        Product held = product(ProductStatus.IN_REVIEW, variant("AT00000011", pending()));
        when(products.findById(held.id())).thenReturn(Optional.of(held));
        when(holds.isHeld(held.id())).thenReturn(true);
        assertThat(gate.reconcile(held.id()).status()).isEqualTo(ProductStatus.IN_REVIEW);
        verify(products, never()).update(any());
    }

    @Test
    void reconcileReleasesAHoldOnAListingThatIsNoLongerWaitingOnTax() {
        Product rejected = product(ProductStatus.REJECTED, variant("AT00000011", resolved()));
        when(products.findById(rejected.id())).thenReturn(Optional.of(rejected));
        gate.reconcile(rejected.id());
        verify(holds).release(rejected.id(), NOW);
    }
}
