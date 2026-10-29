package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RunSlotServiceImpl implements RunSlotService {

    private static final EnumSet<RunStatus> DISPATCHABLE = EnumSet.of(RunStatus.CONFIRMED, RunStatus.COLLECTING,
            RunStatus.OUT_FOR_DELIVERY, RunStatus.COMPLETED);

    private final RunRepository runs;
    private final RunOrderRepository runOrders;
    private final LocationRepository locations;
    private final DeliveryRunsSettings settings;
    private final PermissionChecker permissions;
    private final Clock clock;

    RunSlotServiceImpl(RunRepository runs, RunOrderRepository runOrders, LocationRepository locations,
                       DeliveryRunsSettings settings, PermissionChecker permissions, Clock clock) {
        this.runs = runs;
        this.runOrders = runOrders;
        this.locations = locations;
        this.settings = settings;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SlotReservation reserve(ReserveCommand c) {
        permissions.require(DeliveryRunsPermissions.RESERVE);
        if (c.runId() == null || c.orderId() == null || c.orderId().isBlank()) {
            throw new RuleViolation("delivery-runs.invalid-order", "runId and orderId are required");
        }
        int packages = c.packages() == null ? 1 : c.packages();
        Optional<RunOrder> existing = runOrders.findActiveByOrderId(c.orderId());
        if (existing.isPresent()) {
            RunOrder slot = existing.get();
            Run run = runs.findById(slot.runId()).orElseThrow();
            UUID asked = slot.requestedRunId() == null ? slot.runId() : slot.requestedRunId();
            if (!asked.equals(c.runId())) {
                throw new ConflictException("delivery-runs.order-already-reserved",
                        "Order " + c.orderId() + " already holds a slot on run " + slot.runId());
            }
            return new SlotReservation(slot, run, c.runId(), slot.movedToNextRun(), false);
        }
        Instant now = Instant.now(clock);
        Run requested = runs.lockById(c.runId()).orElseThrow(RunSlotServiceImpl::runNotFound);
        Run target = requested;
        int early = c.earlyAccessHours() == null ? 0 : c.earlyAccessHours();
        if (!requested.canTake(packages, now) && !requested.canTakeEarly(packages, now, early)) {
            boolean full = requested.acceptsOrders(now);
            if (c.allowNextRun() != null && !c.allowNextRun()) {
                throw refused(full);
            }
            target = runs.lockAcceptingAfter(requested.locationId(), requested.terms().cutoffAt(), now,
                            settings.nextRunSearchLimit()).stream()
                    .filter(r -> r.canTake(packages, now))
                    .findFirst()
                    .orElseThrow(() -> refused(full));
        }
        RunOrder slot;
        try {
            slot = runOrders.insert(RunOrder.reserve(UUID.randomUUID(), target.id(), c.orderId(), c.customerId(),
                    packages, requested.id(), now));
        } catch (DuplicateEntryException e) {
            throw new ConflictException("delivery-runs.order-already-reserved",
                    "Order " + c.orderId() + " already holds a run slot");
        }
        Run saved = runs.update(target.reserve(packages, now, early));
        return new SlotReservation(slot, saved, requested.id(), !target.id().equals(requested.id()), true);
    }

    @Override
    @Transactional
    public RunOrder release(UUID runId, String orderId) {
        permissions.require(DeliveryRunsPermissions.RESERVE);
        Run run = runs.lockById(runId).orElseThrow(RunSlotServiceImpl::runNotFound);
        Optional<RunOrder> active = runOrders.findActiveByOrderId(orderId).filter(o -> o.runId().equals(runId));
        if (active.isEmpty()) {
            return runOrders.findLatest(runId, orderId).orElseThrow(() -> new NotFoundException(
                    "delivery-runs.slot-not-found", "Order " + orderId + " has no slot on this run"));
        }
        if (run.status() == RunStatus.OUT_FOR_DELIVERY || run.status() == RunStatus.COMPLETED) {
            throw new RuleViolation("delivery-runs.run-dispatched", "The run is already out for delivery");
        }
        Instant now = Instant.now(clock);
        RunOrder released = runOrders.update(active.get().release(now));
        runs.update(run.release(released.packages(), now));
        return released;
    }

    @Override
    @Transactional(readOnly = true)
    public DispatchList dispatch(UUID runId) {
        permissions.require(DeliveryRunsPermissions.READ);
        Run run = runs.findById(runId).orElseThrow(RunSlotServiceImpl::runNotFound);
        if (!DISPATCHABLE.contains(run.status())) {
            throw new ConflictException("delivery-runs.run-not-confirmed", "The run is " + run.status()
                    + "; dispatch lists exist once it is confirmed");
        }
        Location location = locations.findById(run.locationId()).orElseThrow();
        GeoPoint origin = location.details().origin() == null ? settings.defaultOrigin() : location.details().origin();
        return new DispatchList(run, location, runOrders.findByRun(runId, RunOrderStatus.CONFIRMED), origin);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> dispatchable() {
        permissions.require(DeliveryRunsPermissions.READ);
        return runs.find(null, EnumSet.of(RunStatus.CONFIRMED, RunStatus.COLLECTING), 500).stream()
                .sorted((a, b) -> a.terms().window().start().compareTo(b.terms().window().start()))
                .map(Run::id)
                .toList();
    }

    private static ConflictException refused(boolean full) {
        return full ? new ConflictException("delivery-runs.run-full", "The run is full and no later run has room")
                : new ConflictException("delivery-runs.run-closed",
                "The run no longer takes orders and no later run is open");
    }

    private static NotFoundException runNotFound() {
        return new NotFoundException("delivery-runs.run-not-found", "Run not found");
    }
}
