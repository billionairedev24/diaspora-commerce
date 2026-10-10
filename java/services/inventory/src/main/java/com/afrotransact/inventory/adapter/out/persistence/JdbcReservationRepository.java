package com.afrotransact.inventory.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.LotAllocation;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationLine;
import com.afrotransact.inventory.domain.ReservationStatus;
import com.afrotransact.inventory.port.ReservationRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReservationRepository implements ReservationRepository {

    private static final String SELECT = """
            select id, order_id, idempotency_key, status, expires_at, created_at, updated_at, version from reservation
            """;

    private final JdbcClient jdbc;

    JdbcReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(Reservation r) {
        return jdbc.sql("""
                insert into reservation (id, order_id, idempotency_key, status, expires_at, created_at, updated_at,
                                         version)
                values (:id, :orderId, :key, :status, :expiresAt, :createdAt, :createdAt, 0)
                on conflict (order_id) do nothing
                """)
                .param("id", r.id())
                .param("orderId", r.orderId())
                .param("key", r.idempotencyKey())
                .param("status", r.status().name())
                .param("expiresAt", r.expiresAt() == null ? null : Timestamp.from(r.expiresAt()))
                .param("createdAt", Timestamp.from(r.createdAt()))
                .update() == 1;
    }

    @Override
    public void insertLines(Reservation r) {
        for (ReservationLine line : r.lines()) {
            jdbc.sql("""
                    insert into reservation_line (reservation_id, line_no, sku, node_id, qty)
                    values (:id, :lineNo, :sku, :node, :qty)
                    """)
                    .param("id", r.id()).param("lineNo", line.lineNo()).param("sku", line.sku())
                    .param("node", line.nodeId()).param("qty", line.qty())
                    .update();
            for (LotAllocation a : line.allocations()) {
                jdbc.sql("""
                        insert into reservation_allocation (reservation_id, line_no, lot_id, lot_no, qty, exp_date,
                                                            unit_landed_cost_minor, currency)
                        values (:id, :lineNo, :lotId, :lotNo, :qty, :exp, :cost, :currency)
                        """)
                        .param("id", r.id()).param("lineNo", line.lineNo()).param("lotId", a.lotId())
                        .param("lotNo", a.lotNo()).param("qty", a.qty())
                        .param("exp", a.expDate() == null ? null : Date.valueOf(a.expDate()))
                        .param("cost", a.unitLandedCostMinor()).param("currency", a.currency())
                        .update();
            }
        }
    }

    @Override
    public Optional<Reservation> findByOrderId(String orderId) {
        return withLines(jdbc.sql(SELECT + " where order_id = :orderId").param("orderId", orderId)
                .query(JdbcReservationRepository::map).list()).stream().findFirst();
    }

    @Override
    public Optional<Reservation> lockByOrderId(String orderId) {
        return withLines(jdbc.sql(SELECT + " where order_id = :orderId for update").param("orderId", orderId)
                .query(JdbcReservationRepository::map).list()).stream().findFirst();
    }

    @Override
    public List<Reservation> lockExpired(Instant now, int limit) {
        return withLines(jdbc.sql(SELECT + """
                 where status = 'HELD' and expires_at is not null and expires_at <= :now
                 order by expires_at
                 limit :limit
                 for update skip locked
                """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(JdbcReservationRepository::map).list());
    }

    @Override
    public Reservation update(Reservation r) {
        int rows = jdbc.sql("""
                update reservation set status = :status, expires_at = :expiresAt, updated_at = :updatedAt,
                       version = version + 1
                where id = :id and version = :version
                """)
                .param("status", r.status().name())
                .param("expiresAt", r.expiresAt() == null ? null : Timestamp.from(r.expiresAt()))
                .param("updatedAt", Timestamp.from(r.updatedAt()))
                .param("id", r.id())
                .param("version", r.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("inventory.concurrent-update", "Reservation for order " + r.orderId()
                    + " changed concurrently");
        }
        return new Reservation(r.id(), r.orderId(), r.status(), r.idempotencyKey(), r.expiresAt(), r.createdAt(),
                r.updatedAt(), r.lines(), r.version() + 1);
    }

    private List<Reservation> withLines(List<Reservation> headers) {
        if (headers.isEmpty()) {
            return headers;
        }
        List<UUID> ids = headers.stream().map(Reservation::id).toList();
        Map<String, List<LotAllocation>> allocations = new LinkedHashMap<>();
        jdbc.sql("""
                select reservation_id, line_no, lot_id, lot_no, qty, exp_date, unit_landed_cost_minor, currency
                from reservation_allocation where reservation_id in (:ids)
                order by reservation_id, line_no, exp_date nulls last, lot_no
                """)
                .param("ids", ids)
                .query((ResultSet rs) -> {
                    Date exp = rs.getDate("exp_date");
                    allocations.computeIfAbsent(rs.getObject("reservation_id", UUID.class) + "#" + rs.getInt("line_no"),
                            k -> new ArrayList<>()).add(new LotAllocation(rs.getObject("lot_id", UUID.class),
                            rs.getString("lot_no"), rs.getInt("qty"), exp == null ? null : exp.toLocalDate(),
                            rs.getLong("unit_landed_cost_minor"), rs.getString("currency")));
                });
        Map<UUID, List<ReservationLine>> lines = new LinkedHashMap<>();
        jdbc.sql("""
                select reservation_id, line_no, sku, node_id, qty from reservation_line
                where reservation_id in (:ids) order by reservation_id, line_no
                """)
                .param("ids", ids)
                .query((ResultSet rs) -> {
                    UUID id = rs.getObject("reservation_id", UUID.class);
                    int lineNo = rs.getInt("line_no");
                    lines.computeIfAbsent(id, k -> new ArrayList<>()).add(new ReservationLine(lineNo,
                            rs.getString("sku"), rs.getString("node_id"), rs.getInt("qty"),
                            allocations.getOrDefault(id + "#" + lineNo, List.of())));
                });
        return headers.stream().map(h -> h.withLines(lines.getOrDefault(h.id(), List.of()))).toList();
    }

    private static Reservation map(ResultSet rs, int row) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new Reservation(rs.getObject("id", UUID.class), rs.getString("order_id"),
                ReservationStatus.valueOf(rs.getString("status")), rs.getString("idempotency_key"),
                expires == null ? null : expires.toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), List.of(), rs.getLong("version"));
    }
}
