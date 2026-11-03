package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AttributionChannel;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderOrderStatus;
import com.afrotransact.deliveryruns.domain.OrderRefund;
import com.afrotransact.deliveryruns.domain.PreorderPackage;
import com.afrotransact.deliveryruns.port.AttributedOrders;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.domain.RefundStatus;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcPreorderOrderRepository implements PreorderOrderRepository {

    private static final TypeReference<List<PreorderPackage>> PACKAGES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcPreorderOrderRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public PreorderOrder insert(PreorderOrder o) {
        try {
            bind(jdbc.sql("""
                    insert into preorder_order (order_id, number, window_id, customer_id, customer_name, phone, email,
                        address_line, city, region, zip, delivery_note, placed_at, channel, tracking_tag, packages,
                        fulfilment, currency, status, note, total_minor, units, created_at, updated_at, version)
                    values (:orderId, :number, :windowId, :customerId, :customerName, :phone, :email, :line, :city,
                        :region, :zip, :deliveryNote, :placedAt, :channel, :trackingTag, cast(:packages as jsonb),
                        :fulfilment, :currency, :status, :note, :total, :units, :createdAt, :updatedAt, 0)
                    """), o).param("number", o.number()).param("windowId", o.windowId())
                    .param("placedAt", Sql.ts(o.placedAt())).param("createdAt", Sql.ts(o.createdAt())).update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEntryException("Order " + o.orderId() + " is already projected", e);
        }
        return findById(o.orderId()).orElseThrow();
    }

    @Override
    public PreorderOrder update(PreorderOrder o) {
        int rows = bind(jdbc.sql("""
                update preorder_order set customer_id = :customerId, customer_name = :customerName, phone = :phone,
                    email = :email, address_line = :line, city = :city, region = :region, zip = :zip,
                    delivery_note = :deliveryNote, channel = :channel, tracking_tag = :trackingTag,
                    packages = cast(:packages as jsonb), fulfilment = :fulfilment, currency = :currency,
                    status = :status, note = :note, total_minor = :total, units = :units, updated_at = :updatedAt,
                    version = version + 1
                where order_id = :orderId and version = :version
                """), o).param("version", o.version()).update();
        if (rows == 0) {
            throw new ConcurrentUpdateException("Pre-order " + o.orderId() + " changed; retry");
        }
        return findById(o.orderId()).orElseThrow();
    }

    @Override
    public Optional<PreorderOrder> findById(String orderId) {
        return jdbc.sql("select * from preorder_order where order_id = :id").param("id", orderId).query(this::map)
                .optional();
    }

    @Override
    public List<PreorderOrder> findByIds(Collection<String> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select * from preorder_order where order_id in (:ids)").param("ids", orderIds)
                .query(this::map).list();
    }

    @Override
    public Optional<PreorderOrder> findByShipment(String shipmentId) {
        return jdbc.sql("select * from preorder_order where packages @> cast(:probe as jsonb) limit 1")
                .param("probe", json.write(List.of(Map.of("shipmentId", shipmentId)))).query(this::map).optional();
    }

    @Override
    public List<PreorderOrder> findByWindow(UUID windowId) {
        return jdbc.sql("select * from preorder_order where window_id = :w order by placed_at desc, number desc")
                .param("w", windowId).query(this::map).list();
    }

    @Override
    public Map<UUID, WindowTotals> totals(Collection<UUID> windowIds) {
        Map<UUID, WindowTotals> out = new HashMap<>();
        if (windowIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select window_id, count(*) as n, coalesce(sum(total_minor), 0) as revenue from preorder_order
                where window_id in (:ids) and status <> 'CANCELLED' group by window_id
                """).param("ids", List.copyOf(windowIds)).query((rs, n) -> {
                    out.put(rs.getObject("window_id", UUID.class),
                            new WindowTotals(rs.getInt("n"), rs.getLong("revenue")));
                    return null;
                }).list();
        return out;
    }

    @Override
    public Map<String, AttributedOrders> attributedOrders(Collection<String> creatives) {
        Map<String, AttributedOrders> out = new HashMap<>();
        if (creatives.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select substring(tracking_tag from 'cr=([A-Za-z0-9_-]+)') as creative,
                       count(*) filter (where status <> 'CANCELLED') as live,
                       count(*) filter (where status = 'CANCELLED') as cancelled
                from preorder_order
                where tracking_tag like '%cr=%'
                  and substring(tracking_tag from 'cr=([A-Za-z0-9_-]+)') in (:creatives)
                group by 1
                """).param("creatives", List.copyOf(creatives)).query((rs, n) -> {
                    out.put(rs.getString("creative"), new AttributedOrders(rs.getInt("live"), rs.getInt("cancelled")));
                    return null;
                }).list();
        return out;
    }

    @Override
    public boolean recordRefund(String orderId, RefundStatus status, Long amountMinor, String error, Instant at) {
        // A later report never moves a settled refund back to PENDING or FAILED (events can arrive out of order).
        return jdbc.sql("""
                update preorder_order set refund_status = :status, refund_amount_minor = coalesce(:amount, refund_amount_minor),
                    refund_error = :error, refund_updated_at = :at, updated_at = :at
                where order_id = :id and (refund_status is null or refund_status not in ('REFUNDED', 'VOIDED')
                    or :status in ('REFUNDED', 'VOIDED'))
                """).param("status", status.name()).param("amount", amountMinor).param("error", error)
                .param("at", Sql.ts(at)).param("id", orderId).update() > 0;
    }

    @Override
    public Map<RefundStatus, Integer> refundCounts(UUID windowId) {
        Map<RefundStatus, Integer> out = new EnumMap<>(RefundStatus.class);
        jdbc.sql("""
                select refund_status, count(*) as n from preorder_order
                where window_id = :w and refund_status is not null group by refund_status
                """).param("w", windowId).query((rs, n) -> {
                    out.put(RefundStatus.valueOf(rs.getString("refund_status")), rs.getInt("n"));
                    return null;
                }).list();
        return out;
    }

    @Override
    public int deleteDeliveredBefore(LocalDate deliveredBefore) {
        return jdbc.sql("""
                delete from preorder_order o using preorder_window w
                where o.window_id = w.id and w.delivers_on < :before
                """).param("before", deliveredBefore).update();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, PreorderOrder o) {
        return spec.param("orderId", o.orderId())
                .param("customerId", o.customerId())
                .param("customerName", o.customerName())
                .param("phone", o.phone())
                .param("email", o.email())
                .param("line", o.address().line())
                .param("city", o.address().city())
                .param("region", o.address().region())
                .param("zip", o.address().zip())
                .param("deliveryNote", o.deliveryNote())
                .param("channel", o.channel().name())
                .param("trackingTag", o.trackingTag())
                .param("packages", json.write(o.packages()))
                .param("fulfilment", o.fulfilment().name())
                .param("currency", o.currency())
                .param("status", o.status().name())
                .param("note", o.note())
                .param("total", o.totalMinor())
                .param("units", o.units())
                .param("updatedAt", Sql.ts(o.updatedAt()));
    }

    private static OrderRefund refund(ResultSet rs) throws SQLException {
        String status = rs.getString("refund_status");
        if (status == null) {
            return null;
        }
        long amount = rs.getLong("refund_amount_minor");
        Long amountMinor = rs.wasNull() ? null : amount;
        return new OrderRefund(RefundStatus.valueOf(status), amountMinor, Sql.instant(rs, "refund_updated_at"),
                rs.getString("refund_error"));
    }

    private PreorderOrder map(ResultSet rs, int rowNum) throws SQLException {
        return new PreorderOrder(rs.getString("order_id"), rs.getString("number"),
                rs.getObject("window_id", UUID.class), rs.getString("customer_id"), rs.getString("customer_name"),
                rs.getString("phone"), rs.getString("email"),
                new PreorderOrder.Address(rs.getString("address_line"), rs.getString("city"), rs.getString("region"),
                        rs.getString("zip")),
                rs.getString("delivery_note"), Sql.instant(rs, "placed_at"),
                AttributionChannel.valueOf(rs.getString("channel")), rs.getString("tracking_tag"),
                json.read(rs.getString("packages"), PACKAGES), PreorderFulfilment.valueOf(rs.getString("fulfilment")),
                rs.getString("currency"), PreorderOrderStatus.valueOf(rs.getString("status")), rs.getString("note"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"), refund(rs));
    }
}
