package com.afrotransact.orders.adapter.in.web;

import com.afrotransact.orders.adapter.in.web.OrdersDtos.CancelRequest;
import com.afrotransact.orders.application.OrderPage;
import com.afrotransact.orders.application.OrderQueryService;
import com.afrotransact.orders.application.OrderView;
import com.afrotransact.orders.application.PreorderDeliveryService;
import com.afrotransact.orders.application.ShipmentService;
import com.afrotransact.orders.domain.MissedDeliveryChoice;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.port.PaymentsClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Orders", description = "Your orders, order detail per viewer, customer shipment cancellation, payment split")
@RestController
class OrderController {

    private final OrderQueryService queries;
    private final ShipmentService shipments;
    private final PreorderDeliveryService preorders;

    OrderController(OrderQueryService queries, ShipmentService shipments, PreorderDeliveryService preorders) {
        this.queries = queries;
        this.shipments = shipments;
        this.preorders = preorders;
    }

    @Operation(summary = "My orders (newest first)",
            description = "Customer token; each order with its shipments, status timeline, tracking and handover "
                    + "codes. Page with limit and the opaque nextCursor.")
    @GetMapping("/v1/orders")
    OrderPage<OrderView> list(@Parameter(description = "Only 'me' is supported") @RequestParam(defaultValue = "me")
                              String customer,
                              @RequestParam(required = false) String cursor,
                              @RequestParam(defaultValue = "20") int limit) {
        if (!"me".equals(customer)) {
            throw new OrdersRuleViolation("orders.customer-me-only", "Use customer=me; staff search is "
                    + "GET /v1/admin/orders");
        }
        return queries.mine(cursor, limit);
    }

    @Operation(summary = "One order (by number or id)",
            description = "The customer sees their own order with handover codes; a seller organisation only its own "
                    + "shipments (totals of those); staff with orders:read everything except the codes.")
    @GetMapping("/v1/orders/{number}")
    OrderView get(@PathVariable String number) {
        return queries.get(number);
    }

    @Operation(summary = "Cancel one shipment of my order (until it is packed)",
            description = "Refunds or releases the shipment's amount and stock (captured shipments are refunded). "
                    + "Pre-order shipments (S272) are charged at checkout and can be cancelled until the window "
                    + "closes, for a full refund including their delivery fee; after the close 409 "
                    + "orders.preorder-closed.")
    @PostMapping("/v1/orders/{number}/shipments/{shipmentId}/cancel")
    OrderView cancel(@PathVariable String number, @PathVariable String shipmentId,
                     @RequestHeader(value = CheckoutController.IDEMPOTENCY_KEY, required = false) String key,
                     @Valid @RequestBody(required = false) CancelRequest body) {
        return shipments.cancelByCustomer(number, shipmentId, body == null ? null : body.reason(), key);
    }

    @Operation(summary = "Answer a missed pre-order delivery: refund or wait for a new date (S272)",
            description = "After orders.preorder.delivery_missed. REFUND cancels the pre-order shipment and refunds "
                    + "it in full (delivery fee included) unless it is already out for delivery (409); WAIT keeps "
                    + "it for the new date delivery-runs schedules. Publishes "
                    + "orders.preorder.missed_delivery_answered. 409 orders.delivery-not-missed before the date "
                    + "was missed.")
    @PostMapping("/v1/orders/{id}/shipments/{shipmentId}/missed-delivery")
    OrderView missedDelivery(@Parameter(description = "Order number or id") @PathVariable String id,
                             @PathVariable String shipmentId, @Valid @RequestBody MissedDeliveryRequest body) {
        return preorders.answerMissedDelivery(id, shipmentId, body.choice());
    }

    @Schema(name = "MissedDeliveryRequest")
    record MissedDeliveryRequest(@NotNull @Schema(description = "REFUND | WAIT") MissedDeliveryChoice choice) {
    }

    @Operation(summary = "Payment split of an order (staff: all recipients; seller: own row)",
            description = "One charge, many recipients: items, shipping, tax, commission, FBA fees, AfroTransact "
                    + "Delivery pickup fees (deliveryFeesMinor: what the seller pays for its pickup-delivery trips; on "
                    + "the AfroTransact row the total earned), Stripe fee share and net per recipient, from "
                    + "payments-service.")
    @GetMapping("/v1/orders/{number}/payment-split")
    PaymentsClient.Split split(@PathVariable String number) {
        return queries.paymentSplit(number);
    }
}
