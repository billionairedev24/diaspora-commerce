package com.afrotransact.catalog.adapter.out.productdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;

import com.afrotransact.catalog.config.ProductDataProperties;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.prefill.ProductData;
import com.afrotransact.catalog.port.ProductDataSourceException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** S031: the Open Food Facts and GS1 adapters against WireMock (no real internet). */
class ProductDataSourcesTest {

    static final String OFF_RICE = """
            {"code":"0036000291452","status":1,"status_verbose":"product found","product":{
              "code":"0036000291452","product_name":"Riz long grain","product_name_en":"Long grain parboiled rice",
              "generic_name_en":"Parboiled rice","brands":"Mama Gold,Olam","quantity":"5 lb",
              "image_front_url":"https://images.openfoodfacts.org/images/products/003/600/029/1452/front_en.3.400.jpg",
              "image_url":"https://images.openfoodfacts.org/images/products/003/600/029/1452/front_en.3.400.jpg",
              "categories_tags":["en:plant-based-foods","en:cereals-and-potatoes","en:rices","en:long-grain-rices"]}}
            """;
    static final String GS1_RICE = """
            [{"gtin":"00036000291452","gtinRecordStatus":"ACTIVE","isComplete":true,
              "brandName":[{"language":"fr","value":"Mama Or"},{"language":"en","value":"Mama Gold"}],
              "productDescription":[{"language":"en","value":"Mama Gold Premium Parboiled Rice"}],
              "productImageUrl":[{"language":"en","value":"https://images.gs1.example/0036000291452.png"}],
              "gpcCategoryCode":"10000043",
              "netContent":[{"unitCode":"LBR","value":"5"},{"unitCode":"KGM","value":"2.27"}]}]
            """;

    private static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private OpenFoodFactsSource off;
    private Gs1VerifiedSource gs1;

    @BeforeAll
    static void start() {
        WIREMOCK.start();
    }

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void setUp() {
        WIREMOCK.resetAll();
        ProductDataProperties props = new ProductDataProperties(null, null, null,
                new ProductDataProperties.Gs1(true, WIREMOCK.baseUrl(), "gs1-key", Duration.ofMillis(800)),
                new ProductDataProperties.OpenFoodFacts(true, WIREMOCK.baseUrl(), "AfroTransact-Test/1.0",
                        Duration.ofMillis(800)));
        off = new OpenFoodFactsSource(RestClient.builder(), props);
        gs1 = new Gs1VerifiedSource(RestClient.builder(), props);
    }

    @Test
    void openFoodFactsMapsAFoundProduct() {
        WIREMOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v2/product/0036000291452.json"))
                .willReturn(WireMock.okJson(OFF_RICE)));

        ProductData d = off.lookup(new Gtin("036000291452")).orElseThrow();

        assertThat(d.source()).isEqualTo("OPEN_FOOD_FACTS");
        assertThat(d.title()).isEqualTo("Long grain parboiled rice");
        assertThat(d.brand()).isEqualTo("Mama Gold");
        assertThat(d.netContent()).isEqualTo("5 lb");
        assertThat(d.description()).isEqualTo("Parboiled rice");
        assertThat(d.imageUrls()).hasSize(1);
        assertThat(d.sourceCategories()).endsWith("en:long-grain-rices");
        WIREMOCK.verify(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/v2/product/0036000291452.json"))
                .withHeader("User-Agent", WireMock.equalTo("AfroTransact-Test/1.0"))
                .withQueryParam("fields", WireMock.containing("categories_tags")));
    }

    @Test
    void openFoodFactsNotFoundIsEmptyAndErrorsThrow() {
        WIREMOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v2/product/4006381333931.json"))
                .willReturn(WireMock.notFound().withBody("{\"status\":0,\"status_verbose\":\"product not found\"}")));
        WIREMOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v2/product/9501101530003.json"))
                .willReturn(WireMock.okJson("{\"status\":0}")));
        WIREMOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v2/product/96385074.json"))
                .willReturn(WireMock.serverError()));
        WIREMOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v2/product/0850012345671.json"))
                .willReturn(WireMock.okJson(OFF_RICE).withFixedDelay(2_000)));

        assertThat(off.lookup(new Gtin("4006381333931"))).isEmpty();
        assertThat(off.lookup(new Gtin("09501101530003"))).isEmpty();
        assertThatThrownBy(() -> off.lookup(new Gtin("96385074"))).isInstanceOf(ProductDataSourceException.class)
                .hasMessageContaining("500");
        assertThatThrownBy(() -> off.lookup(new Gtin("850012345671")))
                .isInstanceOf(ProductDataSourceException.class);
    }

    @Test
    void gs1MapsTheEnglishValuesAndSendsTheKey() {
        WIREMOCK.stubFor(WireMock.post(WireMock.urlEqualTo("/grp/v3.2/gtins/verified"))
                .willReturn(WireMock.okJson(GS1_RICE)));

        Optional<ProductData> found = gs1.lookup(new Gtin("036000291452"));

        ProductData d = found.orElseThrow();
        assertThat(d.source()).isEqualTo("GS1");
        assertThat(d.title()).isEqualTo("Mama Gold Premium Parboiled Rice");
        assertThat(d.brand()).isEqualTo("Mama Gold");
        assertThat(d.netContent()).isEqualTo("5 lb / 2.27 kg");
        assertThat(d.imageUrls()).containsExactly("https://images.gs1.example/0036000291452.png");
        assertThat(d.sourceCategories()).containsExactly("gpc:10000043");
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/grp/v3.2/gtins/verified"))
                .withHeader("APIKey", WireMock.equalTo("gs1-key"))
                .withRequestBody(WireMock.equalToJson("[\"00036000291452\"]")));
    }

    @Test
    void gs1InactiveIsEmptyBadKeyThrowsAndNoKeyMeansDisabled() {
        WIREMOCK.stubFor(WireMock.post(WireMock.urlEqualTo("/grp/v3.2/gtins/verified"))
                .withRequestBody(WireMock.containing("09501101530003"))
                .willReturn(WireMock.okJson("[{\"gtin\":\"09501101530003\",\"gtinRecordStatus\":\"INACTIVE\"}]")));
        WIREMOCK.stubFor(WireMock.post(WireMock.urlEqualTo("/grp/v3.2/gtins/verified"))
                .withRequestBody(WireMock.containing("04006381333931"))
                .willReturn(WireMock.unauthorized()));

        assertThat(gs1.lookup(new Gtin("9501101530003"))).isEmpty();
        assertThatThrownBy(() -> gs1.lookup(new Gtin("4006381333931"))).isInstanceOf(ProductDataSourceException.class)
                .hasMessageContaining("API key");
        assertThat(gs1.enabled()).isTrue();
        assertThat(new Gs1VerifiedSource(RestClient.builder(), new ProductDataProperties(null, null, null,
                new ProductDataProperties.Gs1(true, WIREMOCK.baseUrl(), " ", null), null)).enabled()).isFalse();
        assertThat(new Gs1VerifiedSource(RestClient.builder(), new ProductDataProperties(null, null, null, null,
                null)).enabled()).isFalse();
    }
}
