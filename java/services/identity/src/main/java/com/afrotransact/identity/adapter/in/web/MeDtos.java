package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.identity.application.MeView;
import com.afrotransact.identity.domain.OrgMembership;
import com.afrotransact.identity.domain.UserAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Profile, landing and org selection bodies. */
final class MeDtos {

    private MeDtos() {
    }

    @Schema(name = "Org")
    record OrgResponse(String id, String kind, String name, String status, String role) {

        static OrgResponse of(OrgMembership m) {
            return new OrgResponse(m.orgId(), m.kind().name(), m.orgName(), m.status().name(), m.roleKey());
        }
    }

    @Schema(name = "Me", description = "Profile, roles, effective permissions, organisations and where to land")
    record MeResponse(UUID id, String firstName, String lastName, String email, boolean emailVerified, String phone,
                      @Schema(description = "The phone was confirmed with an SMS code; reset when it changes")
                      boolean phoneVerified, boolean smsConsent, Instant termsAcceptedAt, String principalType, String orgId,
                      List<String> roles, List<String> permissions, List<OrgResponse> orgs,
                      @Schema(allowableValues = {"shop", "seller-onboarding", "seller-center", "provider-onboarding",
                          "provider-hub", "staff-console", "driver-app"}) String landing,
                      boolean profileComplete) {

        static MeResponse of(MeView v) {
            UserAccount u = v.user();
            return new MeResponse(u.id(), u.firstName(), u.lastName(), u.email(), u.emailVerified(), u.phone(),
                    v.phoneVerified(), u.smsConsent(), u.termsAcceptedAt(), v.access().principalType().name(),
                    v.access().orgId().orElse(null), List.copyOf(new TreeSet<>(v.access().roles())),
                    List.copyOf(new TreeSet<>(v.access().permissions())),
                    v.orgs().stream().map(OrgResponse::of).toList(), v.landing().wire(), v.profileComplete());
        }
    }

    @Schema(name = "UpdateProfileRequest", description = "Null fields are left unchanged")
    record UpdateProfileRequest(@Size(max = 80) String firstName, @Size(max = 80) String lastName,
                                @Size(max = 32) String phone, String phoneRegion, Boolean termsAccepted,
                                Boolean smsConsent) {
    }

    @Schema(name = "PhoneCodeSent")
    record PhoneCodeSentResponse(@Schema(description = "Masked phone, e.g. •••• 0142") String sentTo,
                                 Instant expiresAt) {
    }

    @Schema(name = "PhoneVerification")
    record PhoneVerificationResponse(@Schema(description = "E.164") String phone, boolean phoneVerified,
                                     Instant verifiedAt) {
    }

    @Schema(name = "SelectOrgRequest")
    record SelectOrgRequest(@NotBlank @Size(max = 80) String orgId) {
    }
}
