package com.afrotransact.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import com.afrotransact.inventory.application.CycleCountService.CountedLine;
import com.afrotransact.inventory.application.CycleCountService.StartCount;
import com.afrotransact.inventory.application.LotService.TransferCommand;
import com.afrotransact.inventory.domain.CycleCount;
import com.afrotransact.inventory.domain.CycleCountReason;
import com.afrotransact.inventory.domain.FcAssignmentPolicy;
import com.afrotransact.inventory.domain.InboundShipment;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.PostalAddress;
import com.afrotransact.inventory.domain.StockImport;
import com.afrotransact.inventory.port.CarrierGateway;
import com.afrotransact.inventory.port.CycleCountRepository;
import com.afrotransact.inventory.port.CycleCountRepository.CountQuery;
import com.afrotransact.inventory.port.ExchangeRateRepository;
import com.afrotransact.inventory.port.IdempotencyStore;
import com.afrotransact.inventory.port.InboundShipmentRepository;
import com.afrotransact.inventory.port.InboundShipmentRepository.InboundQuery;
import com.afrotransact.inventory.port.InventoryInsightsRepository;
import com.afrotransact.inventory.port.InventoryMetrics;
import com.afrotransact.inventory.port.LedgerRepository;
import com.afrotransact.inventory.port.LedgerRepository.MovementQuery;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.LotRepository.LotQuery;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.inventory.port.PositionRepository.PositionQuery;
import com.afrotransact.inventory.port.ReconciliationRepository;
import com.afrotransact.inventory.port.ReservationRepository;
import com.afrotransact.inventory.port.StockImportRepository;
import com.afrotransact.inventory.port.StorageRepository;
import com.afrotransact.inventory.port.UnitLabelClient;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Cross-tenant tests for inventory (prod runs without OpenFGA). Sellers hold {@code inventory:adjust} and
 * {@code inventory:inbound}, so those permissions alone must never reach another organisation's stock or platform
 * wide operations. Tenant A's seller is even given RECEIVE, RESERVE and RETURNS here (a mis-granted role) to prove the
 * principal-type checks hold. Named in tenant-isolation-matrix.txt.
 */
class InventoryTenantIsolationTest {

