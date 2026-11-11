package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.catalog.application.ProductImportService;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.testsupport.PermissiveRulesConfiguration;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seller-written size charts over HTTP against PostgreSQL: the sizing trait staff configure, the seller's chart
 * editor API, linking a chart to a product with custom sizes, the public product page, and the size chart upload.
 * {@code AT_IT_POSTGRES_URL=jdbc:postgresql://…} runs the tests on that PostgreSQL instead of a container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "at.consumers.enabled=false", "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Import({SizeChartIT.Fakes.class, PermissiveRulesConfiguration.class})
class SizeChartIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = EXTERNAL_DB == null ? PostgresTestContainer.create() : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
    }

    private static final Actor ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));
    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(ADMIN);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean
        ActorResolver switchableActorResolver() {
            return CURRENT::get;
        }
    }

    private final JdbcClient jdbc;
    private final ProductImportService imports;
    private final int port;
    private String org;
    private UUID dresses;

    @Autowired
    SizeChartIT(JdbcClient jdbc, ProductImportService imports, @LocalServerPort int port) {
        this.jdbc = jdbc;
        this.imports = imports;
        this.port = port;
    }

    @BeforeEach
    void seller() {
        org = "org_sz_" + UUID.randomUUID().toString().substring(0, 8);
        asSeller(org);
        dresses = jdbc.sql("select id from category where path = :path").param("path", "Fashion & Accessories › Adult clothing & footwear › Women's dresses").query(UUID.class).single();
    }

    @AfterEach
    void reset() {
        CURRENT.set(ADMIN);
    }

    private static void asSeller(String orgId) {
        CURRENT.set(new Actor("member-" + orgId, PrincipalType.SELLER_MEMBER, Optional.of(orgId), Set.of("seller.owner"),
                Set.of("catalog:write")));
    }

    private static Map<String, Object> value(String min, String max) {
        return max == null ? Map.of("min", new BigDecimal(min)) : Map.of("min", new BigDecimal(min),
                "max", new BigDecimal(max));
    }

    private Map<String, Object> chartBody(String name, String... sizes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String size : sizes) {
            rows.add(Map.of("size", size, "values", Map.of("bust", value("84", "88"), "waist", value("66", null),
                    "hip", value("92", "96"))));
        }
        return Map.of("name", name, "unitSystem", "CM", "categoryId", dresses.toString(),
                "columns", List.of(Map.of("label", "Bust"), Map.of("label", "Waist"), Map.of("label", "Hip")),
                "rows", rows, "fitNote", "TRUE_TO_SIZE", "howToMeasure", "Measure over light clothing.");
    }

    // ------------------------------------------------------------------ the sizing trait staff configure

    @Test
    void theSeededFashionTypesAreSizedAndLetSellersWriteTheirOwnSizesAndColours() throws Exception {
        CURRENT.set(ADMIN);
        Reply dress = call("GET", "/v1/taxonomy/product-types/dress", null, null);

        assertThat(dress.status()).isEqualTo(200);
        JsonNode sizing = dress.body().get("traits").get("sizing");
        assertThat(sizing.get("sizeChartRequired").asBoolean()).isFalse();
        assertThat(sizing.get("sizeDimension").asString()).isEqualTo("Size");
        assertThat(sizing.get("suggestedMeasurements").findValuesAsString("key")).containsExactly("bust", "waist", "hip");
        JsonNode dims = dress.body().get("themes").get(0).get("dimensions");
        assertThat(dims.get(0).get("allowCustomValues").asBoolean()).isTrue();
        assertThat(dims.get(1).get("allowCustomValues").asBoolean()).isTrue();
        assertThat(call("GET", "/v1/taxonomy/product-types/rice-grains", null, null).body().get("traits").get("sizing")
                .isNull()).isTrue();
    }

    @Test
    void staffTurnTheChartRequirementOnAndOffWithoutTouchingTheRest() throws Exception {
        String type = "sz_" + UUID.randomUUID().toString().substring(0, 6);
        CURRENT.set(ADMIN);
        Reply created = call("POST", "/v1/taxonomy/product-types", Map.of("code", type, "label", "Test shoes",
                "identifierRule", "GTIN", "basedOn", "dress", "reason", "sized test type",
                "traits", Map.of("sizing", Map.of("sizeChartRequired", true, "suggestedMeasurements", List.of(
                        Map.of("label", "Foot length", "unit", "CM", "help", "Heel to longest toe"))))), null);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("traits").get("sizing").get("suggestedMeasurements").get(0).get("key").asString())
                .isEqualTo("foot_length");
        String version = created.body().get("version").asString();

        Reply flags = call("PATCH", "/v1/taxonomy/product-types/" + type, Map.of("reason", "food now",
                "traits", Map.of("food", true)), version);
        assertThat(flags.status()).isEqualTo(200);
        assertThat(flags.body().get("traits").get("food").asBoolean()).isTrue();
        assertThat(flags.body().get("traits").get("sizing").get("sizeChartRequired").asBoolean()).isTrue();

        Reply off = call("PATCH", "/v1/taxonomy/product-types/" + type, Map.of("reason", "no sizes",
                "traits", Map.of("food", true, "sizing", Map.of("enabled", false))), flags.body().get("version").asString());
        assertThat(off.body().get("traits").get("sizing").isNull()).isTrue();
        assertThat(off.body().get("themes").get(0).get("dimensions").get(0).get("allowCustomValues").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ the seller's charts

    @Test
    void aSellerWritesEditsDuplicatesAndRetiresAChart() throws Exception {
        Reply suggestions = call("GET", "/v1/size-charts/suggestions?productTypeId=" + dresses, null, null);
        assertThat(suggestions.status()).isEqualTo(200);
        assertThat(suggestions.body().get("sized").asBoolean()).isTrue();
        assertThat(suggestions.body().get("sizes").get(0).asString()).isEqualTo("XS");
        assertThat(suggestions.body().get("measurements").get(0).get("label").asString()).isEqualTo("Bust");

        Reply created = call("POST", "/v1/size-charts", chartBody("Adire dresses", "S", "M", "UK 12"), null);
        assertThat(created.status()).isEqualTo(201);
        JsonNode chart = created.body();
        String id = chart.get("id").asString();
        assertThat(chart.get("productTypeCode").asString()).isEqualTo("dress");
        assertThat(chart.get("alternateUnit").asString()).isEqualTo("IN");
        assertThat(chart.get("rows").get(0).get("alternateValues").get("bust").get("min").decimalValue().toPlainString())
                .isEqualTo("33.1");
        assertThat(chart.get("columns").get(0).get("key").asString()).isEqualTo("bust");

        Reply invalid = call("POST", "/v1/size-charts", chartBody("Adire dresses", "S", "s"), null);
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.body().get("fieldErrors").propertyNames()).containsExactly("rows[1].size");
        Reply sameName = call("POST", "/v1/size-charts", chartBody("ADIRE DRESSES", "S"), null);
        assertThat(sameName.status()).isEqualTo(422);
        assertThat(sameName.body().get("fieldErrors").propertyNames()).containsExactly("name");

        Map<String, Object> edit = new LinkedHashMap<>(chartBody("Adire dresses 2026", "S", "M", "L"));
        edit.put("version", chart.get("version").asLong());
        Reply updated = call("PUT", "/v1/size-charts/" + id, edit, null);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("version").asLong()).isEqualTo(1);
        assertThat(call("PUT", "/v1/size-charts/" + id, edit, null).status()).isEqualTo(409);

        Reply copy = call("POST", "/v1/size-charts/" + id + "/duplicate", Map.of(), null);
        assertThat(copy.status()).isEqualTo(201);
        assertThat(copy.body().get("name").asString()).isEqualTo("Adire dresses 2026 (copy)");

        assertThat(call("GET", "/v1/size-charts?productType=dress", null, null).body().get("total").asLong()).isEqualTo(2);
        Reply retired = call("DELETE", "/v1/size-charts/" + id, null, null);
        assertThat(retired.status()).isEqualTo(200);
        assertThat(retired.body().get("retired").asBoolean()).isTrue();
        assertThat(call("GET", "/v1/size-charts", null, null).body().get("total").asLong()).isEqualTo(1);
        assertThat(call("GET", "/v1/size-charts?includeRetired=true", null, null).body().get("total").asLong())
                .isEqualTo(2);
        assertThat(call("POST", "/v1/size-charts", chartBody("Adire dresses 2026", "S"), null).status()).isEqualTo(201);
    }

    @Test
    void aChartIsPrivateToItsSellerAndStaffOnlyRead() throws Exception {
        String id = call("POST", "/v1/size-charts", chartBody("Private chart", "S"), null).body().get("id").asString();

        asSeller("org_other_" + UUID.randomUUID().toString().substring(0, 6));
        assertThat(call("GET", "/v1/size-charts/" + id, null, null).status()).isEqualTo(404);
        assertThat(call("PUT", "/v1/size-charts/" + id, chartBody("Private chart", "S", "M"), null).status())
                .isEqualTo(404);
        assertThat(call("GET", "/v1/size-charts", null, null).body().get("total").asLong()).isZero();

        CURRENT.set(new Actor("stf-1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.catalog_manager"),
                Set.of("catalog:write")));
        assertThat(call("GET", "/v1/size-charts/" + id, null, null).status()).isEqualTo(200);
        assertThat(call("GET", "/v1/size-charts?ownerOrgId=" + org, null, null).body().get("total").asLong()).isEqualTo(1);
        assertThat(call("PUT", "/v1/size-charts/" + id, chartBody("Private chart", "S", "M"), null).status())
                .isEqualTo(403);
        assertThat(call("DELETE", "/v1/size-charts/" + id, null, null).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ products and the public page

    private Map<String, Object> productBody(String chartId, String... sizes) {
        List<Map<String, Object>> variants = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            variants.add(Map.of("options", Map.of("Size", sizes[i]), "sellerSku", "SZ-" + org.substring(7) + "-" + i,
                    "gtinExempt", true, "priceMinor", 4900, "quantity", 5));
        }
        Map<String, Object> listing = new LinkedHashMap<>();
        listing.put("description", "Hand-finished wax print.");
        if (chartId != null) {
            listing.put("sizeChartId", chartId);
            listing.put("fitNote", "RUNS_SMALL");
        }
        return Map.of("categoryId", dresses.toString(), "title", "Ankara Wrap Dress, Puff Sleeve",
                "attributes", Map.of("brand", "Adire Lagos", "material", "Lace", "fit", "Regular", "length", "Midi",
                        "sleeve", "Short", "occasion", "Everyday", "care", "Cold wash", "origin", "Nigeria"),
                "images", List.of(Map.of("url", "https://utfs.io/f/a.jpg", "width", 1600, "height", 1600, "bytes", 250000,
                        "contentType", "image/jpeg")),
                "variationTheme", "Size", "variants", variants, "listing", listing);
    }

    @Test
    void aProductLinksTheChartWithCustomSizesAndThePublicPageReturnsIt() throws Exception {
        String chartId = call("POST", "/v1/size-charts", chartBody("Adire dresses", "S", "UK 12", "Free size"), null)
                .body().get("id").asString();

        Reply missingRow = call("POST", "/v1/products", productBody(chartId, "S", "XL"), null);
        assertThat(missingRow.status()).isEqualTo(422);
        assertThat(missingRow.body().get("fieldErrors").get("variants[1].options.Size").asString())
                .contains("Size \"XL\" has no row in the size chart \"Adire dresses\"");

        Reply created = call("POST", "/v1/products", productBody(chartId, "S", "uk 12", "free size"), null);
        assertThat(created.status()).isEqualTo(201);
        JsonNode product = created.body();
        assertThat(product.get("listing").get("sizeChartId").asString()).isEqualTo(chartId);
        assertThat(product.get("listing").get("fitNote").asString()).isEqualTo("RUNS_SMALL");
        List<String> sizes = new ArrayList<>();
        product.get("variants").forEach(v -> sizes.add(v.get("options").get("Size").asString()));
        assertThat(sizes).containsExactly("S", "UK 12", "Free size");

        CURRENT.set(ADMIN);
        assertThat(call("POST", "/v1/products/" + product.get("id").asString() + "/approve", null, null).status())
                .isEqualTo(200);
        Reply page = call("GET", "/v1/public/products/" + product.get("atin").asString(), null, null);
        assertThat(page.status()).isEqualTo(200);
        JsonNode shown = page.body().get("sizeChart");
        assertThat(shown.get("name").asString()).isEqualTo("Adire dresses");
        assertThat(shown.get("fitNote").asString()).isEqualTo("RUNS_SMALL");
        assertThat(shown.get("howToMeasure").asString()).isEqualTo("Measure over light clothing.");
        assertThat(shown.get("rows").size()).isEqualTo(3);
        assertThat(shown.get("columns").get(2).get("label").asString()).isEqualTo("Hip");
        assertThat(shown.has("ownerOrgId")).isFalse();
    }

    @Test
    void aSellerCannotLinkAnotherSellersChartAndAnOptionalChartMayBeLeftOut() throws Exception {
        String chartId = call("POST", "/v1/size-charts", chartBody("Adire dresses", "S"), null).body().get("id").asString();
        org = "org_other_" + UUID.randomUUID().toString().substring(0, 6);
        asSeller(org);

        Reply foreign = call("POST", "/v1/products", productBody(chartId, "S"), null);
        assertThat(foreign.status()).isEqualTo(422);
        assertThat(foreign.body().get("fieldErrors").get("sizeChartId").asString()).isEqualTo("Size chart not found");
        assertThat(call("POST", "/v1/products", productBody(null, "Age 6-7"), null).status()).isEqualTo(201);
    }

    @Test
    void whenStaffRequireAChartAProductWithoutOneIsRefused() throws Exception {
        CURRENT.set(ADMIN);
        String version = call("GET", "/v1/taxonomy/product-types/dress", null, null).body().get("version").asString();
        Reply on = call("PATCH", "/v1/taxonomy/product-types/dress", Map.of("reason", "charts are required",
                "traits", Map.of("sizing", Map.of("sizeChartRequired", true))), version);
        assertThat(on.status()).isEqualTo(200);
        try {
            asSeller(org);
            Reply refused = call("POST", "/v1/products", productBody(null, "S"), null);
            assertThat(refused.status()).isEqualTo(422);
            assertThat(refused.body().get("fieldErrors").get("sizeChartId").asString()).contains("Add a size chart");
        } finally {
            CURRENT.set(ADMIN);
            call("PATCH", "/v1/taxonomy/product-types/dress", Map.of("reason", "charts are optional again",
                    "traits", Map.of("sizing", Map.of("sizeChartRequired", false))), on.body().get("version").asString());
        }
    }

    // ------------------------------------------------------------------ bulk upload

    @Test
    void theSizeChartUploadCreatesThenUpdatesByNameAndProductRowsReferenceTheChart() throws Exception {
        Reply template = raw("GET", "/v1/size-charts/import-template?productType=dress&format=csv", null, null);
        assertThat(template.status()).isEqualTo(200);
        assertThat(template.text()).contains("Size chart name *").contains("Bust *").contains("Waist *").contains("Hip *");

        String header = template.text().lines().filter(l -> !l.startsWith("#") && !l.startsWith("﻿#")).findFirst()
                .orElseThrow().replace("﻿", "");
        String csv = header + "\r\nAdire dresses,cm,S,84-88,66-70,92-96,True to size,Measure over light clothing\r\n"
                + "Adire dresses,,UK 12,89-93,71-75,97-101,,\r\n";
        Reply dry = upload("/v1/size-charts/import?productType=dress&dryRun=true", "charts.csv", csv);
        assertThat(dry.status()).isEqualTo(200);
        assertThat(dry.body().get("applied").asBoolean()).isFalse();
        assertThat(dry.body().get("charts").get(0).get("action").asString()).isEqualTo("CREATE");
        assertThat(call("GET", "/v1/size-charts", null, null).body().get("total").asLong()).isZero();

        Reply applied = upload("/v1/size-charts/import?productType=dress", "charts.csv", csv);
        assertThat(applied.body().get("created").asInt()).isEqualTo(1);
        String chartId = applied.body().get("charts").get(0).get("chartId").asString();
        Reply again = upload("/v1/size-charts/import?productType=dress", "charts.csv", csv);
        assertThat(again.body().get("unchanged").asInt()).isEqualTo(1);
        assertThat(call("GET", "/v1/size-charts", null, null).body().get("total").asLong()).isEqualTo(1);

        Reply bad = upload("/v1/size-charts/import?productType=dress", "charts.csv", csv.replace("84-88", "abc"));
        assertThat(bad.status()).isEqualTo(200);
        assertThat(bad.body().get("failed").asInt()).isEqualTo(1);
        assertThat(bad.body().get("rows").get(0).get("messages").get(0).get("column").asString()).isEqualTo("m:bust");
        assertThat(upload("/v1/size-charts/import", "charts.pdf", "x").status()).isEqualTo(422);

        String productTemplate = new String(imports.template("dress", null, SpreadsheetFormat.CSV).content(),
                StandardCharsets.UTF_8);
        assertThat(productTemplate).contains("Size chart").contains("Size fit");
        assertThat(chartId).isNotBlank();
    }

    // ------------------------------------------------------------------ http helpers

    private Reply call(String method, String path, Object body, String ifMatch) throws Exception {
        Reply r = raw(method, path, body == null ? null : JSON.writeValueAsString(body), ifMatch);
        return r.text() == null || r.text().isBlank() ? r : new Reply(r.status(), JSON.readTree(r.text()), r.text());
    }

    private Reply raw(String method, String path, String json, String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), null, r.body());
    }

    private Reply upload(String path, String fileName, String content) throws Exception {
        String boundary = "----sizechart" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: text/csv\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body()), r.body());
    }

    private record Reply(int status, JsonNode body, String text) {
    }
}
