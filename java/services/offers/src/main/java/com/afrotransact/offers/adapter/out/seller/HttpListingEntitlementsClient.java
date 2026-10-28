package com.afrotransact.offers.adapter.out.seller;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.offers.port.ListingEntitlementsClient;
import com.afrotransact.offers.port.ServiceTokenProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * seller-service entitlements with a short cache per organisation (a plan changes rarely; the cap is checked on every
 * new listing). Failures are not cached and never block the seller.
 */
@Component
class HttpListingEntitlementsClient implements ListingEntitlementsClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpListingEntitlementsClient.class);

    private final RestClient client;
    private final ServiceTokenProvider tokens;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    HttpListingEntitlementsClient(RestClient.Builder builder, @Value("${at.offers.seller.base-url}") String baseUrl,
                                  @Value("${at.offers.seller.timeout:3s}") Duration timeout,
                                  @Value("${at.offers.seller.entitlements-ttl:60s}") Duration ttl,
                                  ServiceTokenProvider tokens, Clock clock) {
        this.tokens = tokens;
        this.ttl = ttl;
        this.clock = clock;
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EntitlementsJson(Long listings, boolean listingsUnlimited) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrgEntitlementsJson(String planName, EntitlementsJson entitlements) {
    }

    private record Cached(ListingAllowance allowance, Instant until) {
    }

    @Override
    public ListingAllowance allowance(String sellerOrgId) {
        Instant now = Instant.now(clock);
        Cached cached = cache.get(sellerOrgId);
        if (cached != null && cached.until().isAfter(now)) {
            return cached.allowance();
        }
        try {
            OrgEntitlementsJson body = client.get().uri("/v1/orgs/{org}/entitlements", sellerOrgId)
                    .headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(OrgEntitlementsJson.class);
            ListingAllowance allowance = body == null || body.entitlements() == null
                    || body.entitlements().listingsUnlimited() ? ListingAllowance.UNLIMITED
                    : new ListingAllowance(body.planName(), body.entitlements().listings());
            cache.put(sellerOrgId, new Cached(allowance, now.plus(ttl)));
            return allowance;
        } catch (RestClientException ex) {
            LOG.warn("seller-service entitlements of {} unavailable ({}); not limiting this listing", sellerOrgId,
                    ex.getMessage());
            return ListingAllowance.UNLIMITED;
        }
    }
}
