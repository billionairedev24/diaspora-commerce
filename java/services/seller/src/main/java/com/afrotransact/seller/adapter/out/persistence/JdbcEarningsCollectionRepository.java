package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.seller.port.EarningsCollectionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcEarningsCollectionRepository implements EarningsCollectionRepository {

    private final JdbcClient jdbc;

    JdbcEarningsCollectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(String invoiceId, String orgId, String invoiceNumber, long invoiceAmountCents,
                         long amountCents, Instant at) {
        return jdbc.sql("""
                insert into earnings_collection (stripe_invoice_id, org_id, invoice_number, invoice_amount_cents,
                                                 amount_cents, status, next_attempt_at, created_at, updated_at)
                values (:i, :o, :n, :ia, :a, 'REQUESTED', :at, :at, :at) on conflict do nothing
                """).param("i", invoiceId).param("o", orgId).param("n", invoiceNumber).param("ia", invoiceAmountCents)
                .param("a", amountCents).param("at", Sql.ts(at)).update() == 1;
    }

    @Override
    public Optional<Row> find(String invoiceId) {
        return jdbc.sql("select * from earnings_collection where stripe_invoice_id = :i").param("i", invoiceId)
                .query(this::map).optional();
    }

    @Override
    public Optional<Row> lock(String invoiceId) {
        return jdbc.sql("select * from earnings_collection where stripe_invoice_id = :i for update")
                .param("i", invoiceId).query(this::map).optional();
    }

    @Override
    public boolean transition(String invoiceId, String from, String to, Change c, Instant at) {
        return jdbc.sql("""
                update earnings_collection set status = :to,
                    withheld_cents = coalesce(:withheld, withheld_cents),
                    credited_cents = coalesce(:credited, credited_cents),
                    payments_reference = coalesce(:ref, payments_reference),
                    stripe_balance_transaction_id = coalesce(:txn, stripe_balance_transaction_id),
                    reason = coalesce(:reason, reason), last_error = null, updated_at = :at
                where stripe_invoice_id = :i and status = :from
                """).param("to", to).param("withheld", c.withheldCents()).param("credited", c.creditedCents())
                .param("ref", c.paymentsReference()).param("txn", c.balanceTransactionId()).param("reason", c.reason())
                .param("at", Sql.ts(at)).param("i", invoiceId).param("from", from).update() == 1;
    }

    @Override
    public void failedAttempt(String invoiceId, String error, Instant nextAttemptAt, Instant at) {
        jdbc.sql("""
                update earnings_collection set attempts = attempts + 1, last_error = :e, next_attempt_at = :next,
                    updated_at = :at
                where stripe_invoice_id = :i
                """).param("e", error).param("next", Sql.ts(nextAttemptAt)).param("at", Sql.ts(at))
                .param("i", invoiceId).update();
    }

    @Override
    public List<String> dueInvoices(Instant now, int limit) {
        return jdbc.sql("""
                select stripe_invoice_id from earnings_collection where status in ('REQUESTED', 'WITHHELD')
                  and coalesce(next_attempt_at, created_at) <= :now
                order by created_at limit :limit
                """).param("now", Sql.ts(now)).param("limit", limit).query(String.class).list();
    }

    @Override
    public List<Row> forOrg(String orgId, int limit) {
        return jdbc.sql("select * from earnings_collection where org_id = :o order by created_at desc limit :l")
                .param("o", orgId).param("l", limit).query(this::map).list();
    }

    @Override
    public Map<String, Row> forInvoices(Collection<String> invoiceIds) {
        Map<String, Row> out = new LinkedHashMap<>();
        if (invoiceIds.isEmpty()) {
            return out;
        }
        jdbc.sql("select * from earnings_collection where stripe_invoice_id in (:ids)")
                .param("ids", List.copyOf(invoiceIds)).query(this::map).list()
                .forEach(r -> out.put(r.invoiceId(), r));
        return out;
    }

    @Override
    public Optional<Row> inProgress(String orgId) {
        return jdbc.sql("""
                select * from earnings_collection where org_id = :o and status in ('REQUESTED', 'WITHHELD')
                order by created_at desc limit 1
                """).param("o", orgId).query(this::map).optional();
    }

    @Override
    public void addReleased(String invoiceId, long cents, String status, Instant at) {
        jdbc.sql("""
                update earnings_collection set released_cents = least(released_cents + :c, withheld_cents),
                    status = coalesce(:s, status), updated_at = :at
                where stripe_invoice_id = :i
                """).param("c", cents).param("s", status).param("at", Sql.ts(at)).param("i", invoiceId).update();
    }

    @Override
    public boolean requestRelease(String idempotencyKey, String invoiceId, long amountCents, String reason,
                                  boolean debitBalance, Instant at) {
        return jdbc.sql("""
                insert into earnings_release (idempotency_key, stripe_invoice_id, amount_cents, reason, debit_balance,
                                              status, next_attempt_at, created_at, updated_at)
                values (:k, :i, :a, :r, :d, 'PENDING', :at, :at, :at) on conflict do nothing
                """).param("k", idempotencyKey).param("i", invoiceId).param("a", amountCents).param("r", reason)
                .param("d", debitBalance).param("at", Sql.ts(at)).update() == 1;
    }

    @Override
    public List<String> dueReleases(Instant now, String invoiceId, int limit) {
        return jdbc.sql("""
                select idempotency_key from earnings_release
                where status = 'PENDING' and next_attempt_at <= :now
                  and (cast(:inv as varchar) is null or stripe_invoice_id = :inv)
                order by created_at limit :limit
                """).param("now", Sql.ts(now)).param("inv", invoiceId).param("limit", limit).query(String.class)
                .list();
    }

    @Override
    public Optional<Release> lockRelease(String idempotencyKey) {
        return jdbc.sql("""
                select r.*, c.org_id from earnings_release r
                join earnings_collection c on c.stripe_invoice_id = r.stripe_invoice_id
                where r.idempotency_key = :k and r.status = 'PENDING'
                for update of r skip locked
                """).param("k", idempotencyKey)
                .query((rs, n) -> new Release(rs.getString("idempotency_key"), rs.getString("stripe_invoice_id"),
                        rs.getString("org_id"), rs.getLong("amount_cents"), rs.getString("reason"),
                        rs.getBoolean("debit_balance"), rs.getString("stripe_balance_transaction_id"),
                        rs.getInt("attempts")))
                .optional();
    }

    @Override
    public void releaseDone(String idempotencyKey, String balanceTransactionId, Instant at) {
        jdbc.sql("""
                update earnings_release set status = 'DONE', last_error = null, updated_at = :at,
                    stripe_balance_transaction_id = coalesce(:txn, stripe_balance_transaction_id)
                where idempotency_key = :k
                """).param("at", Sql.ts(at)).param("txn", balanceTransactionId).param("k", idempotencyKey).update();
    }

    @Override
    public void releaseFailed(String idempotencyKey, String error, Instant nextAttemptAt, boolean giveUp,
                              Instant at) {
        jdbc.sql("""
                update earnings_release set attempts = attempts + 1, last_error = :e, next_attempt_at = :next,
                    status = case when :giveUp then 'FAILED' else status end, updated_at = :at
                where idempotency_key = :k
                """).param("e", error).param("next", Sql.ts(nextAttemptAt)).param("giveUp", giveUp)
                .param("at", Sql.ts(at)).param("k", idempotencyKey).update();
    }

    @Override
    public void releaseDebited(String idempotencyKey, String balanceTransactionId, Instant at) {
        jdbc.sql("""
                update earnings_release set stripe_balance_transaction_id = :txn, updated_at = :at
                where idempotency_key = :k
                """).param("txn", balanceTransactionId).param("at", Sql.ts(at)).param("k", idempotencyKey).update();
    }

    private Row map(ResultSet rs, int n) throws SQLException {
        long amount = rs.getLong("invoice_amount_cents");
        Long invoiceAmount = rs.wasNull() ? null : amount;
        return new Row(rs.getString("stripe_invoice_id"), rs.getString("org_id"), rs.getString("invoice_number"),
                invoiceAmount, rs.getLong("amount_cents"), rs.getString("status"),
                rs.getLong("withheld_cents"), rs.getLong("credited_cents"), rs.getLong("released_cents"),
                rs.getString("payments_reference"), rs.getString("stripe_balance_transaction_id"),
                rs.getString("reason"), rs.getInt("attempts"), rs.getString("last_error"),
                Sql.instant(rs, "next_attempt_at"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));
    }
}
