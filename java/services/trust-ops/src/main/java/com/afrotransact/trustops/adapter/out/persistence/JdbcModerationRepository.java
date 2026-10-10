package com.afrotransact.trustops.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.trustops.domain.ModerationItem;
import com.afrotransact.trustops.domain.ModerationPriority;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.ModerationStatus;
import com.afrotransact.trustops.domain.ProductSubmission;
import com.afrotransact.trustops.domain.Sla;
import com.afrotransact.trustops.domain.TriageStatus;
import com.afrotransact.trustops.port.ModerationRepository;
import com.afrotransact.trustops.port.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcModerationRepository implements ModerationRepository {

    private static final String OPEN = "('PENDING', 'ESCALATED')";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcModerationRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public ModerationItem insert(ModerationItem i) {
        ProductSubmission p = i.product();
        jdbc.sql("""
                insert into moderation_item (id, product_id, atin, seller_org_id, owner_kind, title, product_type_code,
                    status, priority, triage_status, triage, enqueued_at, sla_started_at, due_at, version)
                values (:id, :productId, :atin, :org, :ownerKind, :title, :type, :status, :priority, :triageStatus,
                    cast(:triage as jsonb), :enqueuedAt, :slaStart, :dueAt, 0)
                """)
                .param("id", i.id())
                .param("productId", p.productId())
                .param("atin", p.atin())
                .param("org", p.sellerOrgId())
                .param("ownerKind", p.ownerKind())
                .param("title", p.title())
                .param("type", p.productTypeCode())
                .param("status", i.status().name())
                .param("priority", i.priority().name())
                .param("triageStatus", i.triageStatus().name())
                .param("triage", json.write(i.triage()))
                .param("enqueuedAt", Sql.ts(i.enqueuedAt()))
                .param("slaStart", Sql.ts(i.sla().startedAt()))
                .param("dueAt", Sql.ts(i.sla().dueAt()))
                .update();
        return findById(i.id()).orElseThrow();
    }

    @Override
    public ModerationItem update(ModerationItem i) {
        int rows = jdbc.sql("""
                update moderation_item set status = :status, priority = :priority, triage_status = :triageStatus,
                    triage = cast(:triage as jsonb), sla_started_at = :slaStart, due_at = :dueAt, assignee = :assignee,
                    assigned_at = :assignedAt, escalated = :escalated, escalation_reason = :escReason,
                    escalated_at = :escAt, decided_by = :decidedBy, decided_at = :decidedAt,
                    reason_code = :reasonCode, decision_note = :note, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", i.status().name())
                .param("priority", i.priority().name())
                .param("triageStatus", i.triageStatus().name())
                .param("triage", json.write(i.triage()))
                .param("slaStart", Sql.ts(i.sla().startedAt()))
                .param("dueAt", Sql.ts(i.sla().dueAt()))
                .param("assignee", i.assignee())
                .param("assignedAt", Sql.ts(i.assignedAt()))
                .param("escalated", i.escalated())
                .param("escReason", i.escalationReason())
                .param("escAt", Sql.ts(i.escalatedAt()))
                .param("decidedBy", i.decidedBy())
                .param("decidedAt", Sql.ts(i.decidedAt()))
                .param("reasonCode", i.reasonCode() == null ? null : i.reasonCode().name())
                .param("note", i.decisionNote())
                .param("id", i.id())
                .param("version", i.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("trustops.concurrent-update", "The listing changed; reload and retry");
        }
        return findById(i.id()).orElseThrow();
    }

    @Override
    public Optional<ModerationItem> findById(UUID id) {
        return jdbc.sql("select * from moderation_item where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<ModerationItem> findOpenByProduct(UUID productId) {
        return jdbc.sql("select * from moderation_item where product_id = :p and status in " + OPEN)
                .param("p", productId).query(this::map).optional();
    }

    @Override
    public Page<ModerationItem> search(ModerationStatus status, String assignee, int page, int size) {
        String where = (status == null ? " where status in " + OPEN : " where status = :status")
                + (assignee == null ? "" : " and assignee = :assignee");
        JdbcClient.StatementSpec count = jdbc.sql("select count(*) from moderation_item" + where);
        JdbcClient.StatementSpec list = jdbc.sql("select * from moderation_item" + where
                + " order by due_at, enqueued_at limit :limit offset :offset")
                .param("limit", size).param("offset", Sql.offset(page, size));
        if (status != null) {
            count = count.param("status", status.name());
            list = list.param("status", status.name());
        }
        if (assignee != null) {
            count = count.param("assignee", assignee);
            list = list.param("assignee", assignee);
        }
        return new Page<>(list.query(this::map).list(), count.query(Long.class).single(), page, size);
    }

    @Override
    public List<ModerationItem> findBreachedPending(Instant now, int limit) {
        return jdbc.sql("select * from moderation_item where status = 'PENDING' and due_at <= :now order by due_at "
                        + "limit :limit for update skip locked")
                .param("now", Sql.ts(now)).param("limit", limit).query(this::map).list();
    }

    private ModerationItem map(ResultSet rs, int row) throws SQLException {
        String reason = rs.getString("reason_code");
        return new ModerationItem(rs.getObject("id", UUID.class),
                new ProductSubmission(rs.getObject("product_id", UUID.class), rs.getString("atin"),
                        rs.getString("seller_org_id"), rs.getString("owner_kind"), rs.getString("title"),
                        rs.getString("product_type_code")),
                ModerationStatus.valueOf(rs.getString("status")), ModerationPriority.valueOf(rs.getString("priority")),
                TriageStatus.valueOf(rs.getString("triage_status")), json.triage(rs.getString("triage")),
                Sql.instant(rs, "enqueued_at"), new Sla(Sql.instant(rs, "sla_started_at"), Sql.instant(rs, "due_at")),
                rs.getString("assignee"), Sql.instant(rs, "assigned_at"), rs.getBoolean("escalated"),
                rs.getString("escalation_reason"), Sql.instant(rs, "escalated_at"), rs.getString("decided_by"),
                Sql.instant(rs, "decided_at"), reason == null ? null : ModerationReasonCode.valueOf(reason),
                rs.getString("decision_note"), rs.getLong("version"));
    }
}
