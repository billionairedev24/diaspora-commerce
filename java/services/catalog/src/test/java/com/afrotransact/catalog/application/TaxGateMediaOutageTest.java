package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.application.ImageSettings.Mode;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.images.ImageCheckResult;
import com.afrotransact.catalog.domain.tax.TaxDecision;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageVerifier;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductTaxHoldRepository;
import com.afrotransact.catalog.port.TaxDecisionAuthority;
import com.afrotransact.catalog.port.TaxGateMetrics;
import com.afrotransact.platform.security.PermissionChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Tax enforcement never depends on media: with the image library down the tax sweep still closes every live listing without a
 * resolved tax decision, skips only the held-photo check (counted, never failed), and fails closed (a held listing stays held;
 * approve and resume, which publish, still refuse). Uses the real gate and sweep with a media port that always throws.
 */
class TaxGateMediaOutageTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final ListingOwner OWNER = new ListingOwner(ListingOwnerKind.SELLER, "org_1");
    private static final TaxDecision DECISION = new TaxDecision("EXEMPT", "AT.GROCERY.X", "S001", "0%", "v1", "r");

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductTaxHoldRepository holds = mock(ProductTaxHoldRepository.class);
    private final ProductEvents events = mock(ProductEvents.class);
    private final TaxGateMetrics metrics = mock(TaxGateMetrics.class);
    private final List<ImageVerifier.Item> asked = new ArrayList<>();
    private ProductLiveGateImpl gate;
    private TaxGateSweepServiceImpl sweep;

    /** Media is down: every question throws. */
    private final ImageVerifier mediaDown = new ImageVerifier() {
        @Override
        public List<ImageCheckResult> verify(List<Item> items) {
            asked.addAll(items);
            throw new ImageServiceUnavailable("media is down", null);
        }

        @Override
        public List<ImageCheckResult> rescore(List<Item> items) {
            return verify(items);
        }
    };

    @BeforeEach
    void setUp() {
        ImageGate images = new ImageGateImpl(mediaDown,
                new ImageSettings(Mode.ENFORCE, 60, false, 50, Duration.ofSeconds(30), Duration.ofDays(30)));
        TaxAuthorityCheck authority = new TaxAuthorityCheck(mock(TaxDecisionAuthority.class), metrics);
        gate = new ProductLiveGateImpl(products, holds, mock(CategoryRepository.class), events,
                mock(SellingPublishGate.class), metrics, authority, images, Clock.fixed(NOW, ZoneOffset.UTC));
        sweep = new TaxGateSweepServiceImpl(products, holds, gate, events, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class), authority, mock(CategoryRepository.class),
                mock(PermissionChecker.class), 200, Duration.ofMinutes(5));
        when(products.update(any())).thenAnswer(i -> i.getArgument(0));
        when(holds.unannounced(anyInt())).thenReturn(List.of());
    }

    private static Product product(ProductStatus status, VariantTax tax, ProductImage... pictures) {
        Variant v = new Variant(UUID.randomUUID(), new Atin("AT00000011"), "SKU-1", null, true, Map.of(), 100, "USD", 1,
                null, List.of(), false, tax);
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), OWNER, null, null, "packaged-snacks", "Plantain chips",
                Map.of(), List.of(pictures), "", List.of(v), status, null, NOW, NOW, 0);
    }

    private static VariantTax resolved() {
        return new VariantTax(Map.of(), TaxStatus.READY, List.of(), DECISION);
    }

    private static VariantTax noDecision() {
        return new VariantTax(Map.of(), TaxStatus.PENDING_REVIEW, List.of(), null);
    }

    private static ProductImage heldPhoto() {
        return ProductImage.held("ast_1", null, null);
    }

    @Test
    void reconcileClosesALiveListingWithoutATaxDecisionWithoutAskingMedia() {
        Product live = product(ProductStatus.LIVE, noDecision(), heldPhoto());
        when(products.findById(live.id())).thenReturn(Optional.of(live));

        ProductLiveGate.Reconciliation done = gate.reconcileChecked(live.id());

        assertThat(done.product().status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(done.photoCheckSkipped()).isFalse();
        assertThat(asked).as("tax is decided first, media is not asked").isEmpty();
        verify(holds).hold(eq(live.id()), any(), any());
        verify(events).publishWithReason(eq(CatalogEvents.PRODUCT_PAUSED), any(), any());
    }

    @Test
    void reconcileKeepsAHeldListingHeldWhenMediaIsDownAndReportsTheSkip() {
        Product held = product(ProductStatus.IN_REVIEW, resolved(), heldPhoto());
        when(products.findById(held.id())).thenReturn(Optional.of(held));
        when(holds.isHeld(held.id())).thenReturn(true);

        ProductLiveGate.Reconciliation done = gate.reconcileChecked(held.id());

        assertThat(done.product().status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(done.photoCheckSkipped()).isTrue();
        verify(metrics).mediaUnavailable();
        verify(products, never()).update(any());
        verify(events, never()).publish(eq(CatalogEvents.PRODUCT_RESUMED), any());
    }

    @Test
    void theSweepCompletesClosesTheUntaxedLiveListingAndLeavesTheHeldOneHeldWhileMediaIsDown() {
        Product offender = product(ProductStatus.LIVE, noDecision());
        Product held = product(ProductStatus.IN_REVIEW, resolved(), heldPhoto());
        when(products.findLiveWithoutTaxDecision(eq(0), anyInt())).thenReturn(List.of(offender)).thenReturn(List.of());
        when(products.findById(offender.id())).thenReturn(Optional.of(offender));
        when(products.findById(held.id())).thenReturn(Optional.of(held));
        when(holds.openHolds(any(), anyInt())).thenReturn(List.of(held.id()));
        when(holds.isHeld(held.id())).thenReturn(true);

        TaxGateSweepService.Result result = sweep.sweepNow();

        assertThat(result.closed()).isEqualTo(1);
        assertThat(result.reopened()).isZero();
        assertThat(result.photoCheckSkipped()).isEqualTo(1);
        verify(holds).hold(eq(offender.id()), any(), any());
        verify(products, never()).update(argThat(p -> p.id().equals(held.id())));
        verify(metrics, times(1)).mediaUnavailable();
    }

    @Test
    void theScheduledSweepAlsoSurvivesAMediaOutage() {
        Product held = product(ProductStatus.IN_REVIEW, resolved(), heldPhoto());
        when(products.findLiveWithoutTaxDecision(anyInt(), anyInt())).thenReturn(List.of());
        when(products.findById(held.id())).thenReturn(Optional.of(held));
        when(holds.openHolds(any(), anyInt())).thenReturn(List.of(held.id()));
        when(holds.isHeld(held.id())).thenReturn(true);

        assertThat(sweep.sweep()).isZero();
    }

    @Test
    void approveAndResumeStillRefuseWhenMediaIsDownBecausePublishingNeedsIt() {
        Product p = product(ProductStatus.IN_REVIEW, resolved(), heldPhoto());
        assertThatThrownBy(() -> gate.approve(p, NOW)).isInstanceOf(ImageServiceUnavailable.class);
        Product paused = product(ProductStatus.PAUSED, resolved(), heldPhoto());
        assertThatThrownBy(() -> gate.resume(paused, NOW)).isInstanceOf(ImageServiceUnavailable.class);
        verify(holds, never()).release(any(), any());
    }
}
