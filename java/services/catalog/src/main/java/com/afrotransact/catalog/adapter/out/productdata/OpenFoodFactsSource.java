package com.afrotransact.catalog.adapter.out.productdata;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.afrotransact.catalog.config.ProductDataProperties;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.prefill.ProductData;
import com.afrotransact.catalog.port.ProductDataSource;
import com.afrotransact.catalog.port.ProductDataSourceException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Open Food Facts product API v2 ({@code GET /api/v2/product/{code}.json}), open data for food and groceries.
 * Not found is HTTP 404 or {@code status: 0}; Open Food Facts asks every client to send a descriptive User-Agent.
 */
@Component
class OpenFoodFactsSource implements ProductDataSource {

    static final String NAME = "OPEN_FOOD_FACTS";
    private static final String FIELDS = "code,product_name,product_name_en,generic_name,generic_name_en,brands,"
            + "quantity,image_front_url,image_url,categories_tags,categories";

    private final RestClient client;
    private final ProductDataProperties.OpenFoodFacts config;

    OpenFoodFactsSource(RestClient.Builder builder, ProductDataProperties props) {
        this.config = props.openFoodFacts();
        this.client = HttpClients.create(builder, config.baseUrl(), config.timeout());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public Optional<ProductData> lookup(Gtin gtin) {
        String code = code(gtin);
        try {
            return HttpClients.withRetry(() -> client.get()
                    .uri("/api/v2/product/{code}.json?fields={fields}", code, FIELDS)
                    .header("User-Agent", config.userAgent()).accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            return Optional.<ProductData>empty();
                        }
                        if (response.getStatusCode().isError()) {
                            throw new ProductDataSourceException("Open Food Facts answered HTTP " + status, null);
                        }
                        JsonNode body = response.bodyTo(JsonNode.class);
                        return body == null || body.path("status").asInt(0) != 1 ? Optional.<ProductData>empty()
                                : map(body.path("product"));
                    }));
        } catch (RestClientException e) {
            throw new ProductDataSourceException("Open Food Facts unavailable: " + e.getMessage(), e);
        }
    }

    /** Open Food Facts keys products by EAN-13 / EAN-8: UPC-A and GTIN-14 with indicator 0 lose the leading zero. */
    static String code(Gtin gtin) {
        String g = gtin.value();
        if (g.length() == 8) {
            return g;
        }
        String g14 = gtin.toGtin14();
        return g14.charAt(0) == '0' ? g14.substring(1) : g14;
    }

    private static Optional<ProductData> map(JsonNode p) {
        String title = first(p, "product_name_en", "product_name", "generic_name_en", "generic_name");
        String brands = text(p, "brands");
        String brand = brands == null ? null : brands.split(",")[0];
        String description = first(p, "generic_name_en", "generic_name");
        List<String> images = new ArrayList<>();
        addIfPresent(images, text(p, "image_front_url"));
        addIfPresent(images, text(p, "image_url"));
        List<String> categories = new ArrayList<>();
        p.path("categories_tags").forEach(t -> addIfPresent(categories, t.asString(null)));
        String freeText = text(p, "categories");
        if (categories.isEmpty() && freeText != null) {
            for (String c : freeText.split(",")) {
                addIfPresent(categories, c.strip());
            }
        }
        ProductData data = new ProductData(NAME, text(p, "code"), title, brand, text(p, "quantity"),
                description == null || description.equalsIgnoreCase(title) ? null : description, images, categories);
        return data.empty() ? Optional.empty() : Optional.of(data);
    }

    private static String first(JsonNode p, String... fields) {
        for (String f : fields) {
            String v = text(p, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String text(JsonNode p, String field) {
        String v = p.path(field).asString(null);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static void addIfPresent(List<String> list, String value) {
        if (value != null && !value.isBlank() && !list.contains(value)) {
            list.add(value);
        }
    }
}
