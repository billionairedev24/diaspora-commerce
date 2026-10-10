package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.inventory.application.ReceiveCommand;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S206: unit dimensions, the daily storage snapshot and cubic-feet-days per owner for metered billing. */
class StorageUsageIT extends InventoryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Actor SELLER_SERVICE = new Actor("seller-service", PrincipalType.SERVICE, Optional.empty(),
            Set.of(), Set.of("inventory:storage.read"));

    private int port;

    @BeforeEach
    void wirePort(@LocalServerPort int serverPort) {
        this.port = serverPort;
    }

    @Test
    void snapshotBillsMeasuredAndDefaultVolumesPerOwnerAndDay() throws Exception {
        String org = "org_fba_" + UUID.randomUUID().toString().substring(0, 8);
        String measured = sku("CUBE");
        String unmeasured = sku("LOOSE");
        // 10 measured units of 12 × 12 × 6 in (0.5 ft³) + 4 unmeasured units (default 0.25 ft³) at HOU-FC.
        receiving.receive(new ReceiveCommand("GRN-" + UUID.randomUUID(), NODE, org, List.of(
                line(measured, "L-M1", 500, 10, 0, 100), line(unmeasured, "L-U1", 500, 4, 0, 100))),
                UUID.randomUUID().toString());

        HttpResponse<String> bad = send("PUT", "/v1/skus/" + measured + "/dimensions",
                "{\"lengthIn\":0,\"widthIn\":12,\"heightIn\":6.123}");
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(bad.body()).path("code").asString()).isEqualTo("inventory.invalid-dimensions");
        HttpResponse<String> set = send("PUT", "/v1/skus/" + measured.toLowerCase() + "/dimensions",
                "{\"lengthIn\":12,\"widthIn\":12,\"heightIn\":6}");
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        assertThat(JSON.readTree(set.body()).path("unitCubicFeet").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(send("GET", "/v1/skus/" + unmeasured + "/dimensions", null).statusCode()).isEqualTo(404);

        HttpResponse<String> snap = send("POST", "/v1/storage/snapshots", "");
        assertThat(snap.statusCode()).as(snap.body()).isEqualTo(200);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(JSON.readTree(snap.body()).path("day").asString()).isEqualTo(today.toString());
        // Re-running replaces today's snapshot instead of adding to it.
        assertThat(send("POST", "/v1/storage/snapshots", "").statusCode()).isEqualTo(200);

        TestActors.as(SELLER_SERVICE);
        JsonNode day = only(usage("?from=" + today.minusDays(2) + "&to=" + today + "&ownerOrg=" + org));
        assertThat(day.path("day").asString()).isEqualTo(today.toString());
        assertThat(day.path("units").asLong()).isEqualTo(14);
        assertThat(day.path("unmeasuredUnits").asLong()).isEqualTo(4);
        assertThat(day.path("cubicFeet").decimalValue()).isEqualByComparingTo("6.0");

        // Sellers only see their own organisation, whatever they ask for.
        TestActors.as(TestActors.seller(org, "inventory:read"));
        assertThat(usage("?from=" + today + "&to=" + today + "&ownerOrg=org_someone_else").path("items"))
                .allSatisfy(i -> assertThat(i.path("ownerOrg").asString()).isEqualTo(org));
        TestActors.as(TestActors.seller(org));
        assertThat(send("GET", "/v1/storage/usage?from=" + today + "&to=" + today, null).statusCode())
                .isEqualTo(403);
        TestActors.as(SELLER_SERVICE);
        assertThat(send("POST", "/v1/storage/snapshots", "").statusCode()).isEqualTo(403);
        assertThat(send("GET", "/v1/storage/usage?from=" + today + "&to=" + today.minusDays(1), null).statusCode())
                .isEqualTo(422);
    }

    private JsonNode usage(String query) throws Exception {
        HttpResponse<String> r = send("GET", "/v1/storage/usage" + query, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static JsonNode only(JsonNode body) {
        assertThat(body.path("items").size()).isEqualTo(1);
        return body.path("items").get(0);
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (json == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
