package com.afrotransact.payments.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PayoutRequest;
import com.afrotransact.payments.domain.PayoutStatus;
import com.afrotransact.payments.port.PayoutRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPayoutRepository implements PayoutRepository {

    private final JdbcClient jdbc;

    JdbcPayoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(PayoutRequest r) {
        jdbc.sql("""
                insert into payout_request (id, org_id, currency, amount_minor, status, requested_by, requested_at,
                    request_day, decided_by, decided_at, hold_reason, stripe_payout_id, failure_message, version)
                values (:id, :org, :currency, :amount, :status, :by, :at, :day, :decidedBy, :decidedAt, :hold,
                    :payoutId, :failure, 0)
                """)
                .param("id", r.id()).param("org", r.orgId()).param("currency", r.amount().currency())
                .param("amount", r.amount().minor()).param("status", r.status().name())
                .param("by", r.requestedBy()).param("at", Sql.ts(r.requestedAt()))
                .param("day", Date.valueOf(r.requestDay())).param("decidedBy", r.decidedBy())
                .param("decidedAt", Sql.ts(r.decidedAt())).param("hold", r.holdReason())
                .param("payoutId", r.stripePayoutId()).param("failure", r.failureMessage())
                .update();
    }

    @Override
    public PayoutRequest update(PayoutRequest r) {
        int updated = jdbc.sql("""
                update payout_request set status = :status, decided_by = :decidedBy, decided_at = :decidedAt,
                    hold_reason = :hold, stripe_payout_id = :payoutId, failure_message = :failure,
                    version = version + 1
                where id = :id and version = :version
                """)
                .param("status", r.status().name()).param("decidedBy", r.decidedBy())
                .param("decidedAt", Sql.ts(r.decidedAt())).param("hold", r.holdReason())
                .param("payoutId", r.stripePayoutId()).param("failure", r.failureMessage())
                .param("id", r.id()).param("version", r.version())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Payout request " + r.id() + " was changed concurrently");
        }
        return find(r.id()).orElseThrow();
    }

    @Override
    public Optional<PayoutRequest> find(UUID id) {
        return jdbc.sql("select * from payout_request where id = :id").param("id", id)
                .query(JdbcPayoutRepository::row).optional();
    }

    @Override
    public Optional<PayoutRequest> lock(UUID id) {
        return jdbc.sql("select * from payout_request where id = :id for update").param("id", id)
                .query(JdbcPayoutRepository::row).optional();
    }

    @Override
    public Optional<PayoutRequest> lockByStripeId(String stripePayoutId) {
        return jdbc.sql("select * from payout_request where stripe_payout_id = :s for update")
                .param("s", stripePayoutId).query(JdbcPayoutRepository::row).optional();
    }

    @Override
    public List<PayoutRequest> list(Optional<PayoutStatus> status, Optional<String> orgId, int limit) {
        StringBuilder sql = new StringBuilder("select * from payout_request where 1 = 1");
        status.ifPresent(s -> sql.append(" and status = :status"));
        orgId.ifPresent(o -> sql.append(" and org_id = :org"));
        sql.append(" order by requested_at, id limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (status.isPresent()) {
            spec = spec.param("status", status.get().name());
        }
        if (orgId.isPresent()) {
            spec = spec.param("org", orgId.get());
        }
        return spec.query(JdbcPayoutRepository::row).list();
    }

    @Override
    public List<PayoutRequest> requestsOn(String orgId, LocalDate day) {
        return jdbc.sql("select * from payout_request where org_id = :org and request_day = :day")
                .param("org", orgId).param("day", Date.valueOf(day)).query(JdbcPayoutRepository::row).list();
    }

    @Override
    public Map<PayoutStatus, Long> totals(String orgId, String currency) {
        Map<PayoutStatus, Long> out = new EnumMap<>(PayoutStatus.class);
        jdbc.sql("select status, sum(amount_minor) as total from payout_request where org_id = :org "
                        + "and currency = :currency group by status")
                .param("org", orgId).param("currency", currency)
                .query(rs -> {
                    out.put(PayoutStatus.valueOf(rs.getString("status")), rs.getLong("total"));
                });
        return out;
    }

    private static PayoutRequest row(ResultSet rs, int n) throws SQLException {
        return new PayoutRequest(rs.getObject("id", UUID.class), rs.getString("org_id"),
                Money.of(rs.getLong("amount_minor"), rs.getString("currency")),
                PayoutStatus.valueOf(rs.getString("status")), rs.getString("requested_by"),
                Sql.instant(rs, "requested_at"), rs.getDate("request_day").toLocalDate(), rs.getString("decided_by"),
                Sql.instant(rs, "decided_at"), rs.getString("hold_reason"), rs.getString("stripe_payout_id"),
                rs.getString("failure_message"), rs.getLong("version"));
    }
}
