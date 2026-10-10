package com.afrotransact.identity.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Role-based landing, first match wins:
 * staff → staff console; driver-only (holds {@code delivery:operate}) → driver app; approved seller org → Seller
 * Center; seller applicant or pending seller org → seller onboarding; same two rules for providers; else the shop.
 * Staff email domains (for example {@code @afrotransact.com}) are reserved: those accounts are provisioned by
 * access administrators with platform roles, so they land in the staff console.
 */
public class DefaultLandingPolicy implements LandingPolicy {

    public static final String DRIVER_PERMISSION = "delivery:operate";

    private final Set<String> staffEmailDomains;

    public DefaultLandingPolicy(Set<String> staffEmailDomains) {
        this.staffEmailDomains = Set.copyOf(staffEmailDomains);
    }

    @Override
    public Landing landing(AccessProfile profile, EffectiveAccess access) {
        if (!profile.user().enabled()) {
            return Landing.SHOP;
        }
        if (access.staff()) {
            return Landing.STAFF_CONSOLE;
        }
        if (access.principalType() == PrincipalType.DRIVER && access.can(DRIVER_PERMISSION)) {
            return Landing.DRIVER_APP;
        }
        Landing seller = orgLanding(profile, OrgKind.SELLER, RoleKeys.SELLER_APPLICANT, Landing.SELLER_CENTER,
                Landing.SELLER_ONBOARDING);
        if (seller != null) {
            return seller;
        }
        Landing provider = orgLanding(profile, OrgKind.PROVIDER, RoleKeys.PROVIDER_APPLICANT, Landing.PROVIDER_HUB,
                Landing.PROVIDER_ONBOARDING);
        return provider != null ? provider : Landing.SHOP;
    }

    @Override
    public boolean isStaffEmail(String email) {
        String lower = email == null ? "" : email.toLowerCase(Locale.ROOT);
        return staffEmailDomains.stream().anyMatch(d -> lower.endsWith("@" + d));
    }

    private static Landing orgLanding(AccessProfile profile, OrgKind kind, String applicantRole, Landing approved,
                                      Landing onboarding) {
        boolean hasApproved = profile.memberships().stream().anyMatch(m -> m.kind() == kind && m.approved());
        if (hasApproved) {
            return approved;
        }
        boolean pending = profile.memberships().stream().anyMatch(m -> m.kind() == kind);
        if (pending || profile.holdsRole(applicantRole)) {
            return onboarding;
        }
        return null;
    }
}
