package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.ProviderFee;
import com.afrotransact.payments.domain.ProviderFeeStatus;
import com.afrotransact.payments.port.ProviderFeeRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProviderFeeRepository implements ProviderFeeRepository {

    private final JdbcClient jdbc;

    JdbcProviderFeeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(ProviderFee f) {
        return jdbc.sql("""
                insert into provider_fee (id, booking_id, booking_reference, org_id, currency, amount_minor, reason,
                    status, stripe_debit_id, attempts, debit_seq, last_error, next_attempt_at, source_event_id,
                    occurred_at, created_at, collected_at, version)
                values (:id, :booking, :reference, :org, :currency, :amount, :reason, :status, :debit, :attempts,
                    :seq, :error, :next, :event, :occurred, :created, :collected, 0)
                on conflict do nothing
                """)
                .param("id", f.id()).param("booking", f.bookingId()).param("reference", f.bookingReference())
                .param("org", f.orgId()).param("currency", f.amount().currency()).param("amount", f.amount().minor())
                .param("reason", f.reason()).param("status", f.status().name()).param("debit", f.stripeDebitId())
                .param("attempts", f.attempts()).param("seq", f.debitSeq()).param("error", f.lastError())
                .param("next", Sql.ts(f.nextAttemptAt())).param("event", f.sourceEventId())
                .param("occurred", Sql.ts(f.occurredAt())).param("created", Sql.ts(f.createdAt()))
                .param("collected", Sql.ts(f.collectedAt()))
                .update() == 1;
    }

    @Override
    public Optional<ProviderFee> findByBooking(String bookingId) {
        return jdbc.sql("select * from provider_fee where booking_id = :b").param("b", bookingId)
                .query(JdbcProviderFeeRepository::fee).optional();
    }

    @Override
    public Optional<ProviderFee> lock(UUID id) {
        return jdbc.sql("select * from provider_fee where id = :id for update").param("id", id)
                .query(JdbcProviderFeeRepository::fee).optional();
    }

    @Override
    public Optional<ProviderFee> lockNextDue(Instant now) {
        return jdbc.sql("""
                select * from provider_fee
                where status = 'OUTSTANDING' and next_attempt_at <= :now
                order by next_attempt_at, id
                limit 1
                for update skip locked
                """)
                .param("now", Sql.ts(now)).query(JdbcProviderFeeRepository::fee).optional();
    }

    @Override
    public ProviderFee update(ProviderFee f) {
        int updated = jdbc.sql("""
                update provider_fee set status = :status, stripe_debit_id = :debit, attempts = :attempts,
                    debit_seq = :seq, last_error = :error, next_attempt_at = :next, collected_at = :collected,
                    version = version + 1
                where id = :id and version = :version
                """)
                .param("status", f.status().name()).param("debit", f.stripeDebitId()).param("attempts", f.attempts())
                .param("seq", f.debitSeq()).param("error", f.lastError()).param("next", Sql.ts(f.nextAttemptAt()))
                .param("collected", Sql.ts(f.collectedAt())).param("id", f.id()).param("version", f.version())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Provider fee " + f.id() + " was changed concurrently");
        }
        return new ProviderFee(f.id(), f.bookingId(), f.bookingReference(), f.orgId(), f.amount(), f.reason(),
                f.status(), f.stripeDebitId(), f.attempts(), f.debitSeq(), f.lastError(), f.nextAttemptAt(),
                f.sourceEventId(),
                f.occurredAt(), f.createdAt(), f.collectedAt(), f.version() + 1);
    }

    @Override
    public Money totalOf(String orgId, String currency) {
        Long sum = jdbc.sql("select coalesce(sum(amount_minor), 0) from provider_fee where org_id = :org "
                        + "and currency = :currency")
                .param("org", orgId).param("currency", currency).query(Long.class).single();
        return Money.of(sum, currency);
    }

    @Override
    public List<ProviderFee> ofOrg(String orgId, int limit) {
        return jdbc.sql("select * from provider_fee where org_id = :org order by created_at desc, id limit :limit")
                .param("org", orgId).param("limit", limit).query(JdbcProviderFeeRepository::fee).list();
    }

    private static ProviderFee fee(ResultSet rs, int n) throws SQLException {
        String currency = rs.getString("currency");
        return new ProviderFee(rs.getObject("id", UUID.class), rs.getString("booking_id"),
                rs.getString("booking_reference"), rs.getString("org_id"),
                Money.of(rs.getLong("amount_minor"), currency), rs.getString("reason"),
                ProviderFeeStatus.valueOf(rs.getString("status")), rs.getString("stripe_debit_id"),
                rs.getInt("attempts"), rs.getInt("debit_seq"), rs.getString("last_error"),
                Sql.instant(rs, "next_attempt_at"),
                rs.getObject("source_event_id", UUID.class), Sql.instant(rs, "occurred_at"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "collected_at"), rs.getLong("version"));
    }
}
