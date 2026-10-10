package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.identity.application.MeView;
import com.afrotransact.identity.domain.OrgMembership;
import com.afrotransact.identity.domain.UserAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Profile, landing and org selection bodies. */
final class MeDtos {

    private MeDtos() {
    }

    @Schema(name = "Org")
    record OrgResponse(UUID id, String kind, String name, String status, String role) {

        static OrgResponse of(OrgMembership m) {
            return new OrgResponse(m.orgId(), m.kind().name(), m.orgName(), m.status().name(), m.roleKey());
        }
    }

    @Schema(name = "Me", description = "Profile, roles, effective permissions, organisations and where to land")
    record MeResponse(UUID id, String firstName, String lastName, String email, boolean emailVerified, String phone,
                      boolean smsConsent, Instant termsAcceptedAt, String principalType, UUID orgId,
                      List<String> roles, List<String> permissions, List<OrgResponse> orgs,
                      @Schema(allowableValues = {"shop", "seller-onboarding", "seller-center", "provider-onboarding",
                          "provider-hub", "staff-console", "driver-app"}) String landing,
                      boolean profileComplete) {

        static MeResponse of(MeView v) {
            UserAccount u = v.user();
            return new MeResponse(u.id(), u.firstName(), u.lastName(), u.email(), u.emailVerified(), u.phone(),
                    u.smsConsent(), u.termsAcceptedAt(), v.access().principalType().name(),
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

    @Schema(name = "SelectOrgRequest")
    record SelectOrgRequest(@NotNull UUID orgId) {
    }
}
