package com.afrotransact.identity.adapter.in.web;

import java.util.List;
import java.util.Optional;

import com.afrotransact.identity.adapter.in.web.SecurityDtos.ChangePasswordRequest;
import com.afrotransact.identity.adapter.in.web.SecurityDtos.LinkedAccountsResponse;
import com.afrotransact.identity.adapter.in.web.SecurityDtos.PasskeyResponse;
import com.afrotransact.identity.adapter.in.web.SecurityDtos.RevokedResponse;
import com.afrotransact.identity.adapter.in.web.SecurityDtos.SessionResponse;
import com.afrotransact.identity.application.PasskeyService;
import com.afrotransact.identity.application.SecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Login & security", description = "Password, active sessions, linked social accounts and passkeys")
@RestController
class SecurityController {

    private final SecurityService security;
    private final PasskeyService passkeys;

    SecurityController(SecurityService security, PasskeyService passkeys) {
        this.security = security;
        this.passkeys = passkeys;
    }

    @Operation(summary = "Change password (requires the current password)")
    @PutMapping("/v1/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody ChangePasswordRequest body) {
        security.changePassword(body.currentPassword(), body.newPassword(), body.confirmPassword());
    }

    @Operation(summary = "Active sessions (one per signed-in app or device); current marks this device")
    @GetMapping("/v1/me/sessions")
    List<SessionResponse> sessions(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return security.sessions(token(jwt)).stream().map(SessionResponse::of).toList();
    }

    @Operation(summary = "Sign out one session (its refresh token stops working immediately)")
    @DeleteMapping("/v1/me/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable String sessionId) {
        security.revokeSession(sessionId);
    }

    @Operation(summary = "Sign out other devices")
    @PostMapping("/v1/me/sessions/revoke-others")
    RevokedResponse revokeOthers(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return new RevokedResponse(security.revokeOtherSessions(token(jwt)));
    }

    @Operation(summary = "Linked Google / Apple / Facebook accounts")
    @GetMapping("/v1/me/linked-accounts")
    LinkedAccountsResponse linked() {
        return LinkedAccountsResponse.of(security.linkedAccounts());
    }

    @Operation(summary = "Unlink a social account (refused for the last sign-in method)")
    @DeleteMapping("/v1/me/linked-accounts/{provider}")
    LinkedAccountsResponse unlink(@PathVariable String provider) {
        return LinkedAccountsResponse.of(security.unlink(provider));
    }

    @Operation(summary = "Registered passkeys",
            description = "Register on the hosted page (/webauthn/register) or over the API: POST "
                    + "/v1/me/passkeys/registrations, then POST /v1/me/passkeys (first-party apps, mobile).")
    @GetMapping("/v1/me/passkeys")
    List<PasskeyResponse> passkeys() {
        return passkeys.list().stream().map(PasskeyResponse::of).toList();
    }

    @Operation(summary = "Remove a passkey (refused for the last sign-in method)")
    @DeleteMapping("/v1/me/passkeys/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removePasskey(@PathVariable String credentialId) {
        passkeys.delete(credentialId);
    }

    private static Optional<String> token(Jwt jwt) {
        return Optional.ofNullable(jwt).map(Jwt::getTokenValue);
    }
}
