package com.afrotransact.taxreturns.adapter.out.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.ProviderConnection;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.TaxProviderException;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Avalara AvaTax adapter against a fake AvaTax (JDK HttpServer): request shape, Basic auth, response parsing, and a
 * clear reason for every way it cannot answer. Not run against a real Avalara sandbox.
 */
class AvalaraTaxCalculatorTest {

    private static final TaxAddress SHIP_TO = new TaxAddress("US", "TX", "77002", "Houston");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InHouseTaxCalculator rules = mock(InHouseTaxCalculator.class);
    private final TaxTelemetry telemetry = mock(TaxTelemetry.class);
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> clientHeader = new AtomicReference<>();
    private final List<AutoCloseable> open = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        for (AutoCloseable c : open) {
            c.close();
        }
    }

    /** A fake AvaTax answering every create call with {@code status} and {@code response} after {@code delayMs}. */
    private String fakeAvaTax(int status, String response, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            clientHeader.set(ex.getRequestHeaders().getFirst("X-Avalara-Client"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private AvalaraTaxCalculator calculator(boolean configured) {
        if (!configured) {
            return new AvalaraTaxCalculator(new TaxReturnsProperties.AvalaraTax(null, null, null, null, null, null, null),
                    null, rules, telemetry, CLOCK);
        }
        return against("http://127.0.0.1:9", Duration.ofSeconds(1));
    }

    private AvalaraTaxCalculator against(String baseUrl, Duration timeout) {
        TaxReturnsProperties.AvalaraTax cfg = new TaxReturnsProperties.AvalaraTax("123", "secret-license",
                "AFROTRANSACT", "sandbox", baseUrl, timeout, null);
        SdkAvaTaxGateway gateway = new SdkAvaTaxGateway(cfg);
        open.add(gateway);
        return new AvalaraTaxCalculator(cfg, gateway, rules, telemetry, CLOCK);
    }

    private static final TaxAddress ORIGIN = new TaxAddress("US", "TX", "75201", "Dallas", "100 Commerce St", null);

    private static TaxLineInput line(String id, String code) {
        return line(id, code, ORIGIN);
    }

    private static TaxLineInput line(String id, String code, TaxAddress origin) {
        return new TaxLineInput(id, "AT" + id, "org", TaxCategory.GENERAL, 2, 1_000, 200, 0, "AT.X.Y", null,
                code == null ? Map.of() : Map.of("AVALARA", code), false, origin);
    }

    private static TaxLineInput lineWithShipping(String id, String code, long shipping) {
        return new TaxLineInput(id, "AT" + id, "org", TaxCategory.GENERAL, 1, 1_000, 0, shipping, "AT.X.Y", null,
                Map.of("AVALARA", code), false, ORIGIN);
    }

    @Test
    void configurationNeedsAccountLicenseKeyAndCompanyCode() {
        assertThat(calculator(false).configured()).isFalse();
        assertThat(calculator(true).configured()).isTrue();
        assertThat(calculator(true).provider()).isEqualTo(TaxProviderId.AVALARA);
        assertThat(new TaxReturnsProperties.AvalaraTax("1", "k", " ", null, null, null, null).configured()).isFalse();
        assertThat(new TaxReturnsProperties.AvalaraTax(null, null, null, null, null, null, null).baseUrl())
                .isEqualTo("https://sandbox-rest.avatax.com");
        assertThat(new TaxReturnsProperties.AvalaraTax(null, null, null, null, null, null, null).appName())
                .isEqualTo("AfroTransact");
    }

    @Test
    void theEnvironmentPicksTheHostAndOnlySandboxOrProductionAreAccepted() {
        assertThat(new TaxReturnsProperties.AvalaraTax("1", "k", "C", "production", null, null, null).baseUrl())
                .isEqualTo("https://rest.avatax.com");
        assertThat(new TaxReturnsProperties.AvalaraTax("1", "k", "C", "Sandbox", null, null, null).baseUrl())
                .isEqualTo("https://sandbox-rest.avatax.com");
        assertThat(new TaxReturnsProperties.AvalaraTax("1", "k", "C", "", null, null, null).environment())
                .isEqualTo("sandbox");
        assertThatThrownBy(() -> new TaxReturnsProperties.AvalaraTax("1", "k", "C", "staging", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AVALARA_ENVIRONMENT");
    }

    @Test
    void notConfiguredSaysWhichVariablesToSetAndMakesNoCall() {
        assertThatThrownBy(() -> calculator(false).quoteStrict(SHIP_TO, "USD", List.of(line("1", "PF050000"))))
                .isInstanceOf(TaxProviderException.class).hasMessageContaining("Avalara is not configured")
                .hasMessageContaining("AVALARA_ACCOUNT_ID").hasMessageContaining("AVALARA_LICENSE_KEY")
                .hasMessageContaining("AVALARA_COMPANY_CODE");
    }

    @Test
    void aLineWithoutACodeIsRefusedAndNothingIsSent() throws IOException {
        String url = fakeAvaTax(200, "{}", 0);

        assertThatThrownBy(() -> against(url, null).quoteStrict(SHIP_TO, "USD",
                List.of(line("1", "PF050000"), line("2", null)))).isInstanceOf(TaxProviderException.class)
                .hasMessageContaining("No Avalara code for lines [2]");
        assertThat(path.get()).as("not even the coded line is sent when another has no code").isNull();
    }

    @Test
    void aLineWithoutAShipFromAddressIsRefusedAndNothingIsSent() throws IOException {
        String url = fakeAvaTax(200, "{}", 0);

        assertThatThrownBy(() -> against(url, null).quoteStrict(SHIP_TO, "USD",
                List.of(line("1", "PF050000"), line("2", "PF050000", null)))).isInstanceOf(TaxProviderException.class)
                .hasMessageContaining("no ship-from address was given for lines [2]");
        assertThat(path.get()).as("no call is made without an origin").isNull();
        assertThatThrownBy(() -> against(url, null).quoteStrict(SHIP_TO, "USD",
                List.of(line("3", "PF050000", new TaxAddress("US", "", "", "Dallas")))))
                .isInstanceOf(TaxProviderException.class).hasMessageContaining("no ship-from address");
    }

    @Test
    void aStrictQuoteCreatesAnUncommittedSalesOrderWithBothAddressesAndReadsTaxAndRatePerLine() throws IOException {
        String url = fakeAvaTax(200, """
                {"id":0,"code":"x","status":"Temporary","totalTax":2.50,"lines":[
                 {"lineNumber":"1","tax":1.49,"details":[{"rate":0.0625,"tax":1.12},{"rate":0.02,"tax":0.37}]},
                 {"lineNumber":"2","tax":0.00,"details":[{"rate":0.0,"tax":0.0}]},
                 {"lineNumber":"3","tax":1.01,"details":[{"rate":0.0825,"tax":0.82}]},
                 {"lineNumber":"4","tax":0.19,"details":[{"rate":0.0825,"tax":0.19}]}]}
                """, 0);
        TaxAddress street = new TaxAddress("US", "TX", "77002", "Houston", "1001 Main St", null);

        TaxQuote q = against(url, null).quoteStrict(street, "USD", List.of(line("a", "PF050000"),
                line("b", "NT"), lineWithShipping("c", "PC040100", 250)));

        assertThat(path.get()).isEqualTo("POST /api/v2/transactions/create");
        assertThat(auth.get()).isEqualTo("Basic MTIzOnNlY3JldC1saWNlbnNl");
        assertThat(clientHeader.get()).contains("AfroTransact");
        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.path("type").asString()).isEqualTo("SalesOrder");
        assertThat(sent.path("commit").asBoolean(true)).isFalse();
        assertThat(sent.path("companyCode").asString()).isEqualTo("AFROTRANSACT");
        assertThat(sent.path("currencyCode").asString()).isEqualTo("USD");
        assertThat(sent.path("date").asString()).isEqualTo("2026-11-09T00:00:00");
        assertThat(sent.path("customerCode").asString()).isNotBlank();
        JsonNode to = sent.path("addresses").path("shipTo");
        assertThat(to.path("line1").asString()).isEqualTo("1001 Main St");
        assertThat(to.path("city").asString()).isEqualTo("Houston");
        assertThat(to.path("region").asString()).isEqualTo("TX");
        assertThat(to.path("country").asString()).isEqualTo("US");
        assertThat(to.path("postalCode").asString()).isEqualTo("77002");
        JsonNode from = sent.path("addresses").path("shipFrom");
        assertThat(from.path("line1").asString()).isEqualTo("100 Commerce St");
        assertThat(from.path("city").asString()).isEqualTo("Dallas");
        assertThat(from.path("region").asString()).isEqualTo("TX");
        assertThat(from.path("postalCode").asString()).isEqualTo("75201");
        assertThat(sent.path("lines")).hasSize(4);
        JsonNode first = sent.path("lines").get(0);
        assertThat(first.path("number").asString()).isEqualTo("1");
        assertThat(first.path("taxCode").asString()).isEqualTo("PF050000");
        assertThat(first.path("itemCode").asString()).isEqualTo("ATa");
        assertThat(first.path("description").asString()).isNotBlank();
        assertThat(first.path("quantity").asDouble()).isEqualTo(2.0);
        assertThat(first.path("amount").asDouble()).isEqualTo(18.0);
        assertThat(first.path("taxIncluded").asBoolean(true)).isFalse();
        assertThat(sent.path("lines").get(3).path("taxCode").asString()).isEqualTo("FR020100");
        assertThat(sent.path("lines").get(3).path("amount").asDouble()).isEqualTo(2.5);

        assertThat(q.provider()).isEqualTo("AVALARA");
        assertThat(q.lines()).extracting(l -> l.lineId(), l -> l.taxMinor()).containsExactly(
                tuple("a", 149L), tuple("b", 0L),
                tuple("c", 120L));
        assertThat(q.lines().get(0).rate()).isEqualByComparingTo("0.0825");
        assertThat(q.lines().get(1).collectedBy()).isEqualTo(CollectedBy.NOT_COLLECTED);
        assertThat(q.lines().get(2).shippingTaxMinor()).isEqualTo(19L);
    }

    @Test
    void whenLinesShipFromDifferentPlacesEachLineCarriesBothEnds() throws IOException {
        String url = fakeAvaTax(200, """
                {"lines":[{"lineNumber":"1","tax":1.00,"details":[]},{"lineNumber":"2","tax":2.00,"details":[]}]}
                """, 0);
        TaxAddress houston = new TaxAddress("US", "TX", "77001", "Houston", "5 Dock Rd", null);

        against(url, null).quoteStrict(SHIP_TO, "USD", List.of(line("a", "PF050000"), line("b", "PF050000", houston)));

        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.path("addresses").path("shipFrom").isMissingNode()).isTrue();
        assertThat(sent.path("addresses").path("shipTo").path("postalCode").asString()).isEqualTo("77002");
        assertThat(sent.path("lines").get(0).path("addresses").path("shipFrom").path("postalCode").asString())
                .isEqualTo("75201");
        assertThat(sent.path("lines").get(1).path("addresses").path("shipFrom").path("line1").asString())
                .isEqualTo("5 Dock Rd");
        assertThat(sent.path("lines").get(1).path("addresses").path("shipTo").path("postalCode").asString())
                .isEqualTo("77002");
    }

    @Test
    void aShipToKnownOnlyByZipSendsNoStreetAndNoMadeUpCity() throws IOException {
        String url = fakeAvaTax(200, "{\"lines\":[{\"lineNumber\":\"1\",\"tax\":1.00,\"details\":[]}]}", 0);

        against(url, null).quoteStrict(new TaxAddress("US", "TX", "77002", null), "USD", List.of(line("a", "PF050000")));

        JsonNode to = JSON.readTree(body.get()).path("addresses").path("shipTo");
        assertThat(to.has("line1")).isFalse();
        assertThat(to.has("city")).isFalse();
        assertThat(to.path("postalCode").asString()).isEqualTo("77002");
        assertThat(new TaxAddress("US", "TX", "77002", null).precision()).isEqualTo("ZIP");
        assertThat(new TaxAddress("US", "TX", "77002", "Houston").withCityPrecision().precision()).isEqualTo("CITY");
        assertThat(new TaxAddress("US", "TX", "77002", "Houston", "1 Main St", "CITY").precision()).isEqualTo("ADDRESS");
    }

    @Test
    void aRefusalNamesAvalarasMessageButNeverTheLicenseKey() throws IOException {
        String url = fakeAvaTax(401, "{\"error\":{\"code\":\"AuthenticationException\",\"message\":\"Invalid credentials\"}}",
                0);

        assertThatThrownBy(() -> against(url, null).quoteStrict(SHIP_TO, "USD", List.of(line("1", "PF050000"))))
                .isInstanceOf(TaxProviderException.class).hasMessageContaining("AuthenticationException")
                .hasMessageContaining("Invalid credentials").satisfies(ex -> assertThat(ex.getMessage())
                        .doesNotContain("secret-license").doesNotContain("123"));
    }

    @Test
    void aServerErrorAndASlowServerAreProviderErrorsNotZeroQuotes() throws IOException {
        String broken = fakeAvaTax(500, "oops", 0);
        assertThatThrownBy(() -> against(broken, null).quoteStrict(SHIP_TO, "USD", List.of(line("1", "PF050000"))))
                .isInstanceOf(TaxProviderException.class).hasMessageContaining("not a readable AvaTax response");
        server.stop(0);

        String slow = fakeAvaTax(200, "{\"lines\":[]}", 1_500);
        assertThatThrownBy(() -> against(slow, Duration.ofMillis(200)).quoteStrict(SHIP_TO, "USD",
                List.of(line("1", "PF050000")))).isInstanceOf(TaxProviderException.class)
                .hasMessageContaining("did not answer in time");
    }

    @Test
    void aResponseThatMissesALineIsAnErrorNotAZero() throws IOException {
        String url = fakeAvaTax(200, "{\"lines\":[{\"lineNumber\":\"1\",\"tax\":1.00,\"details\":[]}]}", 0);

        assertThatThrownBy(() -> against(url, null).quoteStrict(SHIP_TO, "USD",
                List.of(line("1", "PF050000"), line("2", "PF050000")))).isInstanceOf(TaxProviderException.class)
                .hasMessageContaining("no result for line 2");
    }

    @Test
    void aChargingQuoteFallsBackVisiblyToTheRulesEngine() {
        List<TaxLineInput> lines = List.of(line("1", "PF050000"), line("2", null));
        when(rules.quote(any(), any(), any())).thenReturn(new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE, List.of(),
                "rules"));

        TaxQuote q = calculator(false).quote(SHIP_TO, "USD", lines);

        assertThat(q.provider()).isEqualTo("RULES-FALLBACK");
        assertThat(q.fallbackLines()).containsExactly("1", "2");
        verify(telemetry).providerFallback("AVALARA", "not-configured", 2);
        TaxQuote uncoded = calculator(true).quote(SHIP_TO, "USD", lines);
        assertThat(uncoded.fallbackLines()).containsExactly("2");
        verify(telemetry).providerFallback("AVALARA", "no-code", 1);
        TaxQuote noOrigin = calculator(true).quote(SHIP_TO, "USD", List.of(line("3", "PF050000", null)));
        assertThat(noOrigin.fallbackLines()).containsExactly("3");
        verify(telemetry).providerFallback("AVALARA", "no-ship-from", 1);
    }

    @Test
    void testConnectionReportsAuthenticatedAndTheAccountNameNeverTheKey() throws IOException {
        String url = fakeAvaTax(200, "{\"version\":\"26.9.0\",\"authenticated\":true,"
                + "\"authenticationType\":\"UsernamePassword\",\"authenticatedUserName\":\"afro-sandbox\","
                + "\"authenticatedUserId\":5,\"authenticatedAccountId\":123}", 0);

        ProviderConnection c = against(url, null).testConnection();

        assertThat(path.get()).isEqualTo("GET /api/v2/utilities/ping");
        assertThat(c.configured()).isTrue();
        assertThat(c.authenticated()).isTrue();
        assertThat(c.accountName()).isEqualTo("afro-sandbox");
        assertThat(c.environment()).isEqualTo("sandbox");
        assertThat(c.toString()).doesNotContain("secret-license");
    }

    @Test
    void testConnectionSaysWhenAvalaraDoesNotAcceptTheCredentials() throws IOException {
        String unauthenticated = fakeAvaTax(200, "{\"version\":\"26.9.0\",\"authenticated\":false}", 0);
        ProviderConnection c = against(unauthenticated, null).testConnection();
        assertThat(c.authenticated()).isFalse();
        assertThat(c.message()).contains("AVALARA_LICENSE_KEY");
        server.stop(0);

        String refused = fakeAvaTax(401, "{\"error\":{\"code\":\"AuthenticationException\",\"message\":\"Bad\"}}", 0);
        ProviderConnection r = against(refused, null).testConnection();
        assertThat(r.configured()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.message()).contains("AuthenticationException").doesNotContain("secret-license");
    }

    @Test
    void testConnectionWithoutCredentialsMakesNoCall() {
        ProviderConnection c = calculator(false).testConnection();

        assertThat(c.configured()).isFalse();
        assertThat(c.authenticated()).isFalse();
        assertThat(c.message()).contains("AVALARA_ACCOUNT_ID");
    }
}
