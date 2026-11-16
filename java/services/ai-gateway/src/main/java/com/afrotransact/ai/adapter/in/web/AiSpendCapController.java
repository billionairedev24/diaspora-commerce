package com.afrotransact.ai.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.ai.application.AiSpendCapPolicy.Source;
import com.afrotransact.ai.application.SpendCapService;
import com.afrotransact.ai.application.SpendCapService.SpendCapView;
import com.afrotransact.ai.domain.AiRuleViolation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI spend cap", description = "The gateway-wide daily AI spend cap, editable at runtime by staff with ai:cap.manage")
@RestController
class AiSpendCapController {

    private final SpendCapService caps;
    private final CallerResolver callers;

    AiSpendCapController(SpendCapService caps, CallerResolver callers) {
        this.caps = caps;
        this.callers = callers;
    }

    @Schema(name = "AiSpendCapChange", description = "One saved change of the cap (audit trail, newest first)")
    record ChangeResponse(@Schema(description = "Version this change created") long version,
                          @Schema(description = "Cap before, micro-dollars; null when the value before was the deployment default",
                                  nullable = true) Long previousMicros,
                          @Schema(description = "True when the value before was the deployment default") boolean previousWasDefault,
                          @Schema(description = "Cap after, micro-dollars (0 = AI off)") long newMicros,
                          @Schema(description = "Staff subject (identity user id) who saved it") String changedBy,
                          Instant changedAt, @Schema(description = "Why, 3 to 300 characters") String reason) {
    }

    @Schema(name = "AiSpendCap", description = "The daily cap in force, where it comes from, the ceiling, today's spend and the history")
    record SpendCapResponse(@Schema(description = "Cap in force, micro-dollars per UTC day (1,000,000 = USD 1); 0 = AI off")
                            long capMicros,
                            @Schema(description = "default until a value is saved, then saved") String source,
                            @Schema(description = "Deployment default (AI_GLOBAL_DAILY_COST_USD), used until a value is saved")
                            long defaultMicros,
                            @Schema(description = "Hard ceiling (AI_GLOBAL_DAILY_COST_MAX_USD); a saved value above it is refused")
                            long ceilingMicros,
                            @Schema(description = "ETag of the saved value; 0 while the default is in force. Send it as If-Match")
                            long version,
                            @Schema(description = "Staff subject who saved the value in force; null for the default", nullable = true)
                            String changedBy,
                            @Schema(nullable = true) Instant changedAt, @Schema(nullable = true) String reason,
                            @Schema(description = "UTC day the spend is for") LocalDate day,
                            @Schema(description = "Spent so far today, micro-dollars: the figure the cap is checked against")
                            long spentMicros,
                            @Schema(description = "cap minus spent, never below 0") long remainingMicros,
                            @Schema(description = "Next UTC midnight, when the day's total starts again") Instant resetsAt,
                            @Schema(description = "The caller holds ai:cap.manage") boolean canChange,
                            List<ChangeResponse> history) {

        static SpendCapResponse of(SpendCapView v) {
            return new SpendCapResponse(v.capMicros(), v.source() == Source.SAVED ? "saved" : "default", v.defaultMicros(),
                    v.ceilingMicros(), v.version(), v.changedBy(), v.changedAt(), v.reason(), v.day(), v.spentMicros(),
                    v.remainingMicros(), v.resetsAt(), v.canChange(), v.history().stream()
                            .map(c -> new ChangeResponse(c.version(), c.previousMicros(), c.previousWasDefault(),
                                    c.newMicros(), c.changedBy(), c.changedAt(), c.reason())).toList());
        }
    }

    @Schema(name = "AiSpendCapUpdate")
    record UpdateRequest(@Schema(description = "New cap, micro-dollars per UTC day: a whole number of cents (multiple of 10,000), "
            + "0 (AI off) up to the ceiling", examples = "2000000") Long capMicros,
                         @Schema(description = "Why, 3 to 300 characters; logged with who and when", examples = "Launch week") String reason) {
    }

    @Operation(summary = "The daily AI spend cap, its source and ceiling, today's spend and the change history",
            description = "Staff with ai:manage or ai:cap.manage. The ETag is the version to send as If-Match when changing it. "
                    + "UTC day; resets at 00:00 UTC.")
    @GetMapping("/v1/ai/spend-cap")
    ResponseEntity<SpendCapResponse> get(HttpServletRequest request) {
        SpendCapResponse body = SpendCapResponse.of(caps.view(callers.current(request)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"" + body.version() + "\"").body(body);
    }

    @Operation(summary = "Change the daily AI spend cap",
            description = "Staff with ai:cap.manage only. If-Match (the version from GET) is required: 428 without it, 412 when "
                    + "someone else saved first. The value is a whole number of cents between 0 (AI off) and the ceiling, "
                    + "the reason 3 to 300 characters. Takes effect within seconds on every replica, at once on the one "
                    + "that saved. Lowering below today's spend blocks further AI calls immediately; raising applies to the same day.")
    @PutMapping("/v1/ai/spend-cap")
    ResponseEntity<SpendCapResponse> put(@RequestBody UpdateRequest body,
                                         @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                         HttpServletRequest request) {
        if (body == null || body.capMicros() == null) {
            throw new AiRuleViolation("ai.spend-cap.invalid", "capMicros is required");
        }
        SpendCapResponse saved = SpendCapResponse.of(caps.update(callers.current(request), IfMatch.expected(ifMatch),
                body.capMicros(), body.reason()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"" + saved.version() + "\"").body(saved);
    }

    /** {@code If-Match: "3"} names the version the editor loaded; {@code *} and blank mean none was sent. */
    private static final class IfMatch {

        static Long expected(String ifMatch) {
            if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
                return null;
            }
            String v = ifMatch.trim();
            if (v.startsWith("W/")) {
                v = v.substring(2);
            }
            try {
                return Long.parseLong(v.replace("\"", ""));
            } catch (NumberFormatException e) {
                throw new AiRuleViolation("ai.spend-cap.invalid-if-match", "If-Match must be the ETag, for example \"2\"");
            }
        }
    }
}
