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
import com.afrotransact.bookings.port.ReviewRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Booking reviews; the primary key on the booking id makes "one review per booking" hold under races. */
@Repository
class JdbcReviewRepository implements ReviewRepository {

    private final JdbcClient jdbc;

    JdbcReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Review insert(Review r) {
        try {
            jdbc.sql("""
                    insert into booking_review (booking_id, org_id, service_id, customer_id, rating, text, created_at)
                    values (:booking, :org, :service, :customer, :rating, :text, :createdAt)
                    """)
                    .param("booking", r.bookingId()).param("org", r.orgId()).param("service", r.serviceId())
                    .param("customer", r.customerId()).param("rating", r.rating()).param("text", r.text())
                    .param("createdAt", Sql.ts(r.createdAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new BookingsStateConflict("bookings.already-reviewed", "This booking has already been rated");
        }
        return r;
    }

    @Override
    public Optional<Review> forBooking(UUID bookingId) {
        return jdbc.sql("select * from booking_review where booking_id = :id").param("id", bookingId)
                .query(this::map).optional();
    }

    @Override
    public Map<UUID, RatingSummary> summaries(Collection<UUID> serviceIds) {
        Map<UUID, RatingSummary> out = new HashMap<>();
        if (serviceIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select service_id, cast(avg(rating) as float8) as average, count(*) as reviews from booking_review
                where service_id in (:ids) group by service_id
                """)
                .param("ids", List.copyOf(serviceIds))
                .query((rs, n) -> Map.entry(rs.getObject("service_id", UUID.class),
                        new RatingSummary(rs.getDouble("average"), rs.getInt("reviews"))))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private Review map(ResultSet rs, int rowNum) throws SQLException {
        return new Review(rs.getObject("booking_id", UUID.class), rs.getString("org_id"),
                rs.getObject("service_id", UUID.class), rs.getString("customer_id"), rs.getInt("rating"),
                rs.getString("text"), Sql.instant(rs.getTimestamp("created_at")));
    }
}
