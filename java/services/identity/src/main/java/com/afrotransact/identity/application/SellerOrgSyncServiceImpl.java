package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.RoleChanged;
import com.afrotransact.identity.domain.MarketCodes;
import com.afrotransact.identity.domain.OrgIds;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellerOrgSyncServiceImpl implements SellerOrgSyncService {

    static final String CONSUMER = "identity.seller-org";
    static final String ACTOR = "seller-service";

    private static final Logger LOG = LoggerFactory.getLogger(SellerOrgSyncServiceImpl.class);

    private final InboxDeduplicator inbox;
    private final OrganizationRepository orgs;
    private final UserAccountRepository users;
    private final AccessChangeLog changeLog;
    private final DomainEventPublisher events;
    private final ApplicationEventPublisher local;
    private final Clock clock;

    SellerOrgSyncServiceImpl(InboxDeduplicator inbox, OrganizationRepository orgs, UserAccountRepository users,
                             AccessChangeLog changeLog, DomainEventPublisher events, ApplicationEventPublisher local,
                             Clock clock) {
        this.inbox = inbox;
        this.orgs = orgs;
        this.users = users;
        this.changeLog = changeLog;
        this.events = events;
        this.local = local;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean approved(SellerOrgChange change) {
        if (!inbox.firstDelivery(CONSUMER, change.eventId())) {
            return false;
        }
        Optional<Organization> org = mirror(change, OrgStatus.APPROVED);
        if (org.isEmpty()) {
            return false;
        }
        String ownerRole = org.get().kind().ownerRole(primaryKind(change.primaryKind()));
        Set<String> members = new LinkedHashSet<>();
        if (change.applicantUserId() != null) {
            members.add(change.applicantUserId());
        }
        members.addAll(change.ownerUserIds());
        for (String member : members) {
            grantOwner(org.get(), member, ownerRole);
        }
        return true;
    }

    @Override
    @Transactional
    public boolean updated(SellerOrgChange change) {
        if (!inbox.firstDelivery(CONSUMER, change.eventId())) {
            return false;
        }
        return mirror(change, OrgStatus.fromSeller(change.status())).isPresent();
    }

    private Optional<Organization> mirror(SellerOrgChange change, OrgStatus status) {
        if (!OrgIds.valid(change.orgId())) {
            LOG.warn("Ignoring seller org event {}: invalid org id {}", change.eventId(), change.orgId());
            return Optional.empty();
        }
        OrgKind kind;
        try {
            kind = OrgKind.valueOf(String.valueOf(change.kind()).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            LOG.warn("Ignoring seller org event {}: unknown kind {}", change.eventId(), change.kind());
            return Optional.empty();
        }
        String name = firstNonBlank(change.displayName(), change.legalName(),
                orgs.findById(change.orgId()).map(Organization::name).orElse(null), change.orgId());
        // S215: identity's market uses content's market codes (ISO 3166-1 alpha-2); unknown values are not stored.
        String market = MarketCodes.normalise(change.country());
        if (market == null && change.country() != null && !change.country().isBlank()) {
            LOG.warn("Organisation {}: country {} is not a market code; market left unchanged", change.orgId(),
                    change.country());
        }
        Instant at = change.occurredAt() == null ? Instant.now(clock) : change.occurredAt();
        Organization saved = orgs.upsert(new Organization(change.orgId(), kind, name, status, market, at));
        changeLog.append(ACTOR, "org.upserted", null, null, change.orgId(), kind + " " + status);
        // In-process only (after commit): local test users waiting for this organisation are linked to it (S276).
        local.publishEvent(new OrganizationMirrored(saved.id()));
        return Optional.of(saved);
    }

    private static OrgKind primaryKind(String raw) {
        return raw != null && "PROVIDER".equalsIgnoreCase(raw.trim()) ? OrgKind.PROVIDER : OrgKind.SELLER;
    }

    private void grantOwner(Organization org, String rawUserId, String roleKey) {
        UUID userId;
        try {
            userId = UUID.fromString(rawUserId);
        } catch (IllegalArgumentException ex) {
            LOG.warn("Organisation {}: owner {} is not an identity user id; skipped", org.id(), rawUserId);
            return;
        }
        Optional<UserAccount> user = users.findById(userId);
        if (user.isEmpty()) {
            LOG.warn("Organisation {}: owner {} has no identity account; skipped", org.id(), userId);
            return;
        }
        Instant now = Instant.now(clock);
        if (orgs.setMember(org.id(), userId, roleKey)) {
            changeLog.append(ACTOR, "org.member.set", userId, roleKey, org.id(), org.name());
            events.publish(IdentityEvents.ROLE_GRANTED, IdentityEvents.AGGREGATE, userId.toString(),
                    new RoleChanged(userId.toString(), roleKey, org.id(), ACTOR, "seller.org.approved", now,
                            org.market()));
        }
        if (user.get().activeOrgId() == null) {
            users.update(user.get().withActiveOrg(org.id(), now));
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}
