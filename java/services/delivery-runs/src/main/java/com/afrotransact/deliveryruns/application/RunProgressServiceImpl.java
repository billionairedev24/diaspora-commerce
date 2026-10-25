package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderStage;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunProgress;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RunProgressServiceImpl implements RunProgressService {

    private static final Logger LOG = LoggerFactory.getLogger(RunProgressServiceImpl.class);

    /** Runs whose orders are being collected and delivered: the only ones events move. */
    private static final Set<RunStatus> UNDER_WAY = EnumSet.of(RunStatus.CONFIRMED, RunStatus.COLLECTING,
            RunStatus.OUT_FOR_DELIVERY);

    private final RunRepository runs;
    private final RunOrderRepository runOrders;
    private final LocationRepository locations;
    private final RunLifecycle lifecycle;
    private final InboxDeduplicator inbox;
    private final PermissionChecker permissions;
    private final Clock clock;

    RunProgressServiceImpl(RunRepository runs, RunOrderRepository runOrders, LocationRepository locations,
                           RunLifecycle lifecycle, InboxDeduplicator inbox, PermissionChecker permissions,
                           Clock clock) {
        this.runs = runs;
        this.runOrders = runOrders;
        this.locations = locations;
        this.lifecycle = lifecycle;
        this.inbox = inbox;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onShipmentMoved(UUID eventId, String orderId, String shipmentId, String to, Instant at) {
        Optional<RunOrderStage> stage = RunOrderStage.ofShipmentStatus(to);
        if (stage.isEmpty() || !inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        advance(orderId, stage.get(), at);
    }

    @Override
    @Transactional
    public void onShipmentDelivered(UUID eventId, String orderId, String shipmentId, Instant deliveredAt) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        advance(orderId, RunOrderStage.DELIVERED, deliveredAt);
    }

    @Override
    @Transactional
    public void onShipmentCancelled(UUID eventId, String orderId, String shipmentId, Instant at) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        Optional<Run> locked = lockRunOf(orderId);
        if (locked.isEmpty() || !UNDER_WAY.contains(locked.get().status())) {
            return;
        }
        Optional<RunOrder> slot = confirmedSlot(orderId);
        if (slot.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        runOrders.update(slot.get().cancel(now));
        Run released = runs.update(locked.get().release(slot.get().packages(), now));
        LOG.info("Order {} left run {} (shipment {} cancelled)", orderId, released.id(), shipmentId);
        settle(released, now);
    }

    @Override
    @Transactional(readOnly = true)
    public RunProgress progress(UUID runId) {
        permissions.require(DeliveryRunsPermissions.READ);
        runs.findById(runId).orElseThrow(() -> new NotFoundException("delivery-runs.run-not-found", "Run not found"));
        return RunProgress.of(runOrders.findActiveByRun(runId));
    }

    private void advance(String orderId, RunOrderStage stage, Instant at) {
        Optional<Run> locked = lockRunOf(orderId);
        if (locked.isEmpty()) {
            return;
        }
        Optional<RunOrder> slot = confirmedSlot(orderId);
        if (slot.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        RunOrder moved = slot.get().advance(stage, at, now);
        if (moved != slot.get()) {
            runOrders.update(moved);
        }
        // Even a repeated event re-checks the run, so a run a crash left half-way is moved by the next event.
        settle(locked.get(), now);
    }

    /** The run the order's active slot is on, locked for the rest of the transaction; empty when it is on none. */
    private Optional<Run> lockRunOf(String orderId) {
        return runOrders.findActiveByOrderId(orderId).flatMap(s -> runs.lockById(s.runId()));
    }

    private Optional<RunOrder> confirmedSlot(String orderId) {
        return runOrders.findActiveByOrderId(orderId).filter(s -> s.status() == RunOrderStatus.CONFIRMED);
    }

    /** Walks the run forward as far as its orders allow: collecting, out for delivery, completed. */
    private void settle(Run run, Instant now) {
        if (!UNDER_WAY.contains(run.status())) {
            return;
        }
        RunProgress p = RunProgress.of(runOrders.findActiveByRun(run.id()));
        Location location = locations.findById(run.locationId()).orElseThrow();
        Run r = run;
        if (r.status() == RunStatus.CONFIRMED && p.anyCollected()) {
            r = lifecycle.moved(location, r.startCollecting(now), r.status(), DeliveryRunsEvents.RUN_COLLECTING, false);
        }
        if (r.status() == RunStatus.COLLECTING && p.allCollected()) {
            r = lifecycle.moved(location, r.outForDelivery(now), r.status(), DeliveryRunsEvents.RUN_OUT_FOR_DELIVERY,
                    true);
        }
        if (r.status() == RunStatus.OUT_FOR_DELIVERY && p.allClosed()) {
            r = lifecycle.moved(location, r.complete(now), r.status(), DeliveryRunsEvents.RUN_COMPLETED, true);
        }
        if (r != run) {
            LOG.info("Run {} is now {} ({} of {} delivered)", r.id(), r.status(), p.delivered(), p.orders());
        }
    }
}
