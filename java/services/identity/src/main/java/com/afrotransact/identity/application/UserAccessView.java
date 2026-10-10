package com.afrotransact.identity.application;

import java.util.List;
import java.util.Set;

import com.afrotransact.identity.domain.EffectiveAccess;
import com.afrotransact.identity.domain.OrgMembership;
import com.afrotransact.identity.domain.UserAccount;

/** People tab: a user's roles, direct grants, org memberships and resulting access. */
public record UserAccessView(UserAccount user, Set<String> roles, Set<String> directGrants,
                             List<OrgMembership> memberships, EffectiveAccess effective) {
}
