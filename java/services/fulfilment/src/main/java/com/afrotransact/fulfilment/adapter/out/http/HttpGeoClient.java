package com.afrotransact.fulfilment.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    public List<Optional<RoadDistance>> roadDistances(GeoPoint from, List<GeoPoint> to) {
        if (to.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = Map.of("from", Map.of("lat", from.lat(), "lng", from.lon()),
                "to", to.stream().map(p -> Map.of("lat", p.lat(), "lng", p.lon())).toList());
        try {
            JsonNode r = geo.call(c -> c.post().uri("/v1/distance/batch").body(body).retrieve()
                    .body(JsonNode.class));
            List<Optional<RoadDistance>> out = new ArrayList<>();
            for (int i = 0; i < to.size(); i++) {
                out.add(Optional.empty());
            }
            if (r == null) {
                return List.of();
            }
            for (JsonNode item : r.path("results")) {
                int i = item.path("index").asInt(-1);
                JsonNode miles = item.path("roadMiles").isNumber() ? item.path("roadMiles")
                        : item.path("estimatedRoadMiles");
                if (i >= 0 && i < to.size() && miles.isNumber()) {
                    out.set(i, Optional.of(new RoadDistance(miles.asDouble(), item.path("durationMinutes").asDouble(0),
                            item.path("method").asString("GEODESIC_DETOUR"))));
                }
            }
            return out;
        } catch (UpstreamFailure f) {
            LOG.debug("Road distances unavailable: {}", f.getMessage());
            return List.of();
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
