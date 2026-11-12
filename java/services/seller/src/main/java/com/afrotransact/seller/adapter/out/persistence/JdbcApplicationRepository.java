package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationData;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.CaseQueue;
import com.afrotransact.seller.domain.DocumentAccess;
import com.afrotransact.seller.domain.OnboardingStep;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.TimelineEntry;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.CaseRow;
import com.afrotransact.seller.port.CaseSearch;
import com.afrotransact.seller.port.Page;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcApplicationRepository implements ApplicationRepository {

    /**
     * An applicant can have one application per kind (a seller applying for services too). The one the wizard works on
     * is the one still open to the applicant (draft or info requested), then one in review, then the newest.
     */
    private static final String WORKING_ORDER = " order by case when status in ('DRAFT', 'INFO_REQUESTED') then 0"
            + " when status in ('APPROVED', 'REJECTED') then 2 else 1 end, created_at desc limit 1";


    private final JdbcClient jdbc;
    private final JsonCodec json;
    private final RowMapper<Application> mapper;

    JdbcApplicationRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = this::map;
    }

    @Override
    public boolean insert(Application a) {
        return jdbc.sql("""
                insert into application (id, reference, org_id, applicant_user_id, kind, status, current_step, data,
                                         prefilled_fields, failed_checks, requested_items, submission_count,
                                         created_at, updated_at, version)
                values (:id, :ref, :org, :user, :kind, :status, :step, cast(:data as jsonb),
                        cast(:prefilled as jsonb), '[]', '[]', 0, :created, :updated, 0)
                on conflict (applicant_user_id, kind) do nothing
                """)
                .param("id", a.id()).param("ref", a.reference()).param("org", a.orgId())
                .param("user", a.applicantUserId()).param("kind", a.kind().name()).param("status", a.status().name())
                .param("step", a.currentStep().name()).param("data", json.write(a.data()))
                .param("prefilled", json.write(a.prefilledFields().stream().sorted().toList()))
                .param("created", Sql.ts(a.createdAt())).param("updated", Sql.ts(a.updatedAt()))
                .update() == 1;
    }

    @Override
    public Application update(Application a) {
        int rows = jdbc.sql("""
                update application set status = :status, current_step = :step, data = cast(:data as jsonb),
                       prefilled_fields = cast(:prefilled as jsonb), reviewer_id = :reviewer, reviewed_at = :reviewedAt,
                       final_approver_id = :approver, decided_at = :decidedAt,
                       failed_checks = cast(:checks as jsonb), requested_items = cast(:items as jsonb),
                       reason_code = :reason, submission_count = :submissions, submitted_at = :submittedAt,
                       updated_at = :updated, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", a.status().name()).param("step", a.currentStep().name())
                .param("data", json.write(a.data()))
                .param("prefilled", json.write(a.prefilledFields().stream().sorted().toList()))
                .param("reviewer", a.reviewerId()).param("reviewedAt", Sql.ts(a.reviewedAt()))
                .param("approver", a.finalApproverId()).param("decidedAt", Sql.ts(a.decidedAt()))
                .param("checks", json.write(a.failedChecks())).param("items", json.write(a.requestedItems()))
                .param("reason", a.reasonCode()).param("submissions", a.submissionCount())
                .param("submittedAt", Sql.ts(a.submittedAt())).param("updated", Sql.ts(a.updatedAt()))
                .param("id", a.id()).param("version", a.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("seller.concurrent-update", "Application changed; reload and retry");
        }
        return findById(a.id()).orElseThrow();
    }

    @Override
    public Optional<Application> findById(String id) {
        return jdbc.sql("select * from application where id = :id").param("id", id).query(mapper).optional();
    }

    @Override
    public Optional<Application> findByApplicant(String userId) {
        return jdbc.sql("select * from application where applicant_user_id = :u" + WORKING_ORDER).param("u", userId)
                .query(mapper).optional();
    }

    @Override
    public Optional<Application> findByApplicant(String userId, OrgKind kind) {
        return jdbc.sql("select * from application where applicant_user_id = :u and kind = :k")
                .param("u", userId).param("k", kind.name()).query(mapper).optional();
    }

    @Override
    public Optional<Application> findByOrg(String orgId) {
        return jdbc.sql("select * from application where org_id = :o").param("o", orgId).query(mapper).optional();
    }

    @Override
    public Optional<Application> lockById(String id) {
        return jdbc.sql("select * from application where id = :id for update").param("id", id).query(mapper)
                .optional();
    }

    @Override
    public Optional<Application> lockByApplicant(String userId) {
        return jdbc.sql("select * from application where applicant_user_id = :u" + WORKING_ORDER + " for update")
                .param("u", userId).query(mapper).optional();
    }

    @Override
    public Optional<Application> lockByApplicant(String userId, OrgKind kind) {
        return jdbc.sql("select * from application where applicant_user_id = :u and kind = :k for update")
                .param("u", userId).param("k", kind.name()).query(mapper).optional();
    }

    @Override
    public Page<Application> search(List<ApplicationStatus> statuses, OrgKind kind, int page, int size) {
        List<String> names = statuses.stream().map(Enum::name).toList();
        String kindFilter = kind == null ? "" : " and kind = :kind";
        JdbcClient.StatementSpec count = jdbc.sql("select count(*) from application where status in (:s)"
                + kindFilter).param("s", names);
        JdbcClient.StatementSpec rows = jdbc.sql("select * from application where status in (:s)" + kindFilter
                + " order by submitted_at nulls last, created_at limit :limit offset :offset")
                .param("s", names).param("limit", size).param("offset", page * size);
        if (kind != null) {
            count = count.param("kind", kind.name());
            rows = rows.param("kind", kind.name());
        }
        return new Page<>(rows.query(mapper).list(), page, size, count.query(Long.class).single());
    }

    private static final String STAGE = "(case a.status when 'PENDING_REVIEW' then 'REVIEW' when 'FLAGGED' then 'APPROVAL' "
            + "when 'AWAITING_FINAL_APPROVAL' then 'APPROVAL' end)";
    private static final String ENTERED = "coalesce(c.stage_entered_at, a.updated_at)";

    @Override
    public Page<CaseRow> searchCases(CaseSearch q) {
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
                where.add("a.status <> 'DRAFT'");
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
        if (q.kind() != null) {
            where.add("a.kind = :kind");
            params.put("kind", q.kind().name());
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
        String from = " from application a left join application_case c on c.application_id = a.id where "
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
        List<CaseRow> items = rows.query((rs, n) -> new CaseRow(map(rs, n), JdbcApplicationCaseRepository.map(rs,
                "c_", json))).list();
        return new Page<>(items, q.page(), q.size(), count.query(Long.class).single());
    }

    @Override
    public String nextReference() {
        return "APP-" + jdbc.sql("select nextval('application_reference_seq')").query(Long.class).single();
    }

    @Override
    public void appendTimeline(TimelineEntry t) {
        jdbc.sql("""
                insert into application_event (id, application_id, action, from_status, to_status, actor_id,
                                               reason_code, note, items, occurred_at, actor_name, request_key)
                values (:id, :app, :action, :from, :to, :actor, :reason, :note, cast(:items as jsonb), :at,
                        :actorName, :requestKey)
                """)
                .param("id", t.id()).param("app", t.applicationId()).param("action", t.action().name())
                .param("from", t.fromStatus() == null ? null : t.fromStatus().name())
                .param("to", t.toStatus().name()).param("actor", t.actorId()).param("reason", t.reasonCode())
                .param("note", t.note()).param("items", json.write(t.items())).param("at", Sql.ts(t.occurredAt()))
                .param("actorName", t.actorName()).param("requestKey", t.requestKey())
                .update();
    }

    @Override
    public List<TimelineEntry> timeline(String applicationId) {
        return jdbc.sql("select * from application_event where application_id = :a order by occurred_at, id")
                .param("a", applicationId)
                .query(this::timelineEntry)
                .list();
    }

    @Override
    public Optional<TimelineEntry> timelineEntry(String applicationId, String requestKey) {
        return jdbc.sql("select * from application_event where application_id = :a and request_key = :k")
                .param("a", applicationId).param("k", requestKey)
                .query(this::timelineEntry)
                .optional();
    }

    @Override
    public void logAccess(DocumentAccess a) {
        jdbc.sql("""
                insert into document_access_log (id, application_id, asset_id, kind, viewer_id, accessed_at)
                values (:id, :app, :asset, :kind, :viewer, :at)
                """)
                .param("id", a.id()).param("app", a.applicationId()).param("asset", a.assetId())
                .param("kind", a.kind()).param("viewer", a.viewerId()).param("at", Sql.ts(a.accessedAt()))
                .update();
    }

    @Override
    public List<DocumentAccess> accessLog(String applicationId) {
        return jdbc.sql("select * from document_access_log where application_id = :a order by accessed_at")
                .param("a", applicationId)
                .query((rs, n) -> new DocumentAccess(rs.getObject("id", UUID.class), rs.getString("application_id"),
                        rs.getString("asset_id"), rs.getString("kind"), rs.getString("viewer_id"),
                        Sql.instant(rs, "accessed_at")))
                .list();
    }

    private TimelineEntry timelineEntry(ResultSet rs, int n) throws SQLException {
        String from = rs.getString("from_status");
        return new TimelineEntry(rs.getObject("id", UUID.class), rs.getString("application_id"),
                ReviewAction.valueOf(rs.getString("action")), from == null ? null : ApplicationStatus.valueOf(from),
                ApplicationStatus.valueOf(rs.getString("to_status")), rs.getString("actor_id"),
                rs.getString("reason_code"), rs.getString("note"), json.strings(rs.getString("items")),
                Sql.instant(rs, "occurred_at"), rs.getString("actor_name"), rs.getString("request_key"));
    }

    private Application map(ResultSet rs, int n) throws SQLException {
        return new Application(rs.getString("id"), rs.getString("reference"), rs.getString("org_id"),
                rs.getString("applicant_user_id"), OrgKind.valueOf(rs.getString("kind")),
                ApplicationStatus.valueOf(rs.getString("status")), OnboardingStep.valueOf(rs.getString("current_step")),
                json.read(rs.getString("data"), ApplicationData.class),
                new HashSet<>(json.strings(rs.getString("prefilled_fields"))), rs.getString("reviewer_id"),
                Sql.instant(rs, "reviewed_at"), rs.getString("final_approver_id"), Sql.instant(rs, "decided_at"),
                json.strings(rs.getString("failed_checks")), json.strings(rs.getString("requested_items")),
                rs.getString("reason_code"), rs.getInt("submission_count"), Sql.instant(rs, "submitted_at"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
