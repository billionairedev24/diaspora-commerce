package com.afrotransact.payments.adapter.in.web;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.payments.adapter.in.web.PaymentsDtos.DeliveredRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.HoldRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.PayoutBalanceResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.PayoutRequestBody;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.PayoutResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ProviderFeeResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.SettlementResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ShipmentSettlementResponse;
import com.afrotransact.payments.application.PayoutCommand;
import com.afrotransact.payments.application.PayoutService;
import com.afrotransact.payments.application.ProviderFeeService;
import com.afrotransact.payments.application.SettlementService;
import com.afrotransact.payments.domain.PayoutStatus;
import com.afrotransact.payments.domain.SettlementKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Settlement & payouts", description = "Settlement hold after delivery, Connect transfers and payouts on "
        + "request")
@RestController
class SettlementPayoutController {

    private final SettlementService settlements;
    private final PayoutService payouts;
    private final ProviderFeeService providerFees;

    SettlementPayoutController(SettlementService settlements, PayoutService payouts,
                               ProviderFeeService providerFees) {
        this.settlements = settlements;
        this.payouts = payouts;
        this.providerFees = providerFees;
    }

    @Operation(summary = "Order delivered / service completed: start the settlement hold (payments:service)",
            description = "Each seller's net becomes transferable after the hold (7 days by default). AfroTransact "
                    + "Retail lines are AfroTransact revenue and are never transferred. Idempotent.")
    @PostMapping("/v1/settlements/{orderId}/delivered")
    SettlementResponse delivered(@PathVariable String orderId, @Valid @RequestBody(required = false)
                                 DeliveredRequest body) {
        SettlementKind kind = body == null || body.kind() == null ? SettlementKind.PRODUCT
                : SettlementKind.valueOf(body.kind().trim().toUpperCase(Locale.ROOT));
        return SettlementResponse.of(settlements.markDelivered(orderId, body == null ? null : body.deliveredAt(),
                kind));
    }

    @Operation(summary = "One shipment delivered: start that shipment's settlement hold (payments:service)",
            description = "The seller net captured for the shipment becomes transferable after the hold, without "
                    + "waiting for the order's other shipments; the order hold transfers whatever is left. "
                    + "Idempotent per shipment.")
    @PostMapping("/v1/settlements/{orderId}/shipments/{shipmentId}/delivered")
    ShipmentSettlementResponse shipmentDelivered(@PathVariable String orderId, @PathVariable String shipmentId,
                                                 @Valid @RequestBody(required = false) DeliveredRequest body) {
        SettlementKind kind = body == null || body.kind() == null ? SettlementKind.PRODUCT
                : SettlementKind.valueOf(body.kind().trim().toUpperCase(Locale.ROOT));
        return ShipmentSettlementResponse.of(settlements.markShipmentDelivered(orderId, shipmentId,
                body == null ? null : body.deliveredAt(), kind));
    }

    @Operation(summary = "Shipment settlement holds of an order")
    @GetMapping("/v1/settlements/{orderId}/shipments")
    List<ShipmentSettlementResponse> shipmentSettlements(@PathVariable String orderId) {
        return settlements.shipments(orderId).stream().map(ShipmentSettlementResponse::of).toList();
    }

    @Operation(summary = "Settlement hold of an order")
    @GetMapping("/v1/settlements/{orderId}")
    SettlementResponse settlement(@PathVariable String orderId) {
        return SettlementResponse.of(settlements.get(orderId));
    }

    @Operation(summary = "Your balance: available to request, on hold per order (with release date), requested / "
            + "on the way, provider fees, and whether payouts are paused (payoutsPaused + payoutsPausedReason)",
            description = "AfroTransact Retail is not a payable organisation: its balance is always zero and "
                    + "canRequestToday false.")
    @GetMapping("/v1/payouts/balance")
    PayoutBalanceResponse balance(@RequestParam(required = false) String orgId,
                                  @RequestParam(required = false) String currency) {
        return PayoutBalanceResponse.of(payouts.balance(orgId, currency));
    }

    @Operation(summary = "Provider fees of an organisation (late provider cancellations), newest first",
            description = "Org members see their own; staff pass orgId. Fees are debited from the connected account "
                    + "(Stripe account debit) and reduce availableMinor; OUTSTANDING ones are retried.")
    @GetMapping("/v1/payouts/provider-fees")
    List<ProviderFeeResponse> providerFees(@RequestParam(required = false) String orgId) {
        return providerFees.ofOrg(orgId).stream().map(ProviderFeeResponse::of).toList();
    }

    @Operation(summary = "Request a payout (owner / finance roles, payouts:request)",
            description = "Minimum USD 25, one request per day, never more than available; no fee. AfroTransact "
                    + "Retail is refused with 422 payments.retail-no-payouts.")
    @PostMapping("/v1/payouts/requests")
    @ResponseStatus(HttpStatus.CREATED)
    PayoutResponse request(@RequestHeader(value = PaymentController.IDEMPOTENCY_KEY, required = false)
                           String idempotencyKey, @Valid @RequestBody PayoutRequestBody body) {
        return PayoutResponse.of(payouts.request(new PayoutCommand(body.orgId(), body.amountMinor(), Boolean.TRUE.equals(body.all()),
                body.currency()), idempotencyKey));
    }

    @Operation(summary = "Finance queue of payout requests (payouts:approve)")
    @GetMapping("/v1/payouts/requests")
    List<PayoutResponse> queue(@RequestParam(required = false) PayoutStatus status,
                               @RequestParam(required = false) String orgId,
                               @RequestParam(defaultValue = "100") int limit) {
        return payouts.queue(status, orgId, limit).stream().map(PayoutResponse::of).toList();
    }

    @Operation(summary = "One payout request")
    @GetMapping("/v1/payouts/requests/{id}")
    PayoutResponse payout(@PathVariable UUID id) {
        return PayoutResponse.of(payouts.get(id));
    }

    @Operation(summary = "Approve: creates the Stripe payout on the connected account (payouts:approve)")
    @PostMapping("/v1/payouts/requests/{id}/approve")
    PayoutResponse approve(@PathVariable UUID id) {
        return PayoutResponse.of(payouts.approve(id));
    }

    @Operation(summary = "Put a request on hold with a reason (payouts:approve)")
    @PostMapping("/v1/payouts/requests/{id}/hold")
    PayoutResponse hold(@PathVariable UUID id, @Valid @RequestBody HoldRequest body) {
        return PayoutResponse.of(payouts.hold(id, body.reason()));
    }

    @Operation(summary = "Release a held request back to the queue (payouts:approve)")
    @PostMapping("/v1/payouts/requests/{id}/release")
    PayoutResponse release(@PathVariable UUID id) {
        return PayoutResponse.of(payouts.release(id));
    }
}
