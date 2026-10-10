package com.afrotransact.bookings.adapter.out.geo;

import java.net.http.HttpClient;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.bookings.config.BookingsProperties;
import com.afrotransact.bookings.port.GeoClient;
import com.afrotransact.bookings.port.GeoClientException;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * {@link GeoClient} over geo-service's public REST API (contracts/openapi/geo.yaml): {@code GET /v1/zip/{zip}} for
 * the ZIP's coordinates, then {@code GET /v1/zones/covering?lat&lon&type=SERVICE_AREA}; zone owners are provider
 * organisation ids.
 */
@Component
class HttpGeoClient implements GeoClient {

    private static final String SERVICE_AREA = "SERVICE_AREA";

    private final RestClient client;

    HttpGeoClient(RestClient.Builder builder, BookingsProperties props) {
        BookingsProperties.Geo cfg = props.geo();
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(cfg.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(cfg.timeout());
        this.client = builder.clone().requestFactory(factory).baseUrl(cfg.baseUrl()).build();
    }

    @Override
    public Optional<Set<String>> serviceAreaOwners(String zip) {
        JsonNode place;
        try {
            place = client.get().uri("/v1/zip/{zip}", zip).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()
                    || e.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()) {
                return Optional.empty();
            }
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
        if (place == null || !place.hasNonNull("lat") || !place.hasNonNull("lon")) {
            return Optional.empty();
        }
        double lat = place.path("lat").asDouble();
        double lon = place.path("lon").asDouble();
        JsonNode zones;
        try {
            zones = client.get().uri("/v1/zones/covering?lat={lat}&lon={lon}&type={type}", lat, lon, SERVICE_AREA)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
        Set<String> owners = new LinkedHashSet<>();
        if (zones != null) {
            for (JsonNode z : zones.path("zones")) {
                boolean active = !z.has("active") || z.path("active").asBoolean(true);
                String owner = z.path("ownerId").asString("");
                if (active && SERVICE_AREA.equals(z.path("type").asString(SERVICE_AREA)) && !owner.isEmpty()) {
                    owners.add(owner);
                }
            }
        }
        return Optional.of(Set.copyOf(owners));
    }

    private static GeoClientException unavailable(RuntimeException e) {
        return new GeoClientException("Geo service unavailable: " + e.getMessage(), e);
    }
}
