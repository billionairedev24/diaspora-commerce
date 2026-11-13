package com.afrotransact.catalog.adapter.out.search;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.catalog.port.CandidateRecall;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * search-service {@code GET /v1/search} (contracts/openapi/search.yaml): the public query, so it runs on whichever engine
 * {@code SEARCH_PROVIDER} picked (Meilisearch or Elasticsearch) and needs no token. Only the ATINs are used; catalog reads
 * everything else from its own tables.
 */
@Component
class HttpCandidateRecall implements CandidateRecall {

    private final RestClient search;
    private final String market;

    HttpCandidateRecall(RestClient.Builder builder, @Value("${at.catalog.search.base-url:http://localhost:8201}") String baseUrl,
                        @Value("${at.catalog.search.timeout:3s}") Duration timeout,
                        @Value("${at.catalog.search.market:us}") String market) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.search = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.market = market;
    }

    @Override
    public List<String> recall(String text, int limit) {
        try {
            JsonNode page = search.get().uri(b -> b.path("/v1/search").queryParam("q", text).queryParam("market", market)
                    .queryParam("size", Math.min(limit, 60)).queryParam("track", false).build()).retrieve()
                    .body(JsonNode.class);
            List<String> atins = new ArrayList<>();
            if (page != null) {
                for (JsonNode hit : page.path("hits")) {
                    String atin = hit.path("atin").asText("");
                    if (!atin.isBlank()) {
                        atins.add(atin);
                    }
                }
            }
            return atins;
        } catch (RuntimeException e) {
            throw new RecallUnavailable("search-service could not answer: " + e.getMessage(), e);
        }
    }
}
