package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.identity.application.InvitationView;
import com.afrotransact.identity.application.OrgInvitationService;
import com.afrotransact.identity.application.OrgTeamService;
import com.afrotransact.identity.application.OrganizationService;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgMember;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organisations", description = "Seller/provider organisations mirrored from the seller service (orgs:manage), "
        + "their member directory, role changes and invitations (team:manage members, staff, seller service)")
@RestController
class OrganizationController {

    private final OrganizationService orgs;
    private final OrgTeamService team;
    private final OrgInvitationService invitations;

    OrganizationController(OrganizationService orgs, OrgTeamService team, OrgInvitationService invitations) {
        this.orgs = orgs;
        this.team = team;
        this.invitations = invitations;
    }

    @Schema(name = "UpsertOrgRequest")
    record UpsertOrgRequest(@NotBlank @Schema(allowableValues = {"SELLER", "PROVIDER", "BOTH"}) String kind,
                            @NotBlank @Size(max = 200) String name,
                            @NotBlank @Schema(allowableValues = {"PENDING", "APPROVED", "SUSPENDED", "REJECTED"})
                            String status) {
    }

    @Schema(name = "Organization")
    record OrgResponse(@Schema(examples = "org_3k2j9x8q7w6e5r4t3y2u") String id,
                       @Schema(allowableValues = {"SELLER", "PROVIDER", "BOTH"}) String kind, String name,
                       String status,
                       @Schema(description = "Market code as content-service defines it (ISO 3166-1 alpha-2)",
                               examples = "US") String market,
                       Instant updatedAt) {
    }

    @Schema(name = "SetMemberRequest")
    record SetMemberRequest(@NotBlank @Schema(examples = "seller.fulfilment") String roleKey) {
    }

    @Schema(name = "OrgMember", description = "One member of the organisation's team")
    record MemberResponse(UUID userId, String firstName, String lastName,
                          @Schema(description = "First and last name", examples = "Adaeze Okafor") String name,
                          String email, @Schema(examples = "seller.admin") String roleKey,
                          @Schema(description = "Role display name", examples = "Admin") String roleLabel,
                          @Schema(allowableValues = {"ACTIVE", "DISABLED"}) String status,
                          @Schema(description = "When the membership was created") Instant joinedAt) {

        static MemberResponse of(OrgMember m) {
            return new MemberResponse(m.userId(), m.firstName(), m.lastName(), m.displayName(), m.email(), m.roleKey(),
                    m.roleName(), m.enabled() ? "ACTIVE" : "DISABLED", m.joinedAt());
        }
    }

    @Schema(name = "OrgMemberList")
    record MemberList(String orgId, List<MemberResponse> items) {
    }

    @Schema(name = "InviteMemberRequest")
    record InviteRequest(@NotBlank @Email @Size(max = 254) String email,
                         @NotBlank @Schema(examples = "seller.catalog") String roleKey) {
    }

    @Schema(name = "OrgInvitation", description = "An invitation to join an organisation")
    record InvitationResponse(UUID id, String orgId, String orgName,
                              @Schema(allowableValues = {"SELLER", "PROVIDER", "BOTH"}) String orgKind, String email,
                              String roleKey, String roleLabel,
                              @Schema(allowableValues = {"PENDING", "ACCEPTED", "REVOKED", "EXPIRED"}) String status,
                              @Schema(description = "User id (or client id) of the inviter") String invitedBy,
                              Instant createdAt, Instant expiresAt) {

        static InvitationResponse of(InvitationView v) {
            return new InvitationResponse(v.id(), v.orgId(), v.orgName(), v.orgKind().name(), v.email(), v.roleKey(),
                    v.roleName(), v.status().name(), v.invitedBy(), v.createdAt(), v.expiresAt());
        }
    }

    @Schema(name = "OrgInvitationList")
    record InvitationList(String orgId, List<InvitationResponse> items) {
    }

    @Operation(summary = "Create or update an organisation and its approval status")
    @PutMapping("/v1/orgs/{orgId}")
    OrgResponse upsert(@PathVariable String orgId, @Valid @RequestBody UpsertOrgRequest body) {
        Organization o = orgs.upsert(orgId, OrgKind.valueOf(body.kind().toUpperCase(Locale.ROOT)), body.name(),
                OrgStatus.valueOf(body.status().toUpperCase(Locale.ROOT)));
        return new OrgResponse(o.id(), o.kind().name(), o.name(), o.status().name(), o.market(), o.updatedAt());
    }

    @Operation(summary = "Member directory: names, email, role, status and join date",
            description = "Members of the (approved) organisation whose role has team:manage or at least VIEW on the "
                    + "Team & roles module; staff and services with orgs:read or orgs:manage.")
    @GetMapping("/v1/orgs/{orgId}/members")
    MemberList members(@PathVariable String orgId) {
        return new MemberList(orgId, team.members(orgId).stream().map(MemberResponse::of).toList());
    }

    @Operation(summary = "Change a member's organisation role (seller.* / provider.*)",
            description = "team:manage members change other members' roles (not their own; only owners grant or "
                    + "change the owner role). The seller service and staff (orgs:manage) may also add a member. "
                    + "The last owner can't be demoted (422 identity.last-owner). Publishes identity.role.revoked "
                    + "for the old role and identity.role.granted for the new one.")
    @PutMapping("/v1/orgs/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setMember(@PathVariable String orgId, @PathVariable UUID userId, @Valid @RequestBody SetMemberRequest body) {
        team.setMember(orgId, userId, body.roleKey());
    }

    @Operation(summary = "Remove a member",
            description = "The last owner can't be removed (422 identity.last-owner); only owners remove owners. "
                    + "Publishes identity.role.revoked.")
    @DeleteMapping("/v1/orgs/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@PathVariable String orgId, @PathVariable UUID userId) {
        team.removeMember(orgId, userId);
    }

    @Operation(summary = "Invite someone by email with an organisation role",
            description = "Publishes identity.invitation.created with a single-use token (7 days) for "
                    + "notifications-service to email. Inviting the same email again replaces the open invitation. "
                    + "409 identity.already-member when the email already belongs to a member.")
    @PostMapping("/v1/orgs/{orgId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationResponse invite(@PathVariable String orgId, @Valid @RequestBody InviteRequest body) {
        return InvitationResponse.of(invitations.invite(orgId, body.email(), body.roleKey()));
    }

    @Operation(summary = "Pending (unexpired) invitations")
    @GetMapping("/v1/orgs/{orgId}/invitations")
    InvitationList pendingInvitations(@PathVariable String orgId) {
        return new InvitationList(orgId, invitations.pending(orgId).stream().map(InvitationResponse::of).toList());
    }

    @Operation(summary = "Revoke a pending invitation (publishes identity.invitation.revoked)")
    @DeleteMapping("/v1/orgs/{orgId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeInvitation(@PathVariable String orgId, @PathVariable UUID invitationId) {
        invitations.revoke(orgId, invitationId);
    }
}
