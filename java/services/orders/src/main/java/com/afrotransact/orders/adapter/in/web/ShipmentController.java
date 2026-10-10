package com.afrotransact.orders.adapter.in.web;

import java.time.Instant;

import com.afrotransact.orders.adapter.in.web.OrdersDtos.CourierEventRequest;
import com.afrotransact.orders.adapter.in.web.OrdersDtos.TrackingRequest;
import com.afrotransact.orders.adapter.in.web.OrdersDtos.TransitionRequest;
import com.afrotransact.orders.application.DeliveryBrief;
import com.afrotransact.orders.application.OrderPage;
import com.afrotransact.orders.application.OrderQueryService;
import com.afrotransact.orders.application.OrderSearchQuery;
import com.afrotransact.orders.application.OrderView;
import com.afrotransact.orders.application.SellerShipmentView;
import com.afrotransact.orders.application.ShipmentActionResult;
import com.afrotransact.orders.application.ShipmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Shipments", description = "Shipment state machine: seller queue, status transitions per actor, courier "
        + "events with proof, tracking, staff search")
@RestController
class ShipmentController {

    private final ShipmentService shipments;
    private final OrderQueryService queries;

    ShipmentController(ShipmentService shipments, OrderQueryService queries) {
        this.shipments = shipments;
        this.queries = queries;
    }

    @Operation(summary = "Move a shipment to another status",
            description = "Seller-fulfilled: only the seller confirms, packs and marks ready; platform admins may "
                    + "confirm delivery, record a failure, process returns or cancel — never packed. AfroTransact-"
                    + "fulfilled: admins and fulfilment ops own every step. Refusals: 403 orders.transition-forbidden "
                    + "(not this actor's step) or 409 orders.invalid-transition. The optional Idempotency-Key makes "
                    + "retries safe (same key = same result).")
    @PostMapping("/v1/shipments/{id}/transitions")
    ShipmentActionResult transition(@PathVariable String id,
                                    @RequestHeader(value = CheckoutController.IDEMPOTENCY_KEY, required = false)
                                    String key,
                                    @Valid @RequestBody TransitionRequest body) {
        return shipments.transition(id, body.to(), body.reason(), key);
    }

    @Operation(summary = "Driver-app event (AfroTransact Delivery)",
            description = "DRIVER principal with delivery:operate. Delivered needs a photo and a handover method; "
                    + "handing to the customer checks their 4-digit code when one is required; a safe place is "
                    + "refused for perishables and hand-delivery orders. Out-of-order events never move a shipment "
                    + "backwards; replays of the same eventId are ignored.")
    @PostMapping("/v1/shipments/{id}/courier-events")
    ShipmentActionResult courier(@PathVariable String id, @Valid @RequestBody CourierEventRequest body) {
        return shipments.courierEvent(id, body.toCommand());
    }

    @Operation(summary = "Stop details for the driver app (address, instructions, perishable, code needed)")
    @GetMapping("/v1/shipments/{id}/delivery-brief")
    DeliveryBrief brief(@PathVariable String id) {
        return shipments.deliveryBrief(id);
    }

    @Operation(summary = "Record the carrier label of a carrier shipment (seller or staff)",
            description = "carrier.tracking.updated events are matched by label reference (shipment id) or by "
                    + "carrier + tracking number.")
    @PostMapping("/v1/shipments/{id}/tracking")
    OrderView tracking(@PathVariable String id, @Valid @RequestBody TrackingRequest body) {
        return shipments.attachTracking(id, body.carrier(), body.trackingNumber());
    }

    @Operation(summary = "Seller shipment queue",
            description = "Shipments of the caller's organisation (staff: name it with org), newest first.")
    @GetMapping("/v1/seller/shipments")
    OrderPage<SellerShipmentView> sellerQueue(@RequestParam(required = false) String status,
                                              @Parameter(description = "Staff only: seller organisation")
                                              @RequestParam(required = false) String org,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(defaultValue = "20") int limit) {
        return queries.sellerQueue(org, status, cursor, limit);
    }

    @Operation(summary = "Staff order search (orders:read)")
    @GetMapping("/v1/admin/orders")
    OrderPage<OrderView> search(@RequestParam(required = false) String number,
                                @RequestParam(required = false) String customer,
                                @RequestParam(required = false) String seller,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String shipmentStatus,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                Instant from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                Instant to,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(defaultValue = "20") int limit) {
        return queries.search(new OrderSearchQuery(number, customer, seller, status, shipmentStatus, from, to),
                cursor, limit);
    }
}
