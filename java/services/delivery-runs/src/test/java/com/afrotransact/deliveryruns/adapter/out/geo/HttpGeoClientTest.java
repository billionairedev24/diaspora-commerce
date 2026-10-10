package com.afrotransact.deliveryruns.adapter.out.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.RouteEstimate;
import com.afrotransact.deliveryruns.port.GeoUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** geo-service contract as delivery-runs uses it (paths, query names, bodies, error mapping). */
class HttpGeoClientTest {

    private WireMockServer server;
    private HttpGeoClient client;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        String base = "http://localhost:" + server.port();
        client = new HttpGeoClient(base, Duration.ofSeconds(2), "SERVICE_AREA", base + "/oauth2/token",
                "delivery-runs-service", "s3cret", Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void zipCentreFromGeocode() {
        server.stubFor(WireMock.post("/v1/geocode")
                .withRequestBody(WireMock.matchingJsonPath("$.postalCode", WireMock.equalTo("78602")))
                .willReturn(WireMock.okJson("{\"lat\":30.11,\"lng\":-97.31,\"precision\":\"ZIP\"}")));
        server.stubFor(WireMock.post("/v1/geocode")
                .withRequestBody(WireMock.matchingJsonPath("$.postalCode", WireMock.equalTo("00000")))
                .willReturn(WireMock.aResponse().withStatus(404)));
        assertThat(client.locateZip("78602")).contains(new GeoPoint(30.11, -97.31));
        assertThat(client.locateZip("00000")).isEmpty();
    }

    @Test
    void zoneCoverageUsesLatLonAndType() {
        server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/zones/covering"))
                .withQueryParam("lat", WireMock.equalTo("30.35"))
                .withQueryParam("lon", WireMock.equalTo("-97.37"))
                .withQueryParam("type", WireMock.equalTo("SERVICE_AREA"))
                .willReturn(WireMock.okJson("{\"zones\":[{\"id\":\"z1\",\"active\":true},"
                        + "{\"id\":\"z2\",\"active\":false}]}")));
        assertThat(client.zonesCovering(new GeoPoint(30.35, -97.37), "SERVICE_AREA")).containsExactly("z1");
    }

    @Test
    void straightLineMilesKeepDestinationOrderAndGaps() {
        server.stubFor(WireMock.post("/v1/distance/batch").willReturn(WireMock.okJson("""
                {"results":[{"index":1,"straightLineMiles":4.5},
                            {"index":0,"error":{"code":"geo.place-not-found"}},
                            {"index":2,"straightLineMiles":30.2}]}""")));
        List<Double> miles = client.straightLineMiles(new GeoPoint(30, -97),
                List.of(new GeoPoint(30.1, -97.3), new GeoPoint(30.2, -97.2), new GeoPoint(30.3, -97.1)));
        assertThat(miles).containsExactly(null, 4.5, 30.2);
    }

    @Test
    void routeFromDistance() {
        server.stubFor(WireMock.post("/v1/distance").willReturn(WireMock.okJson(
                "{\"straightLineMiles\":27.1,\"roadMiles\":32.4,\"durationMinutes\":41,\"method\":\"ROAD_OSRM\"}")));
        assertThat(client.route(new GeoPoint(30.27, -97.74), new GeoPoint(30.11, -97.31)))
                .isEqualTo(new RouteEstimate(32.4, 41, "ROAD_OSRM"));
    }

    @Test
    void polygonRegistrationUsesAClientCredentialsToken() {
        server.stubFor(WireMock.post("/oauth2/token")
                .withBasicAuth("delivery-runs-service", "s3cret")
                .withRequestBody(WireMock.containing("scope=geo%3Azones%3Awrite"))
                .willReturn(WireMock.okJson("{\"access_token\":\"tok\",\"expires_in\":600}")));
        server.stubFor(WireMock.post("/v1/zones")
                .withHeader("Authorization", WireMock.equalTo("Bearer tok"))
                .withRequestBody(WireMock.matchingJsonPath("$.type", WireMock.equalTo("SERVICE_AREA")))
                .withRequestBody(WireMock.matchingJsonPath("$.ownerId", WireMock.equalTo("delivery-runs:elgin-tx")))
                .willReturn(WireMock.aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"8c1f\"}")));
        assertThat(client.registerPolygon("Elgin", "delivery-runs:elgin-tx", "{}")).isEqualTo("8c1f");
        assertThat(client.registerPolygon("Elgin", "delivery-runs:elgin-tx", "{}")).isEqualTo("8c1f");
        server.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/oauth2/token")));
    }

    @Test
    void serverErrorsMeanUnavailable() {
        server.stubFor(WireMock.post("/v1/distance").willReturn(WireMock.aResponse().withStatus(502)));
        assertThatThrownBy(() -> client.route(new GeoPoint(30, -97), new GeoPoint(30.1, -97.1)))
                .isInstanceOf(GeoUnavailableException.class);
    }
}
