package com.afrotransact.orders.adapter.in.web;

import com.afrotransact.orders.application.OrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Shipments")
@RestController
class ShipmentStreamController {

    private final OrderQueryService queries;
    private final SseShipmentFeed feed;

    ShipmentStreamController(OrderQueryService queries, SseShipmentFeed feed) {
        this.queries = queries;
        this.feed = feed;
    }

    @Operation(summary = "Live shipment status changes (server-sent events)",
            description = "Staff with orders:read receive every transition; sellers only their organisation's. Event "
                    + "name shipment.status_changed; driver and carrier updates appear within a second of commit.")
    @GetMapping(path = "/v1/shipments/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        return feed.subscribe(queries.liveFeedScope());
    }
}
