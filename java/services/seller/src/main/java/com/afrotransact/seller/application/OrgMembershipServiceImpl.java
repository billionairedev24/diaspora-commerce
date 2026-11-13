package com.afrotransact.seller.application;

import java.util.Optional;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.port.OrganisationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgMembershipServiceImpl implements OrgMembershipService {

    private final OrganisationRepository orgs;
    private final PermissionChecker permissions;

    OrgMembershipServiceImpl(OrganisationRepository orgs, PermissionChecker permissions) {
        this.orgs = orgs;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> roleOf(String orgId, String userId) {
        Actor actor = permissions.require(MEMBERSHIP_READ);
        if (actor.type() != PrincipalType.SERVICE) {
            throw new ForbiddenActionException("seller.membership-service-only",
                    "Membership lookups are for service clients, not " + actor.type());
        }
        return orgs.findMember(orgId, userId).map(OrgMember::roleKey);
    }
}
