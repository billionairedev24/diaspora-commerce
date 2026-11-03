package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.RecoveryResolution;
import com.afrotransact.payments.domain.TransferRecovery;
import com.afrotransact.payments.domain.TransferRecovery.ProposedResolution;
import com.afrotransact.payments.domain.TransferRecoveryStatus;
import com.afrotransact.payments.port.TransferRecoveryRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTransferRecoveryRepository implements TransferRecoveryRepository {

    private static final String OUTSTANDING = "amount_minor - recovered_minor - resolved_minor";

    private final JdbcClient jdbc;

    JdbcTransferRecoveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(TransferRecovery r) {
        return jdbc.sql("""
                insert into transfer_recovery (id, refund_id, order_id, org_id, transfer_id, stripe_transfer_id,
                    currency, amount_minor, recovered_minor, resolved_minor, status, stripe_code, reason, journal_id,
                    created_at, updated_at, version)
                values (:id, :refund, :order, :org, :transfer, :stripeTransfer, :currency, :amount, :recovered,
                    :resolved, :status, :code, :reason, :journal, :created, :updated, 0)
                on conflict (refund_id, transfer_id) do nothing
                """)
                .param("id", r.id()).param("refund", r.refundId()).param("order", r.orderId())
                .param("org", r.orgId()).param("transfer", r.transferId()).param("stripeTransfer", r.stripeTransferId())
                .param("currency", r.amount().currency()).param("amount", r.amount().minor())
                .param("recovered", r.recovered().minor()).param("resolved", r.resolved().minor())
                .param("status", r.status().name()).param("code", r.stripeCode()).param("reason", r.reason())
                .param("journal", r.journalId()).param("created", Sql.ts(r.createdAt()))
                .param("updated", Sql.ts(r.updatedAt()))
                .update() == 1;
    }

    @Override
    public Optional<TransferRecovery> find(UUID id) {
        return jdbc.sql("select * from transfer_recovery where id = :i").param("i", id)
                .query(JdbcTransferRecoveryRepository::row).optional();
    }

    @Override
    public Optional<TransferRecovery> lock(UUID id) {
        return jdbc.sql("select * from transfer_recovery where id = :i for update").param("i", id)
                .query(JdbcTransferRecoveryRepository::row).optional();
    }

    @Override
    public TransferRecovery update(TransferRecovery r) {
        ProposedResolution p = r.pending();
        int updated = jdbc.sql("""
                update transfer_recovery set recovered_minor = :recovered, resolved_minor = :resolved,
                    status = :status, pending_kind = :pKind, pending_reason = :pReason,
                    pending_reference = :pRef, pending_by = :pBy, pending_at = :pAt, decided_by = :decidedBy,
                    decided_at = :decidedAt, decision_note = :note, resolution = :resolution,
                    resolution_ref = :resolutionRef, updated_at = :updated, version = version + 1
                where id = :id and version = :version
                """)
                .param("recovered", r.recovered().minor()).param("resolved", r.resolved().minor())
                .param("status", r.status().name()).param("pKind", p == null ? null : p.kind().name())
                .param("pReason", p == null ? null : p.reason()).param("pRef", p == null ? null : p.reference())
                .param("pBy", p == null ? null : p.by()).param("pAt", p == null ? null : Sql.ts(p.at()))
                .param("decidedBy", r.decidedBy()).param("decidedAt", Sql.ts(r.decidedAt()))
                .param("note", r.decisionNote()).param("resolution", r.resolution() == null ? null
                        : r.resolution().name())
                .param("resolutionRef", r.resolutionRef()).param("updated", Sql.ts(r.updatedAt()))
                .param("id", r.id()).param("version", r.version())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Transfer recovery " + r.id() + " was changed concurrently");
        }
        return new TransferRecovery(r.id(), r.refundId(), r.orderId(), r.orgId(), r.transferId(),
                r.stripeTransferId(), r.amount(), r.recovered(), r.resolved(), r.status(), r.stripeCode(), r.reason(),
                r.pending(), r.decidedBy(), r.decidedAt(), r.decisionNote(), r.resolution(), r.resolutionRef(),
                r.journalId(), r.createdAt(), r.updatedAt(), r.version() + 1);
    }

    @Override
    public List<TransferRecovery> lockOutstanding(String orgId, String currency) {
        return jdbc.sql("select * from transfer_recovery where org_id = :o and currency = :c and status = 'OPEN' "
                        + "and " + OUTSTANDING + " > 0 order by created_at, id for update")
                .param("o", orgId).param("c", currency).query(JdbcTransferRecoveryRepository::row).list();
    }

    @Override
    public long outstanding(String orgId, String currency) {
        return jdbc.sql("select coalesce(sum(" + OUTSTANDING + "), 0) from transfer_recovery "
                        + "where org_id = :o and currency = :c and status = 'OPEN'")
                .param("o", orgId).param("c", currency).query(Long.class).single();
    }

    @Override
    public List<TransferRecovery> list(TransferRecoveryStatus status, String orgId, int limit) {
        return jdbc.sql("select * from transfer_recovery where (cast(:s as varchar) is null or status = :s) "
                        + "and (cast(:o as varchar) is null or org_id = :o) order by created_at desc, id limit :l")
                .param("s", status == null ? null : status.name()).param("o", orgId).param("l", limit)
                .query(JdbcTransferRecoveryRepository::row).list();
    }

    @Override
    public List<TransferRecovery> ofRefund(UUID refundId) {
        return jdbc.sql("select * from transfer_recovery where refund_id = :r order by created_at, id")
                .param("r", refundId).query(JdbcTransferRecoveryRepository::row).list();
    }

    @Override
    public void insertDeduction(UUID id, UUID recoveryId, String orderId, Money amount, UUID journalId,
                                Instant at) {
        jdbc.sql("""
                insert into transfer_recovery_deduction (id, recovery_id, order_id, amount_minor, journal_id,
                    created_at)
                values (:id, :r, :o, :a, :j, :at)
                """)
                .param("id", id).param("r", recoveryId).param("o", orderId).param("a", amount.minor())
                .param("j", journalId).param("at", Sql.ts(at)).update();
    }

    private static TransferRecovery row(ResultSet rs, int n) throws SQLException {
        String currency = rs.getString("currency");
        String pendingKind = rs.getString("pending_kind");
        ProposedResolution pending = pendingKind == null ? null : new ProposedResolution(
                RecoveryResolution.valueOf(pendingKind), rs.getString("pending_reason"),
                rs.getString("pending_reference"), rs.getString("pending_by"), Sql.instant(rs, "pending_at"));
        String resolution = rs.getString("resolution");
        return new TransferRecovery(rs.getObject("id", UUID.class), rs.getObject("refund_id", UUID.class),
                rs.getString("order_id"), rs.getString("org_id"), rs.getObject("transfer_id", UUID.class),
                rs.getString("stripe_transfer_id"), Money.of(rs.getLong("amount_minor"), currency),
                Money.of(rs.getLong("recovered_minor"), currency), Money.of(rs.getLong("resolved_minor"), currency),
                TransferRecoveryStatus.valueOf(rs.getString("status")), rs.getString("stripe_code"),
                rs.getString("reason"), pending, rs.getString("decided_by"), Sql.instant(rs, "decided_at"),
                rs.getString("decision_note"), resolution == null ? null : RecoveryResolution.valueOf(resolution),
                rs.getString("resolution_ref"), rs.getObject("journal_id", UUID.class),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
