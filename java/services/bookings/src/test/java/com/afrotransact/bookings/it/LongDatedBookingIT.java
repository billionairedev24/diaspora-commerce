package com.afrotransact.bookings.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.afrotransact.bookings.application.BalanceChargeService;
import com.afrotransact.bookings.application.PaymentEventIngest;
import com.afrotransact.bookings.application.PaymentEventIngest.IntentEvent;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.EqualToJsonPattern;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Long-dated bookings (S220) against PostgreSQL with payments stubbed by WireMock: the balance hold is released after
 * the deposit capture, the balance is charged off-session before the appointment (authorised, SCA, declines), and
 * cancellations / no-shows after the deposit capture release the rest of the authorisation.
 */
class LongDatedBookingIT extends BookingsIntegrationTest {

    private static final String OFF_SESSION = "/v1/payments/[^/]+/off-session-charges";

    private final PaymentEventIngest paymentEvents;
    private final BalanceChargeService balances;

    @Autowired
    LongDatedBookingIT(PaymentEventIngest paymentEvents, BalanceChargeService balances) {
        this.paymentEvents = paymentEvents;
        this.balances = balances;
    }

    @Test
    void farBookingReleasesTheBalanceHoldThenChargesItOffSessionAndCapturesOnCompletion() {
        Setup s = provider(false);
        Far b = confirmedFar(s, "cust-far");
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/payments/" + b.paymentId() + "/release"))
                .withHeader("Idempotency-Key", WireMock.equalTo(b.ref() + ":release"))
                .withRequestBody(WireMock.matchingJsonPath("$.reason")));
        JsonNode confirmed = ok(send("GET", "/v1/bookings/" + b.id(), null, null));
        assertThat(confirmed.path("payment").path("balance").path("state").asString()).isEqualTo("DUE");
        assertThat(confirmed.path("payment").path("releasedMinor").asLong()).isEqualTo(9_000);

        // Too early: nothing is charged.
        balances.chargeDueBalances();
        PAYMENTS.verify(0, WireMock.postRequestedFor(offSessionOf(b)));

