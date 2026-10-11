package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CapturePlanTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    /** An authorised two-shipment order (L1 from org_a, L2 from org_b). */
    private static Payment payment(boolean multicapture, Map<String, String> metadata) {
        Payment p = Payment.create(UUID.randomUUID(), "ORD-1", "cus_1", Fixtures.usd(3300), UUID.randomUUID(), NOW,
                metadata, List.of(Fixtures.line("L1", "org_a", "x", Fulfilment.SELF, 1, 1000, 100, 0),
                        Fixtures.line("L2", "org_b", "x", Fulfilment.SELF, 1, 2000, 200, 0)), NOW);
        return p.withIntent("pi_1", "pi_1_secret", PaymentStatus.AUTHORIZED, multicapture, NOW);
    }

    private static List<PaymentLine> lines(Payment p, String... ids) {
        return List.of(ids).stream().map(id -> p.line(id).orElseThrow()).toList();
    }

    @Test
    void aMulticaptureIntentCapturesTheShipmentAndKeepsTheRest() {
        Payment p = payment(true, Map.of(Payment.CAPTURE_PLAN, Payment.PER_SHIPMENT));

        CapturePlan plan = CapturePlan.of(p, lines(p, "L1"), false);

        assertThat(plan.lines()).extracting(PaymentLine::lineId).containsExactly("L1");
        assertThat(plan.amount("USD")).isEqualTo(Fixtures.usd(1100));
        assertThat(plan.finalCapture()).isFalse();
        assertThat(plan.stripeFinalCapture()).isFalse();
    }

    @Test
    void aMulticaptureIntentsLastShipmentIsFinal() {
        Payment p = payment(true, Map.of(Payment.CAPTURE_PLAN, Payment.PER_SHIPMENT));

        CapturePlan plan = CapturePlan.of(p, lines(p, "L1", "L2"), false);

        assertThat(plan.finalCapture()).isTrue();
        assertThat(plan.stripeFinalCapture()).isTrue();
    }

    @Test
    void aSingleCaptureOrderCapturesEveryLineWithItsFirstShipment() {
        Payment p = payment(false, Map.of(Payment.CAPTURE_PLAN, Payment.PER_SHIPMENT));

        CapturePlan plan = CapturePlan.of(p, lines(p, "L2"), false);

        assertThat(plan.lines()).extracting(PaymentLine::lineId).containsExactly("L2", "L1");
        assertThat(plan.amount("USD")).isEqualTo(Fixtures.usd(3300));
        assertThat(plan.widened(lines(p, "L2"))).isTrue();
        assertThat(plan.finalCapture()).isTrue();
        // Single-capture intents take no final_capture parameter at all (Stripe refuses it without multicapture).
        assertThat(plan.stripeFinalCapture()).isNull();
    }

    @Test
    void aSingleCapturePaymentWithoutShipmentsCapturesWhatItAskedAndReleasesTheRest() {
        Payment deposit = payment(false, Map.of());

        CapturePlan plan = CapturePlan.of(deposit, lines(deposit, "L1"), false);

        assertThat(plan.lines()).extracting(PaymentLine::lineId).containsExactly("L1");
        assertThat(plan.finalCapture()).isTrue();
        assertThat(plan.stripeFinalCapture()).isNull();
        assertThat(plan.widened(lines(deposit, "L1"))).isFalse();
    }

    @Test
    void aSingleCaptureOrderMarksThePaymentCapturedForTheWholeAmount() {
        Payment p = payment(false, Map.of(Payment.CAPTURE_PLAN, Payment.PER_SHIPMENT));
        CapturePlan plan = CapturePlan.of(p, lines(p, "L1"), false);
        List<LineSplit> splits = List.of(new LineSplit("L1", "org_a", 1000, Fixtures.usd(100), Fixtures.usd(0), false),
                new LineSplit("L2", "org_b", 2000, Fixtures.usd(200), Fixtures.usd(0), false));

        Payment captured = p.capture(UUID.randomUUID(), plan.amount("USD"), splits, plan.finalCapture(), NOW);

        assertThat(captured.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(captured.captured()).isEqualTo(Fixtures.usd(3300));
        assertThat(captured.lines()).allMatch(PaymentLine::isCaptured);
        assertThat(captured.multicapture()).isFalse();
        assertThat(captured.capturedPerShipment()).isTrue();
    }
}
