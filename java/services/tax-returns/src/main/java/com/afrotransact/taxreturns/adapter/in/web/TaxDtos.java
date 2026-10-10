package com.afrotransact.taxreturns.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.application.LiabilityReport;
import com.afrotransact.taxreturns.application.TaxQuoteCommand;
import com.afrotransact.taxreturns.domain.tax.LiabilityRow;
import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.domain.tax.TaxTransaction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies for tax quotes, the rate table, the ledger and liability reports. */
final class TaxDtos {

    private TaxDtos() {
    }

    @Schema(name = "TaxShipTo")
    record ShipTo(@Size(max = 2) String country, @NotBlank @Size(max = 10) String state,
                  @NotBlank @Size(max = 10) String postalCode, @Size(max = 120) String city) {
    }

    @Schema(name = "TaxQuoteLine")
    record QuoteLine(@Size(max = 80) String lineId, @Size(max = 40) String atin, @Size(max = 80) String sellerOrgId,
                     @Schema(description = "GENERAL | GROCERY | PREPARED_FOOD; when absent productType decides")
                     @Size(max = 30) String taxCategory,
                     @Schema(description = "catalog product type (mapped by at.taxreturns.tax.category-by-product-type)")
                     @Size(max = 80) String productType, @Min(1) int quantity, @Min(0) long unitPriceMinor,
                     @Min(0) long discountMinor, @Min(0) long shippingMinor) {
    }

    @Schema(name = "TaxQuoteRequest", description = "Cart or order lines shipped to one address")
    record QuoteRequest(@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency, @NotNull @Valid ShipTo shipTo,
                        @NotEmpty @Size(max = 500) List<@Valid QuoteLine> lines) {

        TaxQuoteCommand toCommand() {
            return new TaxQuoteCommand(currency, new TaxAddress(shipTo.country(), shipTo.state(), shipTo.postalCode(),
                    shipTo.city()), lines.stream().map(l -> new TaxQuoteCommand.Line(l.lineId(), l.atin(),
                    l.sellerOrgId(), l.taxCategory(), l.productType(), l.quantity(), l.unitPriceMinor(),
                    l.discountMinor(), l.shippingMinor())).toList());
        }
    }

    @Schema(name = "TaxQuoteLineResult")
    record QuoteLineResponse(String lineId, String sellerOrgId, String taxCategory, long taxableMinor,
                             @Schema(description = "Combined rate applied (fraction, 0.0825 = 8.25 %)") BigDecimal rate,
                             long taxMinor, String jurisdiction, String localJurisdiction, String collectedBy) {

        static QuoteLineResponse of(TaxLineResult r) {
            return new QuoteLineResponse(r.lineId(), r.sellerOrgId(), r.category().name(), r.taxableMinor(), r.rate(),
                    r.taxMinor(), r.jurisdiction(), r.localJurisdiction(), r.collectedBy().name());
        }
    }

    @Schema(name = "TaxQuote", description = "Tax per line, rounded half up to the minor unit once per line")
    record QuoteResponse(String currency, String jurisdiction, String collectedBy, long totalTaxMinor,
                         String provider, List<QuoteLineResponse> lines) {

        static QuoteResponse of(TaxQuote q) {
            return new QuoteResponse(q.currency(), q.jurisdiction(), q.collectedBy().name(), q.totalTaxMinor(),
                    q.provider(), q.lines().stream().map(QuoteLineResponse::of).toList());
        }
    }

