package com.afrotransact.catalog.adapter.out.media;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.imports.ImageLibrary;
import com.afrotransact.catalog.domain.imports.LibraryImage;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageLibraryLookup;
import com.afrotransact.catalog.port.ServiceTokens;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** media-service {@code POST /v1/library/lookup} with catalog's service token (scope media:import). */
@Component
class HttpImageLibraryLookup implements ImageLibraryLookup {

    private static final int MAX_KEYS = 5000;

    private final RestClient media;
    private final ServiceTokens tokens;

    HttpImageLibraryLookup(RestClient.Builder builder, ServiceTokens tokens,
                           @Value("${at.catalog.media.base-url}") String baseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.media = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LibraryImageJson(String assetId, String fileName, String sku, String slot, String status, String url,
                            Integer width, Integer height, Long bytes, String contentType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LookupJson(List<LibraryImageJson> images) {
    }

    @Override
    public ImageLibrary lookup(String ownerOrg, Collection<String> skus, Collection<String> fileNames) {
        List<String> s = skus.stream().filter(v -> v != null && !v.isBlank()).distinct().limit(MAX_KEYS).toList();
        List<String> f = fileNames.stream().filter(v -> v != null && !v.isBlank()).distinct().limit(MAX_KEYS)
                .toList();
        if (s.isEmpty() && f.isEmpty()) {
            return ImageLibrary.EMPTY;
        }
        String token = tokens.token();
        try {
            LookupJson body = media.post().uri("/v1/library/lookup")
                    .headers(h -> {
                        if (token != null) {
                            h.setBearerAuth(token);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ownerOrg", ownerOrg, "skus", s, "fileNames", f))
                    .retrieve()
                    .body(LookupJson.class);
            if (body == null || body.images() == null) {
                return ImageLibrary.EMPTY;
            }
            return new ImageLibrary(body.images().stream().map(i -> new LibraryImage(i.assetId(), i.fileName(),
                    i.sku(), i.slot(), i.status(), i.url(), i.width() == null ? 0 : i.width(),
                    i.height() == null ? 0 : i.height(), i.bytes() == null ? 0 : i.bytes(),
                    i.contentType())).toList());
        } catch (RestClientException ex) {
            throw new ImageServiceUnavailable("media library lookup failed: " + ex.getMessage(), ex);
        }
    }
}
