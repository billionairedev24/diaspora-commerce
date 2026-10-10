package com.afrotransact.identity.adapter.in.web;

import com.afrotransact.identity.adapter.in.web.MeDtos.MeResponse;
import com.afrotransact.identity.adapter.in.web.MeDtos.SelectOrgRequest;
import com.afrotransact.identity.adapter.in.web.MeDtos.UpdateProfileRequest;
import com.afrotransact.identity.application.ProfileService;
import com.afrotransact.identity.application.UpdateProfileCommand;
import com.afrotransact.identity.domain.OrgKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Me", description = "The signed-in user: profile, roles, permissions and role-based landing")
@RestController
class MeController {

    private final ProfileService profile;

    MeController(ProfileService profile) {
        this.profile = profile;
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

    @Operation(summary = "Choose the organisation to act for (token org claim from the next token)")
    @PutMapping("/v1/me/active-org")
    MeResponse selectOrg(@Valid @RequestBody SelectOrgRequest body) {
        return MeResponse.of(profile.selectOrg(body.orgId()));
    }
}
