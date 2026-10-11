package com.afrotransact.orders.adapter.in.web;

import java.util.List;
import java.util.Locale;

import com.afrotransact.orders.application.LatePreorderCommand;
import com.afrotransact.orders.application.LatePreorderService;
import com.afrotransact.orders.application.LatePreorderView;
import com.afrotransact.orders.domain.ShipTo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Pre-orders", description = "Community pre-orders (S272): late adds on a customer's behalf, missed "
        + "deliveries")
@RestController
class LatePreorderController {

    private final LatePreorderService lateAdds;

    LatePreorderController(LatePreorderService lateAdds) {
        this.lateAdds = lateAdds;
    }

    @Operation(summary = "Create a late pre-order on a customer's behalf (delivery-runs-service, orders:on-behalf)",
            description = "Customer service adds an order to a pre-order window that is OPEN (assisted order) or "
                    + "CLOSED and not delivered. Items are priced from the catalog and must be pre-orders of the "
                    + "window at the address. SAVED_CARD charges the customer's default saved card off-session "
                    + "(status PLACED, or AWAITING_PAYMENT when the bank asks the customer to authenticate); "
                    + "PAYMENT_LINK creates the order's PaymentIntent (manual capture) and answers a pay-now link "
                    + "to AfroTransact's own pay page (paymentLinkUrl = <storefront>/orders/<number>/pay?token=<signed "
                    + "token>; status AWAITING_PAYMENT, orders.preorder.payment_link_created for the SMS), where the "
                    + "customer pays with Stripe Elements; voided and its intent cancelled when the link expires "
                    + "unpaid. The order has channel AGENT_LATE and tag LATE_ADD and records the staff actor, reason "
                    + "and note. The same Idempotency-Key returns the same order; another key for the same customer, "
                    + "window and items within 10 minutes is 409 orders.late-add-duplicate.")
    @ApiResponse(responseCode = "201", description = "Created (placed or awaiting payment)")
    @ApiResponse(responseCode = "200", description = "Replay of the same Idempotency-Key")
    @ApiResponse(responseCode = "409", description = "orders.late-add-duplicate, orders.idempotency-key-reused",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "orders.preorder-not-open, orders.preorder-not-eligible, "
            + "orders.payment-failed, orders.late-add-payment-disabled, orders.phone-not-confirmed",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/v1/service/orders/late-preorder")
    ResponseEntity<LatePreorderView> create(
            @Parameter(description = "Required; the same key returns the same order")
            @RequestHeader(CheckoutController.IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody LatePreorderRequest body) {
        LatePreorderView view = lateAdds.create(body.toCommand(idempotencyKey));
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    @Schema(name = "LatePreorderRequest", description = "A late pre-order add taken by customer service")
    record LatePreorderRequest(
            @NotBlank @Size(max = 80) String customerId,
            @NotBlank @Size(max = 80) String windowId,
            @NotNull @Valid Address address,
            @NotEmpty @Size(max = 50) List<@Valid Item> items,
            @Schema(description = "DOOR | PICKUP; default door when the window offers it") String fulfilment,
            @NotNull @Schema(description = "SAVED_CARD | PAYMENT_LINK") LatePreorderCommand.Payment payment,
            @NotBlank @Size(max = 80) @Schema(description = "Customer called | Chat | Driver report | Other")
            String reason,
            @Size(max = 1000) String note,
            @NotBlank @Size(max = 120) @Schema(description = "Staff subject who took the order") String actorId,
            @Schema(description = "Staff confirmed the customer's phone number") Boolean phoneConfirmed,
            @Valid @Schema(description = "settings.lateAdd of the window's payment setup") LateAdd lateAdd) {

        LatePreorderCommand toCommand(String idempotencyKey) {
            return new LatePreorderCommand(idempotencyKey, customerId, windowId, address.toShipTo(),
                    items.stream().map(i -> new LatePreorderCommand.Item(i.atin(), i.qty())).toList(),
                    fulfilment == null ? null : fulfilment.trim().toUpperCase(Locale.ROOT), payment, reason, note,
                    actorId, phoneConfirmed, lateAdd == null ? null
                    : new LatePreorderCommand.LateAddSettings(lateAdd.savedCard(), lateAdd.paymentLink(),
                    lateAdd.linkExpiryHours()));
        }
    }

    @Schema(name = "LatePreorderItem")
    record Item(@NotBlank @Size(max = 64) String atin, @Min(1) @Max(99) int qty) {
    }

    @Schema(name = "LatePreorderAddress", description = "Saved or new (verified) delivery address snapshot")
    record Address(@Size(max = 80) String addressId, @Size(max = 120) String fullName, @Size(max = 40) String phone,
                   @NotBlank @Size(max = 200) String line1, @Size(max = 200) String line2,
                   @NotBlank @Size(max = 120) String city,
                   @NotBlank @Size(max = 40) @Schema(description = "State / region code") String state,
                   @NotBlank @Size(max = 20) String postalCode,
                   @Size(min = 2, max = 2) String country, @Size(max = 240) String instructions, Double lat,
                   Double lng) {

        ShipTo toShipTo() {
            return new ShipTo(addressId, fullName, phone, line1, line2, city, state, postalCode,
                    country == null ? "US" : country.toUpperCase(Locale.ROOT), instructions, lat, lng);
        }
    }

    @Schema(name = "LatePreorderSettings", description = "Back-office late-add payment setup")
    record LateAdd(Boolean savedCard, Boolean paymentLink, @Min(1) @Max(24) Integer linkExpiryHours) {
    }
}
