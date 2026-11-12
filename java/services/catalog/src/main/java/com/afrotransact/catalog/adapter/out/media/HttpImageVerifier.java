package com.afrotransact.catalog.adapter.out.media;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.images.ImageCheckResult;
import com.afrotransact.catalog.domain.images.ImageFinding;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageVerifier;
import com.afrotransact.catalog.port.ServiceTokens;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * media-service {@code POST /v1/assets/verify} and {@code /v1/assets/rescore} with catalog's service token (scope
 * media:import). Calls are cut into the batches media accepts (100 to verify, 20 to rescore). Only public image URLs
 * are sent; nothing from the answer is logged beyond counts.
 */
@Component
class HttpImageVerifier implements ImageVerifier {

    static final int VERIFY_BATCH = 100;
    static final int RESCORE_BATCH = 20;

    private final RestClient media;
    private final ServiceTokens tokens;

    HttpImageVerifier(RestClient.Builder builder, ServiceTokens tokens,
                      @Value("${at.catalog.media.base-url}") String baseUrl,
                      @Value("${at.catalog.media.timeout:30s}") Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.media = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CheckJson(String code, String level, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResultJson(String url, Boolean found, String verdict, List<CheckJson> checks, Integer width, Integer height,
                      Long bytes, String contentType, Boolean measured) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnswerJson(List<ResultJson> results) {
    }

    @Override
    public List<ImageCheckResult> verify(List<Item> items) {
        return call("/v1/assets/verify", items, VERIFY_BATCH);
    }

    @Override
    public List<ImageCheckResult> rescore(List<Item> items) {
        return call("/v1/assets/rescore", items, RESCORE_BATCH);
    }

    private List<ImageCheckResult> call(String path, List<Item> items, int batch) {
        List<ImageCheckResult> out = new ArrayList<>(items.size());
        for (int from = 0; from < items.size(); from += batch) {
            List<Item> part = items.subList(from, Math.min(items.size(), from + batch));
            out.addAll(callBatch(path, part));
        }
        return out;
    }

    private List<ImageCheckResult> callBatch(String path, List<Item> items) {
        List<Map<String, String>> images = new ArrayList<>();
        for (Item i : items) {
            Map<String, String> one = new LinkedHashMap<>();
            one.put("url", i.url());
            one.put("slot", i.slot());
            if (i.productTypeCode() != null && !i.productTypeCode().isBlank()) {
                one.put("productTypeCode", i.productTypeCode());
            }
            images.add(one);
        }
        String token = tokens.token();
        try {
            AnswerJson answer = media.post().uri(path)
                    .headers(h -> {
                        if (token != null) {
                            h.setBearerAuth(token);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("images", images))
                    .retrieve()
                    .body(AnswerJson.class);
            if (answer == null || answer.results() == null || answer.results().size() != items.size()) {
                throw new ImageServiceUnavailable("media answered with the wrong number of results", null);
            }
            List<ImageCheckResult> out = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                out.add(toResult(items.get(i), answer.results().get(i)));
            }
            return out;
        } catch (RestClientException ex) {
            throw new ImageServiceUnavailable("media unavailable: " + ex.getMessage(), ex);
        }
    }

    private static ImageCheckResult toResult(Item asked, ResultJson r) {
        List<ImageFinding> checks = r.checks() == null ? List.of()
                : r.checks().stream().filter(c -> c.code() != null)
                        .map(c -> new ImageFinding(c.code(), c.level(), c.message())).toList();
        return new ImageCheckResult(asked.url(), Boolean.TRUE.equals(r.found()), r.verdict(), checks,
                r.width() == null ? 0 : r.width(), r.height() == null ? 0 : r.height(), r.bytes() == null ? 0 : r.bytes(),
                r.contentType(), Boolean.TRUE.equals(r.measured()));
    }
}
