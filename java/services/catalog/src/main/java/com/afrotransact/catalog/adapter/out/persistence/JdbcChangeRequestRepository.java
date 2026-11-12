package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ChangeRequestStatus;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcChangeRequestRepository implements ChangeRequestRepository {

    private static final String SELECT = """
            select id, product_id, atin, variant_atin, gtin14, type, field, payload::text as payload, note, status,
                   requested_by_org, requested_by, requested_at, sla_due_at, decided_by, decided_at, reason, resolution,
                   version
            from product_change_request
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcChangeRequestRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public ProductChangeRequest insert(ProductChangeRequest r) {
        jdbc.sql("""
                insert into product_change_request (id, product_id, atin, variant_atin, gtin14, type, field, payload, note,
                                                    status, requested_by_org, requested_by, requested_at, sla_due_at, version)
                values (:id, :product, :atin, :variantAtin, :gtin14, :type, :field, cast(:payload as jsonb), :note,
                        :status, :org, :by, :at, :due, 0)
                """)
                .param("id", r.id()).param("product", r.productId()).param("atin", r.atin())
                .param("variantAtin", r.variantAtin()).param("gtin14", r.gtin14()).param("type", r.type().name())
                .param("field", r.field()).param("payload", json.write(r.payload())).param("note", r.note())
                .param("status", r.status().name()).param("org", r.requestedByOrg()).param("by", r.requestedBy())
                .param("at", Timestamp.from(r.requestedAt())).param("due", ts(r.slaDueAt())).update();
        return findById(r.id()).orElseThrow();
    }

    @Override
    public ProductChangeRequest update(ProductChangeRequest r) {
        int rows = jdbc.sql("""
                update product_change_request set status = :status, decided_by = :by, decided_at = :at, reason = :reason,
                       resolution = :resolution, version = version + 1
                where id = :id and version = :version and status = 'PENDING'
                """)
                .param("status", r.status().name()).param("by", r.decidedBy()).param("at", ts(r.decidedAt()))
                .param("reason", r.reason()).param("resolution", r.resolution()).param("id", r.id())
                .param("version", r.version()).update();
        if (rows == 0) {
            throw new StaleVersionException("The change request " + r.id() + " was decided or changed meanwhile");
        }
        return findById(r.id()).orElseThrow();
    }

    @Override
    public Optional<ProductChangeRequest> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public boolean existsOpen(UUID productId, ChangeRequestType type, String field, String requestedByOrg) {
        return jdbc.sql("""
                select exists(select 1 from product_change_request where product_id = :product and type = :type
                              and field = :field and requested_by_org = :org and status = 'PENDING')
                """).param("product", productId).param("type", type.name()).param("field", field == null ? "" : field)
                .param("org", requestedByOrg).query(Boolean.class).single();
    }

    @Override
    public boolean existsOpenWrongMatch(String gtin14) {
        return jdbc.sql("""
                select exists(select 1 from product_change_request where gtin14 = :gtin14 and type = 'WRONG_MATCH'
                              and status = 'PENDING')
                """).param("gtin14", gtin14).query(Boolean.class).single();
    }

    @Override
    public List<ProductChangeRequest> findByProduct(UUID productId, String requestedByOrg) {
        String where = requestedByOrg == null ? "" : " and requested_by_org = :org";
        JdbcClient.StatementSpec spec = jdbc.sql(SELECT + " where product_id = :product" + where
                + " order by requested_at desc").param("product", productId);
        if (requestedByOrg != null) {
            spec = spec.param("org", requestedByOrg);
        }
        return spec.query(this::map).list();
    }

    @Override
    public Page search(Query q) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (q.status() != null) {
            where.append(" and status = :status");
            params.put("status", q.status().name());
        }
        if (q.type() != null) {
            where.append(" and type = :type");
            params.put("type", q.type().name());
        }
        if (q.requestedByOrg() != null) {
            where.append(" and requested_by_org = :org");
            params.put("org", q.requestedByOrg());
        }
        long total = jdbc.sql("select count(*) from product_change_request" + where).params(params)
                .query(Long.class).single();
        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("limit", q.size());
        pageParams.put("offset", (long) q.page() * q.size());
        List<ProductChangeRequest> items = jdbc.sql(SELECT + where + " order by requested_at "
                + (q.oldestFirst() ? "asc" : "desc") + ", id limit :limit offset :offset").params(pageParams)
                .query(this::map).list();
        return new Page(items, total, q.page(), q.size());
    }

    @Override
    public long countOpen() {
        return jdbc.sql("select count(*) from product_change_request where status = 'PENDING'")
                .query(Long.class).single();
    }

    @Override
    public Optional<Instant> oldestOpenSince() {
        return jdbc.sql("select min(requested_at) from product_change_request where status = 'PENDING'")
                .query((rs, n) -> {
                    Timestamp t = rs.getTimestamp(1);
                    return t == null ? null : t.toInstant();
                }).optional().map(i -> i);
    }

    private ProductChangeRequest map(ResultSet rs, int row) throws SQLException {
        return new ProductChangeRequest(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("atin").trim(), trim(rs.getString("variant_atin")), trim(rs.getString("gtin14")),
                ChangeRequestType.valueOf(rs.getString("type")), rs.getString("field"),
                json.objectMap(rs.getString("payload")), rs.getString("note"),
                ChangeRequestStatus.valueOf(rs.getString("status")), rs.getString("requested_by_org"),
                rs.getString("requested_by"), rs.getTimestamp("requested_at").toInstant(),
                instant(rs.getTimestamp("sla_due_at")), rs.getString("decided_by"),
                instant(rs.getTimestamp("decided_at")), rs.getString("reason"), rs.getString("resolution"),
                rs.getLong("version"));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
