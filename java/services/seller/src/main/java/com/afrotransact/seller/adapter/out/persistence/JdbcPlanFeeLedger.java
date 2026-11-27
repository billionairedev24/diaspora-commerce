package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.PlanFeeDispute;
import com.afrotransact.seller.domain.PlanFeeRefund;
import com.afrotransact.seller.port.PlanFeeLedger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlanFeeLedger implements PlanFeeLedger {

    private final JdbcClient jdbc;

    JdbcPlanFeeLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean addRefund(PlanFeeRefund r) {
        return jdbc.sql("""
                insert into invoice_refund (id, invoice_id, org_id, payment_intent_id, stripe_refund_id, amount_cents,
                                            status, origin, reason_code, note, requested_by, idempotency_key,
                                            created_at, updated_at)
                values (:id, :inv, :org, :pi, :re, :amount, :status, :origin, :reason, :note, :by, :key, :at, :at)
                on conflict do nothing
                """).param("id", r.id()).param("inv", r.invoiceId()).param("org", r.orgId())
                .param("pi", r.paymentIntentId()).param("re", r.stripeRefundId()).param("amount", r.amountCents())
                .param("status", r.status()).param("origin", r.origin()).param("reason", r.reasonCode())
                .param("note", r.note()).param("by", r.requestedBy()).param("key", r.idempotencyKey())
                .param("at", Sql.ts(r.createdAt())).update() == 1;
    }

    @Override
    public Optional<PlanFeeRefund> refundByKey(String idempotencyKey) {
        return jdbc.sql("select * from invoice_refund where idempotency_key = :k").param("k", idempotencyKey)
                .query(JdbcPlanFeeLedger::refund).optional();
    }

    @Override
    public List<PlanFeeRefund> refunds(String invoiceId) {
        return jdbc.sql("select * from invoice_refund where invoice_id = :i order by created_at").param("i", invoiceId)
                .query(JdbcPlanFeeLedger::refund).list();
    }

    @Override
    public List<PlanFeeRefund> refundsOfIntent(String paymentIntentId) {
        return jdbc.sql("select * from invoice_refund where payment_intent_id = :p order by created_at for update")
                .param("p", paymentIntentId).query(JdbcPlanFeeLedger::refund).list();
    }

    @Override
    public void setRefund(UUID id, String status, String stripeRefundId, Instant at) {
        jdbc.sql("update invoice_refund set status = :s, stripe_refund_id = coalesce(:re, stripe_refund_id), "
                        + "updated_at = :at where id = :id")
                .param("s", status).param("re", stripeRefundId).param("at", Sql.ts(at)).param("id", id).update();
    }

    @Override
    public boolean addDispute(PlanFeeDispute d) {
        return jdbc.sql("""
                insert into invoice_dispute (id, stripe_dispute_id, invoice_id, org_id, payment_intent_id, charge_id,
                                             amount_cents, reason, stripe_status, outcome, funds_withdrawn, created_at,
                                             updated_at, closed_at)
                values (:id, :dp, :inv, :org, :pi, :ch, :amount, :reason, :ss, :outcome, :fw, :at, :at, :closed)
                on conflict (stripe_dispute_id) do nothing
                """).param("id", d.id()).param("dp", d.stripeDisputeId()).param("inv", d.invoiceId())
                .param("org", d.orgId()).param("pi", d.paymentIntentId()).param("ch", d.chargeId())
                .param("amount", d.amountCents()).param("reason", d.reason()).param("ss", d.stripeStatus())
                .param("outcome", d.outcome()).param("fw", d.fundsWithdrawn()).param("at", Sql.ts(d.createdAt()))
                .param("closed", Sql.ts(d.closedAt())).update() == 1;
    }

    @Override
    public Optional<PlanFeeDispute> lockDispute(String stripeDisputeId) {
        return jdbc.sql("select * from invoice_dispute where stripe_dispute_id = :d for update")
                .param("d", stripeDisputeId).query(JdbcPlanFeeLedger::dispute).optional();
    }

    @Override
    public void updateDispute(String stripeDisputeId, String stripeStatus, String outcome, boolean fundsWithdrawn,
                              Instant closedAt, Instant at) {
        jdbc.sql("""
                update invoice_dispute set stripe_status = :ss, outcome = :outcome, funds_withdrawn = :fw,
                       closed_at = coalesce(:closed, closed_at), updated_at = :at
                where stripe_dispute_id = :d
                """).param("ss", stripeStatus).param("outcome", outcome).param("fw", fundsWithdrawn)
                .param("closed", Sql.ts(closedAt)).param("at", Sql.ts(at)).param("d", stripeDisputeId).update();
    }

    @Override
    public List<PlanFeeDispute> disputes(String invoiceId) {
        return jdbc.sql("select * from invoice_dispute where invoice_id = :i order by created_at")
                .param("i", invoiceId).query(JdbcPlanFeeLedger::dispute).list();
    }

    @Override
    public List<PlanFeeDispute> disputesByOutcome(String outcome, int limit) {
        return jdbc.sql("select * from invoice_dispute where (cast(:o as varchar) is null or outcome = :o) "
                        + "order by created_at desc limit :l")
                .param("o", outcome).param("l", limit).query(JdbcPlanFeeLedger::dispute).list();
    }

    private static PlanFeeRefund refund(ResultSet rs, int n) throws SQLException {
        return new PlanFeeRefund(rs.getObject("id", UUID.class), rs.getString("invoice_id"), rs.getString("org_id"),
                rs.getString("payment_intent_id"), rs.getString("stripe_refund_id"), rs.getLong("amount_cents"),
                rs.getString("status"), rs.getString("origin"), rs.getString("reason_code"), rs.getString("note"),
                rs.getString("requested_by"), rs.getString("idempotency_key"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"));
    }

    private static PlanFeeDispute dispute(ResultSet rs, int n) throws SQLException {
        return new PlanFeeDispute(rs.getObject("id", UUID.class), rs.getString("stripe_dispute_id"),
                rs.getString("invoice_id"), rs.getString("org_id"), rs.getString("payment_intent_id"),
                rs.getString("charge_id"), rs.getLong("amount_cents"), rs.getString("reason"),
                rs.getString("stripe_status"), rs.getString("outcome"), rs.getBoolean("funds_withdrawn"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), Sql.instant(rs, "closed_at"));
    }
}
