package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.afrotransact.inventory.application.ReceiptResult;
import com.afrotransact.inventory.application.ReceiveCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The exchange-rate and currency endpoints and the cost fields of receipts and lots, over HTTP. */
class ExchangeRateApiIT extends InventoryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private int port;

    @BeforeEach
    void wire(@LocalServerPort int serverPort) {
        this.port = serverPort;
    }

    @Test
    void staffSetListAndAuditRatesAndSeeThemSuggestedAtReceiving() throws Exception {
        LocalDate day = today().minusDays(300 + new Random().nextInt(5000));

        HttpResponse<String> created = put("/v1/exchange-rates/cad/" + day,
                "{\"rateToUsd\":0.7312,\"note\":\"Bank of Canada\",\"reason\":\"Monthly rate\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        JsonNode rate = JSON.readTree(created.body());
        assertThat(rate.path("currency").asString()).isEqualTo("CAD");
        assertThat(rate.path("rateDate").asString()).isEqualTo(day.toString());
        assertThat(rate.path("rateToUsd").decimalValue()).isEqualByComparingTo("0.7312");
        assertThat(rate.path("updatedBy").asString()).isEqualTo("local-dev");

        put("/v1/exchange-rates/CAD/" + day, "{\"rateToUsd\":0.7400,\"reason\":\"Bank restated it\"}");

        JsonNode list = json(get("/v1/exchange-rates?currency=CAD&size=200"));
        List<String> days = new ArrayList<>();
        list.path("items").forEach(r -> days.add(r.path("rateDate").asString()));
        assertThat(days).contains(day.toString());
        assertThat(list.path("total").asLong()).isPositive();

        JsonNode current = json(get("/v1/exchange-rates/current?asOf=" + day.plusDays(3)));
        boolean found = false;
        for (JsonNode r : current) {
            if ("CAD".equals(r.path("currency").asString()) && day.toString().equals(r.path("rateDate").asString())) {
                found = true;
                assertThat(r.path("rateToUsd").decimalValue()).isEqualByComparingTo("0.74");
            }
        }
        assertThat(found).as("the day's rate is the suggestion for the days after it").isTrue();

        JsonNode history = json(get("/v1/exchange-rates/history?currency=CAD&size=200"));
        List<String> reasons = new ArrayList<>();
        history.path("items").forEach(c -> {
            if (day.toString().equals(c.path("rateDate").asString())) {
                reasons.add(c.path("action").asString() + ":" + c.path("reason").asString());
            }
        });
        assertThat(reasons).containsExactly("UPDATE:Bank restated it", "CREATE:Monthly rate");

        JsonNode currencies = json(get("/v1/currencies"));
        assertThat(currencies.get(0).path("code").asString()).isEqualTo("USD");
        assertThat(currencies.get(0).path("base").asBoolean()).isTrue();
        assertThat(currencies.get(0).path("minorUnits").asInt()).isEqualTo(2);
    }

    @Test
    void invalidRatesAreRefusedWithProblemDetails() throws Exception {
        LocalDate day = today().minusDays(2);
        assertThat(put("/v1/exchange-rates/MXN/" + day, "{\"rateToUsd\":0.0577}").statusCode())
                .as("a reason is required").isEqualTo(400);
        assertThat(put("/v1/exchange-rates/MXN/" + day, "{\"rateToUsd\":0,\"reason\":\"why not\"}").statusCode())
                .isEqualTo(400);
        HttpResponse<String> unknown = put("/v1/exchange-rates/JPY/" + day,
                "{\"rateToUsd\":0.0067,\"reason\":\"not on the list\"}");
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(unknown.body()).path("code").asString()).isEqualTo("inventory.invalid-currency");
        HttpResponse<String> usd = put("/v1/exchange-rates/USD/" + day, "{\"rateToUsd\":1,\"reason\":\"base\"}");
        assertThat(usd.statusCode()).isEqualTo(422);
        HttpResponse<String> future = put("/v1/exchange-rates/MXN/" + today().plusDays(10),
                "{\"rateToUsd\":0.0577,\"reason\":\"too early\"}");
        assertThat(future.statusCode()).isEqualTo(422);
    }

    @Test
    void sellersNeverSeeRatesAndWritingNeedsTheAdjustPermission() throws Exception {
        TestActors.as(TestActors.staff("inventory:read"));
        assertThat(get("/v1/exchange-rates").statusCode()).isEqualTo(200);
        assertThat(put("/v1/exchange-rates/MXN/" + today().minusDays(2),
                "{\"rateToUsd\":0.0577,\"reason\":\"staff without adjust\"}").statusCode()).isEqualTo(403);
        TestActors.as(TestActors.seller("org_some_seller", "inventory:read"));
        assertThat(get("/v1/exchange-rates").statusCode()).isEqualTo(403);
        assertThat(get("/v1/exchange-rates/current").statusCode()).isEqualTo(403);
        assertThat(get("/v1/exchange-rates/history").statusCode()).isEqualTo(403);
        assertThat(get("/v1/currencies").statusCode()).as("the list of currencies is not secret").isEqualTo(200);
        TestActors.as(TestActors.seller("org_some_seller"));
        assertThat(get("/v1/currencies").statusCode()).isEqualTo(403);
    }

    @Test
    void receiptsTakeTheCurrencyTheRateAndTheBreakdownAndLotsReturnThem() throws Exception {
        String sku = sku("API-MX");
        String body = """
                {"receiptRef":"GRN-%s","nodeId":"%s","lines":[
                  {"sku":"%s","lotNo":"L-API-1","expDate":"%s","qtyGood":12,"qtyDamaged":0,"bin":"A-01-01",
                   "unitLandedCostMinor":12000,"currency":"MXN","fxRateToUsd":0.0577,"fxRateDate":"%s",
                   "costComponents":{"goodsMinor":10000,"freightMinor":1500,"dutyMinor":400,"otherMinor":100}}]}
                """.formatted(UUID.randomUUID(), NODE, sku, today().plusDays(400), today().minusDays(1));
        HttpResponse<String> received = post("/v1/receipts", body);
        assertThat(received.statusCode()).as(received.body()).isEqualTo(201);
        JsonNode lot = JSON.readTree(received.body()).path("lots").get(0);
        assertThat(lot.path("currency").asString()).isEqualTo("MXN");
        assertThat(lot.path("unitLandedCostMinor").asLong()).isEqualTo(12_000);
        assertThat(lot.path("fxRateToUsd").decimalValue()).isEqualByComparingTo("0.0577");
        assertThat(lot.path("fxRateDate").asString()).isEqualTo(today().minusDays(1).toString());
        assertThat(lot.path("fxRateSource").asString()).isEqualTo("ENTERED");
        assertThat(lot.path("unitCostUsdMinor").asLong()).isEqualTo(692);
        assertThat(lot.path("costComponents").path("freightMinor").asLong()).isEqualTo(1_500);

        JsonNode fetched = json(get("/v1/lots/" + lot.path("id").asString()));
        assertThat(fetched.path("unitCostUsdMinor").asLong()).isEqualTo(692);
        assertThat(fetched.path("currency").asString()).isEqualTo("MXN");

        HttpResponse<String> rejected = post("/v1/receipts", body.replace("\"fxRateToUsd\":0.0577,", "")
                .replace("GRN-", "GRN-X").replace("\"MXN\"", "\"ZAR\""));
        assertThat(rejected.statusCode()).isEqualTo(422);
        JsonNode problem = JSON.readTree(rejected.body());
        assertThat(problem.path("code").asString()).isEqualTo("inventory.invalid-cost");
        assertThat(problem.path("fieldErrors").has("lines[0].fxRateToUsd")).isTrue();
    }

    @Test
    void aStoredAnswerFromBeforeLotsCarriedACostStillReplays() {
        String sku = sku("API-OLD");
        ReceiveCommand cmd = new ReceiveCommand("GRN-" + UUID.randomUUID(), NODE, null,
                List.of(line(sku, "L-OLD-1", 400, 5, 0, 250)));
        String key = UUID.randomUUID().toString();
        ReceiptResult first = receiving.receive(cmd, key);
        // Rewrite the stored answer into the shape written before lots had a cost.
        jdbc.sql("""
                update idempotency_record
                set response = jsonb_set(response, '{lots}',
                        (select jsonb_agg(l - 'cost') from jsonb_array_elements(response -> 'lots') l))
                where scope = 'receipt' and idem_key = :key
                """).param("key", key).update();

        ReceiptResult replay = receiving.receive(cmd, key);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.lots()).extracting(l -> l.id()).containsExactly(first.lots().getFirst().id());
        assertThat(replay.lots().getFirst().cost()).isNotNull();
        assertThat(replay.lots().getFirst().unitCostUsdMinor()).isEqualTo(250);
    }

    private JsonNode json(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
