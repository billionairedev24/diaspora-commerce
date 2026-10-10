package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.DistanceClient;
import com.afrotransact.orders.port.UpstreamFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * geo-service (port 8205) {@code POST /v1/distance} (public). The delivery address's stored coordinates flow through
 * as {@code to: {lat, lng}}; without them the street address is sent and geo geocodes it.
 */
@Component
class HttpDistanceClient implements DistanceClient {

    private final Upstream geo;

    HttpDistanceClient(RestClient.Builder builder, @Value("${at.orders.upstreams.geo-url}") String baseUrl,
                       @Value("${at.orders.upstreams.timeout:5s}") Duration timeout) {
        this.geo = new Upstream("geo", builder, baseUrl, timeout);
    }

    @Override
    public Distance measure(double fromLat, double fromLng, ShipTo to) {
        Map<String, Object> target = new LinkedHashMap<>();
        if (to.lat() != null && to.lng() != null) {
            target.put("lat", to.lat());
            target.put("lng", to.lng());
        } else {
            Map<String, Object> address = new LinkedHashMap<>();
            address.put("line1", to.line1());
            address.put("line2", to.line2());
            address.put("city", to.city());
            address.put("state", to.state());
            address.put("postalCode", to.postalCode());
            address.put("country", to.country());
            target.put("address", address);
        }
        Map<String, Object> body = Map.of("from", Map.of("lat", fromLat, "lng", fromLng), "to", target);
        JsonNode d = geo.call(c -> c.post().uri("/v1/distance").body(body).retrieve().body(JsonNode.class));
        JsonNode road = d == null ? null : d.path("roadMiles").isNumber() ? d.path("roadMiles")
                : d.path("estimatedRoadMiles");
        if (road == null || !road.isNumber()) {
            throw new UpstreamFailure("geo", 0, "geo.invalid-response", "geo returned no distance", true, List.of(),
                    null);
        }
        return new Distance(d.path("straightLineMiles").asDouble(), road.asDouble(),
                d.path("durationMinutes").asDouble(0), d.path("method").asString("GEODESIC_DETOUR"));
    }
}
