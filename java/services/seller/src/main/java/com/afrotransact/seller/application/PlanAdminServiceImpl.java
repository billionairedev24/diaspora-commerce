package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanCatalogRules;
import com.afrotransact.seller.domain.PlanInput;
import com.afrotransact.seller.domain.PlanPricesInput;
import com.afrotransact.seller.domain.PlanStatus;
import com.afrotransact.seller.domain.PlanVersion;
import com.afrotransact.seller.domain.PlanVersionStatus;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.PlanRepository;
import com.afrotransact.seller.port.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class PlanAdminServiceImpl implements PlanAdminService {

    private final PlanRepository plans;
    private final SubscriptionRepository subs;
    private final OrganisationRepository orgs;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;

    PlanAdminServiceImpl(PlanRepository plans, SubscriptionRepository subs, OrganisationRepository orgs,
                         AccessGuard access, DomainEventPublisher events, TransactionOperations tx, Clock clock) {
        this.plans = plans;
        this.subs = subs;
        this.orgs = orgs;
        this.access = access;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public List<PlanAdminView> plans() {
        readAccess();
        Map<String, Map<Integer, Long>> counts = counts();
        return tx.execute(s -> plans.findAll().stream().map(p -> view(p, counts)).toList());
    }

    @Override
    public PlanAdminView plan(String code) {
        readAccess();
        return tx.execute(s -> view(plan0(code), counts()));
    }

    @Override
    public PlanAdminView create(PlanInput in, PlanPricesInput prices) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        PlanCatalogRules.validate(in, true);
        PlanCatalogRules.validate(prices);
        Instant now = Instant.now(clock);
        tx.executeWithoutResult(s -> {
            if (plans.find(in.code()).isPresent()) {
                throw new SellerRuleViolation("seller.plan-exists", "A plan with code " + in.code() + " exists",
                        Map.of("code", "Already used"));
            }
            plans.insert(in, actor.subject(), now);
            plans.insertVersion(new PlanVersion(in.code(), 1, PlanVersionStatus.DRAFT, PlanVersion.prices(
                    prices.monthlyCents(), prices.quarterlyCents(), prices.annualCents()), prices.trialDays(),
                    prices.note(), actor.subject(), now, null));
        });
        return plan(in.code());
    }

    @Override
    public PlanAdminView update(String code, PlanInput in, long expectedVersion) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        PlanInput named = new PlanInput(code, null, in.name(), in.tagline(), in.features(), in.limits(),
                in.visibility(), in.sortOrder());
        PlanCatalogRules.validate(named, false);
        tx.executeWithoutResult(s -> {
            Plan p = plans.lock(code).orElseThrow(() -> notFound(code));
            // Leaving the limits out keeps the plan's entitlements (an edit of the name must not wipe them).
            PlanInput effective = named.limits().isEmpty() ? new PlanInput(code, null, named.name(),
                    named.tagline(), named.features(), p.limits(), named.visibility(), named.sortOrder()) : named;
            plans.update(effective, p.status(), expectedVersion, actor.subject(), Instant.now(clock));
        });
        return plan(code);
    }

    @Override
    public PlanAdminView saveDraft(String code, PlanPricesInput in) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        PlanCatalogRules.validate(in);
        Map<Cadence, Long> prices = PlanVersion.prices(in.monthlyCents(),
                in.quarterlyCents(), in.annualCents());
        tx.executeWithoutResult(s -> {
            Plan p = plans.lock(code).orElseThrow(() -> notFound(code));
            var draft = plans.draft(code);
            Instant now = Instant.now(clock);
            if (draft.isPresent()) {
                PlanVersion d = draft.get();
                plans.updateDraft(new PlanVersion(code, d.version(), PlanVersionStatus.DRAFT, prices, in.trialDays(),
                        in.note(), d.createdBy(), d.createdAt(), null));
                return;
            }
            if (p.version() > 0) {
                PlanVersion current = plans.version(code, p.version()).orElseThrow();
                if (!current.differsFrom(prices, in.trialDays())) {
                    throw new SellerRuleViolation("seller.plan-unchanged", "These are already the current prices "
                            + "and trial", Map.of("monthlyCents", "Unchanged"));
                }
                if (current.isFree() != prices.values().stream().allMatch(v -> v == 0)) {
                    throw new SellerRuleViolation("seller.plan-free-switch", "A free plan can't become paid (or the "
                            + "reverse); create another plan", Map.of("monthlyCents", "Free and paid plans differ"));
                }
            }
            plans.insertVersion(new PlanVersion(code, plans.nextVersionNumber(code), PlanVersionStatus.DRAFT, prices,
                    in.trialDays(), in.note(), actor.subject(), now, null));
        });
        return plan(code);
    }

    @Override
    public PlanAdminView publish(String code) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        tx.executeWithoutResult(s -> {
            Plan p = plans.lock(code).orElseThrow(() -> notFound(code));
            Instant now = Instant.now(clock);
            var draft = plans.draft(code);
            if (draft.isEmpty()) {
                if (p.status() == PlanStatus.ACTIVE) {
                    throw new SellerStateConflict("seller.nothing-to-publish", "There is no draft version to "
                            + "publish");
                }
                if (p.version() == 0) {
                    throw new SellerStateConflict("seller.nothing-to-publish", "Save prices before publishing");
                }
            } else {
                plans.publishVersion(code, draft.get().version(), now);
                plans.setCurrentVersion(code, draft.get().version(), actor.subject(), now);
            }
            plans.setStatus(code, PlanStatus.ACTIVE, actor.subject(), now);
        });
        return plan(code);
    }

    @Override
    public PlanAdminView retire(String code) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        tx.executeWithoutResult(s -> {
            Plan p = plans.lock(code).orElseThrow(() -> notFound(code));
            if (p.status() != PlanStatus.ACTIVE) {
                throw new SellerStateConflict("seller.plan-not-active", "Only a published plan can be retired");
            }
            plans.setStatus(code, PlanStatus.RETIRED, actor.subject(), Instant.now(clock));
        });
        return plan(code);
    }

    @Override
    public PlanAdminView setInvites(String code, List<String> orgIds) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        List<String> ids = orgIds == null ? List.of() : orgIds.stream().filter(o -> o != null && !o.isBlank())
                .map(String::trim).distinct().toList();
        tx.executeWithoutResult(s -> {
            plans.lock(code).orElseThrow(() -> notFound(code));
            plans.setInvites(code, ids, actor.subject(), Instant.now(clock));
        });
        return plan(code);
    }

    @Override
    public MigrationPreview migrationPreview(String code) {
        readAccess();
        return tx.execute(s -> preview(plan0(code)));
    }

    @Override
    public MigrationResult migrate(String code, List<String> orgIds) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        Plan target = plan0(code);
        if (target.version() == 0 || target.status() == PlanStatus.DRAFT) {
            throw new SellerStateConflict("seller.plan-not-published", "Publish the plan before migrating");
        }
        List<String> migrated = new ArrayList<>();
        List<MigrationResult.Failure> failed = new ArrayList<>();
        for (Subscription sub : subs.onOtherVersions(code, target.version())) {
            if (orgIds != null && !orgIds.isEmpty() && !orgIds.contains(sub.orgId())) {
                continue;
            }
            try {
                migrateOne(sub, target, actor);
                migrated.add(sub.orgId());
            } catch (GatewayException | SellerStateConflict e) {
                failed.add(new MigrationResult.Failure(sub.orgId(), e.getMessage()));
                plans.recordMigration(sub.orgId(), code, sub.planVersion(), target.version(), actor.subject(),
                        "FAILED", e.getMessage(), Instant.now(clock));
            }
        }
        return new MigrationResult(code, target.version(), migrated, failed);
    }

    /**
     * The subscription moves to the current version without proration, so the new price applies from
     * the next renewal; the stored version changes now.
     */
    private void migrateOne(Subscription sub, Plan target, Actor actor) {
        tx.executeWithoutResult(s -> {
            Subscription locked = subs.lock(sub.orgId()).orElseThrow();
            Instant now = Instant.now(clock);
            Subscription next = locked.withPlan(target.code(), target.version(), locked.cadence(), now);
            Subscription saved = subs.update(next.withPending(locked.pendingPlanCode(), locked.pendingCadence(),
                    locked.pendingEffectiveAt(), now));
            plans.recordMigration(sub.orgId(), target.code(), sub.planVersion(), target.version(), actor.subject(),
                    "MIGRATED", null, now);
            events.publish(SellerEvents.SUBSCRIPTION_CHANGED, SellerEvents.AGGREGATE_SUBSCRIPTION, saved.orgId(),
                    SubscriptionEventPayload.of(saved, "MIGRATED", sub.planCode(), sub.cadence().name(), now));
        });
    }

    private MigrationPreview preview(Plan target) {
        List<MigrationPreview.Row> rows = new ArrayList<>();
        for (Subscription sub : subs.onOtherVersions(target.code(), target.version())) {
            Plan from = plans.find(sub.planCode(), sub.planVersion()).orElse(target);
            String name = orgs.findById(sub.orgId()).map(o -> o.displayName() == null ? o.legalName()
                    : o.displayName()).orElse(sub.orgId());
            rows.add(new MigrationPreview.Row(sub.orgId(), name, sub.planVersion(), sub.cadence(),
                    from.priceCents(sub.cadence()), target.priceCents(sub.cadence()), sub.status().name(),
                    sub.renewalAt()));
        }
        return new MigrationPreview(target.code(), target.version(), rows);
    }

    private PlanAdminView view(Plan p, Map<String, Map<Integer, Long>> counts) {
        Map<Integer, Long> byVersion = counts.getOrDefault(p.code(), Map.of());
        List<PlanAdminView.VersionView> versions = plans.versions(p.code()).stream()
                .map(v -> new PlanAdminView.VersionView(v, plans.prices(p.code(), v.version()),
                        byVersion.getOrDefault(v.version(), 0L))).toList();
        return new PlanAdminView(p, plans.rowVersion(p.code()), versions,
                plans.invites(p.code()), byVersion.values().stream().mapToLong(Long::longValue).sum());
    }

    private Map<String, Map<Integer, Long>> counts() {
        Map<String, Map<Integer, Long>> out = new HashMap<>();
        for (PlanRepository.VersionCount c : plans.subscriberCounts()) {
            out.computeIfAbsent(c.planCode(), k -> new HashMap<>()).put(c.version(), c.subscribers());
        }
        return out;
    }

    private Plan plan0(String code) {
        return plans.find(code).orElseThrow(() -> notFound(code));
    }

    private void readAccess() {
        access.staffAny(SellerPermissions.PLANS_MANAGE, SellerPermissions.BILLING, SellerPermissions.BILLING_ADJUST);
    }

    private static NotFoundException notFound(String code) {
        return new NotFoundException("seller.plan-not-found", "Plan " + code + " not found");
    }
}
