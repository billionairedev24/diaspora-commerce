package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.RatingSummary;
import com.afrotransact.catalog.domain.ReviewDraft;
import com.afrotransact.catalog.domain.ReviewFlag;
import com.afrotransact.catalog.domain.ReviewPhoto;
import com.afrotransact.catalog.domain.ReviewReasonCode;
import com.afrotransact.catalog.domain.ReviewReport;
import com.afrotransact.catalog.domain.ReviewSort;
import com.afrotransact.catalog.domain.ReviewStateConflict;
import com.afrotransact.catalog.domain.ReviewStatus;
import com.afrotransact.catalog.port.ProductReviewRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProductReviewRepository implements ProductReviewRepository {

    private static final String COLUMNS = """
            id, product_id, atin, product_title, seller_org_id, customer_id, reviewer_name, order_id, order_number,
            rating, title, body, photos::text as photos, status, flags, created_at, updated_at, moderated_by,
            moderated_at, moderation_reason, moderation_note, reply_text, reply_by, reply_at, report_count, version""";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcProductReviewRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public ProductReview insert(ProductReview r) {
        try {
            jdbc.sql("""
                    insert into product_review (id, product_id, atin, product_title, seller_org_id, customer_id,
                        reviewer_name, order_id, order_number, rating, title, body, photos, status, flags, created_at,
                        updated_at, moderated_by, moderated_at, moderation_reason, moderation_note, report_count,
                        version)
                    values (:id, :productId, :atin, :productTitle, :org, :customer, :reviewer, :orderId, :orderNumber,
                        :rating, :title, :body, cast(:photos as jsonb), :status, :flags, :createdAt, :updatedAt,
                        :modBy, :modAt, :modReason, :modNote, :reports, 0)
                    """)
                    .params(columnParams(r))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ReviewStateConflict("catalog.review-already-exists", "You have already reviewed this item");
        }
        return findById(r.id()).orElseThrow();
    }

    @Override
    public ProductReview update(ProductReview r) {
        int rows = jdbc.sql("""
                update product_review set rating = :rating, title = :title, body = :body,
                    photos = cast(:photos as jsonb), status = :status, flags = :flags, updated_at = :updatedAt,
                    moderated_by = :modBy, moderated_at = :modAt, moderation_reason = :modReason,
                    moderation_note = :modNote, reply_text = :replyText, reply_by = :replyBy, reply_at = :replyAt,
                    report_count = :reports, version = version + 1
                where id = :id and version = :version
                """)
                .params(columnParams(r))
                .param("replyText", r.reply() == null ? null : r.reply().text())
                .param("replyBy", r.reply() == null ? null : r.reply().by())
                .param("replyAt", r.reply() == null ? null : Timestamp.from(r.reply().at()))
                .param("version", r.version())
                .update();
        if (rows == 0) {
            throw new ReviewStateConflict("catalog.review-concurrent-update",
                    "The review changed; reload and try again");
        }
        return findById(r.id()).orElseThrow();
    }

    @Override
    public Optional<ProductReview> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from product_review where id = :id").param("id", id)
                .query(this::map).optional();
    }

    @Override
    public Optional<ProductReview> findLive(UUID productId, String customerId) {
        return jdbc.sql("select " + COLUMNS + " from product_review where product_id = :p and customer_id = :c "
                        + "and status <> 'DELETED'")
                .param("p", productId).param("c", customerId).query(this::map).optional();
    }

    @Override
    public Page<ProductReview> published(UUID productId, Integer stars, ReviewSort sort, int page, int size) {
        String where = " where product_id = :p and status = 'PUBLISHED'" + (stars == null ? "" : " and rating = :stars");
        String order = switch (sort) {
            case NEWEST -> " order by created_at desc, id";
            case HIGHEST -> " order by rating desc, created_at desc, id";
            case LOWEST -> " order by rating asc, created_at desc, id";
        };
        JdbcClient.StatementSpec count = jdbc.sql("select count(*) from product_review" + where).param("p", productId);
        JdbcClient.StatementSpec list = jdbc.sql("select " + COLUMNS + " from product_review" + where + order
                + " limit :limit offset :offset").param("p", productId);
        if (stars != null) {
            count = count.param("stars", stars);
            list = list.param("stars", stars);
        }
        return page(count, list, page, size);
    }

    @Override
    public Page<ProductReview> publishedForOwner(String orgId, boolean unansweredOnly, int page, int size) {
        String where = " where seller_org_id = :org and status = 'PUBLISHED'"
                + (unansweredOnly ? " and reply_text is null" : "");
        return page(jdbc.sql("select count(*) from product_review" + where).param("org", orgId),
                jdbc.sql("select " + COLUMNS + " from product_review" + where
                        + " order by created_at desc, id limit :limit offset :offset").param("org", orgId),
                page, size);
    }

    @Override
    public Page<ProductReview> byStatus(ReviewStatus status, String atin, int page, int size) {
        String where = " where " + (status == null ? "status <> 'DELETED'" : "status = :status")
                + (atin == null ? "" : " and atin = :atin");
        JdbcClient.StatementSpec count = jdbc.sql("select count(*) from product_review" + where);
        JdbcClient.StatementSpec list = jdbc.sql("select " + COLUMNS + " from product_review" + where
                + " order by created_at desc, id limit :limit offset :offset");
        if (status != null) {
            count = count.param("status", status.name());
            list = list.param("status", status.name());
        }
        if (atin != null) {
            count = count.param("atin", atin);
            list = list.param("atin", atin);
        }
        return page(count, list, page, size);
    }

    @Override
    public boolean addReport(ReviewReport r) {
        return jdbc.sql("""
                insert into product_review_report (id, review_id, reporter_id, reporter_kind, reason_code, details,
                                                   created_at)
                values (:id, :review, :reporter, :kind, :reason, :details, :createdAt)
                on conflict (review_id, reporter_id) do nothing
                """)
                .param("id", r.id()).param("review", r.reviewId()).param("reporter", r.reporterId())
                .param("kind", r.kind().name()).param("reason", r.reasonCode().name()).param("details", r.details())
                .param("createdAt", Timestamp.from(r.createdAt()))
                .update() == 1;
    }

    @Override
    public int countReports(UUID reviewId) {
        return jdbc.sql("select count(*) from product_review_report where review_id = :id").param("id", reviewId)
                .query(Integer.class).single();
    }

    @Override
    public void clearReports(UUID reviewId) {
        jdbc.sql("delete from product_review_report where review_id = :id").param("id", reviewId).update();
    }

    @Override
    public RatingSummary rewriteRating(UUID productId, String atin, Instant now) {
        int[] stars = new int[5];
        jdbc.sql("select rating, count(*) as n from product_review where product_id = :p and status = 'PUBLISHED' "
                        + "group by rating")
                .param("p", productId)
                .query((rs, row) -> {
                    stars[rs.getInt("rating") - 1] = rs.getInt("n");
                    return rs.getInt("n");
                }).list();
        RatingSummary s = RatingSummary.ofCounts(stars[0], stars[1], stars[2], stars[3], stars[4]);
        jdbc.sql("""
                insert into product_rating (product_id, atin, average, review_count, star1, star2, star3, star4, star5,
                                            updated_at)
                values (:p, :atin, :average, :count, :s1, :s2, :s3, :s4, :s5, :now)
                on conflict (product_id) do update set average = excluded.average, review_count = excluded.review_count,
                    star1 = excluded.star1, star2 = excluded.star2, star3 = excluded.star3, star4 = excluded.star4,
                    star5 = excluded.star5, updated_at = excluded.updated_at
                """)
                .param("p", productId).param("atin", atin).param("average", s.average()).param("count", s.count())
                .param("s1", stars[0]).param("s2", stars[1]).param("s3", stars[2]).param("s4", stars[3])
                .param("s5", stars[4]).param("now", Timestamp.from(now))
                .update();
        return s;
    }

    @Override
    public Optional<RatingSummary> rating(UUID productId) {
        return jdbc.sql("select * from product_rating where product_id = :p").param("p", productId)
                .query(JdbcProductReviewRepository::summary).optional();
    }

    @Override
    public Map<UUID, RatingSummary> ratings(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, RatingSummary> out = new HashMap<>();
        jdbc.sql("select * from product_rating where product_id in (:ids)").param("ids", productIds)
                .query((rs, row) -> out.put(rs.getObject("product_id", UUID.class), summary(rs, row))).list();
        return out;
    }

    private Page<ProductReview> page(JdbcClient.StatementSpec count, JdbcClient.StatementSpec list, int page,
                                     int size) {
        long total = count.query(Long.class).single();
        List<ProductReview> items = list.param("limit", size).param("offset", (long) page * size)
                .query(this::map).list();
        return new Page<>(items, total, page, size);
    }

    private Map<String, Object> columnParams(ProductReview r) {
        ProductReview.Moderation m = r.moderation();
        Map<String, Object> p = new HashMap<>();
        p.put("id", r.id());
        p.put("productId", r.productId());
        p.put("atin", r.atin());
        p.put("productTitle", r.productTitle());
        p.put("org", r.sellerOrgId());
        p.put("customer", r.customerId());
        p.put("reviewer", r.reviewerName());
        p.put("orderId", r.orderId());
        p.put("orderNumber", r.orderNumber());
        p.put("rating", r.rating());
        p.put("title", r.content().title());
        p.put("body", r.content().text());
        p.put("photos", json.write(r.content().photos().stream().map(ReviewPhoto::url).toList()));
        p.put("status", r.status().name());
        p.put("flags", r.flags().stream().map(Enum::name).collect(Collectors.joining(",")));
        p.put("createdAt", Timestamp.from(r.createdAt()));
        p.put("updatedAt", Timestamp.from(r.updatedAt()));
        p.put("modBy", m == null ? null : m.by());
        p.put("modAt", m == null ? null : Timestamp.from(m.at()));
        p.put("modReason", m == null || m.reason() == null ? null : m.reason().name());
        p.put("modNote", m == null ? null : m.note());
        p.put("reports", r.reportCount());
        return p;
    }

    private ProductReview map(ResultSet rs, int row) throws SQLException {
        String by = rs.getString("moderated_by");
        String reason = rs.getString("moderation_reason");
        ProductReview.Moderation moderation = by == null ? null : new ProductReview.Moderation(by,
                rs.getTimestamp("moderated_at").toInstant(), reason == null ? null : ReviewReasonCode.valueOf(reason),
                rs.getString("moderation_note"));
        String replyText = rs.getString("reply_text");
        ProductReview.SellerReply reply = replyText == null ? null : new ProductReview.SellerReply(replyText,
                rs.getString("reply_by"), rs.getTimestamp("reply_at").toInstant());
        String flags = rs.getString("flags");
        List<ReviewFlag> parsed = flags == null || flags.isBlank() ? List.of()
                : Arrays.stream(flags.split(",")).map(ReviewFlag::valueOf).toList();
        ReviewDraft content = new ReviewDraft(rs.getInt("rating"), rs.getString("title"), rs.getString("body"),
                json.strings(rs.getString("photos")).stream().map(ReviewPhoto::new).toList());
        return new ProductReview(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("atin").strip(), rs.getString("product_title"), rs.getString("seller_org_id"),
                rs.getString("customer_id"), rs.getString("reviewer_name"), rs.getString("order_id"),
                rs.getString("order_number"), content, ReviewStatus.valueOf(rs.getString("status")), parsed,
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), moderation,
                reply, rs.getInt("report_count"), rs.getLong("version"));
    }

    private static RatingSummary summary(ResultSet rs, int row) throws SQLException {
        return RatingSummary.ofCounts(rs.getInt("star1"), rs.getInt("star2"), rs.getInt("star3"), rs.getInt("star4"),
                rs.getInt("star5"));
    }
}
