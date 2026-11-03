package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.afrotransact.seller.domain.RewardComparison;
import com.afrotransact.seller.domain.RewardEvaluator;
import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.domain.RewardProgress;
import com.afrotransact.seller.domain.RewardRule;
import com.afrotransact.seller.domain.RewardRuleInput;
import com.afrotransact.seller.domain.RewardRuleStatus;
import com.afrotransact.seller.domain.RewardWindow;
import com.afrotransact.seller.domain.RewardWindowAnchor;
import com.afrotransact.seller.domain.SellerFacts;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.RewardRuleRepository;
import com.afrotransact.seller.port.RewardWindowCheckRepository;
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
    private final RewardWindowCheckRepository checks;
    private final BillingGrantRepository grants;
    private final SellerMetricRepository metrics;
    private final OrganisationRepository orgs;
    private final BillingAdjustmentService adjustments;
    private final AccessGuard access;
    private final TransactionOperations tx;
    private final SellerSettings settings;
    private final Clock clock;

    RewardServiceImpl(RewardRuleRepository rules, RewardWindowCheckRepository checks, BillingGrantRepository grants,
                      SellerMetricRepository metrics, OrganisationRepository orgs,
                      BillingAdjustmentService adjustments, AccessGuard access, TransactionOperations tx,
                      SellerSettings settings, Clock clock) {
        this.rules = rules;
        this.checks = checks;
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
            int pending = 0;
            List<SellerFacts> all = metrics.allFacts().stream().filter(f -> !settings.isRetail(f.orgId())).toList();
            for (SellerFacts f : all) {
                RewardWindow w = window(rule, f, now);
                long value = value(rule.metric(), f, w, now);
                boolean already = granted.contains(f.orgId());
                RewardProgress p = RewardEvaluator.evaluate(rule, f, value, w, already,
                        ruleId != null && checked(rule, f, w), now);
                qualifying += p.qualifies() ? 1 : 0;
                pending += p.pending() ? 1 : 0;
                rows.add(new RewardPreview.Row(f.orgId(), orgName(f.orgId()), f.planCode(), value, rule.threshold(),
                        p.percent(), p.qualifies(), already, p.reason(), previewStatus(p, already), p.checkAt()));
            }
            rows.sort(Comparator.comparing(RewardPreview.Row::qualifies).reversed()
                    .thenComparing(Comparator.comparing(RewardPreview.Row::pending).reversed())
                    .thenComparing(Comparator.comparingLong(RewardPreview.Row::value).reversed())
                    .thenComparing(RewardPreview.Row::orgId));
            return new RewardPreview(all.size(), qualifying, pending, rows.size() > PREVIEW_ROWS
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
                        granted.contains(r.id()), checked(r, facts.get(), w), now);
                if (p.eligible() && (p.windowOpen() || p.qualifies() || p.pending())) {
                    out.add(p);
                }
            }
            out.sort(Comparator.comparingInt(RewardProgress::percent).reversed());
            return out;
        });
    }

    @Override
    public List<Earned> earned(String orgId) {
        access.orgRoleOrStaff(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING,
                SellerPermissions.BILLING_ADJUST, SellerPermissions.PLANS_MANAGE);
        return tx.execute(s -> {
            Map<UUID, RewardComparison> comparisons = new HashMap<>();
            List<Earned> out = new ArrayList<>();
            for (BillingGrant g : grants.forOrg(orgId, 100)) {
                if (g.type() == GrantType.TRIAL_END || g.type() == GrantType.WAIVE_INVOICE || g.type() == GrantType.CHARGE_GATE) {
                    continue;
                }
                RewardComparison c = g.ruleId() == null ? null : comparisons.computeIfAbsent(g.ruleId(),
                        id -> rules.find(id).map(RewardRule::comparison).orElse(null));
                out.add(new Earned(g, c));
            }
            return out;
        });
    }

    @Override
    public int evaluate(String orgId, RewardMetric metric) {
        // Events never decide a rule checked at window end: only the jobs do, when its check is due.
        return evaluate(orgId, metric, metric == null ? Scope.ALL : Scope.AS_REACHED);
    }

    @Override
    public int evaluateAll() {
        return forEveryOrganisation(Scope.ALL);
    }

    @Override
    public int evaluateWindowEnds() {
        return forEveryOrganisation(Scope.WINDOW_END);
    }

    /** Which rules an evaluation decides: granted as reached, checked at window end, or both. */
    private enum Scope {
        AS_REACHED, WINDOW_END, ALL;

        boolean includes(RewardRule r) {
            return this == ALL || (this == WINDOW_END) == r.checkedAtWindowEnd();
        }
    }

    private int forEveryOrganisation(Scope scope) {
        int made = 0;
        for (SellerFacts f : tx.execute(s -> metrics.allFacts())) {
            try {
                made += evaluate(f.orgId(), null, scope);
            } catch (RuntimeException e) {
                LOG.warn("Reward evaluation of {} failed; the next run retries", f.orgId(), e);
            }
        }
        return made;
    }

    private int evaluate(String orgId, RewardMetric metric, Scope scope) {
        if (settings.isRetail(orgId)) {
            return 0;
        }
        Instant now = Instant.now(clock);
        List<Decision> decisions = tx.execute(s -> {
            Optional<SellerFacts> facts = metrics.facts(orgId);
            if (facts.isEmpty()) {
                return List.<Decision>of();
            }
            Set<UUID> granted = grants.grantedRules(orgId);
            List<Decision> out = new ArrayList<>();
            for (RewardRule r : rules.active(metric)) {
                if (!scope.includes(r)) {
                    continue;
                }
                RewardWindow w = window(r, facts.get(), now);
                RewardProgress p = RewardEvaluator.evaluate(r, facts.get(), value(r.metric(), facts.get(), w, now), w,
                        granted.contains(r.id()), checked(r, facts.get(), w), now);
                if (p.qualifies() || (r.checkedAtWindowEnd() && p.eligible() && w.due(now))) {
                    out.add(new Decision(p, w));
                }
            }
            return out;
        });
        int made = 0;
        for (Decision d : decisions) {
            RewardProgress p = d.progress();
            // One transaction per decision: the rule's counter, the (rule, org) grant and, for a rule checked at
            // window end, the (rule, org, window) check commit together or not at all.
            BillingGrant g = tx.execute(s -> {
                Optional<BillingGrant> grant = p.qualifies() ? grant(p.rule(), orgId, p.value(), now)
                        : Optional.empty();
                // Qualifying without a grant: the rule ran out or another replica granted first; nothing is kept.
                boolean decided = p.rule().checkedAtWindowEnd() && (grant.isPresent() || !p.qualifies())
                        && checks.insertOnce(new RewardWindowCheckRepository.Check(p.rule().id(), orgId,
                        d.window().from(), d.window().closesAt(), now, p.value(), p.rule().threshold(),
                        p.rule().comparison(), grant.isPresent() ? "QUALIFIED" : "NOT_QUALIFIED",
                        p.label(), grant.map(BillingGrant::id).orElse(null)));
                if (decided) {
                    LOG.info("Reward rule {} checked for {} at window end: {} {} {} {}", p.rule().id(), orgId,
                            p.rule().metric(), p.value(), p.rule().comparison(), grant.isPresent() ? "granted"
                                    : "not granted");
                } else if (p.rule().checkedAtWindowEnd() || grant.isEmpty()) {
                    s.setRollbackOnly();
                    return null;
                }
                return grant.orElse(null);
            });
            if (g != null) {
                made++;
                adjustments.apply(g.id());
            }
        }
        return made;
    }

    /** A rule's progress for one organisation and the window it was read over. */
    private record Decision(RewardProgress progress, RewardWindow window) {
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
                GrantType.of(r.rewardType()), r.creditCents(), r.freeDays(), null, r.percentOff(), r.durationInvoices(),
                r.durationInvoices(), null, null, null, GrantStatus.PENDING, null, r.name(), r.rewardLabel(),
                "system:rewards", r.metric(), value, BillingGrant.Effect.NONE, 0, null, now, null, 0);
        BillingGrant stored = grants.insertOnce(g);
        if (!stored.id().equals(g.id())) {
            return Optional.empty();
        }
        LOG.info("Reward rule {} granted to {} ({} {})", r.id(), orgId, r.metric(), value);
        return Optional.of(stored);
    }

    private RewardWindow window(RewardRule r, SellerFacts f, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant anchor = f.anchor(r.windowAnchor());
        if (r.checkedAtWindowEnd()) {
            if (anchor == null || r.withinDaysOfApproval() == null) {
                return new RewardWindow(today, today, true);
            }
            return RewardWindow.closing(anchor, r.withinDaysOfApproval(), f.renewalAt(),
                    Duration.ofHours(r.checkLeadHours()), now);
        }
        if (anchor == null && r.windowAnchor() == RewardWindowAnchor.SUBSCRIPTION_START) {
            return new RewardWindow(today, today, true);
        }
        LocalDate from = anchor == null ? null : LocalDate.ofInstant(anchor, ZoneOffset.UTC);
        return RewardWindow.of(r.windowDays(), r.withinDaysOfApproval(), from, today);
    }

    /** A rule checked at window end was already decided for this organisation's window. */
    private boolean checked(RewardRule r, SellerFacts f, RewardWindow w) {
        return r.checkedAtWindowEnd() && w.checkAt() != null && checks.exists(r.id(), f.orgId(), w.from());
    }

    /**
     * GRANTED, QUALIFIES (would get it now), PENDING (checked at window end, later), CHECKED (already decided),
     * NOT_YET (below an AT_LEAST threshold, or a window-end check that would not grant now) or INELIGIBLE.
     */
    private static String previewStatus(RewardProgress p, boolean already) {
        if (already) {
            return "GRANTED";
        }
        if (p.qualifies()) {
            return "QUALIFIES";
        }
        if (p.pending()) {
            return "PENDING";
        }
        if ("Already checked".equals(p.reason())) {
            return "CHECKED";
        }
        return p.eligible() ? "NOT_YET" : "INELIGIBLE";
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
                in.windowDays(), in.withinDaysOfApproval(), in.effectiveComparison(), in.effectiveAnchor(),
                in.effectiveCheckAtWindowEnd(), in.effectiveCheckLeadHours(), in.rewardType(), in.rewardFreeDays(),
                in.rewardCreditCents(), in.rewardPercentOff(), in.rewardDurationInvoices(), in.eligiblePlans(),
                in.startsAt(), in.endsAt(), in.maxGrants(), grantsCount, createdBy, createdAt, updatedBy, updatedAt,
                version);
    }
}
