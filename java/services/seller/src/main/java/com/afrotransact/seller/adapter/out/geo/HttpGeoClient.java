package com.afrotransact.seller.adapter.out.geo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.seller.domain.GeoPosition;
import com.afrotransact.seller.domain.ShipFromAddress;
import com.afrotransact.seller.port.DeliveryZones;
import com.afrotransact.seller.port.Geocoder;
import com.afrotransact.seller.port.GeoUnavailableException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * geo-service (local port 8205) public endpoints: {@code POST /v1/geocode} and
 * {@code GET /v1/zones/covering?type=DELIVERY&withinMiles=}. 4xx = no such address / zone; anything else = unavailable.
 */
public class HttpGeoClient implements Geocoder, DeliveryZones {

    private final RestClient geo;
    private final Clock clock;

    public HttpGeoClient(String baseUrl, Duration timeout, Clock clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.geo = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.clock = clock;
    }

    @Override
    public Optional<GeoPosition> geocode(ShipFromAddress a) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("line1", a.line1());
        body.put("line2", a.line2());
        body.put("city", a.city());
        body.put("state", a.state());
        body.put("postalCode", a.postalCode());
        body.put("country", a.country());
        try {
            JsonNode r = geo.post().uri("/v1/geocode").body(body).retrieve().body(JsonNode.class);
            if (r == null || !r.path("lat").isNumber() || !r.path("lng").isNumber()) {
                return Optional.empty();
            }
            return Optional.of(new GeoPosition(r.path("lat").asDouble(), r.path("lng").asDouble(),
                    r.path("precision").asString("ADDRESS"), Instant.now(clock)));
        } catch (HttpClientErrorException e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new GeoUnavailableException("geo-service geocode failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ZoneMatch> nearest(double lat, double lng, double withinMiles) {
        try {
            JsonNode r = geo.get().uri(b -> b.path("/v1/zones/covering").queryParam("lat", lat)
                    .queryParam("lon", lng).queryParam("type", "DELIVERY").queryParam("withinMiles", withinMiles)
                    .build()).retrieve().body(JsonNode.class);
            if (r == null) {
                return Optional.empty();
            }
            for (JsonNode z : r.path("zones")) {
                if (z.path("active").asBoolean(true)) {
                    return Optional.of(new ZoneMatch(z.path("name").asString(""), z.path("inside").asBoolean(false),
                            z.path("distanceMiles").asDouble(0)));
                }
            }
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new GeoUnavailableException("geo-service zone lookup failed: " + e.getMessage(), e);
        }
    }
}
