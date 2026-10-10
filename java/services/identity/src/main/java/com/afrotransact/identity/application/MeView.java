package com.afrotransact.identity.application;

import java.util.List;

import com.afrotransact.identity.domain.EffectiveAccess;
import com.afrotransact.identity.domain.Landing;
import com.afrotransact.identity.domain.OrgMembership;
import com.afrotransact.identity.domain.UserAccount;

/**
 * The signed-in user's profile, access and landing.
 *
 * @param phoneVerified the account's current phone number was confirmed with an SMS code
 */
public record MeView(UserAccount user, EffectiveAccess access, List<OrgMembership> orgs, Landing landing,
                     boolean profileComplete, boolean phoneVerified) {
}
