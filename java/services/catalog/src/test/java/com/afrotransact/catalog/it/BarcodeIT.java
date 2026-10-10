package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.GtinPrefillService;
import com.afrotransact.catalog.application.GtinPrefillService.GtinPrefill;
import com.afrotransact.catalog.application.LabelService;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.application.RenderedLabel;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.barcode.LabelFormat;
import com.afrotransact.catalog.testsupport.BarcodeScanner;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.google.zxing.BarcodeFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S031 GTIN prefill (GS1 then Open Food Facts, both on WireMock, cached in PostgreSQL) and S032 labels over HTTP,
 * including AT-FN unit labels issued per stock owner.
 *
 * <p>Provider timeouts are deliberately generous here (S244). The first provider call of the class is the first
 * HTTP exchange of the JVM with WireMock: client and server classes are still loading (and being instrumented by
 * JaCoCo in {@code verify}), so on a busy runner it can take well over a second. With a tight timeout that call
 * timed out, the lookup counted as a provider error, the miss was rightly not cached and the cache assertions
 * failed. Nothing here tests timeouts (no stub is delayed); timeout handling is covered deterministically by
 * {@code ProductDataSourcesTest} (2 s delay against an 800 ms timeout).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "at.consumers.enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.catalog.product-data.gs1.enabled=true", "at.catalog.product-data.gs1.api-key=it-gs1-key",
    "at.catalog.product-data.gs1.timeout=30s", "at.catalog.product-data.open-food-facts.timeout=30s"})
