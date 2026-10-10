package com.afrotransact.trustops.adapter.out.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.port.BookingsReviewClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** bookings-service {@code POST /v1/reviews/{bookingId}/approve} and {@code /reject} (contracts/openapi/bookings.yaml). */
@Component
class HttpBookingsReviewClient implements BookingsReviewClient {

    private static final String SERVICE = "bookings";
    private static final int MAX_NOTE = 1000;

    private final Downstream downstream;
    private final RestClient client;

    HttpBookingsReviewClient(Downstream downstream, TrustOpsProperties props) {
        this.downstream = downstream;
        this.client = downstream.client(props.bookings().baseUrl(), null);
    }

    @Override
    public void approve(UUID bookingId, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("note", truncate(note));
        downstream.call(SERVICE, client, c -> c.post().uri("/v1/reviews/{bookingId}/approve", bookingId)
                .headers(h -> downstream.headers(h, null)).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class), n -> n);
    }

    @Override
    public void reject(UUID bookingId, ModerationReasonCode reasonCode, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", reasonCode.name());
        body.put("note", truncate(note));
        downstream.call(SERVICE, client, c -> c.post().uri("/v1/reviews/{bookingId}/reject", bookingId)
                .headers(h -> downstream.headers(h, null)).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class), n -> n);
    }

    private static String truncate(String note) {
        return note == null ? null : note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE) : note;
    }
}
