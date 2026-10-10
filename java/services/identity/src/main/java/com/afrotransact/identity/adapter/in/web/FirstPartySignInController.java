package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.identity.application.ClientContext;
import com.afrotransact.identity.application.FirstPartySignInService;
import com.afrotransact.identity.application.PasskeyChallengeView;
import com.afrotransact.identity.application.PasswordSignIn;
import com.afrotransact.identity.application.SecondFactorChallenge;
import com.afrotransact.identity.application.SecondFactorSubmission;
import com.afrotransact.identity.application.SignInStep;
import com.afrotransact.identity.application.SmsCodeSent;
import com.afrotransact.identity.domain.MfaFactor;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Tag(name = "First-party sign-in", description = "Password, two-step and passkey sign-in for the storefront's own "
        + "forms, called by gateway-bff only (client-credentials token, scope auth:first-party). A completed sign-in "
        + "returns a one-time loginTicket that the BFF redeems on GET /oauth2/authorize?…&login_ticket=… "
        + "(authorization code + PKCE), then exchanges the code at /oauth2/token. Failed attempts count towards "
        + "per-account and per-IP lockouts (429 identity.sign-in-locked with Retry-After).")
@RestController
class FirstPartySignInController {

    private static final String IP_PATTERN = "^[0-9A-Fa-f:.]{2,45}$";

    private final FirstPartySignInService signIn;

    FirstPartySignInController(FirstPartySignInService signIn) {
        this.signIn = signIn;
    }

