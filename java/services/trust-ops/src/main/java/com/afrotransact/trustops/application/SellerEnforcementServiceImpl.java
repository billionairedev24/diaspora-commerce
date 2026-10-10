package com.afrotransact.trustops.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.trustops.domain.SellerEnforcement;
import com.afrotransact.trustops.domain.SellerHealthMetrics;
import com.afrotransact.trustops.domain.SuspensionReason;
import com.afrotransact.trustops.port.SellerEnforcementClient;
import com.afrotransact.trustops.port.SellerEnforcementRepository;
import com.afrotransact.trustops.port.SellerHealthRepository;
import com.afrotransact.trustops.port.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellerEnforcementServiceImpl implements SellerEnforcementService {

    private static final Logger LOG = LoggerFactory.getLogger(SellerEnforcementServiceImpl.class);

    private final SellerEnforcementRepository standings;
    private final SellerHealthRepository health;
    private final SellerEnforcementClient seller;
    private final PerformancePush push;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final TrustOpsSettings settings;
    private final Clock clock;

    SellerEnforcementServiceImpl(SellerEnforcementRepository standings, SellerHealthRepository health,
                                 SellerEnforcementClient seller, PerformancePush push, PermissionChecker permissions,
                                 DomainEventPublisher events, AuditTrail audit, TrustOpsSettings settings,
                                 Clock clock) {
        this.standings = standings;
        this.health = health;
        this.seller = seller;
        this.push = push;
        this.permissions = permissions;
        this.events = events;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SellerEnforcement suspend(String orgId, SuspensionReason reason, String note) {
        Actor actor = permissions.require(TrustOpsPermissions.SELLERS_ENFORCE);
        Instant now = Instant.now(clock);
        SellerEnforcement current = load(orgId, now);
        SellerEnforcement next = current.suspend(reason, note, actor.subject(), now);
        return change(actor, current, next, "trustops.seller.suspended", TrustOpsEvents.SELLER_SUSPENDED, now);
    }

    @Override
    @Transactional
    public SellerEnforcement reinstate(String orgId, String note) {
        Actor actor = permissions.require(TrustOpsPermissions.SELLERS_ENFORCE);
        Instant now = Instant.now(clock);
        SellerEnforcement current = load(orgId, now);
        SellerEnforcement next = current.reinstate(note, actor.subject(), now);
        return change(actor, current, next, "trustops.seller.reinstated", TrustOpsEvents.SELLER_REINSTATED, now);
    }

    @Override
    @Transactional(readOnly = true)
    public SellerEnforcement standing(String orgId) {
        permissions.require(TrustOpsPermissions.ORGS_READ);
        return load(orgId, Instant.now(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SellerEnforcement> suspended() {
        permissions.require(TrustOpsPermissions.ORGS_READ);
        return standings.suspended();
    }

    private SellerEnforcement change(Actor actor, SellerEnforcement before, SellerEnforcement next, String action,
                                     String eventType, Instant now) {
        boolean transition = before.status() != next.status();
        SellerEnforcement applied = applySteps(next, now);
        SellerEnforcement saved = standings.save(applied);
        if (transition) {
            audit.action(actor, action, "ORG", saved.orgId(), saved.orgId(), action.substring(action.lastIndexOf('.')
                    + 1) + ": " + saved.note() + (saved.reason() == null ? "" : " (" + saved.reason() + ")"),
                    details(saved));
            events.publish(eventType, TrustOpsEvents.AGGREGATE, saved.orgId(), details(saved));
        }
        return saved;
    }

    /** Applies the upstream steps still pending; a failing step stays pending and is retried on the next call. */
    private SellerEnforcement applySteps(SellerEnforcement e, Instant now) {
        SellerEnforcement out = e;
        if (out.sellerStepPending()) {
            boolean hold = out.suspended();
            try {
                if (hold) {
                    seller.suspend(out.orgId(), out.reason(), out.note());
                } else {
                    seller.reinstate(out.orgId(), out.note());
                }
                out = out.withSellerSuspended(hold);
            } catch (UpstreamException ex) {
                LOG.warn("Seller {} of {} failed ({}): {}", hold ? "suspension" : "reinstatement", out.orgId(),
                        ex.status(), ex.getMessage());
            }
        }
        if (out.buyBoxStepPending()) {
            SellerHealthMetrics metrics = health.latest(out.orgId()).map(s -> s.report().metrics())
                    .orElseGet(() -> health.metrics(e.orgId(), now.minus(settings.healthWindow()), now));
            if (push.push(out.orgId(), metrics, out.suspended())) {
                out = out.withBuyBoxRestricted(out.suspended());
            }
        }
        return out;
    }

    private SellerEnforcement load(String orgId, Instant now) {
        return standings.find(orgId).orElse(SellerEnforcement.active(orgId, now));
    }

    private static Map<String, Object> details(SellerEnforcement e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orgId", e.orgId());
        m.put("status", e.status().name());
        m.put("reason", e.reason() == null ? null : e.reason().name());
        m.put("note", e.note());
        m.put("actorId", e.actorId());
        m.put("payoutsHeld", e.sellerSuspended());
        m.put("buyBoxRestricted", e.buyBoxRestricted());
        m.put("listingsHidden", e.sellerSuspended());
        m.put("changedAt", e.changedAt().toString());
        return m;
    }
}
