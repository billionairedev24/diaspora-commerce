package com.afrotransact.seller.adapter.out.media;

import java.net.URI;

import com.afrotransact.seller.port.MediaAsset;
import com.afrotransact.seller.port.MediaClient;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * media-service client: {@code GET /v1/assets/{id}} returns metadata with a short-lived signed {@code url}; the bytes
 * are then read from that URL. Calls carry the service token configured for seller-service.
 */
public class HttpMediaClient implements MediaClient {

    private final RestClient api;
    private final RestClient files;
    private final JsonMapper json;

    public HttpMediaClient(String baseUrl, String serviceToken, JsonMapper json) {
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl);
        if (serviceToken != null && !serviceToken.isBlank()) {
            b = b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
        }
        this.api = b.build();
        this.files = RestClient.create();
        this.json = json;
    }

    @Override
    public MediaAsset fetch(String assetId) {
        JsonNode asset = metadata(assetId);
        String url = asset.path("url").asString(null);
        if (url == null) {
            throw new NotFoundException("seller.asset-not-ready", "Document " + assetId + " has no download URL");
        }
        byte[] bytes = files.get().uri(URI.create(url)).retrieve().body(byte[].class);
        return new MediaAsset(assetId, asset.path("fileName").asString(assetId),
                asset.path("contentType").asString("application/octet-stream"), bytes == null ? new byte[0] : bytes);
    }

    @Override
    public String viewUrl(String assetId) {
        return metadata(assetId).path("url").asString(null);
    }

    private JsonNode metadata(String assetId) {
        try {
            String body = api.get().uri("/v1/assets/{id}", assetId).retrieve().body(String.class);
            return json.readTree(body == null ? "{}" : body);
        } catch (HttpClientErrorException.NotFound e) {
            throw new NotFoundException("seller.asset-not-found", "Document " + assetId + " not found");
        }
    }
}
