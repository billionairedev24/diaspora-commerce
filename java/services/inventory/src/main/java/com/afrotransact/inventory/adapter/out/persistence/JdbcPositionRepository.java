package com.afrotransact.inventory.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.inventory.domain.StockLevels;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPositionRepository implements PositionRepository {

    private static final String SELECT = """
            select sku, node_id, owner_org, on_hand, reserved, inbound, damaged, version from inventory_position
            """;
    static final int LOW_STOCK = 10;

    private final JdbcClient jdbc;

    JdbcPositionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void ensureExists(String sku, String nodeId, String ownerOrg, Instant now) {
        jdbc.sql("""
                insert into inventory_position (sku, node_id, owner_org, updated_at)
                values (:sku, :node, :owner, :now)
                on conflict (sku, node_id) do nothing
                """)
                .param("sku", sku)
                .param("node", nodeId)
                .param("owner", ownerOrg)
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public List<StockPosition> lockBySkus(Collection<String> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where sku in (:skus) order by sku, node_id for update")
                .param("skus", List.copyOf(skus))
                .query(JdbcPositionRepository::map)
                .list();
    }

    @Override
    public StockPosition update(StockPosition p, Instant now) {
        int rows = jdbc.sql("""
                update inventory_position
                set on_hand = :onHand, reserved = :reserved, inbound = :inbound, damaged = :damaged,
                    version = version + 1, updated_at = :now
                where sku = :sku and node_id = :node and version = :version
                """)
                .param("onHand", p.onHand())
                .param("reserved", p.reserved())
                .param("inbound", p.inbound())
                .param("damaged", p.damaged())
                .param("now", Timestamp.from(now))
                .param("sku", p.sku())
                .param("node", p.nodeId())
                .param("version", p.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("inventory.concurrent-update", "Position " + p.sku() + " at " + p.nodeId()
                    + " changed concurrently; retry");
        }
        return new StockPosition(p.sku(), p.nodeId(), p.ownerOrg(), p.onHand(), p.reserved(), p.inbound(),
                p.damaged(), p.version() + 1);
    }

    @Override
    public Optional<StockPosition> find(String sku, String nodeId) {
        return jdbc.sql(SELECT + " where sku = :sku and node_id = :node")
                .param("sku", sku).param("node", nodeId)
                .query(JdbcPositionRepository::map).optional();
    }

    @Override
    public List<StockPosition> findBySkus(Collection<String> skus, String ownerOrg) {
        if (skus.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("skus", List.copyOf(skus));
        String where = " where sku in (:skus)";
        if (ownerOrg != null) {
            where += " and owner_org = :owner";
            params.put("owner", ownerOrg);
        }
        return jdbc.sql(SELECT + where + " order by sku, node_id").params(params)
                .query(JdbcPositionRepository::map).list();
    }

    @Override
    public Page<StockPosition> search(PositionQuery q) {
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
        if (q.ownerOrg() != null) {
            where.append(" and owner_org = :owner");
            params.put("owner", q.ownerOrg());
        }
        if (q.lowStockOnly()) {
            where.append(" and on_hand - reserved <= ").append(LOW_STOCK);
        }
        long total = jdbc.sql("select count(*) from inventory_position" + where).params(params)
                .query(Long.class).single();
        params.put("limit", q.size());
        params.put("offset", (long) q.page() * q.size());
        List<StockPosition> items = jdbc.sql(SELECT + where + " order by sku, node_id limit :limit offset :offset")
                .params(params).query(JdbcPositionRepository::map).list();
        return new Page<>(items, total, q.page(), q.size());
    }

    @Override
    public Map<String, StockLevels> allLevels() {
        Map<String, StockLevels> out = new LinkedHashMap<>();
        jdbc.sql(SELECT).query((ResultSet rs) -> {
            StockPosition p = map(rs, 0);
            out.put(p.sku() + "|" + p.nodeId(), p.levels());
        });
        return out;
    }

    static StockPosition map(ResultSet rs, int row) throws SQLException {
        return new StockPosition(rs.getString("sku"), rs.getString("node_id"), rs.getString("owner_org"),
                rs.getInt("on_hand"), rs.getInt("reserved"), rs.getInt("inbound"), rs.getInt("damaged"),
                rs.getLong("version"));
    }
}
