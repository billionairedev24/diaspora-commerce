package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.payments.adapter.in.web.PaymentsDtos.DeliveredRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.HoldRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.LabelChargeResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.PayoutBalanceResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.PayoutRequestBody;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.PayoutResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ProviderFeeResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.SettlementResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.TransferResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ShipmentSettlementResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.StatementLineResponse;
import com.afrotransact.payments.application.LabelChargeService;
import com.afrotransact.payments.application.PayoutCommand;
import com.afrotransact.payments.application.PayoutService;
import com.afrotransact.payments.application.ProviderFeeService;
import com.afrotransact.payments.application.SellerStatementService;
import com.afrotransact.payments.application.SettlementService;
import com.afrotransact.payments.application.StripeDashboard;
import com.afrotransact.payments.domain.JournalType;
import com.afrotransact.payments.domain.LabelChargeStatus;
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
    private final LabelChargeService labelCharges;
    private final SellerStatementService statements;
    private final StripeDashboard dashboard;

    SettlementPayoutController(SettlementService settlements, PayoutService payouts,
                               ProviderFeeService providerFees, LabelChargeService labelCharges,
                               SellerStatementService statements, StripeDashboard dashboard) {
        this.settlements = settlements;
        this.payouts = payouts;
        this.providerFees = providerFees;
        this.labelCharges = labelCharges;
        this.statements = statements;
        this.dashboard = dashboard;
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

    @Operation(summary = "Stripe Connect transfers: an organisation's (orgId) or one order's (orderId)",
            description = "The tr_… ids to find in the Stripe dashboard (dashboardUrl opens them). Members see their "
                    + "organisation's; staff with payments:read see any, or an order's by orderId alone.")
    @GetMapping("/v1/settlement/transfers")
    List<TransferResponse> transfers(@RequestParam(required = false) String orgId,
                                     @RequestParam(required = false) String orderId,
                                     @RequestParam(defaultValue = "50") int limit) {
        return settlements.transfers(orgId, orderId, limit).stream()
                .map(t -> TransferResponse.of(t, dashboard)).toList();
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

    @Operation(summary = "Shipping label charges of an organisation (labels bought through AfroTransact), newest "
            + "first",
            description = "Org members see their own; staff pass orgId. Each seller-shipped label bought through "
                    + "carrier-gateway is debited from the payable at the carrier's cost plus handling (CHARGED) and "
                    + "credited back once if voided (CREDITED). AfroTransact Retail and FBA / AfroTransact Delivery "
                    + "labels are never charged. Outstanding charges reduce availableMinor until a transfer recovers "
                    + "them. Filters: status (CHARGED | CREDITED), purchased in [from, to), limit (≤ 200).")
    @GetMapping("/v1/payouts/label-charges")
    List<LabelChargeResponse> labelCharges(@RequestParam(required = false) String orgId,
                                           @RequestParam(required = false) LabelChargeStatus status,
                                           @RequestParam(required = false) Instant from,
                                           @RequestParam(required = false) Instant to,
                                           @RequestParam(defaultValue = "200") int limit) {
        return labelCharges.ofOrg(orgId, status, from, to, limit).stream().map(LabelChargeResponse::of).toList();
    }

    @Operation(summary = "Seller statement: movements of the organisation's payable, newest first",
            description = "Sales (captures), refunds, transfers, chargebacks and shipping labels as their own lines "
                    + "(LABEL_CHARGE / LABEL_CREDIT with labelId, shipmentId and trackingNumber). Amounts are signed: "
                    + "credit to the seller positive. Default period: the last 90 days. Org members see their own; "
                    + "staff pass orgId. kind (repeatable) narrows the lines, e.g. kind=LABEL_CHARGE&kind=LABEL_CREDIT "
                    + "for the label charges of the period.")
    @GetMapping("/v1/payouts/statement")
    List<StatementLineResponse> statement(@RequestParam(required = false) String orgId,
                                          @RequestParam(required = false) String currency,
                                          @RequestParam(required = false) Instant from,
                                          @RequestParam(required = false) Instant to,
                                          @RequestParam(defaultValue = "200") int limit,
                                          @RequestParam(required = false) Set<JournalType> kind) {
        return statements.statement(orgId, currency, from, to, limit, kind).stream()
                .map(StatementLineResponse::of).toList();
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

    @Operation(summary = "Payout requests: Finance queue (payouts:approve); members may list their own organisation (orgId)")
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

    @Operation(summary = "Ask Stripe where an in-transit payout stands and close the request (payouts:approve)",
            description = "The manual twin of the payout.paid / payout.failed webhooks. Changes nothing while Stripe "
                    + "still says pending or in transit.")
    @PostMapping("/v1/payouts/requests/{id}/sync")
    PayoutResponse sync(@PathVariable UUID id) {
        return PayoutResponse.of(payouts.syncWithStripe(id));
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
