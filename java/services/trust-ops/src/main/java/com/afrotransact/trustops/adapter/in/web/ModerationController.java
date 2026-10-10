package com.afrotransact.trustops.adapter.in.web;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.afrotransact.trustops.adapter.in.web.CommonDtos.PageResponse;
import com.afrotransact.trustops.adapter.in.web.ModerationDtos.ApproveRequest;
import com.afrotransact.trustops.adapter.in.web.ModerationDtos.AssignRequest;
import com.afrotransact.trustops.adapter.in.web.ModerationDtos.EscalateRequest;
import com.afrotransact.trustops.adapter.in.web.ModerationDtos.ModerationItemResponse;
import com.afrotransact.trustops.adapter.in.web.ModerationDtos.ReasonCodeResponse;
import com.afrotransact.trustops.adapter.in.web.ModerationDtos.RejectRequest;
import com.afrotransact.trustops.application.ModerationService;
import com.afrotransact.trustops.domain.ModerationItemType;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.ModerationStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Listing moderation", description = "Queue of listings and booking reviews awaiting a human decision, "
        + "with SLA timers, assignment, AI triage hints, allow / remove with reason codes and escalation "
        + "(catalog:moderate; deciding a review also needs bookings:reviews:moderate)")
@RestController
class ModerationController {

    private final ModerationService moderation;

    ModerationController(ModerationService moderation) {
        this.moderation = moderation;
    }

    @Operation(summary = "Moderation queue, soonest due first (default: open items of both types)",
            description = "type=NEW_LISTING: submissions awaiting review; type=LIVE_LISTING: reported or flagged "
                    + "live listings (catalog.product.flagged); type=SERVICE_REVIEW: customer reviews of bookings "
                    + "(booking.review.submitted).")
    @GetMapping("/v1/moderation/items")
    PageResponse<ModerationItemResponse> queue(@RequestParam(required = false) ModerationStatus status,
                                               @RequestParam(required = false) String assignee,
                                               @RequestParam(defaultValue = "false") boolean mine,
                                               @RequestParam(required = false) ModerationItemType type,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return PageResponse.of(moderation.queue(status, assignee, mine, type, CommonDtos.page(page),
                CommonDtos.size(size)), ModerationItemResponse::of);
    }

    @Operation(summary = "One queue item")
    @GetMapping("/v1/moderation/items/{id}")
    ModerationItemResponse get(@PathVariable UUID id) {
        return ModerationItemResponse.of(moderation.get(id));
    }

    @Operation(summary = "Reason codes for removing an item",
            description = "Listing codes by default (and for type=NEW_LISTING or LIVE_LISTING); review codes for "
                    + "type=SERVICE_REVIEW. OTHER fits both and needs a note.")
    @GetMapping("/v1/moderation/reason-codes")
    List<ReasonCodeResponse> reasonCodes(@RequestParam(required = false) ModerationItemType type) {
        ModerationItemType kind = type == null ? ModerationItemType.NEW_LISTING : type;
        return Arrays.stream(ModerationReasonCode.values()).filter(c -> c.appliesTo(kind))
                .map(c -> new ReasonCodeResponse(c, c.label())).toList();
    }

    @Operation(summary = "Assign to a reviewer (default: me)")
    @PostMapping("/v1/moderation/items/{id}/assign")
    ModerationItemResponse assign(@PathVariable UUID id, @Valid @RequestBody(required = false) AssignRequest body) {
        return ModerationItemResponse.of(moderation.assign(id, body == null ? null : body.assignee()));
    }

    @Operation(summary = "Escalate to senior review (new escalation SLA)")
    @PostMapping("/v1/moderation/items/{id}/escalate")
    ModerationItemResponse escalate(@PathVariable UUID id, @Valid @RequestBody EscalateRequest body) {
        return ModerationItemResponse.of(moderation.escalate(id, body.reason()));
    }

    @Operation(summary = "Allow, then close the item (audited)",
            description = "NEW_LISTING: approves the product in catalog. LIVE_LISTING: keeps the listing live and "
                    + "dismisses the reports (no catalog call). SERVICE_REVIEW: publishes the review in bookings "
                    + "(POST /v1/reviews/{bookingId}/approve). If the owning service refuses, the item stays open "
                    + "(409).")
    @PostMapping("/v1/moderation/items/{id}/approve")
    ModerationItemResponse approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApproveRequest body) {
        return ModerationItemResponse.of(moderation.approve(id, body == null ? null : body.note()));
    }

    @Operation(summary = "Remove with a reason code, then close the item (audited)",
            description = "NEW_LISTING: rejects the product in catalog. LIVE_LISTING: takes the live listing down in "
                    + "catalog (POST /v1/products/{id}/takedown). SERVICE_REVIEW: rejects the review in bookings "
                    + "(POST /v1/reviews/{bookingId}/reject; review reason codes). If the owning service refuses, the "
                    + "item stays open (409).")
    @PostMapping("/v1/moderation/items/{id}/reject")
    ModerationItemResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest body) {
        return ModerationItemResponse.of(moderation.reject(id, body.reasonCode(), body.note()));
    }

    @Operation(summary = "Re-run AI triage (fail-open)")
    @PostMapping("/v1/moderation/items/{id}/triage")
    ModerationItemResponse retriage(@PathVariable UUID id) {
        return ModerationItemResponse.of(moderation.retriage(id));
    }
}
