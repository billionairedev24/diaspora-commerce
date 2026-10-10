package com.afrotransact.bookings.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.domain.Activity;
import com.afrotransact.bookings.domain.BalanceCharge;
import com.afrotransact.bookings.domain.BalanceState;
import com.afrotransact.bookings.domain.BookedService;
import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingStatus;
import com.afrotransact.bookings.domain.BookingsStateConflict;
import com.afrotransact.bookings.domain.BusyInterval;
import com.afrotransact.bookings.domain.CancellationPolicy;
import com.afrotransact.bookings.domain.CustomerContact;
import com.afrotransact.bookings.domain.MoneyKind;
import com.afrotransact.bookings.domain.Party;
import com.afrotransact.bookings.domain.PaymentState;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Bookings with their history lines and calendar blocks. The blocks table carries the exclusion constraint
 * {@code booking_time_block_no_overlap}: a second active booking overlapping a team member's time is rejected by
 * PostgreSQL (SQLSTATE 23P01) and reported as {@code bookings.slot-taken}.
 */
@Repository
class JdbcBookingRepository implements BookingRepository {

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final UUID NONE = new UUID(0, 0);
    private static final List<String> ACTIVE = List.of(BookingStatus.values()).stream()
            .filter(BookingStatus::isActive).map(Enum::name).toList();

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcBookingRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public String nextReference() {
        return "BK-" + jdbc.sql("select nextval('booking_reference_seq')").query(Long.class).single();
    }

