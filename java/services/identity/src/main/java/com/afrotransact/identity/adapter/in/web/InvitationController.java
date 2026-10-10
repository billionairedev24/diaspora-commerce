package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.application.AcceptedInvitation;
import com.afrotransact.identity.application.InvitationView;
import com.afrotransact.identity.application.OrgInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Invitations", description = "The invitee's side of organisation invitations (token from the invite link)")
@RestController
class InvitationController {

    private final OrgInvitationService invitations;

    InvitationController(OrgInvitationService invitations) {
        this.invitations = invitations;
    }

    @Schema(name = "InvitationTokenRequest")
    record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    @Schema(name = "InvitationPreview", description = "What an invite link is for; shown before signing in or up")
    record PreviewResponse(String orgName, @Schema(allowableValues = {"SELLER", "PROVIDER"}) String orgKind,
                           String email, String roleKey, String roleLabel,
                           @Schema(allowableValues = {"PENDING", "ACCEPTED", "REVOKED", "EXPIRED"}) String status,
                           Instant expiresAt) {

        static PreviewResponse of(InvitationView v) {
            return new PreviewResponse(v.orgName(), v.orgKind().name(), v.email(), v.roleKey(), v.roleName(),
                    v.status().name(), v.expiresAt());
        }
    }

    @Schema(name = "AcceptedInvitation")
    record AcceptedResponse(UUID invitationId, String orgId, String orgName,
                            @Schema(allowableValues = {"SELLER", "PROVIDER"}) String orgKind,
                            @Schema(description = "The member's role now") String roleKey, UUID userId,
                            @Schema(description = "Was already a member; the role was left unchanged") boolean alreadyMember) {

        static AcceptedResponse of(AcceptedInvitation a) {
            return new AcceptedResponse(a.invitationId(), a.orgId(), a.orgName(), a.orgKind().name(), a.roleKey(),
                    a.userId(), a.alreadyMember());
        }
    }

    @Operation(summary = "Look up an invitation link (public)",
            description = "Organisation, role, invited email and status for the accept / sign-up page. 404 for unknown "
                    + "tokens. The token is sent in the body so it stays out of access logs.")
    @SecurityRequirements
    @PostMapping("/v1/invitations/lookup")
    PreviewResponse lookup(@Valid @RequestBody TokenRequest body) {
        return PreviewResponse.of(invitations.preview(body.token()));
    }

    @Operation(summary = "Accept an invitation as the signed-in user",
            description = "The account email must be the invited one (422 identity.invitation-email-mismatch). "
                    + "Expired, used or revoked tokens: 422 identity.invitation-expired / -used / -revoked. People "
                    + "without an account sign up with POST /v1/accounts and invitationToken instead. Publishes "
                    + "identity.role.granted and identity.invitation.accepted.")
    @PostMapping("/v1/invitations/accept")
    AcceptedResponse accept(@Valid @RequestBody TokenRequest body) {
        return AcceptedResponse.of(invitations.accept(body.token()));
    }
}
