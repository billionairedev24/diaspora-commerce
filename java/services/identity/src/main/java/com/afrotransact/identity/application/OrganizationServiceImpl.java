package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.identity.domain.OrgIds;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrganizationServiceImpl implements OrganizationService {

    private final PermissionChecker permissions;
    private final OrganizationRepository orgs;
    private final AccessChangeLog changeLog;
    private final Clock clock;

    OrganizationServiceImpl(PermissionChecker permissions, OrganizationRepository orgs, AccessChangeLog changeLog,
                            Clock clock) {
        this.permissions = permissions;
        this.orgs = orgs;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Organization upsert(String orgId, OrgKind kind, String name, OrgStatus status) {
        Actor actor = permissions.require(IdentityPermissions.ORGS_MANAGE);
        OrgIds.requireValid(orgId);
        Organization saved = orgs.upsert(new Organization(orgId, kind, name.trim(), status, null,
                Instant.now(clock)));
        changeLog.append(actor.subject(), "org.upserted", null, null, orgId, kind + " " + status);
        return saved;
    }
}
