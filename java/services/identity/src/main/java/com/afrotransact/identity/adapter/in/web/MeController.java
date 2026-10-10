package com.afrotransact.identity.adapter.in.web;

import com.afrotransact.identity.adapter.in.web.MeDtos.MeResponse;
import com.afrotransact.identity.adapter.in.web.MeDtos.PhoneCodeSentResponse;
import com.afrotransact.identity.adapter.in.web.MeDtos.PhoneVerificationResponse;
import com.afrotransact.identity.adapter.in.web.MeDtos.SelectOrgRequest;
import com.afrotransact.identity.adapter.in.web.MeDtos.UpdateProfileRequest;
import com.afrotransact.identity.adapter.in.web.SecurityDtos.CodeRequest;
import com.afrotransact.identity.application.PhoneVerificationService;
import com.afrotransact.identity.application.PhoneVerificationView;
import com.afrotransact.identity.application.ProfileService;
import com.afrotransact.identity.application.SmsCodeSent;
import com.afrotransact.identity.application.UpdateProfileCommand;
import com.afrotransact.identity.domain.OrgKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Me", description = "The signed-in user: profile, roles, permissions and role-based landing")
@RestController
class MeController {

    private final ProfileService profile;
    private final PhoneVerificationService phone;

    MeController(ProfileService profile, PhoneVerificationService phone) {
        this.profile = profile;
        this.phone = phone;
    }

    @Operation(summary = "Profile, roles, effective permissions, organisations and landing")
    @GetMapping("/v1/me")
    MeResponse me() {
        return MeResponse.of(profile.me());
    }

    @Operation(summary = "Complete or edit the profile (social sign-ups add their phone and accept terms here)")
    @PatchMapping("/v1/me")
    MeResponse update(@Valid @RequestBody UpdateProfileRequest body) {
        return MeResponse.of(profile.update(new UpdateProfileCommand(body.firstName(), body.lastName(), body.phone(),
                body.phoneRegion(), body.termsAccepted(), body.smsConsent())));
    }

    @Operation(summary = "Start selling: adds account.seller_applicant to this account (no second sign-up)")
    @PostMapping("/v1/me/roles/seller-applicant")
    MeResponse startSelling() {
        return MeResponse.of(profile.becomeApplicant(OrgKind.SELLER));
    }

    @Operation(summary = "Offer services: adds account.provider_applicant to this account (no second sign-up)")
    @PostMapping("/v1/me/roles/provider-applicant")
    MeResponse startProviding() {
        return MeResponse.of(profile.becomeApplicant(OrgKind.PROVIDER));
    }

    @Operation(summary = "Text a 6-digit code to the account's phone number (S234)",
            description = "Independent of MFA. 10-minute expiry, 5 wrong attempts, at most 1 code per 30 seconds and 5 "
                    + "per hour (429 + Retry-After). 422 identity.phone-missing without a phone; 409 "
                    + "identity.phone-already-verified. Published as identity.verification.requested (channel sms, "
                    + "purpose PHONE_VERIFICATION).")
    @PostMapping("/v1/me/phone/verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PhoneCodeSentResponse startPhoneVerification() {
        SmsCodeSent sent = phone.start();
        return new PhoneCodeSentResponse(sent.sentTo(), sent.expiresAt());
    }

    @Operation(summary = "Confirm the phone number with the SMS code",
            description = "422 identity.invalid-code / code-expired / too-many-attempts / code-used / phone-changed. "
                    + "Changing the phone with PATCH /v1/me resets phoneVerified. Publishes identity.user.phone-verified.")
    @PostMapping("/v1/me/phone/verification/confirm")
    PhoneVerificationResponse confirmPhoneVerification(@Valid @RequestBody CodeRequest body) {
        PhoneVerificationView v = phone.confirm(body.code());
        return new PhoneVerificationResponse(v.phone(), v.phoneVerified(), v.verifiedAt());
    }

    @Operation(summary = "Choose the organisation to act for (token org claim from the next token)")
    @PutMapping("/v1/me/active-org")
    MeResponse selectOrg(@Valid @RequestBody SelectOrgRequest body) {
        return MeResponse.of(profile.selectOrg(body.orgId()));
    }
}
