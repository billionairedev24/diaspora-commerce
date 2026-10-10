package com.afrotransact.catalog.adapter.out.productdata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Verified by GS1 (GS1 Registry Platform, the source behind GS1 US Data Hub "View"):
 * {@code POST /grp/v3.2/gtins/verified} with the GTIN-14s as a JSON array and the subscription key in the
 * {@code APIKey} header. Answers carry language-tagged {@code productDescription}, {@code brandName},
 * {@code productImageUrl}, {@code netContent} (UN/ECE unit codes) and the GPC brick code. Off unless enabled with a
 * key.
 */
@Component
class Gs1VerifiedSource implements ProductDataSource {

    static final String NAME = "GS1";
    private static final Map<String, String> UNITS = Map.ofEntries(Map.entry("GRM", "g"), Map.entry("KGM", "kg"),
            Map.entry("MGM", "mg"), Map.entry("MLT", "ml"), Map.entry("CLT", "cl"), Map.entry("LTR", "l"),
            Map.entry("ONZ", "oz"), Map.entry("OZA", "fl oz"), Map.entry("LBR", "lb"), Map.entry("H87", "ct"),
            Map.entry("EA", "ct"));

    private final RestClient client;
    private final ProductDataProperties.Gs1 config;

    Gs1VerifiedSource(RestClient.Builder builder, ProductDataProperties props) {
        this.config = props.gs1();
        this.client = HttpClients.create(builder, config.baseUrl(), config.timeout());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled() {
        return config.enabled() && !config.apiKey().isBlank();
    }

    @Override
    public Optional<ProductData> lookup(Gtin gtin) {
        try {
            return HttpClients.withRetry(() -> client.post().uri("/grp/v3.2/gtins/verified")
                    .header("APIKey", config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(List.of(gtin.toGtin14()))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            return Optional.<ProductData>empty();
                        }
                        if (status == 401 || status == 403) {
                            throw new ProductDataSourceException("GS1 rejected the API key (HTTP " + status + ")",
                                    null);
                        }
                        if (response.getStatusCode().isError()) {
                            throw new ProductDataSourceException("GS1 answered HTTP " + status, null);
                        }
                        JsonNode body = response.bodyTo(JsonNode.class);
                        JsonNode item = body != null && body.isArray() ? body.path(0) : body;
                        return item == null || item.isMissingNode() ? Optional.<ProductData>empty() : map(item);
                    }));
        } catch (RestClientException e) {
            throw new ProductDataSourceException("GS1 unavailable: " + e.getMessage(), e);
        }
    }

    private static Optional<ProductData> map(JsonNode item) {
        String status = item.path("gtinRecordStatus").asString("");
        if (!status.isEmpty() && !"ACTIVE".equalsIgnoreCase(status)) {
            return Optional.empty();
        }
        List<String> images = new ArrayList<>();
        for (JsonNode img : item.path("productImageUrl")) {
            String url = img.path("value").asString(null);
            if (url != null && !images.contains(url)) {
                images.add(url);
            }
        }
        List<String> categories = new ArrayList<>();
        String gpc = item.path("gpcCategoryCode").asString(null);
        if (gpc != null && !gpc.isBlank()) {
            categories.add("gpc:" + gpc.strip());
        }
        ProductData data = new ProductData(NAME, item.path("gtin").asString(null),
                localized(item.path("productDescription")), localized(item.path("brandName")),
                netContent(item.path("netContent")), null, images, categories);
        return data.empty() ? Optional.empty() : Optional.of(data);
    }

    /** English value of a {@code [{language, value}]} array, else the first one. */
    private static String localized(JsonNode values) {
        String first = null;
        for (JsonNode v : values) {
            String value = v.path("value").asString(null);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (v.path("language").asString("").toLowerCase(Locale.ROOT).startsWith("en")) {
                return value;
            }
            first = first == null ? value : first;
        }
        return first;
    }

    /** {@code [{unitCode: GRM, value: 500}]} → "500 g" (several measures joined with " / "). */
    private static String netContent(JsonNode values) {
        List<String> parts = new ArrayList<>();
        for (JsonNode v : values) {
            String value = v.path("value").asString(null);
            String unit = v.path("unitCode").asString("");
            if (value != null && !value.isBlank()) {
                parts.add(value.strip() + " " + UNITS.getOrDefault(unit, unit.toLowerCase(Locale.ROOT)));
            }
        }
        return parts.isEmpty() ? null : String.join(" / ", parts).strip();
    }
}
