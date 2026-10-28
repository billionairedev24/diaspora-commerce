package com.afrotransact.orders.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.domain.ActorKind;
import com.afrotransact.orders.domain.CourierProof;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.FeeRuleSnapshot;
import com.afrotransact.orders.domain.LocalDelivery;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderOrigin;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.PreorderPackage;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.StatusChange;
import com.afrotransact.orders.domain.TransitionSource;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.Page;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL orders: header, shipments, lines and history; optimistic version on the order header. */
@Repository
class JdbcOrderRepository implements OrderRepository {

    private static final String ORDER_COLUMNS = """
            o.id, o.number, o.customer_id, o.currency, o.status, o.items_minor, o.shipping_minor, o.tax_minor,
            o.discount_minor, o.total_minor, o.ship_to::text as ship_to, o.payment_id, o.workflow_id, o.cancel_reason,
            o.placed_at, o.created_at, o.version, o.origin::text as origin""";
    private static final String SHIPMENT_COLUMNS = """
            s.id, s.order_id, s.seq, s.seller_org_id, s.seller_name, s.fulfilment_type, s.node_id, s.delivery_method,
            s.service_level, s.pickup_location_id, s.preferences::text as preferences, s.perishable,
            s.handover_required, s.handover_code_hash, s.handover_code_sealed, s.status, s.shipping_minor, s.carrier,
            s.tracking_number, s.promised_from, s.promised_by, s.capture_id, s.captured_at, s.delivered_at,
            s.last_external_event_at, s.status_changed_at, s.version, s.local_delivery::text as local_delivery,
            s.preorder::text as preorder, s.delivery_fee_rule::text as delivery_fee_rule""";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcOrderRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ writes

