package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.identity.application.OrganizationService;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organisations", description = "Seller/provider organisations mirrored from the seller service (orgs:manage)")
@RestController
class OrganizationController {

    private final OrganizationService orgs;

    OrganizationController(OrganizationService orgs) {
        this.orgs = orgs;
    }

    @Schema(name = "UpsertOrgRequest")
    record UpsertOrgRequest(@NotBlank @Schema(allowableValues = {"SELLER", "PROVIDER"}) String kind,
                            @NotBlank @Size(max = 200) String name,
                            @NotBlank @Schema(allowableValues = {"PENDING", "APPROVED", "SUSPENDED", "REJECTED"})
                            String status) {
    }

    @Schema(name = "Organization")
    record OrgResponse(@Schema(examples = "org_3k2j9x8q7w6e5r4t3y2u") String id, String kind, String name,
                       String status, String market, Instant updatedAt) {
    }

    @Schema(name = "SetMemberRequest")
    record SetMemberRequest(@NotBlank String roleKey) {
    }

    @Operation(summary = "Create or update an organisation and its approval status")
    @PutMapping("/v1/orgs/{orgId}")
    OrgResponse upsert(@PathVariable String orgId, @Valid @RequestBody UpsertOrgRequest body) {
        Organization o = orgs.upsert(orgId, OrgKind.valueOf(body.kind().toUpperCase(Locale.ROOT)), body.name(),
                OrgStatus.valueOf(body.status().toUpperCase(Locale.ROOT)));
        return new OrgResponse(o.id(), o.kind().name(), o.name(), o.status().name(), o.market(), o.updatedAt());
    }

    @Operation(summary = "Add a member or change their organisation role (seller.* / provider.*)")
    @PutMapping("/v1/orgs/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setMember(@PathVariable String orgId, @PathVariable UUID userId, @Valid @RequestBody SetMemberRequest body) {
        orgs.setMember(orgId, userId, body.roleKey());
    }

    @Operation(summary = "Remove a member")
    @DeleteMapping("/v1/orgs/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@PathVariable String orgId, @PathVariable UUID userId) {
        orgs.removeMember(orgId, userId);
    }
}
