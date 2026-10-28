package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.domain.ChargeGate;
import com.afrotransact.seller.domain.FreeDays;
import com.afrotransact.seller.domain.GrantSource;
import com.afrotransact.seller.domain.GrantStatus;
import com.afrotransact.seller.domain.GrantType;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.PlanPricing;
import com.afrotransact.seller.domain.RewardLabels;
import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.SubscriptionStatus;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.InvoiceRepository;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class BillingAdjustmentServiceImpl implements BillingAdjustmentService {

    private static final Logger LOG = LoggerFactory.getLogger(BillingAdjustmentServiceImpl.class);
    private static final int MAX_ATTEMPTS = 8;
    private static final long MAX_CREDIT_CENTS = 1_000_000;
    private static final long MAX_GATE_THRESHOLD = 100_000_000L;
    private static final Duration MAX_GATE_WAIVER = Duration.ofDays(3 * 365L);
    private static final Set<RewardMetric> GATE_METRICS = Set.of(RewardMetric.GROSS_SALES,
            RewardMetric.COMPLETED_ORDERS, RewardMetric.COMPLETED_BOOKINGS, RewardMetric.ACTIVE_LISTINGS);

    private final BillingGrantRepository grants;
    private final SubscriptionRepository subs;
    private final InvoiceRepository invoices;
    private final InvoiceCollectionService collection;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final SellerSettings settings;
    private final Clock clock;

    BillingAdjustmentServiceImpl(BillingGrantRepository grants, SubscriptionRepository subs,
                                 InvoiceRepository invoices, InvoiceCollectionService collection, AccessGuard access,
                                 DomainEventPublisher events, TransactionOperations tx, SellerSettings settings,
                                 Clock clock) {
        this.grants = grants;
        this.subs = subs;
        this.invoices = invoices;
        this.collection = collection;
        this.access = access;
        this.events = events;
        this.tx = tx;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public BillingGrant credit(String orgId, long amountCents, String reasonCode, String note, String requestKey) {
        Actor actor = staff(orgId, reasonCode, note);
        if (amountCents <= 0 || amountCents > MAX_CREDIT_CENTS) {
            throw invalid("amountCents", "$0.01 to $10,000");
        }
        subscription(orgId);
        return record(manual(orgId, actor, new Spec(GrantType.CREDIT, amountCents, null, null, null, null, null, null,
                null, PlanPricing.usd(amountCents) + " credit"), reasonCode, note, requestKey));
    }

    @Override
    public BillingGrant freeDays(String orgId, int days, String reasonCode, String note, String requestKey) {
        Actor actor = staff(orgId, reasonCode, note);
        if (days < 1 || days > 365) {
            throw invalid("days", "1 to 365 days");
        }
        requireActivated(subscription(orgId));
        return record(manual(orgId, actor, new Spec(GrantType.FREE_DAYS, null, days, null, null, null, null, null,
                null, RewardLabels.days(days) + " free"), reasonCode, note, requestKey));
    }

    @Override
    public BillingGrant freeMonths(String orgId, int months, String reasonCode, String note, String requestKey) {
        Actor actor = staff(orgId, reasonCode, note);
        if (months < 1 || months > 36) {
            throw invalid("months", "1 to 36 months");
        }
        requireActivated(subscription(orgId));
        return record(manual(orgId, actor, new Spec(GrantType.FREE_MONTHS, null, null, months, null, null, null,
                null, null, months + (months == 1 ? " month" : " months") + " free"), reasonCode, note, requestKey));
    }

    @Override
    public BillingGrant waiveInvoice(String orgId, String invoiceId, String reasonCode, String note,
                                     String requestKey) {
        Actor actor = staff(orgId, reasonCode, note);
        Invoice inv = tx.execute(s -> invoices.find(orgId, invoiceId)).orElseThrow(() -> new NotFoundException(
                "seller.invoice-not-found", "Invoice " + invoiceId + " not found"));
        if (!inv.isOpen() && !Invoice.UNCOLLECTIBLE.equals(inv.status())) {
            throw new SellerStateConflict("seller.invoice-not-open", "Only an open invoice can be waived (this one "
                    + "is " + inv.status() + ")");
        }
        return record(manual(orgId, actor, new Spec(GrantType.WAIVE_INVOICE, inv.remainingCents(), null, null, null,
                null, null, invoiceId, null, "Invoice " + (inv.number() == null ? invoiceId : inv.number())
                + " waived (" + PlanPricing.usd(inv.remainingCents()) + ")"), reasonCode, note, requestKey));
    }

    @Override
    public BillingGrant setTrial(String orgId, int days, String reasonCode, String note, String requestKey) {
        Actor actor = staff(orgId, reasonCode, note);
        if (days < 0 || days > 365) {
            throw invalid("days", "0 to 365 days");
        }
        Subscription sub = subscription(orgId);
        Instant trialEnd = null;
        if (sub.status() == SubscriptionStatus.TRIALING) {
            Instant start = sub.currentPeriodStart() == null ? Instant.now(clock) : sub.currentPeriodStart();
            trialEnd = start.plus(Duration.ofDays(days));
            if (!trialEnd.isAfter(Instant.now(clock).plus(Duration.ofHours(1)))) {
                throw invalid("days", "The trial would already be over; give free days instead");
            }
        } else if (sub.status() != SubscriptionStatus.PENDING_ACTIVATION) {
            throw new SellerStateConflict("seller.trial-not-changeable", "The trial can only change before "
                    + "activation or while trialing; give free days instead");
        }
        return record(manual(orgId, actor, new Spec(GrantType.TRIAL_END, null, days, null, null, null, null, null,
                trialEnd, days + "-day trial"), reasonCode, note, requestKey));
    }

    @Override
    public BillingGrant setChargeGate(String orgId, GateInput gate, String reasonCode, String note,
                                      String requestKey) {
        Actor actor = staff(orgId, reasonCode, note);
        ChargeGate.Mode mode = mode(gate);
        RewardMetric metric = null;
        Long threshold = null;
        Instant until = null;
        String summary;
        if (mode == ChargeGate.Mode.WAIVE_UNTIL) {
            until = gate.until();
            Instant now = Instant.now(clock);
            if (until == null || !until.isAfter(now) || until.isAfter(now.plus(MAX_GATE_WAIVER))) {
                throw invalid("until", "A date in the future, within three years");
            }
            summary = "Plan charges waived until " + until;
        } else {
            metric = metric(gate);
            threshold = gate.threshold();
            if (threshold == null || threshold < 1 || threshold > MAX_GATE_THRESHOLD) {
                throw invalid("threshold", "Enter a positive amount");
            }
            summary = mode == ChargeGate.Mode.START_AFTER
                    ? "Charging starts after " + metric.format(threshold) + " " + metric.noun()
                    : "Plan charges waived while the seller has " + metric.format(threshold) + " " + metric.noun();
        }
        subscription(orgId);
        return record(manual(orgId, actor, new Spec(GrantType.CHARGE_GATE, null, null, null, mode.name(), metric,
                threshold, null, until, summary), reasonCode, note, requestKey));
    }

    @Override
    public BillingGrant clearChargeGate(String orgId, String reasonCode, String note, String requestKey) {
        Actor actor = staff(orgId, reasonCode, note);
        subscription(orgId);
        return record(manual(orgId, actor, new Spec(GrantType.CHARGE_GATE, null, null, null, null, null, null, null,
                null, "Charge condition removed"), reasonCode, note, requestKey));
    }

    @Override
    public Page<BillingGrant> grants(BillingGrantRepository.GrantFilter filter, int page, int size) {
        access.staffAny(SellerPermissions.PLANS_MANAGE, SellerPermissions.BILLING, SellerPermissions.BILLING_ADJUST);
        return tx.execute(s -> grants.search(filter, Math.max(0, page), Math.min(200, Math.max(1, size))));
    }

    @Override
    public List<BillingGrantRepository.ReasonCode> reasonCodes() {
        return tx.execute(s -> grants.reasonCodes());
    }

    @Override
    public BillingGrant apply(UUID grantId) {
        BillingGrant g = tx.execute(s -> grants.find(grantId)).orElseThrow();
        if (g.status() == GrantStatus.APPLIED) {
            return g;
        }
        try {
            BillingGrant.Effect effect = applyLocally(g);
            return tx.execute(s -> {
                BillingGrant saved = grants.update(grants.find(grantId).orElseThrow().applied(effect,
                        Instant.now(clock)));
                publish(saved);
                return saved;
            });
        } catch (SellerStateConflict e) {
            LOG.warn("Grant {} ({} for {}) not applied: {}", g.id(), g.type(), g.orgId(), e.getMessage());
            try {
                return tx.execute(s -> grants.update(grants.find(grantId).orElseThrow().failed(e.getMessage())));
            } catch (ConflictException c) {
                return tx.execute(s -> grants.find(grantId)).orElseThrow();
            }
        } catch (ConflictException e) {
            return tx.execute(s -> grants.find(grantId)).orElseThrow();
        }
    }

    @Override
    public int retryPending() {
        int applied = 0;
        for (BillingGrant g : tx.execute(s -> grants.retryable(Instant.now(clock).minusSeconds(60), MAX_ATTEMPTS,
                100))) {
            if (apply(g.id()).status() == GrantStatus.APPLIED) {
                applied++;
            }
        }
        return applied;
    }

    /** Applies the grant to the subscription, credit balance, invoice or charge gate. */
    private BillingGrant.Effect applyLocally(BillingGrant g) {
        Subscription sub = tx.execute(s -> subs.find(g.orgId())).orElseThrow(() -> new SellerStateConflict(
                "seller.no-subscription", "Organisation " + g.orgId() + " has no subscription"));
        Instant now = Instant.now(clock);
        return switch (g.type()) {
            case CREDIT -> {
                tx.execute(s -> subs.adjustCreditBalance(g.orgId(), g.creditCents()));
                collectOpenInvoices(g.orgId());
                yield new BillingGrant.Effect("credit-" + g.id(), null, null);
            }
            case FREE_DAYS -> freeUntil(g, sub, now, FreeDays.newTrialEnd(sub.status(), sub.trialEndsAt(),
                    sub.currentPeriodEnd(), now, g.freeDays() == null ? 0 : g.freeDays()));
            case FREE_MONTHS -> freeUntil(g, sub, now, FreeDays.newTrialEndMonths(sub.status(), sub.trialEndsAt(),
                    sub.currentPeriodEnd(), now, g.freeMonths() == null ? 0 : g.freeMonths()));
            case PERCENT_OFF -> new BillingGrant.Effect("coupon-" + g.id(), null, null);
            case WAIVE_INVOICE -> {
                collection.waive(g.invoiceId(), g.reasonCode(), g.note(), g.actorId());
                yield new BillingGrant.Effect("waiver-" + g.invoiceId(), null, null);
            }
            case TRIAL_END -> {
                Instant newEnd = g.trialEnd();
                tx.executeWithoutResult(s -> {
                    subs.setTrialOverride(g.orgId(), g.freeDays(), g.reasonCode(), g.note(), g.actorId());
                    if (newEnd != null) {
                        subs.lock(g.orgId()).ifPresent(locked -> subs.update(locked.withFreeUntil(newEnd, now)));
                    }
                });
                yield new BillingGrant.Effect(null, sub.trialEndsAt(), newEnd);
            }
            case CHARGE_GATE -> {
                tx.executeWithoutResult(s -> {
                    if (g.gateMode() == null) {
                        subs.clearChargeGate(g.orgId());
                    } else {
                        subs.setChargeGate(g.orgId(), new ChargeGate(ChargeGate.Mode.valueOf(g.gateMode()),
                                g.metric(), g.metricValue(), now, g.trialEnd(), g.reasonCode(), g.note(),
                                g.actorId(), now, null));
                    }
                });
                yield new BillingGrant.Effect("gate-" + g.id(), null, null);
            }
        };
    }

    private BillingGrant.Effect freeUntil(BillingGrant g, Subscription sub, Instant now, Instant newEnd) {
        requireActivated(sub);
        tx.executeWithoutResult(s -> subs.lock(g.orgId()).ifPresent(locked ->
                subs.update(locked.withFreeUntil(newEnd, now))));
        return new BillingGrant.Effect(null, sub.status() == SubscriptionStatus.TRIALING ? sub.trialEndsAt()
                : sub.currentPeriodEnd(), newEnd);
    }

    /** Credit may pay an invoice that is waiting; outside the grant's transaction. */
    private void collectOpenInvoices(String orgId) {
        for (Invoice i : tx.execute(s -> invoices.list(orgId, 20))) {
            if (i.isOpen()) {
                collection.collect(i.id());
            }
        }
    }

    private void publish(BillingGrant g) {
        Optional<Subscription> sub = subs.find(g.orgId());
        events.publish(SellerEvents.BILLING_GRANT_APPLIED, SellerEvents.AGGREGATE_SUBSCRIPTION, g.orgId(),
                GrantEventPayload.of(g, sub.map(Subscription::trialEndsAt).orElse(null), Instant.now(clock)));
    }

    private BillingGrant record(BillingGrant g) {
        BillingGrant stored = tx.execute(s -> grants.insertOnce(g));
        if (stored.status() == GrantStatus.APPLIED) {
            return stored;
        }
        return apply(stored.id());
    }

    /** What a manual grant gives (see {@link BillingGrant}); {@code trialEnd} doubles as a gate's "until". */
    private record Spec(GrantType type, Long credit, Integer days, Integer months, String gateMode,
                        RewardMetric metric, Long metricValue, String invoiceId, Instant trialEnd, String summary) {
    }

    private BillingGrant manual(String orgId, Actor actor, Spec spec, String reason, String note,
                                String requestKey) {
        String key = requestKey == null || requestKey.isBlank() ? null : requestKey.trim();
        Integer coupon = null;
        return new BillingGrant(UUID.randomUUID(), orgId, GrantSource.MANUAL, null, key, spec.type(), spec.credit(),
                spec.days(), spec.months(), null, coupon, coupon, spec.gateMode(), spec.invoiceId(), spec.trialEnd(),
                GrantStatus.PENDING, reason, note == null || note.isBlank() ? null : note.trim(), spec.summary(),
                actor.subject(), spec.metric(), spec.metricValue(), BillingGrant.Effect.NONE, 0, null,
                Instant.now(clock), null, 0);
    }

    private Subscription requireActivated(Subscription sub) {
        if (sub.status() == SubscriptionStatus.CANCELED) {
            throw new SellerStateConflict("seller.no-paid-subscription", "This organisation has no active subscription");
        }
        if (sub.status() == SubscriptionStatus.PENDING_ACTIVATION || sub.anchorDate() == null) {
            throw new SellerStateConflict("seller.not-active-yet", "The subscription isn't active yet; set the trial "
                    + "length instead");
        }
        return sub;
    }

    private Actor staff(String orgId, String reasonCode, String note) {
        Actor actor = access.staff(SellerPermissions.BILLING_ADJUST);
        if (settings.isRetail(orgId)) {
            throw new SellerRuleViolation("seller.retail-not-billed", "AfroTransact Retail is never billed");
        }
        boolean known = tx.execute(s -> grants.reasonCodes()).stream().anyMatch(r -> r.code().equals(reasonCode));
        if (!known) {
            throw invalid("reasonCode", "Choose a reason");
        }
        if ("OTHER".equals(reasonCode) && (note == null || note.isBlank())) {
            throw invalid("note", "Explain the reason");
        }
        return actor;
    }

    private Subscription subscription(String orgId) {
        return tx.execute(s -> subs.find(orgId)).orElseThrow(() -> new NotFoundException("seller.no-subscription",
                "Organisation " + orgId + " has no subscription yet"));
    }

    private static ChargeGate.Mode mode(GateInput gate) {
        try {
            return ChargeGate.Mode.valueOf(gate.mode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid("mode", "START_AFTER, WAIVE_WHILE or WAIVE_UNTIL");
        }
    }

    private static RewardMetric metric(GateInput gate) {
        try {
            RewardMetric m = RewardMetric.valueOf(gate.metric().trim().toUpperCase(Locale.ROOT));
            if (GATE_METRICS.contains(m)) {
                return m;
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            // falls through to the validation error
        }
        throw invalid("metric", "GROSS_SALES, COMPLETED_ORDERS, COMPLETED_BOOKINGS or ACTIVE_LISTINGS");
    }

    private static SellerRuleViolation invalid(String field, String message) {
        return new SellerRuleViolation("seller.invalid-adjustment", message, Map.of(field, message));
    }
}
