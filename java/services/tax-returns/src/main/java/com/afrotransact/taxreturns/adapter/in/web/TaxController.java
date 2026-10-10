package com.afrotransact.taxreturns.adapter.in.web;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.taxreturns.adapter.in.web.TaxDtos.LiabilityResponse;
import com.afrotransact.taxreturns.adapter.in.web.TaxDtos.LocalRateDto;
import com.afrotransact.taxreturns.adapter.in.web.TaxDtos.QuoteRequest;
import com.afrotransact.taxreturns.adapter.in.web.TaxDtos.QuoteResponse;
import com.afrotransact.taxreturns.adapter.in.web.TaxDtos.RatesResponse;
import com.afrotransact.taxreturns.adapter.in.web.TaxDtos.ReversalRequest;
import com.afrotransact.taxreturns.adapter.in.web.TaxDtos.ReversalResponse;
import com.afrotransact.taxreturns.adapter.in.web.TaxDtos.StateRuleDto;
import com.afrotransact.taxreturns.adapter.in.web.TaxDtos.TransactionDto;
import com.afrotransact.taxreturns.application.LiabilityReport;
import com.afrotransact.taxreturns.application.RateTableView;
import com.afrotransact.taxreturns.application.ReversalLine;
import com.afrotransact.taxreturns.application.TaxLedgerService;
import com.afrotransact.taxreturns.application.TaxService;
import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tax", description = "Marketplace-facilitator sales tax: quotes, rate table, ledger and liability reports")
@RestController
class TaxController {

    private static final MediaType CSV = new MediaType("text", "csv");

    private final TaxService tax;
    private final TaxLedgerService ledger;

    TaxController(TaxService tax, TaxLedgerService ledger) {
        this.tax = tax;
        this.ledger = ledger;
    }

    @Operation(summary = "Quote tax per line for a cart or order (tax:quote; orders-service at checkout)",
            description = "rate = state rate for the category + local rate for the ZIP; tax = taxable × rate rounded "
                    + "half up once per line. Non-facilitator states and non-US addresses get no tax.")
    @PostMapping("/v1/tax/quotes")
    QuoteResponse quote(@Valid @RequestBody QuoteRequest body) {
        return QuoteResponse.of(tax.quote(body.toCommand()));
    }

    @Operation(summary = "The rate table: state rules and local ZIP rates (tax:read)")
    @GetMapping("/v1/tax/rates")
    RatesResponse rates() {
        RateTableView v = tax.rates();
        return new RatesResponse(v.states().stream().map(StateRuleDto::of).toList(),
                v.locals().stream().map(LocalRateDto::of).toList());
    }

    @Operation(summary = "Create or update a state rule (tax:configure; version from the last read to update)")
    @PutMapping("/v1/tax/rates/states/{state}")
    StateRuleDto putState(@PathVariable String state, @Valid @RequestBody StateRuleDto body) {
        return StateRuleDto.of(tax.upsertState(body.toDomain(state.toUpperCase(Locale.ROOT))));
    }

    @Operation(summary = "Create or update a local rate for a 5-digit ZIP or 3-digit prefix (tax:configure)")
    @PutMapping("/v1/tax/rates/local/{state}/{zipPrefix}")
    LocalRateDto putLocal(@PathVariable String state, @PathVariable String zipPrefix,
                          @Valid @RequestBody LocalRateDto body) {
        return LocalRateDto.of(tax.upsertLocal(new LocalTaxRate(state.toUpperCase(Locale.ROOT), zipPrefix,
                body.name(), body.rate(), body.appliesToGrocery())));
    }

    @Operation(summary = "Delete a local rate (tax:configure)")
    @DeleteMapping("/v1/tax/rates/local/{state}/{zipPrefix}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteLocal(@PathVariable String state, @PathVariable String zipPrefix) {
        tax.deleteLocal(state, zipPrefix);
    }

    @Operation(summary = "Monthly marketplace liability per jurisdiction (tax:read)")
    @GetMapping("/v1/tax/liability")
    LiabilityResponse liability(@RequestParam String month) {
        return LiabilityResponse.of(ledger.liability(month(month)));
    }

    @Operation(summary = "Monthly liability as CSV (tax:read)")
    @GetMapping(value = "/v1/tax/liability/export", produces = "text/csv")
    ResponseEntity<String> liabilityCsv(@RequestParam String month) {
        LiabilityReport report = ledger.liability(month(month));
        return csv(report, "tax-liability-" + report.month() + ".csv");
    }

    @Operation(summary = "Tax collected on a seller's behalf per jurisdiction, for the monthly statement "
            + "(the seller with payouts:read, or staff with tax:read)")
    @GetMapping("/v1/tax/sellers/{orgId}/statement")
    LiabilityResponse sellerStatement(@PathVariable String orgId, @RequestParam String month) {
        return LiabilityResponse.of(ledger.sellerStatement(orgId, month(month)));
    }

    @Operation(summary = "Seller statement as CSV")
    @GetMapping(value = "/v1/tax/sellers/{orgId}/statement/export", produces = "text/csv")
    ResponseEntity<String> sellerStatementCsv(@PathVariable String orgId, @RequestParam String month) {
        LiabilityReport report = ledger.sellerStatement(orgId, month(month));
        return csv(report, "tax-statement-" + orgId.replaceAll("[^A-Za-z0-9_-]", "") + "-" + report.month() + ".csv");
    }

    @Operation(summary = "Tax ledger entries of an order: sales and refund reversals (tax:read)")
    @GetMapping("/v1/tax/orders/{orderId}/transactions")
    List<TransactionDto> transactions(@PathVariable UUID orderId) {
        return ledger.forOrder(orderId).stream().map(TransactionDto::of).toList();
    }

    @Operation(summary = "Reverse tax for a refund made outside the returns flow (refunds:issue; idempotent per "
            + "refundRef and line). Refunds published as payments.refunded are reversed automatically.")
    @PostMapping("/v1/tax/reversals")
    ReversalResponse reverse(@Valid @RequestBody ReversalRequest body) {
        return new ReversalResponse(ledger.reverse(body.orderId(), body.refundRef(), body.currency(),
                body.lines().stream().map(l -> new ReversalLine(l.lineId(), l.taxableMinor(), l.taxMinor()))
                        .toList()));
    }

    private static ResponseEntity<String> csv(LiabilityReport report, String filename) {
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(LiabilityCsv.write(report));
    }

    private static YearMonth month(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("month must be YYYY-MM");
        }
    }
}
