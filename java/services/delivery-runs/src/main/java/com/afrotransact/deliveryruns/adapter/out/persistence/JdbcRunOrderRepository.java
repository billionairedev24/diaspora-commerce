package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.DeliveryKind;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderMoney;
import com.afrotransact.deliveryruns.domain.RunOrderStage;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunOrderRepository implements RunOrderRepository {

    private static final String ACTIVE = "status in ('RESERVED', 'CONFIRMED')";

    private final JdbcClient jdbc;

    JdbcRunOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RunOrder insert(RunOrder o) {
        try {
            jdbc.sql("""
                    insert into run_order (id, run_id, order_id, customer_id, packages, status, handover_code,
                        requested_run_id, reserved_at, updated_at, version, fee_minor, total_minor, delivery_kind,
                        commission_minor, refunded_minor, refunded_fee_minor, refunded_at)
                    values (:id, :runId, :orderId, :customerId, :packages, :status, :code, :requested, :reservedAt,
                        :updatedAt, 0, :fee, :total, :kind, :commission, :refunded, :refundedFee, :refundedAt)
                    """)
                    .params(moneyParams(o.money()))
                    .param("id", o.id())
                    .param("runId", o.runId())
                    .param("orderId", o.orderId())
                    .param("customerId", o.customerId())
                    .param("packages", o.packages())
                    .param("status", o.status().name())
                    .param("code", o.handoverCode())
                    .param("requested", o.requestedRunId())
                    .param("reservedAt", Sql.ts(o.reservedAt()))
                    .param("updatedAt", Sql.ts(o.updatedAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEntryException("Order " + o.orderId() + " already holds a run slot", e);
        }
        return findById(o.id());
    }

    @Override
    public RunOrder update(RunOrder o) {
        int rows = jdbc.sql("""
                update run_order set status = :status, handover_code = :code, updated_at = :updatedAt,
                    stage = :stage, stage_at = :stageAt, delivered_at = :deliveredAt, version = version + 1,
                    fee_minor = :fee, total_minor = :total, delivery_kind = :kind, commission_minor = :commission,
                    refunded_minor = :refunded, refunded_fee_minor = :refundedFee, refunded_at = :refundedAt
                where id = :id and version = :version
                """)
                .params(moneyParams(o.money()))
                .param("status", o.status().name())
                .param("code", o.handoverCode())
                .param("stage", o.stage().name())
                .param("stageAt", Sql.ts(o.stageAt()))
                .param("deliveredAt", Sql.ts(o.deliveredAt()))
                .param("updatedAt", Sql.ts(o.updatedAt()))
                .param("id", o.id())
                .param("version", o.version())
                .update();
        if (rows == 0) {
            throw new ConcurrentUpdateException("Run slot changed; reload and retry");
        }
        return findById(o.id());
    }

    @Override
    public Optional<RunOrder> findActiveByOrderId(String orderId) {
        return jdbc.sql("select * from run_order where order_id = :orderId and " + ACTIVE)
                .param("orderId", orderId).query(JdbcRunOrderRepository::map).optional();
    }

    @Override
    public Optional<RunOrder> findLatest(UUID runId, String orderId) {
        return jdbc.sql("select * from run_order where run_id = :runId and order_id = :orderId "
                        + "order by updated_at desc limit 1")
                .param("runId", runId).param("orderId", orderId).query(JdbcRunOrderRepository::map).optional();
    }

    @Override
    public List<RunOrder> findByRun(UUID runId, RunOrderStatus status) {
        if (status == null) {
            return jdbc.sql("select * from run_order where run_id = :runId order by reserved_at, id")
                    .param("runId", runId).query(JdbcRunOrderRepository::map).list();
        }
        return jdbc.sql("select * from run_order where run_id = :runId and status = :status order by reserved_at, id")
                .param("runId", runId).param("status", status.name()).query(JdbcRunOrderRepository::map).list();
    }

    @Override
    public List<RunOrder> findByRuns(Collection<UUID> runIds) {
        if (runIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select * from run_order where run_id in (:ids) order by reserved_at, id")
                .param("ids", runIds).query(JdbcRunOrderRepository::map).list();
    }

    @Override
    public Optional<RunOrder> findCurrentByOrderId(String orderId) {
        return jdbc.sql("select * from run_order where order_id = :orderId order by (" + ACTIVE
                        + ") desc, updated_at desc limit 1")
                .param("orderId", orderId).query(JdbcRunOrderRepository::map).optional();
    }

    @Override
    public List<RunOrder> findActiveByRun(UUID runId) {
        return jdbc.sql("select * from run_order where run_id = :runId and " + ACTIVE + " order by reserved_at, id")
                .param("runId", runId).query(JdbcRunOrderRepository::map).list();
    }

    private RunOrder findById(UUID id) {
        return jdbc.sql("select * from run_order where id = :id").param("id", id).query(JdbcRunOrderRepository::map)
                .single();
    }

    private static Map<String, Object> moneyParams(RunOrderMoney m) {
        Map<String, Object> p = new HashMap<>();
        p.put("fee", m.feeMinor());
        p.put("total", m.totalMinor());
        p.put("kind", m.kind() == null ? null : m.kind().name());
        p.put("commission", m.commissionMinor());
        p.put("refunded", m.refundedMinor());
        p.put("refundedFee", m.refundedFeeMinor());
        p.put("refundedAt", Sql.ts(m.refundedAt()));
        return p;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static RunOrder map(ResultSet rs, int rowNum) throws SQLException {
        return new RunOrder(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getString("order_id"), rs.getString("customer_id"), rs.getInt("packages"),
                RunOrderStatus.valueOf(rs.getString("status")), rs.getString("handover_code"),
                rs.getObject("requested_run_id", UUID.class), Sql.instant(rs, "reserved_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"), RunOrderStage.valueOf(rs.getString("stage")),
                Sql.instant(rs, "stage_at"), Sql.instant(rs, "delivered_at"),
                new RunOrderMoney(nullableLong(rs, "fee_minor"), nullableLong(rs, "total_minor"),
                        rs.getString("delivery_kind") == null ? null : DeliveryKind.valueOf(rs.getString("delivery_kind")),
                        nullableLong(rs, "commission_minor"), rs.getLong("refunded_minor"),
                        rs.getLong("refunded_fee_minor"), Sql.instant(rs, "refunded_at")));
    }
}
