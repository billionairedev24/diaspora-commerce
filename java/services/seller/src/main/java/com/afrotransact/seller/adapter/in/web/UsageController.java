package com.afrotransact.seller.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.application.UsageMeteringService;
import com.afrotransact.seller.application.UsageRunView;
import com.afrotransact.seller.application.UsageStatement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Metered usage", description = "FBA storage, sponsored-products spend and AI credits billed on Stripe "
        + "Billing meters (S206)")
@RestController
class UsageController {

    private final UsageMeteringService metering;

    UsageController(UsageMeteringService metering) {
        this.metering = metering;
    }

    @Schema(name = "UsageDayLine")
    record DayLine(LocalDate day, BigDecimal quantity, BigDecimal reportedQuantity,
                   @Schema(allowableValues = {"REPORTED", "PENDING", "CORRECTION"}) String status, String error) {
    }

    @Schema(name = "UsageMeterLine")
    record MeterLine(@Schema(allowableValues = {"FBA_STORAGE", "ADS_SPEND", "AI_CREDITS"}) String meter,
                     @Schema(examples = "FBA storage") String label,
                     @Schema(description = "Unit of quantity", examples = "cubic-foot-day") String unit,
                     BigDecimal quantity,
                     @Schema(description = "What Stripe Billing has received") BigDecimal reportedQuantity,
                     @Schema(description = "Estimated price of one unit in micro-dollars") long unitPriceMicros,
                     long estimatedAmountCents, List<DayLine> days) {

        static MeterLine of(UsageStatement.MeterLine m) {
            return new MeterLine(m.meter().name(), m.meter().label(), m.meter().displayUnit(), m.quantity(),
                    m.reportedQuantity(), m.unitPriceMicros(), m.estimatedAmountCents(), m.days().stream()
                    .map(d -> new DayLine(d.day(), d.quantity(), d.reportedQuantity(), d.status(), d.error()))
                    .toList());
        }
    }

    @Schema(name = "UsageStatement", description = "Metered usage for a period; the Stripe invoice is authoritative")
    record StatementResponse(String orgId, LocalDate from, LocalDate to, String currency, List<MeterLine> meters,
                             long estimatedTotalCents) {

        static StatementResponse of(UsageStatement s) {
            return new StatementResponse(s.orgId(), s.from(), s.to(), s.currency(),
                    s.meters().stream().map(MeterLine::of).toList(), s.estimatedTotalCents());
        }
    }

    @Schema(name = "UsageRunRequest")
    record RunRequest(@NotNull LocalDate from, @NotNull LocalDate to) {
    }

    @Schema(name = "UsageRun")
    record RunResponse(UUID id, LocalDate from, LocalDate to,
                       @Schema(allowableValues = {"JOB", "STAFF"}) String trigger,
                       @Schema(allowableValues = {"RUNNING", "COMPLETED", "PARTIAL", "FAILED"}) String status,
                       int rowsChanged, int reported, int pending, int corrections, List<String> failedSources,
                       Instant startedAt, Instant finishedAt) {

        static RunResponse of(UsageRunView r) {
            return new RunResponse(r.id(), r.from(), r.to(), r.trigger(), r.status(), r.rowsChanged(), r.reported(),
                    r.pending(), r.corrections(), r.failedSources(), r.startedAt(), r.finishedAt());
        }
    }

    @Operation(summary = "Usage statement: metered usage per meter and day (owner / finance; staff)",
            description = "Defaults to the current subscription period (month to date without one). Owner and "
                    + "finance members; staff with seller:billing or billing:usage.")
    @GetMapping("/v1/orgs/{orgId}/billing/usage")
    StatementResponse statement(@PathVariable String orgId,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                LocalDate to) {
        return StatementResponse.of(metering.statement(orgId, from, to));
    }

    @Operation(summary = "Re-run metering for a period (staff with billing:usage)",
            description = "Re-reads every source for [from, to] (at most 93 days, ending today at the latest), stores "
                    + "each day's usage and sends Stripe meter events for the differences only. Idempotent; 409 "
                    + "seller.usage-run-in-progress while another run holds the lease.")
    @PostMapping("/v1/billing/usage/runs")
    RunResponse run(@Valid @RequestBody RunRequest body) {
        return RunResponse.of(metering.run(body.from(), body.to()));
    }

    @Operation(summary = "Recent metering runs (staff with billing:usage)")
    @GetMapping("/v1/billing/usage/runs")
    List<RunResponse> runs() {
        return metering.runs().stream().map(RunResponse::of).toList();
    }
}
