package com.afrotransact.fulfilment.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.fulfilment.domain.FulfilmentTask;
import com.afrotransact.fulfilment.domain.TaskStatus;
import com.afrotransact.fulfilment.port.TaskRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTaskRepository implements TaskRepository {

    private final JdbcClient jdbc;
    private final Json json;

    JdbcTaskRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean insert(FulfilmentTask t) {
        return jdbc.sql("""
                insert into fc_task (id, shipment_id, order_id, order_number, fulfilment_type, delivery_method,
                    node_id, status, lines, package_label, updated_at)
                values (:id, :shipment, :order, :number, :type, :method, :node, :status, cast(:lines as jsonb),
                    :label, :at)
                on conflict (shipment_id) do nothing
                """)
                .param("id", UUID.fromString(t.id())).param("shipment", t.shipmentId()).param("order", t.orderId())
                .param("number", t.orderNumber()).param("type", t.fulfilmentType())
                .param("method", t.deliveryMethod()).param("node", t.nodeId()).param("status", t.status().name())
                .param("lines", json.write(t.lines())).param("label", t.packageLabel())
                .param("at", Rows.ts(t.updatedAt()))
                .update() == 1;
    }

    @Override
    public Optional<FulfilmentTask> lock(String id) {
        return byId(id, " for update");
    }

    @Override
    public Optional<FulfilmentTask> find(String id) {
        return byId(id, "");
    }

    private Optional<FulfilmentTask> byId(String id, String suffix) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return jdbc.sql("select * from fc_task where id = :id" + suffix).param("id", uuid).query(this::map)
                .optional();
    }

    @Override
    public Optional<FulfilmentTask> findByShipment(String shipmentId) {
        return jdbc.sql("select * from fc_task where shipment_id = :s").param("s", shipmentId).query(this::map)
                .optional();
    }

    @Override
    public void update(FulfilmentTask t) {
        int n = jdbc.sql("""
                update fc_task set node_id = :node, status = :status, lines = cast(:lines as jsonb),
                    package_label = :label, weight_lb = :weight, carrier = :carrier, tracking_number = :tracking,
                    exception_reason = :reason, operator = :operator, updated_at = :at, version = version + 1
                where id = :id and version = :version
                """)
                .param("node", t.nodeId()).param("status", t.status().name()).param("lines", json.write(t.lines()))
                .param("label", t.packageLabel()).param("weight", t.weightLb()).param("carrier", t.carrier())
                .param("tracking", t.trackingNumber()).param("reason", t.exceptionReason())
                .param("operator", t.operator()).param("at", Rows.ts(t.updatedAt()))
                .param("id", UUID.fromString(t.id())).param("version", t.version())
                .update();
        if (n != 1) {
            throw new OptimisticLockingFailureException("Task " + t.id() + " was changed concurrently");
        }
    }

    @Override
    public List<FulfilmentTask> list(String nodeId, TaskStatus status, int limit) {
        return jdbc.sql("""
                select * from fc_task
                where (cast(:node as varchar) is null or node_id = :node)
                  and (cast(:status as varchar) is null or status = :status)
                order by created_at limit :limit
                """)
                .param("node", nodeId).param("status", status == null ? null : status.name())
                .param("limit", limit).query(this::map).list();
    }

    private FulfilmentTask map(ResultSet rs, int n) throws SQLException {
        return new FulfilmentTask(rs.getString("id"), rs.getString("shipment_id"), rs.getString("order_id"),
                rs.getString("order_number"), rs.getString("fulfilment_type"), rs.getString("delivery_method"),
                rs.getString("node_id"), TaskStatus.valueOf(rs.getString("status")), json.lines(rs.getString("lines")),
                rs.getString("package_label"), Rows.dbl(rs, "weight_lb"), rs.getString("carrier"),
                rs.getString("tracking_number"), rs.getString("exception_reason"), rs.getString("operator"),
                Rows.instant(rs, "updated_at"), rs.getInt("version"));
    }
}
