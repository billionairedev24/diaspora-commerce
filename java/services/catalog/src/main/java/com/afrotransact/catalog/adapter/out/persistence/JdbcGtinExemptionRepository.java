package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ExemptionRequirement;
import com.afrotransact.catalog.domain.ExemptionStatus;
import com.afrotransact.catalog.domain.GtinExemptionRequest;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.port.GtinExemptionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcGtinExemptionRepository implements GtinExemptionRepository {

    private static final String SELECT = """
            select id, product_id, product_atin, requested_by_org, seller_reason, required_because, status, requested_at,
                   sla_due_at, decided_by, decided_at, reason, version
            from gtin_exemption_request
            """;

    private final JdbcClient jdbc;

    JdbcGtinExemptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public GtinExemptionRequest insert(GtinExemptionRequest r) {
        jdbc.sql("""
                insert into gtin_exemption_request (id, product_id, product_atin, requested_by_org, seller_reason,
                                                    required_because, status, requested_at, sla_due_at, version)
                values (:id, :product, :atin, :org, :reason, :because, :status, :at, :due, 0)
                """)
                .param("id", r.id()).param("product", r.productId()).param("atin", r.productAtin())
                .param("org", r.requestedByOrg()).param("reason", r.sellerReason())
                .param("because", r.requiredBecause().name()).param("status", r.status().name())
                .param("at", Timestamp.from(r.requestedAt())).param("due", ts(r.slaDueAt())).update();
        return findById(r.id()).orElseThrow();
    }

    @Override
    public GtinExemptionRequest update(GtinExemptionRequest r) {
        int rows = jdbc.sql("""
                update gtin_exemption_request set status = :status, decided_by = :by, decided_at = :at, reason = :reason,
                       version = version + 1
                where id = :id and version = :version and status = 'PENDING'
                """)
                .param("status", r.status().name()).param("by", r.decidedBy()).param("at", ts(r.decidedAt()))
                .param("reason", r.reason()).param("id", r.id()).param("version", r.version()).update();
        if (rows == 0) {
            throw new StaleVersionException("The exemption request " + r.id() + " was decided meanwhile");
        }
        return findById(r.id()).orElseThrow();
    }

    @Override
    public Optional<GtinExemptionRequest> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(JdbcGtinExemptionRepository::map).optional();
    }

    @Override
    public Optional<GtinExemptionRequest> findOpenByProduct(UUID productId) {
        return jdbc.sql(SELECT + " where product_id = :product and status = 'PENDING'").param("product", productId)
                .query(JdbcGtinExemptionRepository::map).optional();
    }

    @Override
    public List<GtinExemptionRequest> findByOrg(String requestedByOrg) {
        return jdbc.sql(SELECT + " where requested_by_org = :org order by requested_at desc").param("org", requestedByOrg)
                .query(JdbcGtinExemptionRepository::map).list();
    }

    @Override
    public Page search(ExemptionStatus status, int page, int size) {
        String where = status == null ? "" : " where status = :status";
        JdbcClient.StatementSpec count = jdbc.sql("select count(*) from gtin_exemption_request" + where);
        JdbcClient.StatementSpec list = jdbc.sql(SELECT + where + " order by requested_at asc, id limit :limit offset :offset")
                .param("limit", size).param("offset", (long) page * size);
        if (status != null) {
            count = count.param("status", status.name());
            list = list.param("status", status.name());
        }
        return new Page(list.query(JdbcGtinExemptionRepository::map).list(), count.query(Long.class).single(), page, size);
    }

    @Override
    public long countOpen() {
        return jdbc.sql("select count(*) from gtin_exemption_request where status = 'PENDING'")
                .query(Long.class).single();
    }

    @Override
    public Optional<Instant> oldestOpenSince() {
        return jdbc.sql("select min(requested_at) from gtin_exemption_request where status = 'PENDING'")
                .query((rs, n) -> {
                    Timestamp t = rs.getTimestamp(1);
                    return t == null ? null : t.toInstant();
                }).optional().map(i -> i);
    }

    private static GtinExemptionRequest map(ResultSet rs, int row) throws SQLException {
        return new GtinExemptionRequest(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("product_atin").trim(), rs.getString("requested_by_org"), rs.getString("seller_reason"),
                ExemptionRequirement.valueOf(rs.getString("required_because")),
                ExemptionStatus.valueOf(rs.getString("status")), rs.getTimestamp("requested_at").toInstant(),
                instant(rs.getTimestamp("sla_due_at")), rs.getString("decided_by"),
                instant(rs.getTimestamp("decided_at")), rs.getString("reason"), rs.getLong("version"));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
