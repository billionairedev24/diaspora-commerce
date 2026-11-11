package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.application.ReviewSettingsService;
import com.afrotransact.seller.application.ReviewSettingsService.Current;
import com.afrotransact.seller.domain.AutoAssignMode;
import com.afrotransact.seller.domain.EscalationChain;
import com.afrotransact.seller.domain.ReviewSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Application review settings", description = "SLAs, escalation chains and assignment mode of the "
        + "application review. Read: anyone who views applications. Change: applications:assign, with a reason; "
        + "every change is audited.")
@RestController
class ReviewSettingsController {

    private final ReviewSettingsService settings;

    ReviewSettingsController(ReviewSettingsService settings) {
        this.settings = settings;
    }

    @Operation(summary = "Current SLAs, escalation chains, assignment mode and the last changes")
    @GetMapping("/v1/application-review-settings")
    SettingsResponse get() {
        return SettingsResponse.of(settings.current());
    }

    @Operation(summary = "Change them (reason required; levels in order; targets must exist)")
    @PutMapping("/v1/application-review-settings")
    SettingsResponse put(@Valid @RequestBody SettingsRequest body) {
        ReviewSettings next = new ReviewSettings(body.reviewSlaHours(), body.approvalSlaHours(),
                body.dueSoonPercent(), body.unassignedAfterHours(), body.level2AfterHours(), body.autoAssign(),
                body.enforceAssignee(), body.sendBackToReviewer(), body.reviewChain(), body.approvalChain(), null,
                null, null, null);
        return SettingsResponse.of(settings.update(next, body.reason(), body.actorName()));
    }

    @Schema(name = "ReviewSettingsChange")
    record SettingsRequest(int reviewSlaHours, int approvalSlaHours, int dueSoonPercent, int unassignedAfterHours,
                           int level2AfterHours, AutoAssignMode autoAssign, boolean enforceAssignee,
                           boolean sendBackToReviewer, EscalationChain reviewChain, EscalationChain approvalChain,
                           @NotBlank @Size(max = 500) String reason, @Size(max = 200) String actorName) {
    }

    @Schema(name = "ReviewSettings")
    record SettingsResponse(int reviewSlaHours, int approvalSlaHours, int dueSoonPercent, int unassignedAfterHours,
                            int level2AfterHours, AutoAssignMode autoAssign, boolean enforceAssignee,
                            boolean sendBackToReviewer, EscalationChain reviewChain, EscalationChain approvalChain,
                            List<String> reviewLevel1People, List<String> reviewLevel2People,
                            List<String> approvalLevel1People, List<String> approvalLevel2People,
                            String updatedByName, Instant updatedAt, List<Change> changes) {

        static SettingsResponse of(Current c) {
            ReviewSettings s = c.settings();
            return new SettingsResponse(s.reviewSlaHours(), s.approvalSlaHours(), s.dueSoonPercent(),
                    s.unassignedAfterHours(), s.level2AfterHours(), s.autoAssign(), s.enforceAssignee(),
                    s.sendBackToReviewer(), s.reviewChain(), s.approvalChain(), c.reviewLevel1People(),
                    c.reviewLevel2People(), c.approvalLevel1People(), c.approvalLevel2People(), s.updatedByName(),
                    s.updatedAt(), c.changes().stream().map(x -> new Change(x.actorName(), x.reason(),
                    x.changedAt())).toList());
        }
    }

    @Schema(name = "ReviewSettingsAudit")
    record Change(String actorName, String reason, Instant changedAt) {
    }
}
