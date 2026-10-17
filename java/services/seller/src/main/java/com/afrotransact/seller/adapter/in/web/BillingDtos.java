package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.seller.application.BillingOverview;
import com.afrotransact.seller.application.EntitlementsService;
import com.afrotransact.seller.application.PlanChangePreview;
import com.afrotransact.seller.application.PlanOffer;
import com.afrotransact.seller.domain.ChargeGate;
import com.afrotransact.seller.domain.FirstCharge;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoiceLine;
import com.afrotransact.seller.domain.InvoicePayment;
import com.afrotransact.seller.domain.PaymentMethod;
import com.afrotransact.seller.domain.PaymentSource;
import com.afrotransact.seller.domain.PlanEntitlements;
import com.afrotransact.seller.domain.PlanPricing;
import com.afrotransact.seller.domain.PlanQuote;
import com.afrotransact.seller.domain.Subscription;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Plans, billing page, payment methods and invoices. */
final class BillingDtos {

    private BillingDtos() {
    }

    @Schema(name = "PlanPrice", description = "Price of a plan for one cadence; amounts in cents (USD)")
    record PriceView(String cadence, int months, long totalCents, long perMonthCents, long savingCents,
                     String perMonthLabel, String billedLabel, String savingLabel, FirstCharge firstCharge) {

        static PriceView of(PlanQuote q, FirstCharge first, boolean free) {
            return new PriceView(q.cadence().name(), q.cadence().months(), q.totalCents(), q.perMonthCents(),
                    q.savingCents(), free ? "Free" : PlanPricing.usd(q.perMonthCents()) + "/mo",
                    free ? "No subscription fee" : "Billed " + PlanPricing.usd(q.totalCents()) + " "
                            + q.cadence().every(),
                    q.savingCents() > 0 ? "Save " + PlanPricing.usd(q.savingCents()) + " vs monthly" : null, first);
        }
    }

    @Schema(name = "PlanEntitlements", description = "What a plan lets an organisation do; staff set them per plan. "
            + "A capability that is off is not offered to the plan's members at all")
    record EntitlementsView(@Schema(description = "The most live listings; null = unlimited") Long listings,
                            boolean listingsUnlimited, boolean aiListingWriter,
                            @Schema(allowableValues = {"NONE", "ESSENTIALS", "GROWTH", "PRO"},
                                    description = "What Lola, the seller assistant, can do; NONE = not included")
                            String lolaTier,
                            @Schema(description = "Listings can be uploaded in bulk") boolean bulkUpload,
                            @Schema(description = "The bulk image library is included") boolean imageLibrary,
                            @Schema(description = "Coupon codes for the seller's listings are included")
                            boolean coupons) {

        static EntitlementsView of(PlanEntitlements e) {
            return new EntitlementsView(e.listings(), e.listings() == null, e.aiListingWriter(), e.lolaTier().name(),
                    e.bulkUpload(), e.imageLibrary(), e.coupons());
        }
    }

    @Schema(name = "OrganisationEntitlements", description = "The entitlements of an organisation's plan")
    record OrgEntitlementsResponse(String orgId, String planCode, String planName, EntitlementsView entitlements) {

        static OrgEntitlementsResponse of(EntitlementsService.Entitlements e) {
            return new OrgEntitlementsResponse(e.orgId(), e.planCode(), e.planName(), EntitlementsView.of(e.plan()));
        }
    }

