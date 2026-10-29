package com.afrotransact.taxreturns.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.application.TaxRemittanceService;
import com.afrotransact.taxreturns.application.TaxRemittanceService.RecordRemittance;
import com.afrotransact.taxreturns.domain.tax.FilingObligation;
import com.afrotransact.taxreturns.domain.tax.TaxRemittance;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** What AfroTransact filed and paid to each state, month by month (staff, tax:read / tax:configure). */
@Tag(name = "Tax remittance", description = "The record of marketplace-facilitator tax filed and paid to each state")
@RestController
class TaxRemittanceController {

    private final TaxRemittanceService remittances;

    TaxRemittanceController(TaxRemittanceService remittances) {
        this.remittances = remittances;
    }

    @Schema(name = "TaxRemittance")
    record RemittanceDto(UUID id, String jurisdiction, String month, String currency, long dueMinor, long paidMinor,
                         LocalDate filedOn, LocalDate paidOn, String confirmationRef, String paymentRef, String note,
                         String recordedBy, Instant recordedAt, Instant voidedAt, String voidedBy, String voidReason,
                         boolean active) {

        static RemittanceDto of(TaxRemittance r) {
            return new RemittanceDto(r.id(), r.jurisdiction(), r.month().toString(), r.currency(), r.dueMinor(),
                    r.paidMinor(), r.filedOn(), r.paidOn(), r.confirmationRef(), r.paymentRef(), r.note(),
                    r.recordedBy(), r.recordedAt(), r.voidedAt(), r.voidedBy(), r.voidReason(), r.active());
        }
    }

    @Schema(name = "TaxFilingObligation")
    record ObligationDto(String month, String jurisdiction, String currency,
                         @Schema(description = "Net liability now: collected − refunded") long dueMinor,
                         @Schema(allowableValues = {"NOTHING_DUE", "NOT_FILED", "SETTLED", "OWES_MORE", "OVERPAID"})
                         String status,
                         @Schema(description = "Due now − paid; negative when overpaid") long outstandingMinor,
                         @Schema(description = "Due now − due when it was recorded: the ledger moved after filing")
                         long changedSinceFilingMinor, RemittanceDto remittance) {

        static ObligationDto of(FilingObligation o) {
            return new ObligationDto(o.month().toString(), o.jurisdiction(), o.currency(), o.dueMinor(),
                    o.status().name(), o.outstandingMinor(), o.changedSinceFilingMinor(),
                    o.remittance() == null ? null : RemittanceDto.of(o.remittance()));
        }
    }

    record ObligationsResponse(List<ObligationDto> obligations) {
    }

    record RecordRequest(@NotBlank @Schema(example = "2026-09") String month,
                         @NotBlank @Schema(example = "US-TX") String jurisdiction,
                         @Schema(example = "USD") String currency, @PositiveOrZero long paidMinor,
                         @NotNull LocalDate filedOn, @NotNull LocalDate paidOn, String confirmationRef,
                         String paymentRef, String note) {
    }

    record VoidRequest(@NotBlank String reason) {
    }

    @Operation(summary = "Month by month and state by state: tax due, remitted and outstanding (tax:read)",
            description = "The last `months` months (default 6) up to `through` (default this month), newest first. "
                    + "Due is the ledger's net liability now, so a refund or late sale after filing shows as "
                    + "changedSinceFilingMinor and moves the status to OWES_MORE or OVERPAID.")
    @GetMapping("/v1/tax/remittances")
    ObligationsResponse obligations(@RequestParam(required = false) String through,
                                    @RequestParam(defaultValue = "6") int months) {
        YearMonth end = through == null ? YearMonth.now() : month(through);
        return new ObligationsResponse(remittances.obligations(end, months).stream().map(ObligationDto::of).toList());
    }

    @Operation(summary = "One month and state (tax:read)")
    @GetMapping("/v1/tax/remittances/{month}/{jurisdiction}")
    ObligationDto obligation(@PathVariable String month, @PathVariable String jurisdiction,
                             @RequestParam(defaultValue = "USD") String currency) {
        return ObligationDto.of(remittances.obligation(month(month), jurisdiction, currency));
    }

    @Operation(summary = "Every record of a month, voided ones included (tax:read)")
    @GetMapping("/v1/tax/remittances/{month}/history")
    List<RemittanceDto> history(@PathVariable String month) {
        return remittances.history(month(month)).stream().map(RemittanceDto::of).toList();
    }

    @Operation(summary = "Record a filing and payment to a state (tax:configure)",
            description = "409 tax.remittance-exists when the month and state already have an active record (void it "
                    + "first); 422 for a future month or date. The amount due at the time is stored with it.")
    @PostMapping("/v1/tax/remittances")
    @ResponseStatus(HttpStatus.CREATED)
    RemittanceDto record(@Valid @RequestBody RecordRequest body) {
        return RemittanceDto.of(remittances.record(new RecordRemittance(month(body.month()), body.jurisdiction(),
                body.currency(), body.paidMinor(), body.filedOn(), body.paidOn(), body.confirmationRef(),
                body.paymentRef(), body.note())));
    }

    @Operation(summary = "Withdraw a mistaken record (tax:configure)",
            description = "The record stays visible as voided, with who and why. Record the correct one afterwards.")
    @PostMapping("/v1/tax/remittances/{id}/void")
    RemittanceDto voidRemittance(@PathVariable UUID id, @Valid @RequestBody VoidRequest body) {
        return RemittanceDto.of(remittances.voidRemittance(id, body.reason()));
    }

    private static YearMonth month(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("month must be YYYY-MM");
        }
    }
}
