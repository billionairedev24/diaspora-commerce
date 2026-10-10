package com.afrotransact.inventory.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.inventory.domain.Movement;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.domain.StockLevels;
import com.afrotransact.inventory.port.LedgerRepository;
import com.afrotransact.inventory.port.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Insert-only access to stock_movement; a database trigger rejects UPDATE, DELETE and TRUNCATE. */
@Repository
class JdbcLedgerRepository implements LedgerRepository {

    private static final String SELECT = """
            select id, occurred_at, type, sku, node_id, owner_org, lot_id, lot_no, qty,
                   on_hand_before, on_hand_after, reserved_before, reserved_after, damaged_before, damaged_after,
                   inbound_before, inbound_after, reference, actor, reason, correlation_id
            from stock_movement
            """;

    private final JdbcClient jdbc;

    JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(List<Movement> movements) {
        for (Movement m : movements) {
            StockLevels d = m.delta();
            Map<String, Object> p = new HashMap<>();
            p.put("at", Timestamp.from(m.occurredAt()));
            p.put("type", m.type().name());
            p.put("sku", m.sku());
            p.put("node", m.nodeId());
            p.put("owner", m.ownerOrg());
            p.put("lotId", m.lotId());
            p.put("lotNo", m.lotNo());
            p.put("qty", m.qty());
            p.put("dOnHand", d.onHand());
            p.put("dReserved", d.reserved());
            p.put("dDamaged", d.damaged());
            p.put("dInbound", d.inbound());
            p.put("ohB", m.before().onHand());
            p.put("ohA", m.after().onHand());
            p.put("rB", m.before().reserved());
            p.put("rA", m.after().reserved());
            p.put("dB", m.before().damaged());
            p.put("dA", m.after().damaged());
            p.put("iB", m.before().inbound());
            p.put("iA", m.after().inbound());
            p.put("ref", m.reference());
            p.put("actor", m.actor());
            p.put("reason", m.reason());
            p.put("corr", m.correlationId());
            jdbc.sql("""
                    insert into stock_movement (occurred_at, type, sku, node_id, owner_org, lot_id, lot_no, qty,
                        delta_on_hand, delta_reserved, delta_damaged, delta_inbound,
                        on_hand_before, on_hand_after, reserved_before, reserved_after,
                        damaged_before, damaged_after, inbound_before, inbound_after,
                        reference, actor, reason, correlation_id)
                    values (:at, :type, :sku, :node, :owner, :lotId, :lotNo, :qty,
                        :dOnHand, :dReserved, :dDamaged, :dInbound,
                        :ohB, :ohA, :rB, :rA, :dB, :dA, :iB, :iA,
                        :ref, :actor, :reason, :corr)
                    """)
                    .params(p)
                    .update();
        }
    }

    @Override
    public Page<Movement> search(MovementQuery q) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (q.sku() != null) {
            where.append(" and sku = :sku");
            params.put("sku", q.sku());
        }
        if (q.nodeId() != null) {
            where.append(" and node_id = :node");
            params.put("node", q.nodeId());
        }
        if (q.lotId() != null) {
            where.append(" and lot_id = :lotId");
            params.put("lotId", q.lotId());
        }
        if (q.reference() != null) {
            where.append(" and reference = :ref");
            params.put("ref", q.reference());
        }
        if (q.ownerOrg() != null) {
            where.append(" and owner_org = :owner");
            params.put("owner", q.ownerOrg());
        }
        if (q.type() != null) {
            where.append(" and type = :type");
            params.put("type", q.type().name());
        }
        long total = jdbc.sql("select count(*) from stock_movement" + where).params(params).query(Long.class).single();
        params.put("limit", q.size());
        params.put("offset", (long) q.page() * q.size());
        List<Movement> items = jdbc.sql(SELECT + where + " order by id desc limit :limit offset :offset")
                .params(params).query(JdbcLedgerRepository::map).list();
        return new Page<>(items, total, q.page(), q.size());
    }

    @Override
    public Map<String, StockLevels> sumsByPosition() {
        Map<String, StockLevels> out = new LinkedHashMap<>();
        jdbc.sql("""
                select sku, node_id, sum(delta_on_hand) as on_hand, sum(delta_reserved) as reserved,
                       sum(delta_damaged) as damaged, sum(delta_inbound) as inbound
                from stock_movement group by sku, node_id
                """)
                .query((ResultSet rs) -> {
                    out.put(rs.getString("sku") + "|" + rs.getString("node_id"), new StockLevels(rs.getInt("on_hand"),
                            rs.getInt("reserved"), rs.getInt("damaged"), rs.getInt("inbound")));
                });
        return out;
    }

    @Override
    public int sumQty(String reference, String sku, List<MovementType> types) {
        return jdbc.sql("""
                select coalesce(sum(qty), 0) from stock_movement
                where reference = :ref and sku = :sku and type in (:types)
                """)
                .param("ref", reference).param("sku", sku)
                .param("types", types.stream().map(Enum::name).toList())
                .query(Integer.class).single();
    }

    private static Movement map(ResultSet rs, int row) throws SQLException {
        return new Movement(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
                MovementType.valueOf(rs.getString("type")), rs.getString("sku"), rs.getString("node_id"),
                rs.getString("owner_org"), rs.getObject("lot_id", UUID.class), rs.getString("lot_no"),
                rs.getInt("qty"),
                new StockLevels(rs.getInt("on_hand_before"), rs.getInt("reserved_before"), rs.getInt("damaged_before"),
                        rs.getInt("inbound_before")),
                new StockLevels(rs.getInt("on_hand_after"), rs.getInt("reserved_after"), rs.getInt("damaged_after"),
                        rs.getInt("inbound_after")),
                rs.getString("reference"), rs.getString("actor"), rs.getString("reason"),
                rs.getString("correlation_id"));
    }
}
