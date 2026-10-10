package com.afrotransact.taxreturns.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.application.CarrierScan;
import com.afrotransact.taxreturns.application.InspectCommand;
import com.afrotransact.taxreturns.application.LiabilityReport;
import com.afrotransact.taxreturns.application.PaymentRefunded;
import com.afrotransact.taxreturns.application.RequestReturnCommand;
import com.afrotransact.taxreturns.application.ReturnEligibility;
import com.afrotransact.taxreturns.application.ReturnSearch;
import com.afrotransact.taxreturns.application.ReturnService;
import com.afrotransact.taxreturns.application.ReversalLine;
import com.afrotransact.taxreturns.application.TaxLedgerService;
import com.afrotransact.taxreturns.domain.returns.RefundBreakdown;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.tax.TaxTransaction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PaymentAndCarrierListenersTest {

    private final List<PaymentRefunded> refunds = new ArrayList<>();
    private final List<CarrierScan> scans = new ArrayList<>();

    private final PaymentEventsListener payments = new PaymentEventsListener(new TaxLedgerService() {
        @Override
        public LiabilityReport liability(YearMonth month) {
            return null;
        }

        @Override
        public LiabilityReport sellerStatement(String sellerOrgId, YearMonth month) {
            return null;
        }

        @Override
        public List<TaxTransaction> forOrder(UUID orderId) {
            return List.of();
        }

        @Override
        public int reverse(UUID orderId, String refundRef, String currency, List<ReversalLine> lines) {
            return 0;
        }

        @Override
        public boolean onPaymentRefunded(UUID eventId, PaymentRefunded event) {
            refunds.add(event);
            return true;
        }
    }, JsonMapper.builder().build());

    private final CarrierEventsListener carrier = new CarrierEventsListener(new ScanOnlyReturns(scans),
            JsonMapper.builder().build());

    @Test
    void paymentsRefundedBecomesATaxReversal() {
        payments.onMessage(OrderEventsListenerTest.rec("at.payment.events", "payments.refunded",
                UUID.randomUUID().toString(), """
                {"paymentId":"%s","orderId":"%s","refundId":"%s","amountMinor":7035,"currency":"USD",
                 "status":"SUCCEEDED","lines":[{"lineId":"l1","sellerOrg":"org_a","itemsMinor":6499,
                 "shippingMinor":0,"taxMinor":536,"commissionReversedMinor":650}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
        payments.onMessage(OrderEventsListenerTest.rec("at.payment.events", "payments.captured",
                UUID.randomUUID().toString(), "{}"));
        payments.onMessage(OrderEventsListenerTest.rec("at.payment.events", "payments.refunded",
                UUID.randomUUID().toString(), "{\"orderId\":\"o\"}"));
        assertThat(refunds).hasSize(1);
        assertThat(refunds.getFirst().lines()).containsExactly(new PaymentRefunded.Line("l1", 6499, 536));
        assertThat(refunds.getFirst().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void carrierScansOfReturnLabelsReachReturns() {
        carrier.onMessage(OrderEventsListenerTest.rec("at.carrier-shipment.events", "carrier.tracking.updated",
                UUID.randomUUID().toString(), """
                {"carrier":"ups","trackingNumber":"1Z999","reference":"RMA-ABCD2345","state":"in_transit",
                 "stateChanged":true,"event":{"eventId":"e1","occurredAt":"2026-10-06T09:00:00Z"}}
                """));
        carrier.onMessage(OrderEventsListenerTest.rec("at.carrier-shipment.events", "carrier.pickup.ready",
                UUID.randomUUID().toString(), "{}"));
        assertThat(scans).containsExactly(new CarrierScan("ups", "1Z999", "RMA-ABCD2345", "in_transit",
                Instant.parse("2026-10-06T09:00:00Z")));
    }

    /** Only the carrier-scan entry point is used by the listener. */
    private record ScanOnlyReturns(List<CarrierScan> scans) implements ReturnService {

        @Override
        public boolean onCarrierScan(UUID eventId, CarrierScan scan) {
            return scans.add(scan);
        }

        @Override
        public ReturnEligibility eligibility(UUID orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Rma request(RequestReturnCommand command, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Rma get(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Rma getByNumber(String number) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Rma> search(ReturnSearch search) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Rma cancel(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Rma approve(UUID id, String note) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Rma reject(UUID id, String note) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Rma issueLabel(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Rma pickedUp(UUID id, String handoverCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Rma receive(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Rma inspect(UUID id, InspectCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundBreakdown refundQuote(UUID id) {
            throw new UnsupportedOperationException();
        }
    }
}
