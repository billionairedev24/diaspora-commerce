package com.afrotransact.taxreturns.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.tax.TaxRemittance;
import com.afrotransact.taxreturns.port.TaxRemittanceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTaxRemittanceRepository implements TaxRemittanceRepository {

    private final JdbcClient jdbc;

    JdbcTaxRemittanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TaxRemittance r) {
        jdbc.sql("""
                insert into tax_remittance (id, jurisdiction, period, currency, due_minor, paid_minor, filed_on, paid_on,
                    confirmation_ref, payment_ref, note, recorded_by, recorded_at)
                values (:id, :j, :period, :cur, :due, :paid, :filed, :paidOn, :conf, :pay, :note, :by, :at)
                """)
                .param("id", r.id()).param("j", r.jurisdiction()).param("period", Date.valueOf(r.month().atDay(1)))
                .param("cur", r.currency()).param("due", r.dueMinor()).param("paid", r.paidMinor())
                .param("filed", Date.valueOf(r.filedOn())).param("paidOn", Date.valueOf(r.paidOn()))
                .param("conf", r.confirmationRef()).param("pay", r.paymentRef()).param("note", r.note())
                .param("by", r.recordedBy()).param("at", Timestamp.from(r.recordedAt())).update();
    }

    @Override
    public Optional<TaxRemittance> find(UUID id) {
        return jdbc.sql("select * from tax_remittance where id = :id").param("id", id).query(this::row).optional();
    }

    @Override
    public List<TaxRemittance> between(YearMonth from, YearMonth to, boolean includeVoided) {
        return jdbc.sql("""
                select * from tax_remittance where period between :from and :to
                  and (:voided or voided_at is null)
                order by period desc, jurisdiction, recorded_at desc
                """)
                .param("from", Date.valueOf(from.atDay(1))).param("to", Date.valueOf(to.atDay(1)))
                .param("voided", includeVoided).query(this::row).list();
    }

    @Override
    public boolean markVoided(UUID id, String by, Instant at, String reason) {
        return jdbc.sql("""
                update tax_remittance set voided_at = :at, voided_by = :by, void_reason = :reason
                where id = :id and voided_at is null
                """)
                .param("id", id).param("at", Timestamp.from(at)).param("by", by).param("reason", reason)
                .update() == 1;
    }

    private TaxRemittance row(ResultSet rs, int n) throws SQLException {
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        return new TaxRemittance(rs.getObject("id", UUID.class), rs.getString("jurisdiction"),
                YearMonth.from(rs.getDate("period").toLocalDate()), rs.getString("currency"),
                rs.getLong("due_minor"), rs.getLong("paid_minor"), rs.getDate("filed_on").toLocalDate(),
                rs.getDate("paid_on").toLocalDate(), rs.getString("confirmation_ref"), rs.getString("payment_ref"),
                rs.getString("note"), rs.getString("recorded_by"), rs.getTimestamp("recorded_at").toInstant(),
                voidedAt == null ? null : voidedAt.toInstant(), rs.getString("voided_by"),
                rs.getString("void_reason"));
    }
}
