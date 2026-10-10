package com.afrotransact.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.ReconciliationReport;
import com.afrotransact.inventory.domain.Reconciler;
import com.afrotransact.inventory.port.InventoryMetrics;
import com.afrotransact.inventory.port.LedgerRepository;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.inventory.port.ReconciliationRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads positions, ledger sums and lot sums from one REPEATABLE READ snapshot so concurrent sales never show drift. */
@Service
class ReconciliationServiceImpl implements ReconciliationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationServiceImpl.class);

    private final PositionRepository positions;
    private final LedgerRepository ledger;
    private final LotRepository lots;
    private final ReconciliationRepository runs;
    private final Reconciler reconciler;
    private final InventoryMetrics metrics;
    private final DomainEventPublisher events;
    private final InventoryAccess access;
    private final Clock clock;

    ReconciliationServiceImpl(PositionRepository positions, LedgerRepository ledger, LotRepository lots,
                              ReconciliationRepository runs, Reconciler reconciler, InventoryMetrics metrics,
                              DomainEventPublisher events, InventoryAccess access, Clock clock) {
        this.positions = positions;
        this.ledger = ledger;
        this.lots = lots;
        this.runs = runs;
        this.reconciler = reconciler;
        this.metrics = metrics;
        this.events = events;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile() {
        ReconciliationReport report = reconciler.reconcile(UUID.randomUUID(), Instant.now(clock),
                positions.allLevels(), ledger.sumsByPosition(), lots.levelsByPosition());
        runs.save(report);
        metrics.reconciliationDrift(report.driftUnits());
        if (report.clean()) {
            LOG.info("Inventory reconciliation clean: {} positions", report.positionsChecked());
        } else {
            LOG.warn("Inventory reconciliation DRIFT: {} units across {} positions: {}", report.driftUnits(),
                    report.drifts().size(), report.drifts());
            events.publish(InventoryEvents.RECONCILIATION_DRIFT, InventoryEvents.AGGREGATE_RECONCILIATION,
                    report.id().toString(), Map.of("runId", report.id().toString(), "driftUnits", report.driftUnits(),
                            "positions", report.drifts().size()));
        }
        return report;
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport runNow() {
        access.require(InventoryPermissions.ADJUST);
        return reconcile();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReconciliationReport> latest() {
        if (access.readScope().isPresent()) {
            throw new ForbiddenActionException("inventory.staff-only", "Reconciliation is visible to staff only");
        }
        return runs.latest();
    }
}
