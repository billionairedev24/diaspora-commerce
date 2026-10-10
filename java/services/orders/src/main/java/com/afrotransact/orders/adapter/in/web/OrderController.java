package com.afrotransact.orders.adapter.in.web;

import com.afrotransact.orders.adapter.in.web.OrdersDtos.CancelRequest;
import com.afrotransact.orders.application.OrderPage;
import com.afrotransact.orders.application.OrderQueryService;
import com.afrotransact.orders.application.OrderView;
import com.afrotransact.orders.application.ShipmentService;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.port.PaymentsClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

    OrderController(OrderQueryService queries, ShipmentService shipments) {
        this.queries = queries;
        this.shipments = shipments;
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
            description = "Refunds or releases the shipment's amount and stock (captured shipments are refunded).")
    @PostMapping("/v1/orders/{number}/shipments/{shipmentId}/cancel")
    OrderView cancel(@PathVariable String number, @PathVariable String shipmentId,
                     @RequestHeader(value = CheckoutController.IDEMPOTENCY_KEY, required = false) String key,
                     @Valid @RequestBody(required = false) CancelRequest body) {
        return shipments.cancelByCustomer(number, shipmentId, body == null ? null : body.reason(), key);
    }

    @Operation(summary = "Payment split of an order (staff: all recipients; seller: own row)",
            description = "One charge, many recipients: items, shipping, tax, commission, FBA fees, Stripe fee share "
                    + "and net per recipient, from payments-service.")
    @GetMapping("/v1/orders/{number}/payment-split")
    PaymentsClient.Split split(@PathVariable String number) {
        return queries.paymentSplit(number);
    }
}
