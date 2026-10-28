package com.afrotransact.seller.adapter.in.web;

import com.afrotransact.seller.application.ConnectedAccountSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stripe compliance")
@RestController
class ConnectedAccountSyncController {

    private final ConnectedAccountSyncService sync;

    ConnectedAccountSyncController(ConnectedAccountSyncService sync) {
        this.sync = sync;
    }

    @Schema(name = "PaymentsSyncResult")
    record SyncResult(@Schema(description = "Connect accounts payments accepted") int registered) {
    }

    @Operation(summary = "Staff: register every Connect account with payments now — compliance:remind",
            description = "The scheduled job's run on demand (every minute locally, nightly elsewhere). Payments "
                    + "transfers a seller's net only to an account it knows. Idempotent.")
    @PostMapping("/v1/compliance/payments-sync")
    SyncResult syncNow() {
        return new SyncResult(sync.syncNow());
    }
}
