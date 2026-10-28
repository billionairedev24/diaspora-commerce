package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Capacity;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DeliveryWindow;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.LocationDetails;
import com.afrotransact.deliveryruns.domain.PickupPoint;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RouteEstimate;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunAction;
import com.afrotransact.deliveryruns.domain.RunEconomics;
import com.afrotransact.deliveryruns.domain.RunEconomicsCalculator;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.domain.RunTerms;
import com.afrotransact.deliveryruns.domain.SellerPickupSchedule;
import com.afrotransact.deliveryruns.port.GeoGateway;
import com.afrotransact.deliveryruns.port.GeoUnavailableException;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.RunActionLog;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RunServiceImpl implements RunService {

    private static final Logger LOG = LoggerFactory.getLogger(RunServiceImpl.class);

    private final RunRepository runs;
    private final RunOrderRepository runOrders;
    private final LocationRepository locations;
    private final GeoGateway geo;
    private final RunEconomicsCalculator economics;
    private final RunLifecycle lifecycle;
    private final DeliveryRunsSettings settings;
    private final PermissionChecker permissions;
    private final Clock clock;
    private final PreorderHandover preorders;
    private final RunActionLog actionLog;

    RunServiceImpl(RunRepository runs, RunOrderRepository runOrders, LocationRepository locations, GeoGateway geo,
                   RunEconomicsCalculator economics, RunLifecycle lifecycle, DeliveryRunsSettings settings,
                   PermissionChecker permissions, Clock clock, PreorderHandover preorders, RunActionLog actionLog) {
        this.actionLog = actionLog;
        this.runs = runs;
        this.runOrders = runOrders;
        this.locations = locations;
        this.geo = geo;
        this.economics = economics;
        this.lifecycle = lifecycle;
        this.settings = settings;
        this.permissions = permissions;
        this.clock = clock;
        this.preorders = preorders;
    }

    @Override
    @Transactional
    public Run create(RunCommand command) {
        permissions.require(DeliveryRunsPermissions.MANAGE);
        Location location = location(command.locationId());
        if (!location.active()) {
            throw new RuleViolation("delivery-runs.location-inactive", "The location is not active");
        }
        Instant now = Instant.now(clock);
        RunTerms terms = terms(location, command);
        if (!terms.cutoffAt().isAfter(now)) {
            throw new RuleViolation("delivery-runs.invalid-cutoff", "The cutoff must be in the future");
        }
        return runs.insert(Run.draft(UUID.randomUUID(), location.id(), terms, tryRoute(location), now));
    }

    @Override
    @Transactional
    public Run update(UUID id, RunCommand command) {
        permissions.require(DeliveryRunsPermissions.MANAGE);
        Run run = lock(id);
        if (command.locationId() != null && !command.locationId().equals(run.locationId())) {
            throw new RuleViolation("delivery-runs.location-fixed", "A run cannot move to another location");
        }
        Location location = location(run.locationId());
        Run changed = run.reschedule(terms(location, command), Instant.now(clock));
        if (changed.estimate() == null) {
            changed = changed.withEstimate(tryRoute(location), changed.updatedAt());
        }
        return runs.update(changed);
    }

    @Override
    @Transactional(readOnly = true)
    public Run get(UUID id) {
        permissions.require(DeliveryRunsPermissions.READ);
        return find(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Run> list(UUID locationId, Set<RunStatus> statuses, int limit) {
        permissions.require(DeliveryRunsPermissions.READ);
        return runs.find(locationId, statuses, Math.min(Math.max(limit, 1), 500));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        permissions.require(DeliveryRunsPermissions.MANAGE);
        Run run = lock(id);
        if (run.status() != RunStatus.DRAFT) {
            throw new RuleViolation("delivery-runs.run-not-draft", "Only draft runs can be deleted; cancel instead");
        }
        runs.delete(id);
    }

    @Override
    @Transactional
    public Run announce(UUID id) {
        permissions.require(DeliveryRunsPermissions.MANAGE);
        Run run = lock(id);
        return lifecycle.moved(location(run.locationId()), run.announce(Instant.now(clock)), run.status(),
                DeliveryRunsEvents.RUN_ANNOUNCED, false);
    }

    @Override
    @Transactional
    public Run open(UUID id) {
        permissions.require(DeliveryRunsPermissions.MANAGE);
        Run run = lock(id);
        return lifecycle.moved(location(run.locationId()), run.open(Instant.now(clock)), run.status(),
                DeliveryRunsEvents.RUN_OPENED, false);
    }

    @Override
    @Transactional
    public Run extend(UUID id, ExtendCommand command) {
        Actor actor = permissions.require(DeliveryRunsPermissions.MANAGE);
        Run run = lock(id);
        Instant now = Instant.now(clock);
        if (command.cutoffAt() == null) {
            throw new RuleViolation("delivery-runs.invalid-extension", "The new cutoff is required");
        }
        Duration shift = Duration.between(run.terms().cutoffAt(), command.cutoffAt());
        DeliveryWindow window = command.windowStart() != null && command.windowEnd() != null
                ? new DeliveryWindow(command.windowStart(), command.windowEnd())
                : run.terms().window().shift(shift);
        Instant pickup = command.sellerPickupAt() != null ? command.sellerPickupAt()
                : run.terms().sellerPickupAt() == null ? null : run.terms().sellerPickupAt().plus(shift);
        Run extended = run.extend(command.cutoffAt(), window, pickup, now);
        String reason = command.reason() == null || command.reason().isBlank() ? "STAFF" : command.reason().trim();
        return lifecycle.extended(location(run.locationId()), extended, run.status(), run.terms().cutoffAt(),
                new RunDecision(actor.subject(), reason), now);
    }

    @Override
    @Transactional
    public Run cancel(UUID id, String reason) {
        Actor actor = permissions.require(DeliveryRunsPermissions.MANAGE);
        Run run = lock(id);
        return lifecycle.cancel(location(run.locationId()), run, new RunDecision(actor.subject(), reason),
                Instant.now(clock));
    }

    @Override
    @Transactional
    public Run confirm(UUID id, String reason) {
        Actor actor = permissions.require(DeliveryRunsPermissions.MANAGE);
        Run run = lock(id);
        if (!run.minimumMet() && (reason == null || reason.isBlank())) {
            throw new RuleViolation("delivery-runs.reason-required",
                    "Confirming a run below its minimum needs a reason");
        }
        Instant now = Instant.now(clock);
        Run cutOff = run.status().takesOrders() ? run.cutOff(now) : run;
        return lifecycle.confirm(location(run.locationId()), cutOff, run.status(), now,
                new RunDecision(actor.subject(), reason));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunAction> actions(UUID id) {
        permissions.require(DeliveryRunsPermissions.READ);
        find(id);
        return actionLog.findByRun(id);
    }

    @Override
    @Transactional
    public Run startCollecting(UUID id) {
        permissions.require(DeliveryRunsPermissions.MANAGE);
        Run run = lock(id);
        return lifecycle.moved(location(run.locationId()), run.startCollecting(Instant.now(clock)), run.status(),
                DeliveryRunsEvents.RUN_COLLECTING, false);
    }

    @Override
    @Transactional
    public Run outForDelivery(UUID id) {
        permissions.require(DeliveryRunsPermissions.MANAGE);
        Run run = lock(id);
        return lifecycle.moved(location(run.locationId()), run.outForDelivery(Instant.now(clock)), run.status(),
                DeliveryRunsEvents.RUN_OUT_FOR_DELIVERY, true);
    }

    @Override
    @Transactional
    public Run complete(UUID id) {
        permissions.require(DeliveryRunsPermissions.MANAGE);
        Run run = lock(id);
        return lifecycle.moved(location(run.locationId()), run.complete(Instant.now(clock)), run.status(),
                DeliveryRunsEvents.RUN_COMPLETED, true);
    }

    @Override
    @Transactional(readOnly = true)
    public RunEconomics preview(EconomicsPreviewCommand c) {
        permissions.require(DeliveryRunsPermissions.READ);
        Location location = location(c.locationId());
        LocationDetails d = location.details();
        long fee = c.feeMinor() == null ? defaultFee(location, d.currency()) : c.feeMinor();
        int min = c.minOrders() == null ? d.defaultMinOrders() : c.minOrders();
        Integer capacity = c.capacityOrders() == null ? d.defaultCapacity().maxOrders() : c.capacityOrders();
        DropMode mode = c.dropMode() == null ? d.dropMode() : c.dropMode();
        int expected = c.expectedOrders() == null ? min : c.expectedOrders();
        if (fee < 0 || min < 1 || expected < 0 || (capacity != null && capacity < 1)) {
            throw new RuleViolation("delivery-runs.invalid-preview", "Fee, minimum, capacity or orders out of range");
        }
        return economics.evaluate(route(location), mode, fee, d.currency(), min, capacity, expected,
                c.commissionShareMinor());
    }

    @Override
    @Transactional(readOnly = true)
    public RunEconomics economics(UUID id, Long commissionShareMinor) {
        permissions.require(DeliveryRunsPermissions.READ);
        Run run = find(id);
        RouteEstimate route = run.estimate() != null ? run.estimate() : route(location(run.locationId()));
        RunTerms t = run.terms();
        return economics.evaluate(route, t.dropMode(), t.feeMinor(), t.currency(), t.minOrders(),
                t.capacity().maxOrders(), run.reservedOrders(), commissionShareMinor);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunOrder> orders(UUID id, RunOrderStatus status) {
        permissions.require(DeliveryRunsPermissions.READ);
        find(id);
        return runOrders.findByRun(id, status);
    }

    @Override
    @Transactional
    public Optional<Run> fromWindow(UUID windowId) {
        PreorderWindow w = preorders.windows().findById(windowId).orElseThrow(() -> new NotFoundException(
                "delivery-runs.preorder-not-found", "Pre-order not found"));
        if (w.runId() != null) {
            return runs.findById(w.runId());
        }
        Optional<Location> served = windowLocation(w);
        if (served.isEmpty()) {
            LOG.warn("Pre-order {} closed but no active location serves {}; no run created", w.code(),
                    w.details().targets());
            return Optional.empty();
        }
        Location location = served.get();
        Instant now = Instant.now(clock);
        ZoneId zone = location.details().timeZone();
        Instant start = w.details().deliversOn().atTime(LocalTime.of(9, 0)).atZone(zone).toInstant();
        Instant end = w.details().deliversOn().atTime(LocalTime.of(21, 0)).atZone(zone).toInstant();
        Instant cutoff = w.details().closesAt().isBefore(start) ? w.details().closesAt() : start.minusSeconds(60);
        boolean pickupOnly = !w.details().door() && w.details().pickup();
        PickupPoint point = pickupOnly ? location.details().pickupPoint() : null;
        DropMode mode = point != null ? DropMode.PICKUP_POINT : DropMode.DOORSTEP;
        long fee = w.doorFee(preorders.fees().current()).map(FeeSnapshot::amountMinor)
                .orElse(location.details().defaultFeeMinor());
        Instant sellerPickup = SellerPickupSchedule.at(cutoff, start, preorders.sellerPickupLead());
        RunTerms terms = new RunTerms(new DeliveryWindow(start, end), cutoff, null, sellerPickup, fee,
                w.details().currency(), 1, Capacity.UNLIMITED, mode, point, CutoffPolicy.CANCEL, 1);
        List<PreorderOrder> paid = new ArrayList<>();
        for (PreorderOrder o : preorders.orders().findByWindow(w.id())) {
            if (o.counts() && runOrders.findActiveByOrderId(o.orderId()).isEmpty()) {
                paid.add(o);
            }
        }
        Run run = runs.insert(Run.confirmedFromWindow(UUID.randomUUID(), location.id(), terms, tryRoute(location),
                paid.size(), paid.size(), now));
        List<RunOrder> slots = new ArrayList<>();
        for (PreorderOrder o : paid) {
            String customer = o.customerId() == null || o.customerId().isBlank() ? "unknown" : o.customerId();
            String code = mode == DropMode.PICKUP_POINT ? preorders.codes().next() : null;
            slots.add(runOrders.insert(RunOrder.reserve(UUID.randomUUID(), run.id(), o.orderId(), customer, 1, null,
                    now).confirm(code, now)));
        }
        preorders.events().run(DeliveryRunsEvents.RUN_CONFIRMED, location, run, null, null, "PREORDER " + w.code(),
                slots, sellersOf(paid));
        LOG.info("Pre-order {} handed {} orders to run {} at {}", w.code(), slots.size(), run.id(), location.slug());
        return Optional.of(run);
    }

    /** The sellers a run collects from, with the orders and units each has to pack (largest first). */
    static List<RunEventPayload.SellerRef> sellersOf(List<PreorderOrder> orders) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (PreorderOrder o : orders) {
            Set<String> seen = new HashSet<>();
            for (PreorderLine l : o.lines()) {
                if (l.sellerOrgId() == null || l.sellerOrgId().isBlank() || l.qty() < 1) {
                    continue;
                }
                int[] c = counts.computeIfAbsent(l.sellerOrgId(), k -> new int[2]);
                if (seen.add(l.sellerOrgId())) {
                    c[0]++;
                }
                c[1] += l.qty();
                if (l.sellerName() != null && !l.sellerName().isBlank()) {
                    names.putIfAbsent(l.sellerOrgId(), l.sellerName());
                }
            }
        }
        return counts.entrySet().stream()
                .map(e -> new RunEventPayload.SellerRef(e.getKey(), names.get(e.getKey()), e.getValue()[0],
                        e.getValue()[1]))
                .sorted(Comparator.comparingInt(RunEventPayload.SellerRef::units).reversed()
                        .thenComparing(RunEventPayload.SellerRef::sellerOrgId))
                .toList();
    }

    /** The window's location, else the active location named like its first target city in that state. */
    private Optional<Location> windowLocation(PreorderWindow w) {
        return preorders.fees().distances().locate(w);
    }

    private RunTerms terms(Location location, RunCommand c) {
        LocationDetails d = location.details();
        if (c.windowStart() == null || c.windowEnd() == null) {
            throw new RuleViolation("delivery-runs.invalid-window", "Delivery window start and end are required");
        }
        DropMode mode = c.dropMode() == null ? d.dropMode() : c.dropMode();
        PickupPoint point = c.pickupPoint() != null ? c.pickupPoint()
                : mode == DropMode.PICKUP_POINT ? d.pickupPoint() : null;
        Capacity capacity = c.capacityOrders() == null && c.capacityPackages() == null ? d.defaultCapacity()
                : new Capacity(c.capacityOrders(), c.capacityPackages());
        return new RunTerms(new DeliveryWindow(c.windowStart(), c.windowEnd()), c.cutoffAt(), c.opensAt(),
                c.sellerPickupAt(), c.feeMinor() == null ? defaultFee(location, c.currency() == null ? d.currency()
                : c.currency()) : c.feeMinor(),
                c.currency() == null ? d.currency() : c.currency(),
                c.minOrders() == null ? d.defaultMinOrders() : c.minOrders(), capacity, mode, point,
                c.cutoffPolicy() == null ? d.defaultCutoffPolicy() : c.cutoffPolicy(),
                c.extendDays() == null ? d.defaultExtendDays() : c.extendDays(), c.notes());
    }

    /**
     * A run staff schedule without naming a fee costs what the AfroTransact delivery rule says for the distance of
     * the location (ADR 0005 §3); the default of the location only applies when no rule can price it.
     */
    private long defaultFee(Location location, String currency) {
        return preorders.fees().doorFeeAt(location, currency).map(FeeSnapshot::amountMinor)
                .orElse(location.details().defaultFeeMinor());
    }

    private RouteEstimate route(Location location) {
        GeoPoint origin = location.details().origin() == null ? settings.defaultOrigin() : location.details().origin();
        return geo.route(origin, location.details().area().center());
    }

    private RouteEstimate tryRoute(Location location) {
        try {
            return route(location);
        } catch (GeoUnavailableException e) {
            LOG.warn("No route estimate for location {}: {}", location.slug(), e.getMessage());
            return null;
        }
    }

    private Location location(UUID id) {
        if (id == null) {
            throw new RuleViolation("delivery-runs.location-required", "locationId is required");
        }
        return locations.findById(id).orElseThrow(() -> new NotFoundException("delivery-runs.location-not-found",
                "Location not found"));
    }

    private Run find(UUID id) {
        return runs.findById(id).orElseThrow(RunServiceImpl::notFound);
    }

    private Run lock(UUID id) {
        return runs.lockById(id).orElseThrow(RunServiceImpl::notFound);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("delivery-runs.run-not-found", "Run not found");
    }
}
