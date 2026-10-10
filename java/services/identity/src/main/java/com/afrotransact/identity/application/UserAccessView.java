package com.afrotransact.identity.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.identity.domain.EffectiveAccess;
import com.afrotransact.identity.domain.OrgMembership;
import com.afrotransact.identity.domain.UserAccount;

/** People tab: a user's roles, direct grants, org memberships and resulting access. */
public record UserAccessView(UserAccount user, Set<String> roles, Set<String> directGrants,
                             List<OrgMembership> memberships, EffectiveAccess effective,
                             Map<String, Set<String>> roleMarkets) {

    public UserAccessView {
        roleMarkets = roleMarkets == null ? Map.of() : Map.copyOf(roleMarkets);
    }

    /** A view without market-scoped roles. */
    public UserAccessView(UserAccount user, Set<String> roles, Set<String> directGrants,
                          List<OrgMembership> memberships, EffectiveAccess effective) {
        this(user, roles, directGrants, memberships, effective, Map.of());
    }
}
