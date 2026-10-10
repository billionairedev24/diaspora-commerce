package com.afrotransact.trustops.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.trustops.adapter.in.web.CommonDtos.SlaResponse;
import com.afrotransact.trustops.application.ClaimView;
import com.afrotransact.trustops.domain.Claim;
import com.afrotransact.trustops.domain.ClaimOutcome;
import com.afrotransact.trustops.domain.ClaimStatus;
import com.afrotransact.trustops.domain.ClaimType;
import com.afrotransact.trustops.domain.Evidence;
import com.afrotransact.trustops.port.ClaimHistoryEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Claim DTOs. */
final class ClaimDtos {

    private ClaimDtos() {
    }

    @Schema(description = "Open a claim on an order or one of its shipments. customerId only when staff open it "
            + "for a customer; evidence are media asset ids.")
    record OpenClaimRequest(@NotNull ClaimType type, @NotBlank @Size(max = 80) String orderId,
                            @Size(max = 40) String orderNumber, @Size(max = 80) String shipmentId,
                            @NotBlank @Size(max = 80) String sellerOrgId, @Size(max = 120) String customerId,
                            @Positive long amountMinor, @NotBlank @Size(min = 3, max = 3) String currency,
                            @NotBlank @Size(max = 4000) String description,
                            @Size(max = 20) List<@NotBlank @Size(max = 120) String> evidenceAssetIds) {
    }

    @Schema(description = "Add a media asset id as evidence")
    record EvidenceRequest(@NotBlank @Size(max = 120) String assetId, @Size(max = 500) String note) {
    }

    @Schema(description = "The seller's answer, with optional evidence asset ids")
    record SellerResponseRequest(@NotBlank @Size(max = 4000) String message,
                                 @Size(max = 20) List<@NotBlank @Size(max = 120) String> evidenceAssetIds) {
    }

    @Schema(description = "Escalate a claim")
    record EscalateClaimRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(description = "One order line to refund (minor units)")
    record RefundLineRequest(@NotBlank String lineId, @PositiveOrZero long itemsMinor,
                             @PositiveOrZero long shippingMinor, @PositiveOrZero long taxMinor) {
    }

    @Schema(description = "Decision: REFUND (paymentId + refundLines, sum ≤ claimed amount) or DENY (note required)")
    record DecisionRequest(@NotNull ClaimOutcome outcome, @Size(max = 2000) String note, UUID paymentId,
                           @Size(max = 200) List<@Valid RefundLineRequest> refundLines) {
    }

    @Schema(description = "A dispute / A-to-z claim")
    record ClaimResponse(UUID id, String reference, ClaimType type, ClaimStatus status, String orderId,
                         String orderNumber, String shipmentId, String sellerOrgId, String customerId,
                         long amountMinor, String currency, String description, List<Evidence> evidence,
                         String sellerResponse, Instant sellerRespondedAt, SlaResponse sla, boolean escalated,
                         String escalationReason, ClaimOutcome outcome, Long refundMinor, String refundId,
                         String decidedBy, Instant decidedAt, String decisionNote, Instant openedAt,
                         Instant updatedAt, long version) {

        static ClaimResponse of(ClaimView v) {
            Claim c = v.claim();
            return new ClaimResponse(c.id(), c.reference(), c.type(), c.status(), c.orderId(), c.orderNumber(),
                    c.shipmentId(), c.sellerOrgId(), c.customerId(), c.amountMinor(), c.currency(), c.description(),
                    c.evidence(), c.sellerResponse(), c.sellerRespondedAt(), SlaResponse.of(v.sla()), c.escalated(),
                    c.escalationReason(), c.outcome(), c.refundMinor(), c.refundId(), c.decidedBy(), c.decidedAt(),
                    c.decisionNote(), c.openedAt(), c.updatedAt(), c.version());
        }
    }

    @Schema(description = "Timeline entry")
    record HistoryResponse(Instant at, String actorId, String action, ClaimStatus fromStatus, ClaimStatus toStatus,
                           String note) {

        static HistoryResponse of(ClaimHistoryEntry e) {
            return new HistoryResponse(e.at(), e.actorId(), e.action(), e.fromStatus(), e.toStatus(), e.note());
        }
    }
}
