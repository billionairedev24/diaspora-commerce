package com.afrotransact.bookings.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.bookings.application.BalanceChargeService;
import com.afrotransact.bookings.application.PaymentEventIngest;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.EqualToJsonPattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S232: services without a deposit. Nothing is captured at confirmation, so for a far appointment the whole card
 * authorisation is released (the PaymentIntent is cancelled; payments keeps the card saved, saveForOffSession) and
 * the full price is charged off-session on the saved card before the appointment, like the balance of a deposit
 * booking; unpaid balances raise booking.balance_unpaid for notifications.
 */
class NoDepositBalanceIT extends BookingsIntegrationTest {

    private static final String OFF_SESSION = "/v1/payments/[^/]+/off-session-charges";

    private final PaymentEventIngest paymentEvents;
    private final BalanceChargeService balances;

    @Autowired
    NoDepositBalanceIT(PaymentEventIngest paymentEvents, BalanceChargeService balances) {
        this.paymentEvents = paymentEvents;
        this.balances = balances;
    }

    @Test
    void farNoDepositBookingReleasesTheHoldChargesOffSessionAndCapturesOnCompletion() {
        Setup s = provider(false);
        UUID service = noDepositService(s);
        Confirmed b = confirm(s, service, "cust-nodep-1", day().plusDays(15));
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/payments/" + b.paymentId() + "/cancel"))
                .withHeader("Idempotency-Key", WireMock.equalTo(b.ref() + ":cancel")));
        PAYMENTS.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payments/[^/]+/capture")));
        JsonNode confirmed = ok(send("GET", "/v1/bookings/" + b.id(), null, null));
        assertThat(confirmed.path("payment").path("balance").path("state").asString()).isEqualTo("DUE");
        assertThat(confirmed.path("payment").path("releasedMinor").asLong()).isEqualTo(12_000);

