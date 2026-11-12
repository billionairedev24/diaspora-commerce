package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.port.ProductTaxHoldRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProductTaxHoldRepository implements ProductTaxHoldRepository {

    private final JdbcClient jdbc;

    JdbcProductTaxHoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void hold(UUID productId, String reasonCode, Instant at) {
        jdbc.sql("""
                insert into product_tax_hold (product_id, reason_code, held_at)
                values (:id, :reason, :at)
                on conflict (product_id) do update
                   set reason_code = excluded.reason_code, held_at = excluded.held_at,
                       announced_at = null, released_at = null
                """)
                .param("id", productId).param("reason", reasonCode).param("at", Timestamp.from(at)).update();
    }

    @Override
    public boolean isHeld(UUID productId) {
        return jdbc.sql("select count(*) from product_tax_hold where product_id = :id and released_at is null")
                .param("id", productId).query(Long.class).single() > 0;
    }

    @Override
    public boolean release(UUID productId, Instant at) {
        return jdbc.sql("update product_tax_hold set released_at = :at where product_id = :id and released_at is null")
                .param("id", productId).param("at", Timestamp.from(at)).update() > 0;
    }

    @Override
    public List<UUID> openHolds(UUID afterId, int limit) {
        String sql = "select product_id from product_tax_hold where released_at is null "
                + (afterId == null ? "" : "and product_id > :after ") + "order by product_id limit :limit";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("limit", limit);
        if (afterId != null) {
            spec = spec.param("after", afterId);
        }
        return spec.query(UUID.class).list();
    }

    @Override
    public List<Held> unannounced(int limit) {
        return jdbc.sql("""
                select product_id, reason_code from product_tax_hold
                where released_at is null and announced_at is null order by held_at, product_id limit :limit
                """)
                .param("limit", limit)
                .query((rs, n) -> new Held(rs.getObject("product_id", UUID.class), rs.getString("reason_code"))).list();
    }

    @Override
    public void markAnnounced(UUID productId, Instant at) {
        jdbc.sql("update product_tax_hold set announced_at = :at where product_id = :id and announced_at is null")
                .param("id", productId).param("at", Timestamp.from(at)).update();
    }
}
