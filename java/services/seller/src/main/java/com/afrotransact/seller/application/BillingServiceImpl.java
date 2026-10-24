package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.ChargeGate;
import com.afrotransact.seller.domain.DefaultApplicationRules;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.PaymentMethod;
import com.afrotransact.seller.domain.PaymentMethodKind;
import com.afrotransact.seller.domain.PayoutSection;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanChange;
import com.afrotransact.seller.domain.PlanPricing;
import com.afrotransact.seller.domain.PlanQuote;
import com.afrotransact.seller.domain.PlanSection;
import com.afrotransact.seller.domain.PlanVisibility;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.SubscriptionStatus;
import com.afrotransact.seller.domain.TrialDecision;
import com.afrotransact.seller.domain.TrialSource;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.BillingCustomerRepository;
import com.afrotransact.seller.port.BillingScheduler;
import com.afrotransact.seller.port.EarningsCollectionRepository;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.InvoiceRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.PaymentMethodRepository;
import com.afrotransact.seller.port.PlanPaymentsGateway;
import com.afrotransact.seller.domain.PlanAssignment;
import com.afrotransact.seller.port.PlanAssignmentRepository;
import com.afrotransact.seller.port.PlanRepository;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.seller.port.SubscriptionTerms;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
class BillingServiceImpl implements BillingService {

    private static final Logger LOG = LoggerFactory.getLogger(BillingServiceImpl.class);
    private static final int MIN_ASSIGN_REASON = 10;
    private static final int ASSIGNMENT_HISTORY = 20;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final SubscriptionRepository subs;
    private final PaymentMethodRepository methods;
    private final InvoiceRepository invoices;
    private final PlanRepository plans;
    private final ApplicationRepository apps;
    private final OrganisationRepository orgs;
    private final BillingScheduler scheduler;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final SellerSettings settings;
    private final TrialService trials;
    private final EarningsCollectionService earnings;
    private final BillingGrantRepository grants;
    private final EarningsCollectionRepository collections;
    private final PlanFeeCollectionService planFees;
    private final InvoiceIssuingService invoicing;
    private final InvoiceCollectionService collection;
    private final ChargeGateEvaluator gates;
    private final PlanPaymentsGateway payments;
    private final BillingCustomerRepository customers;
    private final PlanAssignmentRepository assignments;
    private final Clock clock;

    BillingServiceImpl(SubscriptionRepository subs, PaymentMethodRepository methods, InvoiceRepository invoices,
                       PlanRepository plans, ApplicationRepository apps, OrganisationRepository orgs,
                       BillingScheduler scheduler, AccessGuard access,
                       DomainEventPublisher events, TransactionOperations tx, SellerSettings settings,
                       TrialService trials, EarningsCollectionService earnings,
                       BillingGrantRepository grants, EarningsCollectionRepository collections,
                       PlanFeeCollectionService planFees, InvoiceIssuingService invoicing,
                       InvoiceCollectionService collection, ChargeGateEvaluator gates, PlanPaymentsGateway payments,
                       BillingCustomerRepository customers, PlanAssignmentRepository assignments, Clock clock) {
        this.subs = subs;
        this.methods = methods;
        this.invoices = invoices;
        this.plans = plans;
        this.apps = apps;
        this.orgs = orgs;
        this.scheduler = scheduler;
        this.access = access;
        this.events = events;
        this.tx = tx;
        this.settings = settings;
        this.trials = trials;
        this.earnings = earnings;
        this.grants = grants;
        this.collections = collections;
        this.planFees = planFees;
        this.invoicing = invoicing;
        this.collection = collection;
        this.gates = gates;
        this.payments = payments;
        this.customers = customers;
        this.assignments = assignments;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public BillingOverview overview(String orgId) {
        access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING);
        return build(subscription(orgId));
    }

    @Override
    @Transactional(readOnly = true)
    public BillingPanel panel(String orgId) {
        access.staffAny(SellerPermissions.BILLING, SellerPermissions.BILLING_ADJUST, SellerPermissions.PLANS_MANAGE);
        Organisation org = orgs.findById(orgId).orElseThrow(() -> new NotFoundException("seller.org-not-found",
                "Organisation " + orgId + " not found"));
        BillingOverview overview = subs.find(orgId).map(this::build).orElse(null);
        return new BillingPanel(orgId, org.displayName() == null ? org.legalName() : org.displayName(), org.kind(),
                org.status().name(), overview, invoices.list(orgId, 100), grants.forOrg(orgId, 100),
                collections.forOrg(orgId, 50), earnings.enabled(), overview == null ? null : planFees.view(orgId),
                assignments.ofOrg(orgId, ASSIGNMENT_HISTORY));
    }

