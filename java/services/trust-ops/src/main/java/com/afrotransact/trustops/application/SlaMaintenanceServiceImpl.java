package com.afrotransact.trustops.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.trustops.domain.Claim;
import com.afrotransact.trustops.domain.ClaimStatus;
import com.afrotransact.trustops.domain.ModerationItem;
import com.afrotransact.trustops.domain.SlaPolicy;
import com.afrotransact.trustops.port.ClaimHistoryEntry;
import com.afrotransact.trustops.port.ClaimRepository;
import com.afrotransact.trustops.port.ModerationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SlaMaintenanceServiceImpl implements SlaMaintenanceService {

    private static final int BATCH = 200;

    private final ModerationRepository moderation;
    private final ClaimRepository claims;
    private final SlaPolicy sla;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final Clock clock;

    SlaMaintenanceServiceImpl(ModerationRepository moderation, ClaimRepository claims, SlaPolicy sla,
                              DomainEventPublisher events, AuditTrail audit, Clock clock) {
        this.moderation = moderation;
        this.claims = claims;
        this.sla = sla;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SlaSweepResult sweep() {
        Instant now = Instant.now(clock);
        int listings = 0;
        for (ModerationItem item : moderation.findBreachedPending(now, BATCH)) {
            ModerationItem saved = moderation.update(item.escalate("SLA breached: no decision by "
                    + item.sla().dueAt(), now, sla));
            audit.action(AuditTrail.SYSTEM, "trustops.listing.escalated", "LISTING",
                    saved.product().productId().toString(), saved.product().sellerOrgId(),
                    saved.escalationReason(), Map.of("itemId", saved.id().toString()));
            events.publish(TrustOpsEvents.MODERATION_ESCALATED, TrustOpsEvents.AGGREGATE,
                    saved.product().productId().toString(), Map.of("itemId", saved.id().toString(),
                            "productId", saved.product().productId().toString(), "reason", saved.escalationReason(),
                            "dueAt", saved.sla().dueAt().toString()));
            listings++;
        }
        int lapsed = 0;
        for (Claim c : claims.findBreached(ClaimStatus.AWAITING_SELLER, now, BATCH)) {
            Claim saved = claims.update(c.sellerWindowLapsed(now, sla));
            claims.appendHistory(saved.id(), new ClaimHistoryEntry(now, AuditTrail.SYSTEM.subject(),
                    "SELLER_WINDOW_LAPSED", c.status(), saved.status(), "No seller response by " + c.sla().dueAt()));
            lapsed++;
        }
        int escalated = 0;
        for (Claim c : claims.findBreached(ClaimStatus.UNDER_REVIEW, now, BATCH)) {
            Claim saved = claims.update(c.escalate("Decision SLA breached (due " + c.sla().dueAt() + ")", now, sla));
            claims.appendHistory(saved.id(), new ClaimHistoryEntry(now, AuditTrail.SYSTEM.subject(), "ESCALATED",
                    c.status(), saved.status(), saved.escalationReason()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("claimId", saved.id().toString());
            payload.put("reference", saved.reference());
            payload.put("reason", saved.escalationReason());
            payload.put("dueAt", saved.sla().dueAt().toString());
            audit.action(AuditTrail.SYSTEM, "trustops.claim.escalated", "CLAIM", saved.reference(),
                    saved.sellerOrgId(), saved.escalationReason(), payload);
            events.publish(TrustOpsEvents.CLAIM_ESCALATED, TrustOpsEvents.AGGREGATE, saved.id().toString(), payload);
            escalated++;
        }
        return new SlaSweepResult(listings, lapsed, escalated);
    }
}
