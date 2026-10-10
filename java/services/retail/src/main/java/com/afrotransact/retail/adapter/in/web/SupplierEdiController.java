package com.afrotransact.retail.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.retail.application.PoDispatchService;
import com.afrotransact.retail.application.PoDispatchService.EdiProfileCommand;
import com.afrotransact.retail.domain.EdiProtocol;
import com.afrotransact.retail.domain.SupplierEdiProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Suppliers", description = "Supplier records, lifecycle and receipt-driven scorecards (S069)")
@RestController
class SupplierEdiController {

    private final PoDispatchService dispatch;

    SupplierEdiController(PoDispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @Schema(name = "SupplierEdiRequest", description = "EDI trading-partner settings; enabled suppliers receive "
            + "the X12 850 of every sent PO")
    record EdiRequest(boolean enabled, @NotNull EdiProtocol protocol,
                      @Schema(description = "Supplier's ISA receiver id") @NotBlank @Size(max = 15)
                      String interchangeId,
                      @Schema(description = "sftp://host/dir (SFTP) or the partner's https:// AS2 URL")
                      @NotBlank @Size(max = 500) String endpoint) {
    }

    @Schema(name = "SupplierEdi")
    record EdiResponse(UUID supplierId, boolean enabled, EdiProtocol protocol, String interchangeId, String endpoint,
                       String updatedBy, Instant updatedAt) {

        static EdiResponse of(SupplierEdiProfile p) {
            return new EdiResponse(p.supplierId(), p.enabled(), p.protocol(), p.interchangeId(), p.endpoint(),
                    p.updatedBy(), p.updatedAt());
        }
    }

    @Operation(operationId = "getSupplierEdi", summary = "A supplier's EDI settings (retail:read)")
    @GetMapping("/v1/suppliers/{id}/edi")
    EdiResponse get(@PathVariable UUID id) {
        return EdiResponse.of(dispatch.ediProfile(id));
    }

    @Operation(operationId = "setSupplierEdi", summary = "Set a supplier's EDI settings (retail:suppliers:write)")
    @PutMapping("/v1/suppliers/{id}/edi")
    EdiResponse put(@PathVariable UUID id, @Valid @RequestBody EdiRequest body) {
        return EdiResponse.of(dispatch.setEdiProfile(id, new EdiProfileCommand(body.enabled(), body.protocol(),
                body.interchangeId(), body.endpoint())));
    }
}