    @Schema(name = "Plan")
    record PlanResponse(String code, String kind, String name, String tagline, long monthlyCents,
                        @Schema(description = "The trial a sign-up gets today (plan default or a running campaign)")
                        int trialDays, List<String> features, List<PriceView> prices,
                        @Schema(description = "Current version (prices)") int version,
                        @Schema(allowableValues = {"PUBLIC", "HIDDEN", "INVITE_ONLY"}) String visibility,
                        Map<String, String> limits, EntitlementsView entitlements) {

        static PlanResponse of(PlanOffer o) {
            var p = o.plan();
            List<PriceView> prices = new ArrayList<>();
            for (int i = 0; i < o.quotes().size(); i++) {
                prices.add(PriceView.of(o.quotes().get(i), o.firstCharges().get(i), p.isFree()));
            }
            return new PlanResponse(p.code(), p.kind().name(), p.name(), p.tagline(), p.monthlyCents(), o.trialDays(),
                    PlanEntitlements.of(p.limits()).featureList(), prices, p.version(), p.visibility().name(),
                    p.limits(), EntitlementsView.of(PlanEntitlements.of(p.limits())));
        }
    }

    @Schema(name = "PaymentMethod", description = "Card (brand, last 4, expiry) or bank (name, type, last 4)")
    record PaymentMethodResponse(UUID id, String kind, String brand, String last4, Integer expMonth, Integer expYear,
                                 String bankName, String accountType, boolean verified, boolean receivesPayouts,
                                 boolean isDefault, String label) {

        static PaymentMethodResponse of(PaymentMethod m) {
            return new PaymentMethodResponse(m.id(), m.kind().name(), m.brand(), m.last4(), m.expMonth(),
                    m.expYear(), m.bankName(), m.accountType(), m.verified(), m.receivesPayouts(), m.isDefault(),
                    m.label());
        }
    }

    @Schema(name = "PendingPlanChange", description = "Downgrade or cadence change applied at renewal")
    record PendingChange(String planCode, String planName, String cadence, Instant effectiveAt) {
    }

    @Schema(name = "Billing")
    record BillingResponse(String orgId, String planCode, String planName, String cadence, String status,
                           PriceView price, Instant trialEndsAt, Instant currentPeriodEnd, Instant nextChargeAt,
                           long nextChargeCents, String nextChargeLabel, boolean cancelAtPeriodEnd,
                           PendingChange pendingChange, String dunning, int failedPaymentAttempts,
                           List<PaymentMethodResponse> paymentMethods,
                           @Schema(description = "Plan version (prices) the organisation is on") int planVersion,
                           @Schema(description = "Platform credit the next invoices use first")
                           long creditBalanceCents,
                           @Schema(allowableValues = {"PLAN", "CAMPAIGN", "OVERRIDE", "NONE"}) String trialSource,
                           Integer trialDaysOverride,
                           @Schema(allowableValues = {"EARNINGS_FIRST", "CARD"}, description = "How the plan is paid "
                                   + "(S298); details: GET /v1/orgs/{orgId}/billing/plan-fee-collection")
                           String planFeeCollection,
                           @Schema(description = "A condition staff put on the plan charges, with progress; null "
                                   + "when charges apply as normal") ChargeGateView chargeGate,
                           @Schema(description = "What the plan allows (listing cap, AI writer, Lola tier)")
                           EntitlementsView entitlements) {

        static BillingResponse of(BillingOverview b) {
            Subscription s = b.subscription();
            PendingChange pending = s.pendingPlanCode() == null ? null : new PendingChange(s.pendingPlanCode(),
                    b.pendingPlan() == null ? null : b.pendingPlan().name(),
                    s.pendingCadence() == null ? null : s.pendingCadence().name(), s.pendingEffectiveAt());
            return new BillingResponse(s.orgId(), s.planCode(), b.plan().name(), s.cadence().name(), s.status().name(),
                    PriceView.of(b.quote(), null, b.plan().isFree()), s.trialEndsAt(), s.currentPeriodEnd(),
                    b.nextChargeAt(), b.nextChargeCents(), b.nextChargeLabel(), s.cancelAtPeriodEnd(), pending,
                    s.dunning().name(), s.failedPaymentAttempts(),
                    b.paymentMethods().stream().map(PaymentMethodResponse::of).toList(), s.planVersion(),
                    b.terms().creditBalanceCents(), b.terms().trialSource() == null ? null
                            : b.terms().trialSource().name(), b.terms().trialDaysOverride(),
                    b.terms().planFeeCollection().name(), ChargeGateView.of(b),
                    EntitlementsView.of(PlanEntitlements.of(b.plan().limits())));
        }
    }

