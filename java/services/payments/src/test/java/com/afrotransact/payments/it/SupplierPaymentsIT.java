package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.application.SupplierPaymentNotice;
import com.afrotransact.payments.application.SupplierPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S226: retail.payment.released books what AfroTransact Retail (merchant of record) owes the supplier and schedules the
 * payment by its terms; Finance holds, releases and pays it from the operating bank account. Nothing reaches a seller
 * payable, a Connect transfer or Retail's payout balance.
 */
class SupplierPaymentsIT extends PaymentsIntegrationTest {

    private SupplierPaymentService supplierPayments;

    @BeforeEach
    void wire(@Autowired SupplierPaymentService service) {
        this.supplierPayments = service;
    }

    @Test
    void aReleasedPurchaseOrderIsBookedScheduledHeldAndPaid() {
        UUID po = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        Instant released = Instant.parse("2026-10-01T15:00:00Z");
        SupplierPaymentNotice notice = new SupplierPaymentNotice(event, po, "PO-2026-0042", supplier, "SUP-ACC", "usd",
                125_000, List.of(new SupplierPaymentNotice.Invoice(UUID.randomUUID(), "INV-7781", "USD", 100_000),
                new SupplierPaymentNotice.Invoice(UUID.randomUUID(), "INV-7782", null, 25_000)), released, null);

        assertThat(supplierPayments.released(notice)).isTrue();
        assertThat(supplierPayments.released(notice)).as("redelivery").isFalse();
        assertThat(supplierPayments.released(new SupplierPaymentNotice(UUID.randomUUID(), po, "PO-2026-0042",
                supplier, "SUP-ACC", "USD", 125_000, List.of(), released, null))).as("same PO again").isFalse();
        assertEveryJournalBalances();
        assertThat(account("1400", "")).isEqualTo(125_000);
        assertThat(account("2500", supplier.toString())).isEqualTo(-125_000);
        assertThat(jdbc.sql("select count(*) from journal_line where account_code = '2000' and org_id = :r")
                .param("r", RETAIL).query(Long.class).single()).isZero();
        assertThat(outbox("payments.supplier_payment.scheduled", supplier.toString())).isEqualTo(1);

        TestSupport.as(TestSupport.FINANCE);
        JsonNode due = ok(send("GET", "/v1/supplier-payments?status=SCHEDULED&dueBy=2026-10-31&supplierId="
                + supplier, null, null));
        assertThat(due).hasSize(1);
        JsonNode p = due.get(0);
        String id = p.path("id").asString();
        assertThat(p.path("amountMinor").asLong()).isEqualTo(125_000);
        assertThat(p.path("currency").asString()).isEqualTo("USD");
        assertThat(LocalDate.parse(p.path("dueOn").asString())).isEqualTo(LocalDate.parse("2026-10-31"));
        assertThat(p.path("invoices")).hasSize(2);
        assertThat(p.path("invoices").get(1).path("currency").asString()).isEqualTo("USD");
        assertThat(ok(send("GET", "/v1/supplier-payments?dueBy=2026-10-30&supplierId=" + supplier, null, null)))
                .as("not due before the terms").isEmpty();

        // On hold it can't be paid; released, it can.
        assertThat(ok(send("POST", "/v1/supplier-payments/" + id + "/hold", Map.of("reason", "Short shipment claim"),
                null)).path("status").asString()).isEqualTo("ON_HOLD");
        problem(send("POST", "/v1/supplier-payments/" + id + "/paid", Map.of("paymentReference", "ACH-1"), null), 409,
                "payments.supplier-payment-on-hold");
        assertThat(ok(send("POST", "/v1/supplier-payments/" + id + "/release", null, null)).path("status")
                .asString()).isEqualTo("SCHEDULED");
        JsonNode paid = ok(send("POST", "/v1/supplier-payments/" + id + "/paid",
                Map.of("paymentReference", "ACH-20261030-001", "paidAt", "2026-10-30T10:00:00Z"), null));
        assertThat(paid.path("status").asString()).isEqualTo("PAID");
        assertThat(paid.path("paidBy").asString()).isEqualTo("fin-1");
        assertThat(ok(send("POST", "/v1/supplier-payments/" + id + "/paid",
                Map.of("paymentReference", "ACH-20261030-001"), null)).path("paymentJournalId").asString())
                .as("same reference is a replay").isEqualTo(paid.path("paymentJournalId").asString());
        assertEveryJournalBalances();
        assertThat(account("2500", supplier.toString())).isZero();
        assertThat(account("1050", "")).isEqualTo(-125_000);
        assertThat(outbox("payments.supplier_payment.paid", supplier.toString())).isEqualTo(1);

        // Retail's marketplace payout balance never sees supplier money.
        assertThat(ok(send("GET", "/v1/payouts/balance?orgId=" + RETAIL, null, null)).path("availableMinor")
                .asLong()).isZero();
    }

    @Test
    void sellersCannotSeeSupplierPaymentsAndIncompleteEventsAreIgnored() {
        assertThat(supplierPayments.released(new SupplierPaymentNotice(UUID.randomUUID(), UUID.randomUUID(), "PO-X",
                UUID.randomUUID(), null, "USD", 0, List.of(), null, null))).isFalse();
        UUID po = UUID.randomUUID();
        assertThat(supplierPayments.released(new SupplierPaymentNotice(UUID.randomUUID(), po, "PO-Y",
                UUID.randomUUID(), null, "GHS", 5_000, List.of(), null, LocalDate.parse("2026-11-15")))).isTrue();

        TestSupport.as(TestSupport.seller("org_any", "seller.owner", "payments:read", "payouts:read"));
        problem(send("GET", "/v1/supplier-payments", null, null), 403, "payments.staff-only");

        TestSupport.as(TestSupport.FINANCE);
        JsonNode list = ok(send("GET", "/v1/supplier-payments?status=SCHEDULED", null, null));
        JsonNode ghs = null;
        for (JsonNode n : list) {
            if (n.path("poId").asString().equals(po.toString())) {
                ghs = n;
            }
        }
        assertThat(ghs).isNotNull();
        assertThat(ghs.path("currency").asString()).isEqualTo("GHS");
        assertThat(ghs.path("dueOn").asString()).isEqualTo("2026-11-15");
    }

    private long account(String code, String key) {
        return jdbc.sql("""
                select coalesce(sum(case side when 'D' then amount_minor else -amount_minor end), 0)
                from journal_line where account_code = :code and account_key = :key and currency = 'USD'
                """).param("code", code).param("key", key).query(Long.class).single();
    }
}
