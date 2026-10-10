package com.afrotransact.retail.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/** HTTP surface: API reference pages, problem details, validation, ETags. */
class ApiIT extends RetailIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void servesOpenApiSwaggerScalarAndRedoc() {
        HttpResponse<String> spec = HttpSupport.get(port, "/v3/api-docs");
        assertThat(spec.statusCode()).isEqualTo(200);
        assertThat(spec.body()).contains("/v1/purchase-orders/{id}/approve", "/v1/receipts/{id}/complete",
                "/v1/shipments/{id}/landed-cost", "/v1/invoices", "/v1/gl/journal", "/v1/items/{id}/atin");
        assertThat(HttpSupport.get(port, "/v3/api-docs.yaml").statusCode()).isEqualTo(200);
        assertThat(HttpSupport.get(port, "/swagger-ui.html").statusCode()).isEqualTo(200);
        HttpResponse<String> scalar = HttpSupport.get(port, "/docs");
        assertThat(scalar.statusCode()).isEqualTo(200);
        assertThat(scalar.body()).contains("Retail API");
        assertThat(HttpSupport.get(port, "/redoc").statusCode()).isEqualTo(200);
        assertThat(HttpSupport.get(port, "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    void errorsAreProblemDetailsWithStableCodes() {
        HttpResponse<String> missing = HttpSupport.get(port, "/v1/purchase-orders/" + UUID.randomUUID());
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":\"retail.po-not-found\"");
        HttpResponse<String> invalid = HttpSupport.post(port, "/v1/suppliers", "{\"code\":\"bad\",\"name\":\"\",\"country\":\"GH\",\"currency\":\"GHS\",\"leadTimeDays\":1}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("errors");
        HttpResponse<String> noKey = HttpSupport.post(port, "/v1/invoices", "{}");
        assertThat(noKey.statusCode()).isEqualTo(400);
    }

    @Test
    void createsSuppliersAndItemsOverHttpWithEtags() {
        String code = unique("SUP-");
        HttpResponse<String> created = HttpSupport.post(port, "/v1/suppliers", "{\"code\":\"" + code + "\","
                + "\"name\":\"Kumasi Soap Works\",\"country\":\"GH\",\"city\":\"Kumasi\",\"currency\":\"GHS\","
                + "\"paymentTerms\":\"Net 45\",\"leadTimeDays\":42,\"certifications\":[\"GSA certified\"],"
                + "\"contacts\":[{\"name\":\"Kofi\",\"role\":\"Sales\"}]}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("ETag")).contains("\"0\"");
        assertThat(created.body()).contains("\"status\":\"ONBOARDING\"", "\"canReceivePurchaseOrders\":false");
        String id = created.body().replaceAll(".*\"id\":\"([0-9a-f-]{36})\".*", "$1");
        HttpResponse<String> approved = HttpSupport.post(port, "/v1/suppliers/" + id + "/status",
                "{\"status\":\"APPROVED\"}", "If-Match", "\"0\"");
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(approved.headers().firstValue("ETag")).contains("\"1\"");

        HttpResponse<String> item = HttpSupport.post(port, "/v1/items", "{\"name\":\"African Black Soap, 8 oz\","
                + "\"category\":\"Beauty\",\"primarySupplierId\":\"" + id + "\",\"supplierItems\":[{\"supplierId\":\""
                + id + "\",\"supplierSku\":\"KSW-BSP-8\",\"casePack\":48,\"minOrderQty\":480,\"unitCostMinor\":2540,"
                + "\"currency\":\"GHS\"}],\"hsCode\":\"3401.11.5000\",\"dutyRatePercent\":0,\"shelfLifeDays\":1095,"
                + "\"storage\":\"AMBIENT\",\"reorderPoint\":150,\"packSize\":\"8 oz bar\",\"innerPack\":12,"
                + "\"casePack\":48,\"unitWeightGrams\":230,\"standardCostMinor\":236,\"priceMinor\":949}");
        assertThat(item.statusCode()).isEqualTo(201);
        assertThat(item.body()).contains("\"listingStatus\":\"DRAFT\"", "\"sku\":\"AT-AFR-", "\"gtin14\":\"0086001");
    }
}
