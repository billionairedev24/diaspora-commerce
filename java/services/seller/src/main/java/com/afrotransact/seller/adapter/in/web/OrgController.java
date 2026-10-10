package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.application.OrgPublicView;
import com.afrotransact.seller.application.OrgService;
import com.afrotransact.seller.application.OrgView;
import com.afrotransact.seller.domain.OrgMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organisations", description = "Seller and provider organisations")
@RestController
class OrgController {

    private final OrgService orgs;

    OrgController(OrgService orgs) {
        this.orgs = orgs;
    }

    @Operation(summary = "Public profile (display name, kind, status) for indexer, cart and storefront — no auth")
    @GetMapping("/v1/orgs/{id}/public")
    OrgPublicView orgPublicProfile(@PathVariable String id) {
        return orgs.publicProfile(id);
    }

    @Operation(summary = "Organisation with members (members and reviewers)")
    @GetMapping("/v1/orgs/{id}")
    OrgResponse getOrg(@PathVariable String id) {
        return OrgResponse.of(orgs.get(id));
    }

    @Operation(summary = "Rename the store (owner); publishes seller.org.updated")
    @PatchMapping("/v1/orgs/{id}")
    OrgResponse renameOrg(@PathVariable String id, @Valid @RequestBody RenameRequest body) {
        return OrgResponse.of(orgs.rename(id, body.displayName()));
    }

    @Schema(name = "RenameOrgRequest")
    record RenameRequest(@NotBlank String displayName) {
    }

    @Schema(name = "OrgMember")
    record MemberView(String userId, String roleKey, Instant createdAt) {
    }

    @Schema(name = "Organisation")
    record OrgResponse(String id, String kind, String legalName, String displayName, String country, String status,
                       List<MemberView> members, Instant createdAt, Instant updatedAt) {

        static OrgResponse of(OrgView v) {
            var o = v.organisation();
            return new OrgResponse(o.id(), o.kind().name(), o.legalName(), o.displayName(), o.country(),
                    o.status().name(), v.members().stream().map(OrgResponse::member).toList(), o.createdAt(),
                    o.updatedAt());
        }

        private static MemberView member(OrgMember m) {
            return new MemberView(m.userId(), m.roleKey(), m.createdAt());
        }
    }
}
