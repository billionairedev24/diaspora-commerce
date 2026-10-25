package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.RunOrder;
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
                        requested_run_id, reserved_at, updated_at, version)
                    values (:id, :runId, :orderId, :customerId, :packages, :status, :code, :requested, :reservedAt,
                        :updatedAt, 0)
                    """)
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
                    stage = :stage, stage_at = :stageAt, delivered_at = :deliveredAt, version = version + 1
                where id = :id and version = :version
                """)
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
    public List<RunOrder> findActiveByRun(UUID runId) {
        return jdbc.sql("select * from run_order where run_id = :runId and " + ACTIVE + " order by reserved_at, id")
                .param("runId", runId).query(JdbcRunOrderRepository::map).list();
    }

    private RunOrder findById(UUID id) {
        return jdbc.sql("select * from run_order where id = :id").param("id", id).query(JdbcRunOrderRepository::map)
                .single();
    }

    private static RunOrder map(ResultSet rs, int rowNum) throws SQLException {
        return new RunOrder(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getString("order_id"), rs.getString("customer_id"), rs.getInt("packages"),
                RunOrderStatus.valueOf(rs.getString("status")), rs.getString("handover_code"),
                rs.getObject("requested_run_id", UUID.class), Sql.instant(rs, "reserved_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"), RunOrderStage.valueOf(rs.getString("stage")),
                Sql.instant(rs, "stage_at"), Sql.instant(rs, "delivered_at"));
    }
}
