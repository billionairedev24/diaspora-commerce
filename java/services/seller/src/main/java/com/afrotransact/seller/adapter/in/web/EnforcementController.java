package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.application.EnforcementView;
import com.afrotransact.seller.application.SellerEnforcementService;
import com.afrotransact.seller.domain.ConnectAccount;
import com.afrotransact.seller.domain.OrgSuspension;
import com.afrotransact.seller.domain.SuspensionReason;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Enforcement", description = "Trust and Safety suspension of an organisation (sellers:enforce; staff or "
        + "the trust-ops-service client). Separate from Stripe compliance payout holds: reinstating releases only the "
        + "suspension hold.")
@RestController
class EnforcementController {

    private final SellerEnforcementService enforcement;

    EnforcementController(SellerEnforcementService enforcement) {
        this.enforcement = enforcement;
    }

    @Operation(summary = "Suspend an approved organisation — sellers:enforce",
            description = "Adds a suspension hold: publishes seller.listings.hidden and seller.payouts.paused "
                    + "(reason SUSPENDED) and seller.org.updated (status SUSPENDED). Idempotent: an organisation that "
                    + "is already suspended is returned unchanged.")
    @PostMapping("/v1/orgs/{orgId}/suspend")
    EnforcementResponse suspendOrg(@PathVariable String orgId, @Valid @RequestBody SuspendRequest body) {
        return EnforcementResponse.of(enforcement.suspend(orgId, body.reasonCode(), body.note()));
    }

    @Operation(summary = "Reinstate a suspended organisation — sellers:enforce",
            description = "Removes only the suspension hold and restores the previous status (seller.org.updated). "
                    + "seller.payouts.resumed / seller.listings.restored (reason REINSTATED) are published only when "
                    + "no Stripe compliance hold still requires the payout hold or hidden listings. Idempotent.")
    @PostMapping("/v1/orgs/{orgId}/reinstate")
    EnforcementResponse reinstateOrg(@PathVariable String orgId,
                                     @Valid @RequestBody(required = false) ReinstateRequest body) {
        return EnforcementResponse.of(enforcement.reinstate(orgId, body == null ? null : body.note()));
    }

    @Operation(summary = "Suspension and compliance holds of an organisation — sellers:enforce, compliance:hold or "
            + "compliance:remind")
    @GetMapping("/v1/orgs/{orgId}/enforcement")
    EnforcementResponse orgEnforcement(@PathVariable String orgId) {
        return EnforcementResponse.of(enforcement.status(orgId));
    }

    @Schema(name = "SuspendOrgRequest", description = "Reason code required; note required for OTHER")
    record SuspendRequest(@NotNull SuspensionReason reasonCode, @Size(max = 1000) String note) {
    }

    @Schema(name = "ReinstateOrgRequest")
    record ReinstateRequest(@Size(max = 1000) String note) {
    }

    @Schema(name = "OrgSuspension")
    record SuspensionResponse(String id, SuspensionReason reasonCode, String note, String actorId,
                              String previousStatus, Instant suspendedAt, Instant reinstatedAt, String reinstatedBy,
                              String reinstateNote) {

        static SuspensionResponse of(OrgSuspension s) {
            return s == null ? null : new SuspensionResponse(s.id().toString(), s.reason(), s.note(), s.actorId(),
                    s.previousStatus().name(), s.suspendedAt(), s.reinstatedAt(), s.reinstatedBy(),
                    s.reinstateNote());
        }
    }

    @Schema(name = "OrgEnforcement", description = "Suspension hold next to the Stripe compliance holds. payoutsPaused "
            + "and listingsHidden are the effective state (any hold).")
    record EnforcementResponse(String orgId, String status, boolean suspended, SuspensionResponse suspension,
                               boolean payoutsPaused, boolean listingsHidden, String accountId,
                               boolean compliancePayoutHold, String complianceHoldReason,
                               boolean complianceListingsHidden, List<SuspensionResponse> history) {

        static EnforcementResponse of(EnforcementView v) {
            ConnectAccount a = v.account();
            return new EnforcementResponse(v.organisation().id(), v.organisation().status().name(), v.suspended(),
                    SuspensionResponse.of(v.suspension()), v.payoutsPaused(), v.listingsHidden(),
                    a == null ? null : a.accountId(), v.compliancePayoutHold(),
                    a == null || a.holdReason() == null ? null : a.holdReason().name(),
                    v.complianceListingsHidden(), v.history().stream().map(SuspensionResponse::of).toList());
        }
    }
}
