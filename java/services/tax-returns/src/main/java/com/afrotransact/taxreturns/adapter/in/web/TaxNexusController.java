package com.afrotransact.taxreturns.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.afrotransact.taxreturns.application.TaxNexusService;
import com.afrotransact.taxreturns.application.TaxNexusService.UpdateNexus;
import com.afrotransact.taxreturns.domain.tax.NexusRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Where sales into each state stand against its thresholds, and where AfroTransact is registered (staff). */
@Tag(name = "Tax nexus", description = "Sales into each state against its thresholds, and registrations")
@RestController
class TaxNexusController {

    private final TaxNexusService nexus;

    TaxNexusController(TaxNexusService nexus) {
        this.nexus = nexus;
    }

    @Schema(name = "TaxNexusState")
    record NexusDto(String jurisdiction, String name, boolean facilitator,
                    @Schema(description = "Gross sales shipped there over the trailing 12 months, net of refunds")
                    long salesMinor, long orders, long salesThresholdMinor, Integer transactionsThreshold,
                    @Schema(description = "The higher of the sales and transaction percentages of their thresholds")
                    int percent,
                    @Schema(allowableValues = {"REGISTERED", "EXCEEDED", "APPROACHING", "BELOW"}) String status,
                    boolean registered, String registrationNumber, LocalDate registeredOn, String note,
                    @Schema(description = "Staff changed this state's thresholds or registration") boolean customised,
                    String updatedBy, Instant updatedAt) {

        static NexusDto of(NexusRow r) {
            return new NexusDto(r.jurisdiction(), r.name(), r.facilitator(), r.salesMinor(), r.orders(),
                    r.salesThresholdMinor(), r.transactionsThreshold(), r.percent(), r.status().name(), r.registered(),
                    r.registrationNumber(), r.registeredOn(), r.note(), r.customised(), r.updatedBy(), r.updatedAt());
        }
    }

    record NexusResponse(String through, int windowMonths, long defaultSalesThresholdMinor, List<NexusDto> states) {
    }

    record UpdateRequest(@PositiveOrZero long salesThresholdMinor, @PositiveOrZero Integer transactionsThreshold,
                         boolean registered, String registrationNumber, LocalDate registeredOn, String note) {
    }

    @Operation(summary = "Nexus tracker: sales into each state over the last 12 months against its thresholds (tax:read)",
            description = "Most urgent first: EXCEEDED (past a threshold and not registered), APPROACHING (80 % or more), "
                    + "REGISTERED, BELOW. Thresholds are the defaults until staff set a state's own; confirm them with a "
                    + "tax advisor. This tracks and warns; it registers nobody.")
    @GetMapping("/v1/tax/nexus")
    NexusResponse tracker(@RequestParam(required = false) String through) {
        YearMonth end = through == null ? YearMonth.now() : month(through);
        return new NexusResponse(end.toString(), TaxNexusService.WINDOW_MONTHS,
                TaxNexusService.DEFAULT_SALES_THRESHOLD_MINOR,
                nexus.tracker(end).stream().map(NexusDto::of).toList());
    }

    @Operation(summary = "One state (tax:read)")
    @GetMapping("/v1/tax/nexus/{jurisdiction}")
    NexusDto state(@PathVariable String jurisdiction, @RequestParam(required = false) String through) {
        return NexusDto.of(nexus.state(through == null ? YearMonth.now() : month(through), jurisdiction));
    }

    @Operation(summary = "Record a state's thresholds and registration (tax:configure)",
            description = "A registration needs its number and the date it took effect. 422 tax.invalid-nexus otherwise.")
    @PutMapping("/v1/tax/nexus/{jurisdiction}")
    NexusDto update(@PathVariable @NotBlank String jurisdiction, @Valid @RequestBody UpdateRequest body) {
        return NexusDto.of(nexus.update(new UpdateNexus(null, jurisdiction, body.salesThresholdMinor(),
                body.transactionsThreshold(), body.registered(), body.registrationNumber(), body.registeredOn(),
                body.note())));
    }

    private static YearMonth month(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("through must be YYYY-MM");
        }
    }
}
