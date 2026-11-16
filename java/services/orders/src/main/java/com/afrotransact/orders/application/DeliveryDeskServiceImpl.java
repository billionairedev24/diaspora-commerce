package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.orders.domain.ActorKind;
import com.afrotransact.orders.domain.CourierProof;
import com.afrotransact.orders.domain.DeliveryJob;
import com.afrotransact.orders.domain.DeliveryProofDraft;
import com.afrotransact.orders.domain.DeliveryProofRecord;
import com.afrotransact.orders.domain.HandoverMethod;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.OrdersStateConflict;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.ShipmentTransitionPolicy;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.TransitionActor;
import com.afrotransact.orders.domain.TransitionSource;
import com.afrotransact.orders.port.DeliveryDeskRepository;
import com.afrotransact.orders.port.DeliveryDeskRepository.AuditLine;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.Page;
import com.afrotransact.orders.port.StaffDirectory;
import com.afrotransact.orders.port.StaffDirectory.StaffPerson;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeliveryDeskServiceImpl implements DeliveryDeskService {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryDeskServiceImpl.class);
    private static final List<String> ACTIVE = List.of("READY", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY",
            "DELIVERY_FAILED");
    private static final Set<ShipmentStatus> TAKEABLE = Set.of(ShipmentStatus.READY, ShipmentStatus.PICKED_UP,
            ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERY_FAILED);
    private static final int MAX_PAGE = 100;

    private final OrdersAccess access;
    private final OrderRepository orders;
    private final DeliveryDeskRepository desk;
    private final ShipmentTransitions transitions;
    private final ShipmentTransitionPolicy policy;
    private final StaffDirectory staff;
    private final DomainEventPublisher events;
    private final Clock clock;

    DeliveryDeskServiceImpl(OrdersAccess access, OrderRepository orders, DeliveryDeskRepository desk,
                            ShipmentTransitions transitions, ShipmentTransitionPolicy policy, StaffDirectory staff,
                            DomainEventPublisher events, Clock clock) {
        this.access = access;
        this.orders = orders;
        this.desk = desk;
        this.transitions = transitions;
        this.policy = policy;
        this.staff = staff;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public DeskPage list(Query q, int page, int size) {
        access.deliveryReader();
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE);
        int pageNo = Math.max(page, 0);
        List<String> statuses = q.statuses() == null || q.statuses().isEmpty() ? ACTIVE
                : q.statuses().stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).map(DeliveryDeskServiceImpl::status)
                        .map(Enum::name).toList();
        Page<DeliveryDeskRepository.Row> rows = desk.queue(new DeliveryDeskRepository.Filter(statuses, mode(q.mode()),
                q.text(), q.assignee(), q.city(), q.seller(), q.sort(), q.descending()), pageNo * pageSize, pageSize);
        Map<String, Long> counts = new LinkedHashMap<>();
        desk.counts().forEach((k, v) -> counts.put(k.toLowerCase(Locale.ROOT), v));
        return new DeskPage(rows.items().stream().map(DeliveryDeskServiceImpl::row).toList(), pageNo, pageSize,
                rows.total(), counts);
    }

    @Override
    @Transactional(readOnly = true)
    public Detail get(String shipmentId) {
        Actor actor = access.deliveryReader();
        Order order = orderOf(shipmentId, false);
        requireStaffDelivered(order.shipment(shipmentId));
        return detail(order, order.shipment(shipmentId), actor.can(OrdersPermissions.DELIVERY_MANAGE));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Courier> couriers() {
        access.deliveryReader();
        return staff.withPermission(OrdersPermissions.DELIVERY_MANAGE).stream()
                .map(p -> new Courier(p.id(), p.name())).sorted(Comparator.comparing(Courier::name)).toList();
    }

    // ------------------------------------------------------------------ actions

    @Override
    @Transactional
    public Detail take(String shipmentId, Command cmd) {
        Actor actor = access.deliveryManager();
        return assignTo(shipmentId, actor, actor.subject(), cmd, true);
    }

    @Override
    @Transactional
    public Detail assign(String shipmentId, String assigneeId, Command cmd) {
        Actor actor = access.deliveryManager();
        if (assigneeId == null || assigneeId.isBlank()) {
            throw new OrdersRuleViolation("orders.delivery-assignee-required", "Choose who delivers this");
        }
        return assignTo(shipmentId, actor, assigneeId.trim(), cmd, false);
    }

    private Detail assignTo(String shipmentId, Actor actor, String assigneeId, Command cmd, boolean self) {
        Order order = orderOf(shipmentId, true);
        Shipment s = order.shipment(shipmentId);
        requireStaffDelivered(s);
        DeliveryJob job = desk.lockJob(shipmentId).orElse(DeliveryJob.none(shipmentId, Instant.now(clock)));
        if (replayed(shipmentId, cmd)) {
            return detail(order, s, true);
        }
        checkVersion(job, cmd);
        if (!TAKEABLE.contains(s.status())) {
            throw new OrdersStateConflict("orders.delivery-not-ready",
                    "This delivery can be taken once the seller or the warehouse marks it ready");
        }
        if (job.assigned() && job.assigneeId().equals(assigneeId)) {
            return detail(order, s, true);
        }
        if (self && job.assigned()) {
            throw new OrdersStateConflict("orders.delivery-assigned",
                    job.assigneeName() + " has this delivery. Assign it to yourself to take it over");
        }
        String name = self ? nameOf(actor.subject()) : courierName(assigneeId);
        Instant now = Instant.now(clock);
        DeliveryJob next = job.assignedTo(assigneeId, name, actor.subject(), now);
        save(job, next);
        audit(order, s, self ? "TAKE" : job.assigned() ? "REASSIGN" : "ASSIGN", s.status(), s.status(), actor,
                cmd, Map.of("assignee", name));
        events.publish(OrdersEvents.DELIVERY_ASSIGNED, OrdersEvents.AGGREGATE, order.id(),
                new OrdersEventPayloads.DeliveryAssigned(order.id(), order.number(), s.id(), assigneeId, name,
                        actor.subject(), now));
        return detail(order, s, true);
    }

    @Override
    @Transactional
    public Detail pickedUp(String shipmentId, Command cmd) {
        return step(shipmentId, cmd, ShipmentStatus.PICKED_UP, "PICKED_UP", cmd == null ? null : cmd.note(), null,
                null);
    }

    @Override
    @Transactional
    public Detail inTransit(String shipmentId, Command cmd) {
        return step(shipmentId, cmd, ShipmentStatus.IN_TRANSIT, "IN_TRANSIT", cmd == null ? null : cmd.note(), null,
                null);
    }

    @Override
    @Transactional
    public Detail returned(String shipmentId, String reason, Command cmd) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3) {
            throw new OrdersRuleViolation("orders.return-reason-required",
                    "Say why the parcel goes back to the seller (at least a few words)");
        }
        if (why.length() > 500) {
            throw new OrdersRuleViolation("orders.failure-reason-too-long", "The reason can be up to 500 characters");
        }
        return step(shipmentId, cmd, ShipmentStatus.RETURNED, "RETURNED", why, null, null);
    }

    @Override
    @Transactional
    public Detail outForDelivery(String shipmentId, Command cmd) {
        return step(shipmentId, cmd, ShipmentStatus.OUT_FOR_DELIVERY, "OUT_FOR_DELIVERY",
                cmd == null ? null : cmd.note(), null, null);
    }

    @Override
    @Transactional
    public Detail delivered(String shipmentId, ProofInput in, Command cmd) {
        DeliveryProofDraft draft = DeliveryProofDraft.check(in == null ? null : in.photoAssetIds(),
                in == null ? null : in.recipientName(), in == null ? null : in.note(),
                in == null ? null : in.latitude(), in == null ? null : in.longitude());
        return step(shipmentId, cmd, ShipmentStatus.DELIVERED, "DELIVERED", draft.note(), draft, null);
    }

    @Override
    @Transactional
    public Detail failed(String shipmentId, String reason, Command cmd) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3) {
            throw new OrdersRuleViolation("orders.failure-reason-required",
                    "Say why the delivery failed (at least a few words)");
        }
        if (why.length() > 500) {
            throw new OrdersRuleViolation("orders.failure-reason-too-long", "The reason can be up to 500 characters");
        }
        return step(shipmentId, cmd, ShipmentStatus.DELIVERY_FAILED, "FAILED", why, null, why);
    }

    private Detail step(String shipmentId, Command cmd, ShipmentStatus to, String action, String reason,
                        DeliveryProofDraft proof, String failure) {
        Actor actor = access.deliveryManager();
        Order order = orderOf(shipmentId, true);
        Shipment s = order.shipment(shipmentId);
        requireStaffDelivered(s);
        DeliveryJob job = desk.lockJob(shipmentId).orElse(DeliveryJob.none(shipmentId, Instant.now(clock)));
        if (replayed(shipmentId, cmd)) {
            return detail(order, s, true);
        }
        checkStatus(s, cmd);
        checkVersion(job, cmd);
        if (s.status() == to) {
            // Already there: the same step twice changes nothing (and never replaces a saved proof).
            return detail(order, s, true);
        }
        ShipmentStatus from = s.status();
        if (!job.assigned()) {
            // Nobody has it yet: whoever takes the step takes the job (audited as a take), so no extra click is needed.
            // The transition below is still refused when the step is not legal, and then nothing here is saved.
            policy.decide(s, to, ActorKind.DELIVERY_STAFF);
            Instant at = Instant.now(clock);
            String me = nameOf(actor.subject());
            DeliveryJob taken = job.assignedTo(actor.subject(), me, actor.subject(), at);
            save(job, taken);
            audit(order, s, "TAKE", s.status(), s.status(), actor, me, new Command(null, null, null),
                    Map.of("assignee", me, "automatic", true));
            events.publish(OrdersEvents.DELIVERY_ASSIGNED, OrdersEvents.AGGREGATE, order.id(),
                    new OrdersEventPayloads.DeliveryAssigned(order.id(), order.number(), s.id(), actor.subject(), me,
                            actor.subject(), at));
            job = taken;
        }
        String key = cmd == null || cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank()
                ? UUID.randomUUID().toString() : cmd.idempotencyKey().trim();
        CourierProof history = proof == null ? failure == null ? null : failureProof(failure) : historyProof(proof);
        ShipmentTransitions.Outcome outcome = transitions.apply(new ShipmentTransitions.Request(shipmentId, to,
                (o, sh) -> new TransitionActor(ActorKind.DELIVERY_STAFF, actor.subject(), null), reason,
                TransitionSource.API, "desk:" + key, null, history, null));
        if (!outcome.applied()) {
            return detail(outcome.order(), outcome.order().shipment(shipmentId), true);
        }
        Instant now = Instant.now(clock);
        boolean reattempt = to == ShipmentStatus.OUT_FOR_DELIVERY && from == ShipmentStatus.DELIVERY_FAILED;
        DeliveryJob next = failure != null ? job.failed(failure, now) : job.touched(now);
        save(job, next);
        String staffName = nameOf(actor.subject());
        Map<String, Object> detail = new LinkedHashMap<>();
        if (reason != null && !reason.isBlank()) {
            detail.put(failure != null ? "reason" : "note", reason);
        }
        if (failure != null) {
            detail.put("attempt", next.attempts());
        }
        if (proof != null) {
            detail.put("photos", proof.photoAssetIds().size());
            detail.put("recipientNamed", proof.recipientName() != null);
        }
        audit(order, s, reattempt ? "REATTEMPT" : action, from, to, actor, staffName, new Command(null, key, null),
                detail);
        if (proof != null) {
            Instant at = outcome.shipment().deliveredAt() == null ? now : outcome.shipment().deliveredAt();
            DeliveryProofRecord record = DeliveryProofRecord.of(UUID.randomUUID().toString(), shipmentId, proof, at,
                    actor.subject(), staffName);
            desk.insertProof(record);
            events.publish(OrdersEvents.DELIVERY_PROOF_ATTACHED, OrdersEvents.AGGREGATE, order.id(),
                    proofEvent(outcome.order(), outcome.shipment(), record, now));
        }
        Order fresh = orders.findById(order.id()).orElse(outcome.order());
        return detail(fresh, fresh.shipment(shipmentId), true);
    }

    @Override
    @Transactional
    public Detail correctProof(String shipmentId, ProofInput in, String reason, Command cmd) {
        Actor actor = access.deliveryManager();
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3) {
            throw new OrdersRuleViolation("orders.proof-correction-reason-required",
                    "Say why the proof is being corrected");
        }
        if (why.length() > 500) {
            throw new OrdersRuleViolation("orders.failure-reason-too-long", "The reason can be up to 500 characters");
        }
        DeliveryProofDraft draft = DeliveryProofDraft.check(in == null ? null : in.photoAssetIds(),
                in == null ? null : in.recipientName(), in == null ? null : in.note(),
                in == null ? null : in.latitude(), in == null ? null : in.longitude());
        Order order = orderOf(shipmentId, true);
        Shipment s = order.shipment(shipmentId);
        requireStaffDelivered(s);
        if (replayed(shipmentId, cmd)) {
            return detail(order, s, true);
        }
        DeliveryProofRecord old = desk.findProof(shipmentId).orElseThrow(() -> new OrdersStateConflict(
                "orders.delivery-no-proof", "This delivery has no saved proof to correct"));
        if (cmd != null && cmd.expectedVersion() != null && cmd.expectedVersion() != old.version()) {
            throw new OrdersStateConflict("orders.delivery-version-conflict",
                    "Someone changed this proof; reload and try again");
        }
        Instant now = Instant.now(clock);
        String staffName = nameOf(actor.subject());
        DeliveryProofRecord next = old.corrected(draft, now, actor.subject(), staffName);
        if (!desk.updateProof(next, old.version())) {
            throw new OrdersStateConflict("orders.delivery-version-conflict",
                    "Someone changed this proof; reload and try again");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", why);
        detail.put("before", Map.of("photos", old.photoAssetIds(), "recipientName", String.valueOf(old.recipientName()),
                "note", String.valueOf(old.note())));
        detail.put("after", Map.of("photos", next.photoAssetIds(), "recipientName", String.valueOf(next.recipientName()),
                "note", String.valueOf(next.note())));
        audit(order, s, "PROOF_CORRECTED", s.status(), s.status(), actor, staffName, cmd, detail);
        events.publish(OrdersEvents.DELIVERY_PROOF_CORRECTED, OrdersEvents.AGGREGATE, order.id(),
                proofEvent(order, s, next, now));
        return detail(order, s, true);
    }

    // ------------------------------------------------------------------ helpers

    private boolean replayed(String shipmentId, Command cmd) {
        return cmd != null && cmd.idempotencyKey() != null && !cmd.idempotencyKey().isBlank()
                && desk.auditKeySeen(shipmentId, cmd.idempotencyKey().trim());
    }

    /** The caller names the status it saw; if another person has moved the shipment since, it is told, not overruled. */
    private static void checkStatus(Shipment s, Command cmd) {
        if (cmd != null && cmd.expectedStatus() != null && !cmd.expectedStatus().isBlank()
                && !cmd.expectedStatus().trim().equalsIgnoreCase(s.status().wire())) {
            throw new OrdersStateConflict("orders.delivery-status-changed", "Someone already moved this shipment to "
                    + s.status().wire().replace('_', ' ') + ". Reload to see it.");
        }
    }

    private static void checkVersion(DeliveryJob job, Command cmd) {
        if (cmd != null && cmd.expectedVersion() != null && cmd.expectedVersion() != job.version()) {
            throw new OrdersStateConflict("orders.delivery-version-conflict",
                    "Someone else changed this delivery; reload and try again");
        }
    }

    private void save(DeliveryJob before, DeliveryJob after) {
        if (!desk.saveJob(after, before.version())) {
            throw new OrdersStateConflict("orders.delivery-version-conflict",
                    "Someone else changed this delivery; reload and try again");
        }
    }

    private static void requireStaffDelivered(Shipment s) {
        if (!s.fulfilmentMode().staffDelivers()) {
            throw new NotFoundException("orders.delivery-not-found", "No AfroTransact delivery for this shipment");
        }
    }

    private Order orderOf(String shipmentId, boolean lock) {
        String orderId = orders.orderIdOfShipment(shipmentId).orElseThrow(() -> new NotFoundException(
                "orders.delivery-not-found", "No AfroTransact delivery for this shipment"));
        return (lock ? orders.lockById(orderId) : orders.findById(orderId)).orElseThrow(() -> new NotFoundException(
                "orders.delivery-not-found", "No AfroTransact delivery for this shipment"));
    }

    private void audit(Order order, Shipment s, String action, ShipmentStatus from, ShipmentStatus to, Actor actor,
                       Command cmd, Map<String, Object> detail) {
        audit(order, s, action, from, to, actor, nameOf(actor.subject()), cmd, detail);
    }

    private void audit(Order order, Shipment s, String action, ShipmentStatus from, ShipmentStatus to, Actor actor,
                       String actorName, Command cmd, Map<String, Object> detail) {
        String key = cmd == null || cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank() ? null
                : cmd.idempotencyKey().trim();
        desk.appendAudit(order.id(), new AuditLine(0, s.id(), action, from.name(), to.name(), actor.subject(),
                actorName, detail, Instant.now(clock)), key);
    }

    private static OrdersEventPayloads.DeliveryProof proofEvent(Order o, Shipment s, DeliveryProofRecord p,
                                                                Instant at) {
        return new OrdersEventPayloads.DeliveryProof(o.id(), o.number(), o.customerId(), s.id(), s.sellerOrgId(),
                p.photoAssetIds().size(), p.recipientName() != null, p.latitude() != null, p.deliveredAt(),
                p.corrections(), p.recordedById(), at);
    }

    /** The proof the order history carries (the customer, the seller and staff read it from the order). */
    private static CourierProof historyProof(DeliveryProofDraft d) {
        return new CourierProof(d.photoAssetIds().getFirst(), HandoverMethod.PHOTO, null, d.recipientName(), null,
                null, null, null, d.latitude(), d.longitude(), d.photoAssetIds());
    }

    private static CourierProof failureProof(String reason) {
        return new CourierProof(null, null, null, null, null, null, null, reason, null, null);
    }

    private String nameOf(String userId) {
        try {
            return staff.people(List.of(userId)).stream().filter(p -> p.id().equals(userId)).map(StaffPerson::name)
                    .filter(n -> n != null && !n.isBlank()).findFirst().orElse("Staff member");
        } catch (RuntimeException ex) {
            LOG.warn("Staff name of {} unavailable ({}); the audit line says 'Staff member'", userId, ex.getMessage());
            return "Staff member";
        }
    }

    private String courierName(String assigneeId) {
        return staff.withPermission(OrdersPermissions.DELIVERY_MANAGE).stream().filter(p -> p.id().equals(assigneeId))
                .map(StaffPerson::name).findFirst().orElseThrow(() -> new OrdersRuleViolation(
                        "orders.delivery-assignee-invalid",
                        "That person cannot be assigned deliveries (they need the delivery:manage permission)"));
    }

    private static ShipmentStatus status(String s) {
        try {
            return ShipmentStatus.valueOf(s);
        } catch (IllegalArgumentException ex) {
            throw new OrdersRuleViolation("orders.status-invalid", "Unknown status " + s);
        }
    }

    private static String mode(String m) {
        if (m == null || m.isBlank()) {
            return null;
        }
        String v = m.trim().toUpperCase(Locale.ROOT);
        if (!v.equals("AFROTRANSACT_DELIVERY") && !v.equals("FULFILLED_BY_AFROTRANSACT")) {
            throw new OrdersRuleViolation("orders.mode-invalid", "Unknown fulfilment mode " + m);
        }
        return v;
    }

    private static Row row(DeliveryDeskRepository.Row r) {
        String label = "Shipment " + r.seq() + " of " + r.shipmentCount();
        String mode = r.fromSellerStore() ? "AFROTRANSACT_DELIVERY" : "FULFILLED_BY_AFROTRANSACT";
        return new Row(r.shipmentId(), r.orderNumber(), label, mode, r.status().toLowerCase(Locale.ROOT),
                r.sellerName(), r.nodeId(), r.pickupLocationId(), r.customerName(), r.customerCity(),
                r.customerState(), r.customerPostalCode(), r.units(), r.perishable(), r.promisedBy(), r.assigneeName(),
                r.assigneeId(), r.attempts(), r.jobVersion(), r.proofAttached(), r.statusChangedAt(),
                r.deliveredAt());
    }

    private Detail detail(Order order, Shipment s, boolean canManage) {
        DeliveryJob job = desk.findJob(s.id()).orElse(DeliveryJob.none(s.id(), Instant.now(clock)));
        DeliveryProofRecord proof = desk.findProof(s.id()).orElse(null);
        ShipTo to = order.shipTo();
        int units = s.lines().stream().mapToInt(OrderLine::quantity).sum();
        Row row = new Row(s.id(), order.number(), "Shipment " + s.seq() + " of " + order.shipments().size(),
                s.fulfilmentMode().name(), s.status().wire(), s.sellerName(), s.nodeId(),
                s.localDelivery() == null ? null : s.localDelivery().shipFromLocationId(),
                to == null ? null : to.fullName(), to == null ? null : to.city(), to == null ? null : to.state(),
                to == null ? null : to.postalCode(), units, s.perishable(), s.promisedBy(), job.assigneeName(),
                job.assigneeId(), job.attempts(), job.version(), proof != null, s.statusChangedAt(), s.deliveredAt());
        Destination dest = to == null ? null : canManage
                ? new Destination(to.fullName(), to.line1(), to.line2(), to.city(), to.state(), to.postalCode(),
                        to.phone(), to.instructions(), s.preferences().handToCustomer())
                : new Destination(to.fullName(), null, null, to.city(), to.state(), null, null, null,
                        s.preferences().handToCustomer());
        List<String> next = canManage ? policy.nextFor(s, ActorKind.DELIVERY_STAFF).stream()
                .sorted(Comparator.comparingInt(ShipmentStatus::rank)).map(ShipmentStatus::wire).toList() : List.of();
        return new Detail(row, dest, s.lines().stream().map(l -> new Item(l.title(), l.quantity())).toList(),
                job.version() == 0 && !job.assigned() && job.attempts() == 0 ? new Job(null, null, null, null, 0, null,
                        0) : new Job(job.assigneeId(), job.assigneeName(), job.assignedAt(),
                        job.assignedBy() == null ? null : nameOf(job.assignedBy()), job.attempts(), job.lastFailure(),
                        job.version()),
                proof == null ? null : new Proof(proof.photoAssetIds(), proof.recipientName(), proof.note(),
                        canManage ? proof.latitude() : null, canManage ? proof.longitude() : null, proof.deliveredAt(),
                        proof.recordedByName(), proof.correctedAt(), proof.correctedByName(), proof.corrections(),
                        proof.version()),
                desk.audit(s.id()).stream().map(a -> new AuditEntry(a.action(), a.fromStatus(), a.toStatus(),
                        a.actorName(), canManage ? a.detail() : Map.of(), a.occurredAt())).toList(),
                next, canManage);
    }
}
