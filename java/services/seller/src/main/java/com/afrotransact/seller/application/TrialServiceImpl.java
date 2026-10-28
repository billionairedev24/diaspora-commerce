package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.CampaignInput;
import com.afrotransact.seller.domain.CampaignStatus;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.TrialCampaign;
import com.afrotransact.seller.domain.TrialDecision;
import com.afrotransact.seller.port.TrialCampaignRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TrialServiceImpl implements TrialService {

    private final TrialCampaignRepository campaigns;
    private final AccessGuard access;
    private final Clock clock;

    TrialServiceImpl(TrialCampaignRepository campaigns, AccessGuard access, Clock clock) {
        this.campaigns = campaigns;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrialCampaign> campaigns() {
        access.staffAny(SellerPermissions.PLANS_MANAGE, SellerPermissions.BILLING, SellerPermissions.BILLING_ADJUST);
        return campaigns.list();
    }

    @Override
    @Transactional
    public TrialCampaign create(CampaignInput in) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        in.validate();
        Instant now = Instant.now(clock);
        TrialCampaign c = new TrialCampaign(UUID.randomUUID(), in.name().trim(), in.promoCode(), in.trialDays(),
                in.signupFrom(), in.signupTo(), in.kind(), in.eligiblePlans(), in.maxRedemptions(), 0,
                in.status() == null ? CampaignStatus.ACTIVE : in.status(), actor.subject(), now, actor.subject(), now,
                0);
        try {
            campaigns.insert(c);
        } catch (DuplicateKeyException e) {
            throw duplicateCode();
        }
        return campaigns.find(c.id()).orElseThrow();
    }

    @Override
    @Transactional
    public TrialCampaign update(UUID id, CampaignInput in, long expectedVersion) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        in.validate();
        TrialCampaign old = campaign(id);
        TrialCampaign next = new TrialCampaign(id, in.name().trim(), in.promoCode(), in.trialDays(), in.signupFrom(),
                in.signupTo(), in.kind(), in.eligiblePlans(), in.maxRedemptions(), old.redemptions(),
                in.status() == null ? old.status() : in.status(), old.createdBy(), old.createdAt(), actor.subject(),
                Instant.now(clock), expectedVersion);
        try {
            if (!campaigns.update(next)) {
                throw new ConflictException("seller.concurrent-update", "The campaign changed; reload and retry");
            }
        } catch (DuplicateKeyException e) {
            throw duplicateCode();
        }
        return campaign(id);
    }

    @Override
    @Transactional
    public TrialCampaign setStatus(UUID id, CampaignStatus status) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        TrialCampaign c = campaign(id);
        TrialCampaign next = new TrialCampaign(id, c.name(), c.promoCode(), c.trialDays(), c.signupFrom(),
                c.signupTo(), c.kind(), c.eligiblePlans(), c.maxRedemptions(), c.redemptions(), status,
                c.createdBy(), c.createdAt(), actor.subject(), Instant.now(clock), c.version());
        if (!campaigns.update(next)) {
            throw new ConflictException("seller.concurrent-update", "The campaign changed; reload and retry");
        }
        return campaign(id);
    }

    @Override
    public TrialDecision decide(Plan plan, OrgKind kind, Instant signupAt, String promoCode, Integer overrideDays) {
        Instant at = signupAt == null ? Instant.now(clock) : signupAt;
        List<TrialCampaign> matching = campaigns.active().stream()
                .filter(c -> c.appliesTo(at, kind, plan.code(), promoCode)).toList();
        return TrialDecision.decide(plan, overrideDays, matching);
    }

    @Override
    public boolean redeem(UUID campaignId) {
        return campaigns.redeem(campaignId);
    }

    @Override
    public Optional<TrialCampaign> promo(String code, OrgKind kind, String planCode, Instant at) {
        return campaigns.byPromoCode(code).filter(c -> c.appliesTo(at, kind, planCode, code));
    }

    private TrialCampaign campaign(UUID id) {
        return campaigns.find(id).orElseThrow(() -> new NotFoundException("seller.campaign-not-found",
                "Campaign " + id + " not found"));
    }

    private static SellerRuleViolation duplicateCode() {
        return new SellerRuleViolation("seller.invalid-campaign", "Another campaign uses this promo code",
                Map.of("promoCode", "Already used"));
    }
}
