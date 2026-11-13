package com.afrotransact.seller.adapter.in.web;

import com.afrotransact.seller.application.OrgMembershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The live member list, for services that must not trust a token role for money actions. */
@Tag(name = "Organisations", description = "Seller and provider organisations")
@RestController
class OrgMembershipController {

    private final OrgMembershipService membership;

    OrgMembershipController(OrgMembershipService membership) {
        this.membership = membership;
    }

    @Operation(summary = "One user's role in one organisation, read live from the member list (service clients only)",
            description = "Needs sellers:membership.read (the payments-service client). member=false, roleKey=null when "
                    + "the user is not (or no longer) a member. Not cached here; payments caches for a few seconds at most.")
    @GetMapping("/v1/orgs/{orgId}/members/{userId}/role")
    MemberRoleResponse role(@PathVariable String orgId, @PathVariable String userId) {
        return membership.roleOf(orgId, userId).map(k -> new MemberRoleResponse(orgId, userId, true, k))
                .orElseGet(() -> new MemberRoleResponse(orgId, userId, false, null));
    }

    @Schema(name = "MemberRole")
    record MemberRoleResponse(String orgId, String userId, boolean member, String roleKey) {
    }
}
