package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.payments.domain.OrgPlan;
import com.afrotransact.payments.port.OrgPlanRepository;
import com.afrotransact.payments.port.SellerPlanDirectory;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgPlanServiceImpl implements OrgPlanService {

    static final String CONSUMER = "payments.seller-subscription";

    private static final Logger LOG = LoggerFactory.getLogger(OrgPlanServiceImpl.class);

    private final InboxDeduplicator inbox;
    private final OrgPlanRepository plans;
    private final SellerPlanDirectory directory;
    private final PaymentsSettings settings;
    private final Clock clock;

    OrgPlanServiceImpl(InboxDeduplicator inbox, OrgPlanRepository plans, SellerPlanDirectory directory,
                       PaymentsSettings settings, Clock clock) {
        this.inbox = inbox;
        this.plans = plans;
        this.directory = directory;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean subscriptionChanged(SubscriptionNotice n) {
        if (!inbox.firstDelivery(CONSUMER, n.eventId())) {
            return false;
        }
        if (n.orgId() == null || n.orgId().isBlank() || n.orgId().equals(settings.retailOrgId())) {
            return false;
        }
        Instant at = n.occurredAt() == null ? Instant.now(clock) : n.occurredAt();
        boolean stored = plans.upsertIfNewer(new OrgPlan(n.orgId().trim(), OrgPlan.effectivePlan(n.planCode(),
                n.status()), n.status(), OrgPlan.EVENT, at));
        if (stored) {
            LOG.info("Plan of {} is now {} ({})", n.orgId(), n.planCode(), n.status());
        }
        return stored;
    }

    @Override
    public Optional<String> planOf(String orgId) {
        if (orgId == null || orgId.isBlank() || orgId.equals(settings.retailOrgId())) {
            return Optional.empty();
        }
        Optional<OrgPlan> known = plans.find(orgId);
        if (known.isPresent()) {
            return Optional.ofNullable(known.get().planCode());
        }
        Optional<SellerPlanDirectory.Plan> fetched = directory.currentPlan(orgId);
        if (fetched.isEmpty()) {
            return Optional.empty();
        }
        SellerPlanDirectory.Plan p = fetched.get();
        // Stored with an old timestamp so any event, even one already on its way, replaces it.
        plans.upsertIfNewer(new OrgPlan(orgId, OrgPlan.effectivePlan(p.planCode(), p.status()), p.status(),
                OrgPlan.API, Instant.EPOCH));
        return Optional.ofNullable(OrgPlan.effectivePlan(p.planCode(), p.status()));
    }

    @Override
    public Map<String, String> plansOf(Collection<String> orgIds) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String org : orgIds) {
            planOf(org).ifPresent(code -> out.put(org, code));
        }
        return out;
    }
}
