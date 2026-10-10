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
import com.afrotransact.taxreturns.domain.returns.Refund;
import com.afrotransact.taxreturns.domain.returns.RefundKind;
import com.afrotransact.taxreturns.domain.returns.RefundLineAmount;
import com.afrotransact.taxreturns.domain.returns.RefundStatus;
import com.afrotransact.taxreturns.domain.returns.RefundedTotals;
import com.afrotransact.taxreturns.port.RefundRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRefundRepository implements RefundRepository {

    private final JdbcClient jdbc;

    JdbcRefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Refund insertOrGet(Refund r) {
        int rows = jdbc.sql("""
                insert into refund (id, kind, rma_id, order_id, payment_id, currency, status, reason, request_key,
                                    payments_refund_id, failure, attempts, items_minor, shipping_minor, tax_minor,
                                    fee_minor, total_minor, created_at, issued_at, version)
                values (:id, :kind, :rma, :order, :payment, :currency, :status, :reason, :key, :paymentsRefundId,
                        :failure, :attempts, :items, :shipping, :tax, :fee, :total, :createdAt, :issuedAt, 0)
                on conflict (request_key) do nothing
                """)
                .param("id", r.id()).param("kind", r.kind().name()).param("rma", r.rmaId())
                .param("order", r.orderId()).param("payment", r.paymentId()).param("currency", r.currency())
                .param("status", r.status().name()).param("reason", r.reason()).param("key", r.requestKey())
                .param("paymentsRefundId", r.paymentsRefundId()).param("failure", r.failure())
                .param("attempts", r.attempts()).param("items", r.breakdown().itemsMinor())
                .param("shipping", r.breakdown().shippingMinor()).param("tax", r.breakdown().taxMinor())
                .param("fee", r.breakdown().restockingFeeMinor()).param("total", r.breakdown().totalMinor())
                .param("createdAt", Sql.ts(r.createdAt())).param("issuedAt", Sql.ts(r.issuedAt()))
                .update();
        if (rows == 0) {
            return findByRequestKey(r.requestKey()).orElseThrow();
        }
        int pos = 0;
        for (RefundLineAmount l : r.lines()) {
            jdbc.sql("""
                    insert into refund_line (refund_id, line_id, position, quantity, items_minor, shipping_minor,
                                             tax_minor, restocking_fee_minor)
                    values (:refund, :line, :pos, :qty, :items, :shipping, :tax, :fee)
                    """)
                    .param("refund", r.id()).param("line", l.lineId()).param("pos", pos++)
                    .param("qty", l.quantity()).param("items", l.itemsMinor()).param("shipping", l.shippingMinor())
                    .param("tax", l.taxMinor()).param("fee", l.restockingFeeMinor())
                    .update();
        }
        return findById(r.id()).orElseThrow();
    }

    @Override
    public Refund update(Refund r) {
        int rows = jdbc.sql("""
                update refund set status = :status, payments_refund_id = :paymentsRefundId, failure = :failure,
                       attempts = :attempts, issued_at = :issuedAt, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", r.status().name()).param("paymentsRefundId", r.paymentsRefundId())
                .param("failure", r.failure() == null || r.failure().length() <= 1000 ? r.failure()
                        : r.failure().substring(0, 1000))
                .param("attempts", r.attempts()).param("issuedAt", Sql.ts(r.issuedAt()))
                .param("id", r.id()).param("version", r.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("returns.concurrent-update", "Refund changed; reload and retry");
        }
        return findById(r.id()).orElseThrow();
    }

    @Override
    public Optional<Refund> findById(UUID id) {
        return jdbc.sql("select * from refund where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Refund> findByRequestKey(String requestKey) {
        return jdbc.sql("select * from refund where request_key = :k").param("k", requestKey).query(this::map)
                .optional();
    }

    @Override
    public List<Refund> findByOrder(UUID orderId) {
        return jdbc.sql("select * from refund where order_id = :o order by created_at").param("o", orderId)
                .query(this::map).list();
    }

    @Override
    public List<Refund> findByRma(UUID rmaId) {
        return jdbc.sql("select * from refund where rma_id = :r order by created_at").param("r", rmaId)
                .query(this::map).list();
    }

    @Override
    public List<Refund> pendingBefore(Instant before, int limit) {
        return jdbc.sql("""
                select * from refund where status = 'PENDING' and created_at < :before order by created_at limit :limit
                """).param("before", Sql.ts(before)).param("limit", limit).query(this::map).list();
    }

    @Override
    public Map<String, RefundedTotals> refundedTotals(UUID orderId) {
        Map<String, RefundedTotals> out = new HashMap<>();
        jdbc.sql("""
                select l.line_id, sum(l.quantity) as qty, sum(l.items_minor + l.restocking_fee_minor) as items,
                       sum(l.shipping_minor) as shipping, sum(l.tax_minor) as tax
                from refund_line l join refund r on r.id = l.refund_id
                where r.order_id = :order and r.status <> 'FAILED'
                group by l.line_id
                """).param("order", orderId)
                .query(rs -> {
                    out.put(rs.getString("line_id"), new RefundedTotals(rs.getInt("qty"), rs.getLong("items"),
                            rs.getLong("shipping"), rs.getLong("tax")));
                });
        return out;
    }

    private Refund map(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<RefundLineAmount> lines = new ArrayList<>();
        jdbc.sql("select * from refund_line where refund_id = :id order by position").param("id", id)
                .query(l -> {
                    lines.add(new RefundLineAmount(l.getString("line_id"), l.getInt("quantity"),
                            l.getLong("items_minor"), l.getLong("shipping_minor"), l.getLong("tax_minor"),
                            l.getLong("restocking_fee_minor")));
                });
        return new Refund(id, RefundKind.valueOf(rs.getString("kind")), rs.getObject("rma_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getString("currency"), RefundStatus.valueOf(rs.getString("status")), lines,
                rs.getString("reason"), rs.getString("request_key"), rs.getString("payments_refund_id"),
                rs.getString("failure"), rs.getInt("attempts"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "issued_at"), rs.getLong("version"));
    }
}
