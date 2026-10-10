package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Sign-up and email verification bodies. */
final class AccountDtos {

    private AccountDtos() {
    }

    @Schema(name = "SignUpRequest", description = "Unified sign-up. The role is never a form field: intent decides it.")
    record SignUpRequest(
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotBlank @Email @Size(max = 254) String email,
            @Schema(description = "Mobile number, E.164 (+17132490142) or national with phoneRegion", example = "+17132490142")
            @NotBlank @Size(max = 32) String phone,
            @Schema(description = "ISO 3166 region from the country picker, used for national-format numbers",
                    example = "US") String phoneRegion,
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Size(max = 128) String confirmPassword,
            @NotNull Boolean termsAccepted,
            @Schema(description = "Separate, optional, unticked SMS marketing/notification consent") Boolean smsConsent,
            @Schema(description = "Entry point: shop (default), sell or provide", allowableValues = {"shop", "sell", "provide"})
            String intent,
            @Schema(description = "Organisation invitation token from the invite link (token query parameter). The email "
                    + "must be the invited one; the account starts verified and joins the organisation.")
            @Size(max = 200) String invitationToken) {
    }

    @Schema(name = "SignUpResponse")
    record SignUpResponse(UUID userId, String email,
                          @Schema(description = "false when the sign-up came from an invitation link") boolean verificationRequired,
                          @Schema(description = "null when no code was sent") Instant codeExpiresAt) {
    }

    @Schema(name = "VerifyEmailRequest")
    record VerifyEmailRequest(@NotBlank @Email String email,
                              @NotBlank @Size(min = 6, max = 6) String code) {
    }

    @Schema(name = "ResendCodeRequest")
    record ResendCodeRequest(@NotBlank @Email String email) {
    }

    @Schema(name = "CodeSentResponse")
    record CodeSentResponse(Instant codeExpiresAt) {
    }
}
