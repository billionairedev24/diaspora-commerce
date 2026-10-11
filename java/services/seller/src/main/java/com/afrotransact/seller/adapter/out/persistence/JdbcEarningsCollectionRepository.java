package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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
    public boolean claim(String invoiceId, String orgId, long amountCents, Instant at) {
        return jdbc.sql("""
                insert into earnings_collection (stripe_invoice_id, org_id, amount_cents, status, created_at,
                                                 updated_at)
                values (:i, :o, :a, 'REQUESTED', :at, :at) on conflict do nothing
                """).param("i", invoiceId).param("o", orgId).param("a", amountCents).param("at", Sql.ts(at))
                .update() == 1;
    }

    @Override
    public void update(String invoiceId, String status, String paymentsReference, String balanceTransactionId,
                       String reason, Instant at) {
        jdbc.sql("""
                update earnings_collection set status = :s,
                    payments_reference = coalesce(:ref, payments_reference),
                    stripe_balance_transaction_id = coalesce(:txn, stripe_balance_transaction_id),
                    reason = :reason, updated_at = :at
                where stripe_invoice_id = :i
                """).param("s", status).param("ref", paymentsReference).param("txn", balanceTransactionId)
                .param("reason", reason).param("at", Sql.ts(at)).param("i", invoiceId).update();
    }

    @Override
    public Optional<Row> find(String invoiceId) {
        return jdbc.sql("select * from earnings_collection where stripe_invoice_id = :i").param("i", invoiceId)
                .query(this::map).optional();
    }

    @Override
    public List<Row> open(int maxAttempts, int limit) {
        List<Row> rows = jdbc.sql("""
                select * from earnings_collection where status in ('REQUESTED', 'WITHHELD') and attempts < :max
                order by created_at limit :limit for update skip locked
                """).param("max", maxAttempts).param("limit", limit).query(this::map).list();
        for (Row r : rows) {
            jdbc.sql("update earnings_collection set attempts = attempts + 1 where stripe_invoice_id = :i")
                    .param("i", r.invoiceId()).update();
        }
        return rows;
    }

    @Override
    public List<Row> forOrg(String orgId, int limit) {
        return jdbc.sql("select * from earnings_collection where org_id = :o order by created_at desc limit :l")
                .param("o", orgId).param("l", limit).query(this::map).list();
    }

    private Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getString("stripe_invoice_id"), rs.getString("org_id"), rs.getLong("amount_cents"),
                rs.getString("status"), rs.getString("payments_reference"),
                rs.getString("stripe_balance_transaction_id"), rs.getString("reason"), Sql.instant(rs, "created_at"));
    }
}
