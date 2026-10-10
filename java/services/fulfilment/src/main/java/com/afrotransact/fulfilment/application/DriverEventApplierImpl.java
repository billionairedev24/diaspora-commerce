package com.afrotransact.fulfilment.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.DeliveryProblem;
import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DeliveryShipmentStatus;
import com.afrotransact.fulfilment.domain.DeliveryStopFacts;
import com.afrotransact.fulfilment.domain.DriverEventType;
import com.afrotransact.fulfilment.domain.FulfilmentConflict;
import com.afrotransact.fulfilment.domain.FulfilmentRuleViolation;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.domain.InspectionContext;
import com.afrotransact.fulfilment.domain.InspectionItem;
import com.afrotransact.fulfilment.domain.InspectionOutcome;
import com.afrotransact.fulfilment.domain.InspectionPolicy;
import com.afrotransact.fulfilment.domain.InspectionReport;
import com.afrotransact.fulfilment.domain.PackageStatus;
import com.afrotransact.fulfilment.domain.PickupPolicy;
import com.afrotransact.fulfilment.domain.PodAssessment;
import com.afrotransact.fulfilment.domain.ProblemDecision;
import com.afrotransact.fulfilment.domain.ProofOfDelivery;
import com.afrotransact.fulfilment.domain.ProofOfDeliveryPolicy;
import com.afrotransact.fulfilment.domain.ReattemptPolicy;
import com.afrotransact.fulfilment.domain.ReturnPickup;
import com.afrotransact.fulfilment.domain.RoutePackage;
import com.afrotransact.fulfilment.domain.RouteStop;
import com.afrotransact.fulfilment.domain.SequencePolicy;
import com.afrotransact.fulfilment.domain.SosAlert;
import com.afrotransact.fulfilment.domain.SosKind;
import com.afrotransact.fulfilment.domain.StopOutcome;
import com.afrotransact.fulfilment.domain.SyncDecision;
import com.afrotransact.fulfilment.port.CourierEvent;
import com.afrotransact.fulfilment.port.DeliveryShipmentRepository;
import com.afrotransact.fulfilment.port.DispatchFeed;
import com.afrotransact.fulfilment.port.DriverEventLog;
import com.afrotransact.fulfilment.port.InspectionRepository;
import com.afrotransact.fulfilment.port.ReturnPickupRepository;
import com.afrotransact.fulfilment.port.OrdersCommandQueue.Command;
import com.afrotransact.fulfilment.port.RouteRepository;
import com.afrotransact.fulfilment.port.SosRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
class DriverEventApplierImpl implements DriverEventApplier {

    static final String FORWARD_PENDING = "fulfilment.forward-pending";

    private final DriverEventLog log;
    private final SequencePolicy sequences;
    private final RouteRepository routes;
    private final DeliveryShipmentRepository shipments;
    private final InspectionRepository inspections;
    private final SosRepository sos;
    private final DriverRules rules;
    private final OrdersRelayService orders;
    private final DomainEventPublisher events;
    private final DispatchFeed feed;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate savepoint;
    private final ReturnPickupRepository returnPickups;
    private final ZoneId zone;

    /** The domain policies the handlers apply (grouped to keep the constructor readable). */
    record DriverRules(InspectionPolicy inspection, ProofOfDeliveryPolicy pod, ReattemptPolicy reattempt,
                       PickupPolicy pickup) {
    }