    @Override
    public void insert(Order o) {
        Instant now = Instant.now();
        jdbc.sql("""
                insert into customer_order (id, number, customer_id, currency, status, items_minor, shipping_minor,
                    tax_minor, discount_minor, total_minor, ship_to, payment_id, workflow_id, cancel_reason, placed_at,
                    created_at, updated_at, version, origin, channel)
                values (:id, :number, :customer, :currency, :status, :items, :shipping, :tax, :discount, :total,
                    cast(:shipTo as jsonb), :payment, :workflow, :cancelReason, :placedAt, :createdAt, :now, 0,
                    cast(:origin as jsonb), :channel)
                """)
                .param("id", Sql.uuid(o.id())).param("number", o.number()).param("customer", o.customerId())
                .param("currency", o.currency()).param("status", o.status().name())
                .param("items", o.totals().itemsMinor()).param("shipping", o.totals().shippingMinor())
                .param("tax", o.totals().taxMinor()).param("discount", o.totals().discountMinor())
                .param("total", o.totals().grandTotalMinor()).param("shipTo", json.write(o.shipTo()))
                .param("payment", o.paymentId()).param("workflow", o.workflowId())
                .param("cancelReason", o.cancelReason()).param("placedAt", Sql.ts(o.placedAt()))
                .param("createdAt", Sql.ts(o.createdAt() == null ? now : o.createdAt())).param("now", Sql.ts(now))
                .param("origin", json.write(o.origin())).param("channel", o.origin().channel().name())
                .update();
        for (Shipment s : o.shipments()) {
            jdbc.sql("""
                    insert into shipment (id, order_id, seq, seller_org_id, seller_name, fulfilment_type, node_id,
                        delivery_method, service_level, pickup_location_id, preferences, perishable, handover_required,
                        handover_code_hash, handover_code_sealed, status, shipping_minor, carrier, tracking_number,
                        promised_from, promised_by, capture_id, captured_at, delivered_at, last_external_event_at,
                        status_changed_at, created_at, updated_at, version, local_delivery, preorder,
                        preorder_window_id, delivery_fee_rule)
                    values (:id, :order, :seq, :seller, :sellerName, :type, :node, :method, :level, :pickup,
                        cast(:prefs as jsonb), :perishable, :handover, :hash, :sealed, :status, :shipping, :carrier,
                        :tracking, :from, :by, :capture, :capturedAt, :deliveredAt, :lastExternal, :changedAt, :now,
                        :now, 0, cast(:localDelivery as jsonb), cast(:preorder as jsonb), :windowId,
                        cast(:feeRule as jsonb))
                    """)
                    .param("id", Sql.uuid(s.id())).param("order", Sql.uuid(o.id())).param("seq", s.seq())
                    .param("seller", s.sellerOrgId()).param("sellerName", s.sellerName())
                    .param("type", s.fulfilmentType().name()).param("node", s.nodeId())
                    .param("method", s.deliveryMethod().name()).param("level", s.serviceLevel())
                    .param("pickup", s.pickupLocationId()).param("prefs", json.write(s.preferences()))
                    .param("perishable", s.perishable()).param("handover", s.handoverRequired())
                    .param("hash", s.handoverCodeHash()).param("sealed", s.handoverCodeSealed())
                    .param("status", s.status().name()).param("shipping", s.shippingMinor())
                    .param("carrier", s.carrier()).param("tracking", s.trackingNumber())
                    .param("from", s.promisedFrom()).param("by", s.promisedBy()).param("capture", s.captureId())
                    .param("capturedAt", Sql.ts(s.capturedAt())).param("deliveredAt", Sql.ts(s.deliveredAt()))
                    .param("lastExternal", Sql.ts(s.lastExternalEventAt()))
                    .param("changedAt", Sql.ts(s.statusChangedAt())).param("now", Sql.ts(now))
                    .param("localDelivery", s.localDelivery() == null ? null : json.write(s.localDelivery()))
                    .param("preorder", s.preorder() == null ? null : json.write(s.preorder()))
                    .param("windowId", s.preorder() == null ? null : s.preorder().windowId())
                    .param("feeRule", s.feeRule() == null ? null : json.write(s.feeRule()))
                    .update();
            for (OrderLine l : s.lines()) {
                jdbc.sql("""
                        insert into order_line (id, shipment_id, order_id, line_no, variant_atin, product_atin, title,
                            image_url, quantity, unit_price_minor, tax_minor, discount_minor, category, perishable,
                            tax_jurisdiction, product_type, local_tax_jurisdiction, shipping_tax_minor,
                            seller_org_id, seller_name)
                        values (:id, :shipment, :order, :lineNo, :variant, :product, :title, :image, :qty, :unit, :tax,
                            :discount, :category, :perishable, :jurisdiction, :productType, :localJurisdiction,
                            :shippingTax, :sellerOrg, :sellerName)
                        """)
                        .param("id", Sql.uuid(l.id())).param("shipment", Sql.uuid(s.id()))
                        .param("order", Sql.uuid(o.id())).param("lineNo", l.lineNo())
                        .param("variant", l.variantAtin()).param("product", l.productAtin())
                        .param("title", l.title()).param("image", l.imageUrl()).param("qty", l.quantity())
                        .param("unit", l.unitPriceMinor()).param("tax", l.taxMinor())
                        .param("discount", l.discountMinor()).param("category", l.category())
                        .param("perishable", l.perishable()).param("jurisdiction", l.taxJurisdiction())
                        .param("productType", l.productType()).param("localJurisdiction", l.localTaxJurisdiction())
                        .param("shippingTax", l.shippingTaxMinor())
                        .param("sellerOrg", l.sellerOrgId()).param("sellerName", l.sellerName())
                        .update();
            }
        }
    }

