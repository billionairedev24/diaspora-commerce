package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingEnforcementRun;
import com.afrotransact.catalog.port.ListingEnforcementRunRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcListingEnforcementRunRepository implements ListingEnforcementRunRepository {

    private static final String SELECT = """
            select event_id, org_id, action, reason, status, cursor_product_id, processed, pages, started_at,
                   updated_at, finished_at, version
            from listing_enforcement_run
            """;

    private final JdbcClient jdbc;

    JdbcListingEnforcementRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ListingEnforcementRun r) {
        jdbc.sql("""
                insert into listing_enforcement_run (event_id, org_id, action, reason, status, cursor_product_id,
                    processed, pages, started_at, updated_at, finished_at, version)
                values (:id, :org, :action, :reason, :status, :cursor, :processed, :pages, :startedAt, :updatedAt,
                    :finishedAt, 0)
                """)
                .param("id", r.eventId()).param("org", r.orgId()).param("action", r.action().name())
                .param("reason", r.reason()).param("status", r.status().name()).param("cursor", r.cursor())
                .param("processed", r.processed()).param("pages", r.pages())
                .param("startedAt", Timestamp.from(r.startedAt())).param("updatedAt", Timestamp.from(r.updatedAt()))
                .param("finishedAt", ts(r.finishedAt()))
                .update();
    }

    @Override
    public Optional<ListingEnforcementRun> findById(UUID eventId) {
        return jdbc.sql(SELECT + " where event_id = :id").param("id", eventId).query(this::map).optional();
    }

    @Override
    public Optional<ListingEnforcementRun> lock(UUID eventId) {
        return jdbc.sql(SELECT + " where event_id = :id for update").param("id", eventId).query(this::map).optional();
    }

    @Override
    public void update(ListingEnforcementRun r) {
        int rows = jdbc.sql("""
                update listing_enforcement_run set status = :status, cursor_product_id = :cursor,
                    processed = :processed, pages = :pages, updated_at = :updatedAt, finished_at = :finishedAt,
                    version = version + 1
                where event_id = :id and version = :version
                """)
                .param("status", r.status().name()).param("cursor", r.cursor()).param("processed", r.processed())
                .param("pages", r.pages()).param("updatedAt", Timestamp.from(r.updatedAt()))
                .param("finishedAt", ts(r.finishedAt())).param("id", r.eventId()).param("version", r.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("catalog.concurrent-update",
                    "Enforcement run " + r.eventId() + " was changed concurrently");
        }
    }

    @Override
    public int supersedeRunning(String orgId, Instant now) {
        return jdbc.sql("""
                update listing_enforcement_run set status = 'SUPERSEDED', updated_at = :now, finished_at = :now,
                    version = version + 1
                where org_id = :org and status = 'RUNNING'
                """)
                .param("org", orgId).param("now", Timestamp.from(now)).update();
    }

    @Override
    public List<UUID> stalled(Instant before, int limit) {
        return jdbc.sql("""
                select event_id from listing_enforcement_run
                where status = 'RUNNING' and updated_at < :before order by updated_at limit :limit
                """)
                .param("before", Timestamp.from(before)).param("limit", limit)
                .query((rs, n) -> rs.getObject("event_id", UUID.class)).list();
    }

    private ListingEnforcementRun map(ResultSet rs, int n) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new ListingEnforcementRun(rs.getObject("event_id", UUID.class), rs.getString("org_id"),
                ListingEnforcementRun.Action.valueOf(rs.getString("action")), rs.getString("reason"),
                ListingEnforcementRun.Status.valueOf(rs.getString("status")),
                rs.getObject("cursor_product_id", UUID.class), rs.getInt("processed"), rs.getInt("pages"),
                rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                finished == null ? null : finished.toInstant(), rs.getLong("version"));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