    DriverEventApplierImpl(DriverEventLog log, SequencePolicy sequences, RouteRepository routes,
                           DeliveryShipmentRepository shipments, InspectionRepository inspections, SosRepository sos,
                           InspectionPolicy inspectionPolicy, ProofOfDeliveryPolicy podPolicy,
                           ReattemptPolicy reattemptPolicy, PickupPolicy pickupPolicy, OrdersRelayService orders,
                           DomainEventPublisher events, DispatchFeed feed, JsonMapper json, Clock clock,
                           PlatformTransactionManager txm, ReturnPickupRepository returnPickups,
                           @Value("${at.fulfilment.zone:America/Chicago}") String zone) {
        this.returnPickups = returnPickups;
        this.zone = ZoneId.of(zone);
        this.log = log;
        this.sequences = sequences;
        this.routes = routes;
        this.shipments = shipments;
        this.inspections = inspections;
        this.sos = sos;
        this.rules = new DriverRules(inspectionPolicy, podPolicy, reattemptPolicy, pickupPolicy);
        this.orders = orders;
        this.events = events;
        this.feed = feed;
        this.json = json;
        this.clock = clock;
        this.savepoint = new TransactionTemplate(txm);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    @Transactional
    public EventResult apply(DriverContext driver, String deviceId, DriverEvent e, String driverBearer) {
        Optional<DriverEventLog.Entry> prior = log.find(e.eventId());
        if (prior.isPresent()) {
            return replay(prior.get());
        }
        Instant now = Instant.now(clock);
        long last = log.lockDevice(deviceId, driver.driverId(), now);
        prior = log.find(e.eventId());
        if (prior.isPresent()) {
            return replay(prior.get());
        }
        SyncDecision decision = sequences.decide(last, e.sequence(), false);
        if (decision == SyncDecision.SEQUENCE_CONFLICT) {
            return new EventResult(e.eventId(), e.sequence(), EventResult.CONFLICT, "fulfilment.sequence-reused",
                    "Sequence " + e.sequence() + " was already used on this device; next is " + (last + 1), false,
                    false);
        }
        if (decision == SyncDecision.GAP) {
            return new EventResult(e.eventId(), e.sequence(), EventResult.DEFERRED, "fulfilment.sequence-gap",
                    "Send sequence " + (last + 1) + " first", false, false);
        }
        String outcome;
        String code = null;
        String message = null;
        if (!driver.access().acceptsEventCapturedAt(e.capturedAt())) {
            outcome = EventResult.REJECTED;
            code = "fulfilment.driver-access-revoked";
            message = "Captured after driver access was revoked at " + driver.access().changedAt();
        } else {
            try {
                Boolean pending = savepoint.execute(s -> handle(driver, e, driverBearer, now));
                outcome = EventResult.APPLIED;
                if (Boolean.TRUE.equals(pending)) {
                    code = FORWARD_PENDING;
                    message = "Applied; orders-service is unreachable, forwarding is retried";
                }
            } catch (FulfilmentRuleViolation x) {
                outcome = EventResult.REJECTED;
                code = x.code();
                message = x.getMessage();
            } catch (FulfilmentConflict x) {
                outcome = EventResult.CONFLICT;
                code = x.code();
                message = x.getMessage();
            } catch (DomainException x) {
                outcome = EventResult.CONFLICT;
                code = x.code();
                message = x.getMessage();
            }
        }
        log.record(new DriverEventLog.Entry(e.eventId(), deviceId, e.sequence(), driver.driverId(), e.type(),
                e.routeId(), e.stopId(), e.capturedAt(), now, outcome, code, message,
                json.writeValueAsString(e.redacted())));
        log.advance(deviceId, e.sequence(), now);
        Map<String, Object> data = data("eventId", e.eventId(), "type", e.type(), "driverId", driver.driverId(),
                "routeId", e.routeId(), "stopId", e.stopId(), "outcome", outcome, "code", code, "message", message,
                "capturedAt", e.capturedAt() == null ? null : e.capturedAt().toString(), "lat", e.lat(),
                "lon", e.lon());
        feed.publish(new DispatchFeed.Update("driver.event", null, e.routeId(), "NORMAL", data, now));
        return new EventResult(e.eventId(), e.sequence(), outcome, code, message, false, FORWARD_PENDING.equals(code));
    }

    @Override
    @Transactional
    public boolean raiseSos(DriverContext driver, DriverEvent e) {
        Instant now = Instant.now(clock);
        SosKind kind = e.sos() == null ? SosKind.UNSAFE : e.sos();
        GeoPoint where = position(e.lat(), e.lon());
        SosAlert alert = new SosAlert(e.eventId(), driver.driverId(), e.routeId(), kind, where, e.note(),
                e.capturedAt() == null ? now : e.capturedAt(), now, null, null);
        if (!sos.insert(alert)) {
            return false;
        }
        String key = e.routeId() == null ? driver.driverId() : e.routeId();
        events.publish(FulfilmentEvents.SOS_RAISED, FulfilmentEvents.AGGREGATE, key,
                new FulfilmentEventPayloads.SosEvent(e.eventId(), driver.driverId(), e.routeId(), kind.name(),
                        where == null ? null : where.lat(), where == null ? null : where.lon(), e.note(), "HIGH",
                        alert.raisedAt()));
        feed.publish(new DispatchFeed.Update("driver.sos", driver.driverId(), e.routeId(), "HIGH",
                data("alertId", e.eventId(), "driverId", driver.driverId(), "routeId", e.routeId(),
                        "kind", kind.name(), "lat", e.lat(), "lon", e.lon(), "note", e.note(),
                        "raisedAt", alert.raisedAt().toString()), now));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public long lastSequence(String deviceId) {
        return log.lastSequence(deviceId);
    }

    // ------------------------------------------------------------------ handlers

    private boolean handle(DriverContext driver, DriverEvent e, String bearer, Instant now) {
        DriverEventType type = DriverEventType.fromWire(e.type()).orElseThrow(() -> new FulfilmentRuleViolation(
                "fulfilment.event-type-unknown", "Unknown event type " + e.type()));
        if (e.capturedAt() == null) {
            throw new FulfilmentRuleViolation("fulfilment.time-required", "capturedAt is required");
        }
        if (type == DriverEventType.SESSION_SIGNED_IN) {
            return false;
        }
        if (type == DriverEventType.SOS_RAISED) {
            raiseSos(driver, e);
            return false;
        }
        if (e.routeId() == null || e.routeId().isBlank()) {
            throw new FulfilmentRuleViolation("fulfilment.route-required", type.wire() + " needs a routeId");
        }
        DeliveryRoute route = routes.lock(e.routeId()).orElseThrow(() -> new FulfilmentConflict(
                "fulfilment.route-not-found", "Route " + e.routeId() + " no longer exists"));
        route.requireDriver(driver.driverId());
        Instant at = e.capturedAt();
        boolean pending = false;
        switch (type) {
            case INSPECTION_SUBMITTED -> inspect(route, driver, e, now);
            case LOADOUT_SCANNED -> route.scanLoadOut(scan(e).label(), scan(e).manual(), at);
            case LOADOUT_COMPLETED -> {
                List<RoutePackage> loaded = route.completeLoadOut(at);
                forward(loaded, e, driver, bearer, "picked_up");
                handedOver(route, driver, loaded, at);
            }
            case ROUTE_STARTED -> {
                forward(route.start(at), e, driver, bearer, "out_for_delivery");
                publishRoute(FulfilmentEvents.ROUTE_STARTED, route, now);
            }
            case STOP_ARRIVED -> route.arrive(stop(e), at);
            case STOP_SCANNED -> route.scanAtStop(stop(e), scan(e).label(), scan(e).manual(), at);
            case PICKUP_CONFIRMED -> {
                DriverEvent.PickupConfirmation c = require(e.pickup(), "pickup");
                List<RoutePackage> collected = route.confirmPickup(stop(e), c.handedOverBy(), c.missingReason(),
                        rules.pickup(), at);
                forward(collected, e, driver, bearer, "picked_up", "out_for_delivery");
                handedOver(route, driver, collected, at);
                reschedule(route, stop(e));
            }
            case RETURN_PICKUP_COLLECTED -> {
                RouteStop s = route.collectReturn(stop(e), at);
                returnPickupDone(s.id(), null, now);
            }
            case RETURN_PICKUP_FAILED -> {
                RouteStop s = route.failReturn(stop(e), e.note(), at);
                returnPickupDone(s.id(), s.note(), now);
            }
            case PICKUP_PROBLEM -> {
                StopOutcome o = route.pickupProblem(stop(e), require(e.pickupProblem(), "pickupProblem"),
                        rules.pickup(), at);
                if (o == StopOutcome.SKIPPED) {
                    reschedule(route, stop(e));
                    publishStop(FulfilmentEvents.STOP_SKIPPED, route, route.stop(stop(e)),
                            route.packagesForPickup(stop(e)), now);
                }
            }
            case CUSTOMER_CONTACTED -> route.logContact(stop(e), require(e.contact(), "contact"), at);
            case DELIVERY_COMPLETED -> pending = deliver(route, driver, e, bearer, now);
            case DELIVERY_PROBLEM -> problem(route, driver, e, bearer, now);
            case RETURN_SCANNED -> route.scanReturn(scan(e).label(), scan(e).manual(), at);
            case SHIFT_ENDED -> {
                route.endShift(e.odometer(), at);
                if (route.vehicleId() != null) {
                    inspections.recordEndOdometer(route.vehicleId(), route.id(), e.odometer(), now);
                }
                publishRoute(FulfilmentEvents.ROUTE_COMPLETED, route, now);
            }
            default -> throw new FulfilmentRuleViolation("fulfilment.event-type-unknown", "Unhandled " + type);
        }
        routes.save(route);
        return pending;
    }

    private void inspect(DeliveryRoute route, DriverContext driver, DriverEvent e, Instant now) {
        InspectionReport report = require(e.inspection(), "inspection");
        InspectionContext ctx = new InspectionContext(route.perishable(),
                report.vehicleId() == null ? null : inspections.lastOdometer(report.vehicleId()).orElse(null));
        InspectionOutcome outcome = rules.inspection().evaluate(report, ctx);
        route.recordInspection(report.vehicleId(), outcome, report.odometerMiles(), report.defectNote());
        String inspectionId = inspections.save(e.eventId(), route.id(), driver.driverId(), report, outcome, now);
        if (!outcome.result().defect()) {
            return;
        }
        boolean critical = !outcome.criticalFailures().isEmpty();
        List<String> items = outcome.failedItems().stream().map(InspectionItem::name).toList();
        String defectId = inspections.reportDefect(inspectionId, route.id(), report.vehicleId(), driver.driverId(),
                critical, items, report.defectNote(), now);
        String priority = critical ? "HIGH" : "NORMAL";
        events.publish(FulfilmentEvents.VEHICLE_DEFECT, FulfilmentEvents.AGGREGATE, route.id(),
                new FulfilmentEventPayloads.DefectEvent(defectId, route.id(), report.vehicleId(), driver.driverId(),
                        critical, route.blocked(), items, report.defectNote(), priority, now));
        feed.publish(new DispatchFeed.Update("vehicle.defect", driver.driverId(), route.id(), priority,
                data("defectId", defectId, "routeId", route.id(), "vehicleId", report.vehicleId(),
                        "driverId", driver.driverId(), "critical", critical, "routeBlocked", route.blocked(),
                        "items", items, "note", report.defectNote()), now));
    }

    private boolean deliver(DeliveryRoute route, DriverContext driver, DriverEvent e, String bearer, Instant now) {
        String stopId = stop(e);
        ProofOfDelivery pod = require(e.delivery(), "delivery");
        DeliveryStopFacts facts = route.deliveryFacts(stopId);
        PodAssessment assessment = rules.pod().check(facts, pod);
        boolean pending = false;
        List<String> shipmentIds = route.packagesForDrop(stopId).stream().filter(p -> p.status().onBoard())
                .map(RoutePackage::shipmentId).distinct().toList();
        for (String shipmentId : shipmentIds) {
            CourierEvent ce = new CourierEvent(e.eventId() + ":delivered", "delivered", e.capturedAt(),
                    driver.driverId(), pod.photo().assetId(), pod.method().name().toLowerCase(Locale.ROOT),
                    pod.handoverCode() == null ? null : pod.handoverCode().trim(), pod.recipientName(),
                    pod.relationship(), pod.signatureAssetId(), pod.safePlace(), null, pod.photo().lat(),
                    pod.photo().lon(), e.note());
            pending |= !orders.deliverNow(OrdersRelayService.courier(shipmentId, ce), bearer);
        }
        String note = switch (pod.method()) {
            case CUSTOMER -> facts.handoverRequired() ? "Handed to customer · code checked" : "Handed to customer";
            case SOMEONE_ELSE -> "Received by " + pod.recipientName().trim() + " (" + pod.relationship().trim()
                    + ") · signed";
            case SAFE_PLACE -> "Left at " + pod.safePlace().trim();
        };
        List<RoutePackage> delivered = route.markDelivered(stopId, pod.method(), assessment, note, e.capturedAt());
        for (String shipmentId : shipmentIds) {
            shipments.updateStatus(shipmentId, DeliveryShipmentStatus.DELIVERED, route.id());
        }
        publishStop(FulfilmentEvents.STOP_DELIVERED, route, route.stop(stopId), delivered, now);
        return pending;
    }

    private void problem(DeliveryRoute route, DriverContext driver, DriverEvent e, String bearer, Instant now) {
        String stopId = stop(e);
        DeliveryProblem problem = require(e.problem(), "problem");
        List<RoutePackage> onBoard = route.packagesForDrop(stopId).stream().filter(p -> p.status().onBoard())
                .toList();
        ProblemDecision d = route.reportProblem(stopId, problem, rules.reattempt(), e.capturedAt());
        if (!d.reportFailure()) {
            return;
        }
        String reason = d.reason() + (problem.note() == null || problem.note().isBlank() ? ""
                : ": " + problem.note().trim());
        List<Command> cmds = new ArrayList<>();
        for (String shipmentId : onBoard.stream().map(RoutePackage::shipmentId).distinct().toList()) {
            CourierEvent ce = new CourierEvent(e.eventId() + ":delivery_failed", "delivery_failed", e.capturedAt(),
                    driver.driverId(), problem.photo() == null ? null : problem.photo().assetId(), null, null, null,
                    null, null, null, reason, e.lat(), e.lon(), problem.resolution().name());
            cmds.add(OrdersRelayService.courier(shipmentId, ce));
            shipments.updateStatus(shipmentId, DeliveryShipmentStatus.FAILED, route.id());
        }
        orders.submit(cmds, bearer);
        publishStop(FulfilmentEvents.STOP_FAILED, route, route.stop(stopId), onBoard, now);
    }

    /** Packages not collected from a seller go back to the dispatch pool (the seller reschedules). */
    private void reschedule(DeliveryRoute route, String pickupStopId) {
        for (RoutePackage p : route.packagesForPickup(pickupStopId)) {
            if (p.status() == PackageStatus.NOT_PICKED_UP) {
                shipments.updateStatus(p.shipmentId(), DeliveryShipmentStatus.AWAITING_ROUTE, null);
            }
        }
    }

    private void forward(List<RoutePackage> packages, DriverEvent e, DriverContext driver, String bearer,
                         String... statuses) {
        List<Command> cmds = new ArrayList<>();
        for (String shipmentId : packages.stream().map(RoutePackage::shipmentId).distinct().toList()) {
            for (String status : statuses) {
                cmds.add(OrdersRelayService.courier(shipmentId, CourierEvent.step(e.eventId() + ":" + status,
                        status, e.capturedAt(), driver.driverId(), e.lat(), e.lon(), e.note())));
            }
        }
        orders.submit(cmds, bearer);
    }

    /** fulfilment.shipment.handed_over per shipment that left its origin, with its ship-by date. */
    private void handedOver(DeliveryRoute route, DriverContext driver, List<RoutePackage> packages, Instant at) {
        for (String shipmentId : packages.stream().map(RoutePackage::shipmentId).distinct().toList()) {
            shipments.find(shipmentId).ifPresent(s -> events.publish(FulfilmentEvents.SHIPMENT_HANDED_OVER,
                    FulfilmentEvents.AGGREGATE, shipmentId, new FulfilmentEventPayloads.HandedOver(s.shipmentId(),
                            s.orderId(), s.orderNumber(), s.sellerOrgId(), s.fulfilmentType(), s.origin().name(),
                            route.id(), driver.driverId(), s.shipBy(), at, s.shipBy() == null ? null
                            : LocalDate.ofInstant(at, zone).isAfter(s.shipBy()))));
        }
    }

    /** The RETURN_PICKUP stop was collected (why == null) or failed: closes the pickup and tells tax-returns. */
    private void returnPickupDone(String stopId, String why, Instant now) {
        returnPickups.findByStop(stopId).ifPresent(p -> {
            ReturnPickup done = why == null ? p.collected(now) : p.failed(why, now);
            returnPickups.update(done);
            events.publish(why == null ? FulfilmentEvents.RETURN_PICKUP_COLLECTED
                    : FulfilmentEvents.RETURN_PICKUP_FAILED, FulfilmentEvents.AGGREGATE, done.rmaId(),
                    FulfilmentEventPayloads.ReturnPickupEvent.of(done, now));
        });
    }

    private void publishRoute(String type, DeliveryRoute r, Instant now) {
        events.publish(type, FulfilmentEvents.AGGREGATE, r.id(), new FulfilmentEventPayloads.RouteEvent(r.id(),
                r.zone(), r.serviceDate(), r.depotNodeId(), r.driverId(), r.vehicleId(), r.status().name(),
                r.stops().size(), r.packages().size(), now));
    }

    private void publishStop(String type, DeliveryRoute r, RouteStop s, List<RoutePackage> pkgs, Instant now) {
        events.publish(type, FulfilmentEvents.AGGREGATE, r.id(), new FulfilmentEventPayloads.StopEvent(r.id(),
                s.id(), r.driverId(), s.kind().name(), s.status().name(),
                pkgs.stream().map(RoutePackage::shipmentId).distinct().toList(), s.note(),
                s.handoverMethod() == null ? null : s.handoverMethod().name(), null, now));
    }

    // ------------------------------------------------------------------ helpers

    private static EventResult replay(DriverEventLog.Entry prior) {
        return new EventResult(prior.eventId(), prior.sequence(), prior.outcome(), prior.code(), prior.message(),
                true, FORWARD_PENDING.equals(prior.code()));
    }

    private static DriverEvent.Scan scan(DriverEvent e) {
        DriverEvent.Scan s = require(e.scan(), "scan");
        if (s.label() == null || s.label().isBlank()) {
            throw new FulfilmentRuleViolation("fulfilment.label-required", "Scan or type a label");
        }
        return s;
    }

    private static String stop(DriverEvent e) {
        if (e.stopId() == null || e.stopId().isBlank()) {
            throw new FulfilmentRuleViolation("fulfilment.stop-required", e.type() + " needs a stopId");
        }
        return e.stopId();
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new FulfilmentRuleViolation("fulfilment.payload-missing", "This event needs its " + field
                    + " payload");
        }
        return value;
    }

    private static GeoPoint position(Double lat, Double lon) {
        try {
            return GeoPoint.of(lat, lon);
        } catch (FulfilmentRuleViolation invalid) {
            return null;
        }
    }

    /** Map of the non-null pairs (SSE payloads). */
    static Map<String, Object> data(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                out.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        }
        return out;
    }
}
