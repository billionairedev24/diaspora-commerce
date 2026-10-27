package com.afrotransact.deliveryruns.adapter.in.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.AttributionService;
import com.afrotransact.deliveryruns.application.OrderPlaced;
import com.afrotransact.deliveryruns.application.PreorderProjectionService;
import com.afrotransact.deliveryruns.application.RunProgressService;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.RefundStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code orders.order.placed} (pre-order packages: {@code fulfilmentType=PREORDER} with a {@code windowId},
 * read from {@code packages[]} or {@code shipments[]}; attribution {@code {src, cr, po}}), {@code
 * orders.shipment.cancelled} and {@code orders.order.cancelled} (placed orders) from {@code at.order.events} into the
 * pre-order projection, and the PREORDER shipments' {@code orders.shipment.status_changed} / {@code
 * orders.shipment.delivered} into the run's delivery progress (S264: the run starts collecting, goes out for delivery
 * and completes from what the driver does, and a delivery starts the seller payout hold in orders / payments).
 * Malformed messages are logged and skipped; use-case failures propagate so the container retries the record.
 */
@Component
class OrderEventsListener {

    static final String ORDER_PLACED = "orders.order.placed";
    static final String SHIPMENT_CANCELLED = "orders.shipment.cancelled";
    static final String ORDER_CANCELLED = "orders.order.cancelled";
    static final String SHIPMENT_STATUS_CHANGED = "orders.shipment.status_changed";
    static final String SHIPMENT_DELIVERED = "orders.shipment.delivered";
    static final String REFUND_SETTLED = "orders.preorder.refund_settled";
    static final String REFUND_FAILED = "orders.preorder.refund_failed";
    static final String PREORDER = "PREORDER";

    private static final Logger LOG = LoggerFactory.getLogger(OrderEventsListener.class);

    private final PreorderProjectionService projection;
    private final RunProgressService progress;
    private final AttributionService attribution;
    private final JsonMapper json;

    OrderEventsListener(PreorderProjectionService projection, RunProgressService progress,
                        AttributionService attribution, JsonMapper json) {
        this.projection = projection;
        this.progress = progress;
        this.attribution = attribution;
        this.json = json;
    }

    @KafkaListener(id = "delivery-runs-preorders", groupId = "${spring.kafka.consumer.group-id:delivery-runs-service}",
            topics = "${at.delivery-runs.consumers.order-topic:at.order.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = header(rec, "event-type").orElse("");
        if (!ORDER_PLACED.equals(type) && !SHIPMENT_CANCELLED.equals(type) && !ORDER_CANCELLED.equals(type)
                && !REFUND_SETTLED.equals(type) && !REFUND_FAILED.equals(type)
                && !SHIPMENT_STATUS_CHANGED.equals(type) && !SHIPMENT_DELIVERED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = header(rec, "event-id").flatMap(OrderEventsListener::uuid);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id header", type, rec.topic(),
                    rec.partition(), rec.offset());
            return;
        }
        JsonNode n;
        try {
            n = json.readTree(rec.value());
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        handle(type, eventId.get(), n);
    }

    /** Dispatches one parsed event (package-private for tests). */
    void handle(String type, UUID eventId, JsonNode n) {
        switch (type) {
            case ORDER_PLACED -> {
                attribute(n);
                placed(eventId, n).ifPresent(e -> projection.onOrderPlaced(eventId, e));
            }
            case SHIPMENT_CANCELLED -> {
                String orderId = text(n, "orderId");
                String shipmentId = text(n, "shipmentId");
                if (orderId == null || shipmentId == null) {
                    LOG.warn("Skipping orders.shipment.cancelled {}: orderId / shipmentId missing", eventId);
                    return;
                }
                projection.onShipmentCancelled(eventId, orderId, shipmentId, instant(n, "cancelledAt"));
                progress.onShipmentCancelled(eventId, orderId, shipmentId, instant(n, "cancelledAt"));
            }
            case SHIPMENT_STATUS_CHANGED -> {
                String orderId = text(n, "orderId");
                if (orderId == null || !PREORDER.equalsIgnoreCase(text(n, "fulfilmentType"))) {
                    return;
                }
                progress.onShipmentMoved(eventId, orderId, text(n, "shipmentId"), text(n, "to"),
                        instant(n, "occurredAt"));
            }
            case SHIPMENT_DELIVERED -> {
                String orderId = text(n, "orderId");
                if (orderId == null || !PREORDER.equalsIgnoreCase(text(n, "fulfilmentType"))) {
                    return;
                }
                progress.onShipmentDelivered(eventId, orderId, text(n, "shipmentId"), instant(n, "deliveredAt"));
            }
            case ORDER_CANCELLED -> {
                String orderId = text(n, "orderId");
                if (orderId == null || !n.path("wasPlaced").asBoolean(true)) {
                    return;
                }
                projection.onOrderCancelled(eventId, orderId, instant(n, "cancelledAt"));
            }
            case REFUND_SETTLED, REFUND_FAILED -> refund(type, eventId, n);
            default -> {
                // other order events are not needed
            }
        }
    }

    /**
     * Remembers the advertorial an order came from ({@code attribution.cr=adv-...}) whatever the order carries: the
     * run slot it takes is joined at read time (S263), so it does not matter which of the two arrives first.
     */
    private void attribute(JsonNode n) {
        JsonNode a = n.path("attribution");
        String creative = a.isObject() ? text(a, "cr") : null;
        String orderId = text(n, "orderId");
        Instant placedAt = instant(n, "placedAt");
        if (creative != null && orderId != null && placedAt != null) {
            attribution.orderPlaced(orderId, creative, text(a, "src"), placedAt);
        }
    }

