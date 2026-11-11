package com.afrotransact.seller.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.CaseQueue;
import com.afrotransact.seller.domain.DocumentAccess;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.SellingApplication;
import com.afrotransact.seller.domain.SellingDocument;
import com.afrotransact.seller.domain.SellingEvaluator.Filed;
import com.afrotransact.seller.domain.SellingScope;
import com.afrotransact.seller.domain.SellingScopeType;
import com.afrotransact.seller.domain.TimelineEntry;
import com.afrotransact.seller.port.CaseSearch;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.SellingApplicationRepository;
import com.afrotransact.seller.port.SellingCaseRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcSellingApplicationRepository implements SellingApplicationRepository {

    private static final TypeReference<List<SellingDocument>> DOCS = new TypeReference<>() { };
    private static final String STAGE = "(case a.status when 'PENDING_REVIEW' then 'REVIEW' when 'FLAGGED' then "
            + "'APPROVAL' when 'AWAITING_FINAL_APPROVAL' then 'APPROVAL' end)";
    private static final String ENTERED = "coalesce(c.stage_entered_at, a.updated_at)";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcSellingApplicationRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public String nextReference() {
        return "SA-" + jdbc.sql("select nextval('selling_application_reference_seq')").query(Long.class).single();
    }

    @Override
    public boolean insert(SellingApplication a) {
        return jdbc.sql("""
                    insert into selling_application (id, reference, org_id, applicant_user_id, applicant_name,
                        scope_type, scope_key, scope_label, rule_id, kind, source, status, documents, note,
                        submission_count, submitted_at, created_at, updated_at, version)
                    values (:id, :ref, :org, :user, :userName, :type, :key, :label, :rule, :kind, :source, :status,
                        cast(:docs as jsonb), :note, :submissions, :submittedAt, :created, :updated, 0)
                    on conflict (org_id, scope_type, scope_key) where status in ('PENDING_REVIEW', 'FLAGGED',
                        'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED') do nothing
                    """)
                    .param("id", a.id()).param("ref", a.reference()).param("org", a.orgId())
                    .param("user", a.applicantUserId()).param("userName", a.applicantName())
                    .param("type", a.scope().type().name()).param("key", a.scope().key())
                    .param("label", a.scopeLabel()).param("rule", a.ruleId()).param("kind", a.kind())
                    .param("source", a.source()).param("status", a.status().name())
                    .param("docs", json.write(a.documents())).param("note", a.note())
                    .param("submissions", a.submissionCount()).param("submittedAt", Sql.ts(a.submittedAt()))
                    .param("created", Sql.ts(a.createdAt())).param("updated", Sql.ts(a.updatedAt())).update() == 1;
    }

    @Override
    public SellingApplication update(SellingApplication a) {
        int rows = jdbc.sql("""
                update selling_application set status = :status, documents = cast(:docs as jsonb), note = :note,
                    reviewer_id = :reviewer, reviewed_at = :reviewedAt, final_approver_id = :approver,
                    decided_at = :decidedAt, failed_checks = cast(:checks as jsonb),
                    requested_items = cast(:items as jsonb), reason_code = :reason, decision_note = :decisionNote,
                    disqualified = :disqualified, reapply_after = :reapply, submission_count = :submissions,
                    submitted_at = :submittedAt, updated_at = :updated, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", a.status().name()).param("docs", json.write(a.documents()))
                .param("note", a.note()).param("reviewer", a.reviewerId()).param("reviewedAt", Sql.ts(a.reviewedAt()))
                .param("approver", a.finalApproverId()).param("decidedAt", Sql.ts(a.decidedAt()))
                .param("checks", json.write(a.failedChecks())).param("items", json.write(a.requestedItems()))
                .param("reason", a.reasonCode()).param("decisionNote", a.decisionNote())
                .param("disqualified", a.disqualified()).param("reapply", a.reapplyAfter())
                .param("submissions", a.submissionCount()).param("submittedAt", Sql.ts(a.submittedAt()))
                .param("updated", Sql.ts(a.updatedAt())).param("id", a.id()).param("version", a.version()).update();
        if (rows == 0) {
            throw new ConflictException("seller.concurrent-update", "Application changed; reload and retry");
        }
        return findById(a.id()).orElseThrow();
    }

    @Override
    public Optional<SellingApplication> findById(String id) {
        return jdbc.sql("select * from selling_application where id = :id").param("id", id)
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<SellingApplication> lockById(String id) {
        return jdbc.sql("select * from selling_application where id = :id for update").param("id", id)
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<SellingApplication> findOpen(String orgId, SellingScope scope) {
        return jdbc.sql("""
                select * from selling_application where org_id = :o and scope_type = :t and scope_key = :k
                  and status in ('PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED')
                """).param("o", orgId).param("t", scope.type().name()).param("k", scope.key())
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public List<SellingApplication> forOrg(String orgId) {
        return jdbc.sql("select * from selling_application where org_id = :o order by created_at desc")
                .param("o", orgId).query((rs, n) -> map(rs)).list();
    }

    @Override
    public Map<SellingScope, Filed> latest(String orgId, Collection<SellingScope> scopes) {
        Map<SellingScope, Filed> out = new HashMap<>();
        if (scopes.isEmpty()) {
            return out;
        }
        List<String> keys = scopes.stream().map(s -> s.type().name() + ":" + s.key()).toList();
        jdbc.sql("""
                select distinct on (scope_type, scope_key) id, scope_type, scope_key, status from selling_application
                where org_id = :o and (scope_type || ':' || scope_key) in (:keys)
                order by scope_type, scope_key, created_at desc
                """).param("o", orgId).param("keys", keys).query((rs, n) -> {
                    out.put(new SellingScope(SellingScopeType.valueOf(rs.getString("scope_type")),
                            rs.getString("scope_key")), new Filed(rs.getString("id"),
                            ApplicationStatus.valueOf(rs.getString("status"))));
                    return null;
                }).list();
        return out;
    }

    @Override
    public Page<SellingCaseRow> searchCases(CaseSearch q) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        params.put("me", q.me() == null ? "" : q.me());
        params.put("canReview", q.canReview());
        params.put("canApprove", q.canApprove());
        CaseQueue queue = q.queue();
        if (queue == null || queue == CaseQueue.ALL) {
            List<ApplicationStatus> statuses = q.statuses();
            if (statuses.isEmpty() && queue == null) {
                statuses = List.of(ApplicationStatus.PENDING_REVIEW, ApplicationStatus.FLAGGED,
                        ApplicationStatus.AWAITING_FINAL_APPROVAL);
            }
            if (statuses.isEmpty()) {
                where.add("true");
            } else {
                where.add("a.status in (:statuses)");
                params.put("statuses", statuses.stream().map(Enum::name).toList());
            }
        } else {
            switch (queue) {
                case MY -> where.add("(c.assignee_id = :me or (c.assignee_id is null and a.applicant_user_id <> :me and "
                        + "((" + STAGE + " = 'REVIEW' and :canReview) or (" + STAGE + " = 'APPROVAL' and :canApprove "
                        + "and coalesce(a.reviewer_id, '') <> :me)))) and " + STAGE + " is not null");
                case AWAITING_REVIEW -> where.add(STAGE + " = 'REVIEW'");
                case AWAITING_APPROVAL -> where.add(STAGE + " = 'APPROVAL'");
                case ESCALATED -> where.add(STAGE + " is not null and coalesce(c.escalation_level, 0) > 0");
                case RETURNED -> where.add("a.status = 'INFO_REQUESTED'");
                default -> { }
            }
        }
        if (q.stage() != null) {
            where.add(STAGE + " = :stage");
            params.put("stage", q.stage().name());
        }
        if (q.assigneeId() != null) {
            where.add("c.assignee_id = :assignee");
            params.put("assignee", q.assigneeId());
        }
        if (q.unassigned()) {
            where.add(STAGE + " is not null and c.assignee_id is null");
        }
        if (q.escalated()) {
            where.add(STAGE + " is not null and coalesce(c.escalation_level, 0) > 0");
        }
        if (q.overdue()) {
            where.add("((" + STAGE + " = 'REVIEW' and " + ENTERED + " <= :rc) or (" + STAGE + " = 'APPROVAL' and "
                    + ENTERED + " <= :ac))");
            params.put("rc", Sql.ts(q.reviewCutoff()));
            params.put("ac", Sql.ts(q.approvalCutoff()));
        }
        String from = " from selling_application a left join selling_case c on c.application_id = a.id where "
                + String.join(" and ", where);
        JdbcClient.StatementSpec count = jdbc.sql("select count(*)" + from);
        JdbcClient.StatementSpec rows = jdbc.sql("select a.*, c.application_id as c_id, c.stage as c_stage, "
                + "c.assignee_id as c_assignee_id, c.assignee_name as c_assignee_name, "
                + "c.assigned_by_id as c_assigned_by_id, c.assigned_by_name as c_assigned_by_name, "
                + "c.assigned_at as c_assigned_at, c.stage_entered_at as c_stage_entered_at, "
                + "c.escalation_level as c_escalation_level, c.escalated_at as c_escalated_at, "
                + "c.escalation_reason as c_escalation_reason, c.escalation_target_ids as c_escalation_target_ids, "
                + "c.escalation_target_names as c_escalation_target_names, c.returned_by_id as c_returned_by_id, "
                + "c.returned_by_name as c_returned_by_name, c.returned_at as c_returned_at, "
                + "c.return_note as c_return_note, c.return_count as c_return_count, c.updated_at as c_updated_at, "
                + "c.version as c_version" + from + " order by " + ENTERED + ", a.created_at limit :limit offset :offset");
        for (Map.Entry<String, Object> e : params.entrySet()) {
            count = count.param(e.getKey(), e.getValue());
            rows = rows.param(e.getKey(), e.getValue());
        }
        rows = rows.param("limit", q.size()).param("offset", q.page() * q.size());
        List<SellingCaseRow> items = rows.query((rs, n) -> new SellingCaseRow(map(rs),
                JdbcApplicationCaseRepository.map(rs, "c_", json))).list();
        return new Page<>(items, q.page(), q.size(), count.query(Long.class).single());
    }

    @Override
    public void appendTimeline(TimelineEntry t) {
        jdbc.sql("""
                insert into selling_application_event (id, application_id, action, from_status, to_status, actor_id,
                    actor_name, reason_code, note, items, request_key, occurred_at)
                values (:id, :app, :action, :from, :to, :actor, :actorName, :reason, :note, cast(:items as jsonb),
                    :requestKey, :at)
                """)
                .param("id", t.id()).param("app", t.applicationId()).param("action", t.action().name())
                .param("from", t.fromStatus() == null ? null : t.fromStatus().name())
                .param("to", t.toStatus().name()).param("actor", t.actorId()).param("actorName", t.actorName())
                .param("reason", t.reasonCode()).param("note", t.note()).param("items", json.write(t.items()))
                .param("requestKey", t.requestKey()).param("at", Sql.ts(t.occurredAt())).update();
    }

    @Override
    public List<TimelineEntry> timeline(String applicationId) {
        return jdbc.sql("select * from selling_application_event where application_id = :a order by occurred_at, id")
                .param("a", applicationId).query(this::timelineEntry).list();
    }

    @Override
    public Optional<TimelineEntry> timelineEntry(String applicationId, String requestKey) {
        return jdbc.sql("select * from selling_application_event where application_id = :a and request_key = :k")
                .param("a", applicationId).param("k", requestKey).query(this::timelineEntry).optional();
    }

    @Override
    public void logAccess(DocumentAccess a) {
        jdbc.sql("""
                insert into selling_document_access (id, application_id, asset_id, kind, viewer_id, viewed_at)
                values (:id, :app, :asset, :kind, :viewer, :at)
                """)
                .param("id", a.id()).param("app", a.applicationId()).param("asset", a.assetId() == null ? "-" : a.assetId())
                .param("kind", a.kind()).param("viewer", a.viewerId()).param("at", Sql.ts(a.accessedAt())).update();
    }

    @Override
    public List<DocumentAccess> accessLog(String applicationId) {
        return jdbc.sql("select * from selling_document_access where application_id = :a order by viewed_at")
                .param("a", applicationId).query((rs, n) -> new DocumentAccess(rs.getObject("id", UUID.class),
                        rs.getString("application_id"), rs.getString("asset_id"), rs.getString("kind"),
                        rs.getString("viewer_id"), Sql.instant(rs, "viewed_at"))).list();
    }

    private TimelineEntry timelineEntry(ResultSet rs, int n) throws SQLException {
        String from = rs.getString("from_status");
        return new TimelineEntry(rs.getObject("id", UUID.class), rs.getString("application_id"),
                ReviewAction.valueOf(rs.getString("action")), from == null ? null : ApplicationStatus.valueOf(from),
                ApplicationStatus.valueOf(rs.getString("to_status")), rs.getString("actor_id"),
                rs.getString("reason_code"), rs.getString("note"), json.strings(rs.getString("items")),
                Sql.instant(rs, "occurred_at"), rs.getString("actor_name"), rs.getString("request_key"));
    }

    private SellingApplication map(ResultSet rs) throws SQLException {
        Date reapply = rs.getDate("reapply_after");
        return new SellingApplication(rs.getString("id"), rs.getString("reference"), rs.getString("org_id"),
                rs.getString("applicant_user_id"), rs.getString("applicant_name"),
                new SellingScope(SellingScopeType.valueOf(rs.getString("scope_type")), rs.getString("scope_key")),
                rs.getString("scope_label"), rs.getObject("rule_id", UUID.class), rs.getString("kind"),
                rs.getString("source"), ApplicationStatus.valueOf(rs.getString("status")),
                json.read(rs.getString("documents"), DOCS), rs.getString("note"), rs.getString("reviewer_id"),
                Sql.instant(rs, "reviewed_at"), rs.getString("final_approver_id"), Sql.instant(rs, "decided_at"),
                json.strings(rs.getString("failed_checks")), json.strings(rs.getString("requested_items")),
                rs.getString("reason_code"), rs.getString("decision_note"), rs.getBoolean("disqualified"),
                reapply == null ? null : reapply.toLocalDate(), rs.getInt("submission_count"),
                Sql.instant(rs, "submitted_at"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
