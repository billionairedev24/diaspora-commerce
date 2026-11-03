package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
            grantOwner(org.get(), member, ownerRole, "seller.org.approved");
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

    @Override
    @Transactional
    public boolean reconcile(SellerOrgChange truth) {
        if (!OrgIds.valid(truth.orgId())) {
            return false;
        }
        OrgStatus status = OrgStatus.fromSeller(truth.status());
        Optional<Organization> before = orgs.findById(truth.orgId());
        boolean repaired = false;
        Optional<Organization> org = before;
        if (before.isEmpty() || differs(before.get(), truth, status)) {
            org = mirror(truth, status);
            repaired = org.isPresent() && !org.equals(before);
        }
        if (org.isEmpty() || (status != OrgStatus.APPROVED && status != OrgStatus.SUSPENDED)) {
            return repaired;
        }
        String ownerRole = org.get().kind().ownerRole(primaryKind(truth.primaryKind()));
        Set<UUID> members = new LinkedHashSet<>();
        orgs.members(org.get().id()).forEach(m -> members.add(m.userId()));
        Set<String> owners = new LinkedHashSet<>(truth.ownerUserIds());
        // The approved applicant joins as owner when identity never added them; a member whose role the team changed
        // since keeps it (the seller service's owner list is the truth for owners).
        if (truth.applicantUserId() != null && !members.contains(uuidOrNull(truth.applicantUserId()))) {
            owners.add(truth.applicantUserId());
        }
        for (String owner : owners) {
            repaired |= grantOwner(org.get(), owner, ownerRole, "seller.org.reconciled");
        }
        if (repaired) {
            LOG.info("Organisation {} repaired from the seller service ({} {})", truth.orgId(), org.get().kind(),
                    org.get().status());
        }
        return repaired;
    }

    private static boolean differs(Organization org, SellerOrgChange truth, OrgStatus status) {
        String kind = String.valueOf(truth.kind()).trim().toUpperCase(Locale.ROOT);
        String name = firstNonBlank(truth.displayName(), truth.legalName(), org.name());
        return org.status() != status || !org.kind().name().equals(kind) || !org.name().equals(name);
    }

    private static UUID uuidOrNull(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
        Optional<Organization> existing = orgs.findById(change.orgId());
        if (existing.isPresent() && change.occurredAt() != null && existing.get().updatedAt() != null
                && existing.get().updatedAt().isAfter(change.occurredAt().plus(1, ChronoUnit.MILLIS))) {
            // Out of order (a redelivered or late event, or a reconcile racing a newer event): never roll back. One
            // millisecond of tolerance: stored timestamps are rounded to microseconds.
            LOG.info("Organisation {}: {} from {} is older than the copy from {}; kept", change.orgId(),
                    change.eventId() == null ? "seller state" : "event " + change.eventId(), change.occurredAt(),
                    existing.get().updatedAt());
            return existing;
        }
        String name = firstNonBlank(change.displayName(), change.legalName(),
                existing.map(Organization::name).orElse(null), change.orgId());
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

    /** @return true when the membership or the person's active organisation changed */
    private boolean grantOwner(Organization org, String rawUserId, String roleKey, String reason) {
        UUID userId = uuidOrNull(rawUserId);
        if (userId == null) {
            LOG.warn("Organisation {}: owner {} is not an identity user id; skipped", org.id(), rawUserId);
            return false;
        }
        Optional<UserAccount> user = users.findById(userId);
        if (user.isEmpty()) {
            LOG.warn("Organisation {}: owner {} has no identity account; skipped", org.id(), userId);
            return false;
        }
        Instant now = Instant.now(clock);
        boolean changed = false;
        if (orgs.setMember(org.id(), userId, roleKey)) {
            changed = true;
            changeLog.append(ACTOR, "org.member.set", userId, roleKey, org.id(), org.name());
            events.publish(IdentityEvents.ROLE_GRANTED, IdentityEvents.AGGREGATE, userId.toString(),
                    new RoleChanged(userId.toString(), roleKey, org.id(), ACTOR, reason, now, org.market()));
        }
        if (user.get().activeOrgId() == null) {
            users.update(user.get().withActiveOrg(org.id(), now));
            changed = true;
        }
        return changed;
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
