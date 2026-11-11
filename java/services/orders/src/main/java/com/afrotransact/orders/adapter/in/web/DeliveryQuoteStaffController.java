package com.afrotransact.orders.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.afrotransact.orders.application.DeliveryQuoteDeskService;
import com.afrotransact.orders.application.DeliveryQuoteSettingsService;
import com.afrotransact.orders.application.DeliveryQuoteSettingsService.Current;
import com.afrotransact.orders.application.QuoteCaseView;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.QuoteSettings;
import com.afrotransact.orders.domain.QuoteSettings.AutoAssign;
import com.afrotransact.orders.domain.QuoteSettings.Target;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Delivery quotes (staff)", description = "The customer care queue for delivery quote requests: who has "
        + "each case, SLA and escalation, agree an amount or decline, with a reason. View: delivery-quotes:view; "
        + "claim, agree, decline: delivery-quotes:agree; assign, take over and settings: delivery-quotes:assign.")
@RestController
class DeliveryQuoteStaffController {

    private final DeliveryQuoteDeskService desk;
    private final DeliveryQuoteSettingsService settings;

    DeliveryQuoteStaffController(DeliveryQuoteDeskService desk, DeliveryQuoteSettingsService settings) {
        this.desk = desk;
        this.settings = settings;
    }

    @Operation(summary = "The queue: my queue, unassigned, waiting on the customer, closed, all",
            description = "queue = MY | UNASSIGNED | WAITING | CLOSED | ALL; chips overdue, unassigned, escalated; "
                    + "q searches the reference, the assignee's name and the place.")
    @GetMapping("/v1/staff/delivery-quotes")
    QuoteCaseView.Page queue(@RequestParam(defaultValue = "ALL") String queue,
                             @RequestParam(required = false) String assignee,
                             @RequestParam(defaultValue = "false") boolean unassigned,
                             @RequestParam(defaultValue = "false") boolean overdue,
                             @RequestParam(defaultValue = "false") boolean escalated,
                             @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "25") int size) {
        return desk.queue(queue, assignee, unassigned, overdue, escalated, q, page, size);
    }

    @Operation(summary = "One case with its timeline")
    @GetMapping("/v1/staff/delivery-quotes/{id}")
    QuoteCaseView detail(@PathVariable String id) {
        return desk.detail(id);
    }

    @Operation(summary = "Claim, assign, release or escalate a case")
    @PostMapping("/v1/staff/delivery-quotes/{id}/assignment")
    QuoteCaseView assignment(@PathVariable String id, @Valid @RequestBody AssignmentRequest body) {
        String action = body.action().trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case "CLAIM" -> desk.claim(id, body.actorName());
            case "ASSIGN" -> desk.assign(id, body.assigneeId(), body.note(), body.actorName());
            case "RELEASE" -> desk.release(id, body.note(), body.actorName());
            case "ESCALATE" -> desk.escalate(id, body.reason(), body.actorName());
            default -> throw new OrdersRuleViolation("orders.invalid-action", "Unknown assignment action "
                    + body.action());
        };
    }

    @Operation(summary = "People the case can be assigned to now")
    @GetMapping("/v1/staff/delivery-quotes/{id}/assignment/candidates")
    List<Person> candidates(@PathVariable String id) {
        return desk.candidates(id).stream().map(p -> new Person(p.id(), p.name())).toList();
    }

    @Operation(summary = "Agree an amount (reason required, within the settings' bounds)",
            description = "The quote is then ready for the shopper for the configured validity (default 48 hours). "
                    + "Repeating the same call by the same person changes nothing.")
    @PostMapping("/v1/staff/delivery-quotes/{id}/agree")
    QuoteCaseView agree(@PathVariable String id, @Valid @RequestBody AgreeRequest body) {
        return desk.agree(id, body.amountMinor(), body.reason(), body.actorName());
    }

    @Operation(summary = "Decline to quote (reason required)")
    @PostMapping("/v1/staff/delivery-quotes/{id}/decline")
    QuoteCaseView decline(@PathVariable String id, @Valid @RequestBody DeclineRequest body) {
        return desk.decline(id, body.reason(), body.actorName());
    }

    @Operation(summary = "SLA, escalation, hand-out, amount bounds and validity, with the last changes")
    @GetMapping("/v1/staff/delivery-quote-settings")
    SettingsResponse settings() {
        return SettingsResponse.of(settings.current());
    }

    @Operation(summary = "Change them (delivery-quotes:assign; reason required; audited)")
    @PutMapping("/v1/staff/delivery-quote-settings")
    SettingsResponse updateSettings(@Valid @RequestBody SettingsRequest body) {
        QuoteSettings next = new QuoteSettings(body.slaBusinessDays(), body.dueSoonPercent(),
                body.unassignedAfterHours(), body.level2AfterHours(), body.autoAssign(), null, body.level1(),
                body.level2(), body.minAmountMinor(), body.maxAmountMinor(), body.validityHours(),
                body.requestsPerHour(), body.duplicateWindowMinutes(), null, null);
        return SettingsResponse.of(settings.update(next, body.reason(), body.actorName()));
    }

    @Schema(name = "QuoteAssignmentRequest")
    record AssignmentRequest(@NotBlank @Schema(allowableValues = {"CLAIM", "ASSIGN", "RELEASE", "ESCALATE"})
                             String action, String assigneeId, @Size(max = 500) String note,
                             @Size(max = 500) String reason,
                             @Size(max = 200) @Schema(description = "The staff member's display name, recorded on "
                                     + "the timeline (display only)") String actorName) {
    }

    @Schema(name = "QuoteAgreeRequest")
    record AgreeRequest(@PositiveOrZero long amountMinor, @NotBlank @Size(max = 500) String reason,
                        @Size(max = 200) String actorName) {
    }

    @Schema(name = "QuoteDeclineRequest")
    record DeclineRequest(@NotBlank @Size(max = 500) String reason, @Size(max = 200) String actorName) {
    }

    @Schema(name = "QuoteStaffPerson")
    record Person(String id, String name) {
    }

    @Schema(name = "DeliveryQuoteSettingsChange")
    record SettingsRequest(int slaBusinessDays, int dueSoonPercent, int unassignedAfterHours, int level2AfterHours,
                           AutoAssign autoAssign, Target level1, Target level2, long minAmountMinor,
                           long maxAmountMinor, int validityHours, int requestsPerHour, int duplicateWindowMinutes,
                           @NotBlank @Size(max = 500) String reason, @Size(max = 200) String actorName) {
    }

    @Schema(name = "DeliveryQuoteSettings")
    record SettingsResponse(int slaBusinessDays, int dueSoonPercent, int unassignedAfterHours, int level2AfterHours,
                            AutoAssign autoAssign, Target level1, Target level2, List<String> level1People,
                            List<String> level2People, long minAmountMinor, long maxAmountMinor, int validityHours,
                            int requestsPerHour, int duplicateWindowMinutes, String updatedByName, Instant updatedAt,
                            List<Change> changes) {

        static SettingsResponse of(Current c) {
            QuoteSettings s = c.settings();
            return new SettingsResponse(s.slaBusinessDays(), s.dueSoonPercent(), s.unassignedAfterHours(),
                    s.level2AfterHours(), s.autoAssign(), s.level1(), s.level2(), c.level1People(),
                    c.level2People(), s.minAmountMinor(), s.maxAmountMinor(), s.validityHours(), s.requestsPerHour(),
                    s.duplicateWindowMinutes(), s.updatedByName(), s.updatedAt(), c.changes().stream()
                    .map(x -> new Change(x.actorName(), x.reason(), x.changedAt())).toList());
        }
    }

    @Schema(name = "DeliveryQuoteSettingsAudit")
    record Change(String actorName, String reason, Instant changedAt) {
    }
}
