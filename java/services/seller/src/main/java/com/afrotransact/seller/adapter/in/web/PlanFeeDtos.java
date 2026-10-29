package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.application.PlanFeeCollectionService;
import com.afrotransact.seller.domain.PlanFeeCollection;
import com.afrotransact.seller.port.EarningsCollectionRepository;
import com.afrotransact.seller.port.PlanFeeCollectionRepository;
import io.swagger.v3.oas.annotations.media.Schema;

/** How organisations pay their plan (S298): their choice, its audit, the platform default and the collections. */
final class PlanFeeDtos {

    private PlanFeeDtos() {
    }

    private static String name(PlanFeeCollection m) {
        return m == null ? null : m.name();
    }

    @Schema(name = "PlanFeeCollectionChange", description = "One change of an organisation's choice")
    record ChangeView(UUID id,
                      @Schema(allowableValues = {"EARNINGS_FIRST", "CARD"}) String previous,
                      @Schema(allowableValues = {"EARNINGS_FIRST", "CARD"}) String mode,
                      @Schema(allowableValues = {"ONBOARDING", "SELLER", "STAFF"}) String source, String actorId,
                      @Schema(description = "Staff overrides: billing reason code") String reasonCode, String note,
                      @Schema(description = "The next invoice when it changed") Instant appliesFrom,
                      Instant createdAt) {

        static ChangeView of(PlanFeeCollectionRepository.Change c) {
            return new ChangeView(c.id(), name(c.previous()), name(c.mode()), c.source(), c.actorId(), c.reasonCode(),
                    c.note(), c.appliesFrom(), c.createdAt());
        }
    }

    @Schema(name = "PlanFeeCollection", description = "How the organisation pays its plan: EARNINGS_FIRST takes each "
            + "invoice from pending earnings and charges the card only for the rest; CARD always charges the card")
    record CollectionResponse(String orgId,
                              @Schema(description = "The kill switch is on: the choice is offered and honoured")
                              boolean available,
                              @Schema(allowableValues = {"EARNINGS_FIRST", "CARD"}) String mode,
                              @Schema(allowableValues = {"EARNINGS_FIRST", "CARD"},
                                      description = "What the next invoice uses (CARD while the kill switch is off)")
                              String effective,
                              @Schema(allowableValues = {"DEFAULT", "ONBOARDING", "SELLER", "STAFF"}) String source,
                              Instant changedAt, String changedBy,
                              @Schema(allowableValues = {"EARNINGS_FIRST", "CARD"}) String platformDefault,
                              @Schema(description = "A change applies from the invoice of this date (next renewal); "
                                      + "null without a paid plan") Instant appliesFromInvoiceAt,
                              @Schema(description = "e.g. Applies from your next invoice, on Nov 9, 2026")
                              String appliesFromLabel,
                              @Schema(description = "An invoice being collected now keeps the choice it was created "
                                      + "with") String inProgressInvoiceId,
                              List<ChangeView> history) {

        static CollectionResponse of(PlanFeeCollectionService.PlanFeeCollectionView v) {
            return new CollectionResponse(v.orgId(), v.available(), name(v.mode()), name(v.effective()), v.source(),
                    v.changedAt(), v.changedBy(), name(v.platformDefault()), v.appliesFromInvoiceAt(),
                    v.appliesFromLabel(), v.inProgressInvoiceId(), v.history().stream().map(ChangeView::of).toList());
        }
    }

    @Schema(name = "PlanFeeCollectionRequest")
    record ChooseRequest(@Schema(allowableValues = {"EARNINGS_FIRST", "CARD"}) String mode) {
    }

    @Schema(name = "PlanFeeCollectionOverrideRequest", description = "Staff override, audited with a reason code "
            + "(OTHER needs a note)")
    record OverrideRequest(@Schema(allowableValues = {"EARNINGS_FIRST", "CARD"}) String mode, String reasonCode,
                           String note) {
    }

    @Schema(name = "PlanFeeCollectionOption", description = "What the onboarding Plan step offers")
    record OptionView(boolean available,
                      @Schema(allowableValues = {"EARNINGS_FIRST", "CARD"}) String platformDefault) {

        static OptionView of(PlanFeeCollectionService.Options o) {
            return new OptionView(o.available(), name(o.platformDefault()));
        }
    }

    @Schema(name = "BillingSettings", description = "Platform billing settings (staff console › Plans & billing)")
    record SettingsResponse(@Schema(description = "The kill switch COLLECT_PLAN_FEES_FROM_EARNINGS")
                            boolean earningsCollectionEnabled,
                            @Schema(allowableValues = {"EARNINGS_FIRST", "CARD"},
                                    description = "How new sellers pay their plan unless they choose")
                            String planFeeCollectionDefault, String updatedBy, Instant updatedAt) {

        static SettingsResponse of(PlanFeeCollectionService.PlatformSettings s) {
            return new SettingsResponse(s.earningsCollectionEnabled(), name(s.planFeeCollectionDefault()),
                    s.updatedBy(), s.updatedAt());
        }
    }

    @Schema(name = "BillingSettingsRequest")
    record SettingsRequest(@Schema(allowableValues = {"EARNINGS_FIRST", "CARD"}) String planFeeCollectionDefault) {
    }

    @Schema(name = "EarningsCollection", description = "One plan invoice paid (or asked to be paid) from earnings")
    record CollectionView(String invoiceId, String invoiceNumber,
                          @Schema(description = "Invoice amount when Stripe created it") Long invoiceAmountCents,
                          @Schema(description = "Asked from earnings (the invoice less the credit balance)")
                          long amountCents, long withheldCents, long creditedCents, long releasedCents,
                          @Schema(description = "Paid from sales: credited less released") long paidFromSalesCents,
                          @Schema(allowableValues = {"REQUESTED", "WITHHELD", "CREDITED", "DECLINED", "MISSED",
                              "FAILED", "RELEASED"}) String status,
                          String paymentsReference, String stripeBalanceTransactionId, String reason, int attempts,
                          String lastError, Instant createdAt, Instant updatedAt) {

        static CollectionView of(EarningsCollectionRepository.Row r) {
            return new CollectionView(r.invoiceId(), r.invoiceNumber(), r.invoiceAmountCents(), r.amountCents(),
                    r.withheldCents(), r.creditedCents(), r.releasedCents(), r.fromSalesCents(), r.status(),
                    r.paymentsReference(), r.balanceTransactionId(), r.reason(), r.attempts(), r.lastError(),
                    r.createdAt(), r.updatedAt());
        }
    }
}
