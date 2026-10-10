package com.afrotransact.payments.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.afrotransact.payments.application.CaptureResult;
import com.afrotransact.payments.application.DeliveryPricingCommand;
import com.afrotransact.payments.application.OrderSplit;
import com.afrotransact.payments.application.RefundResult;
import com.afrotransact.payments.application.WalletMethod;
import com.afrotransact.payments.domain.AccountBalance;
import com.afrotransact.payments.domain.Capture;
import com.afrotransact.payments.domain.CommissionOverride;
import com.afrotransact.payments.domain.DeliveryMode;
import com.afrotransact.payments.domain.DeliveryPricing;
import com.afrotransact.payments.domain.DeliveryQuote;
import com.afrotransact.payments.domain.DeliveryTariff;
import com.afrotransact.payments.domain.EffectiveFees;
import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.Fulfilment;
import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.LabelCharge;
import com.afrotransact.payments.domain.LineSplit;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.OrderLine;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentLine;
import com.afrotransact.payments.domain.PaymentLink;
import com.afrotransact.payments.domain.PayoutBalance;
import com.afrotransact.payments.domain.PayoutRequest;
import com.afrotransact.payments.domain.ProviderFee;
import com.afrotransact.payments.domain.ReconciliationReport;
import com.afrotransact.payments.domain.RecipientShare;
import com.afrotransact.payments.domain.Refund;
import com.afrotransact.payments.domain.RefundFault;
import com.afrotransact.payments.domain.Settlement;
import com.afrotransact.payments.domain.ShipmentSettlement;
import com.afrotransact.payments.domain.StatementLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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
            @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines,
            @Size(max = 120) @Schema(description = "AfroTransact customer id (token subject) of the shopper; required "
                    + "with saveForOffSession") String shopperId,
            @Schema(description = "Save the confirmed payment method on the shopper's Stripe Customer "
                    + "(setup_future_usage=off_session) so a later balance can be charged off-session; customerId is "
                    + "then the shopper's Stripe Customer") Boolean saveForOffSession,
            @Schema(description = "S272 late pre-order adds: confirm the intent now, off-session, on the shopper's "
                    + "default saved card (shopperId required); the response status is AUTHORIZED, REQUIRES_ACTION "
                    + "(the customer must authenticate) or FAILED") Boolean offSession,
            @Min(1) @Max(24) @Schema(description = "S272 late pre-order adds: create a hosted Stripe payment link "
                    + "(Checkout, manual capture) that expires after this many hours instead of returning a client "
                    + "secret; the response carries paymentLinkUrl") Integer paymentLinkHours) {
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
            @Size(max = 40) String taxJurisdiction,
            @Schema(description = "Part of taxMinor charged on shipping (states that tax delivery); default 0")
            @PositiveOrZero Long shippingTaxMinor,
            @Schema(description = "AFROTRANSACT_DELIVERY lines (S255): the seller's pickup-delivery fee allocated to "
                    + "this line, as quoted at checkout (POST /v1/delivery-pricing/quote) and snapshotted by orders. "
                    + "Credited to 4250 at capture; ignored for AfroTransact Retail; must be 0 on other lines")
            @PositiveOrZero Long deliveryFeeMinor) {

        OrderLine toLine(String currency) {
            return new OrderLine(lineId, sellerOrg, category, fulfilment, quantity, Money.of(itemsMinor, currency),
                    Money.of(shippingMinor == null ? 0 : shippingMinor, currency),
                    Money.of(taxMinor == null ? 0 : taxMinor, currency), taxJurisdiction,
                    Money.of(shippingTaxMinor == null ? 0 : shippingTaxMinor, currency),
                    Money.of(deliveryFeeMinor == null ? 0 : deliveryFeeMinor, currency));
        }
    }

    record PaymentResponse(UUID id, String orderId, String status, String stripePaymentIntentId,
                           @Schema(description = "For Stripe.js / PaymentSheet confirmation (also to complete "
                                   + "authentication of an off-session charge in REQUIRES_ACTION); only returned to "
                                   + "the calling service") String clientSecret,
                           long amountMinor, long capturedMinor, long refundedMinor,
                           @Schema(description = "Authorised but released without capture (final capture, release "
                                   + "or cancellation)") long releasedMinor,
                           String currency, UUID feeScheduleId, Instant placedAt, Instant authorizedAt,
                           @Schema(description = "Decline message; authentication_required while an off-session "
                                   + "charge waits for the customer") String failureMessage,
                           @Schema(description = "Set for an off-session charge: the order's primary payment")
                           UUID parentPaymentId,
                           boolean offSession, boolean replayed, List<LineResponse> lines,
                           @Schema(description = "Hosted payment link (created with paymentLinkHours)")
                           String paymentLinkUrl,
                           @Schema(description = "When the payment link expires") Instant paymentLinkExpiresAt) {

        static PaymentResponse of(Payment p, boolean replayed) {
            return of(p, replayed, null);
        }

        static PaymentResponse of(Payment p, boolean replayed, PaymentLink link) {
            return new PaymentResponse(p.id(), p.orderId(), p.status().name(), p.stripePaymentIntentId(),
                    p.clientSecret(), p.amount().minor(), p.captured().minor(), p.refunded().minor(),
                    p.released().minor(), p.currency(), p.feeScheduleId(), p.placedAt(), p.authorizedAt(),
                    p.failureMessage(), p.parentPaymentId(), p.isOffSession(), replayed,
                    p.lines().stream().map(LineResponse::of).toList(), link == null ? null : link.url(),
                    link == null ? null : link.expiresAt());
        }
    }

    @Schema(name = "PaymentLineResponse", description = "One line as charged, with what was refunded so far "
            + "(by this service's refunds, cancellations and claims alike)")
    record LineResponse(String lineId, String sellerOrg, String category, String fulfilment, int quantity,
                        long itemsMinor, long shippingMinor, long taxMinor, String taxJurisdiction, UUID captureId,
                        long commissionMinor, long fbaFeeMinor, long refundedMinor,
                        @Schema(description = "Part of taxMinor charged on shipping") long shippingTaxMinor,
                        @Schema(description = "taxMinor − shippingTaxMinor") long itemsTaxMinor,
                        long refundedItemsMinor, long refundedShippingMinor, long refundedTaxMinor,
                        @Schema(description = "Captured and not refunded yet (0 before capture)")
                        long refundableMinor,
                        @Schema(description = "Seller pickup-delivery fee quoted for the line (AFROTRANSACT_DELIVERY)")
                        long deliveryFeeMinor,
                        @Schema(description = "Delivery fee charged at capture (0 for AfroTransact Retail)")
                        long deliveryFeeChargedMinor,
                        @Schema(description = "Delivery fee given back by customer-fault refunds")
                        long deliveryFeeReversedMinor) {

        static LineResponse of(PaymentLine pl) {
            OrderLine l = pl.line();
            return new LineResponse(l.lineId(), l.sellerOrg(), l.category(), l.fulfilment().name(), l.quantity(),
                    l.items().minor(), l.shipping().minor(), l.tax().minor(), l.taxJurisdiction(), pl.captureId(),
                    pl.commission().minor(), pl.fbaFee().minor(), pl.refundedTotal().minor(),
                    l.shippingTax().minor(), l.itemsTax().minor(), pl.refundedItems().minor(),
                    pl.refundedShipping().minor(), pl.refundedTax().minor(), pl.refundable().minor(),
                    l.deliveryFee().minor(), pl.deliveryFee().minor(), pl.deliveryFeeReversed().minor());
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

    @Schema(description = "Release the uncaptured rest of the authorisation (e.g. booking cancelled or no-show after "
            + "the deposit capture)")
    record ReleaseRequest(@Size(max = 500) String reason) {
    }

    @Schema(description = "Charge lines of the order off-session on the customer's saved card (manual capture: "
            + "capture with POST /v1/payments/{newId}/capture)")
    record OffSessionChargeRequest(
            @NotBlank @Size(max = 120) @Schema(description = "AfroTransact customer id (token subject)")
            String customerId,
            @Size(max = 80) @Schema(description = "Saved card pm_…; default = the customer's default method")
            String paymentMethodId,
            @Schema(description = "Lines of the primary payment; empty = every line not captured yet")
            List<String> lineIds) {
    }

    @Schema(description = "Line-level partial refund")
    record RefundRequest(@NotEmpty @Size(max = 200) List<@Valid RefundLineRequest> lines,
                         @Size(max = 500) String reason,
                         @Schema(description = "Who caused the refund, for AfroTransact Delivery lines: SELLER "
                                 + "(default) keeps the seller's delivery fee; CUSTOMER returns it pro rata and the "
                                 + "delivery charge is not refundable") RefundFault fault) {
    }

    record RefundLineRequest(@NotBlank String lineId, @PositiveOrZero Long itemsMinor,
                             @PositiveOrZero Long shippingMinor, @PositiveOrZero Long taxMinor) {
    }

    @Schema(name = "OrderRefundRequest", description = "Refund by order and line ids (service callers such as "
            + "trust-ops): the amount is spread over the lines' remaining items, shipping and tax")
    record OrderRefundRequest(
            @NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 80) String> lineIds,
            @Schema(description = "Total to refund; omit to refund everything still refundable on the lines")
            @Positive Long amountMinor,
            @Size(max = 500) String reason,
            @Schema(description = "SELLER (default) or CUSTOMER; see POST /v1/payments/{id}/refunds") RefundFault fault) {
    }

    record RefundResponse(UUID id, UUID paymentId, String orderId, String stripeRefundId, long amountMinor,
                          String currency, String status, String reason, List<RefundLineResponse> lines,
                          List<RefundResult.Reversal> transferReversals, Instant createdAt, Instant settledAt,
                          boolean replayed) {

        static RefundResponse of(Refund r, List<RefundResult.Reversal> reversals, boolean replayed) {
            return new RefundResponse(r.id(), r.paymentId(), r.orderId(), r.stripeRefundId(), r.amount().minor(),
                    r.amount().currency(), r.status().name(), r.reason(), r.lines().stream()
                    .map(l -> new RefundLineResponse(l.lineId(), l.sellerOrg(), l.items().minor(),
                            l.shipping().minor(), l.tax().minor(), l.commissionReversed().minor(),
                            l.deliveryFeeReversed().minor())).toList(),
                    reversals, r.createdAt(), r.settledAt(), replayed);
        }
    }

    record RefundLineResponse(String lineId, String sellerOrg, long itemsMinor, long shippingMinor, long taxMinor,
                              long commissionReversedMinor,
                              @Schema(description = "AfroTransact Delivery fee returned to the seller (customer fault)")
                              long deliveryFeeReversedMinor) {
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

    @Schema(description = "One recipient. For sellers commission, FBA fees, delivery fees and Stripe fee are "
            + "deductions; for the AfroTransact platform row they are the totals earned. AfroTransact Retail (kind RETAIL) pays no "
            + "commission and no FBA fee: its net (items + shipping − Stripe fee share) is AfroTransact revenue "
            + "(4400 Retail sales), never transferred or paid out. Tax is collected and remitted by AfroTransact.")
    record RecipientResponse(String recipient,
                             @Schema(allowableValues = {"SELLER", "RETAIL", "PLATFORM"}) String kind,
                             long itemsMinor, long shippingMinor, long taxMinor,
                             Map<String, Long> taxByJurisdiction, long commissionMinor, long fbaFeesMinor,
                             @Schema(description = "AfroTransact Delivery pickup-delivery fees (4250)")
                             long deliveryFeesMinor, long stripeFeeMinor, long netMinor) {

        static RecipientResponse of(RecipientShare r) {
            Map<String, Long> tax = new TreeMap<>();
            r.taxByJurisdiction().forEach((k, v) -> tax.put(k, v.minor()));
            return new RecipientResponse(r.recipientId(), r.kind().name(), r.items().minor(), r.shipping().minor(),
                    r.tax().minor(), tax, r.commission().minor(), r.fbaFees().minor(), r.deliveryFees().minor(),
                    r.stripeFee().minor(), r.net().minor());
        }
    }

    record LineSplitResponse(String lineId, String sellerOrg, int commissionBps, long commissionMinor,
                             long fbaFeeMinor, boolean shippingToPlatform, long deliveryFeeMinor) {

        static LineSplitResponse of(LineSplit l) {
            return new LineSplitResponse(l.lineId(), l.sellerOrg(), l.commissionBps(), l.commission().minor(),
                    l.fbaFee().minor(), l.shippingToPlatform(), l.deliveryFee().minor());
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
            @Size(max = 500) String note,
            @Valid @Schema(description = "AfroTransact Delivery pricing (S255). Omit to carry the latest version's "
                    + "section over; send enabled=false to stop offering AfroTransact Delivery from seller stores")
            DeliveryPricingRequest deliveryPricing) {
    }

    @Schema(description = "AfroTransact Delivery from seller stores: the customer's delivery charge (to 4200), the "
            + "seller's pickup-delivery fee (to 4250) with per-seller overrides, and the service radius")
    record DeliveryPricingRequest(
            @Schema(description = "false = not offered under this version", defaultValue = "true") Boolean enabled,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("200")
            @Schema(description = "Beyond this (estimated road miles) the option is unavailable", example = "25")
            BigDecimal maxRadiusMiles,
            @Valid @Schema(description = "What the customer pays (FLAT or DISTANCE); omitted = free")
            TariffRequest customerCharge,
            @Valid @Schema(description = "What the seller pays (FLAT or DISTANCE); omitted = no fee")
            TariffRequest sellerFee,
            @Size(max = 500) List<@Valid SellerFeeOverrideRequest> sellerOverrides) {

        DeliveryPricingCommand toCommand() {
            boolean on = !Boolean.FALSE.equals(enabled);
            return new DeliveryPricingCommand(on, maxRadiusMiles, customerCharge == null ? null
                    : customerCharge.toCommand(), sellerFee == null ? null : sellerFee.toCommand(),
                    sellerOverrides == null ? List.of() : sellerOverrides.stream()
                            .map(o -> new DeliveryPricingCommand.Override(o.orgId(), o.sellerFee() == null ? null
                                    : o.sellerFee().toCommand())).toList());
        }
    }

    @Schema(name = "DeliveryTariff", description = "FLAT: flatPerPackageMinor × packages. DISTANCE (per trip): the fee "
            + "of the band covering the distance, else baseMinor + perMileMinor × miles (half up), then at least "
            + "minMinor and at most maxMinor")
    record TariffRequest(
            @NotNull DeliveryMode mode,
            @PositiveOrZero Long flatPerPackageMinor,
            @PositiveOrZero Long baseMinor,
            @PositiveOrZero @Schema(description = "Minor units per mile (85 = $0.85 / mi)") Long perMileMinor,
            @Size(max = 20) List<@Valid BandRequest> bands,
            @PositiveOrZero Long minMinor,
            @PositiveOrZero @Schema(description = "Cap; omit for none") Long maxMinor) {

        DeliveryPricingCommand.Tariff toCommand() {
            return new DeliveryPricingCommand.Tariff(mode, nz(flatPerPackageMinor), nz(baseMinor), nz(perMileMinor),
                    bands == null ? List.of() : bands.stream().map(b -> new DeliveryPricingCommand.Band(b.fromMiles(),
                            b.toMiles(), b.feeMinor())).toList(), nz(minMinor), maxMinor);
        }

        static TariffRequest of(DeliveryTariff t) {
            return new TariffRequest(t.mode(), t.flatPerPackage().minor(), t.base().minor(), t.perMileMinor(),
                    t.bands().stream().map(b -> new BandRequest(b.fromMiles(), b.toMiles(), b.fee().minor()))
                            .toList(), t.min().minor(), t.max() == null ? null : t.max().minor());
        }

        private static long nz(Long v) {
            return v == null ? 0 : v;
        }
    }

    @Schema(name = "DeliveryBand", description = "Trips of fromMiles (inclusive) up to toMiles (exclusive) cost feeMinor")
    record BandRequest(@NotNull @DecimalMin("0") BigDecimal fromMiles, @NotNull BigDecimal toMiles,
                       @NotNull @PositiveOrZero Long feeMinor) {
    }

    @Schema(name = "SellerDeliveryFeeOverride", description = "Negotiated seller pickup-delivery fee of one organisation")
    record SellerFeeOverrideRequest(@NotBlank @Size(max = 80) String orgId, @Valid @NotNull TariffRequest sellerFee) {
    }

    @Schema(name = "DeliveryPricing", description = "AfroTransact Delivery pricing of a fee-schedule version")
    record DeliveryPricingResponse(boolean enabled, BigDecimal maxRadiusMiles, TariffRequest customerCharge,
                                   TariffRequest sellerFee, List<SellerFeeOverrideRequest> sellerOverrides) {

        static DeliveryPricingResponse of(DeliveryPricing d) {
            if (d == null) {
                return new DeliveryPricingResponse(false, null, null, null, List.of());
            }
            return new DeliveryPricingResponse(true, d.maxRadiusMiles(), TariffRequest.of(d.customerCharge()),
                    TariffRequest.of(d.sellerFee()), d.sellerOverrides().stream()
                    .map(o -> new SellerFeeOverrideRequest(o.orgId(), TariffRequest.of(o.sellerFee()))).toList());
        }
    }

    @Schema(name = "DeliveryQuoteRequest", description = "Price one AfroTransact Delivery trip")
    record DeliveryQuoteRequest(
            @NotBlank @Size(max = 80) String sellerOrgId,
            @NotNull @DecimalMin("0") @Schema(description = "Road miles (geo POST /v1/distance roadMiles)",
                    example = "7.4") BigDecimal distanceMiles,
            @Min(1) @Max(50) @Schema(defaultValue = "1") Integer packages,
            @Schema(description = "Fee-schedule version in force at this time; default now") Instant at) {
    }

    @Schema(name = "DeliveryQuote", description = "customerChargeMinor goes on the order as the package's shipping "
            + "(4200); sellerFeeMinor is snapshotted and deducted from the seller at capture (4250)")
    record DeliveryQuoteResponse(boolean available,
                                 @Schema(allowableValues = {"NOT_CONFIGURED", "BEYOND_MAX_RADIUS"})
                                 String unavailableReason, long customerChargeMinor, long sellerFeeMinor,
                                 @Schema(allowableValues = {"DEFAULT", "OVERRIDE", "RETAIL"}) String sellerFeeSource,
                                 String currency, UUID scheduleId, int scheduleVersion, BigDecimal distanceMiles,
                                 int packages, BigDecimal maxRadiusMiles) {

        static DeliveryQuoteResponse of(DeliveryQuote q) {
            return new DeliveryQuoteResponse(q.available(), q.unavailableReason(), q.customerCharge().minor(),
                    q.sellerFee().minor(), q.sellerFeeSource(), q.currency(), q.scheduleId(), q.scheduleVersion(),
                    q.distanceMiles(), q.packages(), q.maxRadiusMiles());
        }
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
                               Instant createdAt,
                               @Schema(description = "Set when the version was cancelled before it took effect "
                                       + "(it never applies)") Instant cancelledAt,
                               @Schema(description = "Who cancelled the version") String cancelledBy,
                               @Schema(description = "Why the version was cancelled") String cancelReason,
                               @Schema(description = "AfroTransact Delivery pricing (S255)")
                               DeliveryPricingResponse deliveryPricing) {

        static FeeScheduleResponse of(FeeSchedule s) {
            FeeSchedule.Cancellation c = s.cancellation();
            return new FeeScheduleResponse(s.id(), s.version(), s.effectiveFrom(), s.currency(),
                    new TreeMap<>(s.categoryBps()), s.defaultCommissionBps(), s.overrides().stream()
                    .map(o -> new OverrideRequest(o.orgId(), o.category(), o.commissionBps())).toList(),
                    s.cardPctBps(), s.cardFixed().minor(), s.fbaPerUnit().minor(), s.reverseCommissionOnRefund(),
                    s.note(), s.createdBy(), s.createdAt(), c == null ? null : c.at(), c == null ? null : c.by(),
                    c == null ? null : c.reason(), DeliveryPricingResponse.of(s.deliveryPricing()));
        }
    }

    @Schema(description = "Why a scheduled fee schedule version is withdrawn (audited)")
    record CancelFeeScheduleRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @Schema(description = "Fees payments applies to one organisation and category (fee-schedule version in force)")
    record EffectiveFeesResponse(UUID feeScheduleId, int version, Instant effectiveFrom, String currency,
                                 String category, String orgId,
                                 @Schema(description = "Commission in basis points (1000 = 10 %)") int commissionBps,
                                 @Schema(allowableValues = {"OVERRIDE", "CATEGORY", "DEFAULT", "RETAIL"})
                                 String commissionSource,
                                 @Schema(description = "Card fee estimate per charge: percentage part in bps")
                                 int cardPctBps,
                                 @Schema(description = "Card fee estimate per charge: fixed part") long cardFixedMinor,
                                 @Schema(description = "FBA fee per unit; 0 for AfroTransact Retail")
                                 long fbaPerUnitMinor, boolean reverseCommissionOnRefund) {

        static EffectiveFeesResponse of(EffectiveFees f) {
            return new EffectiveFeesResponse(f.feeScheduleId(), f.version(), f.effectiveFrom(), f.currency(),
                    f.category(), f.orgId(), f.commissionBps(), f.commissionSource(), f.cardPctBps(),
                    f.cardFixed().minor(), f.fbaPerUnit().minor(), f.reverseCommissionOnRefund());
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

    @Schema(name = "ShipmentSettlement", description = "Settlement hold of one shipment")
    record ShipmentSettlementResponse(String orderId, String shipmentId, String kind, Instant deliveredAt,
                                      Instant releaseAt, String status) {

        static ShipmentSettlementResponse of(ShipmentSettlement s) {
            return new ShipmentSettlementResponse(s.orderId(), s.shipmentId(), s.kind().name(), s.deliveredAt(),
                    s.releaseAt(), s.status().name());
        }
    }

    record PayoutBalanceResponse(String orgId, String currency, long availableMinor, long onHoldMinor,
                                 List<HeldOrderResponse> onHold, long requestedOrInTransitMinor, long paidMinor,
                                 @Schema(description = "Provider fees (late cancellations) debited from or reserved "
                                         + "in the balance") long providerFeesMinor,
                                 @Schema(description = "Shipping labels bought through AfroTransact and not yet "
                                         + "recovered from a transfer; deducted from availableMinor, which may go "
                                         + "negative. Negative when voided labels are owed back (paid with the next "
                                         + "transfer). Details: GET /v1/payouts/label-charges")
                                 long labelChargesMinor,
                                 @Schema(description = "AfroTransact Delivery pickup fees paid on captured lines, net "
                                         + "of fees given back by customer-fault refunds (S259); already deducted from "
                                         + "the order nets, shown for earnings")
                                 long deliveryFeesMinor,
                                 long minimumPayoutMinor, boolean canRequestToday,
                                 @Schema(description = "Payouts are on a compliance hold: requests and approvals fail "
                                         + "with 422 payments.payouts_paused; the balance keeps accruing")
                                 boolean payoutsPaused,
                                 @Schema(description = "Why payouts are paused (null when not paused)",
                                         allowableValues = {"AUTO_PAST_DUE", "MANUAL", "DEAUTHORIZED",
                                             "CHARGES_DISABLED"})
                                 String payoutsPausedReason) {

        static PayoutBalanceResponse of(PayoutBalance b) {
            return new PayoutBalanceResponse(b.orgId(), b.currency(), b.available().minor(), b.onHoldTotal().minor(),
                    b.onHold().stream().map(h -> new HeldOrderResponse(h.orderId(), h.amount().minor(),
                            h.releaseAt())).toList(), b.inFlight().minor(), b.paid().minor(),
                    b.providerFees().minor(), b.labelCharges().minor(), b.deliveryFees().minor(),
                    b.minimum().minor(), b.canRequestToday(),
                    b.payoutsPaused(),
                    b.payoutsPausedReason());
        }
    }

    @Schema(description = "A fee a provider owes (late provider cancellation), collected by a Stripe account debit")
    record ProviderFeeResponse(UUID id, String orgId, String bookingId, String bookingReference, long amountMinor,
                               String currency, String reason,
                               @Schema(allowableValues = {"OUTSTANDING", "COLLECTED"}) String status,
                               String stripeDebitId, int attempts, String lastError, Instant nextAttemptAt,
                               Instant createdAt, Instant collectedAt) {

        static ProviderFeeResponse of(ProviderFee f) {
            return new ProviderFeeResponse(f.id(), f.orgId(), f.bookingId(), f.bookingReference(), f.amount().minor(),
                    f.amount().currency(), f.reason(), f.status().name(), f.stripeDebitId(), f.attempts(),
                    f.lastError(), f.nextAttemptAt(), f.createdAt(), f.collectedAt());
        }
    }

    @Schema(description = "A shipping label bought through AfroTransact and charged to the seller (carrier cost + "
            + "handling), credited back once if the label is voided")
    record LabelChargeResponse(UUID id, String labelId, String orgId, String shipmentId, String orderId,
                               String carrier, String service, String trackingNumber,
                               @Schema(description = "The carrier's negotiated charge") long costMinor,
                               @Schema(description = "AfroTransact handling markup (0 by default)")
                               long handlingMinor,
                               @Schema(description = "costMinor + handlingMinor") long amountMinor, String currency,
                               @Schema(allowableValues = {"CHARGED", "CREDITED"}) String status,
                               Instant purchasedAt, Instant chargedAt, Instant creditedAt) {

        static LabelChargeResponse of(LabelCharge c) {
            return new LabelChargeResponse(c.id(), c.labelId(), c.orgId(), c.shipmentId(), c.orderId(), c.carrier(),
                    c.service(), c.trackingNumber(), c.cost().minor(), c.handling().minor(), c.amount().minor(),
                    c.cost().currency(), c.status().name(), c.purchasedAt(), c.chargedAt(), c.creditedAt());
        }
    }

    @Schema(description = "One line of the seller statement: a movement of the organisation's payable (credit to "
            + "the seller positive). Label lines carry labelId, shipmentId, trackingNumber and carrier.")
    record StatementLineResponse(UUID journalId,
                                 @Schema(description = "CAPTURE, REFUND, TRANSFER, TRANSFER_REVERSAL, DISPUTE_OPENED, "
                                         + "DISPUTE_WON, LABEL_CHARGE, LABEL_CREDIT, …") String kind,
                                 Instant occurredAt, String orderId, String description, long amountMinor,
                                 String currency, String labelId, String shipmentId, String trackingNumber,
                                 String carrier) {

        static StatementLineResponse of(StatementLine l) {
            return new StatementLineResponse(l.journalId(), l.kind().name(), l.occurredAt(), l.orderId(),
                    l.description(), l.amount().minor(), l.amount().currency(), l.labelId(), l.shipmentId(),
                    l.trackingNumber(), l.carrier());
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

    @Schema(name = "EphemeralKeyRequest")
    record EphemeralKeyRequest(@Schema(description = "Stripe API version of the mobile SDK; default 2025-08-27.basil",
            examples = "2025-08-27.basil") String stripeVersion) {
    }

    @Schema(name = "EphemeralKey", description = "PaymentSheet setup for the signed-in shopper (S241)")
    record EphemeralKeyResponse(@Schema(description = "The shopper's Stripe Customer", examples = "cus_Q1w2e3r4")
                                String customerId,
                                @Schema(description = "Ephemeral key secret for the PaymentSheet (never log it)")
                                String ephemeralKeySecret,
                                Instant expiresAt) {
    }
}
