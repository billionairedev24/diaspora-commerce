package com.afrotransact.bookings.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
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

import com.afrotransact.bookings.application.BookingMaintenanceService;
import com.afrotransact.bookings.application.PaymentEventIngest;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.EqualToJsonPattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

/** The booking flow end to end against PostgreSQL, with payments-service stubbed by WireMock. */
class BookingFlowIT extends BookingsIntegrationTest {

    private final PaymentEventIngest paymentEvents;
    private final BookingMaintenanceService maintenance;

    @Autowired
    BookingFlowIT(PaymentEventIngest paymentEvents, BookingMaintenanceService maintenance) {
        this.paymentEvents = paymentEvents;
        this.maintenance = maintenance;
    }

    @Test
    void requestConfirmCompleteMovesMoneyThroughPayments() {
        Setup s = provider(false);
        String start = firstSlot(s.serviceId());

        TestSupport.as(TestSupport.customer("cust-adaeze"));
        HttpResponse<String> created = send("POST", "/v1/bookings", bookingBody(s.serviceId(), start), "book-1-" + s.org());
        JsonNode body = ok(created, 201);
        JsonNode booking = body.path("booking");
        String id = booking.path("id").asString();
        String ref = booking.path("reference").asString();
        assertThat(ref).startsWith("BK-");
        assertThat(booking.path("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(booking.path("payment").path("clientSecret").asString()).isEqualTo("pi_" + ref + "_secret");
        assertThat(booking.path("policy").path("kind").asString()).isEqualTo("MODERATE");

        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/payments"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-bookings"))
                .withHeader("Idempotency-Key", WireMock.equalTo(ref + ":create-intent"))
                .withRequestBody(WireMock.matchingJsonPath("$.amountMinor", WireMock.equalTo("12000")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].lineId", WireMock.equalTo("deposit")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].itemsMinor", WireMock.equalTo("3000")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[1].lineId", WireMock.equalTo("balance")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[1].sellerOrg", WireMock.equalTo(s.org()))));

        // Replay with the same key returns the same booking.
        JsonNode replay = ok(send("POST", "/v1/bookings", bookingBody(s.serviceId(), start), "book-1-" + s.org()), 200);
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("booking").path("id").asString()).isEqualTo(id);

        // The held slot is no longer offered.
        assertThat(ok(send("GET", "/v1/public/services/" + s.serviceId() + "/slots?date=" + day(), null, null))
                .valueStream().map(n -> n.path("start").asString()).toList()).doesNotContain(start);

        // The customer confirmed the card: payments says AUTHORIZED.
        JsonNode synced = ok(send("POST", "/v1/bookings/" + id + "/payment/sync", null, null));
        assertThat(synced.path("status").asString()).isEqualTo("REQUESTED");
        assertThat(synced.path("requestExpiresAt").isNull()).isFalse();

        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode confirmed = ok(send("POST", "/v1/bookings/" + id + "/confirm", null, "confirm-" + id));
        assertThat(confirmed.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("payment").path("depositCapturedMinor").asLong()).isEqualTo(3_000);
        JsonNode again = ok(send("POST", "/v1/bookings/" + id + "/confirm", null, "confirm-" + id));
        assertThat(again.path("version").asLong()).isEqualTo(confirmed.path("version").asLong());
        problem(send("POST", "/v1/bookings/" + id + "/confirm", null, "confirm-other-" + id), 409,
                "bookings.invalid-transition");
        PAYMENTS.verify(1, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payments/.+/capture"))
                .withHeader("Idempotency-Key", WireMock.equalTo(ref + ":capture:deposit"))
                .withRequestBody(new EqualToJsonPattern("{\"shipmentId\":\"" + ref + "-deposit\",\"lineIds\":"
                        + "[\"deposit\"],\"amountMinor\":3000,\"finalCapture\":false}", true, false)));

        JsonNode done = ok(send("POST", "/v1/bookings/" + id + "/complete", null, "complete-" + id));
        assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.path("payment").path("balanceCapturedMinor").asLong()).isEqualTo(9_000);
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payments/.+/capture"))
                .withHeader("Idempotency-Key", WireMock.equalTo(ref + ":capture:balance"))
                .withRequestBody(WireMock.matchingJsonPath("$.finalCapture", WireMock.equalTo("true"))));
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/settlements/" + ref + "/delivered"))
                .withRequestBody(WireMock.matchingJsonPath("$.kind", WireMock.equalTo("SERVICE"))));

        assertThat(outboxTypes(id)).containsExactly("booking.requested", "booking.confirmed", "booking.completed");
        String payload = jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id and type = "
                + "'booking.confirmed'").param("id", id).query(String.class).single();
        assertThat(JSON.readTree(payload).path("recipient").path("email").asString()).isEqualTo("adaeze@example.com");
        assertThat(JSON.readTree(payload).path("bookingReference").asString()).isEqualTo(ref);

        JsonNode earnings = ok(send("GET", "/v1/providers/" + s.org() + "/earnings?from="
                + Instant.parse(start).minus(Duration.ofDays(1)) + "&to=" + Instant.parse(start).plus(Duration.ofDays(1)),
                null, null));
        assertThat(earnings.path("collectedMinor").asLong()).isEqualTo(12_000);
        assertThat(earnings.path("commissionMinor").asLong()).isEqualTo(1_200);

        TestSupport.as(TestSupport.customer("cust-adaeze"));
        assertThat(ok(send("GET", "/v1/bookings/" + id + "/history", null, null)).size()).isGreaterThan(4);
        TestSupport.as(TestSupport.customer("cust-someone-else"));
        problem(send("GET", "/v1/bookings/" + id, null, null), 404, "bookings.booking-not-found");
    }

    @Test
    void parallelRequestsForOneSlotBookItOnce() throws Exception {
        Setup s = provider(false);
        String start = firstSlot(s.serviceId());
        TestSupport.as(TestSupport.customer("cust-race"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String key = "race-" + i + "-" + s.org();
                attempts.add(() -> send("POST", "/v1/bookings", bookingBody(s.serviceId(), start), key).statusCode());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(attempts)) {
                statuses.add(f.get());
            }
            assertThat(statuses).filteredOn(code -> code == 201).hasSize(1);
            assertThat(statuses).filteredOn(code -> code == 409).hasSize(7);
        } finally {
            pool.shutdownNow();
        }
        long active = jdbc.sql("select count(*) from booking where org_id = :o and status = 'PENDING_PAYMENT'")
                .param("o", s.org()).query(Long.class).single();
        assertThat(active).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesOverlappingTimeForOneMember() {
        Setup s = provider(false);
        JsonNode first = book("cust-db", s.serviceId(), firstSlot(s.serviceId()));
        JsonNode second = book("cust-db", s.serviceId(), firstSlot(s.serviceId()));
        assertThat(second.path("startAt").asString()).isNotEqualTo(first.path("startAt").asString());
        assertThatThrownBy(() -> jdbc.sql("""
                insert into booking_time_block (booking_id, kind, member_id, period)
                select :second, 'PROPOSED', member_id, period from booking_time_block where booking_id = :first
                """).param("second", UUID.fromString(second.path("id").asString()))
                .param("first", UUID.fromString(first.path("id").asString())).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("booking_time_block_no_overlap");
    }

    @Test
    void customerSeesTheExactRefundThenCancelsUnderTheBookingsPolicy() {
        Setup s = provider(true);
        JsonNode b = book("cust-cancel", s.serviceId(), firstSlot(s.serviceId()));
        String id = b.path("id").asString();
        String ref = b.path("reference").asString();
        assertThat(paymentEvents.handle(UUID.randomUUID(), "payments.intent.succeeded", ref, "AUTHORIZED", null))
                .isTrue();

        // The provider switches to Strict afterwards; the booking keeps Moderate.
        TestSupport.as(TestSupport.owner(s.org()));
        ok(send("PUT", "/v1/providers/" + s.org() + "/cancellation-policy", Map.of("kind", "STRICT"), null));

        // 30 hours before the start: Moderate refunds half the deposit.
        Instant startAt = Instant.parse(b.path("startAt").asString());
        TestSupport.CLOCK.advance(Duration.between(TestSupport.CLOCK.instant(), startAt.minus(Duration.ofHours(30))));
        TestSupport.as(TestSupport.customer("cust-cancel"));
        JsonNode quote = ok(send("GET", "/v1/bookings/" + id + "/cancellation-quote", null, null));
        assertThat(quote.path("policy").asString()).isEqualTo("Moderate");
        assertThat(quote.path("refundMinor").asLong()).isEqualTo(1_500);
        assertThat(quote.path("keptMinor").asLong()).isEqualTo(1_500);

        JsonNode cancelled = ok(send("POST", "/v1/bookings/" + id + "/cancel", Map.of("reason", "Travelling"),
                "cancel-" + id));
        assertThat(cancelled.path("booking").path("status").asString()).isEqualTo("CANCELLED_CUSTOMER");
        assertThat(cancelled.path("applied").path("refundMinor").asLong()).isEqualTo(1_500);
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payments/.+/refunds"))
                .withHeader("Idempotency-Key", WireMock.equalTo(ref + ":refund:deposit"))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].itemsMinor", WireMock.equalTo("1500"))));
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/settlements/" + ref + "/delivered")));
        assertThat(outboxTypes(id)).containsExactly("booking.confirmed", "booking.cancelled");
    }

    @Test
    void unansweredRequestsExpireAndReleaseTheDepositHold() {
        Setup s = provider(false);
        JsonNode b = book("cust-expire", s.serviceId(), firstSlot(s.serviceId()));
        String ref = b.path("reference").asString();
        UUID event = UUID.randomUUID();
        assertThat(paymentEvents.handle(event, "payments.intent.succeeded", ref, "AUTHORIZED", null)).isTrue();
        assertThat(paymentEvents.handle(event, "payments.intent.succeeded", ref, "AUTHORIZED", null)).isFalse();

        TestSupport.CLOCK.advance(Duration.ofHours(3));
        maintenance.expireRequests();
        assertThat(status(ref)).isEqualTo("REQUESTED");

        TestSupport.CLOCK.advance(Duration.ofHours(1).plusMinutes(1));
        assertThat(maintenance.expireRequests()).isGreaterThanOrEqualTo(1);
        assertThat(status(ref)).isEqualTo("EXPIRED");
        PAYMENTS.verify(WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payments/.+/cancel"))
                .withHeader("Idempotency-Key", WireMock.equalTo(ref + ":cancel")));
        assertThat(outboxTypes(b.path("id").asString())).containsExactly("booking.requested", "booking.expired");
    }

    @Test
    void unpaidHoldsLapseAndFreeTheSlot() {
        Setup s = provider(false);
        String start = firstSlot(s.serviceId());
        JsonNode b = book("cust-abandon", s.serviceId(), start);
        TestSupport.CLOCK.advance(Duration.ofMinutes(16));
        assertThat(maintenance.expireHolds()).isGreaterThanOrEqualTo(1);
        assertThat(status(b.path("reference").asString())).isEqualTo("EXPIRED");
        TestSupport.reset();
        assertThat(firstSlot(s.serviceId())).isEqualTo(start);
    }

    @Test
    void providerProposesANewTimeAndTheCustomerAccepts() {
        Setup s = provider(false);
        JsonNode b = book("cust-move", s.serviceId(), firstSlot(s.serviceId()));
        String id = b.path("id").asString();
        paymentEvents.handle(UUID.randomUUID(), "payments.intent.succeeded", b.path("reference").asString(),
                "AUTHORIZED", null);
        Instant newStart = Instant.parse(b.path("startAt").asString()).plus(Duration.ofHours(3));

        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode proposed = ok(send("POST", "/v1/bookings/" + id + "/propose-time",
                Map.of("startAt", newStart.toString()), "propose-" + id));
        assertThat(proposed.path("status").asString()).isEqualTo("RESCHEDULE_PROPOSED");
        problem(send("POST", "/v1/bookings/" + id + "/new-time/accept", null, "owner-accept-" + id), 409,
                "bookings.nothing-to-decide");

        TestSupport.as(TestSupport.customer("cust-move"));
        JsonNode accepted = ok(send("POST", "/v1/bookings/" + id + "/new-time/accept", null, "accept-" + id));
        assertThat(accepted.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(Instant.parse(accepted.path("startAt").asString())).isEqualTo(newStart);
        assertThat(outboxTypes(id)).containsExactly("booking.requested", "booking.time_proposed",
                "booking.rescheduled");
    }

    @Test
    void priceChangesDoNotAffectExistingBookingsAndServicesShowTakeHome() {
        Setup s = provider(false);
        JsonNode b = book("cust-price", s.serviceId(), firstSlot(s.serviceId()));
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode updated = ok(send("PUT", "/v1/providers/" + s.org() + "/services/" + s.serviceId(),
                Map.of("name", "Cornrows · 8–10 rows", "durationMinutes", 90, "priceMinor", 15_000,
                        "depositMinor", 5_000, "modes", List.of("IN_PERSON")), null));
        assertThat(updated.path("takeHome").path("takeHomeMinor").asLong())
                .isEqualTo(15_000 - 1_500 - (145 + 30) - (290 + 30));
        JsonNode booking = ok(send("GET", "/v1/bookings/" + b.path("id").asString(), null, null));
        assertThat(booking.path("service").path("priceMinor").asLong()).isEqualTo(12_000);
        assertThat(booking.path("service").path("durationMinutes").asInt()).isEqualTo(60);

        JsonNode summary = ok(send("GET", "/v1/public/services/" + s.serviceId() + "/summary", null, null));
        assertThat(summary.path("depositNowMinor").asLong()).isEqualTo(5_000);
        assertThat(summary.path("balanceLaterMinor").asLong()).isEqualTo(10_000);
        assertThat(summary.path("policyText").asString()).startsWith("Free up to 48 hours before");
    }

    @Test
    void publicSearchFindsServicesWithTheNextFreeTime() {
        Setup s = provider(false);
        String name = "Fulani braids " + s.org();
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode created = ok(send("POST", "/v1/providers/" + s.org() + "/services", Map.of("name", name,
                "durationMinutes", 240, "priceMinor", 16_000, "depositMinor", 4_000, "modes", List.of("IN_PERSON")),
                null), 201);
        TestSupport.reset();
        JsonNode found = ok(send("GET", "/v1/services/search?q=" + s.org() + "&size=5", null, null));
        JsonNode hit = found.path("services").get(0);
        assertThat(hit.path("serviceId").asString()).isEqualTo(created.path("id").asString());
        assertThat(hit.path("providerName").asString()).isEqualTo("Aminata's Braids " + tag(s.org()));
        assertThat(hit.path("nextAvailable").isNull()).isFalse();
        assertThat(ok(send("GET", "/v1/public/providers/" + s.org(), null, null)).path("services").size())
                .isEqualTo(2);
    }

    private String status(String reference) {
        return jdbc.sql("select status from booking where reference = :r").param("r", reference)
                .query(String.class).single();
    }
}
