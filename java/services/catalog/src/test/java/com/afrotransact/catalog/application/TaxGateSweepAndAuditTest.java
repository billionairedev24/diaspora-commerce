package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
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
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductTaxHoldRepository;
import com.afrotransact.catalog.port.TaxDecisionAuthority;
import com.afrotransact.catalog.port.TaxGateMetrics;
import com.afrotransact.platform.security.PermissionChecker;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** The audit list, its count and the sweep that closes anything the gate missed (should never find anything). */
class TaxGateSweepAndAuditTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductTaxHoldRepository holds = mock(ProductTaxHoldRepository.class);
    private final ProductLiveGate liveGate = mock(ProductLiveGate.class);
    private final ProductEvents events = mock(ProductEvents.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final TaxDecisionAuthority taxReturns = mock(TaxDecisionAuthority.class);
    private final TaxGateMetrics metrics = mock(TaxGateMetrics.class);
    private final TaxAuthorityCheck authority = new TaxAuthorityCheck(taxReturns, metrics);

    private static Product live(String atin, TaxStatus status) {
        Variant v = new Variant(UUID.randomUUID(), new Atin(atin), "SKU-" + atin, null, true, Map.of(), 100, "USD", 1, null,
                List.of(), false, new VariantTax(Map.of(), status, List.of(), null));
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), new ListingOwner(ListingOwnerKind.SELLER, "org_1"),
                UUID.randomUUID(), null, "grocery", "Ofada rice", Map.of(), List.<ProductImage>of(), "", List.of(v),
                ProductStatus.LIVE, null, NOW, NOW, 0);
    }

    private TaxGateSweepServiceImpl sweep() {
        return new TaxGateSweepServiceImpl(products, holds, liveGate, events, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class), authority, categories, permissions, 200, Duration.ofMinutes(5));
    }

    @Test
    void theAuditNeedsTheStaffTaxPermissionAndNamesWhatIsMissingPerVariantInPlainWords() {
        Product p = live("AT00000011", TaxStatus.MISSING_FACTS);
        when(products.findLiveAfter(any(), anyInt())).thenReturn(List.of(p));
        LiveTaxAuditServiceImpl audit = new LiveTaxAuditServiceImpl(products, categories, permissions, authority);

        LiveTaxAuditService.Audit page = audit.liveWithoutTaxDecision(0, 25);

        verify(permissions).require("tax:read");
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(i -> {
            assertThat(i.title()).isEqualTo("Ofada rice");
            assertThat(i.variants().getFirst().reason()).isEqualTo("Some answers are still needed before this can go live.");
        });
    }

    @Test
    void aReadyStatusWithNoStoredDecisionIsListedWithAPlainReason() {
        Product p = live("AT00000011", TaxStatus.READY);
        when(products.findLiveAfter(any(), anyInt())).thenReturn(List.of(p));
        LiveTaxAuditService.Audit page = new LiveTaxAuditServiceImpl(products, categories, permissions, authority).liveWithoutTaxDecision(0, 25);
        assertThat(page.items().getFirst().variants().getFirst().reason()).isNotBlank();
    }

    @Test
    void theCountIsZeroWhenTheGateHolds() {
        when(products.countLiveWithoutTaxDecision()).thenReturn(0L);
        assertThat(new LiveTaxAuditServiceImpl(products, categories, permissions, authority).count()).isZero();
    }

    @Test
    void theSweepClosesEveryLiveListingTheAuditFindsAndAnnouncesMigrationClosures() {
        Product offender = live("AT00000011", TaxStatus.PENDING_REVIEW);
        Product closed = offender.holdForTax(NOW);
        when(products.findLiveWithoutTaxDecision(eq(0), anyInt())).thenReturn(List.of(offender)).thenReturn(List.of());
        when(liveGate.reconcile(offender.id())).thenReturn(closed);
        UUID migrated = UUID.randomUUID();
        Product migratedProduct = live("AT00000012", TaxStatus.MISSING_FACTS).holdForTax(NOW);
        when(holds.unannounced(anyInt())).thenReturn(List.of(new ProductTaxHoldRepository.Held(migrated, "TAX_DECISION_MISSING")));
        when(products.findById(migrated)).thenReturn(Optional.of(migratedProduct));
        when(holds.openHolds(any(), anyInt())).thenReturn(List.of());

        int changed = sweep().sweep();

        assertThat(changed).isEqualTo(2);
        verify(liveGate).reconcile(offender.id());
        verify(events).publishWithReason(eq(CatalogEvents.PRODUCT_PAUSED), eq(migratedProduct), eq("TAX_DECISION_MISSING"));
        verify(holds).markAnnounced(eq(migrated), any());
    }

    @Test
    void theSweepReopensAListingHeldForTaxThatIsNowResolved() {
        UUID id = UUID.randomUUID();
        Product back = live("AT00000011", TaxStatus.READY);
        when(products.findLiveWithoutTaxDecision(anyInt(), anyInt())).thenReturn(List.of());
        when(holds.openHolds(any(), anyInt())).thenReturn(List.of(id));
        when(liveGate.reconcile(id)).thenReturn(back);
        when(holds.unannounced(anyInt())).thenReturn(List.of());
        assertThat(sweep().sweep()).isEqualTo(1);
    }

    @Test
    void anEmptySweepDoesNothing() {
        when(products.findLiveWithoutTaxDecision(anyInt(), anyInt())).thenReturn(List.of());
        when(holds.openHolds(any(), anyInt())).thenReturn(List.of());
        when(holds.unannounced(anyInt())).thenReturn(List.of());
        assertThat(sweep().sweep()).isZero();
    }

    @Test
    void theAuditListsAListingCatalogCallsReadyWhenTaxReturnsIsNotResolvedAndSaysWhy() {
        Product p = live("AT00000011", TaxStatus.READY).withVariants(List.of(new Variant(UUID.randomUUID(),
                new Atin("AT00000011"), "SKU-1", null, true, Map.of(), 100, "USD", 1, null, List.of(), false,
                new VariantTax(Map.of(), TaxStatus.READY, List.of(), new TaxDecision(
                        "EXEMPT", "AT.X", "S1", "0%", "v1", "r")))));
        when(products.findLiveAfter(any(), anyInt())).thenReturn(List.of(p));
        when(taxReturns.consulted()).thenReturn(true);
        when(taxReturns.ask(any())).thenReturn(Map.of("AT00000011",
                new TaxDecisionAuthority.Answer("AT00000011", "NEEDS_APPROVAL", "PENDING_REVIEW", "x")));

        LiveTaxAuditService.Audit page = new LiveTaxAuditServiceImpl(products, categories, permissions, authority)
                .liveWithoutTaxDecision(0, 25);

        assertThat(page.authorityChecked()).isTrue();
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().variants().getFirst().taxStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(page.items().getFirst().variants().getFirst().reason()).isEqualTo("Tax review has not approved this item yet.");
    }

    @Test
    void theAuditSaysItIsIncompleteWhenTaxReturnsCannotBeReached() {
        Product p = live("AT00000011", TaxStatus.MISSING_FACTS);
        when(products.findLiveAfter(any(), anyInt())).thenReturn(List.of(p));
        when(taxReturns.consulted()).thenReturn(true);
        when(taxReturns.ask(any())).thenThrow(new TaxDecisionAuthority.Unavailable("down", null));

        LiveTaxAuditService.Audit page = new LiveTaxAuditServiceImpl(products, categories, permissions, authority)
                .liveWithoutTaxDecision(0, 25);

        assertThat(page.authorityChecked()).isFalse();
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void theSweepClosesALiveListingTaxReturnsDoesNotResolveAndReReportsItsFactsWhenThereIsNoRecord() {
        Product ready = live("AT00000011", TaxStatus.READY);
        when(products.findLiveAfter(any(), anyInt())).thenReturn(List.of(ready));
        when(taxReturns.consulted()).thenReturn(true);
        when(taxReturns.ask(any())).thenReturn(Map.of());
        when(liveGate.closeForTaxReturns(ready.id(), "TAX_RETURNS_NO_DECISION")).thenReturn(ready.holdForTax(NOW));
        when(holds.openHolds(any(), anyInt())).thenReturn(List.of());
        when(holds.unannounced(anyInt())).thenReturn(List.of());

        TaxGateSweepService.Result result = sweep().sweepNow();

        assertThat(result.closedByTaxReturns()).isEqualTo(1);
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.authorityReachable()).isTrue();
        verify(liveGate).closeForTaxReturns(ready.id(), "TAX_RETURNS_NO_DECISION");
    }

    @Test
    void theSweepKeepsEveryStateAndSaysSoWhenTaxReturnsCannotBeReached() {
        Product ready = live("AT00000011", TaxStatus.READY);
        when(products.findLiveAfter(any(), anyInt())).thenReturn(List.of(ready));
        when(taxReturns.consulted()).thenReturn(true);
        when(taxReturns.ask(any())).thenThrow(new TaxDecisionAuthority.Unavailable("down", null));
        when(holds.openHolds(any(), anyInt())).thenReturn(List.of());
        when(holds.unannounced(anyInt())).thenReturn(List.of());

        TaxGateSweepService.Result result = sweep().sweepNow();

        assertThat(result.authorityReachable()).isFalse();
        assertThat(result.closedByTaxReturns()).isZero();
        verify(liveGate, never()).closeForTaxReturns(any(), any());
        verify(metrics).authorityUnreachable();
    }

    @Test
    void theStaffRepairRunNeedsTheClassifyAdminPermission() {
        when(holds.openHolds(any(), anyInt())).thenReturn(List.of());
        when(holds.unannounced(anyInt())).thenReturn(List.of());
        sweep().sweepNowAsStaff();
        verify(permissions).require("tax:classify-admin");
    }
}
