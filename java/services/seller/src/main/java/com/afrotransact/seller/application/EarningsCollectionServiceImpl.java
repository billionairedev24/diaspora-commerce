package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.seller.domain.PlanFeeCollection;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.port.EarningsCollectionRepository;
import com.afrotransact.seller.port.EarningsCollectionRepository.Change;
import com.afrotransact.seller.port.EarningsCollectionRepository.Release;
import com.afrotransact.seller.port.EarningsCollectionRepository.Row;
import com.afrotransact.seller.port.EarningsWithholdingGateway;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.InvoiceSnapshot;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.StripeEvent;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.seller.port.SubscriptionTerms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Each step runs in one transaction holding the collection's row lock, around idempotent remote calls: a failure
 * rolls the step back and the retry replays the same keys (payments answers its first decision, Stripe its first
 * balance transaction), so nothing is withheld or credited twice and a void can never interleave with a step.
 */
@Service
class EarningsCollectionServiceImpl implements EarningsCollectionService {

    static final String REQUESTED = "REQUESTED";
    static final String WITHHELD = "WITHHELD";
    static final String CREDITED = "CREDITED";
    static final String DECLINED = "DECLINED";
    static final String MISSED = "MISSED";
    static final String FAILED = "FAILED";
    static final String RELEASED = "RELEASED";
    /** Renewal and trial-end invoices; first invoices and upgrades are charged at once (no draft hour). */
    static final String SUBSCRIPTION_CYCLE = "subscription_cycle";
    static final int MAX_ATTEMPTS = 12;

