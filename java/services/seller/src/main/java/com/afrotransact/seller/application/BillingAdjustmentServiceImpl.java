package com.afrotransact.seller.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.domain.FreeDays;
import com.afrotransact.seller.domain.GrantSource;
import com.afrotransact.seller.domain.GrantStatus;
import com.afrotransact.seller.domain.GrantType;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.PlanPricing;
import com.afrotransact.seller.domain.RewardLabels;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.SubscriptionStatus;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.InvoiceRepository;
import com.afrotransact.seller.port.InvoiceSnapshot;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.seller.port.SubscriptionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class BillingAdjustmentServiceImpl implements BillingAdjustmentService {

    private static final Logger LOG = LoggerFactory.getLogger(BillingAdjustmentServiceImpl.class);
    private static final int MAX_ATTEMPTS = 8;
    private static final long MAX_CREDIT_CENTS = 1_000_000;

    private final BillingGrantRepository grants;
    private final SubscriptionRepository subs;
    private final InvoiceRepository invoices;
    private final StripeBillingGateway stripe;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final SellerSettings settings;
    private final Clock clock;

    BillingAdjustmentServiceImpl(BillingGrantRepository grants, SubscriptionRepository subs, InvoiceRepository invoices,
                                 StripeBillingGateway stripe, AccessGuard access, DomainEventPublisher events,
                                 TransactionOperations tx, SellerSettings settings, Clock clock) {
        this.grants = grants;
        this.subs = subs;
        this.invoices = invoices;
        this.stripe = stripe;
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
        Subscription sub = subscription(orgId);
        if (sub.stripeCustomerId() == null) {
            throw new SellerStateConflict("seller.no-stripe-customer", "This organisation has no Stripe customer "
                    + "yet (free plan or not activated)");
        }
        return record(manual(orgId, actor, GrantType.CREDIT, amountCents, null, null, null, null, reasonCode, note,
                requestKey, PlanPricing.usd(amountCents) + " credit"));
    }

    @Override
    public BillingGrant freeDays(String orgId, int days, String reasonCode, String note, String requestKey) {
        Actor actor = staff(orgId, reasonCode, note);
        if (days < 1 || days > 365) {
            throw invalid("days", "1 to 365 days");
        }
        requireStripeSubscription(subscription(orgId));
        return record(manual(orgId, actor, GrantType.FREE_DAYS, null, days, null, null, null, reasonCode, note,
                requestKey, RewardLabels.days(days) + " free"));
    }

    @Override
    public BillingGrant waiveInvoice(String orgId, String invoiceId, String reasonCode, String note,
                                     String requestKey) {
        Actor actor = staff(orgId, reasonCode, note);
        Invoice inv = invoices.find(orgId, invoiceId).orElseThrow(() -> new NotFoundException(
                "seller.invoice-not-found", "Invoice " + invoiceId + " not found"));
        if (!"open".equals(inv.status()) && !"uncollectible".equals(inv.status())) {
            throw new SellerStateConflict("seller.invoice-not-open", "Only an open invoice can be waived (this one "
                    + "is " + inv.status() + ")");
        }
        return record(manual(orgId, actor, GrantType.WAIVE_INVOICE, inv.amountDueCents(), null, null, invoiceId, null,
                reasonCode, note, requestKey, "Invoice " + (inv.number() == null ? invoiceId : inv.number())
                        + " waived (" + PlanPricing.usd(inv.amountDueCents()) + ")"));
    }

    @Override
    public BillingGrant setTrial(String orgId, int days, String reasonCode, String note, String requestKey) {
        Actor actor = staff(orgId, reasonCode, note);
        if (days < 0 || days > 365) {
            throw invalid("days", "0 to 365 days");
        }
        Subscription sub = subscription(orgId);
        Instant trialEnd = null;
        if (sub.status() == SubscriptionStatus.TRIALING && sub.stripeSubscriptionId() != null) {
            Instant start = sub.currentPeriodStart() == null ? Instant.now(clock) : sub.currentPeriodStart();
            trialEnd = start.plus(Duration.ofDays(days));
            if (!trialEnd.isAfter(Instant.now(clock).plus(Duration.ofHours(1)))) {
                throw invalid("days", "The trial would already be over; give free days instead");
            }
        } else if (sub.status() != SubscriptionStatus.PENDING_ACTIVATION) {
            throw new SellerStateConflict("seller.trial-not-changeable", "The trial can only change before "
                    + "activation or while trialing; give free days instead");
        }
        return record(manual(orgId, actor, GrantType.TRIAL_END, null, days, null, null, trialEnd, reasonCode, note,
                requestKey, days + "-day trial"));
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
            BillingGrant.StripeRefs refs = applyAtStripe(g);
            return tx.execute(s -> {
                BillingGrant saved = grants.update(grants.find(grantId).orElseThrow().applied(refs,
                        Instant.now(clock)));
                publish(saved);
                return saved;
            });
        } catch (GatewayException | SellerStateConflict e) {
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

    /** Calls Stripe for one grant; every call carries the grant's own idempotency key. */
    private BillingGrant.StripeRefs applyAtStripe(BillingGrant g) {
        Subscription sub = tx.execute(s -> subs.find(g.orgId())).orElseThrow(() -> new SellerStateConflict(
                "seller.no-subscription", "Organisation " + g.orgId() + " has no subscription"));
        Instant now = Instant.now(clock);
        return switch (g.type()) {
            case CREDIT -> {
                requireCustomer(sub);
                StripeBillingGateway.BalanceCredit c = stripe.creditBalance(sub.stripeCustomerId(), g.creditCents(),
                        g.summary(), g.stripeKey("balance"));
                tx.executeWithoutResult(s -> subs.setCreditBalance(g.orgId(), Math.max(0, -c.endingBalanceCents())));
                yield new BillingGrant.StripeRefs(sub.stripeCustomerId(), sub.stripeSubscriptionId(), c.id(), null,
                        null, null);
            }
            case FREE_DAYS -> {
                requireStripeSubscription(sub);
                Instant next = FreeDays.newTrialEnd(sub.status(), sub.trialEndsAt(), sub.currentPeriodEnd(), now,
                        g.freeDays());
                SubscriptionSnapshot snap = stripe.setTrialEnd(sub.stripeSubscriptionId(), next, g.stripeKey("trial"));
                mirror(g.orgId(), snap);
                yield new BillingGrant.StripeRefs(sub.stripeCustomerId(), sub.stripeSubscriptionId(), null, null,
                        sub.trialEndsAt(), snap.trialEnd() == null ? next : snap.trialEnd());
            }
            case PERCENT_OFF -> {
                requireStripeSubscription(sub);
                String coupon = "at_" + g.id().toString().replace("-", "").substring(0, 20);
                long months = FreeDays.couponMonths(sub.cadence(), g.durationInvoices() == null ? 1
                        : g.durationInvoices());
                stripe.createCoupon(coupon, g.percentOff() == null ? BigDecimal.ZERO : g.percentOff(), months,
                        g.summary(), g.stripeKey("coupon"));
                stripe.addDiscount(sub.stripeSubscriptionId(), coupon, g.stripeKey("discount"));
                yield new BillingGrant.StripeRefs(sub.stripeCustomerId(), sub.stripeSubscriptionId(), null, coupon,
                        null, null);
            }
            case WAIVE_INVOICE -> {
                InvoiceSnapshot v = stripe.voidInvoice(g.invoiceId(), g.stripeKey("void"));
                tx.executeWithoutResult(s -> invoices.find(g.orgId(), g.invoiceId()).ifPresent(i ->
                        invoices.upsert(new Invoice(i.stripeInvoiceId(), i.orgId(), i.number(), i.periodStart(),
                                i.periodEnd(), i.amountDueCents(), i.amountPaidCents(), i.currency(),
                                v.status() == null ? "void" : v.status(), i.billingReason(), i.hostedInvoiceUrl(),
                                i.invoicePdf(), i.createdAt(), i.lines()))));
                yield new BillingGrant.StripeRefs(sub.stripeCustomerId(), sub.stripeSubscriptionId(), null, null,
                        null, null);
            }
            case TRIAL_END -> {
                if (g.trialEnd() == null) {
                    tx.executeWithoutResult(s -> subs.setTrialOverride(g.orgId(), g.freeDays(), g.reasonCode(),
                            g.note(), g.actorId()));
                    yield BillingGrant.StripeRefs.NONE;
                }
                requireStripeSubscription(sub);
                SubscriptionSnapshot snap = stripe.setTrialEnd(sub.stripeSubscriptionId(), g.trialEnd(),
                        g.stripeKey("trial"));
                mirror(g.orgId(), snap);
                tx.executeWithoutResult(s -> subs.setTrialOverride(g.orgId(), g.freeDays(), g.reasonCode(),
                        g.note(), g.actorId()));
                yield new BillingGrant.StripeRefs(sub.stripeCustomerId(), sub.stripeSubscriptionId(), null, null,
                        sub.trialEndsAt(), snap.trialEnd());
            }
        };
    }

    /** Stores Stripe's answer (trial end, status, period) on the subscription. */
    private void mirror(String orgId, SubscriptionSnapshot snap) {
        tx.executeWithoutResult(s -> subs.lock(orgId).ifPresent(sub -> subs.update(sub.withStripe(
                sub.stripeCustomerId(), sub.stripeSubscriptionId(), snap.itemId() == null ? sub.stripeItemId()
                        : snap.itemId(), snap.status() == null ? sub.status()
                        : SubscriptionStatus.fromStripe(snap.status()), snap.trialEnd(),
                snap.currentPeriodStart() == null ? sub.currentPeriodStart() : snap.currentPeriodStart(),
                snap.currentPeriodEnd() == null ? sub.currentPeriodEnd() : snap.currentPeriodEnd(),
                snap.cancelAtPeriodEnd(), Instant.now(clock)))));
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

    private BillingGrant manual(String orgId, Actor actor, GrantType type, Long credit, Integer days,
                                BigDecimal percent, String invoiceId, Instant trialEnd, String reason, String note,
                                String requestKey, String summary) {
        String key = requestKey == null || requestKey.isBlank() ? null : requestKey.trim();
        return new BillingGrant(UUID.randomUUID(), orgId, GrantSource.MANUAL, null, key, type, credit, days, percent,
                null, invoiceId, trialEnd, GrantStatus.PENDING, reason, note == null || note.isBlank() ? null
                : note.trim(), summary, actor.subject(), null, null, BillingGrant.StripeRefs.NONE, 0, null,
                Instant.now(clock), null, 0);
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

    private static void requireCustomer(Subscription sub) {
        if (sub.stripeCustomerId() == null) {
            throw new SellerStateConflict("seller.no-stripe-customer", "No Stripe customer yet");
        }
    }

    private static void requireStripeSubscription(Subscription sub) {
        if (sub.stripeSubscriptionId() == null || sub.status() == SubscriptionStatus.CANCELED) {
            throw new SellerStateConflict("seller.no-paid-subscription", "This organisation has no paid "
                    + "subscription to give free days or a discount on");
        }
    }

    private static SellerRuleViolation invalid(String field, String message) {
        return new SellerRuleViolation("seller.invalid-adjustment", message, Map.of(field, message));
    }
}