        UUID charge = UUID.randomUUID();
        stubOffSession(b.ref() + ":offsession:1", charge, "AUTHORIZED", null);
        TestSupport.CLOCK.advance(untilChargeTime(b.startAt()));
        balances.chargeDueBalances();
        PAYMENTS.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/payments/" + b.paymentId()
                        + "/off-session-charges"))
                .withHeader("Idempotency-Key", WireMock.equalTo(b.ref() + ":offsession:1"))
                .withRequestBody(new EqualToJsonPattern("{\"customerId\":\"cust-nodep-1\",\"lineIds\":[\"balance\"]}",
                        true, false)));

        TestSupport.CLOCK.advance(Duration.ofDays(2).plusHours(2));
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode done = ok(send("POST", "/v1/bookings/" + b.id() + "/complete", null, "complete-" + b.id()));
        assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.path("payment").path("balanceCapturedMinor").asLong()).isEqualTo(12_000);
        assertThat(done.path("payment").path("balance").path("state").asString()).isEqualTo("CAPTURED");
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/payments/" + charge + "/capture"))
                .withRequestBody(WireMock.matchingJsonPath("$.amountMinor", WireMock.equalTo("12000"))));
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/settlements/" + b.ref() + "/delivered")));
    }

    @Test
    void unpaidNoDepositBalanceIsFlaggedForNotifications() {
        Setup s = provider(false);
        UUID service = noDepositService(s);
        Confirmed b = confirm(s, service, "cust-nodep-2", day().plusDays(15));
        for (int n = 1; n <= 3; n++) {
            stubOffSession(b.ref() + ":offsession:" + n, UUID.randomUUID(), "FAILED", "card_declined");
        }
        TestSupport.CLOCK.advance(untilChargeTime(b.startAt()));
        balances.chargeDueBalances();
        TestSupport.CLOCK.advance(Duration.ofHours(7));
        balances.chargeDueBalances();
        TestSupport.CLOCK.advance(Duration.ofHours(7));
        balances.chargeDueBalances();

        assertThat(outboxTypes(b.id())).containsExactly("booking.requested", "booking.confirmed",
                "booking.balance_unpaid");
        JsonNode payload = JSON.readTree(jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id "
                + "and type = 'booking.balance_unpaid'").param("id", b.id()).query(String.class).single());
        assertThat(payload.path("balanceState").asString()).isEqualTo("NEEDS_ATTENTION");
        assertThat(payload.path("balanceMinor").asLong()).isEqualTo(12_000);
        assertThat(payload.path("recipient").isMissingNode()).isFalse();
    }

    @Test
    void cancellingAfterTheOffSessionChargeCancelsThatChargeOnly() {
        Setup s = provider(false);
        UUID service = noDepositService(s);
        Confirmed b = confirm(s, service, "cust-nodep-3", day().plusDays(15));
        UUID charge = UUID.randomUUID();
        stubOffSession(b.ref() + ":offsession:1", charge, "AUTHORIZED", null);
        TestSupport.CLOCK.advance(untilChargeTime(b.startAt()));
        balances.chargeDueBalances();

        TestSupport.as(TestSupport.customer("cust-nodep-3"));
        JsonNode cancelled = ok(send("POST", "/v1/bookings/" + b.id() + "/cancel", Map.of("reason", "Travelling"),
                "cancel-" + b.id())).path("booking");
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED_CUSTOMER");
        PAYMENTS.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/payments/" + charge + "/cancel"))
                .withHeader("Idempotency-Key", WireMock.equalTo(b.ref() + ":offsession:1:cancel")));
        // The original intent was cancelled once, at confirmation, never again.
        PAYMENTS.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/payments/" + b.paymentId()
                + "/cancel")));
        assertThat(cancelled.path("payment").path("balance").path("state").asString()).isEqualTo("RELEASED");
    }

    @Test
    void nearNoDepositBookingKeepsItsAuthorisation() {
        Setup s = provider(false);
        UUID service = noDepositService(s);
        Confirmed b = confirm(s, service, "cust-nodep-4", day().minusDays(3));
        PAYMENTS.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payments/[^/]+/cancel")));
        JsonNode confirmed = ok(send("GET", "/v1/bookings/" + b.id(), null, null));
        assertThat(confirmed.path("payment").path("balance").path("state").asString()).isEqualTo("ON_AUTHORISATION");
        assertThat(confirmed.path("payment").path("state").asString()).isEqualTo("AUTHORIZED");
    }

    private UUID noDepositService(Setup s) {
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode created = ok(send("POST", "/v1/providers/" + s.org() + "/services", Map.of("name",
                "Silk press", "description", "No deposit", "category", "hair & beauty", "durationMinutes", 60,
                "priceMinor", 12_000, "depositMinor", 0, "modes", List.of("IN_PERSON")), null), 201);
        TestSupport.reset();
        return UUID.fromString(created.path("id").asString());
    }

    private Confirmed confirm(Setup s, UUID service, String customer, LocalDate date) {
        String start = firstSlot(service, date);
        JsonNode booked = book(customer, service, start);
        String id = booked.path("id").asString();
        String ref = booked.path("reference").asString();
        assertThat(paymentEvents.handle(UUID.randomUUID(), "payments.intent.succeeded", ref, "AUTHORIZED", null))
                .isTrue();
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode confirmed = ok(send("POST", "/v1/bookings/" + id + "/confirm", null, "confirm-" + id));
        assertThat(confirmed.path("status").asString()).isEqualTo("CONFIRMED");
        return new Confirmed(id, ref, UUID.fromString(confirmed.path("payment").path("paymentId").asString()),
                Instant.parse(start));
    }

    private static Duration untilChargeTime(Instant startAt) {
        return Duration.between(TestSupport.CLOCK.instant(), startAt.minus(Duration.ofDays(2)).plusSeconds(3600));
    }

    private static void stubOffSession(String key, UUID chargeId, String status, String failure) {
        PAYMENTS.stubFor(WireMock.post(WireMock.urlPathMatching(OFF_SESSION))
                .withHeader("Idempotency-Key", WireMock.equalTo(key))
                .willReturn(WireMock.aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + chargeId + "\",\"orderId\":\"x\",\"status\":\"" + status + "\","
                                + "\"clientSecret\":\"pi_" + chargeId + "_secret\","
                                + (failure == null ? "" : "\"failureMessage\":\"" + failure + "\",")
                                + "\"parentPaymentId\":\"{{request.pathSegments.[2]}}\",\"offSession\":true}")));
    }

    private record Confirmed(String id, String ref, UUID paymentId, Instant startAt) {
    }
}