    @Override
    public void update(Order o) {
        Instant now = Instant.now();
        int updated = jdbc.sql("""
                update customer_order set status = :status, cancel_reason = :cancelReason, placed_at = :placedAt,
                    origin = cast(:origin as jsonb), updated_at = :now, version = version + 1
                where id = :id and version = :version
                """)
                .param("origin", json.write(o.origin()))
                .param("status", o.status().name()).param("cancelReason", o.cancelReason())
                .param("placedAt", Sql.ts(o.placedAt())).param("now", Sql.ts(now)).param("id", Sql.uuid(o.id()))
                .param("version", o.version())
                .update();
        if (updated != 1) {
            throw new ConflictException("orders.concurrent-update", "Order " + o.number()
                    + " was changed concurrently; retry");
        }
        for (Shipment s : o.shipments()) {
            jdbc.sql("""
                    update shipment set status = :status, carrier = :carrier, tracking_number = :tracking,
                        capture_id = :capture, captured_at = :capturedAt, delivered_at = :deliveredAt,
                        last_external_event_at = :lastExternal, status_changed_at = :changedAt, updated_at = :now,
                        preorder = cast(:preorder as jsonb), version = version + 1
                    where id = :id
                    """)
                    .param("preorder", s.preorder() == null ? null : json.write(s.preorder()))
                    .param("status", s.status().name()).param("carrier", s.carrier())
                    .param("tracking", s.trackingNumber()).param("capture", s.captureId())
                    .param("capturedAt", Sql.ts(s.capturedAt())).param("deliveredAt", Sql.ts(s.deliveredAt()))
                    .param("lastExternal", Sql.ts(s.lastExternalEventAt()))
                    .param("changedAt", Sql.ts(s.statusChangedAt())).param("now", Sql.ts(now))
                    .param("id", Sql.uuid(s.id()))
                    .update();
        }
    }

    @Override
    public boolean seen(String shipmentId, String eventId) {
        return jdbc.sql("select exists (select 1 from shipment_status_change where shipment_id = :s and event_id = :e)")
                .param("s", Sql.uuid(shipmentId)).param("e", eventId).query(Boolean.class).single();
    }

    @Override
    public void appendHistory(StatusChange c, CourierProof proof) {
        jdbc.sql("""
                insert into shipment_status_change (shipment_id, order_id, event_id, from_status, to_status, actor_type,
                    actor_id, actor_org, reason, source, proof, occurred_at, recorded_at)
                select :shipment, s.order_id, :event, :from, :to, :actorType, :actorId, :actorOrg, :reason, :source,
                    cast(:proof as jsonb), :occurredAt, now()
                from shipment s where s.id = :shipment
                """)
                .param("shipment", Sql.uuid(c.shipmentId())).param("event", c.eventId())
                .param("from", c.from() == null ? null : c.from().name()).param("to", c.to().name())
                .param("actorType", c.actorKind().name()).param("actorId", c.actorId())
                .param("actorOrg", c.actorOrg()).param("reason", c.reason()).param("source", c.source().name())
                .param("proof", json.write(proof)).param("occurredAt", Sql.ts(c.occurredAt()))
                .update();
    }

    // ------------------------------------------------------------------ reads

    @Override
    public Optional<Order> findById(String orderId) {
        return load(List.of(UUID.fromString(orderId)), false).stream().findFirst();
    }

    @Override
    public Optional<Order> findByNumber(String number) {
        return jdbc.sql("select id from customer_order where number = :n").param("n", number)
                .query(UUID.class).optional().flatMap(id -> load(List.of(id), false).stream().findFirst());
    }

    @Override
    public Optional<Order> lockById(String orderId) {
        return load(List.of(UUID.fromString(orderId)), true).stream().findFirst();
    }

    @Override
    public Optional<String> orderIdOfShipment(String shipmentId) {
        return jdbc.sql("select order_id from shipment where id = :id").param("id", Sql.uuid(shipmentId))
                .query(UUID.class).optional().map(UUID::toString);
    }

    @Override
    public Optional<String> shipmentByTracking(String carrier, String trackingNumber) {
        return jdbc.sql("select id from shipment where carrier = :c and tracking_number = :t")
                .param("c", carrier).param("t", trackingNumber).query(UUID.class).optional().map(UUID::toString);
    }

    @Override
    public List<StatusChange> history(String orderId) {
        return jdbc.sql("""
                select shipment_id, event_id, from_status, to_status, actor_type, actor_id, actor_org, reason, source,
                    occurred_at, proof::text as proof
                from shipment_status_change where order_id = :o order by id
                """)
                .param("o", Sql.uuid(orderId))
                .query((rs, n) -> new StatusChange(Sql.str(rs, "shipment_id"), rs.getString("event_id"),
                        rs.getString("from_status") == null ? null : ShipmentStatus.valueOf(rs.getString("from_status")),
                        ShipmentStatus.valueOf(rs.getString("to_status")), ActorKind.valueOf(rs.getString("actor_type")),
                        rs.getString("actor_id"), rs.getString("actor_org"), rs.getString("reason"),
                        TransitionSource.valueOf(rs.getString("source")), Sql.instant(rs, "occurred_at"),
                        rs.getString("proof") == null ? null : json.read(rs.getString("proof"), CourierProof.class)))
                .list();
    }

