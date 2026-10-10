package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.ComplianceHistoryEntry;
import com.afrotransact.seller.domain.ConnectAccount;
import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.OrgSuspension;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.SuspensionReason;
import com.afrotransact.seller.port.ConnectAccountRepository;
import com.afrotransact.seller.port.OrgSuspensionRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellerEnforcementServiceImpl implements SellerEnforcementService {

    private static final int HISTORY = 20;
    private static final String SOURCE = "TRUST_SAFETY";

    private final OrganisationRepository orgs;
    private final OrgSuspensionRepository suspensions;
    private final ConnectAccountRepository accounts;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final Clock clock;

    SellerEnforcementServiceImpl(OrganisationRepository orgs, OrgSuspensionRepository suspensions,
                                 ConnectAccountRepository accounts, AccessGuard access, DomainEventPublisher events,
                                 Clock clock) {
        this.orgs = orgs;
        this.suspensions = suspensions;
        this.accounts = accounts;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public EnforcementView suspend(String orgId, SuspensionReason reason, String note) {
        Actor actor = access.staff(SellerPermissions.SELLERS_ENFORCE);
        Organisation org = orgs.lock(orgId).orElseThrow(() -> notFound(orgId));
        // Lock the connected account too, so a concurrent Stripe webhook cannot interleave its own hold / release.
        Optional<ConnectAccount> account = lockedAccount(orgId);
        if (suspensions.findActive(orgId).isPresent()) {
            return view(orgId);
        }
        Instant now = Instant.now(clock);
        OrgSuspension s = suspensions.insert(OrgSuspension.open(UUID.randomUUID(), org, reason, note,
                actor.subject(), now));
        Organisation saved = orgs.update(org.withStatus(OrgStatus.SUSPENDED, now));
        String accountId = account.map(ConnectAccount::accountId).orElse(null);
        EnforcementPayload payload = new EnforcementPayload(orgId, accountId, SellerEvents.REASON_SUSPENDED,
                actor.subject(), s.summary(), List.of(), now);
        events.publish(SellerEvents.LISTINGS_HIDDEN, SellerEvents.AGGREGATE_COMPLIANCE, orgId, payload);
        events.publish(SellerEvents.PAYOUTS_PAUSED, SellerEvents.AGGREGATE_COMPLIANCE, orgId, payload);
        events.publish(SellerEvents.ORG_UPDATED, SellerEvents.AGGREGATE_ORG, orgId,
                OrgEventPayload.of(saved, owners(orgId), now));
        account.ifPresent(a -> history(a, "org.suspended", "Suspended by Trust and Safety " + s.summary()
                + ": payouts paused, listings hidden", actor.subject(), now));
        return view(orgId);
    }

    @Override
    @Transactional
    public EnforcementView reinstate(String orgId, String note) {
        Actor actor = access.staff(SellerPermissions.SELLERS_ENFORCE);
        Organisation org = orgs.lock(orgId).orElseThrow(() -> notFound(orgId));
        Optional<ConnectAccount> account = lockedAccount(orgId);
        Optional<OrgSuspension> active = suspensions.findActive(orgId);
        if (active.isEmpty()) {
            return view(orgId);
        }
        Instant now = Instant.now(clock);
        OrgSuspension lifted = suspensions.update(active.get().reinstate(actor.subject(), note, now));
        Organisation saved = org.status() == OrgStatus.SUSPENDED
                ? orgs.update(org.withStatus(lifted.previousStatus(), now)) : org;
        String accountId = account.map(ConnectAccount::accountId).orElse(null);
        EnforcementPayload payload = new EnforcementPayload(orgId, accountId, SellerEvents.REASON_REINSTATED,
                actor.subject(), lifted.reinstateNote(), List.of(), now);
        // Only the suspension hold is released: a compliance hold on the connected account keeps its effect.
        boolean payoutsHeld = account.map(ConnectAccount::payoutsPaused).orElse(false);
        boolean listingsHidden = account.map(ConnectAccount::listingsHidden).orElse(false);
        if (!payoutsHeld) {
            events.publish(SellerEvents.PAYOUTS_RESUMED, SellerEvents.AGGREGATE_COMPLIANCE, orgId, payload);
        }
        if (!listingsHidden) {
            events.publish(SellerEvents.LISTINGS_RESTORED, SellerEvents.AGGREGATE_COMPLIANCE, orgId, payload);
        }
        events.publish(SellerEvents.ORG_UPDATED, SellerEvents.AGGREGATE_ORG, orgId,
                OrgEventPayload.of(saved, owners(orgId), now));
        account.ifPresent(a -> history(a, "org.reinstated", reinstateSummary(a), actor.subject(), now));
        return view(orgId);
    }

    @Override
    @Transactional(readOnly = true)
    public EnforcementView status(String orgId) {
        access.staffAny(SellerPermissions.SELLERS_ENFORCE, SellerPermissions.COMPLIANCE_HOLD,
                SellerPermissions.COMPLIANCE_REMIND);
        return view(orgId);
    }

    private EnforcementView view(String orgId) {
        Organisation org = orgs.findById(orgId).orElseThrow(() -> notFound(orgId));
        return new EnforcementView(org, suspensions.findActive(orgId).orElse(null),
                accounts.findByOrg(orgId).orElse(null), suspensions.history(orgId, HISTORY));
    }

    private Optional<ConnectAccount> lockedAccount(String orgId) {
        return accounts.findByOrg(orgId).flatMap(a -> accounts.lock(a.accountId()));
    }

    private List<String> owners(String orgId) {
        return orgs.members(orgId).stream().filter(OrgMember::isOwner).map(OrgMember::userId).toList();
    }

    private void history(ConnectAccount a, String type, String summary, String actorId, Instant now) {
        accounts.appendHistory(new ComplianceHistoryEntry(UUID.randomUUID(), a.accountId(), SOURCE, null, type,
                a.status(), summary, a.requirements(), actorId, now));
    }

    private static String reinstateSummary(ConnectAccount a) {
        List<String> kept = new ArrayList<>();
        if (a.payoutsPaused()) {
            kept.add("payouts stay paused (" + (a.holdReason() == null ? "compliance hold" : a.holdReason()) + ")");
        }
        if (a.listingsHidden()) {
            kept.add("listings stay hidden (Stripe charges disabled)");
        }
        return "Reinstated by Trust and Safety"
                + (kept.isEmpty() ? ": payouts resumed, listings restored" : "; " + String.join(", ", kept));
    }

    private static NotFoundException notFound(String orgId) {
        return new NotFoundException("seller.org-not-found", "Organisation " + orgId + " not found");
    }
}
