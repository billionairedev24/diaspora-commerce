package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.domain.GrantSource;
import com.afrotransact.seller.domain.GrantStatus;
import com.afrotransact.seller.domain.GrantType;
import com.afrotransact.seller.domain.RewardEvaluator;
import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.domain.RewardProgress;
import com.afrotransact.seller.domain.RewardRule;
import com.afrotransact.seller.domain.RewardRuleInput;
import com.afrotransact.seller.domain.RewardRuleStatus;
import com.afrotransact.seller.domain.RewardWindow;
import com.afrotransact.seller.domain.SellerFacts;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.RewardRuleRepository;
import com.afrotransact.seller.port.SellerMetricRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class RewardServiceImpl implements RewardService {

    private static final Logger LOG = LoggerFactory.getLogger(RewardServiceImpl.class);
    private static final int PREVIEW_ROWS = 500;

    private final RewardRuleRepository rules;
    private final BillingGrantRepository grants;
    private final SellerMetricRepository metrics;
    private final OrganisationRepository orgs;
    private final BillingAdjustmentService adjustments;
    private final AccessGuard access;
    private final TransactionOperations tx;
    private final SellerSettings settings;
    private final Clock clock;

    RewardServiceImpl(RewardRuleRepository rules, BillingGrantRepository grants, SellerMetricRepository metrics,
                      OrganisationRepository orgs, BillingAdjustmentService adjustments, AccessGuard access,
                      TransactionOperations tx, SellerSettings settings, Clock clock) {
        this.rules = rules;
        this.grants = grants;
        this.metrics = metrics;
        this.orgs = orgs;
        this.adjustments = adjustments;
        this.access = access;
        this.tx = tx;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public List<RewardRule> rules() {
        readAccess();
        return tx.execute(s -> rules.list());
    }

    @Override
    public RewardRule rule(UUID id) {
        readAccess();
        return find(id);
    }

    @Override
    public RewardRule create(RewardRuleInput in) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        in.validate();
        Instant now = Instant.now(clock);
        RewardRule r = build(UUID.randomUUID(), in, in.status() == null ? RewardRuleStatus.DRAFT : in.status(), 0,
                actor.subject(), now, actor.subject(), now, 0);
        tx.executeWithoutResult(s -> rules.insert(r));
        return find(r.id());
    }

    @Override
    public RewardRule update(UUID id, RewardRuleInput in, long expectedVersion) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        in.validate();
        RewardRule old = find(id);
        RewardRule next = build(id, in, in.status() == null ? old.status() : in.status(), old.grantsCount(),
                old.createdBy(), old.createdAt(), actor.subject(), Instant.now(clock), expectedVersion);
        save(next);
        return find(id);
    }

    @Override
    public RewardRule setStatus(UUID id, RewardRuleStatus status) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        RewardRule old = find(id);
        save(old.withStatus(status, actor.subject(), Instant.now(clock)));
        return find(id);
    }

    @Override
    public RewardPreview preview(RewardRuleInput in, UUID ruleId) {
        readAccess();
        in.validate();
        Instant now = Instant.now(clock);
        RewardRule rule = build(ruleId == null ? UUID.randomUUID() : ruleId, in, RewardRuleStatus.ACTIVE, 0, "preview",
                now, "preview", now, 0);
        return tx.execute(s -> {
            Set<String> granted = ruleId == null ? Set.of() : grants.grantedOrgs(ruleId);
            List<RewardPreview.Row> rows = new ArrayList<>();
            int qualifying = 0;
            List<SellerFacts> all = metrics.allFacts().stream().filter(f -> !settings.isRetail(f.orgId())).toList();
            for (SellerFacts f : all) {
                RewardWindow w = window(rule, f, now);
                long value = value(rule.metric(), f, w, now);
                boolean already = granted.contains(f.orgId());
                RewardProgress p = RewardEvaluator.evaluate(rule, f, value, w, already, now);
                qualifying += p.qualifies() ? 1 : 0;
                rows.add(new RewardPreview.Row(f.orgId(), orgName(f.orgId()), f.planCode(), value, rule.threshold(),
                        p.percent(), p.qualifies(), already, p.reason()));
            }
            rows.sort(Comparator.comparing(RewardPreview.Row::qualifies).reversed()
                    .thenComparing(Comparator.comparingLong(RewardPreview.Row::value).reversed())
                    .thenComparing(RewardPreview.Row::orgId));
            return new RewardPreview(all.size(), qualifying, rows.size() > PREVIEW_ROWS
                    ? rows.subList(0, PREVIEW_ROWS) : rows);
        });
    }

    @Override
    public List<RewardProgress> progress(String orgId) {
        access.orgRoleOrStaff(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING,
                SellerPermissions.BILLING_ADJUST, SellerPermissions.PLANS_MANAGE);
        Instant now = Instant.now(clock);
        return tx.execute(s -> {
            Optional<SellerFacts> facts = metrics.facts(orgId);
            if (facts.isEmpty() || settings.isRetail(orgId)) {
                return List.of();
            }
            Set<UUID> granted = grants.grantedRules(orgId);
            List<RewardProgress> out = new ArrayList<>();
            for (RewardRule r : rules.active(null)) {
                RewardWindow w = window(r, facts.get(), now);
                RewardProgress p = RewardEvaluator.evaluate(r, facts.get(), value(r.metric(), facts.get(), w, now), w,
                        granted.contains(r.id()), now);
                if (p.eligible() && (p.windowOpen() || p.qualifies())) {
                    out.add(p);
                }
            }
            out.sort(Comparator.comparingInt(RewardProgress::percent).reversed());
            return out;
        });
    }

    @Override
    public List<BillingGrant> earned(String orgId) {
        access.orgRoleOrStaff(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING,
                SellerPermissions.BILLING_ADJUST, SellerPermissions.PLANS_MANAGE);
        return tx.execute(s -> grants.forOrg(orgId, 100).stream()
                .filter(g -> g.type() != GrantType.TRIAL_END && g.type() != GrantType.WAIVE_INVOICE).toList());
    }

    @Override
    public int evaluate(String orgId, RewardMetric metric) {
        if (settings.isRetail(orgId)) {
            return 0;
        }
        Instant now = Instant.now(clock);
        List<RewardProgress> qualifying = tx.execute(s -> {
            Optional<SellerFacts> facts = metrics.facts(orgId);
            if (facts.isEmpty()) {
                return List.<RewardProgress>of();
            }
            Set<UUID> granted = grants.grantedRules(orgId);
            List<RewardProgress> out = new ArrayList<>();
            for (RewardRule r : rules.active(metric)) {
                RewardWindow w = window(r, facts.get(), now);
                RewardProgress p = RewardEvaluator.evaluate(r, facts.get(), value(r.metric(), facts.get(), w, now), w,
                        granted.contains(r.id()), now);
                if (p.qualifies()) {
                    out.add(p);
                }
            }
            return out;
        });
        int made = 0;
        for (RewardProgress p : qualifying) {
            // One transaction per grant: the rule's counter and the (rule, org) row commit together or not at all.
            BillingGrant g = tx.execute(s -> grant(p.rule(), orgId, p.value(), now).orElseGet(() -> {
                s.setRollbackOnly();
                return null;
            }));
            if (g != null) {
                made++;
                adjustments.apply(g.id());
            }
        }
        return made;
    }

    @Override
    public int evaluateAll() {
        int made = 0;
        for (SellerFacts f : tx.execute(s -> metrics.allFacts())) {
            try {
                made += evaluate(f.orgId(), null);
            } catch (RuntimeException e) {
                LOG.warn("Reward evaluation of {} failed; the next run retries", f.orgId(), e);
            }
        }
        return made;
    }

    /**
     * Counts the grant against the rule's max (row lock) and records it once per (rule, organisation), inside the
     * caller's transaction; an existing grant or an exhausted rule grants nothing (the caller rolls the claim back).
     */
    private Optional<BillingGrant> grant(RewardRule r, String orgId, long value, Instant now) {
        if (!rules.claimGrant(r.id())) {
            return Optional.empty();
        }
        BillingGrant g = new BillingGrant(UUID.randomUUID(), orgId, GrantSource.RULE, r.id(), null,
                GrantType.of(r.rewardType()), r.creditCents(), r.freeDays(), r.percentOff(), r.durationInvoices(),
                null, null, GrantStatus.PENDING, null, r.name(), r.rewardLabel(), "system:rewards", r.metric(), value,
                BillingGrant.StripeRefs.NONE, 0, null, now, null, 0);
        BillingGrant stored = grants.insertOnce(g);
        if (!stored.id().equals(g.id())) {
            return Optional.empty();
        }
        LOG.info("Reward rule {} granted to {} ({} {})", r.id(), orgId, r.metric(), value);
        return Optional.of(stored);
    }

    private RewardWindow window(RewardRule r, SellerFacts f, Instant now) {
        LocalDate approved = f.approvedAt() == null ? null : LocalDate.ofInstant(f.approvedAt(), ZoneOffset.UTC);
        return RewardWindow.of(r.windowDays(), r.withinDaysOfApproval(), approved,
                LocalDate.ofInstant(now, ZoneOffset.UTC));
    }

    private long value(RewardMetric metric, SellerFacts f, RewardWindow w, Instant now) {
        return switch (metric) {
            case GROSS_SALES, COMPLETED_ORDERS, COMPLETED_BOOKINGS -> w.from().isAfter(w.to()) ? 0
                    : metrics.sum(f.orgId(), metric, w.from(), w.to());
            case ACTIVE_LISTINGS -> metrics.activeListings(f.orgId());
            case DAYS_SINCE_APPROVAL -> f.approvedAt() == null ? 0
                    : ChronoUnit.DAYS.between(f.approvedAt(), now);
        };
    }

    private String orgName(String orgId) {
        return orgs.findById(orgId).map(o -> o.displayName() == null ? o.legalName() : o.displayName())
                .orElse(orgId);
    }

    private void save(RewardRule r) {
        Boolean ok = tx.execute(s -> rules.update(r));
        if (!Boolean.TRUE.equals(ok)) {
            throw new ConflictException("seller.concurrent-update", "The rule changed; reload and retry");
        }
    }

    private RewardRule find(UUID id) {
        return tx.execute(s -> rules.find(id)).orElseThrow(() -> new NotFoundException("seller.reward-rule-not-found",
                "Reward rule " + id + " not found"));
    }

    private void readAccess() {
        access.staffAny(SellerPermissions.PLANS_MANAGE, SellerPermissions.BILLING, SellerPermissions.BILLING_ADJUST);
    }

    private static RewardRule build(UUID id, RewardRuleInput in, RewardRuleStatus status, int grantsCount,
                                    String createdBy, Instant createdAt, String updatedBy, Instant updatedAt,
                                    long version) {
        return new RewardRule(id, in.name().trim(), in.description(), status, in.kind(), in.metric(), in.threshold(),
                in.windowDays(), in.withinDaysOfApproval(), in.rewardType(), in.rewardFreeDays(),
                in.rewardCreditCents(), in.rewardPercentOff(), in.rewardDurationInvoices(), in.eligiblePlans(),
                in.startsAt(), in.endsAt(), in.maxGrants(), grantsCount, createdBy, createdAt, updatedBy, updatedAt,
                version);
    }
}
