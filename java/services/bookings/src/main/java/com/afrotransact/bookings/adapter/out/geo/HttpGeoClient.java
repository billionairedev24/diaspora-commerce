package com.afrotransact.bookings.adapter.out.geo;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.afrotransact.bookings.config.BookingsProperties;
import com.afrotransact.bookings.domain.CustomerPlace;
import com.afrotransact.bookings.domain.ZoneCity;
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
 * a ZIP's city, state and centroid and {@code GET /v1/zips/nearby} for the cities around a point.
 */
@Component
class HttpGeoClient implements GeoClient {

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
    public Optional<CustomerPlace> place(String zip) {
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
        return Optional.of(new CustomerPlace(zip, place.path("city").asString(""), place.path("state").asString(""),
                place.path("lat").asDouble(), place.path("lon").asDouble()));
    }

    @Override
    public List<ZoneCity> citiesNear(double lat, double lon, int miles, int limit) {
        JsonNode body;
        try {
            body = client.get().uri("/v1/zips/nearby?lat={lat}&lon={lon}&miles={miles}&limit={limit}", lat, lon,
                    Math.clamp(miles, 1, 150), limit).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
        List<ZoneCity> out = new ArrayList<>();
        if (body != null) {
            for (JsonNode c : body.path("items")) {
                out.add(new ZoneCity(c.path("city").asString(""), c.path("state").asString("")));
            }
        }
        return out;
    }

    private static GeoClientException unavailable(RuntimeException e) {
        return new GeoClientException("Geo service unavailable: " + e.getMessage(), e);
    }
}
