package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.web.SecurityDtos.PasskeyResponse;
import com.afrotransact.identity.application.PasskeyRegistrationView;
import com.afrotransact.identity.application.PasskeyService;
import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * S016: passkey registration and management over the API with the user's bearer token, for the storefront's Login &
 * security page (through gateway-bff) and the mobile apps (platform passkeys: Face ID, Touch ID, fingerprint).
 */
@Tag(name = "Login & security", description = "Password, active sessions, linked social accounts and passkeys")
@RestController
class PasskeyRegistrationController {

    private final PasskeyService passkeys;

    PasskeyRegistrationController(PasskeyService passkeys) {
        this.passkeys = passkeys;
    }

    @Schema(name = "PasskeyRegistrationStartRequest")
    record StartRequest(@Size(max = 64) @Schema(description = "Name shown in Login & security, e.g. \"iPhone\"")
                        String label) {
    }

    @Schema(name = "PasskeyRegistrationOptions")
    record OptionsResponse(UUID registrationId, Instant expiresAt,
                           @JsonRawValue @Schema(types = "object", description = "PublicKeyCredentialCreationOptions "
                                   + "for navigator.credentials.create / ASAuthorizationPlatformPublicKeyCredential"
                                   + "Provider / Android Credential Manager (challenge and user.id base64url, rp, "
                                   + "pubKeyCredParams, residentKey=required, userVerification=required, "
                                   + "excludeCredentials = the account's passkeys, timeout ms)")
                           String publicKey) {

        static OptionsResponse of(PasskeyRegistrationView v) {
            return new OptionsResponse(v.registrationId(), v.expiresAt(), v.optionsJson());
        }
    }

    @Schema(name = "PasskeyRegistrationRequest")
    record RegisterRequest(@NotNull UUID registrationId,
                           @NotNull @Schema(types = "object", implementation = Object.class,
                                   description = "PublicKeyCredential from navigator.credentials.create (JSON with "
                                           + "base64url fields: id, rawId, type, response.{attestationObject, "
                                           + "clientDataJSON, transports}, clientExtensionResults, "
                                           + "authenticatorAttachment), e.g. credential.toJSON()") JsonNode credential,
                           @Size(max = 64) String label) {
    }

    @Schema(name = "PasskeyRenameRequest")
    record RenameRequest(@NotBlank @Size(max = 64) String label) {
    }

    @Operation(summary = "Start registering a passkey for the signed-in account",
            description = "Returns WebAuthn creation options valid for five minutes and one registration. Up to 20 "
                    + "passkeys per account (422 identity.too-many-passkeys).")
    @PostMapping("/v1/me/passkeys/registrations")
    @ResponseStatus(HttpStatus.CREATED)
    OptionsResponse startPasskeyRegistration(@Valid @RequestBody(required = false) StartRequest body) {
        return OptionsResponse.of(passkeys.startRegistration(body == null ? null : body.label()));
    }

    @Operation(summary = "Finish registering a passkey",
            description = "Verifies the attestation (challenge, origin, RP id, user presence and verification) with the "
                    + "same relying party as the hosted page. 422 identity.passkey-rejected; 409 "
                    + "identity.passkey-registration-expired when the registration expired, was used or is another "
                    + "account's.")
    @PostMapping("/v1/me/passkeys")
    @ResponseStatus(HttpStatus.CREATED)
    PasskeyResponse registerPasskey(@Valid @RequestBody RegisterRequest body) {
        return PasskeyResponse.of(passkeys.completeRegistration(body.registrationId(), body.credential().toString(),
                body.label()));
    }

    @Operation(summary = "Rename a passkey")
    @PatchMapping("/v1/me/passkeys/{credentialId}")
    PasskeyResponse renamePasskey(@PathVariable String credentialId, @Valid @RequestBody RenameRequest body) {
        return PasskeyResponse.of(passkeys.rename(credentialId, body.label()));
    }
}