    @Override
    public Page<Order> byCustomer(String customerId, int offset, int limit) {
        String where = "customer_id = :c and (placed_at is not null or status = 'PENDING_PAYMENT')";
        long total = jdbc.sql("select count(*) from customer_order where " + where).param("c", customerId)
                .query(Long.class).single();
        List<UUID> ids = jdbc.sql("select id from customer_order where " + where
                        + " order by created_at desc, id limit :limit offset :offset")
                .param("c", customerId).param("limit", limit).param("offset", offset).query(UUID.class).list();
        return new Page<>(load(ids, false), offset, limit, total);
    }

    @Override
    public Page<SellerShipment> sellerShipments(String sellerOrgId, ShipmentStatus status, int offset, int limit) {
        String where = " from shipment s join customer_order o on o.id = s.order_id where s.seller_org_id = :org"
                + " and o.placed_at is not null" + (status == null ? "" : " and s.status = :status");
        var count = jdbc.sql("select count(*)" + where).param("org", sellerOrgId);
        var select = jdbc.sql("select s.id as shipment_id, o.id as order_id" + where
                + " order by o.placed_at desc, s.seq limit :limit offset :offset")
                .param("org", sellerOrgId).param("limit", limit).param("offset", offset);
        if (status != null) {
            count = count.param("status", status.name());
            select = select.param("status", status.name());
        }
        long total = count.query(Long.class).single();
        List<UUID[]> rows = select.query((rs, n) -> new UUID[] {rs.getObject("shipment_id", UUID.class),
            rs.getObject("order_id", UUID.class)}).list();
        Map<UUID, Order> byId = new LinkedHashMap<>();
        load(rows.stream().map(r -> r[1]).distinct().toList(), false).forEach(o -> byId.put(UUID.fromString(o.id()), o));
        List<SellerShipment> out = new ArrayList<>();
        for (UUID[] r : rows) {
            Order o = byId.get(r[1]);
            out.add(new SellerShipment(o.shipment(r[0].toString()), o.number(), o.status(), o.shipTo(), o.placedAt(),
                    o.shipments().size()));
        }
        return new Page<>(out, offset, limit, total);
    }

    @Override
    public Page<Order> search(Search q, int offset, int limit) {
        StringBuilder where = new StringBuilder(" from customer_order o where 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (q.number() != null) {
            where.append(" and o.number = :number");
            params.put("number", q.number());
        }
        if (q.customerId() != null) {
            where.append(" and o.customer_id = :customer");
            params.put("customer", q.customerId());
        }
        if (q.status() != null) {
            where.append(" and o.status = :status");
            params.put("status", q.status().name());
        }
        if (q.sellerOrgId() != null || q.shipmentStatus() != null) {
            where.append(" and exists (select 1 from shipment s where s.order_id = o.id");
            if (q.sellerOrgId() != null) {
                where.append(" and s.seller_org_id = :seller");
                params.put("seller", q.sellerOrgId());
            }
            if (q.shipmentStatus() != null) {
                where.append(" and s.status = :shipmentStatus");
                params.put("shipmentStatus", q.shipmentStatus().name());
            }
            where.append(")");
        }
        if (q.from() != null) {
            where.append(" and o.created_at >= :from");
            params.put("from", Sql.ts(q.from()));
        }
        if (q.to() != null) {
            where.append(" and o.created_at < :to");
            params.put("to", Sql.ts(q.to()));
        }
        long total = jdbc.sql("select count(*)" + where).params(params).query(Long.class).single();
        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("limit", limit);
        pageParams.put("offset", offset);
        List<UUID> ids = jdbc.sql("select o.id" + where + " order by o.created_at desc, o.id limit :limit offset :offset")
                .params(pageParams).query(UUID.class).list();
        return new Page<>(load(ids, false), offset, limit, total);
    }

