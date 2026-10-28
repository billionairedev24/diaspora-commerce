package com.afrotransact.trustops.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.trustops.domain.ListingFlagSource;
import com.afrotransact.trustops.domain.ListingReport;
import com.afrotransact.trustops.domain.ModerationItem;
import com.afrotransact.trustops.domain.ModerationItemType;
import com.afrotransact.trustops.domain.ModerationPriority;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.ModerationStatus;
import com.afrotransact.trustops.domain.ProductSubmission;
import com.afrotransact.trustops.domain.ReviewSubmission;
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
                    status, priority, triage_status, triage, enqueued_at, sla_started_at, due_at, item_type, flag_id,
                    report_source, report_reason, report_details, reporter_id, report_risk, report_count, booking_id,
                    service_id, service_name, customer_id, reviewer_name, rating, review_text, review_created_at,
                    review_title, review_photos, review_flags, version)
                values (:id, :productId, :atin, :org, :ownerKind, :title, :type, :status, :priority, :triageStatus,
                    cast(:triage as jsonb), :enqueuedAt, :slaStart, :dueAt, :itemType, :flagId, :reportSource,
                    :reportReason, :reportDetails, :reporterId, :reportRisk, :reportCount, :bookingId, :serviceId,
                    :serviceName, :customerId, :reviewerName, :rating, :reviewText, :reviewCreatedAt, :reviewTitle,
                    :reviewPhotos, :reviewFlags, 0)
                """)
                .params(reportParams(i))
                .params(reviewParams(i.review()))
                .param("id", i.id())
                .param("productId", p == null ? null : p.productId())
                .param("atin", p != null ? p.atin() : i.review() == null ? null : i.review().atin())
                .param("org", i.orgId())
                .param("ownerKind", p == null ? null : p.ownerKind())
                .param("title", i.title())
                .param("type", p == null ? null : p.productTypeCode())
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
                    reason_code = :reasonCode, decision_note = :note, report_count = :reportCount,
                    version = version + 1
                where id = :id and version = :version
                """)
                .param("reportCount", reports(i))
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
    public Optional<ModerationItem> findOpenByReview(UUID bookingId) {
        return jdbc.sql("select * from moderation_item where booking_id = :b and status in " + OPEN)
                .param("b", bookingId).query(this::map).optional();
    }

    @Override
    public Page<ModerationItem> search(ModerationStatus status, String assignee, ModerationItemType type, int page,
                                       int size) {
        String where = (status == null ? " where status in " + OPEN : " where status = :status")
                + (assignee == null ? "" : " and assignee = :assignee")
                + (type == null ? "" : " and item_type = :itemType");
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
        if (type != null) {
            count = count.param("itemType", type.name());
            list = list.param("itemType", type.name());
        }
        return new Page<>(list.query(this::map).list(), count.query(Long.class).single(), page, size);
    }

    @Override
    public List<ModerationItem> findBreachedPending(Instant now, int limit) {
        return jdbc.sql("select * from moderation_item where status = 'PENDING' and due_at <= :now order by due_at "
                        + "limit :limit for update skip locked")
                .param("now", Sql.ts(now)).param("limit", limit).query(this::map).list();
    }

    /** Reports counted on the item: a listing's flags, or the reports on a product review. */
    private static int reports(ModerationItem i) {
        if (i.report() != null) {
            return i.report().reports();
        }
        return i.review() == null ? 0 : i.review().reports();
    }

    private static Map<String, Object> reportParams(ModerationItem i) {
        ListingReport r = i.report();
        Map<String, Object> p = new HashMap<>();
        p.put("itemType", i.type().name());
        p.put("flagId", r == null ? null : r.flagId());
        p.put("reportSource", r == null ? null : r.source().name());
        p.put("reportReason", r == null ? null : r.reasonCode().name());
        p.put("reportDetails", r == null ? null : r.details());
        p.put("reporterId", r == null ? null : r.reporterId());
        p.put("reportRisk", r == null ? null : r.riskLevel());
        p.put("reportCount", r == null ? 0 : r.reports());
        return p;
    }

    private static Map<String, Object> reviewParams(ReviewSubmission r) {
        Map<String, Object> p = new HashMap<>();
        p.put("bookingId", r == null ? null : r.bookingId());
        p.put("serviceId", r == null ? null : r.serviceId());
        p.put("serviceName", r == null ? null : r.serviceName());
        p.put("customerId", r == null ? null : r.customerId());
        p.put("reviewerName", r == null ? null : r.reviewerName());
        p.put("rating", r == null ? null : r.rating());
        p.put("reviewText", r == null ? null : r.text());
        p.put("reviewCreatedAt", r == null ? null : Sql.ts(r.submittedAt()));
        p.put("reviewTitle", r == null ? null : r.title());
        p.put("reviewPhotos", r == null || r.photoUrls().isEmpty() ? null : String.join("\n", r.photoUrls()));
        p.put("reviewFlags", r == null || r.flags().isEmpty() ? null : String.join(",", r.flags()));
        if (r != null && r.productReview()) {
            p.put("reportCount", r.reports());
            p.put("reportReason", r.reportReason());
        }
        return p;
    }

    private static ReviewSubmission review(ResultSet rs) throws SQLException {
        UUID bookingId = rs.getObject("booking_id", UUID.class);
        if (bookingId == null) {
            return null;
        }
        if (!"PRODUCT_REVIEW".equals(rs.getString("item_type"))) {
            return new ReviewSubmission(bookingId, rs.getString("seller_org_id"), rs.getObject("service_id", UUID.class),
                    rs.getString("service_name"), rs.getString("customer_id"), rs.getString("reviewer_name"),
                    rs.getInt("rating"), rs.getString("review_text"), Sql.instant(rs, "review_created_at"));
        }
        String photos = rs.getString("review_photos");
        String flags = rs.getString("review_flags");
        return ReviewSubmission.productReview(bookingId, rs.getString("seller_org_id"),
                rs.getObject("service_id", UUID.class), rs.getString("atin"), rs.getString("service_name"),
                rs.getString("customer_id"), rs.getString("reviewer_name"), rs.getInt("rating"),
                rs.getString("review_title"), rs.getString("review_text"),
                photos == null || photos.isBlank() ? List.of() : List.of(photos.split("\n")),
                flags == null || flags.isBlank() ? List.of() : List.of(flags.split(",")), rs.getInt("report_count"),
                rs.getString("report_reason"), Sql.instant(rs, "review_created_at"));
    }

    private static ProductSubmission product(ResultSet rs) throws SQLException {
        UUID productId = rs.getObject("product_id", UUID.class);
        return productId == null ? null : new ProductSubmission(productId, rs.getString("atin"),
                rs.getString("seller_org_id"), rs.getString("owner_kind"), rs.getString("title"),
                rs.getString("product_type_code"));
    }

    private static ListingReport report(ResultSet rs) throws SQLException {
        String source = rs.getString("report_source");
        if (source == null) {
            return null;
        }
        String reason = rs.getString("report_reason");
        return new ListingReport(rs.getObject("flag_id", UUID.class), ListingFlagSource.valueOf(source),
                reason == null ? ModerationReasonCode.OTHER : ModerationReasonCode.valueOf(reason),
                rs.getString("report_details"), rs.getString("reporter_id"), rs.getString("report_risk"),
                rs.getInt("report_count"));
    }

    private ModerationItem map(ResultSet rs, int row) throws SQLException {
        String reason = rs.getString("reason_code");
        return new ModerationItem(rs.getObject("id", UUID.class), product(rs),
                ModerationItemType.valueOf(rs.getString("item_type")), report(rs), review(rs),
                ModerationStatus.valueOf(rs.getString("status")), ModerationPriority.valueOf(rs.getString("priority")),
                TriageStatus.valueOf(rs.getString("triage_status")), json.triage(rs.getString("triage")),
                Sql.instant(rs, "enqueued_at"), new Sla(Sql.instant(rs, "sla_started_at"), Sql.instant(rs, "due_at")),
                rs.getString("assignee"), Sql.instant(rs, "assigned_at"), rs.getBoolean("escalated"),
                rs.getString("escalation_reason"), Sql.instant(rs, "escalated_at"), rs.getString("decided_by"),
                Sql.instant(rs, "decided_at"), reason == null ? null : ModerationReasonCode.valueOf(reason),
                rs.getString("decision_note"), rs.getLong("version"));
    }
}
