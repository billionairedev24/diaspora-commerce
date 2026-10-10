package com.afrotransact.seller.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.port.InvoiceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInvoiceRepository implements InvoiceRepository {

    private final JdbcClient jdbc;

    JdbcInvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(Invoice i) {
        jdbc.sql("""
                insert into invoice (stripe_invoice_id, org_id, number, period_start, period_end, amount_due_cents,
                                     amount_paid_cents, currency, status, billing_reason, hosted_invoice_url,
                                     invoice_pdf, created_at, updated_at)
                values (:id, :org, :number, :start, :end, :due, :paid, :currency, :status, :reason, :url, :pdf,
                        :created, :now)
                on conflict (stripe_invoice_id) do update set status = excluded.status,
                       amount_due_cents = excluded.amount_due_cents, amount_paid_cents = excluded.amount_paid_cents,
                       number = coalesce(excluded.number, invoice.number),
                       hosted_invoice_url = coalesce(excluded.hosted_invoice_url, invoice.hosted_invoice_url),
                       invoice_pdf = coalesce(excluded.invoice_pdf, invoice.invoice_pdf), updated_at = excluded.updated_at
                """)
                .param("id", i.stripeInvoiceId()).param("org", i.orgId()).param("number", i.number())
                .param("start", Sql.ts(i.periodStart())).param("end", Sql.ts(i.periodEnd()))
                .param("due", i.amountDueCents()).param("paid", i.amountPaidCents())
                .param("currency", i.currency() == null ? "USD" : i.currency().toUpperCase(Locale.ROOT))
                .param("status", i.status()).param("reason", i.billingReason()).param("url", i.hostedInvoiceUrl())
                .param("pdf", i.invoicePdf()).param("created", Sql.ts(i.createdAt()))
                .param("now", Timestamp.from(Instant.now()))
                .update();
    }

    @Override
    public List<Invoice> list(String orgId, int limit) {
        return jdbc.sql("select * from invoice where org_id = :o order by created_at desc limit :limit")
                .param("o", orgId).param("limit", limit)
                .query((rs, n) -> new Invoice(rs.getString("stripe_invoice_id"), rs.getString("org_id"),
                        rs.getString("number"), Sql.instant(rs, "period_start"), Sql.instant(rs, "period_end"),
                        rs.getLong("amount_due_cents"), rs.getLong("amount_paid_cents"), rs.getString("currency"),
                        rs.getString("status"), rs.getString("billing_reason"), rs.getString("hosted_invoice_url"),
                        rs.getString("invoice_pdf"), Sql.instant(rs, "created_at")))
                .list();
    }
}
