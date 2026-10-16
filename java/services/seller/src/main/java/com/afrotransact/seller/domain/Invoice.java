package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A plan invoice AfroTransact issues itself: the plan for one billing period (plus metered usage), less coupons, settled
 * in order by platform credit, the seller's earnings (only if they chose that) and the saved card or bank account.
 *
 * @param id             {@code inv_<uuid>}
 * @param status         {@code open} (awaiting payment), {@code paid} (also a $0 or waived invoice), {@code void}
 *                       (cancelled) or {@code uncollectible} (every attempt failed)
 * @param billingReason  {@code subscription_create}, {@code subscription_cycle}, {@code subscription_update}
 * @param subtotalCents  the lines before coupons
 * @param discountCents  coupons taken off the subtotal
 * @param amountDueCents what is owed after coupons ({@code subtotal - discount})
 * @param amountPaidCents what payments have settled so far
 */
public record Invoice(String id, String orgId, String number, Instant periodStart, Instant periodEnd,
                      long subtotalCents, long discountCents, long amountDueCents, long amountPaidCents,
                      String currency, String status, String billingReason, String planCode, int planVersion,
                      Cadence cadence, Instant dueAt, Instant paidAt, int attempts, Instant nextAttemptAt,
                      String lastError, Instant createdAt, List<InvoiceLine> lines, List<InvoicePayment> payments) {

    public static final String OPEN = "open";
    public static final String PAID = "paid";
    public static final String VOID = "void";
    public static final String UNCOLLECTIBLE = "uncollectible";
    public static final String REASON_CREATE = "subscription_create";
    public static final String REASON_CYCLE = "subscription_cycle";
    public static final String REASON_UPDATE = "subscription_update";

    public Invoice {
        Objects.requireNonNull(id, "id");
        lines = lines == null ? List.of() : List.copyOf(lines);
        payments = payments == null ? List.of() : List.copyOf(payments);
    }

    /** What payments of {@code source} have settled (applied ones only). */
    public long paidBy(PaymentSource source) {
        return payments.stream().filter(p -> p.source() == source && InvoicePayment.APPLIED.equals(p.status()))
                .mapToLong(InvoicePayment::amountCents).sum();
    }

    /** Still to pay: the amount due less what applied and pending payments cover. */
    public long remainingCents() {
        long covered = payments.stream().filter(p -> p.status().equals(InvoicePayment.APPLIED)
                || p.status().equals(InvoicePayment.PENDING)).mapToLong(InvoicePayment::amountCents).sum();
        return Math.max(0, amountDueCents - covered);
    }

    public boolean isOpen() {
        return OPEN.equals(status);
    }

    /** Usage lines billed from the meters (S206). */
    public List<InvoiceLine> meteredLines() {
        return lines.stream().filter(InvoiceLine::metered).toList();
    }

    public Invoice withPayments(List<InvoicePayment> next) {
        return new Invoice(id, orgId, number, periodStart, periodEnd, subtotalCents, discountCents, amountDueCents,
                amountPaidCents, currency, status, billingReason, planCode, planVersion, cadence, dueAt, paidAt,
                attempts, nextAttemptAt, lastError, createdAt, lines, next);
    }
}