    @Schema(name = "StateTaxRule", description = "State rates are fractions (0.0625 = 6.25 %)")
    record StateRuleDto(@Size(max = 2) String country, String state, @NotBlank @Size(max = 80) String name,
                        @NotNull @DecimalMin("0") @DecimalMax("0.5") BigDecimal generalRate,
                        @NotNull @DecimalMin("0") @DecimalMax("0.5") BigDecimal groceryRate,
                        @NotNull @DecimalMin("0") @DecimalMax("0.5") BigDecimal preparedFoodRate,
                        boolean shippingTaxable,
                        @Schema(description = "AfroTransact collects and remits as marketplace facilitator")
                        boolean facilitator, Long version) {

        StateTaxRule toDomain(String stateCode) {
            return new StateTaxRule(country == null || country.isBlank() ? "US" : country, stateCode, name,
                    generalRate, groceryRate, preparedFoodRate, shippingTaxable, facilitator,
                    version == null ? -1L : version);
        }

        static StateRuleDto of(StateTaxRule r) {
            return new StateRuleDto(r.country(), r.state(), r.name(), r.generalRate(), r.groceryRate(),
                    r.preparedFoodRate(), r.shippingTaxable(), r.facilitator(), r.version());
        }
    }

    @Schema(name = "LocalTaxRate", description = "Local rate added to the state rate for a ZIP or 3-digit ZIP prefix")
    record LocalRateDto(String state, String zipPrefix, @NotBlank @Size(max = 120) String name,
                        @NotNull @DecimalMin("0") @DecimalMax("0.5") BigDecimal rate, boolean appliesToGrocery) {

        static LocalRateDto of(LocalTaxRate r) {
            return new LocalRateDto(r.state(), r.zipPrefix(), r.name(), r.rate(), r.appliesToGrocery());
        }
    }

    @Schema(name = "TaxRateTable")
    record RatesResponse(List<StateRuleDto> states, List<LocalRateDto> locals) {
    }

    @Schema(name = "LiabilityRow")
    record LiabilityRowDto(String jurisdiction, String currency, long orders, long grossSalesMinor,
                           long taxableSalesMinor, long taxCollectedMinor, long taxRefundedMinor, long netTaxMinor) {

        static LiabilityRowDto of(LiabilityRow r) {
            return new LiabilityRowDto(r.jurisdiction(), r.currency(), r.orders(), r.grossSalesMinor(),
                    r.taxableSalesMinor(), r.taxCollectedMinor(), r.taxRefundedMinor(), r.netTaxMinor());
        }
    }

    @Schema(name = "LiabilityReport", description = "Monthly tax per jurisdiction: collected on sales − reversed by "
            + "refunds (booked in the month of the refund) = net to remit")
    record LiabilityResponse(String month, String sellerOrgId, List<LiabilityRowDto> rows) {

        static LiabilityResponse of(LiabilityReport r) {
            return new LiabilityResponse(r.month().toString(), r.sellerOrgId(),
                    r.rows().stream().map(LiabilityRowDto::of).toList());
        }
    }

    @Schema(name = "TaxTransaction")
    record TransactionDto(UUID id, String kind, UUID orderId, String orderNumber, String lineId, String sourceRef,
                          String sellerOrgId, String jurisdiction, String localJurisdiction, String currency,
                          long taxableMinor, long taxMinor, Instant occurredAt) {

        static TransactionDto of(TaxTransaction t) {
            return new TransactionDto(t.id(), t.kind().name(), t.orderId(), t.orderNumber(), t.lineId(),
                    t.sourceRef(), t.sellerOrgId(), t.jurisdiction(), t.localJurisdiction(), t.currency(),
                    t.taxableMinor(), t.taxMinor(), t.occurredAt());
        }
    }

    @Schema(name = "TaxReversalLine")
    record ReversalLineDto(@NotBlank String lineId, @Min(0) long taxableMinor, @Min(0) long taxMinor) {
    }

    @Schema(name = "TaxReversal", description = "Reverse tax for a refund made outside the returns flow")
    record ReversalRequest(@NotNull UUID orderId, @NotBlank @Size(max = 80) String refundRef,
                           @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                           @NotEmpty @Size(max = 200) List<@Valid ReversalLineDto> lines) {
    }

    @Schema(name = "TaxReversalResult")
    record ReversalResponse(int linesBooked) {
    }
}
