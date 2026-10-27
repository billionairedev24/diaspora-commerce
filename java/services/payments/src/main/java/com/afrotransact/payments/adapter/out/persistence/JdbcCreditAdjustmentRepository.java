package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.AdjustmentStatus;
import com.afrotransact.payments.domain.CreditAdjustment;
import com.afrotransact.payments.domain.CreditSource;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.CreditAdjustmentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCreditAdjustmentRepository implements CreditAdjustmentRepository {

    private final JdbcClient jdbc;

    JdbcCreditAdjustmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(CreditAdjustment a) {
        jdbc.sql("""
                insert into credit_adjustment (id, customer_id, currency, amount_minor, source, reason, status,
                    proposed_by, proposed_at, decided_by, decided_at, decision_note, entry_id)
                values (:id, :c, :currency, :amount, :source, :reason, :status, :by, :at, :decidedBy, :decidedAt,
                    :note, :entry)
                """)
                .param("id", a.id()).param("c", a.customerId()).param("currency", a.amount().currency())
                .param("amount", a.amount().minor()).param("source", a.source().name()).param("reason", a.reason())
                .param("status", a.status().name()).param("by", a.proposedBy()).param("at", Sql.ts(a.proposedAt()))
                .param("decidedBy", a.decidedBy()).param("decidedAt", Sql.ts(a.decidedAt()))
                .param("note", a.decisionNote()).param("entry", a.entryId()).update();
    }

    @Override
    public Optional<CreditAdjustment> find(UUID id) {
        return jdbc.sql("select * from credit_adjustment where id = :id").param("id", id).query(this::map)
                .optional();
    }

    @Override
    public Optional<CreditAdjustment> lock(UUID id) {
        return jdbc.sql("select * from credit_adjustment where id = :id for update").param("id", id)
                .query(this::map).optional();
    }

    @Override
    public CreditAdjustment update(CreditAdjustment a) {
        jdbc.sql("""
                update credit_adjustment set status = :status, decided_by = :by, decided_at = :at,
                    decision_note = :note, entry_id = :entry where id = :id
                """)
                .param("status", a.status().name()).param("by", a.decidedBy()).param("at", Sql.ts(a.decidedAt()))
                .param("note", a.decisionNote()).param("entry", a.entryId()).param("id", a.id()).update();
        return a;
    }

    @Override
    public List<CreditAdjustment> list(AdjustmentStatus status, String customerId, int limit) {
        return jdbc.sql("""
                select * from credit_adjustment
                where (cast(:status as varchar) is null or status = :status)
                  and (cast(:c as varchar) is null or customer_id = :c)
                order by proposed_at desc limit :limit
                """)
                .param("status", status == null ? null : status.name()).param("c", customerId)
                .param("limit", limit).query(this::map).list();
    }

    private CreditAdjustment map(ResultSet rs, int n) throws SQLException {
        return new CreditAdjustment(rs.getObject("id", UUID.class), rs.getString("customer_id"),
                Money.of(rs.getLong("amount_minor"), rs.getString("currency")),
                CreditSource.valueOf(rs.getString("source")), rs.getString("reason"),
                AdjustmentStatus.valueOf(rs.getString("status")), rs.getString("proposed_by"),
                Sql.instant(rs, "proposed_at"), rs.getString("decided_by"), Sql.instant(rs, "decided_at"),
                rs.getString("decision_note"), rs.getObject("entry_id", UUID.class));
    }
}
