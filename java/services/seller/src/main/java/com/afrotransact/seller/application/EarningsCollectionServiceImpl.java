package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoicePayment;
import com.afrotransact.seller.domain.PaymentSource;
import com.afrotransact.seller.port.EarningsCollectionRepository;
import com.afrotransact.seller.port.EarningsCollectionRepository.Change;
import com.afrotransact.seller.port.EarningsCollectionRepository.Release;
import com.afrotransact.seller.port.EarningsCollectionRepository.Row;
import com.afrotransact.seller.port.EarningsWithholdingGateway;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.InvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Each step runs in one transaction holding the collection's row lock, around idempotent remote calls: a failure
 * rolls the step back and the retry replays the same keys (payments answers its first decision), so nothing is
 * withheld or recorded twice and a void can never interleave with a step.
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
    static final int MAX_ATTEMPTS = 12;

    private static final Logger LOG = LoggerFactory.getLogger(EarningsCollectionServiceImpl.class);
    private static final Set<String> OPEN = Set.of(REQUESTED, WITHHELD);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);
    private static final int BATCH = 50;

    private final EarningsWithholdingGateway payments;
    private final EarningsCollectionRepository collections;
    private final InvoiceRepository invoices;
    private final TransactionOperations tx;
    private final Clock clock;

    EarningsCollectionServiceImpl(EarningsWithholdingGateway payments, EarningsCollectionRepository collections,
                                  InvoiceRepository invoices, TransactionOperations tx, Clock clock) {
        this.payments = payments;
        this.collections = collections;
        this.invoices = invoices;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return payments.enabled();
    }

    @Override
    public boolean request(Invoice inv, long amountCents) {
        if (!payments.enabled() || amountCents <= 0) {
            return false;
        }
        return collections.claim(inv.id(), inv.orgId(), inv.number(), inv.amountDueCents(), amountCents,
                Instant.now(clock));
    }

    @Override
    public String onInvoiceVoided(String orgId, String invoiceId) {
        Optional<Row> found = collections.lock(invoiceId);
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
                collections.requestRelease(key, r.invoiceId(), amount, "invoice voided", false, now);
                return "earnings-release-requested";
            }
            default -> {
                return "applied";
            }
        }
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
        Invoice inv = invoices.findById(r.invoiceId()).orElse(null);
        if (inv == null || !inv.isOpen()) {
            // The invoice was settled or voided before earnings could pay: release anything withheld.
            collections.transition(r.invoiceId(), r.status(), MISSED, Change.reason("Invoice no longer open; "
                    + "nothing was taken from sales"), now);
            collections.requestRelease("plan-fee-release-" + r.invoiceId(), r.invoiceId(), r.amountCents(),
                    "invoice no longer open", false, now);
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
        collections.transition(r.invoiceId(), WITHHELD, CREDITED, new Change(null, withheld, null, null, null), now);
        invoices.addPayment(new InvoicePayment(UUID.randomUUID(), r.invoiceId(), r.orgId(), PaymentSource.EARNINGS,
                withheld, InvoicePayment.APPLIED, "earnings-" + r.invoiceId(), null, now));
        invoices.refreshPaid(r.invoiceId(), now);
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

    /** Releases the earnings at payments (idempotent per release key). */
    private void sendRelease(Release rel) {
        Instant now = Instant.now(clock);
        Row r = collections.find(rel.invoiceId()).orElseThrow();
        payments.release(rel.orgId(), rel.invoiceId(), rel.amountCents(), rel.reason(), rel.idempotencyKey());
        boolean fully = CREDITED.equals(r.status()) && r.releasedCents() + rel.amountCents() >= r.creditedCents();
        collections.addReleased(rel.invoiceId(), rel.amountCents(), fully ? RELEASED : null, now);
        collections.releaseDone(rel.idempotencyKey(), null, now);
        LOG.info("Invoice {} of {}: {} cents released back to the seller's next transfer ({})", rel.invoiceId(),
                rel.orgId(), rel.amountCents(), rel.reason());
    }

    @Override
    public int processReleases() {
        if (!payments.enabled()) {
            return 0;
        }
        List<String> keys = tx.execute(s -> collections.dueReleases(Instant.now(clock), null, BATCH));
        keys.forEach(this::releaseOne);
        return keys.size();
    }

    private static Duration backoff(int attempts) {
        Duration d = Duration.ofMinutes(1L << Math.min(attempts - 1, 4));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

}