    @Schema(name = "PasswordSignInRequest")
    record PasswordSignInRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 1024) String password,
            @Size(max = 200) @Schema(description = "Token from an earlier verification with trustDevice=true; skips "
                    + "the second factor for 30 days") String trustedDeviceToken,
            @NotBlank @Pattern(regexp = IP_PATTERN) @Schema(description = "End user's IP address (throttling, audit)",
                    examples = "203.0.113.7") String clientIp,
            @Size(max = 512) @Schema(description = "End user's User-Agent") String userAgent) {
    }

    @Schema(name = "MfaSmsRequest")
    record MfaSmsRequest(@NotNull UUID challengeId,
                         @NotBlank @Pattern(regexp = IP_PATTERN) String clientIp,
                         @Size(max = 512) String userAgent) {
    }

    @Schema(name = "MfaVerifyRequest")
    record MfaVerifyRequest(
            @NotNull UUID challengeId,
            @NotBlank @Schema(allowableValues = {"TOTP", "SMS", "RECOVERY_CODE"}) String factor,
            @NotBlank @Size(max = 32) String code,
            @Schema(description = "Return a trustedDeviceToken (30 days) to skip the second factor next time")
            boolean trustDevice,
            @NotBlank @Pattern(regexp = IP_PATTERN) String clientIp,
            @Size(max = 512) String userAgent) {
    }

    @Schema(name = "PasskeyOptionsRequest")
    record PasskeyOptionsRequest(@NotBlank @Pattern(regexp = IP_PATTERN) String clientIp,
                                 @Size(max = 512) String userAgent) {
    }

    @Schema(name = "PasskeyVerifyRequest")
    record PasskeyVerifyRequest(
            @NotNull UUID challengeId,
            @NotNull @Schema(types = "object", implementation = Object.class, description = "PublicKeyCredential from navigator.credentials.get "
                    + "(JSON with base64url fields: id, rawId, type, response.{authenticatorData, clientDataJSON, "
                    + "signature, userHandle}, clientExtensionResults, authenticatorAttachment)") JsonNode credential,
            @NotBlank @Pattern(regexp = IP_PATTERN) String clientIp,
            @Size(max = 512) String userAgent) {
    }

    @Schema(name = "SecondFactorOptions")
    record SecondFactorOptions(boolean totp, boolean sms,
                               @Schema(description = "Masked phone, e.g. •••• 0142") String smsHint,
                               boolean recoveryCode) {

        static SecondFactorOptions of(SecondFactorChallenge c) {
            return new SecondFactorOptions(c.totp(), c.sms(), c.smsHint(), c.recovery());
        }
    }

    @Schema(name = "SignInStepResponse", description = "MFA_REQUIRED: continue with challengeId and one of factors. "
            + "AUTHENTICATED: redeem loginTicket (single use, 60 s) on /oauth2/authorize.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SignInStepResponse(@Schema(allowableValues = {"MFA_REQUIRED", "AUTHENTICATED"}) String status,
                              UUID challengeId, Instant challengeExpiresAt, SecondFactorOptions factors,
                              UUID userId, String loginTicket, Instant loginTicketExpiresAt,
                              String trustedDeviceToken, Instant trustedDeviceExpiresAt) {

        static SignInStepResponse of(SignInStep s) {
            return new SignInStepResponse(s.status().name(), s.challengeId(), s.challengeExpiresAt(),
                    s.factors() == null ? null : SecondFactorOptions.of(s.factors()), s.userId(), s.loginTicket(),
                    s.loginTicketExpiresAt(), s.trustedDeviceToken(), s.trustedDeviceExpiresAt());
        }
    }

    @Schema(name = "SmsCodeResponse")
    record SmsCodeResponse(String sentTo, Instant expiresAt) {
    }

    @Schema(name = "PasskeyOptionsResponse")
    record PasskeyOptionsResponse(UUID challengeId, Instant expiresAt,
                                  @JsonRawValue @Schema(types = "object", description = "PublicKeyCredentialRequestOptions "
                                          + "for navigator.credentials.get (challenge base64url, rpId, timeout ms, "
                                          + "userVerification=required, no allowCredentials: discoverable passkeys)")
                                  String publicKey) {
    }

    @Operation(summary = "Sign in with email and password (returns an MFA challenge or a login ticket)")
    @PostMapping("/v1/auth/password")
    SignInStepResponse password(@Valid @RequestBody PasswordSignInRequest body) {
        return SignInStepResponse.of(signIn.password(new PasswordSignIn(body.email(), body.password(),
                body.trustedDeviceToken(), new ClientContext(body.clientIp(), body.userAgent()))));
    }

    @Operation(summary = "Text a sign-in code for an MFA challenge")
    @PostMapping("/v1/auth/mfa/sms")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SmsCodeResponse sms(@Valid @RequestBody MfaSmsRequest body) {
        SmsCodeSent sent = signIn.sendSmsCode(body.challengeId(), new ClientContext(body.clientIp(),
                body.userAgent()));
        return new SmsCodeResponse(sent.sentTo(), sent.expiresAt());
    }

    @Operation(summary = "Verify an authenticator, text-message or recovery code (returns a login ticket)")
    @PostMapping("/v1/auth/mfa/verify")
    SignInStepResponse verify(@Valid @RequestBody MfaVerifyRequest body) {
        MfaFactor factor = MfaFactor.valueOf(body.factor().trim().toUpperCase(Locale.ROOT));
        return SignInStepResponse.of(signIn.verifySecondFactor(new SecondFactorSubmission(body.challengeId(), factor,
                body.code(), body.trustDevice(), new ClientContext(body.clientIp(), body.userAgent()))));
    }

    @Operation(summary = "Start a passkey sign-in (WebAuthn assertion options)")
    @PostMapping("/v1/auth/passkey/options")
    PasskeyOptionsResponse passkeyOptions(@Valid @RequestBody PasskeyOptionsRequest body) {
        PasskeyChallengeView v = signIn.passkeyOptions(new ClientContext(body.clientIp(), body.userAgent()));
        return new PasskeyOptionsResponse(v.challengeId(), v.expiresAt(), v.optionsJson());
    }

    @Operation(summary = "Verify a passkey assertion (returns a login ticket; passkeys need no second factor)")
    @PostMapping("/v1/auth/passkey/verify")
    SignInStepResponse passkeyVerify(@Valid @RequestBody PasskeyVerifyRequest body) {
        return SignInStepResponse.of(signIn.verifyPasskey(body.challengeId(), body.credential().toString(),
                new ClientContext(body.clientIp(), body.userAgent())));
    }
}
