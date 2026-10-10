package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgServiceImpl implements OrgService {

    private static final Set<String> ANY_MEMBER_ROLE = Set.of("owner", "admin", "finance", "catalog", "fulfil",
            "marketing", "support", "manager", "practitioner");

    private final OrganisationRepository orgs;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final Clock clock;

    OrgServiceImpl(OrganisationRepository orgs, AccessGuard access, DomainEventPublisher events, Clock clock) {
        this.orgs = orgs;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public OrgPublicView publicProfile(String orgId) {
        Organisation o = find(orgId);
        return new OrgPublicView(o.id(), o.kind().name(), o.displayName(), o.status().name());
    }

    @Override
    @Transactional(readOnly = true)
    public OrgView get(String orgId) {
        access.orgRole(orgId, ANY_MEMBER_ROLE, SellerPermissions.APPLICATIONS_REVIEW);
        return new OrgView(find(orgId), orgs.members(orgId));
    }

    @Override
    @Transactional
    public OrgView rename(String orgId, String displayName) {
        access.orgRole(orgId, AccessGuard.OWNER, null);
        String name = displayName == null ? "" : displayName.trim();
        if (name.length() < 2 || name.length() > 80) {
            throw new SellerRuleViolation("seller.invalid-input", "Store name must be 2 to 80 characters",
                    Map.of("displayName", "2 to 80 characters"));
        }
        Organisation org = find(orgId);
        if (!org.isApproved()) {
            throw new SellerStateConflict("seller.org-not-approved",
                    "Change your store name in the application until it is approved");
        }
        if (name.equals(org.displayName())) {
            return new OrgView(org, orgs.members(orgId));
        }
        Instant now = Instant.now(clock);
        Organisation saved = orgs.update(org.withProfile(org.legalName(), name, org.country(), now));
        List<String> owners = orgs.members(orgId).stream().filter(OrgMember::isOwner).map(OrgMember::userId).toList();
        events.publish(SellerEvents.ORG_UPDATED, SellerEvents.AGGREGATE_ORG, orgId,
                OrgEventPayload.of(saved, owners, now));
        return new OrgView(saved, orgs.members(orgId));
    }

    private Organisation find(String orgId) {
        return orgs.findById(orgId).orElseThrow(() -> new NotFoundException("seller.org-not-found",
                "Organisation " + orgId + " not found"));
    }
}
