package com.afrotransact.identity.application;

import java.util.UUID;

import com.afrotransact.identity.domain.OrgKind;

/** The signed-in user's own profile, landing and applicant roles. */
public interface ProfileService {

    MeView me();

    /** Completes or edits the profile (social sign-ups add phone and accept terms here). */
    MeView update(UpdateProfileCommand command);

    /**
     * "Start selling" / "offer services" for an existing account: adds the applicant role to the same account
     * (audited), no second sign-up. Idempotent.
     */
    MeView becomeApplicant(OrgKind kind);

    /** Chooses which organisation the user acts for (token {@code org} claim). */
    MeView selectOrg(UUID orgId);
}
