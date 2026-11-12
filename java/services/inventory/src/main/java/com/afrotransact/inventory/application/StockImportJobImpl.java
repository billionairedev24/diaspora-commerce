package com.afrotransact.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.InventoryStateConflict;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.domain.StockImport;
import com.afrotransact.inventory.domain.StockImportMode;
import com.afrotransact.inventory.domain.StockImportRow;
import com.afrotransact.inventory.domain.StockImportValidator;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.domain.StockRowOutcome;
import com.afrotransact.inventory.port.InventoryMetrics;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.inventory.port.StockImportRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class StockImportJobImpl implements StockImportJob {

    private static final Logger LOG = LoggerFactory.getLogger(StockImportJobImpl.class);
    private static final int BATCH = 100;

    private final StockImportRepository imports;
    private final PositionRepository positions;
    private final LotRepository lots;
    private final StockWriter writer;
    private final InventoryMetrics metrics;
    private final InventorySettings settings;
    private final Clock clock;
    private final TransactionTemplate tx;

    StockImportJobImpl(StockImportRepository imports, PositionRepository positions, LotRepository lots,
                       StockWriter writer, InventoryMetrics metrics, InventorySettings settings, Clock clock, PlatformTransactionManager transactions) {
        this.imports = imports;
        this.positions = positions;
        this.lots = lots;
        this.writer = writer;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void drive(UUID importId) {
        try {
            while (true) {
                Batch batch = tx.execute(s -> next(importId));
                if (batch == null) {
                    return;
                }
                int applied = 0;
                int unchanged = 0;
                int failed = 0;
                for (StockImportRow row : batch.rows()) {
                    StockRowOutcome outcome = applyOne(batch.upload(), row);
                    if (outcome == null) {
                        continue;
                    }
                    metrics.stockImportRow(outcome.name());
                    switch (outcome) {
                        case APPLIED -> applied++;
                        case UNCHANGED -> unchanged++;
                        default -> failed++;
                    }
                }
                int done = applied + unchanged + failed;
                tx.executeWithoutResult(s -> imports.lock(importId).filter(u -> u.status().active())
                        .ifPresent(u -> imports.update(u.progressed(done, 0, 0, 0, Instant.now(clock)))));
            }
        } catch (RuntimeException ex) {
            // The import stays RUNNING: the resume job picks it up after the stall time.
            LOG.error("Stock import {} stopped; it will be resumed", importId, ex);
        }
    }

    private record Batch(StockImport upload, List<StockImportRow> rows) {
    }

    /** Locks the import, records progress and returns the next pending rows, or finishes the import. */
    private Batch next(UUID importId) {
        StockImport u = imports.lock(importId).orElse(null);
        if (u == null || !u.status().active()) {
            return null;
        }
        Instant now = Instant.now(clock);
        List<StockImportRow> rows = imports.pendingRows(importId, BATCH);
        if (rows.isEmpty()) {
            Map<StockRowOutcome, Long> counts = imports.outcomeCounts(importId);
            int applied = count(counts, StockRowOutcome.APPLIED);
            int unchanged = count(counts, StockRowOutcome.UNCHANGED);
            int failed = count(counts, StockRowOutcome.FAILED);
            int skipped = count(counts, StockRowOutcome.SKIPPED);
            StockImport done = imports.update(u.recounted(applied + unchanged + failed + skipped, applied, unchanged,
                    failed).completed(now));
            LOG.info("Stock import {} done: {} applied, {} unchanged, {} failed, {} skipped", done.reference(), applied,
                    unchanged, failed, skipped);
            return null;
        }
        return new Batch(imports.update(u.running(now)), rows);
    }

    private static int count(Map<StockRowOutcome, Long> counts, StockRowOutcome outcome) {
        return counts.getOrDefault(outcome, 0L).intValue();
    }

    /** One row in its own transaction; null when another run already finished it. */
    private StockRowOutcome applyOne(StockImport u, StockImportRow row) {
        try {
            return tx.execute(s -> apply(u, row));
        } catch (AlreadyDone ex) {
            return null;
        }
    }

    /** Thrown inside a row's transaction to roll its stock change back when another run finished the row first. */
    private static final class AlreadyDone extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AlreadyDone() {
            super("row already finished", null, false, false);
        }
    }

    private StockRowOutcome apply(StockImport u, StockImportRow row) {
        try {
            return applyStock(u, row);
        } catch (InventoryRuleViolation | InventoryStateConflict | ConflictException ex) {
            // Nothing was written for this row (the exception came before the flush): say why in the report.
            return record(u, row, StockRowOutcome.FAILED, ex.getMessage(), null, null);
        }
    }

    private StockRowOutcome applyStock(StockImport u, StockImportRow row) {
        String org = u.ownerOrg();
        if (org.equals(settings.retailOrgId())) {
            return record(u, row, StockRowOutcome.FAILED, "AfroTransact's own stock cannot be changed with a file", null,
                    null);
        }
        StockChanges changes = writer.begin(u.uploadedBy());
        List<StockPosition> locked = positions.lockBySkus(List.of(row.sku()));
        locked.forEach(changes::track);
        StockPosition position = locked.stream().filter(p -> p.nodeId().equals(row.nodeId())).findFirst().orElse(null);
        if (position == null || !position.ownerOrg().equals(org)) {
            return record(u, row, StockRowOutcome.FAILED, "We do not hold " + row.sku() + " for you at " + row.nodeId()
                    + ". Check the SKU and the location", null, null);
        }
        List<Lot> candidates = lots.findBySkuAndNode(row.sku(), row.nodeId(), org).stream()
                .filter(l -> row.lotNo() == null || l.lotNo().equalsIgnoreCase(row.lotNo()))
                .filter(l -> row.expiry() == null || row.expiry().equals(l.expDate())).toList();
        if (candidates.isEmpty()) {
            return record(u, row, StockRowOutcome.FAILED, row.lotNo() != null ? "There is no lot " + row.lotNo()
                    + " of " + row.sku() + " at " + row.nodeId() + (row.expiry() == null ? "" : " expiring "
                    + row.expiry()) : "There is no lot of " + row.sku() + " at " + row.nodeId()
                    + (row.expiry() == null ? "" : " expiring " + row.expiry()), null, null);
        }
        if (candidates.size() > 1) {
            return record(u, row, StockRowOutcome.FAILED, row.sku() + " has several lots at " + row.nodeId()
                    + ". Give the lot number", null, null);
        }
        Lot lot = lots.lockById(candidates.getFirst().id()).orElseThrow();
        changes.trackLot(lot);
        if (lot.status() == LotStatus.WRITTEN_OFF) {
            return record(u, row, StockRowOutcome.FAILED, "Lot " + lot.lotNo() + " is written off", null, null);
        }
        int quantity = row.quantity() == null ? 0 : row.quantity();
        int target = u.mode() == StockImportMode.DELTA ? lot.qtyOnHand() + quantity : quantity;
        if (target < 0) {
            return record(u, row, StockRowOutcome.FAILED, "Lot " + lot.lotNo() + " has " + lot.qtyOnHand()
                    + " units, so " + quantity + " would leave less than none", lot.qtyOnHand(), null);
        }
        if (target > StockImportValidator.MAX_STOCK) {
            return record(u, row, StockRowOutcome.FAILED, "That would be " + target + " units; at most "
                    + StockImportValidator.MAX_STOCK + " can be held in one lot", lot.qtyOnHand(), null);
        }
        if (target < lot.qtyReserved()) {
            return record(u, row, StockRowOutcome.FAILED, "Lot " + lot.lotNo() + " has " + lot.qtyReserved()
                    + " units reserved for orders; the count would leave " + target + ". Count again after those "
                    + "orders ship", lot.qtyOnHand(), null);
        }
        if (target == lot.qtyOnHand()) {
            return record(u, row, StockRowOutcome.UNCHANGED, "No change", lot.qtyOnHand(), lot.qtyOnHand());
        }
        int delta = target - lot.qtyOnHand();
        StockPosition next = lot.status() == LotStatus.SELLABLE ? position.adjustSellable(delta)
                : position.adjustHeld(delta);
        String reason = "BULK_COUNT · import " + u.id() + " · row " + row.rowNumber() + " · "
                + u.mode().name().toLowerCase(Locale.ROOT);
        changes.apply(MovementType.ADJUST, next, lot.countedAs(target, reason), delta, u.id().toString(), reason);
        writer.flush(changes);
        return record(u, row, StockRowOutcome.APPLIED, null, lot.qtyOnHand(), target);
    }

    /** Stores the row's outcome in the same transaction as its stock change; rolls back when it was finished already. */
    private StockRowOutcome record(StockImport u, StockImportRow row, StockRowOutcome outcome, String note, Integer before,
                                   Integer after) {
        if (imports.setOutcome(u.id(), row.rowNumber(), outcome, note, before, after) == 0) {
            throw new AlreadyDone();
        }
        return outcome;
    }
}
