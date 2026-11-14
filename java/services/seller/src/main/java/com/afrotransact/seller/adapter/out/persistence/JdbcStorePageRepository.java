package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.StorePage;
import com.afrotransact.seller.domain.StorePageAuditEntry;
import com.afrotransact.seller.port.StorePageRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStorePageRepository implements StorePageRepository {

    private final JdbcClient jdbc;

    JdbcStorePageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StorePage> find(String orgId) {
        return jdbc.sql("select org_id, draft::text as draft, published::text as published, version, published_version, "
                + "updated_at, updated_by, published_at from store_page where org_id = :org").param("org", orgId)
                .query(JdbcStorePageRepository::row).optional();
    }

    @Override
    public boolean insert(String orgId, String draft, Instant at, String actor) {
        return jdbc.sql("""
                insert into store_page (org_id, draft, version, updated_at, updated_by)
                values (:org, cast(:draft as jsonb), 1, :at, :actor) on conflict do nothing
                """).param("org", orgId).param("draft", draft).param("at", Sql.ts(at)).param("actor", actor)
                .update() == 1;
    }

    @Override
    public boolean updateDraft(String orgId, String draft, long expectedVersion, Instant at, String actor) {
        return jdbc.sql("""
                update store_page set draft = cast(:draft as jsonb), version = version + 1, updated_at = :at,
                       updated_by = :actor
                where org_id = :org and version = :v
                """).param("org", orgId).param("draft", draft).param("v", expectedVersion).param("at", Sql.ts(at))
                .param("actor", actor).update() == 1;
    }

    @Override
    public boolean publish(String orgId, long expectedVersion, Instant at, String actor) {
        return jdbc.sql("""
                update store_page set published = draft, published_version = version, published_at = :at,
                       updated_at = :at, updated_by = :actor
                where org_id = :org and version = :v
                """).param("org", orgId).param("v", expectedVersion).param("at", Sql.ts(at)).param("actor", actor)
                .update() == 1;
    }

    @Override
    public void unpublish(String orgId, Instant at, String actor) {
        jdbc.sql("update store_page set published = null, published_version = null, published_at = null, "
                + "updated_at = :at, updated_by = :actor where org_id = :org")
                .param("org", orgId).param("at", Sql.ts(at)).param("actor", actor).update();
    }

    @Override
    public void audit(String orgId, String action, String actor, long version, Instant at) {
        jdbc.sql("insert into store_page_audit (org_id, at, action, actor, version) values (:org, :at, :action, :actor, :v)")
                .param("org", orgId).param("at", Sql.ts(at)).param("action", action).param("actor", actor)
                .param("v", version).update();
    }

    @Override
    public List<StorePageAuditEntry> audit(String orgId, int limit) {
        return jdbc.sql("select at, action, actor, version from store_page_audit where org_id = :org "
                + "order by at desc, id desc limit :n").param("org", orgId).param("n", limit)
                .query((rs, n) -> new StorePageAuditEntry(Sql.instant(rs, "at"), rs.getString("action"),
                        rs.getString("actor"), rs.getLong("version"))).list();
    }

    private static StorePage row(ResultSet rs, int n) throws SQLException {
        long publishedVersion = rs.getLong("published_version");
        Long pv = rs.wasNull() ? null : publishedVersion;
        return new StorePage(rs.getString("org_id"), rs.getString("draft"), rs.getString("published"),
                rs.getLong("version"), pv, Sql.instant(rs, "updated_at"), rs.getString("updated_by"),
                rs.getTimestamp("published_at") == null ? null : Sql.instant(rs, "published_at"));
    }
}
