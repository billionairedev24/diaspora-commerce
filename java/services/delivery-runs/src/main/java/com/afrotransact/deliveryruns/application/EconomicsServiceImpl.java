package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.CostModel;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.EconomicsSnapshot;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderStatus;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RouteEstimate;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunEconomics;
import com.afrotransact.deliveryruns.domain.RunEconomicsCalculator;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunPnl;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.domain.RunTerms;
import com.afrotransact.deliveryruns.domain.WindowEconomics;
import com.afrotransact.deliveryruns.domain.WindowPlan;
import com.afrotransact.deliveryruns.port.GeoGateway;
import com.afrotransact.deliveryruns.port.GeoUnavailableException;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EconomicsServiceImpl implements EconomicsService {

    private static final Logger LOG = LoggerFactory.getLogger(EconomicsServiceImpl.class);

    private final RunRepository runs;
    private final RunOrderRepository runOrders;
    private final LocationRepository locations;
    private final GeoGateway geo;
    private final RunEconomicsCalculator calculator;
    private final DeliveryRunsSettings settings;
    private final PreorderHandover preorders;
    private final PermissionChecker permissions;
    private final Clock clock;

    EconomicsServiceImpl(RunRepository runs, RunOrderRepository runOrders, LocationRepository locations,
                         GeoGateway geo, RunEconomicsCalculator calculator, DeliveryRunsSettings settings,
                         PreorderHandover preorders, PermissionChecker permissions, Clock clock) {
        this.runs = runs;
        this.runOrders = runOrders;
        this.locations = locations;
        this.geo = geo;
        this.calculator = calculator;
        this.settings = settings;
        this.preorders = preorders;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public RunPnl runPnl(UUID runId, Long commissionShareMinor) {
        permissions.require(DeliveryRunsPermissions.READ);
        Run run = runs.findById(runId).orElseThrow(() -> new NotFoundException("delivery-runs.run-not-found",
                "Run not found"));
        Location location = locations.findById(run.locationId()).orElseThrow(() -> new NotFoundException(
                "delivery-runs.location-not-found", "Location not found"));
        RouteEstimate route = run.estimate() != null ? run.estimate() : route(location);
        RunTerms t = run.terms();
        if (run.status() == RunStatus.CANCELLED) {
            int refunded = runOrders.findByRun(runId, RunOrderStatus.CANCELLED).size();
            RunEconomics planned = calculator.evaluate(route, t.dropMode(), t.feeMinor(), t.currency(), t.minOrders(),
                    t.capacity().maxOrders(), 0, null);
            // Nobody drove it: no fees kept, no cost (the fixed cost of a trip is only incurred by taking it).
            RunEconomics none = new RunEconomics(planned.route(), planned.cost(), planned.feeMinor(),
                    planned.currency(), planned.breakEvenOrders(), planned.atMinimum(), planned.atCapacity(),
                    new EconomicsSnapshot(0, 0, null, 0, 0, 0));
            return new RunPnl(none, run.status(), RunPnl.Phase.CANCELLED, run.cancelReason(), refunded,
                    refunded * t.feeMinor());
        }
        RunEconomics economics = calculator.evaluate(route, t.dropMode(), t.feeMinor(), t.currency(), t.minOrders(),
                t.capacity().maxOrders(), run.reservedOrders(), commissionShareMinor);
        return new RunPnl(economics, run.status(), RunPnl.phaseOf(run.status()), null, 0, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public WindowEconomics windowEconomics(UUID windowId, Long commissionShareMinor) {
        permissions.require(DeliveryRunsPermissions.READ);
        PreorderWindow w = preorders.windows().findById(windowId).orElseThrow(() -> new NotFoundException(
                "delivery-runs.preorder-not-found", "Pre-order not found"));
        PreorderStatus status = w.status(Instant.now(clock));
        List<PreorderOrder> all = preorders.orders().findByWindow(windowId);
        String currency = w.details().currency();
        int min = w.details().minOrders();
        Optional<Location> served = preorders.fees().distances().locate(w);
        UUID locationId = served.map(Location::id).orElse(null);
        String locationName = served.map(Location::name).orElse(null);
        if (status == PreorderStatus.CANCELLED) {
            List<PreorderOrder> refunds = all.stream().filter(o -> o.refund() != null).toList();
            int settled = (int) refunds.stream().filter(o -> o.refund().status().settled()).count();
            long refundedMinor = refunds.stream().mapToLong(PreorderOrder::totalMinor).sum();
            return new WindowEconomics(w.id(), w.code(), w.details().name(), status, true, locationId, locationName,
                    currency, min, 0, 0, 0, refunds.size(), settled, refundedMinor, null, null);
        }
        List<PreorderOrder> counted = all.stream().filter(PreorderOrder::counts).toList();
        long revenue = counted.stream().mapToLong(PreorderOrder::totalMinor).sum();
        long fees = counted.stream().mapToLong(PreorderOrder::deliveryFeeMinor).sum();
        RunEconomics economics = null;
        if (served.isPresent()) {
            Location location = served.get();
            long fee = counted.isEmpty() ? effectiveFee(w, location) : Math.round((double) fees / counted.size());
            economics = actual(location, mode(w.details().door(), w.details().pickup(), location), fee, currency,
                    min, counted.size(), fees, commissionShareMinor);
        }
        return new WindowEconomics(w.id(), w.code(), w.details().name(), status, false, locationId, locationName,
                currency, min, counted.size(), revenue, fees, 0, 0, 0, economics, covers(economics, min));
    }

    @Override
    @Transactional(readOnly = true)
    public WindowPlan planWindow(WindowPlanCommand c) {
        permissions.require(DeliveryRunsPermissions.READ);
        int min = c.minOrders() == null ? 0 : c.minOrders();
        int expected = c.expectedOrders() == null ? Math.max(min, 1) : c.expectedOrders();
        if (min < 0 || expected < 0 || (c.doorFeeMinor() != null && c.doorFeeMinor() < 0)
                || (c.pickupFeeMinor() != null && c.pickupFeeMinor() < 0)) {
            throw new RuleViolation("delivery-runs.invalid-preview", "Fee, minimum or orders out of range");
        }
        Optional<Location> served = preorders.fees().distances().locate(c.locationId(), c.targets());
        if (served.isEmpty()) {
            return new WindowPlan(null, null, "USD", 0, DropMode.DOORSTEP, min, null, null);
        }
        Location location = served.get();
        String currency = location.details().currency();
        DropMode mode = mode(c.door(), c.pickup(), location);
        long fee = planFee(c, location, currency);
        RunEconomics economics = null;
        try {
            economics = calculator.evaluate(route(location), mode, fee, currency, Math.max(min, 0), null, expected,
                    null);
        } catch (GeoUnavailableException e) {
            LOG.warn("No route for the break-even of location {}: {}", location.slug(), e.getMessage());
        }
        return new WindowPlan(location.id(), location.name(), currency, fee, mode, min, economics,
                covers(economics, min));
    }

    private long planFee(WindowPlanCommand c, Location location, String currency) {
        if (c.door()) {
            if (c.doorFeeMinor() != null) {
                return c.doorFeeMinor();
            }
            return preorders.fees().doorFeeAt(location, currency).map(FeeSnapshot::amountMinor)
                    .orElse(location.details().defaultFeeMinor());
        }
        if (c.pickupFeeMinor() != null) {
            return c.pickupFeeMinor();
        }
        return preorders.fees().current().pickup(currency).amountMinor();
    }

    /** What one order of the window pays for delivery when nothing has been ordered yet. */
    private long effectiveFee(PreorderWindow w, Location location) {
        if (w.details().door()) {
            return w.doorFee(preorders.fees().current()).map(FeeSnapshot::amountMinor)
                    .orElse(location.details().defaultFeeMinor());
        }
        return w.pickupFee(preorders.fees().current()).amountMinor();
    }

    /** Pickup-only windows drop at the location's pickup point when it has one. */
    private static DropMode mode(boolean door, boolean pickup, Location location) {
        boolean pickupOnly = !door && pickup;
        return pickupOnly && location.details().pickupPoint() != null ? DropMode.PICKUP_POINT : DropMode.DOORSTEP;
    }

    /** Economics at the orders actually placed: the fees collected, not orders x one fee (door and pickup differ). */
    private RunEconomics actual(Location location, DropMode mode, long feeMinor, String currency, int min, int orders,
                                long feesMinor, Long commissionShareMinor) {
        RouteEstimate route;
        try {
            route = route(location);
        } catch (GeoUnavailableException e) {
            LOG.warn("No route for the economics of location {}: {}", location.slug(), e.getMessage());
            return null;
        }
        CostModel cost = calculator.costModel(route, mode);
        long commission = commissionShareMinor == null ? 0 : commissionShareMinor;
        long revenue = feesMinor + commission;
        long estimated = cost.costFor(orders);
        EconomicsSnapshot atOrders = new EconomicsSnapshot(orders, feesMinor, commissionShareMinor, revenue,
                estimated, revenue - estimated);
        return new RunEconomics(route, cost, feeMinor, currency, calculator.breakEvenOrders(feeMinor, cost),
                calculator.at(min, feeMinor, null, cost), null, atOrders);
    }

    private static Boolean covers(RunEconomics economics, int min) {
        if (economics == null || economics.breakEvenOrders() == null || min < 1) {
            return null;
        }
        return min >= economics.breakEvenOrders();
    }

    private RouteEstimate route(Location location) {
        GeoPoint origin = location.details().origin() == null ? settings.defaultOrigin() : location.details().origin();
        return geo.route(origin, location.details().area().center());
    }
}
