package com.afrotransact.trustops.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.trustops.domain.Sla;
import com.afrotransact.trustops.domain.SupportCase;
import com.afrotransact.trustops.domain.SupportCaseStatus;
import com.afrotransact.trustops.port.Page;
import com.afrotransact.trustops.port.SupportCaseRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSupportCaseRepository implements SupportCaseRepository {

    private final JdbcClient jdbc;

    JdbcSupportCaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextReference() {
        return "SC-" + jdbc.sql("select nextval('support_case_reference_seq')").query(Long.class).single();
    }

    @Override
    public SupportCase insert(SupportCase c) {
        jdbc.sql("""
                insert into support_case (id, reference, source, handoff_id, session_id, customer_id, reason, summary,
                    status, sla_started_at, due_at, opened_at, version)
                values (:id, :ref, :source, :handoff, :session, :customer, :reason, :summary, :status, :slaStart,
                    :dueAt, :openedAt, 0)
                """)
                .param("id", c.id())
                .param("ref", c.reference())
                .param("source", c.source())
                .param("handoff", c.handoffId())
                .param("session", c.sessionId())
                .param("customer", c.customerId())
                .param("reason", c.reason())
                .param("summary", c.summary())
                .param("status", c.status().name())
                .param("slaStart", Sql.ts(c.sla().startedAt()))
                .param("dueAt", Sql.ts(c.sla().dueAt()))
                .param("openedAt", Sql.ts(c.openedAt()))
                .update();
        return findById(c.id()).orElseThrow();
    }

    @Override
    public SupportCase update(SupportCase c) {
        int rows = jdbc.sql("""
                update support_case set status = :status, assignee = :assignee, resolved_at = :resolvedAt,
                    resolution = :resolution, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", c.status().name())
                .param("assignee", c.assignee())
                .param("resolvedAt", Sql.ts(c.resolvedAt()))
                .param("resolution", c.resolution())
                .param("id", c.id())
                .param("version", c.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("trustops.concurrent-update", "The support case changed; reload and retry");
        }
        return findById(c.id()).orElseThrow();
    }

    @Override
    public Optional<SupportCase> findById(UUID id) {
        return jdbc.sql("select * from support_case where id = :id").param("id", id)
                .query(JdbcSupportCaseRepository::map).optional();
    }

    @Override
    public boolean existsByHandoff(UUID handoffId) {
        return jdbc.sql("select count(*) from support_case where handoff_id = :id").param("id", handoffId)
                .query(Long.class).single() > 0;
    }

    @Override
    public Page<SupportCase> search(SupportCaseStatus status, int page, int size) {
        String where = status == null ? " where status <> 'RESOLVED'" : " where status = :status";
        JdbcClient.StatementSpec count = jdbc.sql("select count(*) from support_case" + where);
        JdbcClient.StatementSpec list = jdbc.sql("select * from support_case" + where
                + " order by due_at limit :limit offset :offset")
                .param("limit", size).param("offset", Sql.offset(page, size));
        if (status != null) {
            count = count.param("status", status.name());
            list = list.param("status", status.name());
        }
        List<SupportCase> items = list.query(JdbcSupportCaseRepository::map).list();
        return new Page<>(items, count.query(Long.class).single(), page, size);
    }

    private static SupportCase map(ResultSet rs, int row) throws SQLException {
        return new SupportCase(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("source"),
                rs.getObject("handoff_id", UUID.class), rs.getString("session_id"), rs.getString("customer_id"),
                rs.getString("reason"), rs.getString("summary"), SupportCaseStatus.valueOf(rs.getString("status")),
                rs.getString("assignee"), new Sla(Sql.instant(rs, "sla_started_at"), Sql.instant(rs, "due_at")),
                Sql.instant(rs, "opened_at"), Sql.instant(rs, "resolved_at"), rs.getString("resolution"),
                rs.getLong("version"));
    }
}