        UUID charge = UUID.randomUUID();
        stubOffSession(b.ref() + ":offsession:1", charge, "AUTHORIZED", null);
        TestSupport.CLOCK.advance(untilChargeTime(b.startAt()));
        assertThat(balances.chargeDueBalances()).isGreaterThanOrEqualTo(1);
        PAYMENTS.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/payments/" + b.paymentId()
                        + "/off-session-charges"))
                .withHeader("Idempotency-Key", WireMock.equalTo(b.ref() + ":offsession:1"))
                .withRequestBody(new EqualToJsonPattern("{\"customerId\":\"cust-far\",\"lineIds\":[\"balance\"]}",
                        true, false)));
        JsonNode authorised = ok(send("GET", "/v1/bookings/" + b.id(), null, null));
        assertThat(authorised.path("payment").path("balance").path("state").asString()).isEqualTo("AUTHORIZED");
        assertThat(authorised.path("payment").path("balance").path("paymentId").asString())
                .isEqualTo(charge.toString());

        // A second round does nothing more.
        balances.chargeDueBalances();
        PAYMENTS.verify(1, WireMock.postRequestedFor(offSessionOf(b)));

        TestSupport.CLOCK.advance(Duration.ofDays(2).plusHours(2));
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode done = ok(send("POST", "/v1/bookings/" + b.id() + "/complete", null, "complete-" + b.id()));
        assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.path("payment").path("balanceCapturedMinor").asLong()).isEqualTo(9_000);
        assertThat(done.path("payment").path("balance").path("state").asString()).isEqualTo("CAPTURED");
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/payments/" + charge + "/capture"))
                .withRequestBody(WireMock.matchingJsonPath("$.lineIds[0]", WireMock.equalTo("balance")))
                .withRequestBody(WireMock.matchingJsonPath("$.amountMinor", WireMock.equalTo("9000"))));
        PAYMENTS.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/payments/" + b.paymentId()
                + "/capture")).withRequestBody(WireMock.matchingJsonPath("$.lineIds[0]", WireMock.equalTo("balance"))));
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/settlements/" + b.ref() + "/delivered")));
    }

    @Test
    void scaIsExposedToTheCustomerAndTheBalanceEventAuthorisesIt() {
        Setup s = provider(false);
        Far b = confirmedFar(s, "cust-sca");
        UUID charge = UUID.randomUUID();
        stubOffSession(b.ref() + ":offsession:1", charge, "REQUIRES_ACTION", "authentication_required");
        TestSupport.CLOCK.advance(untilChargeTime(b.startAt()));
        balances.chargeDueBalances();

        TestSupport.as(TestSupport.customer("cust-sca"));
        JsonNode mine = ok(send("GET", "/v1/bookings/" + b.id() + "/balance-payment", null, null));
        assertThat(mine.path("state").asString()).isEqualTo("ACTION_REQUIRED");
        assertThat(mine.path("clientSecret").asString()).isEqualTo("pi_" + charge + "_secret");
        assertThat(mine.path("amountMinor").asLong()).isEqualTo(9_000);
        JsonNode booking = ok(send("GET", "/v1/bookings/" + b.id(), null, null));
        assertThat(booking.path("payment").path("balance").path("needsCustomerAction").asBoolean()).isTrue();

        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode providerView = ok(send("GET", "/v1/bookings/" + b.id() + "/balance-payment", null, null));
        assertThat(providerView.path("state").asString()).isEqualTo("ACTION_REQUIRED");
        assertThat(providerView.path("clientSecret").isNull()).isTrue();

        // A stale event of another charge is ignored; the off-session charge's own event authorises it.
        assertThat(paymentEvents.handle(UUID.randomUUID(), "payments.intent.succeeded",
                new IntentEvent(b.ref(), UUID.randomUUID(), "AUTHORIZED", null, b.paymentId(), true))).isFalse();
        assertThat(paymentEvents.handle(UUID.randomUUID(), "payments.intent.succeeded",
                new IntentEvent(b.ref(), charge, "AUTHORIZED", null, b.paymentId(), true))).isTrue();
        JsonNode after = ok(send("GET", "/v1/bookings/" + b.id(), null, null));
        assertThat(after.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(after.path("payment").path("balance").path("state").asString()).isEqualTo("AUTHORIZED");
        assertThat(after.path("payment").path("state").asString()).isEqualTo("DEPOSIT_CAPTURED");
    }

    @Test
    void declinedChargesAreRetriedWithNewKeysThenFlagged() {
        Setup s = provider(false);
        Far b = confirmedFar(s, "cust-declined");
        for (int n = 1; n <= 3; n++) {
            stubOffSession(b.ref() + ":offsession:" + n, UUID.randomUUID(), "FAILED", "card_declined");
        }
        TestSupport.CLOCK.advance(untilChargeTime(b.startAt()));
        balances.chargeDueBalances();
        assertThat(balanceState(b)).isEqualTo("FAILED");

        // Before the retry delay nothing happens; after it, attempt 2 with a new key.
        TestSupport.CLOCK.advance(Duration.ofHours(1));
        balances.chargeDueBalances();
        PAYMENTS.verify(1, WireMock.postRequestedFor(offSessionOf(b)));
        TestSupport.CLOCK.advance(Duration.ofHours(6));
        balances.chargeDueBalances();
        TestSupport.CLOCK.advance(Duration.ofHours(6));
        balances.chargeDueBalances();
        for (int n = 1; n <= 3; n++) {
            PAYMENTS.verify(1, WireMock.postRequestedFor(offSessionOf(b))
                    .withHeader("Idempotency-Key", WireMock.equalTo(b.ref() + ":offsession:" + n)));
        }
        assertThat(balanceState(b)).isEqualTo("NEEDS_ATTENTION");
        TestSupport.CLOCK.advance(Duration.ofHours(6));
        balances.chargeDueBalances();
        PAYMENTS.verify(3, WireMock.postRequestedFor(offSessionOf(b)));
        assertThat(outboxTypes(b.id())).containsExactly("booking.requested", "booking.confirmed",
                "booking.balance_unpaid");
        String payload = jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id and type = "
                + "'booking.balance_unpaid'").param("id", b.id()).query(String.class).single();
        assertThat(JSON.readTree(payload).path("balanceState").asString()).isEqualTo("NEEDS_ATTENTION");
        assertThat(JSON.readTree(payload).path("balanceFailure").asString()).isEqualTo("card_declined");
    }

    @Test
    void parallelJobRoundsChargeABookingOnce() throws Exception {
        Setup s = provider(false);
        Far b = confirmedFar(s, "cust-parallel");
        PAYMENTS.stubFor(WireMock.post(WireMock.urlPathMatching(OFF_SESSION))
                .withHeader("Idempotency-Key", WireMock.equalTo(b.ref() + ":offsession:1"))
                .willReturn(offSessionResponse(UUID.randomUUID(), "AUTHORIZED", null).withFixedDelay(700)));
        TestSupport.CLOCK.advance(untilChargeTime(b.startAt()));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> rounds = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                rounds.add(balances::chargeDueBalances);
            }
            for (Future<Integer> f : pool.invokeAll(rounds)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        PAYMENTS.verify(1, WireMock.postRequestedFor(offSessionOf(b))
                .withHeader("Idempotency-Key", WireMock.equalTo(b.ref() + ":offsession:1")));
        assertThat(balanceState(b)).isEqualTo("AUTHORIZED");
    }

    @Test
    void cancellingAfterTheDepositCaptureReleasesTheRestOfTheAuthorisation() {
        Setup s = provider(false);
        JsonNode b = book("cust-release", s.serviceId(), firstSlot(s.serviceId()));
        String id = b.path("id").asString();
        String ref = b.path("reference").asString();
        paymentEvents.handle(UUID.randomUUID(), "payments.intent.succeeded", ref, "AUTHORIZED", null);
        TestSupport.as(TestSupport.owner(s.org()));
        ok(send("POST", "/v1/bookings/" + id + "/confirm", null, "confirm-" + id));
        // Within the authorisation window: nothing released on confirmation.
        PAYMENTS.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payments/.+/release")));

        TestSupport.as(TestSupport.customer("cust-release"));
        JsonNode cancelled = ok(send("POST", "/v1/bookings/" + id + "/cancel", Map.of("reason", "Travelling"),
                "cancel-" + id));
        assertThat(cancelled.path("booking").path("payment").path("balance").path("state").asString())
                .isEqualTo("RELEASED");
        PAYMENTS.verify(1, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payments/.+/release"))
                .withHeader("Idempotency-Key", WireMock.equalTo(ref + ":release"))
                .withRequestBody(WireMock.matchingJsonPath("$.reason", WireMock.equalTo("Travelling"))));
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payments/.+/refunds"))
                .withHeader("Idempotency-Key", WireMock.equalTo(ref + ":refund:deposit")));
    }

    @Test
    void noShowReleasesTheRestOfTheAuthorisation() {
        Setup s = provider(true);
        JsonNode b = book("cust-noshow", s.serviceId(), firstSlot(s.serviceId()));
        String id = b.path("id").asString();
        String ref = b.path("reference").asString();
        paymentEvents.handle(UUID.randomUUID(), "payments.intent.succeeded", ref, "AUTHORIZED", null);
        TestSupport.CLOCK.advance(Duration.between(TestSupport.CLOCK.instant(),
                Instant.parse(b.path("startAt").asString()).plus(Duration.ofMinutes(10))));
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode noShow = ok(send("POST", "/v1/bookings/" + id + "/no-show", null, "noshow-" + id));
        assertThat(noShow.path("status").asString()).isEqualTo("NO_SHOW");
        assertThat(noShow.path("payment").path("releasedMinor").asLong()).isEqualTo(9_000);
        PAYMENTS.verify(1, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payments/.+/release"))
                .withHeader("Idempotency-Key", WireMock.equalTo(ref + ":release")));
    }

    // ------------------------------------------------------------------ helpers

    /** Books 20 days ahead as {@code customer}, authorises the deposit and confirms as the provider. */
    private Far confirmedFar(Setup s, String customer) {
        String start = firstSlot(s.serviceId(), day().plusDays(15));
        JsonNode b = book(customer, s.serviceId(), start);
        String id = b.path("id").asString();
        String ref = b.path("reference").asString();
        assertThat(paymentEvents.handle(UUID.randomUUID(), "payments.intent.succeeded", ref, "AUTHORIZED", null))
                .isTrue();
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode confirmed = ok(send("POST", "/v1/bookings/" + id + "/confirm", null, "confirm-" + id));
        assertThat(confirmed.path("status").asString()).isEqualTo("CONFIRMED");
        return new Far(id, ref, UUID.fromString(confirmed.path("payment").path("paymentId").asString()),
                Instant.parse(start));
    }

    /** Clock offset that lands one hour inside the off-session charge window (2 days before the start). */
    private static Duration untilChargeTime(Instant startAt) {
        return Duration.between(TestSupport.CLOCK.instant(), startAt.minus(Duration.ofDays(2)).plusSeconds(3600));
    }

    private static UrlPattern offSessionOf(Far b) {
        return WireMock.urlEqualTo("/v1/payments/" + b.paymentId() + "/off-session-charges");
    }

    private static void stubOffSession(String key, UUID chargeId, String status, String failure) {
        PAYMENTS.stubFor(WireMock.post(WireMock.urlPathMatching(OFF_SESSION))
                .withHeader("Idempotency-Key", WireMock.equalTo(key))
                .willReturn(offSessionResponse(chargeId, status, failure)));
    }

    private static ResponseDefinitionBuilder offSessionResponse(
            UUID chargeId, String status, String failure) {
        return WireMock.aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + chargeId + "\",\"orderId\":\"x\",\"status\":\"" + status + "\","
                        + "\"clientSecret\":\"pi_" + chargeId + "_secret\","
                        + (failure == null ? "" : "\"failureMessage\":\"" + failure + "\",")
                        + "\"parentPaymentId\":\"{{request.pathSegments.[2]}}\",\"offSession\":true}");
    }

    private String balanceState(Far b) {
        return jdbc.sql("select balance_state from booking where id = cast(:id as uuid)").param("id", b.id())
                .query(String.class).single();
    }

    /** A confirmed booking 20 days ahead. */
    private record Far(String id, String ref, UUID paymentId, Instant startAt) {
    }
}