@ActiveProfiles("local")
@Testcontainers
@Import(BarcodeIT.Actors.class)
class BarcodeIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    static final WireMockServer PROVIDERS = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PROVIDERS.start();
    }

    private static final Actor ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));
    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(ADMIN);
    private static final ProductImage IMAGE = new ProductImage("https://utfs.io/f/gari.jpg", 1600, 1600, 300_000,
            "image/jpeg");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Switchable actor for direct use-case calls and HTTP requests (security is off in the local profile). */
    @TestConfiguration(proxyBeanMethods = false)
    static class Actors {

        @Bean
        ActorResolver switchableActorResolver() {
            return CURRENT::get;
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("at.catalog.product-data.gs1.base-url", PROVIDERS::baseUrl);
        registry.add("at.catalog.product-data.open-food-facts.base-url", PROVIDERS::baseUrl);
    }

    private final GtinPrefillService prefill;
    private final LabelService labels;
    private final ProductService products;
    private final CategoryService categories;
    private final JdbcClient jdbc;
    private final int port;

    @Autowired
    BarcodeIT(GtinPrefillService prefill, LabelService labels, ProductService products, CategoryService categories,
              JdbcClient jdbc, @LocalServerPort int port) {
        this.prefill = prefill;
        this.labels = labels;
        this.products = products;
        this.categories = categories;
        this.jdbc = jdbc;
        this.port = port;
    }

    @BeforeEach
    void resetProviders() {
        PROVIDERS.resetAll();
    }

    @AfterEach
    void resetActor() {
        CURRENT.set(ADMIN);
    }

    // ---------------------------------------------------------------------------------------------- S031 prefill

    @Test
    void prefillAsksGs1ThenOpenFoodFactsMergesMapsCategoriesAndCaches() {
        String gtin = gtin("003600029145");
        stubGs1(gtin, """
                [{"gtin":"%s","gtinRecordStatus":"ACTIVE",
                  "brandName":[{"language":"en","value":"Mama Gold"}],
                  "productDescription":[{"language":"en","value":"Mama Gold Premium Parboiled Rice"}],
                  "gpcCategoryCode":"50000000","netContent":[{"unitCode":"LBR","value":"5"}]}]
                """.formatted(new Gtin(gtin).toGtin14()));
        stubOff(gtin, "Long grain rice", "Olam", "2.27 kg", "[\"en:cereals-and-potatoes\",\"en:rices\"]");

        CURRENT.set(seller("org_seller_a", "catalog:write"));
        GtinPrefill p = prefill.prefill(gtin);

        assertThat(p.found()).isTrue();
        assertThat(p.cached()).isFalse();
        assertThat(p.title()).isEqualTo("Mama Gold Premium Parboiled Rice");
        assertThat(p.brand()).isEqualTo("Mama Gold");
        assertThat(p.netContent()).isEqualTo("5 lb");
        assertThat(p.imageUrls()).containsExactly("https://images.openfoodfacts.org/" + gtin + ".jpg");
        assertThat(p.importedFrom()).containsEntry("title", "GS1").containsEntry("imageUrls", "OPEN_FOOD_FACTS");
        assertThat(p.sources()).containsExactly("GS1", "OPEN_FOOD_FACTS");
        assertThat(p.categoryHints()).extracting(GtinPrefillService.CategoryHint::path)
                .containsExactly("Groceries › Pantry › Grains & rice", "Groceries");
        assertThat(p.categoryHints().getFirst().productTypeCode()).isEqualTo("grocery");
        assertThat(p.categoryHints().getFirst().level()).isEqualTo("PRODUCT_TYPE");

        // The second lookup is answered from the cache.
        GtinPrefill again = prefill.prefill(gtin);
        assertThat(again.cached()).isTrue();
        assertThat(again.title()).isEqualTo(p.title());
        PROVIDERS.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/grp/v3.2/gtins/verified")));
        PROVIDERS.verify(1, WireMock.getRequestedFor(WireMock.urlPathMatching("/api/v2/product/.*")));
        assertThat(jdbc.sql("select found from product_data_lookup where gtin = :g")
                .param("g", new Gtin(gtin).toGtin14()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void gs1FailureFallsBackToOpenFoodFacts() {
        String gtin = gtin("615100000001");
        PROVIDERS.stubFor(WireMock.post(WireMock.urlEqualTo("/grp/v3.2/gtins/verified"))
                .willReturn(WireMock.serviceUnavailable()));
        stubOff(gtin, "Ata rodo pepper paste", "Iya Basira", "400 g", "[\"en:sauces\",\"en:tomato-pastes\"]");

        GtinPrefill p = prefill.prefill(gtin);

        assertThat(p.found()).isTrue();
        assertThat(p.sources()).containsExactly("OPEN_FOOD_FACTS");
        assertThat(p.importedFrom()).containsEntry("title", "OPEN_FOOD_FACTS");
        assertThat(p.categoryHints()).extracting(GtinPrefillService.CategoryHint::path)
                .containsExactly("Groceries › Pantry › Sauces & pastes");
    }

    @Test
    void unknownGtinFallsBackToManualEntryAndTheMissIsCachedButErrorsAreNot() {
        String unknown = gtin("615100000002");
        PROVIDERS.stubFor(WireMock.post(WireMock.urlEqualTo("/grp/v3.2/gtins/verified"))
                .willReturn(WireMock.okJson("[]")));
        PROVIDERS.stubFor(WireMock.get(WireMock.urlPathMatching("/api/v2/product/.*"))
                .willReturn(WireMock.notFound()));

        GtinPrefill miss = prefill.prefill(unknown);
        assertThat(miss.found()).isFalse();
        assertThat(miss.message()).contains("enter the details");
        assertThat(prefill.prefill(unknown).cached()).isTrue();
        PROVIDERS.verify(1, WireMock.getRequestedFor(WireMock.urlPathMatching("/api/v2/product/.*")));

        PROVIDERS.resetAll();
        String failing = gtin("615100000003");
        PROVIDERS.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));
        assertThat(prefill.prefill(failing).found()).isFalse();
        assertThat(prefill.prefill(failing).cached()).isFalse();
        PROVIDERS.verify(2, WireMock.getRequestedFor(WireMock.urlPathMatching("/api/v2/product/.*")));
    }

    @Test
    void invalidGtinIs422WithASuggestionAndExistingProductsShortCircuit() throws IOException {
        HttpResponse<String> bad = send("GET", "/v1/identifiers/gtin/4006381333932/prefill", null);
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(bad.body()).path("fieldErrors").path("gtin").asString()).contains("4006381333931");

        String gtin = gtin("615100000009");
        createGroceryProduct(gtin, "PEP-" + UUID.randomUUID().toString().substring(0, 6));
        HttpResponse<String> ok = send("GET", "/v1/identifiers/gtin/" + gtin + "/prefill", null);
        assertThat(ok.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(ok.body());
        assertThat(body.path("existingAtin").asString()).matches("AT[0-9A-Z]{8}");
        assertThat(body.path("found").asBoolean()).isFalse();
        PROVIDERS.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));

        CURRENT.set(seller("org_x", "orders:read"));
        assertThatThrownBy(() -> prefill.prefill(gtin)).isInstanceOf(ForbiddenActionException.class);
    }

    // ----------------------------------------------------------------------------------------------- S032 labels

    @Test
    void labelsRenderInEveryFormatOverHttp() throws IOException {
        Map<String, Object> caseLabel = Map.of("kind", "CASE", "gtin", "9501101530003", "lot", "L2610",
                "productionDate", "2026-10-01", "expiry", "2027-03-31", "title", "Ijebu gari 12 × 2 lb");

        HttpResponse<byte[]> svg = sendBytes("POST", "/v1/labels?format=svg", caseLabel);
        assertThat(svg.statusCode()).isEqualTo(200);
        assertThat(svg.headers().firstValue("Content-Type")).hasValue("image/svg+xml");
        assertThat(new String(svg.body(), StandardCharsets.UTF_8))
                .contains("(01)09501101530003(11)261001(17)270331(10)L2610");

        HttpResponse<byte[]> png = sendBytes("POST", "/v1/labels?format=png", caseLabel);
        assertThat(png.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(BarcodeScanner.scanPng(png.body(), BarcodeFormat.CODE_128).getText())
                .isEqualTo("]C1010950110153000311261001172703311" + "0L2610");

        HttpResponse<byte[]> zpl = sendBytes("POST", "/v1/labels?format=ZPL", caseLabel);
        assertThat(new String(zpl.body(), StandardCharsets.UTF_8)).startsWith("^XA").contains("^BCN,").endsWith("^XZ\n");

        HttpResponse<byte[]> pdf = sendBytes("POST", "/v1/labels?format=pdf", caseLabel);
        assertThat(pdf.headers().firstValue("Content-Type")).hasValue("application/pdf");
        try (PDDocument doc = Loader.loadPDF(pdf.body())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }

        HttpResponse<byte[]> invalid = sendBytes("POST", "/v1/labels", Map.of("kind", "CASE", "gtin",
                "9501101530004"));
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(new String(invalid.body(), StandardCharsets.UTF_8)).contains("9501101530003");
    }

    @Test
    void batchesArePdfSheetsOrZpl() throws IOException {
        Map<String, Object> body = Map.of("layout", "SHEET", "paper", "A4", "labels", List.of(
                Map.of("kind", "UNIT", "data", "ATF0000001", "copies", 30),
                Map.of("kind", "RETAIL", "gtin", "036000291452", "title", "Peak milk"),
                Map.of("kind", "PALLET", "sscc", "106141411234567897", "gtin", "09501101530003", "count", 40)));
        HttpResponse<byte[]> pdf = sendBytes("POST", "/v1/labels/batch", body);
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("X-Label-Count")).hasValue("32");
        try (PDDocument doc = Loader.loadPDF(pdf.body())) {
            assertThat(doc.getNumberOfPages()).isBetween(2, 3);
        }
        HttpResponse<byte[]> zpl = sendBytes("POST", "/v1/labels/batch?format=zpl", body);
        assertThat(new String(zpl.body(), StandardCharsets.UTF_8).split("\\^XA", -1)).hasSize(4);

        HttpResponse<byte[]> svg = sendBytes("POST", "/v1/labels/batch?format=svg", body);
        assertThat(svg.statusCode()).isEqualTo(422);
        HttpResponse<byte[]> broken = sendBytes("POST", "/v1/labels/batch", Map.of("labels", List.of(
                Map.of("kind", "UNIT", "data", "ATF0000001"), Map.of("kind", "PALLET"))));
        assertThat(JSON.readTree(broken.body()).path("fieldErrors").has("labels[1].sscc")).isTrue();
    }

    @Test
    void unitLabelsUseTheVendorGtinForRetailStockAndAtFnCodesOtherwise() {
        String gtin = gtin("615100000010");
        Product withGtin = createGroceryProduct(gtin, "GAR-" + UUID.randomUUID().toString().substring(0, 6));
        Product exempt = createGroceryProduct(null, "EXM-" + UUID.randomUUID().toString().substring(0, 6));
        String variantAtin = withGtin.variants().getFirst().atin().value();

        // AfroTransact Retail stock with a vendor GTIN keeps the manufacturer barcode.
        RenderedLabel retail = labels.unitLabel(variantAtin, null, LabelFormat.ZPL, 1);
        assertThat(new String(retail.content(), StandardCharsets.UTF_8)).contains("^BEN,", "MANUFACTURER BARCODE");

        // Without a GTIN: an AT-FN code, issued once per owner and variant.
        String first = svgCode(labels.unitLabel(exempt.atin().value(), null, LabelFormat.SVG, 1));
        assertThat(first).matches("ATF[0-9A-Z]{7}");
        assertThat(svgCode(labels.unitLabel(exempt.variants().getFirst().atin().value(), null, LabelFormat.SVG, 1)))
                .isEqualTo(first);

        // A seller printing FBA labels for the same (live) page gets their own code, and the vendor GTIN is not used.
        products.approve(withGtin.id());
        CURRENT.set(seller("org_seller_b", "catalog:write"));
        String sellerCode = svgCode(labels.unitLabel(variantAtin, null, LabelFormat.SVG, 1));
        assertThat(sellerCode).matches("ATF[0-9A-Z]{7}").isNotEqualTo(first);
        assertThat(jdbc.sql("select count(*) from unit_label where owner_org = 'org_seller_b'").query(Long.class)
                .single()).isEqualTo(1);
        // …but not for a page that is not live yet, nor for another organisation.
        assertThatThrownBy(() -> labels.unitLabel(exempt.atin().value(), null, LabelFormat.SVG, 1))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> labels.unitLabel(variantAtin, "org_other", LabelFormat.SVG, 1))
                .isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> labels.unitLabel(variantAtin, null, LabelFormat.SVG, 0))
                .isInstanceOf(CatalogRuleViolation.class);

        // The AT-FN code is returned as data; inventory-service reads it with catalog:read (S226 / FBA inbound).
        assertThat(labels.unitLabel(variantAtin, null, LabelFormat.SVG, 1).unitCode()).isEqualTo(sellerCode);
        CURRENT.set(new Actor("inventory-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("catalog:read")));
        assertThat(labels.unitLabel(variantAtin, "org_seller_b", LabelFormat.SVG, 1).unitCode()).isEqualTo(sellerCode);
        assertThat(labels.unitLabel(variantAtin, null, LabelFormat.ZPL, 1).unitCode())
                .as("Retail's manufacturer barcode has no AT-FN code").isNull();
        CURRENT.set(seller("org_seller_b", "catalog:read"));
        assertThatThrownBy(() -> labels.unitLabel(variantAtin, null, LabelFormat.SVG, 1))
                .isInstanceOf(ForbiddenActionException.class);
    }

    // -------------------------------------------------------------------------------------------------- helpers

    private Product createGroceryProduct(String gtin, String sku) {
        CategoryNode leaf = categories.tree().stream().filter(n -> n.path().equals("Groceries › Pantry › Grains & rice"))
                .findFirst().orElseThrow();
        Map<String, String> attrs = Map.of("brand", "AfroTransact", "ingredients", "Cassava", "allergens", "None",
                "shelf", "365", "storage", "Room temperature", "origin", "Nigeria", "cert", "None");
        return products.create(new CreateProductCommand(leaf.id(), null, "Ijebu Gari Premium 2 lb", attrs,
                List.of(IMAGE), "Net weight", List.of(new VariantDraft(Map.of("Net weight", "2 lb"), sku, gtin,
                gtin == null, 899, 10)), "USD"));
    }

    private static String svgCode(RenderedLabel label) {
        String svg = new String(label.content(), StandardCharsets.UTF_8);
        int start = svg.indexOf("<title>") + "<title>".length();
        return svg.substring(start, svg.indexOf("</title>"));
    }

    private static Actor seller(String org, String... permissions) {
        return new Actor("member-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"),
                Set.of(permissions));
    }

    /** A valid GTIN-13 from a 12-digit body. */
    private static String gtin(String body12) {
        return body12 + Gtin.checkDigit(body12);
    }

    private static void stubGs1(String gtin, String body) {
        PROVIDERS.stubFor(WireMock.post(WireMock.urlEqualTo("/grp/v3.2/gtins/verified"))
                .withHeader("APIKey", WireMock.equalTo("it-gs1-key"))
                .withRequestBody(WireMock.containing(new Gtin(gtin).toGtin14()))
                .willReturn(WireMock.okJson(body)));
    }

    private static void stubOff(String gtin, String name, String brand, String quantity, String categoriesJson) {
        PROVIDERS.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v2/product/" + gtin + ".json"))
                .willReturn(WireMock.okJson("""
                        {"status":1,"product":{"code":"%s","product_name":"%s","brands":"%s","quantity":"%s",
                         "image_front_url":"https://images.openfoodfacts.org/%s.jpg","categories_tags":%s}}
                        """.formatted(gtin, name, brand, quantity, gtin, categoriesJson))));
    }

    private HttpResponse<String> send(String method, String path, Object body) {
        return exchange(method, path, body, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> sendBytes(String method, String path, Object body) {
        return exchange(method, path, body, HttpResponse.BodyHandlers.ofByteArray());
    }

    private <T> HttpResponse<T> exchange(String method, String path, Object body, HttpResponse.BodyHandler<T> h) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            return HTTP.send(b.build(), h);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
