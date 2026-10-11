package com.afrotransact.orders.adapter.in.web;

import com.afrotransact.orders.application.PayLinkService;
import com.afrotransact.orders.application.PayLinkView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Pre-orders", description = "Community pre-orders (S272): late adds on a customer's behalf, missed "
        + "deliveries")
@RestController
class PayLinkController {

    private final PayLinkService payLinks;

    PayLinkController(PayLinkService payLinks) {
        this.payLinks = payLinks;
    }

    @Operation(summary = "Resolve a late add's pay-now link (gateway-bff, orders:pay-link)",
            description = "The storefront pay page (/orders/{orderNumber}/pay?token=…) shows the order and, while it "
                    + "awaits payment, the client secret of its existing manual-capture PaymentIntent, which the "
                    + "customer confirms with Stripe Elements. The token is sent in the body, never in a URL here. "
                    + "A paid order answers status PAID without a client secret (nothing can be paid twice).")
    @ApiResponse(responseCode = "200", description = "AWAITING_PAYMENT (with clientSecret) or PAID")
    @ApiResponse(responseCode = "404", description = "orders.pay-link-not-found: unknown order or wrong token",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "410", description = "orders.pay-link-expired: the link expired or the order was "
            + "voided", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/v1/service/pay-links/{orderNumber}/lookup")
    PayLinkView lookup(@PathVariable String orderNumber, @Valid @RequestBody PayLinkToken body) {
        return payLinks.lookup(orderNumber, body.token());
    }

    @Operation(summary = "After stripe.confirmPayment on the pay page: place the order once authorised",
            description = "Reads the PaymentIntent from Stripe (through payments) and places the order when it is "
                    + "authorised; idempotent. Answers like lookup (status PAID once placed; failureMessage after a "
                    + "decline, the customer may try again until the link expires).")
    @ApiResponse(responseCode = "200", description = "AWAITING_PAYMENT or PAID")
    @PostMapping("/v1/service/pay-links/{orderNumber}/confirm")
    PayLinkView confirm(@PathVariable String orderNumber, @Valid @RequestBody PayLinkToken body) {
        return payLinks.confirm(orderNumber, body.token());
    }

    @Schema(name = "PayLinkToken", description = "The token of the pay-now link")
    record PayLinkToken(@NotBlank @Size(max = 200) String token) {
    }
}
