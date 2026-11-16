package com.afrotransact.bookings.adapter.in.web;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON of the service search endpoint the MCP shopper tool reads, for the in-process {@code ServiceSearchApi}: it calls the
 * controller handler of {@code GET /v1/services/search} (same use case, same response record) and turns the response into a JSON tree.
 * It lives next to the controller because its handlers and response records are package private.
 */
@Component
public class BookingsEndpointViews {

    /** The number of hits the MCP tool has always asked for. */
    private static final int SIZE = 10;

    private final PublicBookingsController bookings;
    private final JsonMapper json;

    BookingsEndpointViews(PublicBookingsController bookings, JsonMapper json) {
        this.bookings = bookings;
        this.json = json;
    }

    public Object findServices(String query, String zip) {
        return json.convertValue(bookings.searchAlias(query, null, zip, SIZE).getBody(), Object.class);
    }
}