    private static final Logger LOG = LoggerFactory.getLogger(EarningsCollectionServiceImpl.class);
    private static final Set<String> OPEN = Set.of(REQUESTED, WITHHELD);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);
    private static final int BATCH = 50;

    private final EarningsWithholdingGateway payments;
    private final EarningsCollectionRepository collections;
    private final SubscriptionRepository subs;
    private final StripeBillingGateway stripe;
    private final TransactionOperations tx;
    private final Clock clock;

    EarningsCollectionServiceImpl(EarningsWithholdingGateway payments, EarningsCollectionRepository collections,
                                  SubscriptionRepository subs, StripeBillingGateway stripe, TransactionOperations tx,
                                  Clock clock) {
        this.payments = payments;
        this.collections = collections;
        this.subs = subs;
        this.stripe = stripe;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return payments.enabled();
    }

    @Override
    public String onInvoiceCreated(String orgId, InvoiceSnapshot inv) {
        if (!payments.enabled() || !"draft".equals(inv.status()) || inv.amountDue() <= 0
                || !SUBSCRIPTION_CYCLE.equals(inv.billingReason())) {
            return "ignored";
        }
        SubscriptionTerms terms = subs.terms(orgId);
        if (terms.planFeeCollection() != PlanFeeCollection.EARNINGS_FIRST) {
            return "ignored-card";
        }
        // Stripe applies the customer's credit balance first: only the rest is asked from earnings.
        long amount = inv.amountDue() - Math.max(0, terms.creditBalanceCents());
        if (amount <= 0) {
            return "ignored-covered-by-credit";
        }
        return collections.claim(inv.id(), orgId, inv.number(), inv.amountDue(), amount, Instant.now(clock))
                ? "earnings-requested" : "earnings-duplicate";
    }

    @Override
    public String onInvoiceVoided(String orgId, InvoiceSnapshot inv) {
        Optional<Row> found = collections.lock(inv.id());
        if (found.isEmpty()) {
            return "applied";
        }
        Row r = found.get();
        Instant now = Instant.now(clock);
        String key = "plan-fee-release-" + r.invoiceId();
        switch (r.status()) {
            case REQUESTED, WITHHELD -> {
                // A rolled-back attempt may still have withheld at payments: release whatever it holds.
                collections.transition(r.invoiceId(), r.status(), MISSED, Change.reason("Invoice voided"), now);
                collections.requestRelease(key, r.invoiceId(), r.amountCents(), "invoice voided", false, now);
                return "earnings-release-requested";
            }
            case CREDITED -> {
                long amount = r.fromSalesCents();
                if (amount <= 0) {
                    return "applied";
                }
                // Stripe returns the applied balance on a void (and a deleted draft never used it): take our credit
                // back before the earnings go back to the seller.
                collections.requestRelease(key, r.invoiceId(), amount, "invoice voided", true, now);
                return "earnings-release-requested";
            }
            default -> {
                return "applied";
            }
        }
    }

    @Override
    public String onCreditNote(String orgId, StripeEvent.CreditNote cn) {
        if (cn.invoiceId() == null || cn.outOfBandAmount() <= 0) {
            return "ignored";
        }
        Optional<Row> found = collections.lock(cn.invoiceId());
        if (found.isEmpty() || !CREDITED.equals(found.get().status())) {
            return "ignored";
        }
        long amount = Math.min(cn.outOfBandAmount(), found.get().fromSalesCents());
        if (amount <= 0) {
            return "ignored";
        }
        collections.requestRelease("plan-fee-release-" + cn.id(), cn.invoiceId(), amount,
                "refunded out of band (credit note " + cn.id() + ")", false, Instant.now(clock));
        return "earnings-release-requested";
    }

    @Override
    public Outcome collect(String invoiceId) {
        if (!payments.enabled()) {
            return Outcome.DONE;
        }
        try {
            Outcome out = tx.execute(s -> collections.lock(invoiceId).filter(r -> OPEN.contains(r.status()))
                    .map(this::step).orElse(Outcome.DONE));
            release(invoiceId);
            return out;
        } catch (GatewayException e) {
            LOG.warn("Collection from earnings of invoice {} failed: {}", invoiceId, e.getMessage());
            return tx.execute(s -> failed(invoiceId, e.getMessage()));
        }
    }

    /** One step of a locked, open collection: withhold (REQUESTED), then credit the customer balance (WITHHELD). */
    private Outcome step(Row r) {
        Instant now = Instant.now(clock);
        InvoiceSnapshot inv = stripe.retrieveInvoice(r.invoiceId());
        if (!"draft".equals(inv.status())) {
            // Stripe finalised the invoice first: the card paid it. Anything withheld (now or by a rolled-back attempt)
            // goes back to the seller.
            collections.transition(r.invoiceId(), r.status(), MISSED, Change.reason("Stripe finalised the invoice "
                    + "before it could be paid from sales; the card paid it"), now);
            collections.requestRelease("plan-fee-release-" + r.invoiceId(), r.invoiceId(), r.amountCents(),
                    "invoice finalised before the credit", false, now);
            return Outcome.DONE;
        }
        long withheld = r.withheldCents();
        if (REQUESTED.equals(r.status())) {
            EarningsWithholdingGateway.Withholding w = payments.withhold(r.orgId(), r.invoiceId(), r.invoiceNumber(),
                    r.amountCents(), "usd");
            if (!w.withheld() || w.withheldCents() <= 0) {
                collections.transition(r.invoiceId(), REQUESTED, DECLINED, new Change(0L, null, w.reference(), null,
                        w.reason() == null ? "No earnings to collect from" : w.reason()), now);
                return Outcome.DONE;
            }
            withheld = Math.min(w.withheldCents(), r.amountCents());
            collections.transition(r.invoiceId(), REQUESTED, WITHHELD, new Change(withheld, null, w.reference(), null,
                    w.reason()), now);
        }
        Subscription sub = subs.find(r.orgId()).orElseThrow();
        StripeBillingGateway.BalanceCredit c = stripe.creditBalance(sub.stripeCustomerId(), withheld,
                "Plan fee paid from your sales (invoice " + label(r) + ")", "earnings-" + r.invoiceId());
        collections.transition(r.invoiceId(), WITHHELD, CREDITED, new Change(null, withheld, null, c.id(), null), now);
        subs.setCreditBalance(r.orgId(), Math.max(0, -c.endingBalanceCents()));
        LOG.info("Invoice {} of {}: {} cents paid from sales (of {} asked)", r.invoiceId(), r.orgId(), withheld,
                r.amountCents());
        return Outcome.DONE;
    }

    /** Counts the failure; after {@link #MAX_ATTEMPTS} gives up (the card pays) and releases anything withheld. */
    private Outcome failed(String invoiceId, String error) {
        Optional<Row> found = collections.lock(invoiceId).filter(r -> OPEN.contains(r.status()));
        if (found.isEmpty()) {
            return Outcome.DONE;
        }
        Row r = found.get();
        Instant now = Instant.now(clock);
        int attempts = r.attempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            collections.failedAttempt(invoiceId, error, null, now);
            collections.transition(invoiceId, r.status(), FAILED, Change.reason("Gave up after " + attempts
                    + " attempts; the card pays the invoice"), now);
            collections.requestRelease("plan-fee-release-" + invoiceId, invoiceId, r.amountCents(),
                    "collection gave up", false, now);
            return Outcome.DONE;
        }
        collections.failedAttempt(invoiceId, error, now.plus(backoff(attempts)), now);
        return Outcome.RETRY;
    }

    @Override
    public void release(String invoiceId) {
        for (String key : tx.execute(s -> collections.dueReleases(Instant.now(clock), invoiceId, BATCH))) {
            releaseOne(key);
        }
    }

    private void releaseOne(String key) {
        try {
            tx.executeWithoutResult(s -> collections.lockRelease(key).ifPresent(this::sendRelease));
        } catch (GatewayException e) {
            LOG.warn("Release {} of a plan fee paid from sales failed: {}", key, e.getMessage());
            tx.executeWithoutResult(s -> collections.lockRelease(key).ifPresent(rel -> {
                Instant now = Instant.now(clock);
                int attempts = rel.attempts() + 1;
                collections.releaseFailed(key, e.getMessage(), now.plus(backoff(attempts)), attempts >= 50, now);
            }));
        }
    }

    /** Takes the credit back from the customer balance when needed, then releases the earnings at payments. */
    private void sendRelease(Release rel) {
        Instant now = Instant.now(clock);
        Row r = collections.find(rel.invoiceId()).orElseThrow();
        String txn = rel.balanceTransactionId();
        if (rel.debitBalance() && txn == null) {
            Subscription sub = subs.find(rel.orgId()).orElseThrow();
            StripeBillingGateway.BalanceCredit d = stripe.debitBalance(sub.stripeCustomerId(), rel.amountCents(),
                    "Plan fee paid from your sales returned (invoice " + label(r) + " " + rel.reason() + ")",
                    "earnings-" + rel.idempotencyKey());
            txn = d.id();
            collections.releaseDebited(rel.idempotencyKey(), txn, now);
            subs.setCreditBalance(rel.orgId(), Math.max(0, -d.endingBalanceCents()));
        }
        payments.release(rel.orgId(), rel.invoiceId(), rel.amountCents(), rel.reason(), rel.idempotencyKey());
        boolean fully = CREDITED.equals(r.status()) && r.releasedCents() + rel.amountCents() >= r.creditedCents();
        collections.addReleased(rel.invoiceId(), rel.amountCents(), fully ? RELEASED : null, now);
        collections.releaseDone(rel.idempotencyKey(), txn, now);
        LOG.info("Invoice {} of {}: {} cents released back to the seller's next transfer ({})", rel.invoiceId(),
                rel.orgId(), rel.amountCents(), rel.reason());
    }

    @Override
    public int processOpen() {
        if (!payments.enabled()) {
            return 0;
        }
        int credited = 0;
        for (String invoiceId : tx.execute(s -> collections.dueInvoices(Instant.now(clock), BATCH))) {
            collect(invoiceId);
            credited += collections.find(invoiceId).filter(r -> CREDITED.equals(r.status())).isPresent() ? 1 : 0;
        }
        release(null);
        return credited;
    }

    private static Duration backoff(int attempts) {
        Duration d = Duration.ofMinutes(1L << Math.min(attempts - 1, 4));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private static String label(Row r) {
        return r.invoiceNumber() == null ? r.invoiceId() : r.invoiceNumber();
    }
}
