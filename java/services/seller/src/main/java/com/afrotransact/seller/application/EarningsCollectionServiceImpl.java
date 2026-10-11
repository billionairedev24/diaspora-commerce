package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.port.EarningsCollectionRepository;
import com.afrotransact.seller.port.EarningsWithholdingGateway;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.InvoiceSnapshot;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class EarningsCollectionServiceImpl implements EarningsCollectionService {

    private static final Logger LOG = LoggerFactory.getLogger(EarningsCollectionServiceImpl.class);
    private static final int MAX_ATTEMPTS = 3;

    private final EarningsWithholdingGateway payments;
    private final EarningsCollectionRepository collections;
    private final SubscriptionRepository subs;
    private final StripeBillingGateway stripe;
    private final AccessGuard access;
    private final TransactionOperations tx;
    private final Clock clock;

    EarningsCollectionServiceImpl(EarningsWithholdingGateway payments, EarningsCollectionRepository collections,
                                  SubscriptionRepository subs, StripeBillingGateway stripe, AccessGuard access,
                                  TransactionOperations tx, Clock clock) {
        this.payments = payments;
        this.collections = collections;
        this.subs = subs;
        this.stripe = stripe;
        this.access = access;
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
                || !"subscription_cycle".equals(inv.billingReason())) {
            return "ignored";
        }
        if (!subs.terms(orgId).collectFromEarnings()) {
            return "ignored-not-opted-in";
        }
        return collections.claim(inv.id(), orgId, inv.amountDue(), Instant.now(clock)) ? "earnings-requested"
                : "earnings-duplicate";
    }

    @Override
    public int processOpen() {
        if (!payments.enabled()) {
            return 0;
        }
        int credited = 0;
        for (EarningsCollectionRepository.Row r : tx.execute(s -> collections.open(MAX_ATTEMPTS, 50))) {
            try {
                credited += process(r) ? 1 : 0;
            } catch (GatewayException e) {
                LOG.warn("Collection from earnings of invoice {} failed: {}", r.invoiceId(), e.getMessage());
                tx.executeWithoutResult(s -> collections.update(r.invoiceId(), "FAILED", null, null, e.getMessage(),
                        Instant.now(clock)));
            }
        }
        return credited;
    }

    private boolean process(EarningsCollectionRepository.Row r) {
        Subscription sub = tx.execute(s -> subs.find(r.orgId())).orElseThrow();
        if ("REQUESTED".equals(r.status())) {
            EarningsWithholdingGateway.Withholding w = payments.withhold(r.orgId(), r.invoiceId(), r.amountCents(),
                    "usd");
            if (!w.withheld()) {
                tx.executeWithoutResult(s -> collections.update(r.invoiceId(), "DECLINED", w.reference(), null,
                        w.reason(), Instant.now(clock)));
                return false;
            }
            tx.executeWithoutResult(s -> collections.update(r.invoiceId(), "WITHHELD", w.reference(), null, null,
                    Instant.now(clock)));
        }
        StripeBillingGateway.BalanceCredit c = stripe.creditBalance(sub.stripeCustomerId(), r.amountCents(),
                "Plan fee collected from earnings (invoice " + r.invoiceId() + ")", "earnings-" + r.invoiceId());
        tx.executeWithoutResult(s -> {
            collections.update(r.invoiceId(), "CREDITED", null, c.id(), null, Instant.now(clock));
            subs.setCreditBalance(r.orgId(), Math.max(0, -c.endingBalanceCents()));
        });
        return true;
    }

    @Override
    public void setOptIn(String orgId, boolean on) {
        access.staff(SellerPermissions.BILLING_ADJUST);
        if (on && !payments.enabled()) {
            throw new SellerStateConflict("seller.earnings-collection-off", "Collection from earnings is switched "
                    + "off on this platform");
        }
        tx.executeWithoutResult(s -> subs.setCollectFromEarnings(orgId, on));
    }
}
