package com.afrotransact.bookings.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Service areas end to end on PostgreSQL: the haversine candidate query, the matching rules, the metro-zone labels,
 * the nearest fallback, category counts, sorting, in-person-only validation and the staff policy.
 */
class ServiceAreasIT extends BookingsIntegrationTest {

    private static final String[][] PLACES = {
        {"78626", "Georgetown", "TX", "30.6680", "-97.5747"},
        {"78664", "Round Rock", "TX", "30.5305", "-97.6351"},
        {"78634", "Hutto", "TX", "30.5430", "-97.5470"},
        {"78701", "Austin", "TX", "30.2711", "-97.7437"},
        {"11211", "Brooklyn", "NY", "40.7078", "-73.9453"},
        {"77002", "Houston", "TX", "29.7543", "-95.3577"},
    };

    @BeforeEach
    void stubGeo() {
        for (String[] p : PLACES) {
            GEO.stubFor(WireMock.get(WireMock.urlEqualTo("/v1/zip/" + p[0])).willReturn(WireMock.okJson(
                    "{\"city\":\"" + p[1] + "\",\"state\":\"" + p[2] + "\",\"postalCode\":\"" + p[0]
                            + "\",\"country\":\"US\",\"lat\":" + p[3] + ",\"lon\":" + p[4] + "}")));
        }
        GEO.stubFor(WireMock.get(WireMock.urlEqualTo("/v1/zip/99999")).willReturn(WireMock.aResponse().withStatus(404)));
        GEO.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/zips/nearby")).willReturn(WireMock.okJson(
                "{\"items\":[{\"city\":\"Round Rock\",\"state\":\"TX\",\"lat\":30.53,\"lon\":-97.63,"
                        + "\"distanceMiles\":0,\"zips\":[\"78664\"]},{\"city\":\"Georgetown\",\"state\":\"TX\","
                        + "\"lat\":30.66,\"lon\":-97.57,\"distanceMiles\":10,\"zips\":[\"78626\"]},"
                        + "{\"city\":\"Hutto\",\"state\":\"TX\",\"lat\":30.54,\"lon\":-97.54,\"distanceMiles\":5,"
                        + "\"zips\":[\"78634\"]},{\"city\":\"Pflugerville\",\"state\":\"TX\",\"lat\":30.44,"
                        + "\"lon\":-97.59,\"distanceMiles\":8,\"zips\":[\"78660\"]}]}")));
    }

    @Test
    void georgetownSeesTexasProvidersNearestFirstAndNeverBrooklyn() {
        String token = "area" + UUID.randomUUID().toString().substring(0, 8);
        Setup roundRock = placed("78664", 20, token, "braids rr", "hair & beauty", 9_000, List.of("AT_HOME"));
        Setup hutto = placed("78634", 25, token, "braids hutto", "hair & beauty", 18_000, List.of("IN_PERSON"));
        Setup brooklyn = placed("11211", 25, token, "braids bk", "hair & beauty", 5_000, List.of("AT_HOME"));

        JsonNode r = ok(send("GET", "/v1/public/services/search?q=" + token + "&zip=78626", null, null));
        assertThat(r.path("coverage").asString()).isEqualTo("LOCAL");
        assertThat(r.path("place").path("city").asString()).isEqualTo("Georgetown");
        List<String> orgs = r.path("services").valueStream().map(n -> n.path("orgId").asString()).toList();
        assertThat(orgs).containsExactly(hutto.org(), roundRock.org());
        assertThat(orgs).doesNotContain(brooklyn.org());
        JsonNode first = r.path("services").get(0);
        assertThat(first.path("area").path("baseCity").asString()).isEqualTo("Hutto");
        assertThat(first.path("area").path("distanceMiles").asDouble()).isBetween(5.0, 10.0);
        assertThat(first.path("area").path("serves").get(0).asString()).isEqualTo("Georgetown");
        assertThat(first.path("area").path("nearest").asBoolean()).isFalse();
        assertThat(r.path("nearest").size()).isZero();
        assertThat(r.path("categories").get(0).path("category").asString()).isEqualTo("hair & beauty");
        assertThat(r.path("categories").get(0).path("count").asInt()).isEqualTo(2);

        JsonNode bk = ok(send("GET", "/v1/public/services/search?q=" + token + "&zip=11211", null, null));
        assertThat(bk.path("services").valueStream().map(n -> n.path("orgId").asString()).toList())
                .containsExactly(brooklyn.org());

        JsonNode cheapest = ok(send("GET", "/v1/public/services/search?q=" + token + "&zip=78626&sort=PRICE", null,
                null));
        assertThat(cheapest.path("services").get(0).path("orgId").asString()).isEqualTo(roundRock.org());
        JsonNode soonest = ok(send("GET", "/v1/public/services/search?q=" + token + "&zip=78626&sort=SOONEST", null,
                null));
        assertThat(soonest.path("services").get(0).path("nextAvailable").isNull()).isFalse();
    }

    @Test
    void nothingServingThePlaceGivesTheNearestWithinTheMaximumOrNothing() {
        String token = "near" + UUID.randomUUID().toString().substring(0, 8);
        Setup austin = placed("78701", 5, token, "tailor austin", "tailoring", 22_000, List.of("IN_PERSON"));
        placed("11211", 25, token, "tailor brooklyn", "tailoring", 22_000, List.of("IN_PERSON"));

        JsonNode r = ok(send("GET", "/v1/public/services/search?q=" + token + "&zip=78626&category=tailoring", null,
                null));
        assertThat(r.path("services").size()).isZero();
        assertThat(r.path("coverage").asString()).isEqualTo("NEAREST");
        assertThat(r.path("nearest").size()).isEqualTo(1);
        JsonNode n = r.path("nearest").get(0);
        assertThat(n.path("orgId").asString()).isEqualTo(austin.org());
        assertThat(n.path("area").path("nearest").asBoolean()).isTrue();
        assertThat(n.path("area").path("distanceMiles").asDouble()).isBetween(20.0, 35.0);
        assertThat(r.path("categories").size()).as("no category serves Georgetown").isZero();

        JsonNode houston = ok(send("GET", "/v1/public/services/search?q=" + token + "&zip=77002", null, null));
        assertThat(houston.path("coverage").asString()).isEqualTo("NONE");
        assertThat(houston.path("services").size()).isZero();
        assertThat(houston.path("nearest").size()).isZero();

        JsonNode unknown = ok(send("GET", "/v1/public/services/search?zip=99999", null, null));
        assertThat(unknown.path("coverage").asString()).isEqualTo("NONE");
        problem(send("GET", "/v1/public/services/search?zip=7700", null, null), 422, "bookings.invalid-zip");
        GEO.stubFor(WireMock.get(WireMock.urlEqualTo("/v1/zip/10001")).willReturn(WireMock.serverError()));
        problem(send("GET", "/v1/public/services/search?zip=10001", null, null), 502, "bookings.geo-unavailable");
    }

    @Test
    void categoryCountsListWhatTheLocationHasAndChipsFilter() {
        String token = "cats" + UUID.randomUUID().toString().substring(0, 8);
        placed("78664", 20, token, "braids", "hair & beauty", 18_000, List.of("AT_HOME"));
        placed("78634", 40, token, "trays", "catering", 40_000, List.of("AT_HOME"));
        JsonNode all = ok(send("GET", "/v1/public/services/search?q=" + token + "&zip=78626", null, null));
        assertThat(all.path("categories").valueStream().map(c -> c.path("category").asString()).toList())
                .containsExactlyInAnyOrder("hair & beauty", "catering");
        JsonNode catering = ok(send("GET", "/v1/public/services/search?q=" + token + "&zip=78626&category=catering",
                null, null));
        assertThat(catering.path("services").size()).isEqualTo(1);
        assertThat(catering.path("categories").size()).as("counts ignore the category filter").isEqualTo(2);
    }

    @Test
    void providerSavesAnAreaWithinBoundsAndGetsAZoneLabel() {
        Setup s = provider(false);
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode empty = ok(send("GET", "/v1/providers/" + s.org() + "/service-area", null, null));
        assertThat(empty.path("area").isNull()).isTrue();
        assertThat(empty.path("bounds").path("maxMiles").asInt()).isEqualTo(40);
        assertThat(empty.path("customerTravelMiles").asInt()).isEqualTo(30);

        JsonNode saved = ok(send("PUT", "/v1/providers/" + s.org() + "/service-area", Map.of("baseZip", "78664",
                "radiusMiles", 20, "extraAreas", List.of(Map.of("kind", "CITY", "value", "Taylor", "state", "TX"),
                        Map.of("kind", "ZONE", "value", "greater-austin")))));
        assertThat(saved.path("area").path("baseCity").asString()).isEqualTo("Round Rock");
        assertThat(saved.path("area").path("zone").asString()).isEqualTo("Greater Austin");
        assertThat(saved.path("area").path("servedCities").valueStream().map(c -> c.path("city").asString())
                .toList()).contains("Georgetown", "Hutto", "Pflugerville", "Taylor", "Austin");

        problem(send("PUT", "/v1/providers/" + s.org() + "/service-area", Map.of("baseZip", "78664",
                "radiusMiles", 100)), 422, "bookings.invalid-service-area");
        problem(send("PUT", "/v1/providers/" + s.org() + "/service-area", Map.of("baseZip", "99999")), 422,
                "bookings.invalid-service-area");
        problem(send("PUT", "/v1/providers/" + s.org() + "/service-area", Map.of("baseZip", "78664",
                "extraAreas", List.of(Map.of("kind", "ZONE", "value", "atlantis")))), 422,
                "bookings.invalid-service-area");
        assertThat(send("PUT", "/v1/providers/" + s.org() + "/service-area", Map.of("baseZip", "786")).statusCode())
                .isEqualTo(400);
        JsonNode preview = ok(send("GET", "/v1/providers/" + s.org() + "/service-area/preview?zip=78664&radiusMiles=15",
                null, null));
        assertThat(preview.path("zone").asString()).isEqualTo("Greater Austin");
        assertThat(preview.path("cities").size()).isGreaterThan(2);

        TestSupport.as(TestSupport.finance(s.org()));
        assertThat(send("PUT", "/v1/providers/" + s.org() + "/service-area", Map.of("baseZip", "78664"))
                .statusCode()).isEqualTo(403);
    }

    @Test
    void servicesAreInPersonOnly() {
        Setup s = provider(false);
        TestSupport.as(TestSupport.owner(s.org()));
        for (Object modes : List.of(List.of("VIRTUAL"), List.of("IN_PERSON", "ONLINE"), List.of("remote"))) {
            problem(send("POST", "/v1/providers/" + s.org() + "/services", service("Consult", modes)), 422,
                    "bookings.in-person-only");
        }
        Map<String, Object> flagged = new LinkedHashMap<>(service("Consult", List.of("IN_PERSON")));
        flagged.put("virtual", true);
        problem(send("POST", "/v1/providers/" + s.org() + "/services", flagged), 422, "bookings.in-person-only");
        JsonNode ok = ok(send("POST", "/v1/providers/" + s.org() + "/services", service("Consult", List.of("AT_HOME"))),
                201);
        assertThat(ok.path("modes").get(0).asString()).isEqualTo("AT_HOME");
        problem(send("PUT", "/v1/providers/" + s.org() + "/services/" + ok.path("id").asString(),
                service("Consult", List.of("VIRTUAL"))), 422, "bookings.in-person-only");
    }

    @Test
    void staffChangePolicyAndZonesWithAReasonAndItIsAudited() {
        TestSupport.as(TestSupport.SUPER_ADMIN);
        JsonNode policy = ok(send("GET", "/v1/staff/service-areas/policy", null, null));
        Map<String, Object> body = JSON.convertValue(policy, Map.class);
        assertThat(send("PUT", "/v1/staff/service-areas/policy", Map.of("policy", body, "reason", "")).statusCode())
                .isEqualTo(400);
        body.put("nearestMaxMiles", 45);
        ok(send("PUT", "/v1/staff/service-areas/policy", Map.of("policy", body, "reason", "Tighten nearest fallback")));
        assertThat(ok(send("GET", "/v1/staff/service-areas/policy", null, null)).path("nearestMaxMiles").asInt())
                .isEqualTo(45);
        body.put("nearestMaxMiles", 60);
        ok(send("PUT", "/v1/staff/service-areas/policy", Map.of("policy", body, "reason", "Restore default")));

        ok(send("PUT", "/v1/staff/service-areas/zones/it-zone", Map.of("reason", "Add a test zone", "zone",
                Map.of("key", "it-zone", "name", "IT zone", "cities", List.of(Map.of("city", "Taylor", "state", "TX"))))));
        assertThat(ok(send("GET", "/v1/staff/service-areas/zones", null, null)).valueStream()
                .map(z -> z.path("key").asString()).toList()).contains("it-zone", "greater-austin");
        assertThat(send("DELETE", "/v1/staff/service-areas/zones/it-zone?reason=Remove+test+zone", null, null)
                .statusCode()).isEqualTo(200);
        assertThat(ok(send("GET", "/v1/staff/service-areas/audit", null, null)).valueStream()
                .map(a -> a.path("reason").asString()).toList()).contains("Add a test zone", "Remove test zone");

        TestSupport.as(TestSupport.owner(orgId()));
        assertThat(send("GET", "/v1/staff/service-areas/policy", null, null).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> send(String method, String path, Object body) {
        return send(method, path, body, null);
    }

    private static Map<String, Object> service(String name, Object modes) {
        return Map.of("name", name, "durationMinutes", 60, "priceMinor", 10_000, "depositMinor", 2_000, "modes", modes);
    }

    /** A provider with one service of {@code category} and a service area based at {@code zip}. */
    private Setup placed(String zip, int radius, String token, String serviceName, String category, long price,
                         List<String> modes) {
        Setup s = provider(false);
        TestSupport.as(TestSupport.owner(s.org()));
        ok(send("POST", "/v1/providers/" + s.org() + "/services", Map.of("name", serviceName + " " + token,
                "category", category, "durationMinutes", 60, "priceMinor", price, "depositMinor", 1_000,
                "modes", modes)), 201);
        ok(send("POST", "/v1/providers/" + s.org() + "/services/" + s.serviceId() + "/deactivate", null, null));
        ok(send("PUT", "/v1/providers/" + s.org() + "/service-area", Map.of("baseZip", zip, "radiusMiles", radius)));
        TestSupport.reset();
        return s;
    }
}
