package com.afrotransact.payments.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.adapter.in.web.PaymentsDtos.TransferRecoveryResponse;
import com.afrotransact.payments.application.TransferRecoveryService;
import com.afrotransact.payments.domain.RecoveryResolution;
import com.afrotransact.payments.domain.TransferRecoveryStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transfer recoveries", description = "Refunds Stripe would not take back from sellers' transfers: what "
        + "sellers owe the platform, and Finance's resolution with four-eyes approval (staff)")
@RestController
class TransferRecoveryController {

    private final TransferRecoveryService recoveries;

    TransferRecoveryController(TransferRecoveryService recoveries) {
        this.recoveries = recoveries;
    }

    record ProposeResolutionRequest(@NotNull RecoveryResolution resolution,
                                    @NotBlank @Size(max = 500) String reason,
                                    @Size(max = 120) String reference) {
    }

    record RecoveryDecisionRequest(@Size(max = 500) String note) {
    }

    @Operation(summary = "Transfer recoveries, newest first (payments:read)",
            description = "Each is an amount Stripe definitively refused to take back from a seller's transfer for a "
                    + "refund (typically balance_insufficient: the seller's balance was already paid out). The "
                    + "customer's refund completed; the seller owes the platform until later transfers recover it "
                    + "or Finance resolves it.")
    @GetMapping("/v1/transfer-recoveries")
    List<TransferRecoveryResponse> list(@RequestParam(required = false) TransferRecoveryStatus status,
                                        @RequestParam(required = false) String orgId,
                                        @RequestParam(defaultValue = "100") int limit) {
        return recoveries.list(status, orgId, limit).stream().map(TransferRecoveryResponse::of).toList();
    }

    @Operation(summary = "One transfer recovery (payments:read)")
    @GetMapping("/v1/transfer-recoveries/{id}")
    TransferRecoveryResponse get(@PathVariable UUID id) {
        return TransferRecoveryResponse.of(recoveries.get(id));
    }

    @Operation(summary = "Propose closing a transfer recovery: received outside Stripe, or written off "
            + "(ledger:adjust)",
            description = "Nothing is posted until a different person with ledger:approve approves it. RECOVERED "
                    + "needs the bank reference of the money received (Dr 1050 / Cr 2000:org); WRITE_OFF absorbs the "
                    + "outstanding amount as a platform loss (Dr 5300 / Cr 2000:org). 409 "
                    + "payments.recovery-pending while another proposal waits; 409 payments.recovery-closed once "
                    + "it is recovered or written off.")
    @PostMapping("/v1/transfer-recoveries/{id}/propose")
    TransferRecoveryResponse propose(@PathVariable UUID id, @Valid @RequestBody ProposeResolutionRequest body) {
        return TransferRecoveryResponse.of(recoveries.propose(id, body.resolution(), body.reason(),
                body.reference()));
    }

    @Operation(summary = "Approve the proposed resolution and post it (ledger:approve, not the proposer)",
            description = "403 payments.recovery-four-eyes when the approver proposed it.")
    @PostMapping("/v1/transfer-recoveries/{id}/approve")
    TransferRecoveryResponse approve(@PathVariable UUID id,
                                     @RequestBody(required = false) RecoveryDecisionRequest body) {
        return TransferRecoveryResponse.of(recoveries.approve(id, body == null ? null : body.note()));
    }

    @Operation(summary = "Reject the proposed resolution, with the reason (ledger:approve; the proposer may "
            + "withdraw)")
    @PostMapping("/v1/transfer-recoveries/{id}/reject")
    TransferRecoveryResponse reject(@PathVariable UUID id, @RequestBody RecoveryDecisionRequest body) {
        return TransferRecoveryResponse.of(recoveries.reject(id, body == null ? null : body.note()));
    }
}
