package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;

import com.afrotransact.identity.application.AcceptStaffInvitationCommand;
import com.afrotransact.identity.application.AcceptedStaffInvitation;
import com.afrotransact.identity.application.StaffInvitationPreview;
import com.afrotransact.identity.application.StaffInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Staff invitations", description = "The invitee's side: open the emailed link, set a password (token in the body, never the URL)")
@RestController
class StaffInvitationPublicController {

    private final StaffInvitationService invitations;

    StaffInvitationPublicController(StaffInvitationService invitations) {
        this.invitations = invitations;
    }

    @Schema(name = "StaffInvitationTokenRequest")
    record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    @Schema(name = "AcceptStaffInvitationRequest")
    record AcceptRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 128) String password,
                         @NotBlank @Size(max = 128) String confirmPassword, @Size(max = 30) String phone,
                         @Size(max = 2) String phoneRegion) {
    }

    @Schema(name = "StaffInvitationPreview", description = "What an invite link is for, shown before choosing a password")
    record PreviewResponse(String firstName, String lastName, String email, String roleName,
                           @Schema(allowableValues = {"PENDING", "ACCEPTED", "REVOKED", "EXPIRED"}) String status,
                           Instant expiresAt) {

        static PreviewResponse of(StaffInvitationPreview p) {
            return new PreviewResponse(p.firstName(), p.lastName(), p.email(), p.roleName(), p.status().name(),
                    p.expiresAt());
        }
    }

    @Schema(name = "AcceptedStaffInvitation")
    record AcceptedResponse(String email, String roleName) {

        static AcceptedResponse of(AcceptedStaffInvitation a) {
            return new AcceptedResponse(a.email(), a.roleName());
        }
    }

    @Operation(summary = "Look up a staff invitation link (public)",
            description = "Name, email, role and status for the accept page. 404 for a token that is not an invitation. "
                    + "The token is sent in the body so it stays out of access logs.")
    @SecurityRequirements
    @PostMapping("/v1/staff-invitations/preview")
    PreviewResponse preview(@Valid @RequestBody TokenRequest body) {
        return PreviewResponse.of(invitations.preview(body.token()));
    }

    @Operation(summary = "Accept a staff invitation by choosing a password (public)",
            description = "Creates the account (enabled, email verified, with the invited role) and spends the token. "
                    + "The password must meet the password policy. Expired, withdrawn or used links: 422 "
                    + "identity.staff-invitation-expired / -revoked / -used. Sign-in then follows the usual "
                    + "two-step verification rules for staff.")
    @SecurityRequirements
    @PostMapping("/v1/staff-invitations/accept")
    @ResponseStatus(HttpStatus.CREATED)
    AcceptedResponse accept(@Valid @RequestBody AcceptRequest body) {
        return AcceptedResponse.of(invitations.accept(new AcceptStaffInvitationCommand(body.token(), body.password(),
                body.confirmPassword(), body.phone(), body.phoneRegion())));
    }
}