    @Schema(name = "ChargeGate", description = "A condition on the plan charges: START_AFTER (no charge until the "
            + "seller reaches the threshold), WAIVE_WHILE (free while they keep it) or WAIVE_UNTIL (free until a date)")
    record ChargeGateView(@Schema(allowableValues = {"START_AFTER", "WAIVE_WHILE", "WAIVE_UNTIL"}) String mode,
                          @Schema(allowableValues = {"GROSS_SALES", "COMPLETED_ORDERS", "COMPLETED_BOOKINGS",
                                  "ACTIVE_LISTINGS"}) String metric,
                          Long threshold, @Schema(description = "Where the seller is now (cents for sales)")
                          long progress, Instant since, Instant until, String reasonCode, String note,
                          Instant setAt, Instant metAt, @Schema(description = "One sentence for the seller")
                          String label) {

        static ChargeGateView of(BillingOverview b) {
            ChargeGate g = b.terms().chargeGate();
            if (g == null) {
                return null;
            }
            return new ChargeGateView(g.mode().name(), g.metric() == null ? null : g.metric().name(), g.threshold(),
                    b.gateProgress(), g.since(), g.until(), g.reasonCode(), g.note(), g.setAt(), g.metAt(),
                    b.gateLabel());
        }
    }

    @Schema(name = "ChangePlanRequest")
    record ChangePlanRequest(@NotBlank String planCode,
                             @Schema(allowableValues = {"MONTHLY", "QUARTERLY", "ANNUAL"}) String cadence) {
    }

    @Schema(name = "ChangePlanResponse", description = "applied: IMMEDIATE_UPGRADE (prorated now), AT_RENEWAL "
            + "(downgrade or cadence change) or NO_CHANGE")
    record ChangePlanResponse(String applied, BillingResponse billing) {
    }

    @Schema(name = "AddPaymentMethodRequest", description = "Tokenised client-side by Stripe.js (card) or Financial "
            + "Connections (bank); only display fields are stored")
    record AddPaymentMethodRequest(@NotBlank String stripePaymentMethodId,
                                   @Schema(allowableValues = {"CARD", "US_BANK_ACCOUNT"}) @NotBlank String kind,
                                   String brand, String last4, Integer expMonth, Integer expYear, String bankName,
                                   String accountType, Boolean makeDefault) {
    }

    @Schema(name = "InvoiceLine", description = "Plan, proration, usage, coupon or waiver line (S206)")
    record InvoiceLineResponse(@Schema(allowableValues = {"PLAN", "USAGE", "COUPON", "PRORATION", "WAIVER"})
                               String kind, String description, long amountCents, Long quantity, boolean metered,
                               @Schema(allowableValues = {"FBA_STORAGE", "ADS_SPEND", "AI_CREDITS"},
                                       description = "Usage meter of a metered line") String meter,
                               Instant periodStart, Instant periodEnd) {

        static InvoiceLineResponse of(InvoiceLine l) {
            return new InvoiceLineResponse(l.kind(), l.description(), l.amountCents(), l.quantity(), l.metered(),
                    l.meter() == null ? null : l.meter().name(), l.periodStart(), l.periodEnd());
        }
    }

    @Schema(name = "InvoicePayment", description = "What settled part of an invoice, in the order tried: platform "
            + "credit, the seller's earnings (only if they chose that), the saved card or bank, or a waiver")
    record InvoicePaymentResponse(UUID id, @Schema(allowableValues = {"CREDIT", "EARNINGS", "CARD", "WAIVER"})
                                  String source, long amountCents,
                                  @Schema(allowableValues = {"APPLIED", "PENDING", "FAILED", "REVERSED"}) String status,
                                  String note, Instant createdAt) {

        static InvoicePaymentResponse of(InvoicePayment p) {
            return new InvoicePaymentResponse(p.id(), p.source().name(), p.amountCents(), p.status(), p.note(),
                    p.createdAt());
        }
    }

