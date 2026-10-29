package com.afrotransact.seller.application;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanEntitlements;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.port.PlanRepository;
import com.afrotransact.seller.port.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EntitlementsServiceImpl implements EntitlementsService {

    /** Every role of an organisation can see what its plan includes. */
    private static final Set<String> MEMBERS = Set.of("owner", "admin", "fulfilment", "catalog", "finance", "support",
            "ads", "marketing");

    private final SubscriptionRepository subs;
    private final PlanRepository plans;
    private final AccessGuard access;
    private final SellerSettings settings;

    EntitlementsServiceImpl(SubscriptionRepository subs, PlanRepository plans, AccessGuard access,
                            SellerSettings settings) {
        this.subs = subs;
        this.plans = plans;
        this.access = access;
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public Entitlements forOrg(String orgId) {
        Actor actor = access.current();
        boolean service = actor.type() == PrincipalType.SERVICE && actor.can(SERVICE_READ);
        if (!service) {
            access.orgRoleOrStaff(orgId, MEMBERS, SellerPermissions.BILLING, SellerPermissions.BILLING_ADJUST,
                    SellerPermissions.PLANS_MANAGE);
        }
        if (settings.isRetail(orgId)) {
            return new Entitlements(orgId, null, null, PlanEntitlements.NONE);
        }
        Optional<Subscription> sub = subs.find(orgId);
        Optional<Plan> plan = sub.flatMap(s -> plans.find(s.planCode()));
        return plan.map(p -> new Entitlements(orgId, p.code(), p.name(), PlanEntitlements.of(p.limits())))
                .orElseGet(() -> new Entitlements(orgId, null, null, PlanEntitlements.NONE));
    }
}
