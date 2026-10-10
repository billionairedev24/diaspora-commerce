package com.afrotransact.orders.adapter.in.web;

import com.afrotransact.orders.adapter.in.web.OrdersDtos.CheckoutRequest;
import com.afrotransact.orders.application.CheckoutService;
import com.afrotransact.orders.application.CheckoutView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Checkout", description = "Checkout saga (Temporal): reserve stock, authorise payment, split into shipments")
@RestController
class CheckoutController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CheckoutService checkout;

    CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    @Operation(summary = "Place an order from the cart",
            description = "Starts the checkout saga: validate the cart and address (markets switched off block "
                    + "checkout), snapshot prices and tax, reserve AfroTransact stock (all or nothing), create the "
                    + "PaymentIntent (manual capture) and store the order + shipments as pending payment. Returns the "
                    + "client secret to confirm with Stripe; the order is placed when payments reports the "
                    + "authorisation (15 minutes, then everything is released). The same Idempotency-Key returns the "
                    + "same order. POST /v1/orders is an alias used by gateway-bff.")
    @ApiResponse(responseCode = "201", description = "Order created; awaiting payment (or already placed)")
    @ApiResponse(responseCode = "202", description = "Still processing; poll GET /v1/checkout/{orderNumber}")
    @ApiResponse(responseCode = "409", description = "orders.insufficient-stock / orders.idempotency-key-reused",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "orders.cart-empty, orders.cart-not-checkoutable, "
            + "orders.market-disabled, orders.address-not-found, orders.invalid-delivery-selection",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping({"/v1/checkout", "/v1/orders"})
    ResponseEntity<CheckoutView> checkout(
            @Parameter(description = "Required; the same key returns the same order")
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @Valid @RequestBody CheckoutRequest body) {
        CheckoutView view = checkout.checkout(body.toCommand(idempotencyKey, Bearer.of(authorization)));
        return ResponseEntity.status("processing".equals(view.status()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED)
                .body(view);
    }

    @Operation(summary = "Checkout status of one of my orders")
    @GetMapping("/v1/checkout/{orderNumber}")
    CheckoutView status(@PathVariable String orderNumber) {
        return checkout.status(orderNumber);
    }

    @Operation(summary = "I confirmed the payment (Stripe.js / PaymentSheet)",
            description = "Orders asks payments for the intent status and continues the saga without waiting for "
                    + "the webhook.")
    @PostMapping("/v1/checkout/{orderNumber}/confirm")
    CheckoutView confirm(@PathVariable String orderNumber) {
        return checkout.confirmPayment(orderNumber);
    }
}
