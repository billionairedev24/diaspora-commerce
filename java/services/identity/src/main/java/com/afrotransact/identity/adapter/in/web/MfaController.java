package com.afrotransact.identity.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.web.SecurityDtos.CodeRequest;
import com.afrotransact.identity.adapter.in.web.SecurityDtos.CodeSentResponse;
import com.afrotransact.identity.adapter.in.web.SecurityDtos.MfaStatusResponse;
import com.afrotransact.identity.adapter.in.web.SecurityDtos.RecoveryCodesResponse;
import com.afrotransact.identity.adapter.in.web.SecurityDtos.TotpSetupResponse;
import com.afrotransact.identity.adapter.in.web.SecurityDtos.TrustedBrowserResponse;
import com.afrotransact.identity.application.MfaService;
import com.afrotransact.identity.application.TotpSetup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Two-step verification", description = "Authenticator app, SMS codes and recovery codes")
@RestController
class MfaController {

    private final MfaService mfa;

    MfaController(MfaService mfa) {
        this.mfa = mfa;
    }

    @Operation(summary = "Enrolled factors and whether MFA is required / may be turned off (never for staff)")
    @GetMapping("/v1/me/mfa")
    MfaStatusResponse status() {
        return MfaStatusResponse.of(mfa.status());
    }

    @Operation(summary = "Start authenticator-app enrolment (QR otpauth URI)")
    @PostMapping("/v1/me/mfa/totp")
    TotpSetupResponse startTotp() {
        TotpSetup s = mfa.startTotp();
        return new TotpSetupResponse(s.secret(), s.otpauthUri());
    }

    @Operation(summary = "Confirm the authenticator app with a code; returns recovery codes the first time")
    @PostMapping("/v1/me/mfa/totp/confirm")
    RecoveryCodesResponse confirmTotp(@Valid @RequestBody CodeRequest body) {
        return new RecoveryCodesResponse(mfa.confirmTotp(body.code()));
    }

    @Operation(summary = "Turn off the authenticator app (403 identity.staff-mfa-required for staff without SMS codes on)")
    @DeleteMapping("/v1/me/mfa/totp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disableTotp() {
        mfa.disableTotp();
    }

    @Operation(summary = "Text a code to the account phone to turn on SMS sign-in codes")
    @PostMapping("/v1/me/mfa/sms")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CodeSentResponse startSms() {
        return new CodeSentResponse(mfa.startSms());
    }

    @Operation(summary = "Confirm SMS sign-in codes; returns recovery codes the first time")
    @PostMapping("/v1/me/mfa/sms/confirm")
    RecoveryCodesResponse confirmSms(@Valid @RequestBody CodeRequest body) {
        return new RecoveryCodesResponse(mfa.confirmSms(body.code()));
    }

    @Operation(summary = "Turn off SMS sign-in codes (403 identity.staff-mfa-required for staff without the authenticator app on)")
    @DeleteMapping("/v1/me/mfa/sms")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disableSms() {
        mfa.disableSms();
    }

    @Operation(summary = "Regenerate recovery codes (old ones stop working)")
    @PostMapping("/v1/me/mfa/recovery-codes")
    RecoveryCodesResponse regenerate() {
        return new RecoveryCodesResponse(mfa.regenerateRecoveryCodes());
    }

    @Operation(summary = "Browsers trusted to skip the second factor (\"Trust this browser\"), unexpired only")
    @GetMapping("/v1/me/trusted-browsers")
    List<TrustedBrowserResponse> trustedBrowsers() {
        return mfa.trustedBrowsers().stream().map(TrustedBrowserResponse::of).toList();
    }

    @Operation(summary = "Forget every trusted browser (each asks for the second factor again)")
    @DeleteMapping("/v1/me/trusted-browsers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forgetTrustedBrowsers() {
        mfa.forgetTrustedBrowsers();
    }

    @Operation(summary = "Forget one trusted browser (404 identity.trusted-browser-not-found)")
    @DeleteMapping("/v1/me/trusted-browsers/{browserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forgetTrustedBrowser(@PathVariable UUID browserId) {
        mfa.forgetTrustedBrowser(browserId);
    }
}