    @Override
    public List<String> preorderShipmentsPastDue(LocalDate today, int limit) {
        return jdbc.sql("""
                select s.id from shipment s join customer_order o on o.id = s.order_id
                where s.fulfilment_type = 'PREORDER' and s.promised_by < :today
                  and s.status not in ('DELIVERED', 'RETURNED', 'CANCELLED', 'PENDING_PAYMENT')
                  and o.placed_at is not null and (s.preorder ->> 'deliveryMissedAt') is null
                order by s.promised_by, s.id limit :limit
                """)
                .param("today", today).param("limit", limit)
                .query(UUID.class).list().stream().map(UUID::toString).toList();
    }

    @Override
    public List<String> preorderShipmentsOfWindow(String windowId) {
        return jdbc.sql("""
                select s.id from shipment s
                where s.fulfilment_type = 'PREORDER' and s.preorder_window_id = :window
                order by s.created_at, s.id
                """)
                .param("window", windowId).query(UUID.class).list().stream().map(UUID::toString).toList();
    }

    @Override
    public List<String> lateAddsAwaitingPayment(int limit) {
        return jdbc.sql("""
                select id from customer_order where channel = 'AGENT_LATE' and status = 'PENDING_PAYMENT'
                order by created_at limit :limit
                """)
                .param("limit", limit).query(UUID.class).list().stream().map(UUID::toString).toList();
    }

    // ------------------------------------------------------------------ loading

