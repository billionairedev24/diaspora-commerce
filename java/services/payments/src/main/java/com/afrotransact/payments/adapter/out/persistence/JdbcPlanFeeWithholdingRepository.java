package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PlanFeeDeduction;
import com.afrotransact.payments.domain.PlanFeeWithholding;
import com.afrotransact.payments.domain.PlanFeeWithholdingStatus;
import com.afrotransact.payments.port.PlanFeeWithholdingRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlanFeeWithholdingRepository implements PlanFeeWithholdingRepository {

    private static final String OUTSTANDING = "withheld_minor - released_minor - settled_minor";

    private final JdbcClient jdbc;

    JdbcPlanFeeWithholdingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(PlanFeeWithholding w) {
        return jdbc.sql("""
                insert into plan_fee_withholding (id, org_id, invoice_id, invoice_number, currency, requested_minor,
                    withheld_minor, released_minor, settled_minor, status, reason, journal_id, created_at, updated_at,
                    version)
                values (:id, :org, :invoice, :number, :currency, :requested, :withheld, :released, :settled, :status,
                    :reason, :journal, :created, :updated, 0)
                on conflict (invoice_id) do nothing
                """)
                .param("id", w.id()).param("org", w.orgId()).param("invoice", w.invoiceId())
                .param("number", w.invoiceNumber()).param("currency", w.requested().currency())
                .param("requested", w.requested().minor()).param("withheld", w.withheld().minor())
                .param("released", w.released().minor()).param("settled", w.settled().minor())
                .param("status", w.status().name()).param("reason", w.reason()).param("journal", w.journalId())
                .param("created", Sql.ts(w.createdAt())).param("updated", Sql.ts(w.updatedAt()))
                .update() == 1;
    }

    @Override
    public Optional<PlanFeeWithholding> findByInvoice(String invoiceId) {
        return jdbc.sql("select * from plan_fee_withholding where invoice_id = :i").param("i", invoiceId)
                .query(JdbcPlanFeeWithholdingRepository::row).optional();
    }

    @Override
    public Optional<PlanFeeWithholding> lockByInvoice(String invoiceId) {
        return jdbc.sql("select * from plan_fee_withholding where invoice_id = :i for update").param("i", invoiceId)
                .query(JdbcPlanFeeWithholdingRepository::row).optional();
    }

    @Override
    public PlanFeeWithholding update(PlanFeeWithholding w) {
        int updated = jdbc.sql("""
                update plan_fee_withholding set released_minor = :released, settled_minor = :settled,
                    status = :status, updated_at = :updated, version = version + 1
                where id = :id and version = :version
                """)
                .param("released", w.released().minor()).param("settled", w.settled().minor())
                .param("status", w.status().name()).param("updated", Sql.ts(w.updatedAt())).param("id", w.id())
                .param("version", w.version())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Plan fee " + w.invoiceId() + " was changed concurrently");
        }
        return new PlanFeeWithholding(w.id(), w.orgId(), w.invoiceId(), w.invoiceNumber(), w.requested(),
                w.withheld(), w.released(), w.settled(), w.status(), w.reason(), w.journalId(), w.createdAt(),
                w.updatedAt(), w.version() + 1);
    }

    @Override
    public List<PlanFeeWithholding> lockOutstanding(String orgId, String currency) {
        return jdbc.sql("select * from plan_fee_withholding where org_id = :o and currency = :c and " + OUTSTANDING
                        + " <> 0 order by created_at, id for update")
                .param("o", orgId).param("c", currency).query(JdbcPlanFeeWithholdingRepository::row).list();
    }

    @Override
    public long outstanding(String orgId, String currency) {
        return jdbc.sql("select coalesce(sum(" + OUTSTANDING + "), 0) from plan_fee_withholding "
                        + "where org_id = :o and currency = :c")
                .param("o", orgId).param("c", currency).query(Long.class).single();
    }

    @Override
    public List<PlanFeeWithholding> ofOrg(String orgId, int limit) {
        return jdbc.sql("select * from plan_fee_withholding where org_id = :o order by created_at desc, id limit :l")
                .param("o", orgId).param("l", limit).query(JdbcPlanFeeWithholdingRepository::row).list();
    }

    @Override
    public boolean insertRelease(String idempotencyKey, UUID withholdingId, Money amount, String reason,
                                 UUID journalId, Instant at) {
        return jdbc.sql("""
                insert into plan_fee_release (idempotency_key, withholding_id, amount_minor, reason, journal_id,
                    created_at)
                values (:k, :w, :a, :r, :j, :at) on conflict (idempotency_key) do nothing
                """)
                .param("k", idempotencyKey).param("w", withholdingId).param("a", amount.minor()).param("r", reason)
                .param("j", journalId).param("at", Sql.ts(at)).update() == 1;
    }

    @Override
    public Optional<UUID> releaseOf(String idempotencyKey) {
        return jdbc.sql("select withholding_id from plan_fee_release where idempotency_key = :k")
                .param("k", idempotencyKey).query((rs, n) -> rs.getObject(1, UUID.class)).optional();
    }

    @Override
    public void insertDeduction(UUID id, UUID withholdingId, String orderId, Money amount, UUID journalId,
                                Instant at) {
        jdbc.sql("""
                insert into plan_fee_deduction (id, withholding_id, order_id, amount_minor, journal_id, created_at)
                values (:id, :w, :o, :a, :j, :at)
                """)
                .param("id", id).param("w", withholdingId).param("o", orderId).param("a", amount.minor())
                .param("j", journalId).param("at", Sql.ts(at)).update();
    }

    @Override
    public Map<UUID, List<PlanFeeDeduction>> deductions(Collection<UUID> withholdingIds) {
        Map<UUID, List<PlanFeeDeduction>> out = new LinkedHashMap<>();
        if (withholdingIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select d.withholding_id, d.order_id, d.amount_minor, d.created_at, w.currency
                from plan_fee_deduction d join plan_fee_withholding w on w.id = d.withholding_id
                where d.withholding_id in (:ids) order by d.created_at, d.id
                """)
                .param("ids", List.copyOf(withholdingIds))
                .query(rs -> {
                    UUID w = rs.getObject("withholding_id", UUID.class);
                    out.computeIfAbsent(w, k -> new ArrayList<>()).add(new PlanFeeDeduction(w,
                            rs.getString("order_id"), Money.of(rs.getLong("amount_minor"), rs.getString("currency")),
                            Sql.instant(rs, "created_at")));
                });
        return out;
    }

    private static PlanFeeWithholding row(ResultSet rs, int n) throws SQLException {
        String currency = rs.getString("currency");
        return new PlanFeeWithholding(rs.getObject("id", UUID.class), rs.getString("org_id"),
                rs.getString("invoice_id"), rs.getString("invoice_number"),
                Money.of(rs.getLong("requested_minor"), currency), Money.of(rs.getLong("withheld_minor"), currency),
                Money.of(rs.getLong("released_minor"), currency), Money.of(rs.getLong("settled_minor"), currency),
                PlanFeeWithholdingStatus.valueOf(rs.getString("status")), rs.getString("reason"),
                rs.getObject("journal_id", UUID.class), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
