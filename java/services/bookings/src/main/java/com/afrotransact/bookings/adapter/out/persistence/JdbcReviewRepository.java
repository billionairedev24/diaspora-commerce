package com.afrotransact.bookings.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.domain.BookingsStateConflict;
import com.afrotransact.bookings.domain.RatingSummary;
import com.afrotransact.bookings.domain.Review;
import com.afrotransact.bookings.domain.ReviewRejectReason;
import com.afrotransact.bookings.domain.ReviewStatus;
import com.afrotransact.bookings.port.ReviewRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Booking reviews; the primary key on the booking id makes "one review per booking" hold under races. */
@Repository
class JdbcReviewRepository implements ReviewRepository {

    private static final String SELECT = """
            select booking_id, org_id, service_id, service_name, customer_id, reviewer_name, rating, text, created_at,
                   status, moderated_by, moderated_at, moderation_reason, moderation_note, reply_text, reply_by,
                   reply_at, version
            from booking_review
            """;

    private final JdbcClient jdbc;

    JdbcReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Review insert(Review r) {
        try {
            jdbc.sql("""
                    insert into booking_review (booking_id, org_id, service_id, service_name, customer_id,
                                                reviewer_name, rating, text, created_at, status, version)
                    values (:booking, :org, :service, :serviceName, :customer, :reviewer, :rating, :text, :createdAt,
                            :status, 0)
                    """)
                    .param("booking", r.bookingId()).param("org", r.orgId()).param("service", r.serviceId())
                    .param("serviceName", r.serviceName()).param("customer", r.customerId())
                    .param("reviewer", r.reviewerName()).param("rating", r.rating()).param("text", r.text())
                    .param("createdAt", Sql.ts(r.createdAt())).param("status", r.status().name())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new BookingsStateConflict("bookings.already-reviewed", "This booking has already been rated");
        }
        return r;
    }

    @Override
    public Optional<Review> forBooking(UUID bookingId) {
        return jdbc.sql(SELECT + " where booking_id = :id").param("id", bookingId).query(this::map).optional();
    }

    @Override
    public Review update(Review r) {
        Review.Moderation m = r.moderation();
        Review.ProviderReply reply = r.reply();
        int rows = jdbc.sql("""
                update booking_review set status = :status, moderated_by = :modBy, moderated_at = :modAt,
                    moderation_reason = :reason, moderation_note = :note, reply_text = :replyText,
                    reply_by = :replyBy, reply_at = :replyAt, version = version + 1
                where booking_id = :id and version = :version
                """)
                .param("status", r.status().name())
                .param("modBy", m == null ? null : m.by()).param("modAt", m == null ? null : Sql.ts(m.at()))
                .param("reason", m == null || m.reason() == null ? null : m.reason().name())
                .param("note", m == null ? null : m.note())
                .param("replyText", reply == null ? null : reply.text())
                .param("replyBy", reply == null ? null : reply.by())
                .param("replyAt", reply == null ? null : Sql.ts(reply.at()))
                .param("id", r.bookingId()).param("version", r.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("bookings.concurrent-update",
                    "Review of booking " + r.bookingId() + " was changed by someone else; reload and try again");
        }
        return forBooking(r.bookingId()).orElseThrow();
    }

    @Override
    public Map<UUID, RatingSummary> summaries(Collection<UUID> serviceIds) {
        Map<UUID, RatingSummary> out = new HashMap<>();
        if (serviceIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select service_id, cast(avg(rating) as float8) as average, count(*) as reviews from booking_review
                where service_id in (:ids) and status = 'APPROVED' group by service_id
                """)
                .param("ids", List.copyOf(serviceIds))
                .query((rs, n) -> Map.entry(rs.getObject("service_id", UUID.class),
                        new RatingSummary(rs.getDouble("average"), rs.getInt("reviews"))))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    @Override
    public RatingSummary providerSummary(String orgId) {
        return jdbc.sql("""
                select coalesce(cast(avg(rating) as float8), 0) as average, count(*) as reviews from booking_review
                where org_id = :org and status = 'APPROVED'
                """)
                .param("org", orgId)
                .query((rs, n) -> new RatingSummary(rs.getDouble("average"), rs.getInt("reviews")))
                .single();
    }

    @Override
    public ReviewPage published(String orgId, UUID serviceId, int page, int size) {
        if ((orgId == null) == (serviceId == null)) {
            throw new IllegalArgumentException("Exactly one of orgId and serviceId");
        }
        String where = orgId != null ? " where org_id = :key and status = 'APPROVED'"
                : " where service_id = :key and status = 'APPROVED'";
        Object key = orgId != null ? orgId : serviceId;
        return page(where, Map.of("key", key), " order by created_at desc", page, size);
    }

    @Override
    public ReviewPage byStatus(ReviewStatus status, int page, int size) {
        return page(" where status = :status", Map.of("status", status.name()), " order by created_at", page, size);
    }

    @Override
    public ReviewPage byOrg(String orgId, ReviewStatus status, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        params.put("org", orgId);
        String where = " where org_id = :org";
        if (status != null) {
            where += " and status = :status";
            params.put("status", status.name());
        }
        return page(where, params, " order by created_at desc", page, size);
    }

    private ReviewPage page(String where, Map<String, Object> params, String order, int page, int size) {
        long total = jdbc.sql("select count(*) from booking_review" + where).params(params).query(Long.class)
                .single();
        Map<String, Object> p = new HashMap<>(params);
        p.put("limit", size);
        p.put("offset", (long) page * size);
        List<Review> items = jdbc.sql(SELECT + where + order + " limit :limit offset :offset").params(p)
                .query(this::map).list();
        return new ReviewPage(items, total, page, size);
    }

    private Review map(ResultSet rs, int rowNum) throws SQLException {
        String modBy = rs.getString("moderated_by");
        String reason = rs.getString("moderation_reason");
        Review.Moderation moderation = modBy == null ? null : new Review.Moderation(modBy,
                Sql.instant(rs.getTimestamp("moderated_at")),
                reason == null ? null : ReviewRejectReason.valueOf(reason), rs.getString("moderation_note"));
        String replyText = rs.getString("reply_text");
        Review.ProviderReply reply = replyText == null ? null : new Review.ProviderReply(replyText,
                rs.getString("reply_by"), Sql.instant(rs.getTimestamp("reply_at")));
        return new Review(rs.getObject("booking_id", UUID.class), rs.getString("org_id"),
                rs.getObject("service_id", UUID.class), rs.getString("service_name"), rs.getString("customer_id"),
                rs.getString("reviewer_name"), rs.getInt("rating"), rs.getString("text"),
                Sql.instant(rs.getTimestamp("created_at")), ReviewStatus.valueOf(rs.getString("status")),
                moderation, reply, rs.getLong("version"));
    }
}
