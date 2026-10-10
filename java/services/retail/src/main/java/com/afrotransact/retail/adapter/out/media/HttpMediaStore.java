package com.afrotransact.retail.adapter.out.media;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.port.MediaStore;
import com.afrotransact.retail.port.MediaUnavailableException;
import com.afrotransact.retail.port.ServiceTokenProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * media-service over HTTP with the retail-service client-credentials token: {@code POST /v1/uploads}, a direct
 * {@code PUT} of the bytes to the returned storage URL (never with our token), {@code POST /v1/assets/{id}/complete}
 * and {@code GET /v1/assets/{id}} (private assets carry a signed URL; {@code urlTtlSeconds} asks for a longer one,
 * capped by media per purpose).
 */
public class HttpMediaStore implements MediaStore {

    /** What media signs today when it ignores {@code urlTtlSeconds}. */
    static final Duration MEDIA_DEFAULT_TTL = Duration.ofMinutes(5);

    private final RestClient api;
    private final RestClient storage;
    private final ServiceTokenProvider tokens;
    private final JsonMapper json;
    private final Clock clock;

    /**
     * @param api     media-service base URL
     * @param storage client for the pre-signed storage URLs (absolute, no base URL, no auth)
     */
    public HttpMediaStore(RestClient api, RestClient storage, ServiceTokenProvider tokens, JsonMapper json,
                          Clock clock) {
        this.api = api;
        this.storage = storage;
        this.tokens = tokens;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public MediaAsset store(String purpose, String fileName, String contentType, byte[] content) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("purpose", purpose);
            request.put("fileName", fileName);
            request.put("contentType", contentType);
            request.put("size", content.length);
            JsonNode created = json.readTree(api.post().uri("/v1/uploads").headers(this::auth)
                    .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(request))
                    .retrieve().body(String.class));
            UUID id = UUID.fromString(created.path("asset").path("id").asString());
            JsonNode upload = created.path("upload");
            String method = upload.path("method").asString("PUT");
            storage.method(HttpMethod.valueOf(method))
                    .uri(URI.create(upload.path("url").asString()))
                    .headers(h -> {
                        upload.path("headers").properties().forEach(e -> h.set(e.getKey(), e.getValue().asString()));
                        if (!h.containsHeader(HttpHeaders.CONTENT_TYPE)) {
                            h.set(HttpHeaders.CONTENT_TYPE, contentType);
                        }
                    })
                    .body(content).retrieve().toBodilessEntity();
            JsonNode asset = json.readTree(api.post().uri("/v1/assets/{id}/complete", id).headers(this::auth)
                    .retrieve().body(String.class));
            MediaAsset stored = asset(asset);
            if (!stored.usable()) {
                throw new MediaUnavailableException("media did not accept " + fileName + " (" + stored.status()
                        + ")", null);
            }
            return stored;
        } catch (RestClientException | IllegalArgumentException e) {
            throw new MediaUnavailableException("media-service could not store " + fileName + ": " + e.getMessage(),
                    e);
        }
    }

    @Override
    public Optional<MediaAsset> find(UUID assetId) {
        try {
            String body = api.get().uri("/v1/assets/{id}", assetId).headers(this::auth).retrieve()
                    .body(String.class);
            return Optional.of(asset(json.readTree(body)));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new MediaUnavailableException("media-service unavailable: " + e.getMessage(), e);
        }
    }

    @Override
    public SignedLink signedLink(UUID assetId, Duration ttl) {
        try {
            JsonNode asset = json.readTree(api.get().uri("/v1/assets/{id}?urlTtlSeconds={ttl}", assetId,
                    ttl.toSeconds()).headers(this::auth).retrieve().body(String.class));
            String url = asset.path("url").asString(null);
            if (url == null || url.isBlank()) {
                throw new MediaUnavailableException("media returned no URL for asset " + assetId, null);
            }
            String expires = asset.path("urlExpiresAt").asString(null);
            Instant expiresAt = expires != null ? Instant.parse(expires) : Instant.now(clock).plus(MEDIA_DEFAULT_TTL);
            return new SignedLink(url, expiresAt);
        } catch (RestClientException e) {
            throw new MediaUnavailableException("media-service unavailable: " + e.getMessage(), e);
        }
    }

    private MediaAsset asset(JsonNode a) {
        return new MediaAsset(UUID.fromString(a.path("id").asString()), a.path("purpose").asString(null),
                a.path("fileName").asString(null), a.path("contentType").asString(null), a.path("bytes").asLong(0),
                a.path("status").asString("PENDING"));
    }

    private void auth(HttpHeaders headers) {
        tokens.token().ifPresent(headers::setBearerAuth);
    }
}
