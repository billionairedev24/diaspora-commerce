package com.afrotransact.catalog.adapter.out.offers;

import java.net.http.HttpClient;
import java.time.Duration;

import com.afrotransact.catalog.port.SellerOffers;
import com.afrotransact.catalog.port.ServiceTokens;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * offers-service {@code GET /v1/offers/presence} with catalog's service token (scope {@code offers:read}). A failure is
 * never guessed: it is {@link OffersUnavailable}, so a request to change somebody else's page is refused.
 */
@Component
class HttpSellerOffers implements SellerOffers {

    private final RestClient offers;
    private final ServiceTokens tokens;

    HttpSellerOffers(RestClient.Builder builder, ServiceTokens tokens,
                     @Value("${at.catalog.offers.base-url:http://localhost:8102}") String baseUrl,
                     @Value("${at.catalog.offers.timeout:3s}") Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.offers = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PresenceJson(boolean hasOpenOffer) {
    }

    @Override
    public boolean hasOpenOffer(String sellerOrgId, String atin) {
        try {
            String token = tokens.token();
            PresenceJson body = offers.get().uri(b -> b.path("/v1/offers/presence").queryParam("atin", atin)
                    .queryParam("sellerOrgId", sellerOrgId).build()).headers(h -> {
                        if (token != null) {
                            h.setBearerAuth(token);
                        }
                    }).retrieve().body(PresenceJson.class);
            if (body == null) {
                throw new OffersUnavailable("offers-service returned nothing for " + atin, null);
            }
            return body.hasOpenOffer();
        } catch (OffersUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OffersUnavailable("offers-service could not say whether " + sellerOrgId + " sells " + atin + ": "
                    + e.getMessage(), e);
        }
    }
}
