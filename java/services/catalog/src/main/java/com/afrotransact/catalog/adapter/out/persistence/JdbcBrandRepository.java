package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandOption;
import com.afrotransact.catalog.domain.brand.BrandStatus;
import com.afrotransact.catalog.domain.brand.BrandUsage;
import com.afrotransact.catalog.port.BrandRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBrandRepository implements BrandRepository {

    private static final String COLUMNS = """
            b.id, b.name, b.normalized_name, b.status, b.merged_into_id, b.created_by_org, b.created_at, b.updated_at,
            b.version, b.decided_by, b.decided_at, b.decision_reason""";
    private static final String HELD = "(p.attributes ->> 'brand_id') = cast(b.id as text) and p.status <> 'REJECTED'";

    private final JdbcClient jdbc;

    JdbcBrandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Brand> findById(UUID id) {
        return withScopes(jdbc.sql("select " + COLUMNS + " from brand b where b.id = :id").param("id", id)
                .query(JdbcBrandRepository::mapBrand).list()).stream().findFirst();
    }

    @Override
    public List<Brand> findAllById(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return withScopes(jdbc.sql("select " + COLUMNS + " from brand b where b.id in (:ids)").param("ids", ids)
                .query(JdbcBrandRepository::mapBrand).list());
    }

    @Override
    public List<Brand> findLiveByKey(String normalisedName) {
        return withScopes(jdbc.sql("select " + COLUMNS + """
                 from brand b where b.normalized_name = :key and b.status in ('APPROVED', 'PENDING')
                order by (b.status = 'APPROVED') desc, b.created_at
                """).param("key", normalisedName).query(JdbcBrandRepository::mapBrand).list());
    }

    @Override
    public List<Brand> findMergedInto(UUID id) {
        return withScopes(jdbc.sql("select " + COLUMNS + " from brand b where b.merged_into_id = :id")
                .param("id", id).query(JdbcBrandRepository::mapBrand).list());
    }

    @Override
    public Brand insert(Brand b) {
        jdbc.sql("""
                insert into brand (id, name, normalized_name, status, merged_into_id, created_by_org, created_at,
                                   updated_at, version, decided_by, decided_at, decision_reason)
                values (:id, :name, :key, :status, :merged, :org, :created, :updated, 0, :decidedBy, :decidedAt,
                        :reason)
                """)
                .param("id", b.id())
                .param("name", b.name())
                .param("key", b.normalisedName())
                .param("status", b.status().name())
                .param("merged", b.mergedIntoId())
                .param("org", b.createdByOrg())
                .param("created", Timestamp.from(b.createdAt()))
                .param("updated", Timestamp.from(b.updatedAt()))
                .param("decidedBy", b.decidedBy())
                .param("decidedAt", b.decidedAt() == null ? null : Timestamp.from(b.decidedAt()))
                .param("reason", b.decisionReason())
                .update();
        writeScope(b.id(), b.scope());
        return findById(b.id()).orElseThrow();
    }

    @Override
    public Brand update(Brand b) {
        int rows = jdbc.sql("""
                update brand set name = :name, normalized_name = :key, status = :status, merged_into_id = :merged,
                       updated_at = :updated, version = version + 1, decided_by = :decidedBy,
                       decided_at = :decidedAt, decision_reason = :reason
                where id = :id and version = :version
                """)
                .param("id", b.id())
                .param("name", b.name())
                .param("key", b.normalisedName())
                .param("status", b.status().name())
                .param("merged", b.mergedIntoId())
                .param("updated", Timestamp.from(b.updatedAt()))
                .param("decidedBy", b.decidedBy())
                .param("decidedAt", b.decidedAt() == null ? null : Timestamp.from(b.decidedAt()))
                .param("reason", b.decisionReason())
                .param("version", b.version())
                .update();
        if (rows == 0) {
            throw new StaleVersionException("This brand changed since you opened it. Reload and try again.");
        }
        jdbc.sql("delete from brand_scope where brand_id = :id").param("id", b.id()).update();
        writeScope(b.id(), b.scope());
        return findById(b.id()).orElseThrow();
    }

    @Override
    public void lockKey(String normalisedName) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:key))").param("key", "brand:" + normalisedName)
                .query().singleValue();
    }

    @Override
    public long countPendingOf(String orgId) {
        return jdbc.sql("select count(*) from brand where status = 'PENDING' and created_by_org = :org")
                .param("org", orgId).query(Long.class).single();
    }

    @Override
    public List<BrandOption> options(Set<UUID> nodeIds, String orgId, String needle, int limit) {
        if (nodeIds.isEmpty()) {
            return List.of();
        }
        String org = orgId == null ? "" : orgId;
        String match = needle == null || needle.isEmpty() ? "" : " and b.normalized_name like :contains";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                select b.id, b.name, b.status, b.created_by_org,
                       (select count(*) from product p where %s and p.owner_org = :org) as own,
                       (select count(*) from product p where %s and p.status = 'LIVE') as popular
                from brand b
                where exists (select 1 from brand_scope s where s.brand_id = b.id and s.node_id in (:nodes))
                  and (b.status = 'APPROVED' or b.status = 'PENDING' and b.created_by_org = :org)%s
                order by own desc, (b.normalized_name like :prefix) desc, popular desc, lower(b.name), b.id
                limit :limit
                """.formatted(HELD, HELD, match))
                .param("org", org).param("nodes", nodeIds).param("limit", limit)
                .param("prefix", (needle == null ? "" : needle) + "%");
        if (!match.isEmpty()) {
            spec = spec.param("contains", "%" + needle + "%");
        }
        return spec.query((rs, i) -> new BrandOption(rs.getObject("id", UUID.class), rs.getString("name"),
                BrandStatus.valueOf(rs.getString("status")), rs.getString("created_by_org"), rs.getLong("own"),
                rs.getLong("popular"))).list();
    }

    @Override
    public List<BrandUsage> search(Set<BrandStatus> statuses, String needle, UUID scopeNodeId, int limit, int offset) {
        Map<String, Object> params = new HashMap<>();
        String where = filter(statuses, needle, scopeNodeId, params);
        JdbcClient.StatementSpec spec = jdbc.sql("select " + COLUMNS + ", " + """
                (select count(*) from product p where %s) as products,
                (select count(distinct p.owner_org) from product p where %s) as sellers
                from brand b where %s order by b.created_at desc, b.name, b.id limit :limit offset :offset
                """.formatted(HELD, HELD, where));
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        List<Object[]> counts = new ArrayList<>();
        List<Brand> brands = withScopes(spec.param("limit", limit).param("offset", offset)
                .query((rs, i) -> {
                    counts.add(new Object[] {rs.getLong("products"), rs.getLong("sellers")});
                    return mapBrand(rs, i);
                }).list());
        List<BrandUsage> out = new ArrayList<>();
        for (int i = 0; i < brands.size(); i++) {
            out.add(new BrandUsage(brands.get(i), (Long) counts.get(i)[0], (Long) counts.get(i)[1]));
        }
        return out;
    }

    @Override
    public long count(Set<BrandStatus> statuses, String needle, UUID scopeNodeId) {
        Map<String, Object> params = new HashMap<>();
        String where = filter(statuses, needle, scopeNodeId, params);
        JdbcClient.StatementSpec spec = jdbc.sql("select count(*) from brand b where " + where);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query(Long.class).single();
    }

    @Override
    public Optional<BrandUsage> usage(UUID id) {
        return findById(id).map(b -> {
            Map<String, Long> n = jdbc.sql("""
                    select count(*) as products, count(distinct p.owner_org) as sellers
                    from product p, brand b where b.id = :id and %s
                    """.formatted(HELD)).param("id", id).query((rs, i) -> Map.of("products", rs.getLong("products"),
                    "sellers", rs.getLong("sellers"))).single();
            return new BrandUsage(b, n.get("products"), n.get("sellers"));
        });
    }

    @Override
    public List<BrandCount> countByStatus() {
        return jdbc.sql("select status, count(*) as n from brand group by status order by status")
                .query((rs, i) -> new BrandCount(BrandStatus.valueOf(rs.getString("status")), rs.getLong("n"))).list();
    }

    @Override
    public List<UUID> repointProducts(UUID fromId, UUID toId, String toName) {
        return jdbc.sql("""
                update product set attributes = attributes || jsonb_build_object('brand_id', cast(:to as text),
                                                                                 'brand', cast(:name as text)),
                       updated_at = now(), version = version + 1
                where (attributes ->> 'brand_id') = :from
                returning id
                """)
                .param("from", fromId.toString()).param("to", toId.toString()).param("name", toName)
                .query(UUID.class).list();
    }

    private static String filter(Set<BrandStatus> statuses, String needle, UUID scopeNodeId, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("true");
        if (statuses != null && !statuses.isEmpty()) {
            where.append(" and b.status in (:statuses)");
            params.put("statuses", statuses.stream().map(Enum::name).toList());
        }
        if (needle != null && !needle.isEmpty()) {
            where.append(" and b.normalized_name like :contains");
            params.put("contains", "%" + needle + "%");
        }
        if (scopeNodeId != null) {
            where.append(" and exists (select 1 from brand_scope s where s.brand_id = b.id and s.node_id = :scopeNode)");
            params.put("scopeNode", scopeNodeId);
        }
        return where.toString();
    }

    private void writeScope(UUID brandId, Set<UUID> scope) {
        for (UUID node : scope) {
            jdbc.sql("insert into brand_scope (brand_id, node_id) values (:brand, :node)")
                    .param("brand", brandId).param("node", node).update();
        }
    }

    private List<Brand> withScopes(List<Brand> brands) {
        if (brands.isEmpty()) {
            return brands;
        }
        Map<UUID, Set<UUID>> scopes = new LinkedHashMap<>();
        jdbc.sql("select brand_id, node_id from brand_scope where brand_id in (:ids)")
                .param("ids", brands.stream().map(Brand::id).toList())
                .query((rs, i) -> {
                    scopes.computeIfAbsent(rs.getObject("brand_id", UUID.class), k -> new LinkedHashSet<>())
                            .add(rs.getObject("node_id", UUID.class));
                    return 0;
                }).list();
        return brands.stream().map(b -> b.withScope(scopes.getOrDefault(b.id(), Set.of()), b.updatedAt())).toList();
    }

    private static Brand mapBrand(ResultSet rs, int rowNum) throws SQLException {
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new Brand(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("normalized_name"),
                BrandStatus.valueOf(rs.getString("status")), rs.getObject("merged_into_id", UUID.class),
                rs.getString("created_by_org"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"), rs.getString("decided_by"),
                decidedAt == null ? null : decidedAt.toInstant(), rs.getString("decision_reason"), Set.of());
    }
}