    @Override
    public Booking insert(Booking b) {
        try {
            jdbc.sql("""
                    insert into booking (id, reference, org_id, member_id, customer_id, customer, service, location,
                        notes, channel, start_at, end_at, buffer_minutes, proposed_start_at, previous_status, status,
                        policy, instant, reschedules, payment_id, payment_state, client_secret, payment_failure,
                        hold_expires_at, request_expires_at, authorized_minor, deposit_captured_minor,
                        balance_captured_minor, refunded_minor, released_minor, provider_fee_minor, balance_state,
                        balance_payment_id, balance_attempts, balance_client_secret, balance_failure,
                        balance_next_attempt_at, reason, cancelled_by, created_at, updated_at, version)
                    values (:id, :reference, :org, :member, :customerId, cast(:customer as jsonb),
                        cast(:service as jsonb), :location, :notes, :channel, :startAt, :endAt, :buffer, :proposed,
                        :previousStatus, :status, cast(:policy as jsonb), :instant, :reschedules, :paymentId,
                        :paymentState, :clientSecret, :paymentFailure, :holdExpiresAt, :requestExpiresAt,
                        :authorized, :depositCaptured, :balanceCaptured, :refunded, :released, :providerFee,
                        :balanceState, :balancePaymentId, :balanceAttempts, :balanceSecret, :balanceFailure,
                        :balanceNextAttempt, :reason, :cancelledBy, :createdAt, :updatedAt, 0)
                    """)
                    .param("id", b.id())
                    .param("reference", b.reference())
                    .param("org", b.orgId())
                    .param("member", b.memberId())
                    .param("customerId", b.customerId())
                    .param("customer", json.write(b.customer()))
                    .param("service", json.write(b.service()))
                    .param("location", b.location())
                    .param("notes", b.notes())
                    .param("channel", b.channel())
                    .param("startAt", Sql.ts(b.startAt()))
                    .param("endAt", Sql.ts(b.endAt()))
                    .param("buffer", b.bufferMinutes())
                    .param("proposed", Sql.ts(b.proposedStartAt()))
                    .param("previousStatus", b.previousStatus() == null ? null : b.previousStatus().name())
                    .param("status", b.status().name())
                    .param("policy", json.write(b.policy()))
                    .param("instant", b.instant())
                    .param("reschedules", b.reschedules())
                    .param("paymentId", b.paymentId())
                    .param("paymentState", b.paymentState().name())
                    .param("clientSecret", b.clientSecret())
                    .param("paymentFailure", b.paymentFailure())
                    .param("holdExpiresAt", Sql.ts(b.holdExpiresAt()))
                    .param("requestExpiresAt", Sql.ts(b.requestExpiresAt()))
                    .param("authorized", b.authorizedMinor())
                    .param("depositCaptured", b.depositCapturedMinor())
                    .param("balanceCaptured", b.balanceCapturedMinor())
                    .param("refunded", b.refundedMinor())
                    .param("released", b.releasedMinor())
                    .param("providerFee", b.providerFeeMinor())
                    .param("balanceState", b.balance().state().name())
                    .param("balancePaymentId", b.balance().paymentId())
                    .param("balanceAttempts", b.balance().attempts())
                    .param("balanceSecret", b.balance().clientSecret())
                    .param("balanceFailure", b.balance().failure())
                    .param("balanceNextAttempt", Sql.ts(b.balance().nextAttemptAt()))
                    .param("reason", b.reason())
                    .param("cancelledBy", b.cancelledBy() == null ? null : b.cancelledBy().name())
                    .param("createdAt", Sql.ts(b.createdAt()))
                    .param("updatedAt", Sql.ts(b.updatedAt()))
                    .update();
            syncBlocks(b);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        appendActivities(b);
        return find(b.id()).orElseThrow();
    }

    @Override
    public Booking update(Booking b) {
        try {
            int rows = jdbc.sql("""
                    update booking set start_at = :startAt, end_at = :endAt, proposed_start_at = :proposed,
                        previous_status = :previousStatus, status = :status, reschedules = :reschedules,
                        payment_id = :paymentId, payment_state = :paymentState, client_secret = :clientSecret,
                        payment_failure = :paymentFailure, request_expires_at = :requestExpiresAt,
                        authorized_minor = :authorized, deposit_captured_minor = :depositCaptured,
                        balance_captured_minor = :balanceCaptured, refunded_minor = :refunded,
                        released_minor = :released, provider_fee_minor = :providerFee,
                        balance_state = :balanceState, balance_payment_id = :balancePaymentId,
                        balance_attempts = :balanceAttempts, balance_client_secret = :balanceSecret,
                        balance_failure = :balanceFailure, balance_next_attempt_at = :balanceNextAttempt,
                        reason = :reason,
                        cancelled_by = :cancelledBy, updated_at = :updatedAt, version = version + 1
                    where id = :id and version = :version
                    """)
                    .param("startAt", Sql.ts(b.startAt()))
                    .param("endAt", Sql.ts(b.endAt()))
                    .param("proposed", Sql.ts(b.proposedStartAt()))
                    .param("previousStatus", b.previousStatus() == null ? null : b.previousStatus().name())
                    .param("status", b.status().name())
                    .param("reschedules", b.reschedules())
                    .param("paymentId", b.paymentId())
                    .param("paymentState", b.paymentState().name())
                    .param("clientSecret", b.clientSecret())
                    .param("paymentFailure", b.paymentFailure())
                    .param("requestExpiresAt", Sql.ts(b.requestExpiresAt()))
                    .param("authorized", b.authorizedMinor())
                    .param("depositCaptured", b.depositCapturedMinor())
                    .param("balanceCaptured", b.balanceCapturedMinor())
                    .param("refunded", b.refundedMinor())
                    .param("released", b.releasedMinor())
                    .param("providerFee", b.providerFeeMinor())
                    .param("balanceState", b.balance().state().name())
                    .param("balancePaymentId", b.balance().paymentId())
                    .param("balanceAttempts", b.balance().attempts())
                    .param("balanceSecret", b.balance().clientSecret())
                    .param("balanceFailure", b.balance().failure())
                    .param("balanceNextAttempt", Sql.ts(b.balance().nextAttemptAt()))
                    .param("reason", b.reason())
                    .param("cancelledBy", b.cancelledBy() == null ? null : b.cancelledBy().name())
                    .param("updatedAt", Sql.ts(b.updatedAt()))
                    .param("id", b.id())
                    .param("version", b.version())
                    .update();
            if (rows == 0) {
                throw new ConflictException("bookings.concurrent-update", "Booking " + b.reference()
                        + " changed; reload and retry");
            }
            syncBlocks(b);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        appendActivities(b);
        return find(b.id()).orElseThrow();
    }

    /** Re-writes the calendar blocks of the booking; the exclusion constraint checks them against others. */
    private void syncBlocks(Booking b) {
        jdbc.sql("delete from booking_time_block where booking_id = :id").param("id", b.id()).update();
        if (!b.status().isActive()) {
            return;
        }
        block(b, "PRIMARY", b.startAt(), b.blockedUntil());
        if (b.proposedStartAt() != null) {
            block(b, "PROPOSED", b.proposedStartAt(), b.proposedBlockedUntil());
        }
    }

    private void block(Booking b, String kind, Instant from, Instant to) {
        jdbc.sql("""
                insert into booking_time_block (booking_id, kind, member_id, period)
                values (:id, :kind, :member, tstzrange(:from, :to, '[)'))
                """)
                .param("id", b.id()).param("kind", kind).param("member", b.memberId())
                .param("from", Sql.ts(from)).param("to", Sql.ts(to))
                .update();
    }

    private void appendActivities(Booking b) {
        for (Activity a : b.drainActivities()) {
            jdbc.sql("""
                    insert into booking_activity (booking_id, at, by_party, action, money_kind, amount_minor)
                    values (:id, :at, :by, :action, :kind, :amount)
                    """)
                    .param("id", b.id()).param("at", Sql.ts(a.at())).param("by", a.by().name())
                    .param("action", a.action()).param("kind", a.moneyKind() == null ? null : a.moneyKind().name())
                    .param("amount", a.amountMinor())
                    .update();
        }
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        if (Sql.hasState(e, EXCLUSION_VIOLATION)) {
            return new BookingsStateConflict("bookings.slot-taken", "That time was just taken. Pick another slot.");
        }
        return e;
    }

    @Override
    public Optional<Booking> find(UUID id) {
        return jdbc.sql("select * from booking where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Booking> lock(UUID id) {
        return jdbc.sql("select * from booking where id = :id for update").param("id", id).query(this::map)
                .optional();
    }

    @Override
    public Optional<Booking> tryLock(UUID id) {
        return jdbc.sql("select * from booking where id = :id for update skip locked").param("id", id)
                .query(this::map).optional();
    }

    @Override
    public Optional<Booking> findByReference(String reference) {
        return jdbc.sql("select * from booking where reference = :r").param("r", reference).query(this::map)
                .optional();
    }

    @Override
    public List<BusyInterval> busy(UUID memberId, Instant from, Instant to, UUID excludeId) {
        return jdbc.sql("""
                select lower(period) as s, upper(period) as e from booking_time_block
                where member_id = :m and period && tstzrange(:from, :to, '[)') and booking_id <> :exclude
                order by lower(period)
                """)
                .param("m", memberId).param("from", Sql.ts(from)).param("to", Sql.ts(to))
                .param("exclude", excludeId == null ? NONE : excludeId)
                .query((rs, n) -> new BusyInterval(rs.getTimestamp("s").toInstant(), rs.getTimestamp("e").toInstant()))
                .list();
    }

    @Override
    public int countStarting(UUID memberId, Instant from, Instant to, UUID excludeId) {
        return jdbc.sql("""
                select count(*) from booking
                where member_id = :m and status in (:active) and start_at >= :from and start_at < :to
                  and id <> :exclude
                """)
                .param("m", memberId).param("active", ACTIVE).param("from", Sql.ts(from)).param("to", Sql.ts(to))
                .param("exclude", excludeId == null ? NONE : excludeId)
                .query(Integer.class).single();
    }

    @Override
    public List<Booking> forCustomer(String customerId) {
        return jdbc.sql("select * from booking where customer_id = :c order by start_at desc limit 200")
                .param("c", customerId).query(this::map).list();
    }

    @Override
    public List<Booking> forProvider(String orgId, Instant from, Instant to, UUID memberId, BookingStatus status) {
        StringBuilder sql = new StringBuilder("select * from booking where org_id = :org and start_at >= :from "
                + "and start_at < :to");
        if (memberId != null) {
            sql.append(" and member_id = :member");
        }
        if (status != null) {
            sql.append(" and status = :status");
        }
        sql.append(" order by start_at, reference limit 1000");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("org", orgId).param("from", Sql.ts(from))
                .param("to", Sql.ts(to));
        if (memberId != null) {
            spec = spec.param("member", memberId);
        }
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.query(this::map).list();
    }

    @Override
    public List<UUID> dueForExpiry(BookingStatus status, Instant now, int limit) {
        String column = switch (status) {
            case PENDING_PAYMENT -> "hold_expires_at";
            case REQUESTED -> "request_expires_at";
            default -> throw new IllegalArgumentException("No expiry for " + status);
        };
        return jdbc.sql("select id from booking where status = :s and " + column + " <= :now order by " + column
                        + " limit :limit")
                .param("s", status.name()).param("now", Sql.ts(now)).param("limit", limit)
                .query(UUID.class).list();
    }

    @Override
    public List<UUID> dueForCompletion(Instant endedBefore, int limit) {
        return jdbc.sql("select id from booking where status = 'CONFIRMED' and end_at <= :before order by end_at "
                        + "limit :limit")
                .param("before", Sql.ts(endedBefore)).param("limit", limit).query(UUID.class).list();
    }

    @Override
    public List<UUID> dueForBalanceCharge(Instant startsBefore, int limit) {
        return jdbc.sql("select id from booking where balance_state in ('DUE', 'FAILED') and status in (:active) "
                        + "and start_at <= :before order by start_at limit :limit")
                .param("active", ACTIVE).param("before", Sql.ts(startsBefore)).param("limit", limit)
                .query(UUID.class).list();
    }

    @Override
    public List<Activity> history(UUID bookingId) {
        return jdbc.sql("select * from booking_activity where booking_id = :id order by id")
                .param("id", bookingId)
                .query((rs, n) -> new Activity(rs.getTimestamp("at").toInstant(), Party.valueOf(rs.getString("by_party")),
                        rs.getString("action"), rs.getString("money_kind") == null ? null
                        : MoneyKind.valueOf(rs.getString("money_kind")), rs.getLong("amount_minor")))
                .list();
    }

    private Booking map(ResultSet rs, int rowNum) throws SQLException {
        String previous = rs.getString("previous_status");
        String cancelledBy = rs.getString("cancelled_by");
        return new Booking(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("org_id"),
                rs.getObject("member_id", UUID.class), rs.getString("customer_id"),
                json.read(rs.getString("customer"), CustomerContact.class),
                json.read(rs.getString("service"), BookedService.class), rs.getString("location"),
                rs.getString("notes"), rs.getString("channel"), Sql.instant(rs.getTimestamp("start_at")),
                Sql.instant(rs.getTimestamp("end_at")), rs.getInt("buffer_minutes"),
                Sql.instant(rs.getTimestamp("proposed_start_at")),
                previous == null ? null : BookingStatus.valueOf(previous), BookingStatus.valueOf(rs.getString("status")),
                json.read(rs.getString("policy"), CancellationPolicy.class), rs.getBoolean("instant"),
                rs.getInt("reschedules"), rs.getObject("payment_id", UUID.class),
                PaymentState.valueOf(rs.getString("payment_state")), rs.getString("client_secret"),
                rs.getString("payment_failure"), Sql.instant(rs.getTimestamp("hold_expires_at")),
                Sql.instant(rs.getTimestamp("request_expires_at")), rs.getLong("authorized_minor"),
                rs.getLong("deposit_captured_minor"), rs.getLong("balance_captured_minor"),
                rs.getLong("refunded_minor"), rs.getLong("released_minor"), rs.getLong("provider_fee_minor"),
                new BalanceCharge(BalanceState.valueOf(rs.getString("balance_state")),
                        rs.getObject("balance_payment_id", UUID.class), rs.getInt("balance_attempts"),
                        rs.getString("balance_client_secret"), rs.getString("balance_failure"),
                        Sql.instant(rs.getTimestamp("balance_next_attempt_at"))),
                rs.getString("reason"), cancelledBy == null ? null : Party.valueOf(cancelledBy),
                Sql.instant(rs.getTimestamp("created_at")), Sql.instant(rs.getTimestamp("updated_at")),
                rs.getLong("version"));
    }
}
