package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.DefaultApplicationRules;
import com.afrotransact.seller.domain.DunningState;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoiceLine;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.PaymentMethod;
import com.afrotransact.seller.domain.PaymentMethodKind;
import com.afrotransact.seller.domain.PayoutSection;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanChange;
import com.afrotransact.seller.domain.PlanPricing;
import com.afrotransact.seller.domain.PlanQuote;
import com.afrotransact.seller.domain.PlanSection;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.SubscriptionStatus;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.InvoiceRepository;
import com.afrotransact.seller.port.InvoiceSnapshot;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.PaymentMethodRepository;
import com.afrotransact.seller.port.PlanRepository;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.StripeEvent;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.seller.port.SubscriptionSnapshot;
import com.afrotransact.seller.port.UsageRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
class BillingServiceImpl implements BillingService {

    private static final Logger LOG = LoggerFactory.getLogger(BillingServiceImpl.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final SubscriptionRepository subs;
    private final PaymentMethodRepository methods;
    private final InvoiceRepository invoices;
    private final PlanRepository plans;
    private final ApplicationRepository apps;
    private final OrganisationRepository orgs;
    private final StripeBillingGateway stripe;
    private final UsageRepository usage;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final SellerSettings settings;
    private final Clock clock;

    BillingServiceImpl(SubscriptionRepository subs, PaymentMethodRepository methods, InvoiceRepository invoices,
                       PlanRepository plans, ApplicationRepository apps, OrganisationRepository orgs,
                       StripeBillingGateway stripe, UsageRepository usage, AccessGuard access,
                       DomainEventPublisher events, TransactionOperations tx, SellerSettings settings, Clock clock) {
        this.subs = subs;
        this.methods = methods;
        this.invoices = invoices;
        this.plans = plans;
        this.apps = apps;
        this.orgs = orgs;
        this.stripe = stripe;
        this.usage = usage;
        this.access = access;
        this.events = events;
        this.tx = tx;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public BillingOverview overview(String orgId) {
        access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING);
        return build(subscription(orgId));
    }

    @Override
    @Transactional
    public PlanChangeResult changePlan(String orgId, String planCode, Cadence cadence) {
        access.orgRole(orgId, AccessGuard.OWNER, null);
        requireNotRetail(orgId);
        Subscription sub = subs.lock(orgId).orElseThrow(() -> noSubscription(orgId));
        requireActive(sub);
        Organisation org = orgs.findById(orgId).orElseThrow(() -> noSubscription(orgId));
        Plan target = plans.find(planCode).filter(p -> p.kind() == org.kind()).orElseThrow(() ->
                new SellerRuleViolation("seller.unknown-plan", "Plan " + planCode + " isn't available",
                        Map.of("planCode", "Unknown")));
        Cadence targetCadence = cadence == null ? sub.cadence() : cadence;
        Plan current = plan(sub.planCode());
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
                String lookup = plans.stripeLookupKey(target.code(), targetCadence);
                SubscriptionSnapshot snap;
                if (sub.stripeSubscriptionId() == null) {
                    String customer = ensureCustomer(sub, org);
                    if (methods.list(orgId).stream().noneMatch(PaymentMethod::isDefault)) {
                        throw new SellerRuleViolation("seller.payment-method-required",
                                "Add a card or bank account before choosing a paid plan");
                    }
                    snap = stripe.createSubscription(customer, lookup, target.trialDays(),
                            "subscription-" + orgId + "-" + target.code() + "-" + targetCadence.name());
                } else {
                    snap = stripe.changePrice(sub.stripeSubscriptionId(), sub.stripeItemId(), lookup,
                            sub.status() != SubscriptionStatus.TRIALING,
                            "upgrade-" + orgId + "-" + target.code() + "-" + sub.version());
                }
                Subscription base = subs.find(orgId).orElse(sub);
                saved = subs.update(applySnapshot(base.withPlan(target.code(), targetCadence, now), snap, now));
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
    @Transactional
    public PaymentMethod addPaymentMethod(String orgId, AddPaymentMethodCommand cmd) {
        access.orgRole(orgId, AccessGuard.OWNER, null);
        requireNotRetail(orgId);
        validate(cmd);
        Subscription sub = subs.lock(orgId).orElseThrow(() -> noSubscription(orgId));
        Organisation org = orgs.findById(orgId).orElseThrow(() -> noSubscription(orgId));
        String customer = ensureCustomer(sub, org);
        stripe.attachPaymentMethod(customer, cmd.stripePaymentMethodId());
        boolean makeDefault = cmd.makeDefault() || methods.list(orgId).isEmpty();
        PaymentMethod pm = new PaymentMethod(UUID.randomUUID(), orgId, cmd.stripePaymentMethodId(), cmd.kind(),
                cmd.brand() == null ? null : cmd.brand().toLowerCase(Locale.ROOT), cmd.last4(), cmd.expMonth(),
                cmd.expYear(), cmd.bankName(), cmd.accountType(), cmd.kind() == PaymentMethodKind.CARD, false, false,
                Instant.now(clock));
        methods.insert(pm);
        if (makeDefault) {
            stripe.setDefaultPaymentMethod(customer, pm.stripePaymentMethodId());
            methods.makeDefault(orgId, pm.id());
        }
        return methods.find(orgId, pm.id()).orElseThrow();
    }

    @Override
    @Transactional
    public List<PaymentMethod> makeDefault(String orgId, UUID paymentMethodId) {
        access.orgRole(orgId, AccessGuard.OWNER, null);
        requireNotRetail(orgId);
        PaymentMethod pm = method(orgId, paymentMethodId);
        Subscription sub = subscription(orgId);
        if (sub.stripeCustomerId() != null) {
            stripe.setDefaultPaymentMethod(sub.stripeCustomerId(), pm.stripePaymentMethodId());
        }
        methods.makeDefault(orgId, pm.id());
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
        stripe.detachPaymentMethod(pm.stripePaymentMethodId());
        methods.delete(orgId, pm.id());
        return methods.list(orgId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invoice> invoices(String orgId) {
        access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING);
        return invoices.list(orgId, 100);
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
        Plan plan = plan(sub.planCode());
        if (plan.isFree()) {
            tx.executeWithoutResult(s -> subs.lock(orgId).filter(x -> x.status() == SubscriptionStatus.PENDING_ACTIVATION)
                    .ifPresent(x -> {
                        Instant now = Instant.now(clock);
                        Subscription saved = subs.update(x.withStripe(null, null, null, SubscriptionStatus.ACTIVE,
                                null, now, null, false, now));
                        publish(saved, "ACTIVATED", null, null, now);
                    }));
            return;
        }
        Organisation org = orgs.findById(orgId).orElseThrow(() -> noSubscription(orgId));
        String customer = tx.execute(s -> ensureCustomer(subs.lock(orgId).orElseThrow(), org));
        methods.list(orgId).stream().filter(PaymentMethod::isDefault).findFirst().ifPresent(pm -> {
            stripe.attachPaymentMethod(customer, pm.stripePaymentMethodId());
            stripe.setDefaultPaymentMethod(customer, pm.stripePaymentMethodId());
        });
        SubscriptionSnapshot snap = stripe.createSubscription(customer,
                plans.stripeLookupKey(sub.planCode(), sub.cadence()), plan.trialDays(), "subscription-" + orgId);
        tx.executeWithoutResult(s -> subs.lock(orgId).filter(x -> x.status() == SubscriptionStatus.PENDING_ACTIVATION)
                .ifPresent(x -> {
                    Instant now = Instant.now(clock);
                    Subscription saved = subs.update(applySnapshot(x, snap, now));
                    publish(saved, "ACTIVATED", null, null, now);
                }));
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
    @Transactional(propagation = Propagation.MANDATORY)
    public String handle(StripeEvent e) {
        Instant now = Instant.now(clock);
        if (e.invoice() != null) {
            InvoiceSnapshot inv = e.invoice();
            Optional<Subscription> found = subs.findByCustomer(inv.customerId());
            if (found.isEmpty()) {
                return "ignored-unknown-customer";
            }
            String orgId = found.get().orgId();
            invoices.upsert(new Invoice(inv.id(), orgId, inv.number(), inv.periodStart(), inv.periodEnd(),
                    inv.amountDue(), inv.amountPaid(), inv.currency(), inv.status(), inv.billingReason(),
                    inv.hostedInvoiceUrl(), inv.invoicePdf(), inv.created() == null ? now : inv.created(),
                    inv.lines().stream().map(this::resolveMeter).toList()));
            Subscription sub = subs.lock(orgId).orElseThrow();
            if ("invoice.payment_failed".equals(e.type())) {
                DunningState d = inv.nextPaymentAttempt() == null ? DunningState.FINAL_ATTEMPT_FAILED
                        : DunningState.RETRYING;
                Subscription saved = subs.update(sub.withDunning(d, SubscriptionStatus.PAST_DUE, inv.attemptCount(),
                        inv.nextPaymentAttempt(), now));
                publish(saved, "PAYMENT_FAILED", sub.planCode(), sub.cadence(), now);
            } else if ("invoice.paid".equals(e.type())
                    && (sub.dunning() != DunningState.NONE || sub.status() == SubscriptionStatus.PAST_DUE
                    || sub.status() == SubscriptionStatus.UNPAID)) {
                SubscriptionStatus status = sub.status() == SubscriptionStatus.PAST_DUE
                        || sub.status() == SubscriptionStatus.UNPAID ? SubscriptionStatus.ACTIVE : sub.status();
                Subscription saved = subs.update(sub.withDunning(DunningState.NONE, status, 0, null, now));
                publish(saved, "PAYMENT_SUCCEEDED", sub.planCode(), sub.cadence(), now);
            }
            return "applied";
        }
        if (e.subscription() != null) {
            SubscriptionSnapshot snap = e.subscription();
            Optional<Subscription> locked = subs.lockByStripeSubscription(snap.id());
            if (locked.isEmpty()) {
                return "ignored-unknown-subscription";
            }
            Subscription sub = locked.get();
            Subscription next = applySnapshot(sub, snap, now);
            if ("customer.subscription.deleted".equals(e.type())) {
                next = next.withStripe(next.stripeCustomerId(), next.stripeSubscriptionId(), next.stripeItemId(),
                        SubscriptionStatus.CANCELED, next.trialEndsAt(), next.currentPeriodStart(),
                        next.currentPeriodEnd(), false, now);
            }
            Optional<PlanRepository.PriceRef> ref = plans.byLookupKey(snap.lookupKey());
            if (ref.isPresent() && (!ref.get().planCode().equals(sub.planCode())
                    || ref.get().cadence() != sub.cadence())) {
                next = next.withPlan(ref.get().planCode(), ref.get().cadence(), now);
            }
            boolean changed = next.status() != sub.status() || !Objects.equals(next.planCode(), sub.planCode())
                    || next.cadence() != sub.cadence() || next.cancelAtPeriodEnd() != sub.cancelAtPeriodEnd();
            Subscription saved = subs.update(next);
            if (changed) {
                publish(saved, "STATUS_CHANGED", sub.planCode(), sub.cadence(), now);
            }
            return "applied";
        }
        return "ignored";
    }

    /** A line without a known lookup key is metered when it bills one of our metered subscription items. */
    private InvoiceLine resolveMeter(InvoiceLine line) {
        if (line.metered() || line.subscriptionItemId() == null) {
            return line;
        }
        return usage.meterOfItem(line.subscriptionItemId()).map(line::withMeter).orElse(line);
    }

    private Boolean applyChange(String orgId) {
        Subscription sub = subs.lock(orgId).orElseThrow();
        Instant now = Instant.now(clock);
        if (!sub.hasPendingChange() || sub.pendingEffectiveAt().isAfter(now)) {
            return false;
        }
        Plan target = plan(sub.pendingPlanCode());
        Cadence cadence = sub.pendingCadence();
        Subscription next;
        if (target.isFree()) {
            if (sub.stripeSubscriptionId() != null) {
                stripe.cancelNow(sub.stripeSubscriptionId());
            }
            next = sub.withStripe(sub.stripeCustomerId(), null, null, SubscriptionStatus.ACTIVE, null, now, null,
                    false, now).withPlan(target.code(), cadence, now);
        } else if (sub.stripeSubscriptionId() == null) {
            Organisation org = orgs.findById(orgId).orElseThrow();
            SubscriptionSnapshot snap = stripe.createSubscription(ensureCustomer(sub, org),
                    plans.stripeLookupKey(target.code(), cadence), 0, "renewal-" + orgId + "-"
                            + sub.pendingEffectiveAt().getEpochSecond());
            next = applySnapshot(subs.find(orgId).orElse(sub), snap, now).withPlan(target.code(), cadence, now);
        } else {
            SubscriptionSnapshot snap = stripe.changePrice(sub.stripeSubscriptionId(), sub.stripeItemId(),
                    plans.stripeLookupKey(target.code(), cadence), false,
                    "renewal-" + orgId + "-" + sub.pendingEffectiveAt().getEpochSecond());
            next = applySnapshot(sub, snap, now).withPlan(target.code(), cadence, now);
        }
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
        subs.insert(Subscription.selected(orgId, p.planCode(), cadence, p.paymentMethodKind(), now));
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

    private String ensureCustomer(Subscription sub, Organisation org) {
        if (sub.stripeCustomerId() != null) {
            return sub.stripeCustomerId();
        }
        String email = apps.findByOrg(org.id()).map(a -> a.data().business() == null ? null
                : a.data().business().contactEmail()).orElse(null);
        String customer = stripe.createCustomer(org.id(), org.legalName() == null ? org.displayName()
                : org.legalName(), email, "customer-" + org.id());
        Instant now = Instant.now(clock);
        subs.update(sub.withStripe(customer, sub.stripeSubscriptionId(), sub.stripeItemId(), sub.status(),
                sub.trialEndsAt(), sub.currentPeriodStart(), sub.currentPeriodEnd(), sub.cancelAtPeriodEnd(), now));
        return customer;
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
        if (sub.stripeSubscriptionId() == null) {
            throw new SellerStateConflict("seller.no-paid-subscription", "There is no paid subscription to "
                    + (cancel ? "cancel" : "resume"));
        }
        if (sub.cancelAtPeriodEnd() == cancel) {
            return build(sub);
        }
        Instant now = Instant.now(clock);
        SubscriptionSnapshot snap = stripe.setCancelAtPeriodEnd(sub.stripeSubscriptionId(), cancel);
        Subscription saved = subs.update(applySnapshot(sub, snap, now).withCancelAtPeriodEnd(cancel, now));
        publish(saved, cancel ? "CANCEL_SCHEDULED" : "RESUMED", sub.planCode(), sub.cadence(), now);
        return build(saved);
    }

    private Subscription applySnapshot(Subscription sub, SubscriptionSnapshot snap, Instant now) {
        return sub.withStripe(sub.stripeCustomerId() != null ? sub.stripeCustomerId() : snap.customerId(), snap.id(),
                snap.itemId() == null ? sub.stripeItemId() : snap.itemId(), SubscriptionStatus.fromStripe(snap.status()),
                snap.trialEnd(), snap.currentPeriodStart(), snap.currentPeriodEnd(), snap.cancelAtPeriodEnd(), now);
    }

    private BillingOverview build(Subscription sub) {
        Plan plan = plan(sub.planCode());
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
        return new BillingOverview(sub, plan, quote, pending, nextAt, amount, label, pms);
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
