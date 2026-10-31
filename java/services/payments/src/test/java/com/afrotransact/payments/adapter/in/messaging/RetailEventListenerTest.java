package com.afrotransact.payments.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.application.SupplierPaymentNotice;
import com.afrotransact.payments.application.SupplierPaymentService;
import com.afrotransact.payments.domain.SupplierPayment;
import com.afrotransact.payments.domain.SupplierPaymentStatus;
import com.afrotransact.platform.events.consumer.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** S226: retail.payment.released is mapped to a supplier payment notice; other retail events are ignored. */
class RetailEventListenerTest {

    private final List<SupplierPaymentNotice> released = new ArrayList<>();
    private final RetailEventListener listener = new RetailEventListener(new SupplierPaymentService() {
        @Override
        public boolean released(SupplierPaymentNotice notice) {
            released.add(notice);
            return true;
        }

        @Override
        public List<SupplierPayment> list(SupplierPaymentStatus status, UUID supplierId, LocalDate dueBy,
                                          int limit) {
            return List.of();
        }

        @Override
        public SupplierPayment get(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SupplierPayment markPaid(UUID id, String paymentReference, Instant paidAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SupplierPayment hold(UUID id, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SupplierPayment release(UUID id) {
            throw new UnsupportedOperationException();
        }
    }, JsonMapper.builder().build());

    @Test
    void mapsThePaymentReleasedPayloadOfRetailService() {
        UUID event = UUID.randomUUID();
        UUID po = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        listener.onMessage(rec("retail.payment.released", event.toString(), """
                {"poId":"%s","poNumber":"PO-2026-0042","supplierId":"%s","supplierCode":"SUP-ACC","currency":"USD",
                 "amountMinor":125000,"invoices":[{"invoiceId":"%s","invoiceNumber":"INV-7781","currency":"USD",
                 "amountMinor":125000}],"extra":"ignored"}
                """.formatted(po, supplier, invoice)));

        assertThat(released).hasSize(1);
        SupplierPaymentNotice n = released.getFirst();
        assertThat(n.eventId()).isEqualTo(event);
        assertThat(n.poId()).isEqualTo(po);
        assertThat(n.supplierId()).isEqualTo(supplier);
        assertThat(n.amountMinor()).isEqualTo(125_000);
        assertThat(n.invoices()).singleElement().satisfies(i -> {
            assertThat(i.invoiceId()).isEqualTo(invoice);
            assertThat(i.invoiceNumber()).isEqualTo("INV-7781");
        });
        assertThat(n.releasedAt()).isEqualTo(Instant.parse("2026-10-01T15:00:00Z"));
        assertThat(n.dueOn()).isNull();
    }

    @Test
    void ignoresOtherEventsMissingIdsAndMalformedPayloads() {
        listener.onMessage(rec("retail.purchase_order.sent", UUID.randomUUID().toString(), "{}"));
        assertThatThrownBy(() -> listener.onMessage(rec("retail.payment.released", null, "{}")))
                .isInstanceOf(NonRetryableEventException.class);
        assertThatThrownBy(() -> listener.onMessage(rec("retail.payment.released", UUID.randomUUID().toString(), "not json")))
                .isInstanceOf(NonRetryableEventException.class);
        assertThat(released).isEmpty();
    }

    private static ConsumerRecord<String, String> rec(String type, String eventId, String json) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.retail.events", 0, 0L, "po", json);
        r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        r.headers().add(EventHeaders.OCCURRED_AT, "2026-10-01T15:00:00Z".getBytes(StandardCharsets.UTF_8));
        if (eventId != null) {
            r.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }
}