    @Schema(name = "Invoice", description = "A plan invoice AfroTransact issued: the plan for one billing period "
            + "(and metered usage), less coupons and anything a charge condition waived")
    record InvoiceResponse(String id, String number, Instant periodStart, Instant periodEnd, long subtotalCents,
                           long discountCents, long amountDueCents, long amountPaidCents, String currency,
                           @Schema(allowableValues = {"open", "paid", "void", "uncollectible"}) String status,
                           @Schema(allowableValues = {"subscription_create", "subscription_cycle",
                                   "subscription_update"}) String billingReason,
                           String planCode, String cadence, Instant dueAt, Instant paidAt, int attempts,
                           Instant nextAttemptAt, String lastError, Instant createdAt,
                           List<InvoiceLineResponse> lines, List<InvoicePaymentResponse> payments,
                           @Schema(description = "Sum of the metered usage lines") long meteredAmountCents,
                           @Schema(description = "Paid from platform credit") long paidFromCreditCents,
                           @Schema(description = "Paid from the organisation's sales (S298): withheld from its "
                                   + "earnings, only if it chose that") long paidFromSalesCents,
                           @Schema(description = "Paid (or, while open, due) by card or bank") long paidByCardCents,
                           @Schema(description = "Waived by staff") long waivedCents) {

        static InvoiceResponse of(Invoice i) {
            long card = i.isOpen() ? i.remainingCents() + i.paidBy(PaymentSource.CARD) : i.paidBy(PaymentSource.CARD);
            return new InvoiceResponse(i.id(), i.number(), i.periodStart(), i.periodEnd(), i.subtotalCents(),
                    i.discountCents(), i.amountDueCents(), i.amountPaidCents(), i.currency(), i.status(),
                    i.billingReason(), i.planCode(), i.cadence() == null ? null : i.cadence().name(), i.dueAt(),
                    i.paidAt(), i.attempts(), i.nextAttemptAt(), i.lastError(), i.createdAt(),
                    i.lines().stream().map(InvoiceLineResponse::of).toList(),
                    i.payments().stream().map(InvoicePaymentResponse::of).toList(),
                    i.meteredLines().stream().mapToLong(InvoiceLine::amountCents).sum(),
                    i.paidBy(PaymentSource.CREDIT), i.paidBy(PaymentSource.EARNINGS), card,
                    i.paidBy(PaymentSource.WAIVER));
        }
    }

    @Schema(name = "PlanChangePreview")
    record PlanChangePreviewResponse(@Schema(allowableValues = {"IMMEDIATE_UPGRADE", "AT_RENEWAL", "NO_CHANGE"})
                                     String change, String planCode, String planName, String cadence,
                                     Instant effectiveAt,
                                     @Schema(description = "Invoiced now: the proration of an upgrade (0 in a trial)")
                                     long amountDueNowCents,
                                     @Schema(description = "The new plan's charge at the next renewal")
                                     long nextChargeCents, Instant nextChargeAt, boolean trialing) {

        static PlanChangePreviewResponse of(PlanChangePreview p) {
            return new PlanChangePreviewResponse(p.change().name(), p.planCode(), p.planName(), p.cadence().name(),
                    p.effectiveAt(), p.amountDueNowCents(), p.nextChargeCents(), p.nextChargeAt(), p.trialing());
        }
    }

    @Schema(name = "BillingSetupIntent")
    record SetupIntentResponse(String customerId, String setupIntentId,
                               @Schema(description = "Confirm with Stripe.js / PaymentSheet") String clientSecret) {
    }
}
