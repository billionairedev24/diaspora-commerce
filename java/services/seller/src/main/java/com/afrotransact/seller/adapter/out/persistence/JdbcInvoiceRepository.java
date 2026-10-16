package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoiceLine;
import com.afrotransact.seller.domain.InvoicePayment;
import com.afrotransact.seller.domain.PaymentSource;
import com.afrotransact.seller.port.InvoiceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcInvoiceRepository implements InvoiceRepository {

    private static final TypeReference<List<InvoiceLine>> LINES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcInvoiceRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean insert(Invoice i) {
        return jdbc.sql("""
                insert into invoice (stripe_invoice_id, org_id, number, period_start, period_end, subtotal_cents,
                                     discount_cents, amount_due_cents, amount_paid_cents, currency, status,
                                     billing_reason, plan_code, plan_version, cadence, due_at, paid_at, attempts,
                                     next_attempt_at, last_error, created_at, updated_at, lines)
                values (:id, :org, :number, :start, :end, :subtotal, :discount, :due, :paid, :currency, :status,
                        :reason, :plan, :planVersion, :cadence, :dueAt, :paidAt, :attempts, :nextAttempt, :error,
                        :created, :created, cast(:lines as jsonb))
                on conflict do nothing
                """)
                .param("id", i.id()).param("org", i.orgId()).param("number", i.number())
                .param("start", Sql.ts(i.periodStart())).param("end", Sql.ts(i.periodEnd()))
                .param("subtotal", i.subtotalCents()).param("discount", i.discountCents())
                .param("due", i.amountDueCents()).param("paid", i.amountPaidCents())
                .param("currency", i.currency() == null ? "USD" : i.currency().toUpperCase(Locale.ROOT))
                .param("status", i.status()).param("reason", i.billingReason()).param("plan", i.planCode())
                .param("planVersion", i.planVersion()).param("cadence", i.cadence() == null ? null : i.cadence().name())
                .param("dueAt", Sql.ts(i.dueAt())).param("paidAt", Sql.ts(i.paidAt())).param("attempts", i.attempts())
                .param("nextAttempt", Sql.ts(i.nextAttemptAt())).param("error", i.lastError())
                .param("created", Sql.ts(i.createdAt())).param("lines", json.write(i.lines()))
                .update() == 1;
    }

    @Override
    public Optional<Invoice> findForPeriod(String orgId, Instant periodStart) {
        return jdbc.sql("""
                select * from invoice where org_id = :o and period_start = :start
                  and billing_reason in ('subscription_create', 'subscription_cycle')
                """)
                .param("o", orgId).param("start", Sql.ts(periodStart)).query(this::row).optional().map(this::withPayments);
    }

    @Override
    public List<Invoice> list(String orgId, int limit) {
        return jdbc.sql("select * from invoice where org_id = :o order by created_at desc limit :limit")
                .param("o", orgId).param("limit", limit)
                .query(this::row).list().stream().map(this::withPayments).toList();
    }

    @Override
    public Optional<Invoice> find(String orgId, String invoiceId) {
        return jdbc.sql("select * from invoice where org_id = :o and stripe_invoice_id = :id")
                .param("o", orgId).param("id", invoiceId).query(this::row).optional().map(this::withPayments);
    }

    @Override
    public Optional<Invoice> findById(String invoiceId) {
        return jdbc.sql("select * from invoice where stripe_invoice_id = :id")
                .param("id", invoiceId).query(this::row).optional().map(this::withPayments);
    }

    @Override
    public Optional<Invoice> lock(String invoiceId) {
        return jdbc.sql("select * from invoice where stripe_invoice_id = :id for update")
                .param("id", invoiceId).query(this::row).optional().map(this::withPayments);
    }

    @Override
    public List<String> dueForCollection(Instant now, int limit) {
        return jdbc.sql("""
                select stripe_invoice_id from invoice
                where status = 'open' and (next_attempt_at is null or next_attempt_at <= :now)
                order by created_at limit :limit
                """).param("now", Sql.ts(now)).param("limit", limit).query(String.class).list();
    }

    @Override
    public boolean addPayment(InvoicePayment p) {
        return jdbc.sql("""
                insert into invoice_payment (id, invoice_id, org_id, source, amount_cents, status, reference, note,
                                             created_at)
                values (:id, :invoice, :org, :source, :amount, :status, :reference, :note, :at)
                on conflict do nothing
                """)
                .param("id", p.id()).param("invoice", p.invoiceId()).param("org", p.orgId())
                .param("source", p.source().name()).param("amount", p.amountCents()).param("status", p.status())
                .param("reference", p.reference()).param("note", p.note()).param("at", Sql.ts(p.createdAt()))
                .update() == 1;
    }

    @Override
    public void setPaymentStatus(UUID paymentId, String status, String note) {
        jdbc.sql("update invoice_payment set status = :s, note = coalesce(:n, note) where id = :id")
                .param("s", status).param("n", note).param("id", paymentId).update();
    }

    @Override
    public Optional<InvoicePayment> findPaymentByReference(String reference) {
        return jdbc.sql("select * from invoice_payment where reference = :r and source = 'CARD' limit 1")
                .param("r", reference).query(JdbcInvoiceRepository::payment).optional();
    }

    @Override
    public void refreshPaid(String invoiceId, Instant at) {
        jdbc.sql("""
                update invoice set amount_paid_cents = coalesce((select sum(amount_cents) from invoice_payment
                    where invoice_id = :id and status = 'APPLIED'), 0), updated_at = :at, version = version + 1
                where stripe_invoice_id = :id
                """).param("id", invoiceId).param("at", Sql.ts(at)).update();
    }

    @Override
    public void markPaid(String invoiceId, Instant at) {
        jdbc.sql("""
                update invoice set status = 'paid', paid_at = :at, next_attempt_at = null, last_error = null,
                       updated_at = :at, version = version + 1
                where stripe_invoice_id = :id and status = 'open'
                """).param("id", invoiceId).param("at", Sql.ts(at)).update();
    }

    @Override
    public void markStatus(String invoiceId, String status, Instant at) {
        jdbc.sql("""
                update invoice set status = :s, next_attempt_at = null, updated_at = :at, version = version + 1
                where stripe_invoice_id = :id and status = 'open'
                """).param("id", invoiceId).param("s", status).param("at", Sql.ts(at)).update();
    }

    @Override
    public void reopen(String invoiceId, Instant at) {
        jdbc.sql("""
                update invoice set status = 'open', attempts = 0, next_attempt_at = null, last_error = null,
                       updated_at = :at, version = version + 1
                where stripe_invoice_id = :id and status in ('open', 'uncollectible')
                """).param("id", invoiceId).param("at", Sql.ts(at)).update();
    }

    @Override
    public void recordAttempt(String invoiceId, int attempts, Instant nextAttemptAt, String error, Instant at) {
        jdbc.sql("""
                update invoice set attempts = :n, next_attempt_at = :next, last_error = :e, updated_at = :at,
                       version = version + 1
                where stripe_invoice_id = :id
                """).param("id", invoiceId).param("n", attempts).param("next", Sql.ts(nextAttemptAt))
                .param("e", error).param("at", Sql.ts(at)).update();
    }

    @Override
    public void setWaiver(String invoiceId, String reasonCode, String note, String actor, Instant at) {
        jdbc.sql("""
                update invoice set waived_reason_code = :r, waived_note = :n, waived_by = :a, updated_at = :at
                where stripe_invoice_id = :id
                """).param("id", invoiceId).param("r", reasonCode).param("n", note).param("a", actor)
                .param("at", Sql.ts(at)).update();
    }

    @Override
    public long nextNumber() {
        Long n = jdbc.sql("select nextval('invoice_number_seq')").query(Long.class).single();
        return n == null ? 0 : n;
    }

    private Invoice withPayments(Invoice i) {
        return i.withPayments(jdbc.sql("select * from invoice_payment where invoice_id = :id order by created_at, id")
                .param("id", i.id()).query(JdbcInvoiceRepository::payment).list());
    }

    private static InvoicePayment payment(ResultSet rs, int n) throws SQLException {
        return new InvoicePayment(rs.getObject("id", UUID.class), rs.getString("invoice_id"), rs.getString("org_id"),
                PaymentSource.valueOf(rs.getString("source")), rs.getLong("amount_cents"), rs.getString("status"),
                rs.getString("reference"), rs.getString("note"), Sql.instant(rs, "created_at"));
    }

    private Invoice row(ResultSet rs, int n) throws SQLException {
        String cadence = rs.getString("cadence");
        return new Invoice(rs.getString("stripe_invoice_id"), rs.getString("org_id"), rs.getString("number"),
                Sql.instant(rs, "period_start"), Sql.instant(rs, "period_end"), rs.getLong("subtotal_cents"),
                rs.getLong("discount_cents"), rs.getLong("amount_due_cents"), rs.getLong("amount_paid_cents"),
                rs.getString("currency"), rs.getString("status"), rs.getString("billing_reason"),
                rs.getString("plan_code"), rs.getInt("plan_version"), cadence == null ? null : Cadence.valueOf(cadence),
                Sql.instant(rs, "due_at"), Sql.instant(rs, "paid_at"), rs.getInt("attempts"),
                Sql.instant(rs, "next_attempt_at"), rs.getString("last_error"), Sql.instant(rs, "created_at"),
                json.read(rs.getString("lines"), LINES), List.of());
    }
}
