package com.afrotransact.seller.adapter.in.web;

import java.net.URI;

import com.afrotransact.seller.application.OrgProvisioningService;
import com.afrotransact.seller.application.OrgProvisioningService.ProvisionCommand;
import com.afrotransact.seller.application.OrgProvisioningService.Provisioned;
import com.afrotransact.seller.domain.Offering;
import com.afrotransact.seller.domain.OrgKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organisations", description = "Seller and provider organisations")
@RestController
class OrgProvisioningController {

    private final OrgProvisioningService provisioning;

    OrgProvisioningController(OrgProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Operation(summary = "Provision an approved organisation without an application (staff, sellers:provision)",
            description = "Local and dev demo data and migrations only: 404 seller.provisioning-off unless "
                    + "at.seller.provisioning-enabled is true. Publishes seller.org.approved and seller.org.updated. "
                    + "Idempotent on id: 201 when created, 200 with the existing organisation (unchanged) otherwise.")
    @PostMapping("/v1/orgs")
    ResponseEntity<OrgController.OrgResponse> provision(@Valid @RequestBody ProvisionRequest body) {
        Offering offering = body.offering() != null ? body.offering()
                : body.kind() == OrgKind.PROVIDER ? Offering.SERVICES : Offering.PRODUCTS;
        Provisioned result = provisioning.provision(new ProvisionCommand(body.id(), body.kind(), offering,
                body.legalName(), body.displayName(), body.country()));
        OrgController.OrgResponse response = OrgController.OrgResponse.of(result.org());
        return result.created()
                ? ResponseEntity.created(URI.create("/v1/orgs/" + response.id())).body(response)
                : ResponseEntity.ok(response);
    }

    @Schema(name = "ProvisionOrgRequest")
    record ProvisionRequest(
            @Schema(description = "org_… id to make the call idempotent; generated when omitted",
                    example = "org_mama_put_pantry") String id,
            @NotNull OrgKind kind,
            @Schema(description = "Defaults to PRODUCTS for sellers and SERVICES for providers") Offering offering,
            String legalName, String displayName,
            @Schema(example = "US") String country) {
    }
}
