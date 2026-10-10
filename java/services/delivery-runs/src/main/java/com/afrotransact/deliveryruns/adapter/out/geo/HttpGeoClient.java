package com.afrotransact.deliveryruns.adapter.out.geo;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.deliveryruns.adapter.out.http.ClientCredentialsTokens;
import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.RouteEstimate;
import com.afrotransact.deliveryruns.port.GeoGateway;
import com.afrotransact.deliveryruns.port.GeoUnavailableException;
import com.afrotransact.deliveryruns.port.GeoZoneRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * geo-service (local port 8205). Public endpoints: {@code POST /v1/geocode}, {@code GET /v1/zones/covering},
 * {@code POST /v1/distance}, {@code POST /v1/distance/batch}. {@code POST /v1/zones} needs a bearer token with
 * {@code geo:zones:write} (client credentials of {@code delivery-runs-service}).
 */
public class HttpGeoClient implements GeoGateway, GeoZoneRegistry {

    private static final int BATCH = 100;

    private final RestClient geo;
    private final ClientCredentialsTokens tokens;
    private final String zoneType;

    /**
     * @param zoneType geo zone type used to register polygons
     */
    public HttpGeoClient(String baseUrl, Duration timeout, String zoneType, String tokenUri, String clientId,
                         String clientSecret, Clock clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.geo = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = new ClientCredentialsTokens(tokenUri, clientId, clientSecret, "geo:zones:write", timeout, clock,
                GeoUnavailableException::new);
        this.zoneType = zoneType;
    }

    @Override
    public Optional<GeoPoint> locateZip(String zip) {
        try {
            JsonNode r = geo.post().uri("/v1/geocode").body(Map.of("postalCode", zip, "country", "US"))
                    .retrieve().body(JsonNode.class);
            if (r == null || !r.path("lat").isNumber() || !r.path("lng").isNumber()) {
                return Optional.empty();
            }
            return Optional.of(new GeoPoint(r.path("lat").asDouble(), r.path("lng").asDouble()));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()
                    || e.getStatusCode().value() == HttpStatus.UNPROCESSABLE_CONTENT.value()) {
                return Optional.empty();
            }
            throw unavailable("geocode", e);
        } catch (RestClientException e) {
            throw unavailable("geocode", e);
        }
    }

    @Override
    public Set<String> zonesCovering(GeoPoint point, String type) {
        try {
            JsonNode r = geo.get().uri(b -> b.path("/v1/zones/covering").queryParam("lat", point.lat())
                    .queryParam("lon", point.lng()).queryParam("type", type).build()).retrieve().body(JsonNode.class);
            Set<String> ids = new HashSet<>();
            if (r != null) {
                for (JsonNode z : r.path("zones")) {
                    if (z.path("active").asBoolean(true) && z.path("inside").asBoolean(true)) {
                        ids.add(z.path("id").asString(""));
                    }
                }
            }
            return ids;
        } catch (RestClientException e) {
            throw unavailable("zones/covering", e);
        }
    }

    @Override
    public List<Double> straightLineMiles(GeoPoint from, List<GeoPoint> to) {
        List<Double> out = new ArrayList<>(to.size());
        for (int start = 0; start < to.size(); start += BATCH) {
            List<GeoPoint> chunk = to.subList(start, Math.min(to.size(), start + BATCH));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", place(from));
            body.put("to", chunk.stream().map(HttpGeoClient::place).toList());
            JsonNode r;
            try {
                r = geo.post().uri("/v1/distance/batch").body(body).retrieve().body(JsonNode.class);
            } catch (RestClientException e) {
                throw unavailable("distance/batch", e);
            }
            Double[] miles = new Double[chunk.size()];
            if (r != null) {
                for (JsonNode item : r.path("results")) {
                    int i = item.path("index").asInt(-1);
                    if (i >= 0 && i < miles.length && item.path("straightLineMiles").isNumber()) {
                        miles[i] = item.path("straightLineMiles").asDouble();
                    }
                }
            }
            out.addAll(Arrays.asList(miles));
        }
        return out;
    }

    @Override
    public RouteEstimate route(GeoPoint from, GeoPoint to) {
        try {
            JsonNode r = geo.post().uri("/v1/distance").body(Map.of("from", place(from), "to", place(to)))
                    .retrieve().body(JsonNode.class);
            if (r == null || !r.path("roadMiles").isNumber() || !r.path("durationMinutes").isNumber()) {
                throw new GeoUnavailableException("geo-service returned no route", null);
            }
            return new RouteEstimate(r.path("roadMiles").asDouble(), r.path("durationMinutes").asDouble(),
                    r.path("method").asString("UNKNOWN"));
        } catch (RestClientException e) {
            throw unavailable("distance", e);
        }
    }

    @Override
    public String registerPolygon(String name, String ownerId, String geoJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", zoneType);
        body.put("ownerId", ownerId);
        body.put("name", name);
        body.put("geojson", geoJson);
        body.put("active", true);
        try {
            JsonNode r = geo.post().uri("/v1/zones").header("Authorization", "Bearer " + tokens.token()).body(body)
                    .retrieve().body(JsonNode.class);
            String id = r == null ? "" : r.path("id").asString("");
            if (id.isEmpty()) {
                throw new GeoUnavailableException("geo-service returned no zone id", null);
            }
            return id;
        } catch (RestClientException e) {
            throw unavailable("zones", e);
        }
    }

    private static Map<String, Object> place(GeoPoint p) {
        return Map.of("lat", p.lat(), "lng", p.lng());
    }

    private static GeoUnavailableException unavailable(String call, RestClientException e) {
        return new GeoUnavailableException("geo-service " + call + " failed: " + e.getMessage(), e);
    }
}
