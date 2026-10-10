package com.afrotransact.trustops.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.trustops.domain.SellerEnforcement;
import com.afrotransact.trustops.domain.SellerHealthPolicy;
import com.afrotransact.trustops.domain.SellerHealthReport;
import com.afrotransact.trustops.port.SellerEnforcementRepository;
import com.afrotransact.trustops.port.SellerHealthRepository;
import com.afrotransact.trustops.port.ShipmentFact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class SellerHealthServiceImpl implements SellerHealthService {

    private static final Logger LOG = LoggerFactory.getLogger(SellerHealthServiceImpl.class);

    private final SellerHealthRepository health;
    private final SellerEnforcementRepository enforcement;
    private final SellerHealthPolicy policy;
    private final PerformancePush push;
    private final InboxDeduplicator inbox;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final TrustOpsSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    SellerHealthServiceImpl(SellerHealthRepository health, SellerEnforcementRepository enforcement,
                            SellerHealthPolicy policy, PerformancePush push, InboxDeduplicator inbox,
                            PermissionChecker permissions, DomainEventPublisher events, TrustOpsSettings settings,
                            TransactionTemplate tx, Clock clock) {
        this.health = health;
        this.enforcement = enforcement;
        this.policy = policy;
        this.push = push;
        this.inbox = inbox;
        this.permissions = permissions;
        this.events = events;
        this.settings = settings;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void recordShipments(UUID eventId, List<ShipmentFact> facts) {
        if (facts.isEmpty() || !inbox.firstDelivery(TrustOpsEvents.CONSUMER_ORDERS, eventId)) {
            return;
        }
        Instant now = Instant.now(clock);
        facts.forEach(f -> health.apply(f, now));
    }

    @Override
    @Transactional(readOnly = true)
    public SellerHealthView health(String orgId) {
        permissions.require(TrustOpsPermissions.ORGS_READ);
        Instant now = Instant.now(clock);
        SellerHealthReport current = policy.assess(health.metrics(orgId, now.minus(settings.healthWindow()), now),
                now);
        boolean pushed = health.latest(orgId).map(SellerHealthRepository.StoredHealth::pushedToOffers).orElse(false);
        return new SellerHealthView(current, pushed, standing(orgId, now));
    }

    @Override
    @Transactional
    public SellerHealthReport recompute(String orgId) {
        permissions.require(TrustOpsPermissions.SELLERS_ENFORCE);
        return compute(orgId, Instant.now(clock));
    }

    @Override
    public int computeAll() {
        Instant now = Instant.now(clock);
        Set<String> orgs = new LinkedHashSet<>(health.sellersActiveSince(now.minus(settings.healthWindow())));
        enforcement.suspended().forEach(e -> orgs.add(e.orgId()));
        int done = 0;
        for (String org : orgs) {
            try {
                tx.executeWithoutResult(status -> compute(org, now));
                done++;
            } catch (RuntimeException e) {
                LOG.warn("Seller health for {} failed: {}", org, e.getMessage());
            }
        }
        return done;
    }

    private SellerHealthReport compute(String orgId, Instant now) {
        SellerHealthReport report = policy.assess(health.metrics(orgId, now.minus(settings.healthWindow()), now),
                now);
        boolean restricted = standing(orgId, now).suspended();
        boolean pushed = settings.pushToOffers() && push.push(orgId, report.metrics(), restricted);
        health.saveSnapshot(report, pushed);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orgId", orgId);
        payload.put("status", report.status().name());
        payload.put("shipments", report.metrics().shipments());
        payload.put("lateShipmentRate", report.metrics().lateShipmentRate());
        payload.put("cancellationRate", report.metrics().cancellationRate());
        payload.put("defectRate", report.metrics().defectRate());
        payload.put("breaches", report.breaches());
        payload.put("pushedToOffers", pushed);
        payload.put("computedAt", now.toString());
        events.publish(TrustOpsEvents.SELLER_HEALTH_COMPUTED, TrustOpsEvents.AGGREGATE, orgId, payload);
        return report;
    }

    private SellerEnforcement standing(String orgId, Instant now) {
        return enforcement.find(orgId).orElse(SellerEnforcement.active(orgId, now));
    }
}
