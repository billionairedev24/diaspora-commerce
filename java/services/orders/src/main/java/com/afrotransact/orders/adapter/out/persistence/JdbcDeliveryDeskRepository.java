package com.afrotransact.orders.adapter.out.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.orders.domain.DeliveryJob;
import com.afrotransact.orders.domain.DeliveryProofRecord;
import com.afrotransact.orders.port.DeliveryDeskRepository;
import com.afrotransact.orders.port.Page;
import tools.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** delivery_job, delivery_proof, delivery_audit and the queue read (docs/engineering/delivery-fulfilment.md). */
@Repository
class JdbcDeliveryDeskRepository implements DeliveryDeskRepository {

    /** Shipments AfroTransact delivers: FBA and Retail from a fulfilment centre, and seller-store deliveries. */
    private static final String STAFF_DELIVERED = """
            s.delivery_method = 'AFROTRANSACT_DELIVERY'
            and (s.fulfilment_type in ('FBA_AFROTRANSACT', 'RETAIL_1P') or s.local_delivery is not null)
            and o.placed_at is not null
            """;

    private static final String ROW_SELECT = """
            select s.id as shipment_id, o.number as order_number, s.seq,
                   (select count(*) from shipment x where x.order_id = o.id) as shipment_count,
                   s.seller_name, s.fulfilment_type, (s.local_delivery is not null) as from_seller_store, s.node_id,
                   s.local_delivery ->> 'shipFromLocationId' as pickup_location_id, s.status, s.promised_by,
                   s.status_changed_at, s.delivered_at, s.perishable, s.handover_required,
                   (select coalesce(sum(l.quantity), 0) from order_line l where l.shipment_id = s.id) as units,
                   o.ship_to ->> 'fullName' as customer_name, o.ship_to ->> 'city' as customer_city,
                   o.ship_to ->> 'state' as customer_state, o.ship_to ->> 'postalCode' as customer_postal,
                   j.assignee_id, j.assignee_name, coalesce(j.attempts, 0) as attempts,
                   coalesce(j.version, 0) as job_version, (p.id is not null) as proof_attached
            from shipment s
            join customer_order o on o.id = s.order_id
            left join delivery_job j on j.shipment_id = s.id
            left join delivery_proof p on p.shipment_id = s.id
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcDeliveryDeskRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Page<Row> queue(Filter f, int offset, int limit) {
        StringBuilder where = new StringBuilder(" where ").append(STAFF_DELIVERED);
        Map<String, Object> params = new LinkedHashMap<>();
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            where.append(" and s.status in (:statuses)");
            params.put("statuses", f.statuses());
        }
        if (f.mode() != null && !f.mode().isBlank()) {
            where.append("FULFILLED_BY_AFROTRANSACT".equals(f.mode())
                    ? " and s.fulfilment_type in ('FBA_AFROTRANSACT', 'RETAIL_1P')"
                    : " and s.local_delivery is not null");
        }
        if (f.text() != null && !f.text().isBlank()) {
            where.append("""
                     and (o.number ilike :q or s.seller_name ilike :q or o.ship_to ->> 'fullName' ilike :q
                          or o.ship_to ->> 'city' ilike :q or j.assignee_name ilike :q)
                    """);
            params.put("q", "%" + escape(f.text().trim()) + "%");
        }
        if (f.assignee() != null && !f.assignee().isBlank()) {
            if ("unassigned".equals(f.assignee())) {
                where.append(" and j.assignee_id is null");
            } else {
                where.append(" and j.assignee_id = :assignee");
                params.put("assignee", f.assignee());
            }
        }
        if (f.city() != null && !f.city().isBlank()) {
            where.append(" and o.ship_to ->> 'city' ilike :city");
            params.put("city", "%" + escape(f.city().trim()) + "%");
        }
        if (f.seller() != null && !f.seller().isBlank()) {
            where.append(" and s.seller_name ilike :seller");
            params.put("seller", "%" + escape(f.seller().trim()) + "%");
        }
        long total = bind(jdbc.sql("select count(*) from shipment s join customer_order o on o.id = s.order_id "
                + "left join delivery_job j on j.shipment_id = s.id" + where), params)
                .query(Long.class).single();
        String order = switch (f.sort() == null ? "" : f.sort()) {
            case "order" -> "o.number";
            case "seller" -> "s.seller_name";
            case "city" -> "o.ship_to ->> 'city'";
            case "status" -> "s.status";
            case "updated" -> "s.status_changed_at";
            default -> "s.promised_by";
        };
        String sql = ROW_SELECT + where + " order by " + order + (f.descending() ? " desc" : " asc")
                + " nulls last, o.number asc limit :limit offset :offset";
        Map<String, Object> all = new LinkedHashMap<>(params);
        all.put("limit", limit);
        all.put("offset", offset);
        List<Row> rows = bind(jdbc.sql(sql), all).query((rs, n) -> new Row(Sql.str(rs, "shipment_id"),
                rs.getString("order_number"), rs.getInt("seq"), rs.getInt("shipment_count"),
                rs.getString("seller_name"), rs.getString("fulfilment_type"), rs.getBoolean("from_seller_store"),
                rs.getString("node_id"), rs.getString("pickup_location_id"), rs.getString("status"),
                Sql.date(rs, "promised_by"), Sql.instant(rs, "status_changed_at"), Sql.instant(rs, "delivered_at"),
                rs.getBoolean("perishable"), rs.getBoolean("handover_required"), rs.getInt("units"),
                rs.getString("customer_name"), rs.getString("customer_city"), rs.getString("customer_state"),
                rs.getString("customer_postal"), rs.getString("assignee_id"), rs.getString("assignee_name"),
                rs.getInt("attempts"), rs.getLong("job_version"), rs.getBoolean("proof_attached"))).list();
        return new Page<>(rows, offset, limit, total);
    }

    @Override
    public Map<String, Long> counts() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("select s.status, count(*) as n from shipment s join customer_order o on o.id = s.order_id where "
                + STAFF_DELIVERED + " group by s.status").query((rs, n) -> {
                    out.put(rs.getString("status"), rs.getLong("n"));
                    return null;
                }).list();
        return out;
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        JdbcClient.StatementSpec s = spec;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            s = s.param(e.getKey(), e.getValue());
        }
        return s;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public Optional<DeliveryJob> findJob(String shipmentId) {
        return jdbc.sql("select * from delivery_job where shipment_id = :id").param("id", Sql.uuid(shipmentId))
                .query(JdbcDeliveryDeskRepository::job).optional();
    }

    @Override
    public Optional<DeliveryJob> lockJob(String shipmentId) {
        return jdbc.sql("select * from delivery_job where shipment_id = :id for update")
                .param("id", Sql.uuid(shipmentId)).query(JdbcDeliveryDeskRepository::job).optional();
    }

    private static DeliveryJob job(ResultSet rs, int n) throws SQLException {
        return new DeliveryJob(Sql.str(rs, "shipment_id"), rs.getString("assignee_id"), rs.getString("assignee_name"),
                Sql.instant(rs, "assigned_at"), rs.getString("assigned_by"), rs.getInt("attempts"),
                rs.getString("last_failure"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    @Override
    public boolean saveJob(DeliveryJob j, long expectedVersion) {
        if (expectedVersion == 0 && j.version() == 1 && findJob(j.shipmentId()).isEmpty()) {
            return jdbc.sql("""
                    insert into delivery_job (shipment_id, assignee_id, assignee_name, assigned_at, assigned_by,
                        attempts, last_failure, created_at, updated_at, version)
                    values (:id, :aid, :aname, :aat, :aby, :attempts, :failure, :created, :updated, :version)
                    on conflict (shipment_id) do nothing
                    """)
                    .param("id", Sql.uuid(j.shipmentId())).param("aid", j.assigneeId())
                    .param("aname", j.assigneeName()).param("aat", Sql.ts(j.assignedAt()))
                    .param("aby", j.assignedBy()).param("attempts", j.attempts()).param("failure", j.lastFailure())
                    .param("created", Sql.ts(j.createdAt())).param("updated", Sql.ts(j.updatedAt()))
                    .param("version", j.version()).update() == 1;
        }
        return jdbc.sql("""
                update delivery_job set assignee_id = :aid, assignee_name = :aname, assigned_at = :aat,
                    assigned_by = :aby, attempts = :attempts, last_failure = :failure, updated_at = :updated,
                    version = :version
                where shipment_id = :id and version = :expected
                """)
                .param("id", Sql.uuid(j.shipmentId())).param("aid", j.assigneeId()).param("aname", j.assigneeName())
                .param("aat", Sql.ts(j.assignedAt())).param("aby", j.assignedBy()).param("attempts", j.attempts())
                .param("failure", j.lastFailure()).param("updated", Sql.ts(j.updatedAt()))
                .param("version", j.version()).param("expected", expectedVersion).update() == 1;
    }

    @Override
    public Optional<DeliveryProofRecord> findProof(String shipmentId) {
        return jdbc.sql("select * from delivery_proof where shipment_id = :id").param("id", Sql.uuid(shipmentId))
                .query((rs, n) -> new DeliveryProofRecord(Sql.str(rs, "id"), Sql.str(rs, "shipment_id"),
                        assets(rs.getArray("photo_asset_ids")), rs.getString("recipient_name"), rs.getString("note"),
                        rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class),
                        Sql.instant(rs, "delivered_at"), rs.getString("recorded_by_id"),
                        rs.getString("recorded_by_name"), Sql.instant(rs, "corrected_at"),
                        rs.getString("corrected_by_id"), rs.getString("corrected_by_name"), rs.getInt("corrections"),
                        rs.getLong("version"), Sql.instant(rs, "created_at"))).optional();
    }

    private static List<String> assets(Array a) throws SQLException {
        List<String> out = new ArrayList<>();
        if (a != null) {
            for (Object o : (Object[]) a.getArray()) {
                out.add(o.toString());
            }
        }
        return out;
    }

    @Override
    public void insertProof(DeliveryProofRecord p) {
        jdbc.sql("""
                insert into delivery_proof (id, shipment_id, photo_asset_ids, recipient_name, note, latitude, longitude,
                    delivered_at, recorded_by_id, recorded_by_name, corrections, created_at, version)
                values (:id, :shipment, string_to_array(:photos, ','), :recipient, :note, :lat, :lng, :at, :by, :byname,
                    0, :created, 0)
                """)
                .param("id", Sql.uuid(p.id())).param("shipment", Sql.uuid(p.shipmentId()))
                .param("photos", String.join(",", p.photoAssetIds())).param("recipient", p.recipientName())
                .param("note", p.note()).param("lat", p.latitude()).param("lng", p.longitude())
                .param("at", Sql.ts(p.deliveredAt())).param("by", p.recordedById())
                .param("byname", p.recordedByName()).param("created", Sql.ts(p.createdAt())).update();
    }

    @Override
    public boolean updateProof(DeliveryProofRecord p, long expectedVersion) {
        return jdbc.sql("""
                update delivery_proof set photo_asset_ids = string_to_array(:photos, ','), recipient_name = :recipient,
                    note = :note, latitude = :lat, longitude = :lng, corrected_at = :cat, corrected_by_id = :cby,
                    corrected_by_name = :cbyname, corrections = :corrections, version = version + 1
                where shipment_id = :shipment and version = :expected
                """)
                .param("photos", String.join(",", p.photoAssetIds())).param("recipient", p.recipientName())
                .param("note", p.note()).param("lat", p.latitude()).param("lng", p.longitude())
                .param("cat", Sql.ts(p.correctedAt())).param("cby", p.correctedById())
                .param("cbyname", p.correctedByName()).param("corrections", p.corrections())
                .param("shipment", Sql.uuid(p.shipmentId())).param("expected", expectedVersion).update() == 1;
    }

    @Override
    public boolean appendAudit(String orderId, AuditLine l, String key) {
        return jdbc.sql("""
                insert into delivery_audit (shipment_id, order_id, action, from_status, to_status, actor_id, actor_name,
                    detail, idempotency_key, occurred_at)
                values (:shipment, :order, :action, :from, :to, :actor, :name, cast(:detail as jsonb), :key, :at)
                on conflict (shipment_id, idempotency_key) where idempotency_key is not null do nothing
                """)
                .param("shipment", Sql.uuid(l.shipmentId())).param("order", Sql.uuid(orderId))
                .param("action", l.action()).param("from", l.fromStatus()).param("to", l.toStatus())
                .param("actor", l.actorId()).param("name", l.actorName()).param("detail", json.write(l.detail()))
                .param("key", key).param("at", Sql.ts(l.occurredAt())).update() == 1;
    }

    @Override
    public List<AuditLine> audit(String shipmentId) {
        return jdbc.sql("select * from delivery_audit where shipment_id = :id order by id")
                .param("id", Sql.uuid(shipmentId))
                .query((rs, n) -> new AuditLine(rs.getLong("id"), Sql.str(rs, "shipment_id"), rs.getString("action"),
                        rs.getString("from_status"), rs.getString("to_status"), rs.getString("actor_id"),
                        rs.getString("actor_name"), detail(rs.getString("detail")),
                        Sql.instant(rs, "occurred_at")))
                .list();
    }

    private Map<String, Object> detail(String raw) {
        Map<String, Object> d = raw == null ? null : json.read(raw, new TypeReference<Map<String, Object>>() { });
        return d == null ? Map.of() : d;
    }

    @Override
    public boolean auditKeySeen(String shipmentId, String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return jdbc.sql("select count(*) from delivery_audit where shipment_id = :id and idempotency_key = :key")
                .param("id", Sql.uuid(shipmentId)).param("key", key).query(Long.class).single() > 0;
    }
}
