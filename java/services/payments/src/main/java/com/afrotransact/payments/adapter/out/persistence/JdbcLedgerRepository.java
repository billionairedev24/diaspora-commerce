package com.afrotransact.payments.adapter.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.AccountBalance;
import com.afrotransact.payments.domain.Chart;
import com.afrotransact.payments.domain.EntrySide;
import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.JournalLine;
import com.afrotransact.payments.domain.JournalType;
import com.afrotransact.payments.domain.LedgerAccount;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.LedgerRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLedgerRepository implements LedgerRepository {

    private static final String SIGNED = "case l.side when 'D' then l.amount_minor else -l.amount_minor end";

    private final JdbcClient jdbc;

    JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(String journalKey) {
        return jdbc.sql("select count(*) from journal_entry where journal_key = :k").param("k", journalKey)
                .query(Long.class).single() > 0;
    }

    @Override
    public void insert(Journal j, String postedBy, Instant postedAt) {
        jdbc.sql("""
                insert into journal_entry (id, journal_key, type, order_id, payment_id, stripe_ref, description,
                    occurred_at, posted_at, posted_by)
                values (:id, :key, :type, :orderId, :paymentId, :ref, :description, :occurredAt, :postedAt, :by)
                """)
                .param("id", j.id()).param("key", j.key()).param("type", j.type().name())
                .param("orderId", j.orderId()).param("paymentId", j.paymentId()).param("ref", j.stripeRef())
                .param("description", j.description()).param("occurredAt", Sql.ts(j.occurredAt()))
                .param("postedAt", Sql.ts(postedAt)).param("by", postedBy)
                .update();
        int no = 0;
        for (JournalLine l : j.lines()) {
            jdbc.sql("""
                    insert into journal_line (journal_id, line_no, account_code, account_key, side, amount_minor,
                        currency, org_id, order_id)
                    values (:jid, :no, :code, :key, :side, :amount, :currency, :org, :orderId)
                    """)
                    .param("jid", j.id()).param("no", ++no).param("code", l.account().chart().code())
                    .param("key", l.account().key()).param("side", l.side().code())
                    .param("amount", l.amount().minor()).param("currency", l.amount().currency())
                    .param("org", l.orgId()).param("orderId", j.orderId())
                    .update();
        }
    }

    @Override
    public Optional<Journal> findByKey(String journalKey) {
        return journals("where e.journal_key = :v", journalKey).stream().findFirst();
    }

    @Override
    public List<Journal> journalsOfOrder(String orderId) {
        return journals("where e.order_id = :v", orderId);
    }

    private List<Journal> journals(String where, Object value) {
        List<Row> rows = jdbc.sql("""
                select e.id, e.journal_key, e.type, e.order_id, e.payment_id, e.stripe_ref, e.description,
                       e.occurred_at, e.posted_at, l.line_no, l.account_code, l.account_key, l.side, l.amount_minor,
                       l.currency, l.org_id
                from journal_entry e join journal_line l on l.journal_id = e.id
                """ + where + " order by e.posted_at, e.id, l.line_no")
                .param("v", value)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("journal_key"),
                        JournalType.valueOf(rs.getString("type")), rs.getString("order_id"),
                        rs.getString("payment_id"), rs.getString("stripe_ref"), rs.getString("description"),
                        Sql.instant(rs, "occurred_at"), new JournalLine(
                                new LedgerAccount(Chart.ofCode(rs.getString("account_code")),
                                        rs.getString("account_key")),
                                EntrySide.ofCode(rs.getString("side")),
                                Money.of(rs.getLong("amount_minor"), rs.getString("currency")),
                                rs.getString("org_id"))))
                .list();
        Map<UUID, List<Row>> byJournal = new LinkedHashMap<>();
        rows.forEach(r -> byJournal.computeIfAbsent(r.id(), k -> new ArrayList<>()).add(r));
        List<Journal> out = new ArrayList<>();
        for (List<Row> group : byJournal.values()) {
            Row h = group.getFirst();
            out.add(new Journal(h.id(), h.key(), h.type(), h.orderId(), h.paymentId(), h.stripeRef(),
                    h.description(), h.occurredAt(), group.stream().map(Row::line).toList()));
        }
        return out;
    }

    @Override
    public List<AccountBalance> balances(Optional<String> orgId) {
        String where = orgId.isPresent() ? " where l.org_id = :org" : "";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                select l.account_code, l.account_key, l.currency,
                       sum(case l.side when 'D' then l.amount_minor else 0 end) as debits,
                       sum(case l.side when 'C' then l.amount_minor else 0 end) as credits
                from journal_line l
                """ + where + " group by l.account_code, l.account_key, l.currency"
                + " order by l.account_code, l.account_key, l.currency");
        if (orgId.isPresent()) {
            spec = spec.param("org", orgId.get());
        }
        return spec.query((rs, n) -> new AccountBalance(new LedgerAccount(Chart.ofCode(rs.getString("account_code")),
                        rs.getString("account_key")), rs.getString("currency"), rs.getLong("debits"),
                        rs.getLong("credits")))
                .list();
    }

    @Override
    public Map<String, Long> payableByOrder(String orgId, String currency) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("select coalesce(l.order_id, '') as order_id, sum(-(" + SIGNED + ")) as balance "
                        + "from journal_line l where l.account_code = :code and l.account_key = :org "
                        + "and l.currency = :currency group by l.order_id "
                        + "having sum(" + SIGNED + ") <> 0 order by l.order_id")
                .param("code", Chart.SELLER_PAYABLE.code()).param("org", orgId).param("currency", currency)
                .query(rs -> {
                    out.put(rs.getString("order_id"), rs.getLong("balance"));
                });
        return out;
    }

    @Override
    public Map<String, Long> payableByOrg(String orderId, String currency) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("select l.account_key, sum(-(" + SIGNED + ")) as balance from journal_line l "
                        + "where l.account_code = :code and l.order_id = :orderId and l.currency = :currency "
                        + "group by l.account_key having sum(" + SIGNED + ") <> 0 order by l.account_key")
                .param("code", Chart.SELLER_PAYABLE.code()).param("orderId", orderId).param("currency", currency)
                .query(rs -> {
                    out.put(rs.getString("account_key"), rs.getLong("balance"));
                });
        return out;
    }

    @Override
    public Map<String, Long> clearingByRef(Instant from, Instant to, String currency) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("select coalesce(e.stripe_ref, '(no-stripe-ref)') as ref, sum(" + SIGNED + ") as net "
                        + "from journal_line l join journal_entry e on e.id = l.journal_id "
                        + "where l.account_code = :code and l.currency = :currency "
                        + "and e.posted_at >= :from and e.posted_at < :to group by 1 order by 1")
                .param("code", Chart.STRIPE_CLEARING.code()).param("currency", currency)
                .param("from", Sql.ts(from)).param("to", Sql.ts(to))
                .query(rs -> {
                    out.put(rs.getString("ref"), rs.getLong("net"));
                });
        return out;
    }

    private record Row(UUID id, String key, JournalType type, String orderId, String paymentId, String stripeRef,
                       String description, Instant occurredAt, JournalLine line) {
    }
}
