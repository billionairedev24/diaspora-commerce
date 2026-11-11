package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.port.SellingHoldRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSellingHoldRepository implements SellingHoldRepository {

    /** A category and everything below it, by code(s). */
    private static final String SUBTREE = "p.category_id in (with recursive sub as (select id from category where code in "
            + "(:codes) union all select c.id from category c join sub on c.parent_id = sub.id) select id from sub)";
    private static final String BRAND = "regexp_replace(lower(coalesce(p.attributes->>'brand', '')), '[^[:alnum:]]+', '', 'g')";

    private final JdbcClient jdbc;

    JdbcSellingHoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void hold(UUID productId, String scopeType, String scopeKey, String orgId, String reason, Instant at) {
        jdbc.sql("""
                insert into product_selling_hold (product_id, scope_type, scope_key, org_id, reason, held_at)
                values (:p, :t, :k, :o, :r, :at) on conflict do nothing
                """).param("p", productId).param("t", scopeType).param("k", scopeKey).param("o", orgId)
                .param("r", reason).param("at", Timestamp.from(at)).update();
    }

    @Override
    public boolean isHeld(UUID productId) {
        return jdbc.sql("select exists (select 1 from product_selling_hold where product_id = :p)").param("p", productId)
                .query(Boolean.class).single();
    }

    @Override
    public boolean release(UUID productId, String scopeType, String scopeKey) {
        jdbc.sql("delete from product_selling_hold where product_id = :p and scope_type = :t and scope_key = :k")
                .param("p", productId).param("t", scopeType).param("k", scopeKey).update();
        return !isHeld(productId);
    }

    @Override
    public List<UUID> heldFor(String scopeType, String scopeKey, String orgId, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder("select product_id from product_selling_hold where scope_type = :t "
                + "and scope_key = :k");
        Map<String, Object> params = new HashMap<>(Map.of("t", scopeType, "k", scopeKey, "limit", limit));
        if (orgId != null) {
            sql.append(" and org_id = :o");
            params.put("o", orgId);
        }
        if (afterId != null) {
            sql.append(" and product_id > :after");
            params.put("after", afterId);
        }
        sql.append(" order by product_id limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query((rs, n) -> rs.getObject(1, UUID.class)).list();
    }

    @Override
    public List<UUID> liveInScope(String scopeType, String scopeKey, Collection<String> categoryCodes, String orgId,
                                  UUID afterId, int limit) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder("p.status = 'LIVE'");
        switch (scopeType) {
            case "CATEGORY" -> {
                where.append(" and ").append(SUBTREE);
                params.put("codes", List.of(scopeKey));
            }
            case "CAPABILITY" -> {
                if (categoryCodes.isEmpty()) {
                    return List.of();
                }
                where.append(" and ").append(SUBTREE);
                params.put("codes", List.copyOf(categoryCodes));
            }
            case "PRODUCT_TYPE" -> {
                where.append(" and p.product_type = :key");
                params.put("key", scopeKey);
            }
            case "BRAND" -> {
                where.append(" and ").append(BRAND).append(" = :key");
                params.put("key", scopeKey);
            }
            case "BRAND_IN_CATEGORY" -> {
                int bar = scopeKey.indexOf('|');
                where.append(" and ").append(BRAND).append(" = :brand and ").append(SUBTREE);
                params.put("brand", scopeKey.substring(0, Math.max(bar, 0)));
                params.put("codes", List.of(scopeKey.substring(bar + 1)));
            }
            case "PRODUCT" -> {
                where.append(" and exists (select 1 from product_variant v where v.product_id = p.id and v.gtin = :key)");
                params.put("key", scopeKey);
            }
            default -> {
                return List.of();
            }
        }
        if (orgId != null) {
            where.append(" and p.owner_org = :org");
            params.put("org", orgId);
        }
        if (afterId != null) {
            where.append(" and p.id > :after");
            params.put("after", afterId);
        }
        JdbcClient.StatementSpec spec = jdbc.sql("select p.id from product p where " + where + " order by p.id limit :limit")
                .param("limit", limit);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query((rs, n) -> rs.getObject(1, UUID.class)).list();
    }
}
