package com.afrotransact.offers.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.offers.port.StockRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStockRepository implements StockRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcStockRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean apply(StockLevel level) {
        int rows = jdbc.sql("""
                insert into stock_level (sku, node_id, owner_org, available, version, updated_at)
                values (:sku, :node, :org, :available, :version, :now)
                on conflict (sku, node_id) do update
                   set owner_org = excluded.owner_org, available = excluded.available, version = excluded.version,
                       updated_at = excluded.updated_at
                 where stock_level.version < excluded.version
                """)
                .param("sku", level.sku())
                .param("node", level.nodeId())
                .param("org", level.ownerOrg())
                .param("available", Math.max(0, level.available()))
                .param("version", level.version())
                .param("now", Timestamp.from(Instant.now(clock)))
                .update();
        return rows == 1;
    }

    @Override
    public Map<StockKey, Integer> available(Collection<StockKey> keys) {
        Map<StockKey, Integer> out = new HashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        List<String> skus = keys.stream().map(StockKey::sku).distinct().toList();
        jdbc.sql("""
                select owner_org, sku, sum(available) as available from stock_level
                where sku in (:skus) group by owner_org, sku
                """)
                .param("skus", skus)
                .query((rs, n) -> Map.entry(new StockKey(rs.getString("owner_org"), rs.getString("sku")),
                        rs.getInt("available")))
                .list()
                .stream()
                .filter(e -> keys.contains(e.getKey()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
