package com.afrotransact.fulfilment.adapter.out.http;

import java.time.Duration;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.port.GeoClient;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** geo-service (port 8205): {@code GET /v1/zip/{zip}} and {@code GET /v1/zones/covering} (public endpoints). */
@Component
class HttpGeoClient implements GeoClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpGeoClient.class);

    private final Upstream geo;

    HttpGeoClient(RestClient.Builder builder, @Value("${at.fulfilment.upstreams.geo-url}") String baseUrl,
                  @Value("${at.fulfilment.upstreams.geo-timeout:2s}") Duration timeout) {
        this.geo = new Upstream("geo", builder, baseUrl, timeout);
    }

    @Override
    public Optional<GeoPoint> locateZip(String zip) {
        if (zip == null || !zip.matches("\\d{5}")) {
            return Optional.empty();
        }
        try {
            JsonNode a = geo.call(c -> c.get().uri("/v1/zip/{zip}", zip).retrieve().body(JsonNode.class));
            if (a == null || !a.path("lat").isNumber() || !a.path("lon").isNumber()) {
                return Optional.empty();
            }
            return Optional.of(new GeoPoint(a.path("lat").asDouble(), a.path("lon").asDouble()));
        } catch (UpstreamFailure f) {
            LOG.debug("ZIP {} not located: {}", zip, f.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> deliveryZoneAt(GeoPoint point) {
        try {
            JsonNode r = geo.call(c -> c.get().uri(b -> b.path("/v1/zones/covering")
                    .queryParam("lat", point.lat()).queryParam("lon", point.lon())
                    .queryParam("type", "DELIVERY").build()).retrieve().body(JsonNode.class));
            if (r == null) {
                return Optional.empty();
            }
            for (JsonNode z : r.path("zones")) {
                if (z.path("active").asBoolean(true) && !z.path("name").asString("").isBlank()) {
                    return Optional.of(z.path("name").asString());
                }
            }
            return Optional.empty();
        } catch (UpstreamFailure f) {
            LOG.debug("Zone lookup failed: {}", f.getMessage());
            return Optional.empty();
        }
    }
}
