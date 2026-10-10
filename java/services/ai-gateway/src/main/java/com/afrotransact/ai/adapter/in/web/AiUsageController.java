package com.afrotransact.ai.adapter.in.web;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.ai.application.OrgUsageService;
import com.afrotransact.ai.domain.AiCredits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI usage", description = "AI credits per organisation for metered billing (S206)")
@RestController
class AiUsageController {

    private final OrgUsageService usage;
    private final CallerResolver callers;

    AiUsageController(OrgUsageService usage, CallerResolver callers) {
        this.usage = usage;
        this.callers = callers;
    }

    @Schema(name = "AiOrgDayUsage", description = "AI usage of one organisation on one UTC day")
    record OrgDayUsageResponse(@Schema(examples = "org_3k2j9x8q7w6e5r4t3y2u") String orgId,
                               @Schema(description = "UTC day") LocalDate day, long calls, long tokens,
                               @Schema(description = "Estimated model cost in micro-dollars") long costMicros,
                               @Schema(description = "AI credits (1 credit = 10,000 micro-dollars, rounded up per day)")
                               long credits) {

        static OrgDayUsageResponse of(OrgUsageService.OrgDayCredits c) {
            return new OrgDayUsageResponse(c.orgId(), c.day(), c.calls(), c.tokens(), c.costMicros(), c.credits());
        }
    }

    @Schema(name = "AiOrgUsageReport")
    record UsageResponse(LocalDate from, LocalDate to, long microsPerCredit, List<OrgDayUsageResponse> items) {
    }

    @Operation(summary = "AI credits per organisation and UTC day (metered billing, S206)",
            description = "Service scope ai:usage.read (seller-service) or staff with ai:manage. Only calls made by "
                    + "seller and provider members are attributed to their organisation. Inclusive range of at most "
                    + "93 days; only days with usage are listed.")
    @GetMapping("/v1/ai/usage/orgs")
    ResponseEntity<UsageResponse> orgs(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       HttpServletRequest request) {
        UsageResponse body = new UsageResponse(from, to, AiCredits.MICROS_PER_CREDIT,
                usage.usage(callers.current(request), from, to).stream().map(OrgDayUsageResponse::of).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
