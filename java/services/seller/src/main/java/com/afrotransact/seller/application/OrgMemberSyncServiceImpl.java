package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgMemberSyncServiceImpl implements OrgMemberSyncService {

    static final String CONSUMER = "seller.identity-roles";

    private static final Logger LOG = LoggerFactory.getLogger(OrgMemberSyncServiceImpl.class);

    private final InboxDeduplicator inbox;
    private final OrganisationRepository orgs;
    private final Clock clock;

    OrgMemberSyncServiceImpl(InboxDeduplicator inbox, OrganisationRepository orgs, Clock clock) {
        this.inbox = inbox;
        this.orgs = orgs;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean granted(RoleChange c) {
        if (!relevant(c) || !inbox.firstDelivery(CONSUMER, c.eventId())) {
            return false;
        }
        Instant at = c.at() == null ? Instant.now(clock) : c.at();
        boolean changed = orgs.syncMember(new OrgMember(c.orgId(), c.userId(), c.roleKey(), at, at));
        LOG.debug("identity.role.granted {} {} {} → changed={}", c.orgId(), c.userId(), c.roleKey(), changed);
        return changed;
    }

    @Override
    @Transactional
    public boolean revoked(RoleChange c) {
        if (!relevant(c) || !inbox.firstDelivery(CONSUMER, c.eventId())) {
            return false;
        }
        Instant at = c.at() == null ? Instant.now(clock) : c.at();
        boolean changed = orgs.removeMember(c.orgId(), c.userId(), c.roleKey(), at);
        LOG.debug("identity.role.revoked {} {} {} → changed={}", c.orgId(), c.userId(), c.roleKey(), changed);
        return changed;
    }

    private boolean relevant(RoleChange c) {
        if (c.orgId() == null || c.orgId().isBlank() || c.userId() == null || !OrgMember.isOrgRole(c.roleKey())) {
            return false;
        }
        if (orgs.findById(c.orgId()).isEmpty()) {
            LOG.info("Ignoring role change for organisation {} unknown to seller-service", c.orgId());
            return false;
        }
        return true;
    }
}
