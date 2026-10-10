package com.afrotransact.trustops.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.trustops.adapter.in.web.CommonDtos.SlaResponse;
import com.afrotransact.trustops.application.SupportCaseView;
import com.afrotransact.trustops.domain.SupportCase;
import com.afrotransact.trustops.domain.SupportCaseStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Support case DTOs. */
final class SupportCaseDtos {

    private SupportCaseDtos() {
    }

    @Schema(description = "A support case opened from a Lola hand-off (summary redacted by ai-gateway)")
    record SupportCaseResponse(UUID id, String reference, String source, UUID handoffId, String sessionId,
                               String customerId, String reason, String summary, SupportCaseStatus status,
                               String assignee, SlaResponse sla, Instant openedAt, Instant resolvedAt,
                               String resolution, long version) {

        static SupportCaseResponse of(SupportCaseView v) {
            SupportCase c = v.supportCase();
            return new SupportCaseResponse(c.id(), c.reference(), c.source(), c.handoffId(), c.sessionId(),
                    c.customerId(), c.reason(), c.summary(), c.status(), c.assignee(), SlaResponse.of(v.sla()),
                    c.openedAt(), c.resolvedAt(), c.resolution(), c.version());
        }
    }

    @Schema(description = "Assign; omit assignee to take it yourself")
    record AssignCaseRequest(@Size(max = 120) String assignee) {
    }

    @Schema(description = "Resolve with what was done")
    record ResolveRequest(@NotBlank @Size(max = 2000) String resolution) {
    }
}
