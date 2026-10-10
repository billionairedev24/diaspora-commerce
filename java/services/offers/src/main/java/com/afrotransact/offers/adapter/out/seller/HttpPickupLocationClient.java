package com.afrotransact.offers.adapter.out.seller;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import com.afrotransact.offers.port.PickupLocationClient;
import com.afrotransact.offers.port.ServiceTokenProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * seller-service (port 8106) {@code GET /v1/ship-from-locations/{id}} with offers' own client-credentials token
 * (scope {@code sellers:ship-from.read}). 404 = unknown location; other failures raise
 * {@link SellerUnavailableException} (HTTP 503).
 */
@Component
class HttpPickupLocationClient implements PickupLocationClient {

    private final RestClient client;
    private final ServiceTokenProvider tokens;

    HttpPickupLocationClient(RestClient.Builder builder, @Value("${at.offers.seller.base-url}") String baseUrl,
                             @Value("${at.offers.seller.timeout:3s}") Duration timeout, ServiceTokenProvider tokens) {
        this.tokens = tokens;
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PickupJson(boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LocationJson(String id, String orgId, String city, String state, String country, String postalCode,
                        PickupJson afrotransactPickup) {
    }

    @Override
    public Optional<PickupLocation> find(String locationId) {
        try {
            LocationJson l = client.get().uri("/v1/ship-from-locations/{id}", locationId)
                    .headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(LocationJson.class);
            if (l == null) {
                return Optional.empty();
            }
            return Optional.of(new PickupLocation(l.id(), l.orgId(), l.afrotransactPickup() != null
                    && l.afrotransactPickup().enabled(), l.city(), l.state(), l.country(), l.postalCode()));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest ex) {
            return Optional.empty();
        } catch (RestClientException ex) {
            throw new SellerUnavailableException("seller-service is unavailable: " + ex.getMessage(), ex);
        }
    }
}