    private void refund(String type, UUID eventId, JsonNode n) {
        String orderId = text(n, "orderId");
        if (orderId == null) {
            LOG.warn("Skipping {} {}: orderId missing", type, eventId);
            return;
        }
        RefundStatus status = REFUND_FAILED.equals(type) ? RefundStatus.FAILED : "VOIDED".equalsIgnoreCase(
                text(n, "status")) ? RefundStatus.VOIDED : RefundStatus.REFUNDED;
        JsonNode amount = n.path("amountMinor");
        projection.onRefund(eventId, orderId, status, amount.isNumber() ? amount.asLong() : null, text(n, "error"),
                instant(n, "at"));
    }

    /** The order's pre-order packages; empty when it has none (most orders). */
    static Optional<OrderPlaced> placed(UUID eventId, JsonNode n) {
        JsonNode list = n.path("packages").isArray() ? n.path("packages") : n.path("shipments");
        List<OrderPlaced.Package> packages = new ArrayList<>();
        for (JsonNode p : list) {
            JsonNode pre = p.path("preorder");
            String windowId = text(p, "windowId") != null ? text(p, "windowId") : text(pre, "windowId");
            boolean isPreorder = PREORDER.equalsIgnoreCase(text(p, "fulfilmentType")) || windowId != null
                    && pre.isObject();
            if (!isPreorder || windowId == null) {
                continue;
            }
            if ("RUN".equalsIgnoreCase(text(pre, "source"))) {
                // S261: an order on a staff-scheduled run holds a slot on it (orders reserved it through the slot
                // API); there is no pre-order window to project it into.
                continue;
            }
            Optional<UUID> wid = uuid(windowId);
            if (wid.isEmpty()) {
                LOG.warn("Skipping a package of order event {}: invalid windowId {}", eventId, windowId);
                continue;
            }
            String f = text(p, "fulfilment") != null ? text(p, "fulfilment") : text(pre, "fulfilment");
            PreorderFulfilment fulfilment = "PICKUP".equalsIgnoreCase(f)
                    || "PICKUP".equalsIgnoreCase(text(p, "deliveryMethod")) ? PreorderFulfilment.PICKUP
                    : PreorderFulfilment.DOOR;
            List<PreorderLine> lines = new ArrayList<>();
            for (JsonNode l : p.path("lines")) {
                String atin = text(l, "variantAtin") != null ? text(l, "variantAtin") : text(l, "atin");
                String name = text(l, "title") != null ? text(l, "title") : text(l, "name");
                // A window's package mixes sellers: the line names its own, the package's first seller is the fallback.
                String seller = text(l, "sellerOrgId") != null ? text(l, "sellerOrgId") : text(p, "sellerOrgId");
                String sellerName = text(l, "sellerOrgId") != null ? text(l, "sellerName") : text(p, "sellerName");
                lines.add(new PreorderLine(atin, name, seller, sellerName,
                        l.path("quantity").asInt(l.path("qty").asInt(1))));
            }
            packages.add(new OrderPlaced.Package(text(p, "shipmentId") != null ? text(p, "shipmentId")
                    : text(p, "packageId"), text(p, "sellerOrgId"), wid.get(), fulfilment,
                    p.path("totalMinor").asLong(0), p.path("shippingMinor").asLong(0), lines));
        }
        if (packages.isEmpty()) {
            return Optional.empty();
        }
        String orderId = text(n, "orderId");
        Instant placedAt = instant(n, "placedAt");
        if (orderId == null || placedAt == null) {
            LOG.warn("Skipping orders.order.placed {}: orderId / placedAt missing", eventId);
            return Optional.empty();
        }
        JsonNode a = n.path("attribution");
        if (!a.isObject()) {
            for (JsonNode p : list) {
                if (p.path("preorder").path("attribution").isObject()) {
                    a = p.path("preorder").path("attribution");
                    break;
                }
            }
        }
        JsonNode first = list.size() > 0 ? list.get(0) : n;
        return Optional.of(new OrderPlaced(orderId, text(n, "orderNumber"), text(n, "customerId"),
                text(n, "currency") == null ? "USD" : text(n, "currency").toUpperCase(Locale.ROOT), placedAt,
                text(n, "shipToName"), firstText(n, first, "shipToPhone", "customerPhone"),
                firstText(n, first, "customerEmail", "email"), firstText(n, first, "shipToLine1", "shipToLine1"),
                text(n, "shipToCity"), text(n, "shipToState"), text(n, "shipToPostalCode"),
                firstText(n, first, "deliveryNote", "deliveryInstructions"), text(a, "src"), text(a, "cr"),
                text(a, "po"), packages));
    }

    private static String firstText(JsonNode order, JsonNode pkg, String a, String b) {
        for (String f : new String[] {a, b}) {
            String v = text(order, f);
            if (v == null) {
                v = text(pkg, f);
            }
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Optional<String> header(ConsumerRecord<?, ?> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null || h.value() == null ? Optional.empty()
                : Optional.of(new String(h.value(), StandardCharsets.UTF_8));
    }

    private static Optional<UUID> uuid(String s) {
        try {
            return Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asString().isBlank() ? null : v.asString();
    }

    private static Instant instant(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
