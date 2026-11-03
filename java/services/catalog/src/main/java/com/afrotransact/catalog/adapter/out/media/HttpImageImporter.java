package com.afrotransact.catalog.adapter.out.media;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.port.ImageImporter;
import com.afrotransact.catalog.port.ServiceTokens;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * media-service {@code POST /v1/assets/import} with catalog's service token (scope media:import): media fetches the
 * URL (https only, public addresses only, at most 8 MB, image types only), quarantines, inspects and moderates it
 * like a browser upload, owned by the uploading organisation. A 422 is the image's fault (unreachable, not an image,
 * too large) and becomes a REJECTED result with media's reason; other failures mean media is unavailable.
 */
@Component
class HttpImageImporter implements ImageImporter {

    private final RestClient media;
    private final ServiceTokens tokens;

    HttpImageImporter(RestClient.Builder builder, ServiceTokens tokens,
                      @Value("${at.catalog.media.base-url}") String baseUrl,
                      @Value("${at.catalog.media.timeout:30s}") Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.media = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AssetJson(String id, String url, Integer width, Integer height, Long bytes, String contentType,
                     String status, List<String> flags) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProblemJson(String detail, String title, String code) {
    }

    @Override
    public ImportedImage importImage(String url, String ownerOrg, String uploadedBy) {
        String token = tokens.token();
        try {
            AssetJson a = media.post().uri("/v1/assets/import")
                    .headers(h -> {
                        if (token != null) {
                            h.setBearerAuth(token);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("purpose", "product-image", "url", url, "ownerOrg", ownerOrg,
                            "uploadedBy", uploadedBy == null ? "" : uploadedBy))
                    .retrieve()
                    .body(AssetJson.class);
            if (a == null) {
                throw new ImageServiceUnavailable("media returned no asset", null);
            }
            String reason = a.flags() == null || a.flags().isEmpty() ? null : String.join("; ", a.flags());
            return new ImportedImage(a.url(), a.width() == null ? 0 : a.width(), a.height() == null ? 0 : a.height(),
                    a.bytes() == null ? 0 : a.bytes(), a.contentType() == null ? "" : a.contentType(),
                    a.status() == null ? "REJECTED" : a.status(), reason);
        } catch (RestClientResponseException ex) {
            HttpStatusCode code = ex.getStatusCode();
            if (code.value() == 422 || code.value() == 400) {
                ProblemJson p = ex.getResponseBodyAs(ProblemJson.class);
                String reason = p == null ? "The image could not be fetched"
                        : p.detail() != null ? p.detail() : p.title();
                return new ImportedImage(null, 0, 0, 0, "", "REJECTED", reason);
            }
            throw new ImageServiceUnavailable("media answered " + code.value(), ex);
        } catch (RestClientException ex) {
            throw new ImageServiceUnavailable("media unavailable: " + ex.getMessage(), ex);
        }
    }
}