    @Override
    public PlanChangeResult changePlan(String orgId, String planCode, Cadence cadence) {
        List<String> toCollect = new ArrayList<>();
        PlanChangeResult result = tx.execute(t -> changePlanLocked(orgId, planCode, cadence, toCollect, null));
        collectAll(toCollect);
        return result;
    }

    @Override
    public PlanChangeResult assignPlan(String orgId, String planCode, Cadence cadence, String reason) {
        Actor staff = access.staff(SellerPermissions.BILLING_ADJUST);
        if (reason == null || reason.trim().length() < MIN_ASSIGN_REASON) {
            throw new SellerRuleViolation("seller.reason-required", "Say why the plan is changed (at least "
                    + MIN_ASSIGN_REASON + " characters)", Map.of("reason", "Required"));
        }
        List<String> toCollect = new ArrayList<>();
        PlanChangeResult result = tx.execute(t -> {
            Subscription before = subs.find(orgId).orElse(null);
            PlanChangeResult r = changePlanLocked(orgId, planCode, cadence, toCollect, staff);
            assignments.insert(new PlanAssignment(UUID.randomUUID(), orgId, before == null ? null : before.planCode(),
                    planCode, cadence == null && before != null ? before.cadence() : cadence, r.applied().name(),
                    reason.trim(), staff.subject(), Instant.now(clock)));
            return r;
        });
        collectAll(toCollect);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanAssignment> assignments(String orgId) {
        access.staffAny(SellerPermissions.BILLING, SellerPermissions.BILLING_ADJUST, SellerPermissions.PLANS_MANAGE);
        return assignments.ofOrg(orgId, ASSIGNMENT_HISTORY);
    }

    /** {@code byStaff} null: the owner's own change; otherwise staff assigning (may pick an invite-only plan). */
    private PlanChangeResult changePlanLocked(String orgId, String planCode, Cadence cadence, List<String> toCollect,
                                              Actor byStaff) {
        if (byStaff == null) {
            access.orgRole(orgId, AccessGuard.OWNER, null);
        }
        requireNotRetail(orgId);
        Subscription sub = subs.lock(orgId).orElseThrow(() -> noSubscription(orgId));
        requireActive(sub);
        Organisation org = orgs.findById(orgId).orElseThrow(() -> noSubscription(orgId));
        Plan target = byStaff == null ? selectable(org, sub, planCode) : assignable(org, planCode);
        Cadence targetCadence = cadence == null ? sub.cadence() : cadence;
        Plan current = plan(sub);
        PlanChange decision = current.isFree() && !target.isFree() ? PlanChange.IMMEDIATE_UPGRADE
                : PlanChange.decide(current, sub.cadence(), target, targetCadence);
        Instant now = Instant.now(clock);
        Subscription saved;
        String change;
        switch (decision) {
            case NO_CHANGE -> {
                if (!sub.hasPendingChange()) {
                    return new PlanChangeResult(decision, build(sub));
                }
                saved = subs.update(sub.withPending(null, null, null, now));
                change = "CHANGE_CANCELLED";
            }
            case IMMEDIATE_UPGRADE -> {
                if (sub.anchorDate() == null || sub.status() == SubscriptionStatus.TRIALING) {
                    // Not charged yet: just the new plan (a trial carries over).
                    saved = subs.update(sub.withPlan(target.code(), target.version(), targetCadence, now));
                } else if (current.isFree()) {
                    saved = startPaidPeriod(sub, target, targetCadence, now, toCollect);
                } else {
                    saved = subs.update(sub.withPlan(target.code(), target.version(), targetCadence, now));
                    long due = proration(sub, current, target, targetCadence, now);
                    if (due > 0) {
                        invoicing.issueUpgrade(saved, target, due, now).filter(Invoice::isOpen)
                                .ifPresent(i -> toCollect.add(i.id()));
                    }
                }
                change = "UPGRADED";
            }
            default -> {
                Instant effective = sub.renewalAt() == null ? now : sub.renewalAt();
                saved = subs.update(sub.withPending(target.code(), targetCadence, effective, now));
                change = "CHANGE_SCHEDULED";
            }
        }
        publish(saved, change, sub.planCode(), sub.cadence(), now);
        return new PlanChangeResult(decision, build(saved));
    }

    @Override
    @Transactional
    public BillingOverview cancelAtPeriodEnd(String orgId) {
        return setCancel(orgId, true);
    }

    @Override
    @Transactional
    public BillingOverview resume(String orgId) {
        return setCancel(orgId, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentMethod> paymentMethods(String orgId) {
        access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING);
        return methods.list(orgId);
    }

    @Override
    public SetupIntentView setupIntent(String orgId) {
        access.orgRole(orgId, AccessGuard.OWNER, null);
        requireNotRetail(orgId);
        tx.execute(t -> subs.find(orgId)).orElseThrow(() -> noSubscription(orgId));
        String customer = tx.execute(t -> customers.find(orgId)).orElseGet(() -> {
            Organisation org = tx.execute(t -> orgs.findById(orgId)).orElseThrow(() -> noSubscription(orgId));
            String created = payments.ensureCustomer(orgId, org.displayName() == null ? org.legalName()
                    : org.displayName(), null);
            return tx.execute(t -> customers.save(orgId, created, Instant.now(clock)));
        });
        PlanPaymentsGateway.SetupIntentSecret si = payments.createSetupIntent(customer,
                "setup-" + orgId + "-" + UUID.randomUUID());
        return new SetupIntentView(customer, si.id(), si.clientSecret());
    }

    @Override
    @Transactional
    public PaymentMethod addPaymentMethod(String orgId, AddPaymentMethodCommand cmd) {
        access.orgRole(orgId, AccessGuard.OWNER, null);
        requireNotRetail(orgId);
        validate(cmd);
        subs.lock(orgId).orElseThrow(() -> noSubscription(orgId));
        boolean makeDefault = cmd.makeDefault() || methods.list(orgId).isEmpty();
        PaymentMethod pm = new PaymentMethod(UUID.randomUUID(), orgId, cmd.stripePaymentMethodId(), cmd.kind(),
                cmd.brand() == null ? null : cmd.brand().toLowerCase(Locale.ROOT), cmd.last4(), cmd.expMonth(),
                cmd.expYear(), cmd.bankName(), cmd.accountType(), cmd.kind() == PaymentMethodKind.CARD, false, false,
                Instant.now(clock));
        methods.insert(pm);
        if (makeDefault) {
            methods.makeDefault(orgId, pm.id());
        }
        return methods.find(orgId, pm.id()).orElseThrow();
    }

    @Override
    @Transactional
    public List<PaymentMethod> makeDefault(String orgId, UUID paymentMethodId) {
        access.orgRole(orgId, AccessGuard.OWNER, null);
        requireNotRetail(orgId);
        method(orgId, paymentMethodId);
        methods.makeDefault(orgId, paymentMethodId);
        return methods.list(orgId);
    }

    @Override
    @Transactional
    public List<PaymentMethod> removePaymentMethod(String orgId, UUID paymentMethodId) {
        access.orgRole(orgId, AccessGuard.OWNER, null);
        requireNotRetail(orgId);
        PaymentMethod pm = method(orgId, paymentMethodId);
        if (pm.isDefault()) {
            throw new SellerStateConflict("seller.default-payment-method",
                    "Make another payment method the default before removing this one");
        }
        methods.delete(orgId, pm.id());
        try {
            payments.detachPaymentMethod(pm.stripePaymentMethodId());
        } catch (GatewayException e) {
            LOG.warn("Detaching {} at Stripe failed (removed locally): {}", pm.stripePaymentMethodId(), e.getMessage());
        }
        return methods.list(orgId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invoice> invoices(String orgId) {
        access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING);
        return invoices.list(orgId, 100);
    }

    @Override
    @Transactional(readOnly = true)
    public Invoice invoice(String orgId, String invoiceId) {
        access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING);
        return invoices.find(orgId, invoiceId).orElseThrow(() -> new NotFoundException("seller.invoice-not-found",
                "Invoice " + invoiceId + " not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public PlanChangePreview previewPlanChange(String orgId, String planCode, Cadence cadence) {
        access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING);
        requireNotRetail(orgId);
        Subscription sub = subscription(orgId);
        requireActive(sub);
        Organisation org = orgs.findById(orgId).orElseThrow(() -> noSubscription(orgId));
        Plan target = selectable(org, sub, planCode);
        Cadence targetCadence = cadence == null ? sub.cadence() : cadence;
        Plan current = plan(sub);
        PlanChange decision = current.isFree() && !target.isFree() ? PlanChange.IMMEDIATE_UPGRADE
                : PlanChange.decide(current, sub.cadence(), target, targetCadence);
        Instant now = Instant.now(clock);
        boolean trialing = sub.status() == SubscriptionStatus.TRIALING;
        long targetCharge = PlanPricing.quote(target, targetCadence).totalCents();
        return switch (decision) {
            case NO_CHANGE -> new PlanChangePreview(decision, target.code(), target.name(), targetCadence, now, 0,
                    target.isFree() ? 0 : targetCharge, sub.renewalAt(), trialing);
            case IMMEDIATE_UPGRADE -> {
                long dueNow;
                Instant next;
                if (sub.anchorDate() == null) {
                    // Not yet activated — trial applies if the new plan has one.
                    boolean trial = target.trialDays() > 0;
                    dueNow = trial ? 0 : targetCharge;
                    next = trial ? now.plus(Duration.ofDays(target.trialDays())) : null;
                } else if (trialing) {
                    dueNow = 0;
                    next = sub.renewalAt();
                } else {
                    // Proration is computed locally (period remaining / period total * new price).
                    dueNow = current.isFree() ? targetCharge : proration(sub, current, target, targetCadence, now);
                    next = current.isFree() ? scheduler.nextPeriodEnd(now, targetCadence, now)
                            : sub.currentPeriodEnd();
                }
                yield new PlanChangePreview(decision, target.code(), target.name(), targetCadence, now, dueNow,
                        targetCharge, next, trialing);
            }
            default -> {
                Instant effective = sub.renewalAt() == null ? now : sub.renewalAt();
                yield new PlanChangePreview(decision, target.code(), target.name(), targetCadence, effective, 0,
                        target.isFree() ? 0 : targetCharge, effective, trialing);
            }
        };
    }

    @Override
    @Transactional(readOnly = true)
    public String billedTo(String orgId) {
        access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING);
        Organisation org = orgs.findById(orgId).orElseThrow(() -> noSubscription(orgId));
        return org.legalName() != null ? org.legalName() : org.displayName();
    }

    @Override
    public Invoice retryInvoice(String orgId, String invoiceId) {
        access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING);
        requireNotRetail(orgId);
        Invoice inv = tx.execute(t -> invoices.find(orgId, invoiceId)).orElseThrow(() -> new NotFoundException(
                "seller.invoice-not-found", "Invoice " + invoiceId + " not found"));
        if (!Invoice.UNCOLLECTIBLE.equals(inv.status()) && !inv.isOpen()) {
            throw new SellerStateConflict("seller.invoice-not-open", "Only an unpaid invoice can be retried (this "
                    + "one is " + inv.status() + ")");
        }
        tx.executeWithoutResult(t -> invoices.reopen(invoiceId, Instant.now(clock)));
        collection.collect(invoiceId);
        return tx.execute(t -> invoices.find(orgId, invoiceId)).orElseThrow();
    }

    @Override
    public void activate(String orgId) {
        if (settings.isRetail(orgId)) {
            LOG.info("AfroTransact Retail is not on a plan; no subscription is activated");
            return;
        }
        Subscription sub = tx.execute(s -> prepare(orgId));
        if (sub == null || sub.status() != SubscriptionStatus.PENDING_ACTIVATION) {
            return;
        }
        Plan plan = plan(sub);
        Instant now = Instant.now(clock);

        if (plan.isFree()) {
            tx.executeWithoutResult(s -> subs.lock(orgId).filter(x -> x.status() == SubscriptionStatus.PENDING_ACTIVATION)
                    .ifPresent(x -> {
                        Instant t = Instant.now(clock);
                        Subscription saved = subs.update(x.withPeriod(t, SubscriptionStatus.ACTIVE,
                                null, t, null, false, t));
                        publish(saved, "ACTIVATED", null, null, t);
                    }));
            return;
        }

        Organisation org = orgs.findById(orgId).orElseThrow(() -> noSubscription(orgId));
        TrialDecision trial = tx.execute(s -> trialFor(orgId, plan, org));
        int trialDays = trial == null ? 0 : trial.days();

        List<String> toCollect = new ArrayList<>();
        tx.executeWithoutResult(s -> subs.lock(orgId).filter(x -> x.status() == SubscriptionStatus.PENDING_ACTIVATION)
                .ifPresent(x -> {
                    Instant t = Instant.now(clock);
                    Instant anchor;
                    SubscriptionStatus status;
                    Instant trialEnd;
                    Instant periodStart;
                    Instant periodEnd;

                    if (trialDays > 0) {
                        trialEnd = t.plus(Duration.ofDays(trialDays));
                        anchor = trialEnd; // period anchor starts after the trial
                        status = SubscriptionStatus.TRIALING;
                        periodStart = t;
                        periodEnd = trialEnd;
                    } else {
                        trialEnd = null;
                        anchor = t;
                        status = SubscriptionStatus.ACTIVE;
                        periodStart = t;
                        periodEnd = scheduler.nextPeriodEnd(anchor, x.cadence(), t);
                    }

                    Subscription saved = subs.update(x.withPeriod(anchor, status, trialEnd, periodStart, periodEnd,
                            false, t));
                    subs.setTrialSource(orgId, trial == null ? null : trial.source(),
                            trial == null ? null : trial.campaignId());
                    if (trial != null && trial.campaignId() != null && !trialsRedeem(trial)) {
                        LOG.info("Campaign {} reached its redemptions while {} activated", trial.campaignId(), orgId);
                    }
                    publish(saved, "ACTIVATED", null, null, t);
                    if (trialEnd == null) {
                        issueAndCollect(saved, periodStart, periodEnd, Invoice.REASON_CREATE, toCollect);
                    }
                }));
        collectAll(toCollect);
    }

    @Override
    public int retryPendingActivations() {
        int done = 0;
        for (Subscription s : subs.pendingActivation(50)) {
            try {
                activate(s.orgId());
                done++;
            } catch (RuntimeException e) {
                LOG.warn("Activation of {} failed again; will retry", s.orgId(), e);
            }
        }
        return done;
    }

    @Override
    public int applyDueChanges() {
        int applied = 0;
        Instant now = Instant.now(clock);
        for (Subscription s : subs.dueChanges(now, 100)) {
            try {
                Boolean ok = tx.execute(t -> applyChange(s.orgId()));
                applied += Boolean.TRUE.equals(ok) ? 1 : 0;
            } catch (RuntimeException e) {
                LOG.warn("Pending plan change of {} failed; will retry", s.orgId(), e);
            }
        }
        return applied;
    }

    @Override
    public int renewDueSubscriptions() {
        int renewed = 0;
        Instant now = Instant.now(clock);
        for (Subscription s : subs.dueRenewals(now, 100)) {
            try {
                List<String> toCollect = new ArrayList<>();
                Boolean ok = tx.execute(t -> renewOne(s.orgId(), now, toCollect));
                renewed += Boolean.TRUE.equals(ok) ? 1 : 0;
                collectAll(toCollect);
            } catch (RuntimeException e) {
                LOG.warn("Renewal of {} failed; will retry", s.orgId(), e);
            }
        }
        return renewed;
    }

    // ── private helpers ───────────────────────────────────────────────────────────────────────────────────────────

    private Boolean renewOne(String orgId, Instant now, List<String> toCollect) {
        Subscription sub = subs.lock(orgId).orElseThrow();
        if (sub.anchorDate() == null || sub.currentPeriodEnd() == null
                || sub.currentPeriodEnd().isAfter(now)
                || sub.status() == SubscriptionStatus.CANCELED) {
            return false;
        }
        if (sub.cancelAtPeriodEnd()) {
            Subscription canceled = subs.update(sub.withPeriod(sub.anchorDate(), SubscriptionStatus.CANCELED,
                    null, sub.currentPeriodEnd(), null, false, now));
            publish(canceled, "CANCELED", sub.planCode(), sub.cadence(), now);
            return true;
        }
        // A pending plan or cadence change takes effect at this renewal.
        Subscription base = sub;
        if (sub.hasPendingChange()) {
            base = base.withPlan(sub.pendingPlanCode(),
                    plan(sub.pendingPlanCode()).version(), sub.pendingCadence(), now)
                    .withPending(null, null, null, now);
        }
        Instant nextStart = sub.currentPeriodEnd();
        Instant nextEnd = scheduler.nextPeriodEnd(base.anchorDate(), base.cadence(), nextStart);
        Subscription renewed = subs.update(base.withNextPeriod(nextStart, nextEnd, now));
        publish(renewed, "RENEWED", sub.planCode(), sub.cadence(), now);
        issueAndCollect(renewed, nextStart, nextEnd, Invoice.REASON_CYCLE, toCollect);
        return true;
    }

    private Boolean applyChange(String orgId) {
        Subscription sub = subs.lock(orgId).orElseThrow();
        Instant now = Instant.now(clock);
        if (!sub.hasPendingChange() || sub.pendingEffectiveAt().isAfter(now)) {
            return false;
        }
        Plan target = plan(sub.pendingPlanCode());
        Cadence cadence = sub.pendingCadence();
        Subscription next = sub.withPlan(target.code(), target.version(), cadence, now)
                .withPending(null, null, null, now);
        Subscription saved = subs.update(next);
        publish(saved, "CHANGE_APPLIED", sub.planCode(), sub.cadence(), now);
        return true;
    }

    /** Creates the subscription row and the onboarding payment method from the approved application. */
    private Subscription prepare(String orgId) {
        Optional<Subscription> existing = subs.find(orgId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Application app = apps.findByOrg(orgId).filter(Application::isApproved).orElseThrow(() ->
                new SellerStateConflict("seller.org-not-approved", "Organisation " + orgId + " is not approved"));
        PlanSection p = app.data().plan();
        if (p == null || p.planCode() == null) {
            throw new SellerStateConflict("seller.no-plan", "The application has no plan");
        }
        Instant now = Instant.now(clock);
        Cadence cadence = p.cadence() == null ? Cadence.MONTHLY : p.cadence();
        Plan chosen = plans.find(p.planCode()).filter(Plan::isOpen).orElseThrow(() -> new SellerStateConflict(
                "seller.plan-not-available", "Plan " + p.planCode() + " is not published"));
        subs.insert(Subscription.selected(orgId, p.planCode(), chosen.version(), cadence, p.paymentMethodKind(), now));
        // How the plan is paid (S298): the Plan step's choice, else the platform default for new sellers.
        planFees.initialise(orgId, p.planFeeCollection(), app.applicantUserId());
        if (p.stripePaymentMethodId() != null) {
            PayoutSection bank = app.data().payouts();
            PaymentMethod pm = p.paymentMethodKind() == PaymentMethodKind.CARD
                    ? new PaymentMethod(UUID.randomUUID(), orgId, p.stripePaymentMethodId(), PaymentMethodKind.CARD,
                    p.cardBrand() == null ? null : p.cardBrand().toLowerCase(Locale.ROOT), p.cardLast4(),
                    p.cardExpMonth(), p.cardExpYear(), null, null, true, false, false, now)
                    : new PaymentMethod(UUID.randomUUID(), orgId, p.stripePaymentMethodId(),
                    PaymentMethodKind.US_BANK_ACCOUNT, null, bank == null ? null : bank.accountLast4(), null, null,
                    bank == null ? null : bank.bankName(), bank == null ? null : bank.accountType(),
                    bank != null && bank.externalAccountLinked(), true, false, now);
            methods.insert(pm);
            methods.makeDefault(orgId, pm.id());
        }
        return subs.find(orgId).orElseThrow();
    }

    /** AfroTransact Retail is the merchant of record, not a marketplace seller: it has no plan or billing. */
    private void requireNotRetail(String orgId) {
        if (settings.isRetail(orgId)) {
            throw new SellerRuleViolation("seller.retail-not-billed", "AfroTransact Retail is not on a seller plan "
                    + "and is never billed");
        }
    }

    private BillingOverview setCancel(String orgId, boolean cancel) {
        access.orgRole(orgId, AccessGuard.OWNER, null);
        requireNotRetail(orgId);
        Subscription sub = subs.lock(orgId).orElseThrow(() -> noSubscription(orgId));
        if (sub.anchorDate() == null) {
            throw new SellerStateConflict("seller.no-paid-subscription", "There is no active subscription to "
                    + (cancel ? "cancel" : "resume"));
        }
        if (sub.cancelAtPeriodEnd() == cancel) {
            return build(sub);
        }
        Instant now = Instant.now(clock);
        Subscription saved = subs.update(sub.withCancelAtPeriodEnd(cancel, now));
        publish(saved, cancel ? "CANCEL_SCHEDULED" : "RESUMED", sub.planCode(), sub.cadence(), now);
        return build(saved);
    }

    private BillingOverview build(Subscription sub) {
        Plan plan = plan(sub);
        PlanQuote quote = PlanPricing.quote(plan, sub.cadence());
        Plan pending = sub.pendingPlanCode() == null ? null : plan(sub.pendingPlanCode());
        List<PaymentMethod> pms = methods.list(sub.orgId());
        String to = pms.stream().filter(PaymentMethod::isDefault).findFirst().map(pm -> " to " + pm.label())
                .orElse("");
        Instant nextAt = null;
        long amount = 0;
        String label;
        if (plan.isFree() && pending == null) {
            label = "No charges";
        } else if (sub.cancelAtPeriodEnd()) {
            label = "Ends " + day(sub.currentPeriodEnd()) + " — no further charges";
        } else {
            nextAt = sub.renewalAt();
            PlanQuote next = pending != null && sub.pendingCadence() != null
                    ? PlanPricing.quote(pending, sub.pendingCadence()) : quote;
            amount = next.totalCents();
            label = sub.status() == SubscriptionStatus.TRIALING && sub.trialEndsAt() != null
                    ? "Free trial ends " + day(sub.trialEndsAt()) + " · first charge " + PlanPricing.usd(amount) + to
                    : "Next charge " + PlanPricing.usd(amount) + (nextAt == null ? "" : " on " + day(nextAt)) + to;
        }
        SubscriptionTerms terms = subs.terms(sub.orgId());
        long progress = 0;
        String gateLabel = null;
        ChargeGate gate = terms.chargeGate();
        if (gate != null) {
            Instant now = Instant.now(clock);
            progress = gates.progress(sub.orgId(), gate, now);
            ChargeGate.Decision d = gate.decide(now, progress);
            gateLabel = gateLabel(gate, d, progress);
            if (!plan.isFree() && !d.charge() && sub.status() == SubscriptionStatus.ACTIVE && !sub.cancelAtPeriodEnd()) {
                label = "No charge now — " + Character.toLowerCase(d.reason().charAt(0)) + d.reason().substring(1);
                amount = 0;
            }
        }
        return new BillingOverview(sub, plan, quote, pending, nextAt, amount, label, pms, terms, progress, gateLabel);
    }

    private static String gateLabel(ChargeGate gate, ChargeGate.Decision d, long progress) {
        return switch (gate.mode()) {
            case START_AFTER -> gate.metAt() != null ? "You reached " + gate.metric().format(gate.threshold()) + " "
                    + gate.metric().noun() + " — plan charges have started"
                    : "Charging starts after " + gate.metric().format(gate.threshold()) + " " + gate.metric().noun()
                    + " — you're at " + gate.metric().format(progress);
            case WAIVE_WHILE -> "Plan charges are waived while you have " + gate.metric().format(gate.threshold())
                    + " " + gate.metric().noun() + " — you have " + gate.metric().format(progress);
            case WAIVE_UNTIL -> d.expired() ? "The free period has ended" : "Plan charges are waived until "
                    + day(gate.until());
        };
    }

    private void validate(AddPaymentMethodCommand cmd) {
        if (cmd.stripePaymentMethodId() == null || !cmd.stripePaymentMethodId().startsWith("pm_")) {
            throw new SellerRuleViolation("seller.invalid-payment-method", "Expected a Stripe payment method (pm_…)",
                    Map.of("stripePaymentMethodId", "Invalid"));
        }
        if (cmd.kind() == null) {
            throw new SellerRuleViolation("seller.invalid-payment-method", "Card or bank account?",
                    Map.of("kind", "Required"));
        }
        if (cmd.last4() == null || !cmd.last4().matches("\\d{4}")) {
            throw new SellerRuleViolation("seller.invalid-payment-method", "Last 4 digits required",
                    Map.of("last4", "Invalid"));
        }
        if (cmd.kind() == PaymentMethodKind.CARD) {
            if (cmd.brand() == null || !DefaultApplicationRules.CARD_BRANDS.contains(cmd.brand().toLowerCase(Locale.ROOT))) {
                throw new SellerRuleViolation("seller.invalid-payment-method", "Unsupported card brand",
                        Map.of("brand", "Visa, Mastercard, American Express or Discover"));
            }
            if (cmd.expMonth() == null || cmd.expYear() == null || cmd.expMonth() < 1 || cmd.expMonth() > 12
                    || YearMonth.of(cmd.expYear(), cmd.expMonth()).isBefore(YearMonth.from(LocalDate.now(clock)))) {
                throw new SellerRuleViolation("seller.invalid-payment-method", "This card has expired",
                        Map.of("expiry", "Expired or invalid"));
            }
        } else if (cmd.bankName() == null || cmd.bankName().isBlank()) {
            throw new SellerRuleViolation("seller.invalid-payment-method", "Bank name required",
                    Map.of("bankName", "Required"));
        }
    }

    private void publish(Subscription s, String change, String previousPlan, Cadence previousCadence, Instant now) {
        events.publish(SellerEvents.SUBSCRIPTION_CHANGED, SellerEvents.AGGREGATE_SUBSCRIPTION, s.orgId(),
                SubscriptionEventPayload.of(s, change, previousPlan,
                        previousCadence == null ? null : previousCadence.name(), now));
    }

    private void requireActive(Subscription sub) {
        if (sub.status() == SubscriptionStatus.PENDING_ACTIVATION || sub.status() == SubscriptionStatus.CANCELED) {
            throw new SellerStateConflict("seller.subscription-not-active",
                    "The subscription is " + sub.status() + "; plan changes are not possible yet");
        }
    }

    private Subscription subscription(String orgId) {
        return subs.find(orgId).orElseThrow(() -> noSubscription(orgId));
    }

    private PaymentMethod method(String orgId, UUID id) {
        return methods.find(orgId, id).orElseThrow(() -> new NotFoundException("seller.payment-method-not-found",
                "Payment method " + id + " not found"));
    }

    /** The plan priced at the subscription's own version (older versions keep their prices until migrated). */
    private Plan plan(Subscription sub) {
        return plans.find(sub.planCode(), sub.planVersion()).filter(p -> p.version() > 0)
                .orElseGet(() -> plan(sub.planCode()));
    }

    /**
     * A plan the organisation may move to: published, of its kind, and public, invite-only with an invitation, or its
     * current plan.
     */
    /** Staff may assign any open plan of the organisation's kind: public, invite-only or hidden. */
    private Plan assignable(Organisation org, String planCode) {
        return plans.find(planCode).filter(p -> p.kind() == org.kind() && p.isOpen())
                .orElseThrow(() -> new SellerRuleViolation("seller.unknown-plan", "Plan " + planCode
                        + " isn't available for this organisation", Map.of("planCode", "Unknown")));
    }

    private Plan selectable(Organisation org, Subscription sub, String planCode) {
        return plans.find(planCode).filter(p -> p.kind() == org.kind() && p.isOpen())
                .filter(p -> p.visibility() == PlanVisibility.PUBLIC || p.code().equals(sub.planCode())
                        || (p.visibility() == PlanVisibility.INVITE_ONLY
                        && plans.invitedPlans(org.id()).contains(p.code())))
                .orElseThrow(() -> new SellerRuleViolation("seller.unknown-plan", "Plan " + planCode
                        + " isn't available", Map.of("planCode", "Unknown")));
    }

    /** The trial: staff override, else the longest of the plan's and the matching campaigns' (promo code, window). */
    private TrialDecision trialFor(String orgId, Plan plan, Organisation org) {
        Application app = apps.findByOrg(orgId).orElse(null);
        String promo = app == null || app.data().plan() == null ? null : app.data().plan().promoCode();
        Instant signup = app == null || app.submittedAt() == null ? Instant.now(clock) : app.submittedAt();
        return trials.decide(plan, org.kind(), signup, promo, subs.terms(orgId).trialDaysOverride());
    }

    private boolean trialsRedeem(TrialDecision trial) {
        return trials.redeem(trial.campaignId());
    }

    /**
     * The charge for moving to a dearer plan mid-period: the price difference for the part of the period left (the
     * unused part of the old plan is credited against the new one). Never negative.
     */
    private long proration(Subscription sub, Plan from, Plan to, Cadence cadence, Instant now) {
        if (sub.currentPeriodStart() == null || sub.currentPeriodEnd() == null) {
            return PlanPricing.quote(to, cadence).totalCents();
        }
        long total = sub.currentPeriodEnd().getEpochSecond() - sub.currentPeriodStart().getEpochSecond();
        long left = sub.currentPeriodEnd().getEpochSecond() - now.getEpochSecond();
        if (total <= 0 || left <= 0) {
            return 0;
        }
        long difference = PlanPricing.quote(to, cadence).totalCents() - PlanPricing.quote(from, cadence).totalCents();
        return difference <= 0 ? 0 : Math.round((double) difference * left / total);
    }

    /** A free plan moving to a paid one: the first paid period starts now (after the plan's trial, if never used). */
    private Subscription startPaidPeriod(Subscription sub, Plan target, Cadence cadence, Instant now,
                                         List<String> toCollect) {
        Subscription planned = sub.withPlan(target.code(), target.version(), cadence, now);
        boolean trialUsed = subs.terms(sub.orgId()).trialSource() != null;
        if (target.trialDays() > 0 && !trialUsed) {
            Instant trialEnd = now.plus(Duration.ofDays(target.trialDays()));
            subs.setTrialSource(sub.orgId(), TrialSource.PLAN, null);
            return subs.update(planned.withPeriod(trialEnd, SubscriptionStatus.TRIALING, trialEnd, now, trialEnd,
                    false, now));
        }
        Instant end = scheduler.nextPeriodEnd(now, cadence, now);
        Subscription saved = subs.update(planned.withPeriod(now, SubscriptionStatus.ACTIVE, null, now, end, false,
                now));
        issueAndCollect(saved, now, end, Invoice.REASON_CREATE, toCollect);
        return saved;
    }

    /** Issues the period's invoice inside the current transaction; the caller collects {@code toCollect} afterwards. */
    private void issueAndCollect(Subscription sub, Instant start, Instant end, String reason, List<String> toCollect) {
        invoicing.issue(sub, start, end, reason).filter(Invoice::isOpen).ifPresent(i -> toCollect.add(i.id()));
    }

    /** After the transaction committed: settles the issued invoices. A failure leaves them open for the job. */
    private void collectAll(List<String> invoiceIds) {
        for (String id : invoiceIds) {
            try {
                collection.collect(id);
            } catch (RuntimeException e) {
                LOG.warn("Collecting invoice {} failed; the collection job will try again", id, e);
            }
        }
    }

    private Plan plan(String code) {
        return plans.find(code).orElseThrow(() -> new NotFoundException("seller.plan-not-found",
                "Plan " + code + " not found"));
    }

    private static String day(Instant at) {
        return at == null ? "" : DAY.format(at.atZone(ZoneOffset.UTC));
    }

    private static NotFoundException noSubscription(String orgId) {
        return new NotFoundException("seller.no-subscription", "Organisation " + orgId + " has no subscription yet");
    }
}
