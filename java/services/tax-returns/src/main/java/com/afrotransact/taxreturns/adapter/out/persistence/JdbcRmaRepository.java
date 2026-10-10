package com.afrotransact.taxreturns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.taxreturns.domain.returns.DeliveryMethod;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.InspectionOutcome;
import com.afrotransact.taxreturns.domain.returns.ItemCondition;
import com.afrotransact.taxreturns.domain.returns.RefundState;
import com.afrotransact.taxreturns.domain.returns.ReturnLabel;
import com.afrotransact.taxreturns.domain.returns.ReturnMethod;
import com.afrotransact.taxreturns.domain.returns.ReturnReason;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaLine;
import com.afrotransact.taxreturns.domain.returns.RmaStatus;
import com.afrotransact.taxreturns.domain.returns.StockStatus;
import com.afrotransact.taxreturns.port.RmaRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRmaRepository implements RmaRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcRmaRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Rma insert(Rma r, String idempotencyKey) {
        try {
            jdbc.sql("""
                    insert into rma (id, number, order_id, order_number, shipment_id, customer_id, seller_org_id,
                                     fulfilment_type, delivery_method, method, status, currency, estimate_minor,
                                     auto_approved, approval_due_at, refund_at_first_scan, refund_state, return_from,
                                     idempotency_key, created_at, updated_at, version)
                    values (:id, :number, :order, :orderNumber, :shipment, :customer, :seller, :ff, :dm, :method,
                            :status, :currency, :estimate, :auto, :due, :firstScan, :refundState,
                            cast(:returnFrom as jsonb), :key, :createdAt, :updatedAt, 0)
                    """)
                    .param("id", r.id()).param("number", r.number()).param("order", r.orderId())
                    .param("orderNumber", r.orderNumber()).param("shipment", r.shipmentId())
                    .param("customer", r.customerId()).param("seller", r.sellerOrgId())
                    .param("ff", r.fulfilment().name()).param("dm", r.delivery().name())
                    .param("method", r.method().name()).param("status", r.status().name())
                    .param("currency", r.currency()).param("estimate", r.estimateMinor())
                    .param("auto", r.autoApproved()).param("due", Sql.ts(r.approvalDueAt()))
                    .param("firstScan", r.refundAtFirstScan()).param("refundState", r.refundState().name())
                    .param("returnFrom", json.write(r.returnFrom())).param("key", idempotencyKey)
                    .param("createdAt", Sql.ts(r.createdAt())).param("updatedAt", Sql.ts(r.updatedAt()))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("returns.duplicate-request", "This return was already requested");
        }
        writeLines(r);
        return findById(r.id()).orElseThrow();
    }

    @Override
    public Rma update(Rma r) {
        int rows = jdbc.sql("""
                update rma set status = :status, auto_approved = :auto, approval_due_at = :due,
                       refund_state = :refundState, label_id = :labelId, carrier = :carrier,
                       tracking_number = :tracking, label_url = :labelUrl, qr_payload = :qr,
                       handover_code = :code, drop_off_by = :dropOffBy, decision_note = :note,
                       updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", r.status().name()).param("auto", r.autoApproved())
                .param("due", Sql.ts(r.approvalDueAt())).param("refundState", r.refundState().name())
                .param("labelId", r.label() == null ? null : r.label().labelId())
                .param("carrier", r.label() == null ? null : r.label().carrier())
                .param("tracking", r.label() == null ? null : r.label().trackingNumber())
                .param("labelUrl", r.label() == null ? null : r.label().documentUrl())
                .param("qr", r.qrPayload()).param("code", r.handoverCode())
                .param("dropOffBy", Sql.ts(r.dropOffBy())).param("note", r.decisionNote())
                .param("updatedAt", Sql.ts(r.updatedAt())).param("id", r.id()).param("version", r.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("returns.concurrent-update", "Return changed; reload and retry");
        }
        jdbc.sql("delete from rma_line where rma_id = :id").param("id", r.id()).update();
        writeLines(r);
        return findById(r.id()).orElseThrow();
    }

    private void writeLines(Rma r) {
        int pos = 0;
        for (RmaLine l : r.lines()) {
            jdbc.sql("""
                    insert into rma_line (rma_id, line_id, position, atin, title, quantity, unit_price_minor, reason,
                                          comment, evidence, outcome, item_condition, stock_status, inspection_note)
                    values (:rma, :line, :pos, :atin, :title, :qty, :unit, :reason, :comment,
                            cast(:evidence as jsonb), :outcome, :cond, :stock, :note)
                    """)
                    .param("rma", r.id()).param("line", l.lineId()).param("pos", pos++).param("atin", l.atin())
                    .param("title", l.title()).param("qty", l.quantity()).param("unit", l.unitPriceMinor())
                    .param("reason", l.reason().name()).param("comment", l.comment())
                    .param("evidence", json.write(l.evidenceAssetIds()))
                    .param("outcome", l.outcome() == null ? null : l.outcome().name())
                    .param("cond", l.condition() == null ? null : l.condition().name())
                    .param("stock", l.stockStatus().name()).param("note", l.inspectionNote())
                    .update();
        }
    }

    @Override
    public Optional<Rma> findById(UUID id) {
        return one("select * from rma where id = :v", id);
    }

    @Override
    public Optional<Rma> findByNumber(String number) {
        return one("select * from rma where number = :v", number);
    }

    @Override
    public Optional<Rma> findByIdempotencyKey(String customerId, String idempotencyKey) {
        return jdbc.sql("select * from rma where customer_id = :c and idempotency_key = :k")
                .param("c", customerId).param("k", idempotencyKey).query(this::map).optional();
    }

    @Override
    public Optional<Rma> findByTracking(String carrier, String trackingNumber) {
        return jdbc.sql("select * from rma where carrier = :c and tracking_number = :t")
                .param("c", carrier).param("t", trackingNumber).query(this::map).optional();
    }

    @Override
    public List<Rma> search(Filter f) {
        StringBuilder sql = new StringBuilder("select * from rma where true");
        Map<String, Object> params = new HashMap<>();
        if (f.customerId() != null) {
            sql.append(" and customer_id = :customer");
            params.put("customer", f.customerId());
        }
        if (f.sellerOrgId() != null) {
            sql.append(" and seller_org_id = :seller");
            params.put("seller", f.sellerOrgId());
        }
        if (f.orderId() != null) {
            sql.append(" and order_id = :order");
            params.put("order", f.orderId());
        }
        if (f.status() != null) {
            sql.append(" and status = :status");
            params.put("status", f.status().name());
        }
        sql.append(" order by created_at desc limit :limit");
        params.put("limit", f.limit());
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    @Override
    public Map<String, Integer> openQuantities(UUID orderId) {
        Map<String, Integer> out = new HashMap<>();
        jdbc.sql("""
                select l.line_id, sum(l.quantity) as qty from rma_line l join rma r on r.id = l.rma_id
                where r.order_id = :order and r.status not in ('REJECTED', 'CANCELLED')
                group by l.line_id
                """).param("order", orderId)
                .query(rs -> {
                    out.put(rs.getString("line_id"), rs.getInt("qty"));
                });
        return out;
    }

    @Override
    public List<Rma> awaitingSellerBefore(Instant now, int limit) {
        return jdbc.sql("""
                select * from rma where status = 'REQUESTED' and approval_due_at is not null and approval_due_at < :now
                order by approval_due_at limit :limit
                """).param("now", Sql.ts(now)).param("limit", limit).query(this::map).list();
    }

    @Override
    public List<Rma> approvedWithoutLabel(int limit) {
        return jdbc.sql("""
                select * from rma where status in ('APPROVED', 'AUTO_APPROVED')
                  and (method <> 'NO_RETURN' or refund_state = 'NONE')
                order by updated_at limit :limit
                """).param("limit", limit).query(this::map).list();
    }

    @Override
    public List<Rma> awaitingRefund(int limit) {
        return jdbc.sql("""
                select * from rma r where r.refund_state = 'PENDING'
                  and not exists (select 1 from refund f where f.rma_id = r.id)
                order by r.updated_at limit :limit
                """).param("limit", limit).query(this::map).list();
    }

    @Override
    public List<Rma> withPendingStock(int limit) {
        return jdbc.sql("""
                select * from rma r where exists (select 1 from rma_line l where l.rma_id = r.id
                                                    and l.stock_status = 'PENDING')
                order by r.updated_at limit :limit
                """).param("limit", limit).query(this::map).list();
    }

    @Override
    public void recordTransition(UUID rmaId, RmaStatus from, RmaStatus to, String actor, String note, Instant at) {
        jdbc.sql("""
                insert into rma_status_change (rma_id, from_status, to_status, actor, note, occurred_at)
                values (:rma, :from, :to, :actor, :note, :at)
                """)
                .param("rma", rmaId).param("from", from == null ? null : from.name()).param("to", to.name())
                .param("actor", actor).param("note", note == null || note.length() <= 1200 ? note
                        : note.substring(0, 1200))
                .param("at", Sql.ts(at)).update();
    }

    private Optional<Rma> one(String sql, Object value) {
        return jdbc.sql(sql).param("v", value).query(this::map).optional();
    }

    @Override
    public Optional<String> pickupId(UUID rmaId) {
        return jdbc.sql("select pickup_id from rma_pickup where rma_id = :id and pickup_id is not null")
                .param("id", rmaId).query(String.class).optional();
    }

    @Override
    public void recordPickup(UUID rmaId, String pickupId, String status, String error, Instant at) {
        jdbc.sql("""
                insert into rma_pickup (rma_id, pickup_id, status, attempts, last_error, updated_at)
                values (:id, :pickup, :status, case when :status = 'FAILED' then 1 else 0 end, :error, :at)
                on conflict (rma_id) do update set pickup_id = coalesce(excluded.pickup_id, rma_pickup.pickup_id),
                    status = excluded.status, last_error = excluded.last_error, updated_at = excluded.updated_at,
                    attempts = rma_pickup.attempts + case when excluded.status = 'FAILED' then 1 else 0 end
                """).param("id", rmaId).param("pickup", pickupId).param("status", status)
                .param("error", error == null ? null : error.length() > 500 ? error.substring(0, 500) : error)
                .param("at", Sql.ts(at)).update();
    }

    @Override
    public List<Rma> pickupsToSchedule(int limit) {
        return jdbc.sql("""
                select r.* from rma r left join rma_pickup p on p.rma_id = r.id
                where r.method = 'DRIVER_PICKUP' and r.status = 'LABEL_ISSUED'
                  and (p.rma_id is null or (p.status = 'FAILED' and p.attempts < 20))
                order by r.updated_at limit :limit
                """).param("limit", limit).query(this::map).list();
    }

    private Rma map(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String labelId = rs.getString("label_id");
        ReturnLabel label = labelId == null && rs.getString("tracking_number") == null ? null
                : new ReturnLabel(labelId, rs.getString("carrier"), rs.getString("tracking_number"),
                rs.getString("label_url"));
        return new Rma(id, rs.getString("number"), rs.getObject("order_id", UUID.class),
                rs.getString("order_number"), rs.getObject("shipment_id", UUID.class), rs.getString("customer_id"),
                rs.getString("seller_org_id"), FulfilmentType.valueOf(rs.getString("fulfilment_type")),
                DeliveryMethod.valueOf(rs.getString("delivery_method")),
                ReturnMethod.valueOf(rs.getString("method")), RmaStatus.valueOf(rs.getString("status")),
                lines(id), rs.getString("currency"), rs.getLong("estimate_minor"), rs.getBoolean("auto_approved"),
                Sql.instant(rs, "approval_due_at"), rs.getBoolean("refund_at_first_scan"),
                RefundState.valueOf(rs.getString("refund_state")), label, rs.getString("qr_payload"),
                rs.getString("handover_code"), json.address(rs.getString("return_from")),
                Sql.instant(rs, "drop_off_by"), rs.getString("decision_note"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }

    private List<RmaLine> lines(UUID rmaId) {
        List<RmaLine> out = new ArrayList<>();
        jdbc.sql("select * from rma_line where rma_id = :id order by position").param("id", rmaId)
                .query(rs -> {
                    out.add(new RmaLine(rs.getString("line_id"), rs.getString("atin"), rs.getString("title"),
                            rs.getInt("quantity"), rs.getLong("unit_price_minor"),
                            ReturnReason.valueOf(rs.getString("reason")), rs.getString("comment"),
                            json.strings(rs.getString("evidence")),
                            Sql.enumOf(InspectionOutcome.class, rs.getString("outcome")),
                            Sql.enumOf(ItemCondition.class, rs.getString("item_condition")),
                            StockStatus.valueOf(rs.getString("stock_status")), rs.getString("inspection_note")));
                });
        return out;
    }
}
