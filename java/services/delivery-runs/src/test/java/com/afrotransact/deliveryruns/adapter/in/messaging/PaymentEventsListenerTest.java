package com.afrotransact.deliveryruns.adapter.in.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.RunMoneyService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** S265: payments.captured and payments.refunded reach the order's run slot. */
class PaymentEventsListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final RunMoneyService money = mock(RunMoneyService.class);
    private final PaymentEventsListener listener = new PaymentEventsListener(money, json);
    private final Instant at = Instant.parse("2026-10-24T18:00:00Z");

    @Test
    void aCaptureReportsTheSellerCommissionAndNothingFromRetailOrThePlatform() {
        UUID capture = UUID.randomUUID();
        String body = """
                {"paymentId": "%s", "orderId": "ord-1", "captureId": "%s", "shipmentId": "s-1", "amountMinor": 4200,
                 "stripeFeeMinor": 150, "currency": "USD",
                 "recipients": [
                   {"recipientId": "org-a", "kind": "SELLER", "netMinor": 2800, "commissionMinor": 450},
                   {"recipientId": "org-b", "kind": "SELLER", "netMinor": 900, "commissionMinor": 80},
                   {"recipientId": "retail", "kind": "RETAIL", "netMinor": 100, "commissionMinor": 999},
                   {"recipientId": "afrotransact", "kind": "PLATFORM", "netMinor": 100}]}
                """.formatted(UUID.randomUUID(), capture);

        listener.handle("payments.captured", json.readTree(body), at);

        verify(money).onCaptured(capture, "ord-1", 530L, at);
    }

    @Test
    void aSucceededRefundReportsTheAmountAndItsDeliveryFeePart() {
        UUID refund = UUID.randomUUID();
        String body = """
                {"paymentId": "%s", "orderId": "ord-1", "refundId": "%s", "amountMinor": 2299, "currency": "USD",
                 "status": "SUCCEEDED", "lines": [
                   {"lineId": "l-1", "sellerOrg": "org-a", "itemsMinor": 1500, "shippingMinor": 799, "taxMinor": 0},
                   {"lineId": "l-2", "sellerOrg": "org-a", "itemsMinor": 0, "taxMinor": 0}]}
                """.formatted(UUID.randomUUID(), refund);

        listener.handle("payments.refunded", json.readTree(body), at);

        verify(money).onRefunded(refund, "ord-1", 2299L, 799L, at);
    }

    @Test
    void pendingAndFailedRefundsAreNotMoneyHandedBack() {
        for (String status : new String[] {"PENDING", "FAILED"}) {
            String body = """
                    {"orderId": "ord-1", "refundId": "%s", "amountMinor": 2299, "status": "%s", "lines": []}
                    """.formatted(UUID.randomUUID(), status);
            listener.handle("payments.refunded", json.readTree(body), at);
        }
        verifyNoInteractions(money);
    }

    @Test
    void eventsWithoutAnOrderOrAnIdAreSkipped() {
        listener.handle("payments.captured", json.readTree("{\"captureId\": \"" + UUID.randomUUID() + "\"}"), at);
        listener.handle("payments.captured", json.readTree("{\"orderId\": \"ord-1\", \"captureId\": \"nope\"}"), at);
        listener.handle("payments.refunded", json.readTree("{\"orderId\": \"ord-1\", \"status\": \"SUCCEEDED\"}"), at);
        verifyNoInteractions(money);
    }
}
