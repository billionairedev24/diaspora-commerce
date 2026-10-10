package com.afrotransact.bookings.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.bookings.application.PaymentEventIngest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Fees from payments' effective fee schedule (with the configured fallback), service search by ZIP through geo's
 * provider service areas, and ratings from customers' reviews.
 */
class FeesSearchReviewsIT extends BookingsIntegrationTest {

    private final PaymentEventIngest paymentEvents;

    @Autowired
    FeesSearchReviewsIT(PaymentEventIngest paymentEvents) {
        this.paymentEvents = paymentEvents;
    }

    @Test
    void takeHomeAndEarningsUseThePaymentsFeeSchedule() {
        PAYMENTS.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/fee-schedules/effective"))
                .withQueryParam("category", WireMock.equalTo("services"))
                .willReturn(WireMock.okJson("{\"feeScheduleId\":\"" + UUID.randomUUID() + "\",\"version\":7,"
                        + "\"currency\":\"USD\",\"category\":\"services\",\"orgId\":\"{{request.query.orgId}}\","
                        + "\"commissionBps\":800,\"commissionSource\":\"OVERRIDE\",\"cardPctBps\":300,"
                        + "\"cardFixedMinor\":25}")));
        Setup s = provider(false);
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode service = ok(send("GET", "/v1/providers/" + s.org() + "/services/" + s.serviceId(), null, null));
        JsonNode takeHome = service.path("takeHome");
        assertThat(takeHome.path("commissionBps").asInt()).isEqualTo(800);
        assertThat(takeHome.path("feeSource").asString()).isEqualTo("OVERRIDE");
        assertThat(takeHome.path("feeVersion").asInt()).isEqualTo(7);
        // 12 000 − 8 % − (3 % + 25¢ on 3 000) − (3 % + 25¢ on 9 000)
        assertThat(takeHome.path("takeHomeMinor").asLong()).isEqualTo(12_000 - 960 - (90 + 25) - (270 + 25));
        PAYMENTS.verify(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/fee-schedules/effective"))
                .withQueryParam("orgId", WireMock.equalTo(s.org()))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-bookings")));

        JsonNode b = completed(s, "cust-fees");
        TestSupport.as(TestSupport.owner(s.org()));
        Instant start = Instant.parse(b.path("startAt").asString());
        JsonNode earnings = ok(send("GET", "/v1/providers/" + s.org() + "/earnings?from="
                + start.minus(Duration.ofDays(1)) + "&to=" + start.plus(Duration.ofDays(1)), null, null));
        assertThat(earnings.path("collectedMinor").asLong()).isEqualTo(12_000);
        assertThat(earnings.path("commissionMinor").asLong()).isEqualTo(960);
        // Cached: the provider's fee schedule was read once.
        PAYMENTS.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/fee-schedules/effective"))
                .withQueryParam("orgId", WireMock.equalTo(s.org())));
    }

    @Test
    void configuredFeesApplyWhilePaymentsCannotAnswer() {
        PAYMENTS.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/fee-schedules/effective"))
                .willReturn(WireMock.aResponse().withStatus(503)));
        Setup s = provider(false);
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode takeHome = ok(send("GET", "/v1/providers/" + s.org() + "/services/" + s.serviceId(), null, null))
                .path("takeHome");
        assertThat(takeHome.path("commissionBps").asInt()).isEqualTo(1000);
        assertThat(takeHome.path("feeSource").asString()).isEqualTo("CONFIG");
        assertThat(takeHome.path("feeVersion").isNull()).isTrue();
    }

    @Test
    void zipSearchKeepsProvidersWhoseServiceAreaCoversIt() {
        Setup near = provider(false);
        Setup far = provider(false);
        String token = "zipcheck" + UUID.randomUUID().toString().substring(0, 8);
        UUID nearService = service(near, "Braids " + token, List.of("IN_PERSON"));
        service(far, "Braids " + token, List.of("AT_HOME"));
        UUID virtualService = service(far, "Consultation " + token, List.of("VIRTUAL"));

        GEO.stubFor(WireMock.get(WireMock.urlEqualTo("/v1/zip/77002"))
                .willReturn(WireMock.okJson("{\"city\":\"Houston\",\"state\":\"TX\",\"postalCode\":\"77002\","
                        + "\"country\":\"US\",\"lat\":29.7543,\"lon\":-95.3577}")));
        GEO.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/zones/covering"))
                .withQueryParam("type", WireMock.equalTo("SERVICE_AREA"))
                .willReturn(WireMock.okJson("{\"zones\":[{\"id\":\"" + UUID.randomUUID() + "\",\"type\":"
                        + "\"SERVICE_AREA\",\"ownerId\":\"" + near.org() + "\",\"name\":\"15 miles\",\"active\":true},"
                        + "{\"type\":\"SERVICE_AREA\",\"ownerId\":\"" + far.org() + "\",\"name\":\"old\","
                        + "\"active\":false}]}")));
        GEO.stubFor(WireMock.get(WireMock.urlEqualTo("/v1/zip/99999"))
                .willReturn(WireMock.aResponse().withStatus(404)));

        JsonNode all = ok(send("GET", "/v1/public/services/search?q=" + token + "&size=10", null, null));
        assertThat(all.path("services").size()).isEqualTo(3);

        JsonNode inArea = ok(send("GET", "/v1/public/services/search?q=" + token + "&zip=77002&size=10", null, null));
        assertThat(inArea.path("services").valueStream().map(n -> n.path("serviceId").asString()).toList())
                .containsExactlyInAnyOrder(nearService.toString(), virtualService.toString());
        GEO.verify(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/zones/covering"))
                .withQueryParam("lat", WireMock.equalTo("29.7543"))
                .withQueryParam("lon", WireMock.equalTo("-95.3577")));

        JsonNode unknown = ok(send("GET", "/v1/services/search?q=" + token + "&zip=99999", null, null));
        assertThat(unknown.path("services").valueStream().map(n -> n.path("serviceId").asString()).toList())
                .containsExactly(virtualService.toString());
        problem(send("GET", "/v1/public/services/search?q=" + token + "&zip=7700", null, null), 422,
                "bookings.invalid-zip");

        GEO.stubFor(WireMock.get(WireMock.urlEqualTo("/v1/zip/10001")).willReturn(WireMock.serverError()));
        problem(send("GET", "/v1/public/services/search?q=" + token + "&zip=10001", null, null), 502,
                "bookings.geo-unavailable");
    }

    @Test
    void customersRateCompletedBookingsOnceAndSearchShowsTheAverage() {
        Setup s = provider(false);
        JsonNode open = book("cust-open", s.serviceId(), firstSlot(s.serviceId()));
        TestSupport.as(TestSupport.customer("cust-open"));
        problem(send("POST", "/v1/bookings/" + open.path("id").asString() + "/review", Map.of("rating", 5), null),
                409, "bookings.not-reviewable");

        JsonNode first = completed(s, "cust-rate-1");
        JsonNode second = completed(s, "cust-rate-2");

        TestSupport.as(TestSupport.customer("cust-rate-2"));
        problem(send("POST", "/v1/bookings/" + first.path("id").asString() + "/review", Map.of("rating", 1), null),
                404, "bookings.booking-not-found");

        TestSupport.as(TestSupport.customer("cust-rate-1"));
        assertThat(send("POST", "/v1/bookings/" + first.path("id").asString() + "/review", Map.of("rating", 6), null)
                .statusCode()).isEqualTo(400);
        JsonNode review = ok(send("POST", "/v1/bookings/" + first.path("id").asString() + "/review",
                Map.of("rating", 5, "text", "Neat rows, on time"), null), 201);
        assertThat(review.path("rating").asInt()).isEqualTo(5);
        assertThat(review.path("serviceId").asString()).isEqualTo(s.serviceId().toString());
        problem(send("POST", "/v1/bookings/" + first.path("id").asString() + "/review", Map.of("rating", 4), null),
                409, "bookings.already-reviewed");

        TestSupport.as(TestSupport.customer("cust-rate-2"));
        ok(send("POST", "/v1/bookings/" + second.path("id").asString() + "/review", Map.of("rating", 4), null), 201);

        TestSupport.as(TestSupport.owner(s.org()));
        problem(send("POST", "/v1/bookings/" + second.path("id").asString() + "/review", Map.of("rating", 1), null),
                403, "bookings.customer-only");

        TestSupport.reset();
        JsonNode hit = ok(send("GET", "/v1/public/services/search?q=" + tag(s.org()), null, null))
                .path("services").get(0);
        assertThat(hit.path("serviceId").asString()).isEqualTo(s.serviceId().toString());
        assertThat(hit.path("rating").asDouble()).isEqualTo(4.5);
        assertThat(hit.path("ratingCount").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private UUID service(Setup s, String name, List<String> modes) {
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode created = ok(send("POST", "/v1/providers/" + s.org() + "/services", Map.of("name", name,
                "durationMinutes", 60, "priceMinor", 10_000, "depositMinor", 2_000, "modes", modes), null), 201);
        TestSupport.reset();
        return UUID.fromString(created.path("id").asString());
    }

    /** A booking of the provider's service, confirmed and completed by the provider. */
    private JsonNode completed(Setup s, String customer) {
        JsonNode b = book(customer, s.serviceId(), firstSlot(s.serviceId()));
        String id = b.path("id").asString();
        paymentEvents.handle(UUID.randomUUID(), "payments.intent.succeeded", b.path("reference").asString(),
                "AUTHORIZED", null);
        TestSupport.as(TestSupport.owner(s.org()));
        ok(send("POST", "/v1/bookings/" + id + "/confirm", null, "confirm-" + id));
        JsonNode done = ok(send("POST", "/v1/bookings/" + id + "/complete", null, "complete-" + id));
        assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
        return done;
    }
}
