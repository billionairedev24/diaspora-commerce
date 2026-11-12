package com.afrotransact.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotCost;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.MarkdownFlag;
import com.afrotransact.inventory.domain.Movement;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.domain.StockImport;
import com.afrotransact.inventory.domain.StockImportMode;
import com.afrotransact.inventory.domain.StockImportRow;
import com.afrotransact.inventory.domain.StockImportStatus;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.domain.StockRowOutcome;
import com.afrotransact.inventory.port.InventoryMetrics;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.inventory.port.StockImportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * S395: the job writes BULK_COUNT movements for the seller's own stock only, never below the reserved units, and a row is
 * recorded in the same transaction as its stock change.
 */
class StockImportJobTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final UUID IMPORT = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final String ORG = "org_seller";

    private final StockImportRepository imports = mock(StockImportRepository.class);
    private final PositionRepository positions = mock(PositionRepository.class);
    private final LotRepository lots = mock(LotRepository.class);
    private final StockWriter writer = mock(StockWriter.class);
    private final InventoryMetrics metrics = mock(InventoryMetrics.class);
    private StockImportJobImpl job;
    private StockChanges changes;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        InventorySettings settings = new InventorySettings("org_retail", Duration.ofMinutes(15), 7, 200,
                BigDecimal.ONE, List.of("USD"));
        job = new StockImportJobImpl(imports, positions, lots, writer, metrics, settings,
                Clock.fixed(NOW, ZoneOffset.UTC), tm);
        changes = new StockChanges("user_seller", "corr", NOW);
        when(writer.begin(anyString())).thenReturn(changes);
        when(imports.update(any())).thenAnswer(i -> i.getArgument(0));
        when(imports.setOutcome(any(), anyInt(), any(), any(), any(), any())).thenReturn(1);
        when(imports.outcomeCounts(IMPORT)).thenReturn(Map.of());
    }

    private static StockImport upload(StockImportMode mode, String org) {
        return new StockImport(IMPORT, "SI-000001", org, "user_seller", "stock.csv", "abc", null, mode,
                List.of("Seller SKU", "Location", "On hand"), StockImportStatus.QUEUED, 1, 1, 0, 0, 0, 0, 0, 0, NOW, null,
                null, null, 0);
    }

    private static StockImportRow row(int n, String sku, String node, Integer qty, String lot, LocalDate expiry) {
        return new StockImportRow(n, sku, node, lot, qty, expiry, "VALID", List.of(), List.of(), StockRowOutcome.PENDING,
                null, null, null);
    }

    private static Lot lot(String lotNo, int onHand, int reserved, LocalDate expiry, LotStatus status, String owner) {
        return new Lot(UUID.randomUUID(), "SKU-1", lotNo, "HOU-FC", owner, "A-01", onHand, reserved, null, expiry, NOW,
                LotCost.usd(192, LocalDate.of(2026, 10, 10)), status, null, MarkdownFlag.NONE, "GRN-1", 0);
    }

    private void given(StockImport u, StockImportRow row, StockPosition position, Lot... lotsOfSku) {
        when(imports.lock(IMPORT)).thenReturn(Optional.of(u));
        when(imports.pendingRows(eq(IMPORT), anyInt())).thenReturn(List.of(row)).thenReturn(List.of());
        when(positions.lockBySkus(any())).thenReturn(position == null ? List.of() : List.of(position));
        when(lots.findBySkuAndNode(anyString(), anyString(), anyString())).thenReturn(List.of(lotsOfSku));
        for (Lot l : lotsOfSku) {
            when(lots.lockById(l.id())).thenReturn(Optional.of(l));
        }
    }

    private static StockPosition position(String owner, int onHand, int reserved) {
        return new StockPosition("SKU-1", "HOU-FC", owner, onHand, reserved, 0, 0, 3);
    }

    private void drive() {
        job.drive(IMPORT);
    }

    private void outcome(StockRowOutcome expected, String noteContains) {
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(imports).setOutcome(eq(IMPORT), eq(2), eq(expected), note.capture(), any(), any());
        if (noteContains != null) {
            assertThat(note.getValue()).contains(noteContains);
        }
    }

    @Test
    void anAbsoluteCountWritesABulkCountMovementWithTheImportIdAndRecordsTheRowInTheSameTransaction() {
        Lot lot = lot("LOT-A", 100, 10, null, LotStatus.SELLABLE, ORG);
        given(upload(StockImportMode.ABSOLUTE, ORG), row(2, "SKU-1", "HOU-FC", 120, null, null),
                position(ORG, 100, 10), lot);

        drive();

        verify(writer).flush(changes);
        Movement m = changes.movements().getFirst();
        assertThat(m.type()).isEqualTo(MovementType.ADJUST);
        assertThat(m.reason()).startsWith("BULK_COUNT").contains(IMPORT.toString()).contains("row 2");
        assertThat(m.reference()).isEqualTo(IMPORT.toString());
        assertThat(m.before().onHand()).isEqualTo(100);
        assertThat(m.after().onHand()).isEqualTo(120);
        assertThat(m.actor()).isEqualTo("user_seller");
        assertThat(changes.lot(lot.id()).qtyOnHand()).isEqualTo(120);
        assertThat(changes.lot(lot.id()).qtyReserved()).isEqualTo(10);
        ArgumentCaptor<Integer> after = ArgumentCaptor.forClass(Integer.class);
        verify(imports).setOutcome(eq(IMPORT), eq(2), eq(StockRowOutcome.APPLIED), isNull(), eq(100), after.capture());
        assertThat(after.getValue()).isEqualTo(120);
        verify(metrics).stockImportRow("APPLIED");
    }

    @Test
    void aCountBelowTheReservedUnitsIsARowErrorAndNothingIsWritten() {
        Lot lot = lot("LOT-A", 100, 40, null, LotStatus.SELLABLE, ORG);
        given(upload(StockImportMode.ABSOLUTE, ORG), row(2, "SKU-1", "HOU-FC", 30, null, null), position(ORG, 100, 40), lot);

        drive();

        outcome(StockRowOutcome.FAILED, "40 units reserved for orders; the count would leave 30");
        verify(writer, never()).flush(any());
        assertThat(changes.movements()).isEmpty();
    }

    @Test
    void aCountEqualToWhatIsOnHandChangesNothingAndWritesNoMovement() {
        Lot lot = lot("LOT-A", 100, 0, null, LotStatus.SELLABLE, ORG);
        given(upload(StockImportMode.ABSOLUTE, ORG), row(2, "SKU-1", "HOU-FC", 100, null, null), position(ORG, 100, 0), lot);

        drive();

        outcome(StockRowOutcome.UNCHANGED, "No change");
        verify(writer, never()).flush(any());
    }

    @Test
    void aDeltaAddsToWhatIsOnHandAndCannotGoNegative() {
        Lot lot = lot("LOT-A", 10, 0, null, LotStatus.SELLABLE, ORG);
        given(upload(StockImportMode.DELTA, ORG), row(2, "SKU-1", "HOU-FC", -4, null, null), position(ORG, 10, 0), lot);

        drive();

        assertThat(changes.lot(lot.id()).qtyOnHand()).isEqualTo(6);
        assertThat(changes.movements().getFirst().reason()).endsWith("delta");
    }

    @Test
    void aDeltaBelowZeroOrOverTheLimitIsARowError() {
        Lot lot = lot("LOT-A", 10, 0, null, LotStatus.SELLABLE, ORG);
        given(upload(StockImportMode.DELTA, ORG), row(2, "SKU-1", "HOU-FC", -11, null, null), position(ORG, 10, 0), lot);

        drive();

        outcome(StockRowOutcome.FAILED, "would leave less than none");
        verify(writer, never()).flush(any());
    }

    @Test
    void stockOfAnotherOwnerIsNeverTouchedAndLooksUnknown() {
        Lot lot = lot("LOT-A", 100, 0, null, LotStatus.SELLABLE, "org_other");
        given(upload(StockImportMode.ABSOLUTE, ORG), row(2, "SKU-1", "HOU-FC", 5, null, null),
                position("org_other", 100, 0), lot);

        drive();

        outcome(StockRowOutcome.FAILED, "We do not hold SKU-1 for you at HOU-FC");
        verify(writer, never()).flush(any());
        verify(lots, never()).lockById(any());
    }

    @Test
    void afroTransactRetailsOwnStockIsNeverChangedByAFile() {
        StockImport retail = upload(StockImportMode.ABSOLUTE, "org_retail");
        given(retail, row(2, "SKU-1", "HOU-FC", 5, null, null), position("org_retail", 100, 0),
                lot("LOT-A", 100, 0, null, LotStatus.SELLABLE, "org_retail"));

        drive();

        outcome(StockRowOutcome.FAILED, "AfroTransact's own stock cannot be changed with a file");
        verify(positions, never()).lockBySkus(any());
        verify(writer, never()).flush(any());
    }

    @Test
    void severalLotsNeedALotNumberAndTheLotOrExpiryPicksOne() {
        Lot a = lot("LOT-A", 10, 0, LocalDate.of(2027, 1, 31), LotStatus.SELLABLE, ORG);
        Lot b = lot("LOT-B", 20, 0, LocalDate.of(2027, 6, 30), LotStatus.SELLABLE, ORG);
        given(upload(StockImportMode.ABSOLUTE, ORG), row(2, "SKU-1", "HOU-FC", 5, null, null), position(ORG, 30, 0), a, b);

        drive();

        outcome(StockRowOutcome.FAILED, "has several lots at HOU-FC. Give the lot number");
    }

    @Test
    void theExpiryPicksTheLotWhenNoLotNumberIsGiven() {
        Lot a = lot("LOT-A", 10, 0, LocalDate.of(2027, 1, 31), LotStatus.SELLABLE, ORG);
        Lot b = lot("LOT-B", 20, 0, LocalDate.of(2027, 6, 30), LotStatus.SELLABLE, ORG);
        given(upload(StockImportMode.ABSOLUTE, ORG), row(2, "SKU-1", "HOU-FC", 25, null, LocalDate.of(2027, 6, 30)),
                position(ORG, 30, 0), a, b);

        drive();

        assertThat(changes.lot(b.id()).qtyOnHand()).isEqualTo(25);
        verify(lots, never()).lockById(a.id());
    }

    @Test
    void aHeldLotChangesTheHeldBucketNotTheSellableOne() {
        Lot quarantined = lot("LOT-Q", 10, 0, null, LotStatus.QUARANTINE, ORG);
        StockPosition p = new StockPosition("SKU-1", "HOU-FC", ORG, 0, 0, 0, 10, 1);
        given(upload(StockImportMode.ABSOLUTE, ORG), row(2, "SKU-1", "HOU-FC", 12, null, null), p, quarantined);

        drive();

        assertThat(changes.requirePosition("SKU-1", "HOU-FC").damaged()).isEqualTo(12);
        assertThat(changes.requirePosition("SKU-1", "HOU-FC").onHand()).isZero();
    }

    @Test
    void aRowAnotherRunFinishedFirstIsRolledBackAndNotCounted() {
        Lot lot = lot("LOT-A", 100, 0, null, LotStatus.SELLABLE, ORG);
        given(upload(StockImportMode.ABSOLUTE, ORG), row(2, "SKU-1", "HOU-FC", 120, null, null), position(ORG, 100, 0), lot);
        when(imports.setOutcome(any(), anyInt(), any(), any(), any(), any())).thenReturn(0);

        drive();

        verify(metrics, never()).stockImportRow(anyString());
    }
}
