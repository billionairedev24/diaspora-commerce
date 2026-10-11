package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.application.LinkedAccountsView;
import com.afrotransact.identity.application.MfaStatus;
import com.afrotransact.identity.domain.FederatedIdentity;
import com.afrotransact.identity.domain.Passkey;
import com.afrotransact.identity.domain.SignInAuditEntry;
import com.afrotransact.identity.domain.TrustedBrowser;
import com.afrotransact.identity.domain.UserSession;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Login & security page bodies: password, sessions, linked accounts, passkeys, MFA. */
final class SecurityDtos {

    private SecurityDtos() {
    }

    @Schema(name = "ChangePasswordRequest", description = "currentPassword is required when the account has one")
    record ChangePasswordRequest(@Size(max = 128) String currentPassword, @NotBlank @Size(max = 128) String newPassword,
                                 @NotBlank @Size(max = 128) String confirmPassword) {
    }

    @Schema(name = "Session")
    record SessionResponse(String id, String clientId, String clientName, Instant signedInAt, Instant lastActiveAt,
                           Instant expiresAt, boolean current) {

        static SessionResponse of(UserSession s) {
            return new SessionResponse(s.id(), s.clientId(), s.clientName(), s.signedInAt(), s.lastActiveAt(),
                    s.expiresAt(), s.current());
        }
    }

    @Schema(name = "RevokedSessions")
    record RevokedResponse(int revoked) {
    }

    @Schema(name = "LinkedAccount")
    record LinkedAccountResponse(String provider, String email, Instant linkedAt, boolean canUnlink) {
    }

    @Schema(name = "LinkedAccounts")
    record LinkedAccountsResponse(List<LinkedAccountResponse> accounts, boolean hasPassword, int passkeys,
                                  @Schema(description = "Start linking by visiting this path on the authorization "
                                          + "server while signed in: /oauth2/authorization/{provider}")
                                  String linkPathTemplate) {

        static LinkedAccountsResponse of(LinkedAccountsView v) {
            List<LinkedAccountResponse> accounts = v.identities().stream()
                    .map((FederatedIdentity f) -> new LinkedAccountResponse(f.provider(), f.email(), f.linkedAt(),
                            v.methods().canUnlink(f.provider())))
                    .toList();
            return new LinkedAccountsResponse(accounts, v.methods().password(), v.methods().passkeys(),
                    "/oauth2/authorization/{provider}");
        }
    }

    @Schema(name = "Passkey")
    record PasskeyResponse(String credentialId, String label, Instant createdAt, Instant lastUsedAt) {

        static PasskeyResponse of(Passkey p) {
            return new PasskeyResponse(p.credentialId(), p.label(), p.createdAt(), p.lastUsedAt());
        }
    }

    @Schema(name = "MfaStatus")
    record MfaStatusResponse(boolean totp, boolean sms, String smsPhone, int recoveryCodesLeft, int passkeys,
                             boolean required, boolean mayDisable,
                             @Schema(description = "The authenticator app may be turned off now (staff only while SMS "
                                     + "codes stay on)") boolean mayRemoveTotp,
                             @Schema(description = "SMS codes may be turned off now (staff only while the "
                                     + "authenticator app stays on)") boolean mayRemoveSms) {

        static MfaStatusResponse of(MfaStatus s) {
            return new MfaStatusResponse(s.enrolment().totp(), s.enrolment().sms(), s.enrolment().smsPhone(),
                    s.enrolment().recoveryCodesLeft(), s.enrolment().passkeys(), s.required(), s.mayDisable(),
                    s.mayRemoveTotp(), s.mayRemoveSms());
        }
    }

    @Schema(name = "TrustedBrowser")
    record TrustedBrowserResponse(UUID id, String userAgent, Instant createdAt, Instant expiresAt) {

        static TrustedBrowserResponse of(TrustedBrowser b) {
            return new TrustedBrowserResponse(b.id(), b.userAgent(), b.createdAt(), b.expiresAt());
        }
    }

    @Schema(name = "SignInActivity", description = "One sign-in audit row of the signed-in account")
    record SignInActivityResponse(Instant at, @Schema(examples = {"PASSWORD", "MFA_TOTP", "PASSKEY"}) String method,
                                  @Schema(examples = {"SUCCEEDED", "FAILED", "MFA_REQUIRED"}) String outcome,
                                  @Schema(description = "first-party (BFF forms) or hosted (identity's pages)")
                                  String channel, String clientId, String ip, String userAgent) {

        static SignInActivityResponse of(SignInAuditEntry e) {
            return new SignInActivityResponse(e.at(), e.method(), e.outcome(), e.channel(), e.clientId(), e.ip(),
                    e.userAgent());
        }
    }

    @Schema(name = "TotpSetup", description = "Render otpauthUri as a QR code; secret is for manual entry")
    record TotpSetupResponse(String secret, String otpauthUri) {
    }

    @Schema(name = "CodeRequest")
    record CodeRequest(@NotBlank @Size(min = 6, max = 6) String code) {
    }

    @Schema(name = "RecoveryCodes", description = "Shown once; empty when existing recovery codes were kept")
    record RecoveryCodesResponse(List<String> recoveryCodes) {
    }

    @Schema(name = "CodeSent")
    record CodeSentResponse(Instant codeExpiresAt) {
    }
}
