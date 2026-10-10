package com.afrotransact.trustops.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.trustops.domain.Claim;
import com.afrotransact.trustops.domain.ClaimDraft;
import com.afrotransact.trustops.domain.ClaimOutcome;
import com.afrotransact.trustops.domain.ClaimStatus;
import com.afrotransact.trustops.domain.PartyRole;
import com.afrotransact.trustops.domain.SlaPolicy;
import com.afrotransact.trustops.domain.TrustRuleViolation;
import com.afrotransact.trustops.port.ClaimHistoryEntry;
import com.afrotransact.trustops.port.ClaimQuery;
import com.afrotransact.trustops.port.ClaimRepository;
import com.afrotransact.trustops.port.Page;
import com.afrotransact.trustops.port.PaymentsClient;
import com.afrotransact.trustops.port.PaymentsClient.RefundLine;
import com.afrotransact.trustops.port.PaymentsClient.RefundResult;
import com.afrotransact.trustops.port.UpstreamException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ClaimServiceImpl implements ClaimService {

    private final ClaimRepository claims;
    private final PaymentsClient payments;
    private final SlaPolicy sla;
    private final PermissionChecker permissions;
    private final ActorResolver actors;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final Clock clock;

    ClaimServiceImpl(ClaimRepository claims, PaymentsClient payments, SlaPolicy sla, PermissionChecker permissions,
                     ActorResolver actors, DomainEventPublisher events, AuditTrail audit, Clock clock) {
        this.claims = claims;
        this.payments = payments;
        this.sla = sla;
        this.permissions = permissions;
        this.actors = actors;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ClaimView open(OpenClaimCommand c) {
        Actor actor = actors.current();
        String customer;
        if (actor.type() == PrincipalType.CUSTOMER) {
            customer = actor.subject();
        } else if (staff(actor) && actor.can(TrustOpsPermissions.DISPUTES)) {
            customer = c.customerId();
        } else {
            throw new ForbiddenActionException("trustops.customers-only", "Only customers (or staff on their behalf) "
                    + "open claims");
        }
        Instant now = Instant.now(clock);
        Claim opened = claims.insert(Claim.open(UUID.randomUUID(), claims.nextReference(), new ClaimDraft(c.type(),
                c.orderId(), c.orderNumber(), c.shipmentId(), c.sellerOrgId(), customer, c.amountMinor(),
                c.currency(), c.description(), c.evidenceAssetIds()), now, sla));
        history(opened, actor, "OPENED", null, opened.description());
        publish(TrustOpsEvents.CLAIM_OPENED, opened);
        return ClaimView.of(opened, now);
    }

    @Override
    @Transactional(readOnly = true)
    public ClaimView get(UUID id) {
        Claim c = find(id);
        party(actors.current(), c);
        return ClaimView.of(c, Instant.now(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClaimHistoryEntry> history(UUID id) {
        party(actors.current(), find(id));
        return claims.history(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ClaimView> mine(int page, int size) {
        Actor actor = actors.current();
        Instant now = Instant.now(clock);
        return claims.search(new ClaimQuery(null, null, actor.subject(), null, false), page, size)
                .map(c -> ClaimView.of(c, now));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ClaimView> forSeller(String sellerOrgId, ClaimStatus status, int page, int size) {
        permissions.requireForOrg(TrustOpsPermissions.SUPPORT, sellerOrgId);
        Instant now = Instant.now(clock);
        return claims.search(new ClaimQuery(status, sellerOrgId, null, null, false), page, size)
                .map(c -> ClaimView.of(c, now));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ClaimView> queue(ClaimStatus status, String sellerOrgId, String orderId, int page, int size) {
        staffOnly(permissions.require(TrustOpsPermissions.DISPUTES));
        Instant now = Instant.now(clock);
        return claims.search(new ClaimQuery(status, sellerOrgId, null, orderId, status == null), page, size)
                .map(c -> ClaimView.of(c, now));
    }

    @Override
    @Transactional
    public ClaimView addEvidence(UUID id, String assetId, String note) {
        Actor actor = actors.current();
        Claim c = find(id);
        PartyRole role = party(actor, c);
        Instant now = Instant.now(clock);
        Claim saved = claims.update(c.addEvidence(role, actor.subject(), assetId, note, now));
        history(saved, actor, "EVIDENCE_ADDED", c.status(), role.name() + " added " + assetId);
        return ClaimView.of(saved, now);
    }

    @Override
    @Transactional
    public ClaimView sellerRespond(UUID id, String message, List<String> evidenceAssetIds) {
        Claim c = find(id);
        Actor actor = permissions.requireForOrg(TrustOpsPermissions.SUPPORT, c.sellerOrgId());
        Instant now = Instant.now(clock);
        Claim saved = claims.update(c.sellerRespond(actor.subject(), message, evidenceAssetIds, now, sla));
        history(saved, actor, "SELLER_RESPONDED", c.status(), saved.sellerResponse());
        publish(TrustOpsEvents.CLAIM_SELLER_RESPONDED, saved);
        return ClaimView.of(saved, now);
    }

    @Override
    @Transactional
    public ClaimView escalate(UUID id, String reason) {
        Actor actor = staffOnly(permissions.require(TrustOpsPermissions.DISPUTES));
        Claim c = find(id);
        Instant now = Instant.now(clock);
        Claim saved = claims.update(c.escalate(reason, now, sla));
        history(saved, actor, "ESCALATED", c.status(), saved.escalationReason());
        audit.action(actor, "trustops.claim.escalated", "CLAIM", saved.reference(), saved.sellerOrgId(),
                "Escalated: " + saved.escalationReason(), TrustOpsPayloads.claim(saved));
        publish(TrustOpsEvents.CLAIM_ESCALATED, saved);
        return ClaimView.of(saved, now);
    }

    @Override
    @Transactional
    public ClaimView withdraw(UUID id) {
        Actor actor = actors.current();
        Claim c = find(id);
        if (!actor.subject().equals(c.customerId())) {
            throw new ForbiddenActionException("trustops.not-your-claim", "Only the customer can withdraw a claim");
        }
        Instant now = Instant.now(clock);
        Claim saved = claims.update(c.withdraw(actor.subject(), now));
        history(saved, actor, "WITHDRAWN", c.status(), null);
        publish(TrustOpsEvents.CLAIM_WITHDRAWN, saved);
        return ClaimView.of(saved, now);
    }

    @Override
    @Transactional
    public ClaimView decide(UUID id, DecideClaimCommand cmd) {
        Actor actor = staffOnly(permissions.require(TrustOpsPermissions.DISPUTES));
        Claim c = find(id);
        if (!c.status().decidable()) {
            throw new IllegalStateException("Claim " + c.reference() + " cannot be decided while " + c.status());
        }
        if (cmd.outcome() == null) {
            throw new TrustRuleViolation("trustops.outcome-required", "Choose refund or deny",
                    Map.of("outcome", "Required"));
        }
        Instant now = Instant.now(clock);
        Claim decided;
        if (cmd.outcome() == ClaimOutcome.REFUND) {
            List<RefundLine> lines = cmd.refundLines();
            if (cmd.paymentId() == null || lines.isEmpty()) {
                throw new TrustRuleViolation("trustops.refund-lines-required", "A refund needs the payment id and "
                        + "at least one order line", Map.of("paymentId", "Required", "refundLines", "Required"));
            }
            long total = lines.stream().mapToLong(RefundLine::total).sum();
            c.requireRefundAmount(total);
            RefundResult refund = refund(c, cmd.paymentId(), lines, cmd.note());
            decided = c.decideRefund(actor.subject(), refund.amountMinor() > 0 ? refund.amountMinor() : total,
                    refund.refundId(), cmd.note(), now);
        } else {
            decided = c.deny(actor.subject(), cmd.note(), now);
        }
        Claim saved = claims.update(decided);
        history(saved, actor, "DECIDED", c.status(), saved.decisionNote());
        audit.action(actor, "trustops.claim.decided", "CLAIM", saved.reference(), saved.sellerOrgId(),
                "Claim " + saved.reference() + " " + saved.status() + (saved.refundMinor() == null ? ""
                        : " " + saved.refundMinor() + " " + saved.currency()), TrustOpsPayloads.claim(saved));
        publish(TrustOpsEvents.CLAIM_DECIDED, saved);
        return ClaimView.of(saved, now);
    }

    private RefundResult refund(Claim c, UUID paymentId, List<RefundLine> lines, String note) {
        String reason = "A-to-z claim " + c.reference() + (note == null || note.isBlank() ? "" : ": " + note);
        try {
            return payments.refund(paymentId, lines, reason.length() > 500 ? reason.substring(0, 500) : reason,
                    "trustops-claim-" + c.id());
        } catch (UpstreamException e) {
            if (e.rejected() || e.status() == 404 || e.status() == 400) {
                throw new ConflictException("trustops.refund-refused", "Payments refused the refund: "
                        + e.getMessage());
            }
            throw e;
        }
    }

    /** Which party the actor is on this claim; throws when none. */
    private static PartyRole party(Actor actor, Claim c) {
        if (staff(actor) && actor.can(TrustOpsPermissions.DISPUTES)) {
            return PartyRole.STAFF;
        }
        if (actor.subject().equals(c.customerId())) {
            return PartyRole.CUSTOMER;
        }
        if (actor.orgId().map(c.sellerOrgId()::equals).orElse(false)) {
            return PartyRole.SELLER;
        }
        throw new ForbiddenActionException("trustops.not-a-party", "Only the customer, the seller or staff can see "
                + "this claim");
    }

    private static boolean staff(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.permissions().contains("*");
    }

    private static Actor staffOnly(Actor actor) {
        if (!staff(actor)) {
            throw new ForbiddenActionException("trustops.staff-only", "Only staff decide and escalate claims");
        }
        return actor;
    }

    private Claim find(UUID id) {
        return claims.findById(id).orElseThrow(() -> new NotFoundException("trustops.claim-not-found",
                "Claim not found"));
    }

    private void history(Claim c, Actor actor, String action, ClaimStatus from, String note) {
        claims.appendHistory(c.id(), new ClaimHistoryEntry(c.updatedAt(), actor.subject(), action, from, c.status(),
                note));
    }

    private void publish(String type, Claim c) {
        events.publish(type, TrustOpsEvents.AGGREGATE, c.id().toString(), TrustOpsPayloads.claim(c));
    }
}