    /** Loads orders (keeping the order of {@code ids}) with their shipments and lines. */
    private List<Order> load(Collection<UUID> ids, boolean lock) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Header> headers = new LinkedHashMap<>();
        jdbc.sql("select " + ORDER_COLUMNS + " from customer_order o where o.id in (:ids)" + (lock ? " for update" : ""))
                .param("ids", ids)
                .query((rs, n) -> header(rs)).list()
                .forEach(h -> headers.put(h.id(), h));
        Map<UUID, List<OrderLine>> linesByShipment = new LinkedHashMap<>();
        jdbc.sql("""
                select l.id, l.shipment_id, l.line_no, l.variant_atin, l.product_atin, l.title, l.image_url, l.quantity,
                    l.unit_price_minor, l.tax_minor, l.discount_minor, l.category, l.perishable, l.tax_jurisdiction,
                    l.product_type, l.local_tax_jurisdiction, l.shipping_tax_minor, l.seller_org_id, l.seller_name
                from order_line l where l.order_id in (:ids) order by l.line_no
                """)
                .param("ids", ids)
                .query(rs -> {
                    linesByShipment.computeIfAbsent(rs.getObject("shipment_id", UUID.class), k -> new ArrayList<>())
                            .add(line(rs));
                });
        Map<UUID, List<Shipment>> shipmentsByOrder = new LinkedHashMap<>();
        jdbc.sql("select " + SHIPMENT_COLUMNS + " from shipment s where s.order_id in (:ids) order by s.seq")
                .param("ids", ids)
                .query((rs, n) -> shipmentRow(rs)).list()
                .forEach(row -> shipmentsByOrder.computeIfAbsent(row.orderId(), k -> new ArrayList<>())
                        .add(row.build(linesByShipment.getOrDefault(row.id(), List.of()))));
        List<Order> out = new ArrayList<>();
        for (UUID id : ids) {
            Header h = headers.get(id);
            if (h != null) {
                out.add(h.build(shipmentsByOrder.getOrDefault(id, List.of())));
            }
        }
        return out;
    }

    private record Header(UUID id, String number, String customerId, String currency, OrderStatus status,
                          OrderTotals totals, ShipTo shipTo, String paymentId, String workflowId, String cancelReason,
                          Instant placedAt, Instant createdAt, long version, OrderOrigin origin) {

        Order build(List<Shipment> shipments) {
            return new Order(id.toString(), number, customerId, currency, status, totals, shipTo, paymentId,
                    workflowId, cancelReason, placedAt, createdAt, shipments, version, origin);
        }
    }

    private Header header(ResultSet rs) throws SQLException {
        return new Header(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("customer_id"),
                rs.getString("currency").trim(), OrderStatus.valueOf(rs.getString("status")),
                new OrderTotals(rs.getLong("items_minor"), rs.getLong("shipping_minor"), rs.getLong("tax_minor"),
                        rs.getLong("discount_minor"), rs.getLong("total_minor")),
                json.read(rs.getString("ship_to"), ShipTo.class), rs.getString("payment_id"),
                rs.getString("workflow_id"), rs.getString("cancel_reason"), Sql.instant(rs, "placed_at"),
                Sql.instant(rs, "created_at"), rs.getLong("version"),
                rs.getString("origin") == null ? OrderOrigin.none() : json.read(rs.getString("origin"),
                        OrderOrigin.class));
    }

    private static OrderLine line(ResultSet rs) throws SQLException {
        return new OrderLine(Sql.str(rs, "id"), rs.getInt("line_no"), rs.getString("variant_atin"),
                rs.getString("product_atin"), rs.getString("title"), rs.getString("image_url"), rs.getInt("quantity"),
                rs.getLong("unit_price_minor"), rs.getLong("tax_minor"), rs.getLong("discount_minor"),
                rs.getString("category"), rs.getBoolean("perishable"), rs.getString("tax_jurisdiction"),
                rs.getString("product_type"), rs.getString("local_tax_jurisdiction"), rs.getLong("shipping_tax_minor"),
                rs.getString("seller_org_id"), rs.getString("seller_name"));
    }

    private record ShipmentRow(UUID id, UUID orderId, int seq, String sellerOrgId, String sellerName,
                               FulfilmentType type, String nodeId, DeliveryMethod method, String serviceLevel,
                               String pickupLocationId, DeliveryPreferences preferences, boolean perishable,
                               boolean handoverRequired, String hash, String sealed, ShipmentStatus status,
                               long shippingMinor, String carrier, String trackingNumber, LocalDate promisedFrom,
                               LocalDate promisedBy, String captureId, Instant capturedAt, Instant deliveredAt,
                               Instant lastExternalEventAt, Instant statusChangedAt, long version,
                               LocalDelivery localDelivery, PreorderPackage preorder, FeeRuleSnapshot feeRule) {

        Shipment build(List<OrderLine> lines) {
            return new Shipment(id.toString(), orderId.toString(), seq, sellerOrgId, sellerName, type, nodeId, method,
                    serviceLevel, pickupLocationId, preferences, perishable, handoverRequired, hash, sealed, status,
                    shippingMinor, carrier, trackingNumber, promisedFrom, promisedBy, captureId, capturedAt,
                    deliveredAt, lastExternalEventAt, statusChangedAt, lines, version, localDelivery, preorder, feeRule);
        }
    }

    private ShipmentRow shipmentRow(ResultSet rs) throws SQLException {
        return new ShipmentRow(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getInt("seq"),
                rs.getString("seller_org_id"), rs.getString("seller_name"),
                FulfilmentType.valueOf(rs.getString("fulfilment_type")), rs.getString("node_id"),
                DeliveryMethod.valueOf(rs.getString("delivery_method")), rs.getString("service_level"),
                rs.getString("pickup_location_id"), json.read(rs.getString("preferences"), DeliveryPreferences.class),
                rs.getBoolean("perishable"), rs.getBoolean("handover_required"), rs.getString("handover_code_hash"),
                rs.getString("handover_code_sealed"), ShipmentStatus.valueOf(rs.getString("status")),
                rs.getLong("shipping_minor"), rs.getString("carrier"), rs.getString("tracking_number"),
                Sql.date(rs, "promised_from"), Sql.date(rs, "promised_by"), rs.getString("capture_id"),
                Sql.instant(rs, "captured_at"), Sql.instant(rs, "delivered_at"),
                Sql.instant(rs, "last_external_event_at"), Sql.instant(rs, "status_changed_at"), rs.getLong("version"),
                rs.getString("local_delivery") == null ? null
                        : json.read(rs.getString("local_delivery"), LocalDelivery.class),
                rs.getString("preorder") == null ? null : json.read(rs.getString("preorder"), PreorderPackage.class),
                rs.getString("delivery_fee_rule") == null ? null
                        : json.read(rs.getString("delivery_fee_rule"), FeeRuleSnapshot.class));
    }
}
