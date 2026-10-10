package com.afrotransact.identity.adapter.in.web;

import java.util.Map;

import com.afrotransact.identity.application.DataProtectionService;
import com.afrotransact.identity.application.DataProtectionStatus;
import com.afrotransact.identity.application.ResealReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Data protection", description = "Encryption at rest of MFA secrets: key status and key rotation "
        + "(staff with platform.super_admin only)")
@RestController
class DataProtectionController {

    private final DataProtectionService dataProtection;

    DataProtectionController(DataProtectionService dataProtection) {
        this.dataProtection = dataProtection;
    }

    @Schema(name = "DataProtectionStatus", description = "secretsByKey counts TOTP secrets per key id; 'plaintext' "
            + "counts legacy rows not sealed yet")
    record StatusResponse(String currentKeyId, Map<String, Long> secretsByKey) {

        static StatusResponse of(DataProtectionStatus s) {
            return new StatusResponse(s.currentKeyId(), s.secretsByKey());
        }
    }

    @Schema(name = "DataProtectionRotation", description = "remaining counts TOTP secrets per key id after the run; "
            + "rotation is complete when it lists only keyId")
    record RotationResponse(String keyId, int resealed, int skipped, int failed, Map<String, Long> remaining) {

        static RotationResponse of(ResealReport r) {
            return new RotationResponse(r.keyId(), r.resealed(), r.skipped(), r.failed(), r.remaining());
        }
    }

    @Operation(summary = "Current data-protection key and TOTP secrets per key (platform.super_admin)")
    @GetMapping("/v1/admin/data-protection")
    StatusResponse dataProtectionStatus() {
        return StatusResponse.of(dataProtection.status());
    }

    @Operation(summary = "Re-encrypt every TOTP secret under the current key (platform.super_admin)",
            description = "Run after deploying a new IDENTITY_DATA_KEY with the old key in IDENTITY_DATA_KEYS_PREVIOUS. "
                    + "Online and idempotent; audited in the access change log.")
    @PostMapping("/v1/admin/data-protection/rotate")
    RotationResponse rotateDataProtectionKey() {
        return RotationResponse.of(dataProtection.rotate());
    }
}
