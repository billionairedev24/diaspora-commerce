package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanPricing;
import com.afrotransact.seller.domain.PlanVisibility;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.PlanRepository;
import com.afrotransact.seller.port.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlanServiceImpl implements PlanService {

    private final PlanRepository plans;
    private final SubscriptionRepository subs;
    private final OrganisationRepository orgs;
    private final TrialService trials;
    private final AccessGuard access;
    private final Clock clock;

    PlanServiceImpl(PlanRepository plans, SubscriptionRepository subs, OrganisationRepository orgs,
                    TrialService trials, AccessGuard access, Clock clock) {
        this.plans = plans;
        this.subs = subs;
        this.orgs = orgs;
        this.trials = trials;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanOffer> list(OrgKind kind) {
        return plans.findOpen(kind).stream().filter(p -> p.visibility() == PlanVisibility.PUBLIC)
                .map(p -> offer(p, p.kind())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanOffer> listFor(String orgId) {
        access.orgRoleOrStaff(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING,
                SellerPermissions.BILLING_ADJUST, SellerPermissions.PLANS_MANAGE);
        Organisation org = orgs.findById(orgId).orElseThrow(() -> new NotFoundException("seller.org-not-found",
                "Organisation " + orgId + " not found"));
        Set<String> invited = Set.copyOf(plans.invitedPlans(orgId));
        String current = subs.find(orgId).map(Subscription::planCode).orElse(null);
        List<PlanOffer> out = new ArrayList<>();
        for (Plan p : plans.findOpen(org.kind())) {
            boolean visible = p.visibility() == PlanVisibility.PUBLIC || p.code().equals(current)
                    || (p.visibility() == PlanVisibility.INVITE_ONLY && invited.contains(p.code()));
            if (visible) {
                out.add(offer(p, org.kind()));
            }
        }
        if (current != null && out.stream().noneMatch(o -> o.plan().code().equals(current))) {
            plans.find(current).ifPresent(p -> out.add(offer(p, org.kind())));
        }
        return out;
    }

    /** Prices per cadence and the first-charge sentence with the trial a sign-up gets today (campaigns included). */
    private PlanOffer offer(Plan p, OrgKind kind) {
        LocalDate today = LocalDate.now(clock);
        int trial = trials.decide(p, kind, Instant.now(clock), null, null).days();
        return new PlanOffer(p, Arrays.stream(Cadence.values()).map(c -> PlanPricing.quote(p, c)).toList(),
                Arrays.stream(Cadence.values()).map(c -> PlanPricing.firstCharge(p, c, today, trial)).toList(),
                trial);
    }
}
