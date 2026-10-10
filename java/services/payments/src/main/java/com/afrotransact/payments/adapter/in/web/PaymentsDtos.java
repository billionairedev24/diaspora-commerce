package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.afrotransact.payments.application.CaptureResult;
import com.afrotransact.payments.application.OrderSplit;
import com.afrotransact.payments.application.RefundResult;
import com.afrotransact.payments.application.WalletMethod;
import com.afrotransact.payments.domain.AccountBalance;
import com.afrotransact.payments.domain.Capture;
import com.afrotransact.payments.domain.CommissionOverride;
import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.Fulfilment;
import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.LineSplit;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.OrderLine;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentLine;
import com.afrotransact.payments.domain.PayoutBalance;
import com.afrotransact.payments.domain.PayoutRequest;
import com.afrotransact.payments.domain.ReconciliationReport;
import com.afrotransact.payments.domain.RecipientShare;
import com.afrotransact.payments.domain.Refund;
import com.afrotransact.payments.domain.Settlement;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the payments API. Amounts are integer minor units with an ISO currency. */
final class PaymentsDtos {

    static final String CURRENCY = "[A-Za-z]{3}";

    private PaymentsDtos() {
    }

    // ------------------------------------------------------------------ payments

    @Schema(description = "Create the PaymentIntent (manual capture) of an order")
    record CreatePaymentRequest(
            @NotBlank @Size(max = 80) String orderId,
            @Size(max = 120) @Schema(description = "Stripe customer id (cus_…) of the shopper") String customerId,
            @NotNull @Min(1) Long amountMinor,
            @NotBlank @Pattern(regexp = CURRENCY) String currency,
            @Schema(description = "Explicit types (card, us_bank_account, cashapp, affirm, link); empty = automatic "
                    + "payment methods") List<String> paymentMethodTypes,
            @Schema(description = "When the order was placed; selects the fee schedule version") Instant placedAt,
            Map<String, String> metadata,
            @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {
    }

    @Schema(description = "One order line: who sells it and what the shopper pays for it")
    record LineRequest(
            @NotBlank @Size(max = 80) String lineId,
            @NotBlank @Size(max = 80) String sellerOrg,
            @Size(max = 80) String category,
            @NotNull Fulfilment fulfilment,
            @Min(1) int quantity,
            @NotNull @PositiveOrZero Long itemsMinor,
            @PositiveOrZero Long shippingMinor,
            @PositiveOrZero Long taxMinor,
            @Size(max = 40) String taxJurisdiction) {

        OrderLine toLine(String currency) {
            return new OrderLine(lineId, sellerOrg, category, fulfilment, quantity, Money.of(itemsMinor, currency),
                    Money.of(shippingMinor == null ? 0 : shippingMinor, currency),
                    Money.of(taxMinor == null ? 0 : taxMinor, currency), taxJurisdiction);
        }
    }

    record PaymentResponse(UUID id, String orderId, String status, String stripePaymentIntentId,
                           @Schema(description = "For Stripe.js / PaymentSheet confirmation; only returned to the "
                                   + "calling service") String clientSecret,
                           long amountMinor, long capturedMinor, long refundedMinor, String currency,
                           UUID feeScheduleId, Instant placedAt, Instant authorizedAt,
                           String failureMessage, boolean replayed, List<LineResponse> lines) {

        static PaymentResponse of(Payment p, boolean replayed) {
            return new PaymentResponse(p.id(), p.orderId(), p.status().name(), p.stripePaymentIntentId(),
                    p.clientSecret(), p.amount().minor(), p.captured().minor(), p.refunded().minor(), p.currency(),
                    p.feeScheduleId(), p.placedAt(), p.authorizedAt(), p.failureMessage(), replayed,
                    p.lines().stream().map(LineResponse::of).toList());
        }
    }

    record LineResponse(String lineId, String sellerOrg, String category, String fulfilment, int quantity,
                        long itemsMinor, long shippingMinor, long taxMinor, String taxJurisdiction, UUID captureId,
                        long commissionMinor, long fbaFeeMinor, long refundedMinor) {

        static LineResponse of(PaymentLine pl) {
            OrderLine l = pl.line();
            return new LineResponse(l.lineId(), l.sellerOrg(), l.category(), l.fulfilment().name(), l.quantity(),
                    l.items().minor(), l.shipping().minor(), l.tax().minor(), l.taxJurisdiction(), pl.captureId(),
                    pl.commission().minor(), pl.fbaFee().minor(), pl.refundedTotal().minor());
        }
    }

    @Schema(description = "Capture one shipment (carrier acceptance)")
    record CaptureRequest(
            @NotBlank @Size(max = 80) String shipmentId,
            @Schema(description = "Lines in the shipment; empty = every line not captured yet") List<String> lineIds,
            @Schema(description = "Expected amount; must equal the lines' items + shipping + tax") Long amountMinor,
            @Schema(description = "Release the rest of the authorisation after this capture") Boolean finalCapture) {
    }

    record CaptureResponse(UUID id, UUID paymentId, String orderId, String shipmentId, long amountMinor,
                           long stripeFeeMinor, boolean feeEstimated, String currency, String stripeChargeId,
                           boolean finalCapture, List<String> lineIds, UUID journalId, Instant capturedAt,
                           String paymentStatus, boolean replayed) {

        static CaptureResponse of(Capture c, String orderId, String paymentStatus, boolean replayed) {
            return new CaptureResponse(c.id(), c.paymentId(), orderId, c.shipmentId(), c.amount().minor(),
                    c.stripeFee().minor(), c.feeEstimated(), c.amount().currency(), c.stripeChargeId(),
                    c.finalCapture(), c.lineIds(), c.journalId(), c.capturedAt(), paymentStatus, replayed);
        }

        static CaptureResponse of(CaptureResult r) {
            return of(r.capture(), r.payment().orderId(), r.payment().status().name(), r.replayed());
        }
    }

    record CancelRequest(@Size(max = 500) String reason) {
    }

    @Schema(description = "Line-level partial refund")
    record RefundRequest(@NotEmpty @Size(max = 200) List<@Valid RefundLineRequest> lines,
                         @Size(max = 500) String reason) {
    }

    record RefundLineRequest(@NotBlank String lineId, @PositiveOrZero Long itemsMinor,
                             @PositiveOrZero Long shippingMinor, @PositiveOrZero Long taxMinor) {
    }

    record RefundResponse(UUID id, UUID paymentId, String orderId, String stripeRefundId, long amountMinor,
                          String currency, String status, String reason, List<RefundLineResponse> lines,
                          List<RefundResult.Reversal> transferReversals, Instant createdAt, Instant settledAt,
                          boolean replayed) {

        static RefundResponse of(Refund r, List<RefundResult.Reversal> reversals, boolean replayed) {
            return new RefundResponse(r.id(), r.paymentId(), r.orderId(), r.stripeRefundId(), r.amount().minor(),
                    r.amount().currency(), r.status().name(), r.reason(), r.lines().stream()
                    .map(l -> new RefundLineResponse(l.lineId(), l.sellerOrg(), l.items().minor(),
                            l.shipping().minor(), l.tax().minor(), l.commissionReversed().minor())).toList(),
                    reversals, r.createdAt(), r.settledAt(), replayed);
        }
    }

    record RefundLineResponse(String lineId, String sellerOrg, long itemsMinor, long shippingMinor, long taxMinor,
                              long commissionReversedMinor) {
    }

    // ------------------------------------------------------------------ split & fees

    record SplitResponse(String orderId, UUID paymentId, int feeScheduleVersion, String feeSource,
                         long chargeMinor, long stripeFeeMinor, long taxCollectedMinor, String currency,
                         List<RecipientResponse> recipients, List<LineSplitResponse> lines) {

        static SplitResponse of(OrderSplit s) {
            return new SplitResponse(s.orderId(), s.paymentId(), s.feeScheduleVersion(), s.feeSource(),
                    s.split().charge().minor(), s.split().stripeFee().minor(), s.split().totalTax().minor(),
                    s.split().currency(), s.split().recipients().stream().map(RecipientResponse::of).toList(),
                    s.split().lines().stream().map(LineSplitResponse::of).toList());
        }
    }

    @Schema(description = "One recipient. For sellers commission, FBA fees and Stripe fee are deductions; for the "
            + "AfroTransact platform row they are the totals earned. Tax is collected and remitted by AfroTransact.")
    record RecipientResponse(String recipient, String kind, long itemsMinor, long shippingMinor, long taxMinor,
                             Map<String, Long> taxByJurisdiction, long commissionMinor, long fbaFeesMinor,
                             long stripeFeeMinor, long netMinor) {

        static RecipientResponse of(RecipientShare r) {
            Map<String, Long> tax = new TreeMap<>();
            r.taxByJurisdiction().forEach((k, v) -> tax.put(k, v.minor()));
            return new RecipientResponse(r.recipientId(), r.kind().name(), r.items().minor(), r.shipping().minor(),
                    r.tax().minor(), tax, r.commission().minor(), r.fbaFees().minor(), r.stripeFee().minor(),
                    r.net().minor());
        }
    }

    record LineSplitResponse(String lineId, String sellerOrg, int commissionBps, long commissionMinor,
                             long fbaFeeMinor, boolean shippingToPlatform) {

        static LineSplitResponse of(LineSplit l) {
            return new LineSplitResponse(l.lineId(), l.sellerOrg(), l.commissionBps(), l.commission().minor(),
                    l.fbaFee().minor(), l.shippingToPlatform());
        }
    }

    @Schema(description = "A new fee-schedule version (Super Admin / Finance)")
    record FeeScheduleRequest(
            @Schema(description = "Takes effect for orders placed from this time; default now, never in the past")
            Instant effectiveFrom,
            @Pattern(regexp = CURRENCY) String currency,
            @NotNull @Schema(description = "Commission per category in basis points (800 = 8 %)")
            Map<String, @Min(0) @Max(10000) Integer> categoryBps,
            @Min(0) @Max(10000) int defaultCommissionBps,
            List<@Valid OverrideRequest> overrides,
            @Min(0) @Max(10000) int cardPctBps,
            @PositiveOrZero long cardFixedMinor,
            @PositiveOrZero long fbaPerUnitMinor,
            Boolean reverseCommissionOnRefund,
            @Size(max = 500) String note) {
    }

    record OverrideRequest(@NotBlank String orgId, String category, @Min(0) @Max(10000) int commissionBps) {

        CommissionOverride toOverride() {
            return new CommissionOverride(orgId, category, commissionBps);
        }
    }

    record FeeScheduleResponse(UUID id, int version, Instant effectiveFrom, String currency,
                               Map<String, Integer> categoryBps, int defaultCommissionBps,
                               List<OverrideRequest> overrides, int cardPctBps, long cardFixedMinor,
                               long fbaPerUnitMinor, boolean reverseCommissionOnRefund, String note, String createdBy,
                               Instant createdAt) {

        static FeeScheduleResponse of(FeeSchedule s) {
            return new FeeScheduleResponse(s.id(), s.version(), s.effectiveFrom(), s.currency(),
                    new TreeMap<>(s.categoryBps()), s.defaultCommissionBps(), s.overrides().stream()
                    .map(o -> new OverrideRequest(o.orgId(), o.category(), o.commissionBps())).toList(),
                    s.cardPctBps(), s.cardFixed().minor(), s.fbaPerUnit().minor(), s.reverseCommissionOnRefund(),
                    s.note(), s.createdBy(), s.createdAt());
        }
    }

    // ------------------------------------------------------------------ ledger

    record BalanceResponse(String account, String code, String key, String title, String type, String currency,
                           long debitsMinor, long creditsMinor, long balanceMinor) {

        static BalanceResponse of(AccountBalance b) {
            return new BalanceResponse(b.account().display(), b.account().chart().code(), b.account().key(),
                    b.account().chart().title(), b.account().chart().type().name(), b.currency(), b.debits(),
                    b.credits(), b.balance());
        }
    }

    record JournalResponse(UUID id, String key, String type, String orderId, String paymentId, String stripeRef,
                           String description, Instant occurredAt, List<JournalLineResponse> lines) {

        static JournalResponse of(Journal j) {
            return new JournalResponse(j.id(), j.key(), j.type().name(), j.orderId(), j.paymentId(), j.stripeRef(),
                    j.description(), j.occurredAt(), j.lines().stream().map(l -> new JournalLineResponse(
                            l.account().display(), l.account().chart().title(), l.side().name(),
                            l.amount().minor(), l.amount().currency(), l.orgId())).toList());
        }
    }

    record JournalLineResponse(String account, String title, String side, long amountMinor, String currency,
                               String orgId) {
    }

    // ------------------------------------------------------------------ settlement & payouts

    record DeliveredRequest(@Schema(description = "Delivery or service completion time; default now")
                            Instant deliveredAt,
                            @Schema(description = "PRODUCT (delivery) or SERVICE (booking completed)")
                            String kind) {
    }

    record SettlementResponse(String orderId, String kind, Instant deliveredAt, Instant releaseAt, String status) {

        static SettlementResponse of(Settlement s) {
            return new SettlementResponse(s.orderId(), s.kind().name(), s.deliveredAt(), s.releaseAt(),
                    s.status().name());
        }
    }

    record PayoutBalanceResponse(String orgId, String currency, long availableMinor, long onHoldMinor,
                                 List<HeldOrderResponse> onHold, long requestedOrInTransitMinor, long paidMinor,
                                 long minimumPayoutMinor, boolean canRequestToday) {

        static PayoutBalanceResponse of(PayoutBalance b) {
            return new PayoutBalanceResponse(b.orgId(), b.currency(), b.available().minor(), b.onHoldTotal().minor(),
                    b.onHold().stream().map(h -> new HeldOrderResponse(h.orderId(), h.amount().minor(),
                            h.releaseAt())).toList(), b.inFlight().minor(), b.paid().minor(), b.minimum().minor(),
                    b.canRequestToday());
        }
    }

    @Schema(description = "Net on settlement hold for one order; releaseAt null until delivered / completed")
    record HeldOrderResponse(String orderId, long amountMinor, Instant releaseAt) {
    }

    @Schema(description = "Request a payout: an amount, or all=true for everything available")
    record PayoutRequestBody(@Schema(description = "Staff only; members always request for their own org")
                             String orgId,
                             @Min(1) Long amountMinor,
                             Boolean all,
                             @Pattern(regexp = CURRENCY) String currency) {
    }

    record PayoutResponse(UUID id, String orgId, long amountMinor, String currency, String status,
                          String requestedBy, Instant requestedAt, LocalDate requestDay, String decidedBy,
                          Instant decidedAt, String holdReason, String stripePayoutId, String failureMessage) {

        static PayoutResponse of(PayoutRequest r) {
            return new PayoutResponse(r.id(), r.orgId(), r.amount().minor(), r.amount().currency(),
                    r.status().name(), r.requestedBy(), r.requestedAt(), r.requestDay(), r.decidedBy(),
                    r.decidedAt(), r.holdReason(), r.stripePayoutId(), r.failureMessage());
        }
    }

    record HoldRequest(@NotBlank @Size(max = 500) String reason) {
    }

    record ConnectedAccountRequest(@NotBlank @Size(max = 80) String stripeAccountId) {
    }

    record ConnectedAccountResponse(String orgId, String stripeAccountId) {
    }

    // ------------------------------------------------------------------ webhook & reconciliation

    record WebhookAck(boolean received, String eventId, String type, boolean duplicate, boolean handled) {
    }

    record ReconcileRequest(@NotNull LocalDate date) {
    }

    record ReconciliationResponse(UUID id, Instant windowStart, Instant windowEnd, String currency,
                                  long ledgerNetMinor, long stripeNetMinor, long driftMinor,
                                  List<DriftResponse> drifts, Instant createdAt) {

        static ReconciliationResponse of(ReconciliationReport r) {
            return new ReconciliationResponse(r.id(), r.windowStart(), r.windowEnd(), r.currency(),
                    r.ledgerNetMinor(), r.stripeNetMinor(), r.driftMinor(), r.drifts().stream()
                    .map(d -> new DriftResponse(d.stripeRef(), d.ledgerMinor(), d.stripeMinor(), d.difference()))
                    .toList(), r.createdAt());
        }
    }

    record DriftResponse(String stripeRef, long ledgerMinor, long stripeMinor, long differenceMinor) {
    }

    // ------------------------------------------------------------------ saved payment methods

    @Schema(description = "A saved payment method (display data only — never full numbers)")
    record PaymentMethodResponse(String id,
                                 @Schema(allowableValues = {"card", "us_bank_account", "cashapp", "link"}) String type,
                                 String brand, String last4, Integer expMonth, Integer expYear, String bankName,
                                 String accountType,
                                 @Schema(description = "false = bank account pending verification") boolean verified,
                                 boolean isDefault) {

        static PaymentMethodResponse of(WalletMethod w) {
            return new PaymentMethodResponse(w.method().id(), w.method().type().stripe(), w.method().brand(),
                    w.method().last4(), w.method().expMonth(), w.method().expYear(), w.method().bankName(),
                    w.method().accountType(), w.method().verified(), w.isDefault());
        }
    }

    record SetupIntentRequest(@Schema(description = "card, us_bank_account, cashapp, link; default card")
                              List<String> types) {
    }

    record SetupIntentResponse(String setupIntentId, String clientSecret, String status) {
    }
}
