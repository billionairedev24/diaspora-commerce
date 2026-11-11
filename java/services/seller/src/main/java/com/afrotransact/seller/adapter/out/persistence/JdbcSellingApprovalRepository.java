package com.afrotransact.seller.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.seller.domain.SellingApproval;
import com.afrotransact.seller.domain.SellingApprovalStatus;
import com.afrotransact.seller.domain.SellingDocument;
import com.afrotransact.seller.domain.SellingScope;
import com.afrotransact.seller.domain.SellingScopeType;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.SellingApprovalRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcSellingApprovalRepository implements SellingApprovalRepository {

    private static final TypeReference<List<SellingDocument>> DOCS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcSellingApprovalRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<SellingApproval> find(String orgId, SellingScope scope) {
        return jdbc.sql("select * from selling_approval where org_id = :o and scope_type = :t and scope_key = :k")
                .param("o", orgId).param("t", scope.type().name()).param("k", scope.key())
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<SellingApproval> lock(String orgId, SellingScope scope) {
        return jdbc.sql("select * from selling_approval where org_id = :o and scope_type = :t and scope_key = :k "
                + "for update").param("o", orgId).param("t", scope.type().name()).param("k", scope.key())
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<SellingApproval> findById(UUID id) {
        return jdbc.sql("select * from selling_approval where id = :id").param("id", id).query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public Optional<SellingApproval> lockById(UUID id) {
        return jdbc.sql("select * from selling_approval where id = :id for update").param("id", id)
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Map<SellingScope, SellingApproval> find(String orgId, Collection<SellingScope> scopes) {
        Map<SellingScope, SellingApproval> out = new HashMap<>();
        if (scopes.isEmpty()) {
            return out;
        }
        List<String> keys = scopes.stream().map(s -> s.type().name() + ":" + s.key()).toList();
        jdbc.sql("select * from selling_approval where org_id = :o and (scope_type || ':' || scope_key) in (:keys)")
                .param("o", orgId).param("keys", keys).query((rs, n) -> map(rs)).list()
                .forEach(a -> out.put(a.scope(), a));
        return out;
    }

    @Override
    public List<SellingApproval> forOrg(String orgId) {
        return jdbc.sql("select * from selling_approval where org_id = :o order by scope_type, scope_label")
                .param("o", orgId).query((rs, n) -> map(rs)).list();
    }

    @Override
    public SellingApproval save(SellingApproval a) {
        int rows = jdbc.sql("""
                insert into selling_approval (id, org_id, scope_type, scope_key, scope_label, rule_id, status, reason,
                    reapply_after, approved_at, expires_at, documents, application_id, paused, pause_reason, paused_at,
                    listings_paused, warned_30_at, warned_14_at, warned_7_at, expired_notified_at, created_at,
                    updated_at, version)
                values (:id, :org, :type, :key, :label, :rule, :status, :reason, :reapply, :approvedAt, :expiresAt,
                    cast(:docs as jsonb), :app, :paused, :pauseReason, :pausedAt, :listingsPaused, :w30, :w14, :w7,
                    :expiredAt, :created, :updated, 0)
                on conflict (org_id, scope_type, scope_key) do update set scope_label = excluded.scope_label,
                    rule_id = excluded.rule_id, status = excluded.status, reason = excluded.reason,
                    reapply_after = excluded.reapply_after, approved_at = excluded.approved_at,
                    expires_at = excluded.expires_at, documents = excluded.documents,
                    application_id = excluded.application_id, paused = excluded.paused,
                    pause_reason = excluded.pause_reason, paused_at = excluded.paused_at,
                    listings_paused = excluded.listings_paused, warned_30_at = excluded.warned_30_at,
                    warned_14_at = excluded.warned_14_at, warned_7_at = excluded.warned_7_at,
                    expired_notified_at = excluded.expired_notified_at, updated_at = excluded.updated_at,
                    version = selling_approval.version + 1
                where selling_approval.version = :version
                """)
                .param("id", a.id()).param("org", a.orgId()).param("type", a.scope().type().name())
                .param("key", a.scope().key()).param("label", a.label()).param("rule", a.ruleId())
                .param("status", a.status().name()).param("reason", a.reason()).param("reapply", a.reapplyAfter())
                .param("approvedAt", Sql.ts(a.approvedAt())).param("expiresAt", Sql.ts(a.expiresAt()))
                .param("docs", json.write(a.documents())).param("app", a.applicationId())
                .param("paused", a.paused()).param("pauseReason", a.pauseReason()).param("pausedAt", Sql.ts(a.pausedAt()))
                .param("listingsPaused", a.listingsPaused()).param("w30", Sql.ts(a.warned30At()))
                .param("w14", Sql.ts(a.warned14At())).param("w7", Sql.ts(a.warned7At()))
                .param("expiredAt", Sql.ts(a.expiredNotifiedAt())).param("created", Sql.ts(a.createdAt()))
                .param("updated", Sql.ts(a.updatedAt())).param("version", a.version()).update();
        if (rows == 0) {
            throw new ConflictException("seller.concurrent-update", "This approval changed; reload and retry");
        }
        return find(a.orgId(), a.scope()).orElseThrow();
    }

    @Override
    public Page<SellingApproval> search(String orgId, SellingApprovalStatus status, SellingScopeType type, int page,
                                        int size) {
        List<String> where = new ArrayList<>(List.of("true"));
        Map<String, Object> params = new LinkedHashMap<>();
        if (orgId != null) {
            where.add("org_id = :org");
            params.put("org", orgId);
        }
        if (status != null) {
            where.add("status = :status");
            params.put("status", status.name());
        }
        if (type != null) {
            where.add("scope_type = :type");
            params.put("type", type.name());
        }
        String from = " from selling_approval where " + String.join(" and ", where);
        JdbcClient.StatementSpec count = jdbc.sql("select count(*)" + from);
        JdbcClient.StatementSpec rows = jdbc.sql("select *" + from
                + " order by expires_at nulls last, updated_at desc limit :limit offset :offset")
                .param("limit", size).param("offset", page * size);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            count = count.param(e.getKey(), e.getValue());
            rows = rows.param(e.getKey(), e.getValue());
        }
        return new Page<>(rows.query((rs, n) -> map(rs)).list(), page, size, count.query(Long.class).single());
    }

    @Override
    public List<UUID> lifecycleCandidates(Instant horizon, int limit) {
        return jdbc.sql("""
                select id from selling_approval
                where status in ('APPROVED', 'EXPIRED') and expires_at is not null and expires_at <= :h
                  and not (listings_paused and status = 'EXPIRED')
                order by expires_at limit :n
                """).param("h", Sql.ts(horizon)).param("n", limit).query((rs, n) -> rs.getObject(1, UUID.class)).list();
    }

    @Override
    public List<String> orgsWith(SellingScope scope, Instant now) {
        return jdbc.sql("""
                select org_id from selling_approval where scope_type = :t and scope_key = :k and status = 'APPROVED'
                  and not paused and (expires_at is null or expires_at >= :now)
                """).param("t", scope.type().name()).param("k", scope.key()).param("now", Sql.ts(now))
                .query(String.class).list();
    }

    private SellingApproval map(ResultSet rs) throws SQLException {
        Date reapply = rs.getDate("reapply_after");
        return new SellingApproval(rs.getObject("id", UUID.class), rs.getString("org_id"),
                new SellingScope(SellingScopeType.valueOf(rs.getString("scope_type")), rs.getString("scope_key")),
                rs.getString("scope_label"), rs.getObject("rule_id", UUID.class),
                SellingApprovalStatus.valueOf(rs.getString("status")), rs.getString("reason"),
                reapply == null ? null : reapply.toLocalDate(), Sql.instant(rs, "approved_at"),
                Sql.instant(rs, "expires_at"), json.read(rs.getString("documents"), DOCS),
                rs.getString("application_id"), rs.getBoolean("paused"), rs.getString("pause_reason"),
                Sql.instant(rs, "paused_at"), rs.getBoolean("listings_paused"), Sql.instant(rs, "warned_30_at"),
                Sql.instant(rs, "warned_14_at"), Sql.instant(rs, "warned_7_at"),
                Sql.instant(rs, "expired_notified_at"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
