package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.CreditEntry;
import com.afrotransact.payments.domain.CreditKind;
import com.afrotransact.payments.domain.CreditSource;
import com.afrotransact.payments.port.CreditLedgerRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCreditLedgerRepository implements CreditLedgerRepository {

    private final JdbcClient jdbc;

    JdbcCreditLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long lockBalance(String customerId, String currency, Instant now) {
        jdbc.sql("""
                insert into credit_account (customer_id, currency, balance_minor, created_at, updated_at)
                values (:c, :currency, 0, :now, :now) on conflict (customer_id) do nothing
                """)
                .param("c", customerId).param("currency", currency).param("now", Sql.ts(now)).update();
        return jdbc.sql("select balance_minor from credit_account where customer_id = :c for update")
                .param("c", customerId).query(Long.class).single();
    }

    @Override
    public long balance(String customerId) {
        return jdbc.sql("select balance_minor from credit_account where customer_id = :c").param("c", customerId)
                .query(Long.class).optional().orElse(0L);
    }

    @Override
    public Optional<CreditEntry> findByKey(String idempotencyKey) {
        return jdbc.sql("select * from credit_entry where idempotency_key = :k").param("k", idempotencyKey)
                .query(this::map).optional();
    }

    @Override
    public void append(CreditEntry e) {
        jdbc.sql("""
                insert into credit_entry (id, customer_id, kind, amount_minor, currency, source, reason, actor,
                    order_id, reference, idempotency_key, balance_after_minor, created_at)
                values (:id, :c, :kind, :amount, :currency, :source, :reason, :actor, :order, :ref, :key, :after,
                    :at)
                """)
                .param("id", e.id()).param("c", e.customerId()).param("kind", e.kind().name())
                .param("amount", e.amountMinor()).param("currency", e.currency()).param("source", e.source().name())
                .param("reason", e.reason()).param("actor", e.actor()).param("order", e.orderId())
                .param("ref", e.reference()).param("key", e.idempotencyKey()).param("after", e.balanceAfterMinor())
                .param("at", Sql.ts(e.createdAt())).update();
        jdbc.sql("update credit_account set balance_minor = :b, updated_at = :at where customer_id = :c")
                .param("b", e.balanceAfterMinor()).param("at", Sql.ts(e.createdAt())).param("c", e.customerId())
                .update();
    }

    @Override
    public List<CreditEntry> entries(String customerId, Instant before, int limit) {
        return jdbc.sql("""
                select * from credit_entry
                where customer_id = :c and (cast(:before as timestamptz) is null or created_at < :before)
                order by created_at desc, id desc limit :n
                """)
                .param("c", customerId).param("before", Sql.ts(before)).param("n", limit).query(this::map).list();
    }

    @Override
    public List<CreditEntry> entriesOfOrder(String orderId) {
        return jdbc.sql("select * from credit_entry where order_id = :o order by created_at, id")
                .param("o", orderId).query(this::map).list();
    }

    @Override
    public long sumOfEntries(String customerId) {
        return jdbc.sql("select coalesce(sum(amount_minor), 0) from credit_entry where customer_id = :c")
                .param("c", customerId).query(Long.class).single();
    }

    @Override
    public Totals totals() {
        long outstanding = jdbc.sql("select coalesce(sum(balance_minor), 0) from credit_account")
                .query(Long.class).single();
        long accounts = jdbc.sql("select count(*) from credit_account where balance_minor > 0")
                .query(Long.class).single();
        Map<String, Long> flows = new LinkedHashMap<>();
        jdbc.sql("select kind || '/' || source as k, sum(amount_minor) as s from credit_entry group by 1 order by 1")
                .query((rs, n) -> {
                    flows.put(rs.getString("k"), rs.getLong("s"));
                    return 0;
                }).list();
        return new Totals(outstanding, accounts, flows);
    }

    private CreditEntry map(ResultSet rs, int n) throws SQLException {
        return new CreditEntry(rs.getObject("id", UUID.class), rs.getString("customer_id"),
                CreditKind.valueOf(rs.getString("kind")), rs.getLong("amount_minor"), rs.getString("currency"),
                CreditSource.valueOf(rs.getString("source")), rs.getString("reason"), rs.getString("actor"),
                rs.getString("order_id"), rs.getString("reference"), rs.getString("idempotency_key"),
                rs.getLong("balance_after_minor"), Sql.instant(rs, "created_at"));
    }
}
