package com.afrotransact.identity.adapter.in.web;

import com.afrotransact.identity.application.OrgReconcileService;
import com.afrotransact.identity.application.OrgReconcileService.OrgReconcileSummary;
import com.afrotransact.identity.application.OrgReconcileService.OrgSyncResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organisation sync", description = "Repairs identity's copy of seller and provider organisations from the "
        + "seller service (the source of truth) when an approval event was missed or arrived late")
@RestController
class OrgSyncController {

    private final OrgReconcileService reconcile;

    OrgSyncController(OrgReconcileService reconcile) {
        this.reconcile = reconcile;
    }

    @Schema(name = "OrgSyncResult")
    record SyncResponse(String orgId, @Schema(allowableValues = {"PENDING", "APPROVED", "SUSPENDED", "REJECTED"},
            description = "identity's status after the sync; null when the organisation has none yet") String status,
                        @Schema(description = "true when identity's copy was repaired") boolean repaired) {

        static SyncResponse of(OrgSyncResult r) {
            return new SyncResponse(r.orgId(), r.status(), r.repaired());
        }
    }

    @Schema(name = "OrgReconcileSummary")
    record ReconcileResponse(int scanned, int repaired, int failed) {

        static ReconcileResponse of(OrgReconcileSummary s) {
            return new ReconcileResponse(s.scanned(), s.repaired(), s.failed());
        }
    }

    @Operation(summary = "Sync one organisation from the seller service now",
            description = "gateway-bff calls it with the person's token when the seller service shows the organisation "
                    + "approved and identity does not yet: identity reads the seller service, corrects the status and "
                    + "adds the owners' memberships, so the next token carries the organisation. The caller must be "
                    + "an owner or the approved applicant at the seller service, or a service or staff member with "
                    + "orgs:manage; anyone else gets 404.")
    @PostMapping("/v1/orgs/{orgId}/sync")
    SyncResponse sync(@PathVariable String orgId) {
        return SyncResponse.of(reconcile.sync(orgId));
    }

    @Operation(summary = "Reconcile every approved or suspended organisation with the seller service now",
            description = "What the scheduled job does on start-up and every few minutes, on request (the local "
                    + "repair command, an operator). Needs a service or staff token with orgs:manage.")
    @PostMapping("/v1/internal/orgs/reconcile")
    ReconcileResponse reconcileAll() {
        return ReconcileResponse.of(reconcile.reconcileAllOnRequest());
    }
}
