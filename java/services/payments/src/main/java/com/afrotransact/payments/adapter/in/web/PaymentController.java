package com.afrotransact.payments.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.adapter.in.web.PaymentsDtos.CancelRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.CaptureRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.CaptureResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.CreatePaymentRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.OffSessionChargeRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.OrderRefundRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.PaymentResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.RefundRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.RefundResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ReleaseRequest;
import com.afrotransact.payments.application.CaptureCommand;
import com.afrotransact.payments.application.CaptureResult;
import com.afrotransact.payments.application.CreatePaymentCommand;
import com.afrotransact.payments.application.OffSessionChargeService;
import com.afrotransact.payments.application.OffSessionCommand;
import com.afrotransact.payments.application.OrderRefundCommand;
import com.afrotransact.payments.application.OrderRefundService;
import com.afrotransact.payments.application.PaymentResult;
import com.afrotransact.payments.application.PaymentService;
import com.afrotransact.payments.application.RefundCommand;
import com.afrotransact.payments.application.RefundResult;
import com.afrotransact.payments.application.ReleaseResult;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Payment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments", description = "Order payment lifecycle for the orders saga (SERVICE principals with "
        + "payments:service; Idempotency-Key on every write)")
@RestController
class PaymentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentService payments;
    private final OffSessionChargeService offSession;
    private final OrderRefundService orderRefunds;

    PaymentController(PaymentService payments, OffSessionChargeService offSession, OrderRefundService orderRefunds) {
        this.payments = payments;
        this.offSession = offSession;
        this.orderRefunds = orderRefunds;
    }

    @Operation(summary = "Create the order's PaymentIntent (manual capture) and return its client secret",
            description = "Stripe idempotency key = <orderId>:create-intent. Replays (same order, same lines) return "
                    + "200 with replayed=true; a different amount or lines for the same order is 409.")
    @PostMapping("/v1/payments")
    ResponseEntity<PaymentResponse> create(
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest body) {
        String currency = body.currency();
        PaymentResult result = payments.create(new CreatePaymentCommand(body.orderId(), body.customerId(),
                Money.of(body.amountMinor(), currency), body.paymentMethodTypes(), body.placedAt(), body.metadata(),
                body.lines().stream().map(l -> l.toLine(currency)).toList(), body.shopperId(),
                Boolean.TRUE.equals(body.saveForOffSession()), Boolean.TRUE.equals(body.offSession()),
                body.paymentLinkHours()), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(PaymentResponse.of(result.payment(), result.replayed(),
                        payments.link(result.payment().id()).orElse(null)));
    }

    @Operation(summary = "Get a payment", description = "refresh=true reads the intent's status from Stripe first "
            + "(confirmation, 3-D Secure, authorisation).")
    @GetMapping("/v1/payments/{id}")
    PaymentResponse get(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean refresh) {
        return PaymentResponse.of(payments.get(id, refresh), false, payments.link(id).orElse(null));
    }

    @Operation(summary = "Get the payment of an order",
            description = "Services with payments:service and staff with payments:read read any order's payment. "
                    + "Shoppers (CUSTOMER tokens) read only their own orders' payments; another shopper's order is "
                    + "403 payments.not-your-order (S241).")
    @GetMapping("/v1/payments")
    PaymentResponse byOrder(@RequestParam String orderId) {
        return PaymentResponse.of(payments.getByOrder(orderId), false);
    }

    @Operation(summary = "Capture one shipment and post its journal",
            description = "Stripe amount_to_capture = the lines' items + shipping + tax (multicapture; final_capture "
                    + "releases the rest). Posts Dr 1000 Stripe clearing / Dr 5000 Stripe fees, Cr 2000 seller "
                    + "payables, 2100 sales tax, 4000 commission, 4100 FBA fees, 4200 shipping. Idempotent per "
                    + "(payment, shipmentId).")
    @PostMapping("/v1/payments/{id}/capture")
    ResponseEntity<CaptureResponse> capture(
            @PathVariable UUID id,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody CaptureRequest body) {
        Money amount = null;
        if (body.amountMinor() != null) {
            amount = Money.of(body.amountMinor(), payments.get(id, false).currency());
        }
        CaptureResult result = payments.capture(id, new CaptureCommand(body.shipmentId(), body.lineIds(), amount,
                Boolean.TRUE.equals(body.finalCapture())), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(CaptureResponse.of(result));
    }

    @Operation(summary = "Cancel an uncaptured payment (releases the authorisation)",
            description = "Refused (409 payments.already-captured) once something was captured: use /release.")
    @PostMapping("/v1/payments/{id}/cancel")
    PaymentResponse cancel(@PathVariable UUID id,
                           @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
                           @Valid @RequestBody(required = false) CancelRequest body) {
        Payment p = payments.cancel(id, body == null ? null : body.reason(), idempotencyKey);
        return PaymentResponse.of(p, false);
    }

    @Operation(summary = "Release the remaining authorisation after a partial (deposit) capture",
            description = "For a booking cancelled or a no-show after the deposit capture: the uncaptured rest of the "
                    + "hold is released (Stripe: cancelling a requires_capture multicapture intent with captures "
                    + "releases the remainder; nothing to do at Stripe when the card already released it at the "
                    + "partial capture). The payment becomes CAPTURED for what was captured (releasedMinor = the "
                    + "rest); without captures it is cancelled. Uncaptured lines can later be charged off-session. "
                    + "No journal is posted: authorisations never reach the ledger. Idempotent (Idempotency-Key; a "
                    + "payment already released replays 200).")
    @PostMapping("/v1/payments/{id}/release")
    ResponseEntity<PaymentResponse> releaseAuthorization(
            @PathVariable UUID id,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody(required = false) ReleaseRequest body) {
        ReleaseResult result = payments.release(id, body == null ? null : body.reason(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.OK).body(PaymentResponse.of(result.payment(), result.replayed()));
    }

    @Operation(summary = "Charge lines off-session on the customer's saved card (long-dated bookings)",
            description = "After the order's authorisation was released or lapsed (cards hold ~7 days), creates and "
                    + "confirms a manual-capture PaymentIntent off_session on a saved card of the customer: a new "
                    + "payment of the same order (parentPaymentId = this payment), captured / refunded / settled "
                    + "through the usual endpoints. Status AUTHORIZED = ready to capture; REQUIRES_ACTION = the bank "
                    + "requires authentication (SCA): payments.intent.action_required is published and the customer "
                    + "confirms on-session with clientSecret; FAILED = declined (retry with another card and a new "
                    + "Idempotency-Key). Idempotent per Idempotency-Key.")
    @PostMapping("/v1/payments/{id}/off-session-charges")
    ResponseEntity<PaymentResponse> chargeOffSession(
            @PathVariable UUID id,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody OffSessionChargeRequest body) {
        PaymentResult result = offSession.charge(id, new OffSessionCommand(body.customerId(), body.paymentMethodId(),
                body.lineIds()), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(PaymentResponse.of(result.payment(), result.replayed()));
    }

    @Operation(summary = "Off-session charges of a payment, oldest first")
    @GetMapping("/v1/payments/{id}/off-session-charges")
    List<PaymentResponse> offSessionCharges(@PathVariable UUID id) {
        return payments.offSessionCharges(id).stream().map(p -> PaymentResponse.of(p, false)).toList();
    }

    @Operation(summary = "Refund captured lines (partially)",
            description = "Reverses the seller payable, tax and — per fee policy — commission of each line; FBA "
                    + "fees and Stripe fee shares are kept. If the seller's net was already transferred, a Stripe "
                    + "transfer reversal takes it back. AfroTransact Delivery lines (S255) follow `fault`: SELLER "
                    + "(default) keeps the seller's pickup-delivery fee and may refund the delivery charge; CUSTOMER "
                    + "returns the fee to the seller pro rata to items refunded and refuses a delivery-charge refund "
                    + "(422 payments.delivery-charge-not-refundable).")
    @PostMapping("/v1/payments/{id}/refunds")
    ResponseEntity<RefundResponse> refund(
            @PathVariable UUID id,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody RefundRequest body) {
        String currency = payments.get(id, false).currency();
        RefundResult result = payments.refund(id, new RefundCommand(body.lines().stream()
                .map(l -> new RefundCommand.Line(l.lineId(), money(l.itemsMinor(), currency),
                        money(l.shippingMinor(), currency), money(l.taxMinor(), currency))).toList(),
                body.reason(), body.fault()), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(RefundResponse.of(result.refund(), result.reversals(), result.replayed()));
    }

    @Operation(summary = "Refund by order and line ids (payments:service; e.g. trust-ops claims)",
            description = "Finds the order's payment and spreads amountMinor (default: everything still refundable) "
                    + "over the lines' remaining items, shipping and tax with the largest-remainder method, then "
                    + "refunds like POST /v1/payments/{id}/refunds. Idempotent per Idempotency-Key. With fault "
                    + "CUSTOMER the delivery charge of AfroTransact Delivery lines is left out of the spread.")
    @PostMapping("/v1/orders/{orderId}/refunds")
    ResponseEntity<RefundResponse> refundByOrder(
            @PathVariable String orderId,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody OrderRefundRequest body) {
        RefundResult result = orderRefunds.refundByOrder(new OrderRefundCommand(orderId, body.lineIds(),
                body.amountMinor(), body.reason(), body.fault()), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(RefundResponse.of(result.refund(), result.reversals(), result.replayed()));
    }

    @Operation(summary = "Captures of a payment")
    @GetMapping("/v1/payments/{id}/captures")
    List<CaptureResponse> captures(@PathVariable UUID id) {
        Payment p = payments.get(id, false);
        return payments.captures(id).stream()
                .map(c -> CaptureResponse.of(c, p.orderId(), p.status().name(), false)).toList();
    }

    @Operation(summary = "Refunds of a payment")
    @GetMapping("/v1/payments/{id}/refunds")
    List<RefundResponse> refunds(@PathVariable UUID id) {
        return payments.refunds(id).stream().map(r -> RefundResponse.of(r, List.of(), false)).toList();
    }

    private static Money money(Long minor, String currency) {
        return Money.of(minor == null ? 0 : minor, currency);
    }
}
