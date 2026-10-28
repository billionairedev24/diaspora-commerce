package com.afrotransact.fulfilment.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.DeliveryShipmentStatus;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.domain.PackageStatus;
import com.afrotransact.fulfilment.domain.RoutePackage;
import com.afrotransact.fulfilment.domain.RouteStatus;
import com.afrotransact.fulfilment.domain.RouteStop;
import com.afrotransact.fulfilment.domain.RunBag;
import com.afrotransact.fulfilment.domain.RunDispatch;
import com.afrotransact.fulfilment.domain.RunDispatchStatus;
import com.afrotransact.fulfilment.domain.RunDrop;
import com.afrotransact.fulfilment.domain.RunRoutePlanner;
import com.afrotransact.fulfilment.domain.StopKind;
import com.afrotransact.fulfilment.domain.StopStatus;
import com.afrotransact.fulfilment.port.CentreRepository;
import com.afrotransact.fulfilment.port.DeliveryRunsClient;
import com.afrotransact.fulfilment.port.DeliveryRunsClient.RunPlan;
import com.afrotransact.fulfilment.port.DeliveryShipmentRepository;
import com.afrotransact.fulfilment.port.DispatchFeed;
import com.afrotransact.fulfilment.port.GeoClient;
import com.afrotransact.fulfilment.port.GeoClient.RoadDistance;
import com.afrotransact.fulfilment.port.IdIssuer;
import com.afrotransact.fulfilment.port.OrdersCommandQueue;
import com.afrotransact.fulfilment.port.RouteRepository;
import com.afrotransact.fulfilment.port.RunDispatchRepository;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RunDispatchServiceImpl implements RunDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(RunDispatchServiceImpl.class);
    private static final double METERS_PER_MILE = 1609.344;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private final RunDispatchRepository runs;
    private final DeliveryRunsClient deliveryRuns;
    private final DeliveryShipmentRepository shipments;
    private final RouteRepository routes;
    private final CentreRepository centres;
    private final RouteAssembler assembler;
    private final RunRoutePlanner planner;
    private final GeoClient geo;
    private final IdIssuer ids;
    private final OrdersCommandQueue ordersQueue;
    private final DomainEventPublisher events;
    private final DispatchFeed feed;
    private final InboxDeduplicator inbox;
    private final FulfilmentAccess access;
    private final RunDispatchSettings settings;
    private final Clock clock;

    RunDispatchServiceImpl(RunDispatchRepository runs, DeliveryRunsClient deliveryRuns,
                           DeliveryShipmentRepository shipments, RouteRepository routes, CentreRepository centres,
                           RouteAssembler assembler, RunRoutePlanner planner, GeoClient geo, IdIssuer ids,
                           OrdersCommandQueue ordersQueue, DomainEventPublisher events, DispatchFeed feed,
                           InboxDeduplicator inbox, FulfilmentAccess access, RunDispatchSettings settings,
                           Clock clock) {
        this.runs = runs;
        this.deliveryRuns = deliveryRuns;
        this.shipments = shipments;
        this.routes = routes;
        this.centres = centres;
        this.assembler = assembler;
        this.planner = planner;
        this.geo = geo;
        this.ids = ids;
        this.ordersQueue = ordersQueue;
        this.events = events;
        this.feed = feed;
        this.inbox = inbox;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ events

    @Override
    @Transactional
    public void onRunConfirmed(UUID eventId, RunPlan fallback) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        planRun(fallback.runId(), fallback);
    }

    @Override
    @Transactional
    public void onRunCancelled(UUID eventId, String runId, String reason) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        Optional<RunDispatch> locked = runs.lock(runId);
        if (locked.isEmpty() || locked.get().status() == RunDispatchStatus.CANCELLED) {
            return;
        }
        Instant now = Instant.now(clock);
        String why = reason == null || reason.isBlank() ? "The delivery run was cancelled" : reason;
        for (String routeId : runs.routeIds(runId)) {
            Optional<DeliveryRoute> route = routes.lock(routeId);
            if (route.isEmpty() || route.get().status() != RouteStatus.PLANNED
                    && route.get().status() != RouteStatus.ASSIGNED) {
                continue;
            }
            DeliveryRoute r = route.get();
            List<String> freed = r.packages().stream().filter(p -> p.status() == PackageStatus.EXPECTED)
                    .map(RoutePackage::shipmentId).distinct().toList();
            r.cancel(why, now);
            routes.save(r);
            for (String shipmentId : freed) {
                shipments.updateStatus(shipmentId, DeliveryShipmentStatus.AWAITING_ROUTE, null);
            }
            publishRoute(FulfilmentEvents.ROUTE_CANCELLED, r, now);
            if (r.driverId() != null) {
                feed.publish(new DispatchFeed.Update("route.cancelled", r.driverId(), r.id(), "NORMAL",
                        Map.of("routeId", r.id(), "status", r.status().name()), now));
            }
        }
        runs.save(locked.get().cancelled());
        LOG.info("Run {} cancelled in dispatch: {}", runId, why);
    }

    // ------------------------------------------------------------------ staff

    @Override
    @Transactional
    public RunDispatchView plan(String runId) {
        access.dispatcher();
        RunDispatch run = planRun(runId, null);
        return view(run);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunDispatchView> list(RunDispatchStatus status, int limit) {
        access.opsReader();
        return runs.list(status, Math.max(1, Math.min(limit, 100))).stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RunDispatchView get(String runId) {
        access.opsReader();
        return view(runs.find(runId).orElseThrow(() -> new NotFoundException("fulfilment.run-not-found",
                "Run " + runId + " is not in dispatch")));
    }

    // ------------------------------------------------------------------ planning

    /**
     * Remembers the run and plans routes for every shipment of its orders that is waiting and not on a route. With a
     * {@code fallback} (the confirmed event) an unreachable delivery-runs is recorded on the run for staff instead of
     * failing the event; without one (staff asked) the failure is thrown.
     */
    private RunDispatch planRun(String runId, RunPlan fallback) {
        RunPlan plan;
        String error = null;
        try {
            plan = deliveryRuns.dispatch(runId);
        } catch (UpstreamFailure f) {
            if (fallback == null) {
                throw f;
            }
            plan = fallback;
            error = "delivery-runs did not answer (" + f.code() + "): " + f.getMessage();
        }
        runs.upsert(describe(plan));
        RunDispatch run = runs.lock(runId).orElseThrow();
        if (run.status() == RunDispatchStatus.CANCELLED) {
            return run;
        }
        if (error != null) {
            return keep(run.failed(error));
        }
        String depot = settings.depotNodeId();
        if (depot == null || centres.find(depot).isEmpty()) {
            return keep(run.failed("No fulfilment centre to load the routes at: set FULFILMENT_RUN_DEPOT_NODE (or "
                    + "FULFILMENT_DEFAULT_NODE) to an existing centre"));
        }
        List<DeliveryShipment> mine = shipments.findByOrderIds(plan.orders()).stream()
                .filter(s -> "PREORDER".equals(s.fulfilmentType())).toList();
        List<String> waiting = mine.stream().filter(s -> s.status() == DeliveryShipmentStatus.AWAITING_ROUTE
                && runs.runOfShipment(s.shipmentId()).map(runId::equals).orElse(true))
                .map(DeliveryShipment::shipmentId).toList();
        List<DeliveryShipment> todo = shipments.lockAll(waiting).stream()
                .filter(s -> s.status() == DeliveryShipmentStatus.AWAITING_ROUTE).toList();
        List<String> existing = runs.routeIds(runId);
        if (todo.isEmpty() && existing.isEmpty()) {
            return keep(run.failed(mine.isEmpty()
                    ? "None of the run's orders are in dispatch yet; plan it again once their shipments arrive"
                    : "Every shipment of the run is delivered, cancelled or already on a route"));
        }
        Instant now = Instant.now(clock);
        if (!todo.isEmpty()) {
            planRoutes(run, plan, depot, todo, existing.size(), now);
        }
        RunDispatch planned = run.planned(depot, now);
        runs.save(planned);
        return planned;
    }

    private RunDispatch keep(RunDispatch run) {
        runs.save(run);
        LOG.warn("Run {} not planned: {}", run.runId(), run.planError());
        return run;
    }

    private void planRoutes(RunDispatch run, RunPlan plan, String depot, List<DeliveryShipment> todo, int plannedSoFar,
                            Instant now) {
        Map<String, DeliveryShipment> byId = new LinkedHashMap<>();
        todo.forEach(s -> byId.put(s.shipmentId(), s));
        Map<String, List<RunBag>> bags = runs.bags(byId.keySet());
        Map<String, List<DeliveryShipment>> byOrder = new LinkedHashMap<>();
        for (DeliveryShipment s : todo) {
            byOrder.computeIfAbsent(s.orderNumber(), o -> new ArrayList<>()).add(s);
        }
        List<RunDrop> drops = drops(run, plan.origin(), byOrder, bags);
        List<List<RunDrop>> chunks = planner.plan(drops, settings.routeCapacity());
        String window = windowText(run);
        List<String> planned = new ArrayList<>();
        List<Map<String, Object>> summary = new ArrayList<>();
        int seq = plannedSoFar;
        for (List<RunDrop> chunk : chunks) {
            seq++;
            String id = ids.routeId();
            String name = run.town() + " run" + (chunks.size() > 1 || plannedSoFar > 0 ? " " + seq : "");
            DeliveryRoute route = DeliveryRoute.plan(id, name, run.town(), run.serviceDate(), window, depot);
            routes.insert(route);
            route = routes.lock(id).orElseThrow();
            List<String> added = assembler.addRun(route, chunk, byId, bags, run, settings::atDepot, now);
            routes.save(route);
            runs.linkRoute(run.runId(), id, seq);
            for (String shipmentId : added) {
                shipments.updateStatus(shipmentId, DeliveryShipmentStatus.ROUTED, id);
                runs.linkShipment(run.runId(), shipmentId);
                planned.add(shipmentId);
            }
            publishRoute(FulfilmentEvents.ROUTE_PLANNED, route, now);
            summary.add(Map.of("routeId", id, "name", name, "stops", route.stops().size(),
                    "packages", route.packages().size()));
        }
        // orders-service refuses courier events on a shipment that is only "placed": planning confirms it. Queued in
        // this transaction, in order per shipment, so a slow orders-service never holds the planning.
        for (String shipmentId : planned) {
            ordersQueue.enqueue(OrdersRelayService.transition(shipmentId, "confirmed",
                    "Planned on delivery run " + run.runId(), "fulfilment:run:" + run.runId() + ":" + shipmentId
                            + ":confirmed"), now);
        }
        events.publish(FulfilmentEvents.RUN_PLANNED, FulfilmentEvents.AGGREGATE, run.runId(),
                new FulfilmentEventPayloads.RunPlanned(run.runId(), run.town(), run.serviceDate(),
                        run.doorstep() ? "DOORSTEP" : "PICKUP_POINT", summary.size(), byOrder.size(),
                        planned.size(), now));
        feed.publish(new DispatchFeed.Update("run.planned", null, null, "NORMAL",
                Map.of("runId", run.runId(), "routes", summary, "orders", byOrder.size()), now));
        LOG.info("Run {} planned: {} orders on {} route(s)", run.runId(), byOrder.size(), summary.size());
    }

    /** One drop per order with its distance from the hub (a pickup-point run drops everything at one place). */
    private List<RunDrop> drops(RunDispatch run, GeoPoint origin, Map<String, List<DeliveryShipment>> byOrder,
                                Map<String, List<RunBag>> bags) {
        List<String> numbers = new ArrayList<>(byOrder.keySet());
        Map<String, Double> distance = new HashMap<>();
        if (run.doorstep() && origin != null) {
            Map<String, GeoPoint> where = new LinkedHashMap<>();
            Map<String, Optional<GeoPoint>> zips = new HashMap<>();
            for (String number : numbers) {
                DeliveryShipment first = byOrder.get(number).getFirst();
                GeoPoint p = first.location();
                if (p == null && first.postalCode() != null) {
                    p = zips.computeIfAbsent(first.postalCode(), geo::locateZip).orElse(null);
                }
                if (p != null) {
                    where.put(number, p);
                }
            }
            List<String> keys = new ArrayList<>(where.keySet());
            List<Optional<RoadDistance>> road = geo.roadDistances(origin, keys.stream().map(where::get).toList());
            for (int i = 0; i < keys.size(); i++) {
                Optional<RoadDistance> r = i < road.size() ? road.get(i) : Optional.empty();
                GeoPoint at = where.get(keys.get(i));
                distance.put(keys.get(i), r.isPresent() ? r.get().roadMiles() * METERS_PER_MILE
                        : origin.distanceMetersTo(at));
            }
        }
        List<RunDrop> drops = new ArrayList<>();
        for (String number : numbers) {
            List<DeliveryShipment> mine = byOrder.get(number);
            int packages = mine.stream().mapToInt(s -> Math.max(1, bags.getOrDefault(s.shipmentId(), List.of())
                    .size())).sum();
            drops.add(new RunDrop(number, mine.stream().map(DeliveryShipment::shipmentId).toList(), packages,
                    distance.get(number)));
        }
        return drops;
    }

    private static RunDispatch describe(RunPlan p) {
        ZoneId zone = ZoneId.of(p.timeZone());
        return new RunDispatch(p.runId(), p.town() == null ? "Run" : p.town(), p.state(), p.timeZone(),
                p.windowStart().atZone(zone).toLocalDate(), p.windowStart(), p.windowEnd(), p.sellerPickupAt(),
                p.doorstep(), p.pickupPoint(), p.origin(), null, RunDispatchStatus.PENDING, null, null, 0);
    }

    private static String windowText(RunDispatch run) {
        ZoneId zone = ZoneId.of(run.timeZone());
        return run.windowStart().atZone(zone).format(CLOCK) + " – " + run.windowEnd().atZone(zone).format(CLOCK);
    }

    // ------------------------------------------------------------------ views

    private RunDispatchView view(RunDispatch run) {
        List<DeliveryRoute> rs = runs.routeIds(run.runId()).stream().map(routes::find).flatMap(Optional::stream)
                .toList();
        List<DeliveryShipment> mine = shipments.findAllById(runs.shipmentIds(run.runId()));
        int orders = (int) mine.stream().map(DeliveryShipment::orderNumber).distinct().count();
        int unrouted = (int) mine.stream().filter(s -> s.status() == DeliveryShipmentStatus.AWAITING_ROUTE).count();
        int bags = 0;
        int collected = 0;
        int delivered = 0;
        int pickupStops = 0;
        int pickupsDone = 0;
        int dropStops = 0;
        int dropsDone = 0;
        int dropsFailed = 0;
        for (DeliveryRoute r : rs) {
            for (RoutePackage p : r.packages()) {
                if (!p.status().active()) {
                    continue;
                }
                bags++;
                if (p.status() != PackageStatus.EXPECTED && p.status() != PackageStatus.NOT_PICKED_UP) {
                    collected++;
                }
                if (p.status() == PackageStatus.DELIVERED) {
                    delivered++;
                }
            }
            for (RouteStop s : r.stops()) {
                if (s.status() == StopStatus.CANCELLED || s.status() == StopStatus.REASSIGNED) {
                    continue;
                }
                if (s.kind() == StopKind.PICKUP) {
                    pickupStops++;
                    pickupsDone += s.status() == StopStatus.DONE ? 1 : 0;
                } else if (s.kind() == StopKind.DROPOFF) {
                    dropStops++;
                    dropsDone += s.status() == StopStatus.DONE ? 1 : 0;
                    dropsFailed += s.status() == StopStatus.FAILED || s.status() == StopStatus.SKIPPED ? 1 : 0;
                }
            }
        }
        return new RunDispatchView(run, rs, new RunDispatchView.Progress(orders, bags, pickupStops, pickupsDone,
                collected, dropStops, dropsDone, dropsFailed, delivered, unrouted));
    }

    private void publishRoute(String type, DeliveryRoute r, Instant now) {
        events.publish(type, FulfilmentEvents.AGGREGATE, r.id(), new FulfilmentEventPayloads.RouteEvent(r.id(),
                r.zone(), r.serviceDate(), r.depotNodeId(), r.driverId(), r.vehicleId(), r.status().name(),
                r.stops().size(), r.packages().size(), now));
        feed.publish(new DispatchFeed.Update("route.updated", null, r.id(), "NORMAL",
                Map.of("routeId", r.id(), "status", r.status().name(), "type", type), now));
    }
}
