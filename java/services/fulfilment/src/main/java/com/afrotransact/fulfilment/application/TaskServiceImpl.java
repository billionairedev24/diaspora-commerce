package com.afrotransact.fulfilment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.fulfilment.domain.FulfilmentTask;
import com.afrotransact.fulfilment.domain.TaskStatus;
import com.afrotransact.fulfilment.port.CentreRepository;
import com.afrotransact.fulfilment.port.DeliveryShipmentRepository;
import com.afrotransact.fulfilment.port.IdIssuer;
import com.afrotransact.fulfilment.port.TaskRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaskServiceImpl implements TaskService {

    private final FulfilmentAccess access;
    private final TaskRepository tasks;
    private final CentreRepository centres;
    private final DeliveryShipmentRepository shipments;
    private final IdIssuer ids;
    private final OrdersRelayService orders;
    private final DomainEventPublisher events;
    private final Clock clock;

    TaskServiceImpl(FulfilmentAccess access, TaskRepository tasks, CentreRepository centres,
                    DeliveryShipmentRepository shipments, IdIssuer ids, OrdersRelayService orders,
                    DomainEventPublisher events, Clock clock) {
        this.access = access;
        this.tasks = tasks;
        this.centres = centres;
        this.shipments = shipments;
        this.ids = ids;
        this.orders = orders;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FulfilmentTask> list(String nodeId, TaskStatus status, int limit) {
        access.operator();
        return tasks.list(nodeId, status, Math.max(1, Math.min(limit, 200)));
    }

    @Override
    @Transactional(readOnly = true)
    public FulfilmentTask get(String id) {
        access.operator();
        return tasks.find(id).orElseThrow(() -> notFound(id));
    }

    @Override
    @Transactional
    public FulfilmentTask assignNode(String id, String nodeId) {
        access.operator();
        if (centres.find(nodeId).isEmpty()) {
            throw new NotFoundException("fulfilment.centre-not-found", "Fulfilment centre " + nodeId + " not found");
        }
        FulfilmentTask t = lock(id);
        t.assignNode(nodeId, Instant.now(clock));
        tasks.update(t);
        return t;
    }

    @Override
    @Transactional
    public FulfilmentTask startPick(String id) {
        Actor actor = access.operator();
        FulfilmentTask t = lock(id);
        boolean first = t.status() == TaskStatus.PICK_PENDING;
        t.startPick(actor.subject(), Instant.now(clock));
        tasks.update(t);
        if (first) {
            orders.submit(List.of(OrdersRelayService.transition(t.shipmentId(), "confirmed",
                    "Picking started at " + t.nodeId(), key(t, "confirmed"))), null);
        }
        return t;
    }

    @Override
    @Transactional
    public FulfilmentTask completePick(String id, Map<String, Integer> picked, Map<String, String> lots,
                                       String shortReason) {
        access.operator();
        FulfilmentTask t = lock(id);
        Instant now = Instant.now(clock);
        boolean complete = t.completePick(picked == null ? Map.of() : picked, lots == null ? Map.of() : lots,
                shortReason, now);
        tasks.update(t);
        publish(complete ? FulfilmentEvents.TASK_PICKED : FulfilmentEvents.TASK_PICK_EXCEPTION, t, now);
        return t;
    }

    @Override
    @Transactional
    public FulfilmentTask pack(String id, double weightLb, String carrier, String trackingNumber) {
        Actor actor = access.operator();
        FulfilmentTask t = lock(id);
        Instant now = Instant.now(clock);
        String label = t.packageLabel() != null ? t.packageLabel() : ids.packageLabel();
        t.pack(label, weightLb, blankToNull(carrier), blankToNull(trackingNumber), actor.subject(), now);
        tasks.update(t);
        shipments.find(t.shipmentId()).filter(s -> s.label() == null).ifPresent(s ->
                shipments.updateLabel(s.shipmentId(), label));
        orders.submit(t.carrierDelivery()
                ? List.of(OrdersRelayService.transition(t.shipmentId(), "packed", "Packed at " + t.nodeId(),
                        key(t, "packed")), OrdersRelayService.tracking(t.shipmentId(), t.carrier(),
                        t.trackingNumber()))
                : List.of(OrdersRelayService.transition(t.shipmentId(), "packed", "Packed at " + t.nodeId(),
                        key(t, "packed"))), null);
        publish(FulfilmentEvents.TASK_PACKED, t, now);
        return t;
    }

    @Override
    @Transactional
    public FulfilmentTask ready(String id) {
        access.operator();
        FulfilmentTask t = lock(id);
        Instant now = Instant.now(clock);
        t.ready(now);
        tasks.update(t);
        orders.submit(List.of(OrdersRelayService.transition(t.shipmentId(), "ready",
                "Staged at the outbound dock of " + t.nodeId(), key(t, "ready"))), null);
        publish(FulfilmentEvents.TASK_READY, t, now);
        return t;
    }

    private void publish(String type, FulfilmentTask t, Instant at) {
        events.publish(type, FulfilmentEvents.AGGREGATE, t.shipmentId(), new FulfilmentEventPayloads.TaskEvent(
                t.id(), t.shipmentId(), t.orderId(), t.orderNumber(), t.nodeId(), t.status().name(),
                t.packageLabel(), t.exceptionReason(), at));
    }

    private FulfilmentTask lock(String id) {
        return tasks.lock(id).orElseThrow(() -> notFound(id));
    }

    private static String key(FulfilmentTask t, String step) {
        return "fulfilment:" + t.id() + ":" + step;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("fulfilment.task-not-found", "Task " + id + " not found");
    }
}
