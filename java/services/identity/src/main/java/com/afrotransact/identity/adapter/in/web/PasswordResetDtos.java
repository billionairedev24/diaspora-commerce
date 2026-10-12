package com.afrotransact.identity.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Forgotten-password bodies (S300). */
final class PasswordResetDtos {

    private PasswordResetDtos() {
    }

    @Schema(name = "PasswordResetRequest")
    record ResetCodeRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    @Schema(name = "PasswordResetRequested", description = "The same answer whether or not the email has an account")
    record ResetCodeRequested(@Schema(example = "10") int codeTtlMinutes) {
    }

    @Schema(name = "PasswordResetConfirmRequest")
    record ResetConfirmRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code,
            @NotBlank @Size(max = 128) String newPassword,
            @NotBlank @Size(max = 128) String confirmPassword) {

        /** Never print the code or the passwords. */
        @Override
        public String toString() {
            return "ResetConfirmRequest[email=" + email + "]";
        }
    }
}