    private static final String ORG_A = TenantFixture.ORG_A;
    private static final String ORG_B = TenantFixture.ORG_B;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);

    private final TenantFixture tenants = new TenantFixture();
    private final InventoryAccess access = new InventoryAccess(tenants.permissions(), tenants.resolver());
    private final LotRepository lots = mock(LotRepository.class);
    private final CycleCountRepository counts = mock(CycleCountRepository.class);
    private final InboundShipmentRepository inbounds = mock(InboundShipmentRepository.class);
    private final StockImportRepository imports = mock(StockImportRepository.class);
    private final PositionRepository positions = mock(PositionRepository.class);
    private final LedgerRepository ledger = mock(LedgerRepository.class);
    private final NodeRepository nodes = mock(NodeRepository.class);
    private final StorageRepository storage = mock(StorageRepository.class);
    private final InventoryInsightsRepository insightsRepo = mock(InventoryInsightsRepository.class);
    private final StockWriter writer = mock(StockWriter.class);
    private final LotService lotService = mock(LotService.class);
    private LotServiceImpl lotsService;
    private CycleCountServiceImpl cycleCounts;
    private InboundShipmentServiceImpl inboundService;
    private StockImportServiceImpl importService;
    private StockQueryServiceImpl stockQuery;
    private StorageServiceImpl storageService;
    private NodeServiceImpl nodeService;
    private ExchangeRateServiceImpl exchange;
    private ReconciliationServiceImpl reconciliation;
    private LotLifecycleServiceImpl lifecycle;
    private ReturnServiceImpl returns;
    private ReceivingServiceImpl receiving;
    private InventoryInsightsServiceImpl insights;
    private ReservationServiceImpl reservations;
    private Lot lotOfB;
    private CycleCount countOfB;
    private InboundShipment shipmentOfB;
    private StockImport importOfB;

    @BeforeEach
    void setUp() {
        InventorySettings settings = mock(InventorySettings.class);
        when(settings.retailOrgId()).thenReturn("org_retail");
        lotsService = new LotServiceImpl(lots, positions, nodes, writer, mock(IdempotencyStore.class), access, CLOCK);
        cycleCounts = new CycleCountServiceImpl(counts, lots, nodes, lotService, access, CLOCK);
        inboundService = new InboundShipmentServiceImpl(inbounds, nodes, positions,
                mock(FcAssignmentPolicy.class), mock(UnitLabelClient.class),
                mock(CarrierGateway.class), mock(ReceivingService.class), writer, access, settings,
                mock(DomainEventPublisher.class), mock(InboxDeduplicator.class), CLOCK);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        importService = new StockImportServiceImpl(imports, positions, nodes, mock(StockImportJob.class), access,
                settings, mock(Executor.class), CLOCK, tm);
        stockQuery = new StockQueryServiceImpl(positions, ledger, access);
        storageService = new StorageServiceImpl(storage, access, tenants.resolver(), settings, CLOCK);
        nodeService = new NodeServiceImpl(nodes, access);
        exchange = new ExchangeRateServiceImpl(mock(ExchangeRateRepository.class), access, settings, CLOCK);
        reconciliation = new ReconciliationServiceImpl(positions, ledger, lots, mock(ReconciliationRepository.class),
                null, mock(InventoryMetrics.class), mock(DomainEventPublisher.class), access, CLOCK);
        lifecycle = new LotLifecycleServiceImpl(nodes, lots, positions, writer, access,
                mock(TransactionOperations.class), CLOCK);
        returns = new ReturnServiceImpl(mock(ReservationRepository.class), positions, lots, ledger, nodes, writer,
                mock(IdempotencyStore.class), access, CLOCK);
        receiving = new ReceivingServiceImpl(nodes, positions, lots, null, writer, mock(IdempotencyStore.class),
                access, settings, inbounds, null, mock(DomainEventPublisher.class), CLOCK);
        insights = new InventoryInsightsServiceImpl(insightsRepo, lots, ledger, access,
                settings);
        reservations = new ReservationServiceImpl(mock(ReservationRepository.class), positions, lots, nodes, null,
                writer, access, mock(DomainEventPublisher.class), mock(InventoryMetrics.class), settings, CLOCK);

        lotOfB = mock(Lot.class);
        when(lotOfB.ownerOrg()).thenReturn(ORG_B);
        when(lotOfB.sku()).thenReturn("SKU-B");
        UUID lotId = UUID.randomUUID();
        when(lotOfB.id()).thenReturn(lotId);
        when(lots.findById(lotId)).thenReturn(Optional.of(lotOfB));
        when(lots.lockById(lotId)).thenReturn(Optional.of(lotOfB));
        countOfB = mock(CycleCount.class);
        when(countOfB.ownerOrg()).thenReturn(ORG_B);
        when(counts.findById(any())).thenReturn(Optional.empty());
        UUID countId = UUID.randomUUID();
        when(countOfB.id()).thenReturn(countId);
        when(counts.findById(countId)).thenReturn(Optional.of(countOfB));
        when(counts.lockById(countId)).thenReturn(Optional.of(countOfB));
        shipmentOfB = mock(InboundShipment.class);
        when(shipmentOfB.ownerOrg()).thenReturn(ORG_B);
        UUID shipId = UUID.randomUUID();
        when(shipmentOfB.id()).thenReturn(shipId);
        when(inbounds.findById(shipId)).thenReturn(Optional.of(shipmentOfB));
        when(inbounds.lockById(shipId)).thenReturn(Optional.of(shipmentOfB));
        importOfB = mock(StockImport.class);
        when(importOfB.ownerOrg()).thenReturn(ORG_B);
        UUID impId = UUID.randomUUID();
        when(importOfB.id()).thenReturn(impId);
        when(imports.findById(impId)).thenReturn(Optional.of(importOfB));
        when(imports.lock(impId)).thenReturn(Optional.of(importOfB));
        when(lots.search(any())).thenReturn(new Page<>(List.of(), 0, 0, 25));
        when(counts.search(any())).thenReturn(new Page<>(List.of(), 0, 0, 25));
        when(inbounds.search(any())).thenReturn(new Page<>(List.of(), 0, 0, 25));
        when(positions.search(any())).thenReturn(new Page<>(List.of(), 0, 0, 25));
        when(ledger.search(any())).thenReturn(new Page<>(List.of(), 0, 0, 25));
        when(imports.list(anyString(), anyInt(), anyInt()))
                .thenReturn(new Page<>(List.of(), 0, 0, 25));
        tenants.as(sellerA());
    }

    /** A seller as roles seed them (read, adjust, inbound) plus mis-granted platform permissions. */
    private static Actor sellerA() {
        return TenantFixture.sellerOf(ORG_A, InventoryPermissions.READ, InventoryPermissions.ADJUST,
                InventoryPermissions.INBOUND, InventoryPermissions.RECEIVE, InventoryPermissions.RESERVE,
                InventoryPermissions.RETURNS);
    }

    private static void forbidden(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ForbiddenActionException.class);
    }

    // ------------------------------------------------------------------ lots (ORG_OWNED)

    @Test
    void aSellerCannotReadOrChangeAnotherOrganisationsLot() {
        UUID id = lotOfB.id();
        CrossTenant.assertDenied(() -> lotsService.get(id));
        CrossTenant.assertDenied(() -> lotsService.quarantine(id, "why"));
        CrossTenant.assertDenied(() -> lotsService.release(id, "why"));
        CrossTenant.assertDenied(() -> lotsService.writeOff(id, 1, "why", null));
        CrossTenant.assertDenied(() -> lotsService.adjust(id, 1, "why", null));
        CrossTenant.assertDenied(() -> lotsService.transfer(id, new TransferCommand("HOU", null, 1, "why"), null));
        verify(writer, never()).begin(anyString());
        CrossTenant.assertDenied(() -> insights.lotTrace(id));
    }

    @Test
    void lotAndStockListsAreFilteredByTheCallersOrgWhateverIsAsked() {
        lotsService.search(new LotQuery(null, null, ORG_B, null, null, 0, 25));
        ArgumentCaptor<LotQuery> lq = ArgumentCaptor.forClass(LotQuery.class);
        verify(lots).search(lq.capture());
        assertThat(lq.getValue().ownerOrg()).isEqualTo(ORG_A);

        stockQuery.positions(new PositionQuery(null, null, ORG_B, false, 0, 25));
        ArgumentCaptor<PositionQuery> pq = ArgumentCaptor.forClass(PositionQuery.class);
        verify(positions).search(pq.capture());
        assertThat(pq.getValue().ownerOrg()).isEqualTo(ORG_A);

        stockQuery.movements(new MovementQuery(null, null, null, null, ORG_B, null, 0, 25));
        ArgumentCaptor<MovementQuery> mq = ArgumentCaptor.forClass(MovementQuery.class);
        verify(ledger).search(mq.capture());
        assertThat(mq.getValue().ownerOrg()).isEqualTo(ORG_A);
    }

    @Test
    void aSellerWithoutAnOrganisationReadsNoStock() {
        tenants.as(new Actor("u", PrincipalType.SELLER_MEMBER, Optional.empty(),
                Set.of(), Set.of(InventoryPermissions.READ)));
        forbidden(() -> stockQuery.positions(new PositionQuery(null, null, null, false, 0, 25)));
        forbidden(() -> lotsService.search(new LotQuery(null, null, null, null, null, 0, 25)));
        verify(positions, never()).search(any());
        verify(lots, never()).search(any());
    }

    @Test
    void markdownAndOverviewAreFilteredByTheCallersOrg() {
        lotsService.markdown(null);
        verify(lots).findMarkdown(eq(ORG_A), any());
        insights.overview(null, ORG_B);
        verify(insightsRepo).overview(any(), eq(ORG_A), anyInt());
        when(insightsRepo.fbaStock(any())).thenReturn(new Page<>(List.of(), 0, 0, 25));
        insights.fbaInventory(ORG_B, null, null, false, 0, 25);
        ArgumentCaptor<InventoryInsightsRepository.FbaQuery> fba =
                ArgumentCaptor.forClass(InventoryInsightsRepository.FbaQuery.class);
        verify(insightsRepo, times(2)).fbaStock(fba.capture());
        assertThat(fba.getAllValues()).allSatisfy(q -> assertThat(q.ownerOrg()).isEqualTo(ORG_A));
    }

    // ------------------------------------------------------------------ cycle counts

    @Test
    void aSellerCannotRecordPostOrCancelAnotherOrganisationsCount() {
        UUID id = countOfB.id();
        CrossTenant.assertDenied(() -> cycleCounts.get(id));
        CrossTenant.assertDenied(() -> cycleCounts.record(id,
                List.of(new CountedLine(1, 0, CycleCountReason.OTHER, null)), null));
        CrossTenant.assertDenied(() -> cycleCounts.post(id, null));
        CrossTenant.assertDenied(() -> cycleCounts.cancel(id, null));
        verify(counts, never()).update(any());
        verify(lotService, never()).adjust(any(), anyInt(), anyString(), anyString());
    }

    @Test
    void aSellerStartsCountsOnlyOverItsOwnStock() {
        Node node = mock(Node.class);
        when(node.id()).thenReturn("HOU");
        when(node.active()).thenReturn(true);
        when(nodes.findById("HOU")).thenReturn(Optional.of(node));
        when(lots.findForCount(anyString(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        assertThatThrownBy(() -> cycleCounts.start(new StartCount("HOU", null, null, ORG_B, false, null)))
                .isNotInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> cycleCounts.start(new StartCount("HOU", null, null, null, false, null)))
                .isNotInstanceOf(ForbiddenActionException.class);
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(lots, times(2)).findForCount(anyString(), any(), any(), owner.capture(),
                anyInt());
        assertThat(owner.getAllValues()).containsOnly(ORG_A);
    }

    @Test
    void cycleCountListIsPinnedToTheCallersOrg() {
        cycleCounts.list(new CountQuery(null, null, null, ORG_B, 0, 25));
        ArgumentCaptor<CountQuery> q = ArgumentCaptor.forClass(CountQuery.class);
        verify(counts).search(q.capture());
        assertThat(q.getValue().ownerOrg()).isEqualTo(ORG_A);
    }

    // ------------------------------------------------------------------ inbound shipments

    @Test
    void aSellerCannotSeeOrChangeAnotherOrganisationsInboundShipment() {
        UUID id = shipmentOfB.id();
        CrossTenant.assertDenied(() -> inboundService.get(id));
        CrossTenant.assertDenied(() -> inboundService.updatePlan(id, null, null));
        CrossTenant.assertDenied(() -> inboundService.labels(id, InboundShipmentService.LabelKind.UNIT, "PDF", null));
        CrossTenant.assertDenied(() -> inboundService.track(id, "UPS", "1Z"));
        CrossTenant.assertDenied(() -> inboundService.buyLabel(id, null));
        CrossTenant.assertDenied(() -> inboundService.ship(id, null));
        CrossTenant.assertDenied(() -> inboundService.cancel(id));
        verify(inbounds, never()).update(any());
    }

    @Test
    void inboundShipmentsAreStaffReceivedOnly() {
        UUID id = shipmentOfB.id();
        forbidden(() -> inboundService.receive(id, null, "k"));
        forbidden(() -> inboundService.close(id));
    }

    @Test
    void inboundListIsPinnedToTheCallersOrg() {
        inboundService.list(new InboundQuery(ORG_B, null, null, null, null, 0, 25));
        ArgumentCaptor<InboundQuery> q = ArgumentCaptor.forClass(InboundQuery.class);
        verify(inbounds).search(q.capture());
        assertThat(q.getValue().ownerOrg()).isEqualTo(ORG_A);
    }

    // ------------------------------------------------------------------ stock imports

    @Test
    void aSellerCannotReadOrStartAnotherOrganisationsStockImport() {
        UUID id = importOfB.id();
        CrossTenant.assertDenied(() -> importService.get(id));
        CrossTenant.assertDenied(() -> importService.rows(id, null, null, 0, 25));
        CrossTenant.assertDenied(() -> importService.report(id));
        CrossTenant.assertDenied(() -> importService.start(id, false));
        verify(imports, never()).update(any());
    }

    @Test
    void stockImportListIgnoresAnOrganisationNamedByASeller() {
        importService.list(ORG_B, 0, 25);
        verify(imports).list(ORG_A, 0, 25);
        verify(imports, never()).list(ORG_B, 0, 25);
    }

    // ------------------------------------------------------------------ storage

    @Test
    void storageUsageIsPinnedToTheCallersOrg() {
        storageService.usage(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), ORG_B);
        verify(storage).usage(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), ORG_A);
    }

    // ------------------------------------------------------------------ platform operations are staff only

    @Test
    void sellersCannotRunPlatformWideOperations() {
        forbidden(() -> nodeService.create(mock(Node.class)));
        forbidden(() -> nodeService.updateAddress("HOU", mock(PostalAddress.class)));
        forbidden(() -> exchange.set("NGN", LocalDate.of(2026, 11, 1), BigDecimal.ONE, null, "why"));
        forbidden(() -> reconciliation.runNow());
        forbidden(() -> lifecycle.expireNow());
        forbidden(() -> lifecycle.markdownNow());
        forbidden(() -> storageService.snapshotToday());
        forbidden(() -> storageService.setDimensions("SKU-B", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE));
        verify(storage, never()).saveDimensions(any(), anyString(), any());
        verify(nodes, never()).updateAddress(anyString(), any());
    }

    @Test
    void sellersCannotPostReturnsReceiptsOrReservations() {
        forbidden(() -> returns.accept(null, "k"));
        forbidden(() -> receiving.receive(null, "k"));
        forbidden(() -> receiving.expectInbound(null));
        forbidden(() -> reservations.reserve(null));
        forbidden(() -> reservations.get("order-1"));
        forbidden(() -> reservations.commit("order-1"));
        forbidden(() -> reservations.release("order-1", "x"));
        forbidden(() -> reconciliation.latest().ifPresent(r -> {
        }));
        forbidden(() -> exchange.current(null));
    }
}
