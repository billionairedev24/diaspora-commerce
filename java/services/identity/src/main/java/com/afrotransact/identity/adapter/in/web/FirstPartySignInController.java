package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.identity.application.ClientContext;
import com.afrotransact.identity.application.EmailCodeSent;
import com.afrotransact.identity.application.EnrolmentCompleted;
import com.afrotransact.identity.application.FirstPartySignInService;
import com.afrotransact.identity.application.PasskeyChallengeView;
import com.afrotransact.identity.application.PasswordSignIn;
import com.afrotransact.identity.application.SecondFactorChallenge;
import com.afrotransact.identity.application.SecondFactorSubmission;
import com.afrotransact.identity.application.SignInStep;
import com.afrotransact.identity.application.SmsCodeSent;
import com.afrotransact.identity.application.TotpSetup;
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
            @Size(max = 512) @Schema(description = "End user's User-Agent") String userAgent,
            @Size(max = 64) @Schema(description = "Native app the login ticket is for (customer-app, driver-app); omit for web sessions") String appClientId) {
    }

    @Schema(name = "MfaSmsRequest")
    record MfaSmsRequest(@NotNull UUID challengeId,
                         @NotBlank @Pattern(regexp = IP_PATTERN) String clientIp,
                         @Size(max = 512) String userAgent) {
    }

    @Schema(name = "MfaChallengeRequest", description = "Identifies an open sign-in challenge (MFA_REQUIRED or "
            + "MFA_ENROLMENT_REQUIRED) and the end user's client details")
    record MfaChallengeRequest(@NotNull UUID challengeId,
                               @NotBlank @Pattern(regexp = IP_PATTERN) String clientIp,
                               @Size(max = 512) String userAgent) {
    }

    @Schema(name = "MfaEnrolConfirmRequest")
    record MfaEnrolConfirmRequest(@NotNull UUID challengeId, @NotBlank @Size(max = 32) String code,
                                  @NotBlank @Pattern(regexp = IP_PATTERN) String clientIp,
                                  @Size(max = 512) String userAgent) {
    }

    @Schema(name = "MfaVerifyRequest")
    record MfaVerifyRequest(
            @NotNull UUID challengeId,
            @NotBlank @Schema(allowableValues = {"TOTP", "EMAIL_OTP", "SMS", "RECOVERY_CODE"}) String factor,
            @NotBlank @Size(max = 32) String code,
            @Schema(description = "Return a trustedDeviceToken (30 days) to skip the second factor next time")
            boolean trustDevice,
            @NotBlank @Pattern(regexp = IP_PATTERN) String clientIp,
            @Size(max = 512) String userAgent,
            @Size(max = 64) String appClientId) {
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
    record SecondFactorOptions(boolean totp, boolean emailOtp,
                               @Schema(description = "Masked email the code goes to, e.g. a••••@example.com")
                               String emailHint,
                               @Schema(description = "Only while SMS codes are switched on (off by default)")
                               boolean sms,
                               @Schema(description = "Masked phone, e.g. •••• 0142 (SMS only)") String smsHint,
                               boolean recoveryCode,
                               @Schema(description = "Enrolment methods offered when status is "
                                       + "MFA_ENROLMENT_REQUIRED (authenticator and email codes)")
                               List<String> enrolMethods) {

        static SecondFactorOptions of(SecondFactorChallenge c) {
            return new SecondFactorOptions(c.totp(), c.emailOtp(), c.emailHint(), c.sms(), c.smsHint(), c.recovery(),
                    c.enrolmentRequired() ? List.of("TOTP", "EMAIL_OTP") : null);
        }
    }

    @Schema(name = "FirstPartyTotpSetup", description = "Render otpauthUri as a QR code; secret is for manual entry")
    record FirstPartyTotpSetup(String secret, String otpauthUri) {
    }

    @Schema(name = "EnrolmentCompleted", description = "The forced enrolment finished: redeem step.loginTicket; "
            + "show recoveryCodes once when present")
    record EnrolmentCompletedResponse(List<String> recoveryCodes, SignInStepResponse step) {
    }

    @Schema(name = "SignInStepResponse", description = "MFA_REQUIRED: the person enrolled a factor: continue with "
            + "challengeId and one of factors. MFA_ENROLMENT_REQUIRED: MFA is mandatory for them and nothing is set "
            + "up: no code was sent, enrol with the /v1/auth/mfa/enrol/* endpoints using challengeId. "
            + "AUTHENTICATED: redeem loginTicket (single use, 60 s) on /oauth2/authorize.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SignInStepResponse(@Schema(allowableValues = {"MFA_REQUIRED", "MFA_ENROLMENT_REQUIRED", "AUTHENTICATED"}) String status,
                              UUID challengeId, Instant challengeExpiresAt, SecondFactorOptions factors,
                              UUID userId, String loginTicket, Instant loginTicketExpiresAt,
                              String trustedDeviceToken, Instant trustedDeviceExpiresAt,
                              @Schema(description = "AUTHENTICATED only: show a gentle, dismissable 'Secure your "
                                      + "account' prompt (nothing enrolled, reminder due). Never blocks sign-in.")
                              boolean mfaSetupSuggested) {

        static SignInStepResponse of(SignInStep s) {
            return new SignInStepResponse(s.status().name(), s.challengeId(), s.challengeExpiresAt(),
                    s.factors() == null ? null : SecondFactorOptions.of(s.factors()), s.userId(), s.loginTicket(),
                    s.loginTicketExpiresAt(), s.trustedDeviceToken(), s.trustedDeviceExpiresAt(),
                    s.mfaSetupSuggested());
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
                body.trustedDeviceToken(), new ClientContext(body.clientIp(), body.userAgent(), body.appClientId()))));
    }

    @Operation(summary = "Text a sign-in code for an MFA challenge")
    @PostMapping("/v1/auth/mfa/sms")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SmsCodeResponse sms(@Valid @RequestBody MfaSmsRequest body) {
        SmsCodeSent sent = signIn.sendSmsCode(body.challengeId(), new ClientContext(body.clientIp(),
                body.userAgent()));
        return new SmsCodeResponse(sent.sentTo(), sent.expiresAt());
    }

    @Operation(summary = "Verify an authenticator, email-code, text-message or recovery code (returns a login ticket)")
    @PostMapping("/v1/auth/mfa/verify")
    SignInStepResponse verify(@Valid @RequestBody MfaVerifyRequest body) {
        MfaFactor factor = MfaFactor.valueOf(body.factor().trim().toUpperCase(Locale.ROOT));
        return SignInStepResponse.of(signIn.verifySecondFactor(new SecondFactorSubmission(body.challengeId(), factor,
                body.code(), body.trustDevice(),
                new ClientContext(body.clientIp(), body.userAgent(), body.appClientId()))));
    }

    @Operation(summary = "Email a sign-in code for an MFA challenge (only for people who turned email codes on)")
    @PostMapping("/v1/auth/mfa/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SmsCodeResponse email(@Valid @RequestBody MfaChallengeRequest body) {
        EmailCodeSent sent = signIn.sendEmailCode(body.challengeId(), new ClientContext(body.clientIp(),
                body.userAgent()));
        return new SmsCodeResponse(sent.sentTo(), sent.expiresAt());
    }

    @Operation(summary = "Forced enrolment: start the authenticator app (MFA_ENROLMENT_REQUIRED challenge)")
    @PostMapping("/v1/auth/mfa/enrol/totp")
    FirstPartyTotpSetup enrolTotp(@Valid @RequestBody MfaChallengeRequest body) {
        TotpSetup s = signIn.enrolTotp(body.challengeId(), new ClientContext(body.clientIp(), body.userAgent()));
        return new FirstPartyTotpSetup(s.secret(), s.otpauthUri());
    }

    @Operation(summary = "Forced enrolment: confirm the authenticator with a code and finish signing in")
    @PostMapping("/v1/auth/mfa/enrol/totp/confirm")
    EnrolmentCompletedResponse confirmEnrolTotp(@Valid @RequestBody MfaEnrolConfirmRequest body) {
        EnrolmentCompleted r = signIn.confirmEnrolTotp(body.challengeId(), body.code(),
                new ClientContext(body.clientIp(), body.userAgent()));
        return new EnrolmentCompletedResponse(r.recoveryCodes(), SignInStepResponse.of(r.step()));
    }

    @Operation(summary = "Forced enrolment: email a first code to the verified account email")
    @PostMapping("/v1/auth/mfa/enrol/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SmsCodeResponse enrolEmail(@Valid @RequestBody MfaChallengeRequest body) {
        EmailCodeSent sent = signIn.enrolEmail(body.challengeId(), new ClientContext(body.clientIp(),
                body.userAgent()));
        return new SmsCodeResponse(sent.sentTo(), sent.expiresAt());
    }

    @Operation(summary = "Forced enrolment: confirm the emailed code, turn email codes on and finish signing in")
    @PostMapping("/v1/auth/mfa/enrol/email/confirm")
    EnrolmentCompletedResponse confirmEnrolEmail(@Valid @RequestBody MfaEnrolConfirmRequest body) {
        EnrolmentCompleted r = signIn.confirmEnrolEmail(body.challengeId(), body.code(),
                new ClientContext(body.clientIp(), body.userAgent()));
        return new EnrolmentCompletedResponse(r.recoveryCodes(), SignInStepResponse.of(r.step()));
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
