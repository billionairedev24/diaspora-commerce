package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.application.InviteStaffCommand;
import com.afrotransact.identity.application.PageView;
import com.afrotransact.identity.application.StaffInvitationService;
import com.afrotransact.identity.application.StaffInvitationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Staff invitations", description = "Invite staff by email and role (access:admin; listing needs access:read)")
@RestController
class StaffInvitationController {

    private final StaffInvitationService invitations;

    StaffInvitationController(StaffInvitationService invitations) {
        this.invitations = invitations;
    }

    @Schema(name = "InviteStaffRequest")
    record InviteRequest(@NotBlank @Size(max = 80) String firstName, @NotBlank @Size(max = 80) String lastName,
                         @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 80) String roleKey,
                         @NotBlank @Size(min = 3, max = 300) String reason) {
    }

    @Schema(name = "RevokeStaffInvitationRequest")
    record RevokeRequest(@NotBlank @Size(min = 3, max = 300) String reason) {
    }

    @Schema(name = "StaffInvitation", description = "Never carries the token")
    record InvitationResponse(UUID id, String email, String firstName, String lastName, String roleKey,
                              String roleName,
                              @Schema(allowableValues = {"PENDING", "EXPIRED"}) String status, String reason,
                              String invitedByName, Instant createdAt, Instant expiresAt, Instant lastSentAt,
                              int sendCount) {

        static InvitationResponse of(StaffInvitationView v) {
            return new InvitationResponse(v.id(), v.email(), v.firstName(), v.lastName(), v.roleKey(), v.roleName(),
                    v.status().name(), v.reason(), v.invitedByName(), v.createdAt(), v.expiresAt(), v.lastSentAt(),
                    v.sendCount());
        }
    }

    @Schema(name = "StaffInvitationPage")
    record InvitationPageResponse(List<InvitationResponse> items, int page, int size, long total) {
    }

    @Operation(summary = "Invite a person to be staff",
            description = "Creates a pending invitation (single-use token, expires after 72 hours by default, stored "
                    + "hashed) and queues the email through identity.staff-invitation.created. Inviting an email that "
                    + "already has a pending invitation replaces it. 409 identity.email-taken when an account exists.")
    @PostMapping("/v1/admin/staff-invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationResponse invite(@Valid @RequestBody InviteRequest body) {
        return InvitationResponse.of(invitations.invite(new InviteStaffCommand(body.firstName(), body.lastName(),
                body.email(), body.roleKey(), body.reason())));
    }

    @Operation(summary = "Open staff invitations (pending, and expired ones never used), newest first")
    @GetMapping("/v1/admin/staff-invitations")
    InvitationPageResponse open(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        PageView<StaffInvitationView> p = invitations.open(page, size);
        return new InvitationPageResponse(p.items().stream().map(InvitationResponse::of).toList(), p.page(), p.size(),
                p.total());
    }

    @Operation(summary = "Send an invitation again with a new token and expiry (the old link stops working)")
    @PostMapping("/v1/admin/staff-invitations/{invitationId}/resend")
    InvitationResponse resend(@PathVariable UUID invitationId) {
        return InvitationResponse.of(invitations.resend(invitationId));
    }

    @Operation(summary = "Revoke an open invitation (its link stops working at once)")
    @PostMapping("/v1/admin/staff-invitations/{invitationId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID invitationId, @Valid @RequestBody RevokeRequest body) {
        invitations.revoke(invitationId, body.reason());
    }
}
