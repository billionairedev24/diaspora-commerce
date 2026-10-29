package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.AdjustmentStatus;
import com.afrotransact.payments.domain.Chart;
import com.afrotransact.payments.domain.EntrySide;
import com.afrotransact.payments.domain.JournalLine;
import com.afrotransact.payments.domain.LedgerAccount;
import com.afrotransact.payments.domain.LedgerAdjustment;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.LedgerAdjustmentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcLedgerAdjustmentRepository implements LedgerAdjustmentRepository {

    private static final TypeReference<List<LineRow>> LINES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcLedgerAdjustmentRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(LedgerAdjustment a) {
        jdbc.sql("""
                insert into ledger_adjustment (id, item_id, stripe_ref, currency, reason, lines, status, proposed_by,
                    proposed_at, decided_by, decided_at, decision_note, journal_id)
                values (:id, :item, :ref, :currency, :reason, cast(:lines as jsonb), :status, :by, :at, :decidedBy,
                    :decidedAt, :note, :journal)
                """)
                .param("id", a.id()).param("item", a.itemId()).param("ref", a.stripeRef())
                .param("currency", a.currency()).param("reason", a.reason()).param("lines", lines(a))
                .param("status", a.status().name()).param("by", a.proposedBy()).param("at", Sql.ts(a.proposedAt()))
                .param("decidedBy", a.decidedBy()).param("decidedAt", Sql.ts(a.decidedAt()))
                .param("note", a.decisionNote()).param("journal", a.journalId())
                .update();
    }

    @Override
    public Optional<LedgerAdjustment> find(UUID id) {
        return jdbc.sql("select *, lines::text as lines_json from ledger_adjustment where id = :id").param("id", id)
                .query(this::map).optional();
    }

    @Override
    public Optional<LedgerAdjustment> lock(UUID id) {
        return jdbc.sql("select *, lines::text as lines_json from ledger_adjustment where id = :id for update")
                .param("id", id).query(this::map).optional();
    }

    @Override
    public LedgerAdjustment update(LedgerAdjustment a) {
        jdbc.sql("""
                update ledger_adjustment set status = :status, decided_by = :by, decided_at = :at,
                    decision_note = :note, journal_id = :journal
                where id = :id
                """)
                .param("status", a.status().name()).param("by", a.decidedBy()).param("at", Sql.ts(a.decidedAt()))
                .param("note", a.decisionNote()).param("journal", a.journalId()).param("id", a.id()).update();
        return a;
    }

    @Override
    public List<LedgerAdjustment> list(AdjustmentStatus status, int limit) {
        return jdbc.sql("""
                select *, lines::text as lines_json from ledger_adjustment
                where (cast(:status as varchar) is null or status = :status)
                order by proposed_at desc limit :limit
                """)
                .param("status", status == null ? null : status.name()).param("limit", limit).query(this::map).list();
    }

    @Override
    public List<LedgerAdjustment> ofItem(UUID itemId) {
        return jdbc.sql("select *, lines::text as lines_json from ledger_adjustment where item_id = :item "
                        + "order by proposed_at desc")
                .param("item", itemId).query(this::map).list();
    }

    private String lines(LedgerAdjustment a) {
        return json.write(a.lines().stream().map(l -> new LineRow(l.account().chart().code(), l.account().key(),
                l.side().name(), l.amount().minor(), l.amount().currency(), l.orgId())).toList());
    }

    private LedgerAdjustment map(ResultSet rs, int n) throws SQLException {
        List<JournalLine> lines = json.read(rs.getString("lines_json"), LINES).stream()
                .map(l -> new JournalLine(new LedgerAccount(Chart.ofCode(l.code()), l.key()),
                        EntrySide.valueOf(l.side()), Money.of(l.amountMinor(), l.currency()), l.orgId())).toList();
        return new LedgerAdjustment(rs.getObject("id", UUID.class), rs.getObject("item_id", UUID.class),
                rs.getString("stripe_ref"), rs.getString("currency"), rs.getString("reason"), lines,
                AdjustmentStatus.valueOf(rs.getString("status")), rs.getString("proposed_by"),
                Sql.instant(rs, "proposed_at"), rs.getString("decided_by"), Sql.instant(rs, "decided_at"),
                rs.getString("decision_note"), rs.getObject("journal_id", UUID.class));
    }

    /** Stored line. */
    record LineRow(String code, String key, String side, long amountMinor, String currency, String orgId) {
    }
}
