package com.afrotransact.seller.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.OrgDirectoryEntry;
import com.afrotransact.seller.port.OrgDirectoryReader;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgMirrorServiceImpl implements OrgMirrorService {

    private final OrgDirectoryReader directory;
    private final OrganisationRepository orgs;
    private final ApplicationRepository apps;
    private final PermissionChecker permissions;

    OrgMirrorServiceImpl(OrgDirectoryReader directory, OrganisationRepository orgs, ApplicationRepository apps,
                         PermissionChecker permissions) {
        this.directory = directory;
        this.orgs = orgs;
        this.apps = apps;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrgEventPayload> page(int page, int size, Set<OrgStatus> statuses) {
        requireCaller();
        if (page < 0 || size < 1 || size > OrgDirectoryService.MAX_PAGE_SIZE) {
            throw new SellerRuleViolation("seller.invalid-input",
                    "page must be ≥ 0 and size 1 to " + OrgDirectoryService.MAX_PAGE_SIZE,
                    Map.of("size", "1 to " + OrgDirectoryService.MAX_PAGE_SIZE));
        }
        Page<OrgDirectoryEntry> ids = directory.page(page, size, statuses == null ? Set.of() : statuses);
        List<OrgEventPayload> items = ids.items().stream().map(e -> orgs.findById(e.id())).flatMap(Optional::stream)
                .map(this::mirror).toList();
        return new Page<>(items, ids.page(), ids.size(), ids.total());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrgEventPayload> one(String orgId) {
        requireCaller();
        return orgs.findById(orgId).map(this::mirror);
    }

    private OrgEventPayload mirror(Organisation org) {
        List<String> owners = orgs.members(org.id()).stream().filter(OrgMember::isOwner).map(OrgMember::userId)
                .toList();
        String applicant = apps.findByOrg(org.id()).filter(Application::isApproved).map(Application::applicantUserId)
                .orElse(null);
        return OrgEventPayload.of(org, owners, applicant, org.updatedAt());
    }

    private void requireCaller() {
        Actor actor = permissions.require(OrgDirectoryService.DIRECTORY_READ);
        if (actor.type() != PrincipalType.SERVICE && actor.type() != PrincipalType.STAFF) {
            throw new ForbiddenActionException("seller.directory-service-only",
                    "The organisation directory is for services and staff");
        }
    }
}
