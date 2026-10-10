package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.application.BillingOverview;
import com.afrotransact.seller.application.PlanChangePreview;
import com.afrotransact.seller.application.PlanOffer;
import com.afrotransact.seller.domain.FirstCharge;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoiceLine;
import com.afrotransact.seller.domain.PaymentMethod;
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

    @Schema(name = "Plan")
    record PlanResponse(String code, String kind, String name, String tagline, long monthlyCents, int trialDays,
                        List<String> features, List<PriceView> prices) {

        static PlanResponse of(PlanOffer o) {
            var p = o.plan();
            List<PriceView> prices = new ArrayList<>();
            for (int i = 0; i < o.quotes().size(); i++) {
                prices.add(PriceView.of(o.quotes().get(i), o.firstCharges().get(i), p.isFree()));
            }
            return new PlanResponse(p.code(), p.kind().name(), p.name(), p.tagline(), p.monthlyCents(), p.trialDays(),
                    p.features(), prices);
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
                           List<PaymentMethodResponse> paymentMethods) {

        static BillingResponse of(BillingOverview b) {
            Subscription s = b.subscription();
            PendingChange pending = s.pendingPlanCode() == null ? null : new PendingChange(s.pendingPlanCode(),
                    b.pendingPlan() == null ? null : b.pendingPlan().name(),
                    s.pendingCadence() == null ? null : s.pendingCadence().name(), s.pendingEffectiveAt());
            return new BillingResponse(s.orgId(), s.planCode(), b.plan().name(), s.cadence().name(), s.status().name(),
                    PriceView.of(b.quote(), null, b.plan().isFree()), s.trialEndsAt(), s.currentPeriodEnd(),
                    b.nextChargeAt(), b.nextChargeCents(), b.nextChargeLabel(), s.cancelAtPeriodEnd(), pending,
                    s.dunning().name(), s.failedPaymentAttempts(),
                    b.paymentMethods().stream().map(PaymentMethodResponse::of).toList());
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

    @Schema(name = "InvoiceLine", description = "Plan, proration or metered usage line (S206)")
    record InvoiceLineResponse(String description, long amountCents, Long quantity, boolean metered,
                               @Schema(allowableValues = {"FBA_STORAGE", "ADS_SPEND", "AI_CREDITS"},
                                       description = "Usage meter of a metered line") String meter,
                               Instant periodStart, Instant periodEnd) {

        static InvoiceLineResponse of(InvoiceLine l) {
            return new InvoiceLineResponse(l.description(), l.amountCents(), l.quantity(), l.metered(),
                    l.meter() == null ? null : l.meter().name(), l.periodStart(), l.periodEnd());
        }
    }

    @Schema(name = "Invoice")
    record InvoiceResponse(String id, String number, Instant periodStart, Instant periodEnd, long amountDueCents,
                           long amountPaidCents, String currency, String status, String billingReason,
                           String hostedInvoiceUrl, String invoicePdf, Instant createdAt,
                           List<InvoiceLineResponse> lines,
                           @Schema(description = "Sum of the metered usage lines") long meteredAmountCents) {

        static InvoiceResponse of(Invoice i) {
            return new InvoiceResponse(i.stripeInvoiceId(), i.number(), i.periodStart(), i.periodEnd(),
                    i.amountDueCents(), i.amountPaidCents(), i.currency(), i.status(), i.billingReason(),
                    i.hostedInvoiceUrl(), i.invoicePdf(), i.createdAt(),
                    i.lines().stream().map(InvoiceLineResponse::of).toList(),
                    i.meteredLines().stream().mapToLong(InvoiceLine::amountCents).sum());
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
