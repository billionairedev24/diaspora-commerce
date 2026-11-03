package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.seller.domain.DunningState;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoicePayment;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.PaymentMethod;
import com.afrotransact.seller.domain.PaymentSource;
import com.afrotransact.seller.domain.PlanFeeCollection;
import com.afrotransact.seller.domain.PlanPricing;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.SubscriptionStatus;
import com.afrotransact.seller.port.BillingCustomerRepository;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.InvoiceRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.PaymentMethodRepository;
import com.afrotransact.seller.port.PlanPaymentsGateway;
import com.afrotransact.seller.port.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class InvoiceCollectionServiceImpl implements InvoiceCollectionService {

    /** Delay before retry n after the n-th failed charge; the failure after the last delay ends collection. */
    static final List<Duration> RETRY_AFTER = List.of(Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(5),
            Duration.ofDays(7));
    static final int MAX_FAILURES = RETRY_AFTER.size() + 1;
    static final Duration EARNINGS_RETRY = Duration.ofMinutes(5);
    static final Duration UNAVAILABLE_RETRY = Duration.ofMinutes(30);
    static final Duration BANK_DEBIT_WAIT = Duration.ofDays(7);

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceCollectionServiceImpl.class);
    private static final int BATCH = 50;

    private final InvoiceRepository invoices;
    private final SubscriptionRepository subs;
    private final PaymentMethodRepository methods;
    private final BillingCustomerRepository customers;
    private final OrganisationRepository orgs;
    private final PlanPaymentsGateway gateway;
    private final EarningsCollectionService earnings;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;

    InvoiceCollectionServiceImpl(InvoiceRepository invoices, SubscriptionRepository subs,
                                 PaymentMethodRepository methods, BillingCustomerRepository customers,
                                 OrganisationRepository orgs, PlanPaymentsGateway gateway,
                                 EarningsCollectionService earnings, DomainEventPublisher events,
                                 TransactionOperations tx, Clock clock) {
        this.invoices = invoices;
        this.subs = subs;
        this.methods = methods;
        this.customers = customers;
        this.orgs = orgs;
        this.gateway = gateway;
        this.earnings = earnings;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Outcome collect(String invoiceId) {
        Invoice afterCredit = tx.execute(s -> applyCredit(invoiceId));
        if (afterCredit == null || !afterCredit.isOpen()) {
            return Outcome.PAID;
        }
        Invoice open = afterCredit;
        if (open.remainingCents() > 0 && paysFromEarnings(open.orgId())) {
            earnings.request(open, open.remainingCents());
            if (earnings.collect(invoiceId) == EarningsCollectionService.Outcome.RETRY) {
                Instant now = Instant.now(clock);
                tx.executeWithoutResult(s -> invoices.recordAttempt(invoiceId, afterCredit.attempts(),
                        now.plus(EARNINGS_RETRY), "Collecting from sales; retrying", now));
                return Outcome.PENDING;
            }
            open = tx.execute(s -> settleIfCovered(invoiceId));
            if (open == null || !open.isOpen()) {
                return Outcome.PAID;
            }
        }
        long remaining = open.remainingCents();
        if (remaining <= 0) {
            return Outcome.PENDING;
        }
        return chargeCard(open, remaining);
    }

    @Override
    public int collectDue() {
        int paid = 0;
        for (String id : tx.execute(s -> invoices.dueForCollection(Instant.now(clock), BATCH))) {
            try {
                paid += collect(id) == Outcome.PAID ? 1 : 0;
            } catch (RuntimeException e) {
                LOG.warn("Collecting invoice {} failed; it will be tried again", id, e);
            }
        }
        return paid;
    }

    @Override
    public void onPaymentIntentResult(String paymentIntentId, boolean succeeded, String failureCode,
                                      String failureMessage) {
        Optional<InvoicePayment> pending = tx.execute(s -> invoices.findPaymentByReference(paymentIntentId));
        if (pending.isEmpty() || !InvoicePayment.PENDING.equals(pending.get().status())) {
            return;
        }
        InvoicePayment p = pending.get();
        Instant now = Instant.now(clock);
        if (succeeded) {
            tx.executeWithoutResult(s -> {
                invoices.setPaymentStatus(p.id(), InvoicePayment.APPLIED, null);
                invoices.refreshPaid(p.invoiceId(), now);
                settleIfCovered(p.invoiceId());
                resolveDunning(p.orgId(), now);
            });
            return;
        }
        tx.executeWithoutResult(s -> {
            invoices.setPaymentStatus(p.id(), InvoicePayment.FAILED, failureMessage);
            invoices.refreshPaid(p.invoiceId(), now);
            invoices.lock(p.invoiceId()).filter(Invoice::isOpen).ifPresent(inv ->
                    failure(inv, failureCode, failureMessage == null ? "The bank debit failed" : failureMessage, now));
        });
    }

    @Override
    public void waive(String invoiceId, String reasonCode, String note, String actor) {
        Instant now = Instant.now(clock);
        tx.executeWithoutResult(s -> {
            Invoice inv = invoices.lock(invoiceId).orElseThrow();
            if (!inv.isOpen()) {
                return;
            }
            long remaining = inv.remainingCents();
            if (remaining > 0) {
                invoices.addPayment(new InvoicePayment(UUID.randomUUID(), invoiceId, inv.orgId(),
                        PaymentSource.WAIVER, remaining, InvoicePayment.APPLIED, "waiver-" + invoiceId, note, now));
            }
            invoices.setWaiver(invoiceId, reasonCode, note, actor, now);
            invoices.refreshPaid(invoiceId, now);
            invoices.markPaid(invoiceId, now);
            resolveDunning(inv.orgId(), now);
        });
    }

    // ── steps ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Locks the invoice, settles what platform credit covers, and marks it paid when nothing is left. */
    private Invoice applyCredit(String invoiceId) {
        Optional<Invoice> locked = invoices.lock(invoiceId);
        if (locked.isEmpty() || !locked.get().isOpen()) {
            return locked.orElse(null);
        }
        Invoice inv = locked.get();
        Instant now = Instant.now(clock);
        long remaining = inv.remainingCents();
        if (remaining > 0 && inv.paidBy(PaymentSource.CREDIT) == 0) {
            subs.lock(inv.orgId());
            long balance = subs.terms(inv.orgId()).creditBalanceCents();
            long use = Math.min(balance, remaining);
            if (use > 0) {
                subs.adjustCreditBalance(inv.orgId(), -use);
                invoices.addPayment(new InvoicePayment(UUID.randomUUID(), invoiceId, inv.orgId(),
                        PaymentSource.CREDIT, use, InvoicePayment.APPLIED, "credit-" + invoiceId,
                        "Platform credit", now));
                invoices.refreshPaid(invoiceId, now);
            }
        }
        return settleIfCovered(invoiceId);
    }

    /** Marks the invoice paid when applied payments cover it; returns it as it stands. */
    private Invoice settleIfCovered(String invoiceId) {
        Invoice inv = invoices.lock(invoiceId).orElse(null);
        if (inv == null || !inv.isOpen()) {
            return inv;
        }
        boolean pending = inv.payments().stream().anyMatch(p -> InvoicePayment.PENDING.equals(p.status()));
        if (inv.remainingCents() == 0 && !pending) {
            Instant now = Instant.now(clock);
            invoices.refreshPaid(invoiceId, now);
            invoices.markPaid(invoiceId, now);
            resolveDunning(inv.orgId(), now);
            return invoices.findById(invoiceId).orElse(inv);
        }
        return inv;
    }

    private boolean paysFromEarnings(String orgId) {
        return earnings.enabled()
                && tx.execute(s -> subs.terms(orgId)).planFeeCollection() == PlanFeeCollection.EARNINGS_FIRST;
    }

    private Outcome chargeCard(Invoice inv, long remaining) {
        Instant now = Instant.now(clock);
        Optional<PaymentMethod> pm = tx.execute(s -> methods.list(inv.orgId()).stream()
                .filter(PaymentMethod::isDefault).findFirst());
        if (pm.isEmpty()) {
            return failed(inv.id(), "no_payment_method", "No payment method on file. Add a card or bank account "
                    + "in Plan & billing.", now);
        }
        PlanPaymentsGateway.Charge charge;
        try {
            String customer = customer(inv.orgId());
            gateway.attachPaymentMethod(customer, pm.get().stripePaymentMethodId());
            charge = gateway.charge(customer, pm.get().stripePaymentMethodId(), remaining, inv.currency(), inv.id(),
                    "AfroTransact plan " + inv.number(), "inv-" + inv.id() + "-a" + inv.attempts());
        } catch (GatewayException e) {
            LOG.warn("Charging invoice {} of {} failed: {}", inv.id(), inv.orgId(), e.getMessage());
            if (e.rejected()) {
                return failed(inv.id(), e.code(), e.getMessage(), now);
            }
            tx.executeWithoutResult(s -> invoices.recordAttempt(inv.id(), inv.attempts(),
                    now.plus(UNAVAILABLE_RETRY), "Payment system unavailable; retrying", now));
            return Outcome.RETRY;
        }
        return switch (charge.status()) {
            case SUCCEEDED -> {
                tx.executeWithoutResult(s -> {
                    invoices.addPayment(new InvoicePayment(UUID.randomUUID(), inv.id(), inv.orgId(),
                            PaymentSource.CARD, remaining, InvoicePayment.APPLIED, charge.reference(),
                            pm.get().label(), now));
                    invoices.refreshPaid(inv.id(), now);
                    settleIfCovered(inv.id());
                    publish(inv.orgId(), "PAYMENT_SUCCEEDED", now);
                });
                yield Outcome.PAID;
            }
            case PROCESSING -> {
                tx.executeWithoutResult(s -> {
                    invoices.addPayment(new InvoicePayment(UUID.randomUUID(), inv.id(), inv.orgId(),
                            PaymentSource.CARD, remaining, InvoicePayment.PENDING, charge.reference(),
                            pm.get().label(), now));
                    invoices.recordAttempt(inv.id(), inv.attempts(), now.plus(BANK_DEBIT_WAIT),
                            "Bank debit in progress", now);
                });
                yield Outcome.PENDING;
            }
            case REQUIRES_ACTION -> failed(inv.id(), "requires_action", "Your bank needs you to confirm this "
                    + "payment. Re-add the payment method in Plan & billing.", now);
            case FAILED -> failed(inv.id(), charge.failureCode(), charge.failureMessage() == null
                    ? "The payment was declined" : charge.failureMessage(), now);
        };
    }

    private String customer(String orgId) {
        Optional<String> known = tx.execute(s -> customers.find(orgId));
        if (known.isPresent()) {
            return known.get();
        }
        Organisation org = tx.execute(s -> orgs.findById(orgId)).orElseThrow();
        String created = gateway.ensureCustomer(orgId, org.displayName() == null ? org.legalName()
                : org.displayName(), null);
        return tx.execute(s -> customers.save(orgId, created, Instant.now(clock)));
    }

    private Outcome failed(String invoiceId, String code, String message, Instant now) {
        return tx.execute(s -> invoices.lock(invoiceId).filter(Invoice::isOpen)
                .map(inv -> failure(inv, code, message, now)).orElse(Outcome.PAID));
    }

    /** Counts a failed charge; schedules the next attempt or, after the last one, gives up. Runs in a transaction. */
    private Outcome failure(Invoice inv, String code, String message, Instant now) {
        int failures = inv.attempts() + 1;
        Subscription sub = subs.lock(inv.orgId()).orElse(null);
        if (failures >= MAX_FAILURES) {
            invoices.recordAttempt(inv.id(), failures, null, message, now);
            invoices.markStatus(inv.id(), Invoice.UNCOLLECTIBLE, now);
            if (sub != null) {
                Subscription next = subs.update(sub.withDunning(DunningState.FINAL_ATTEMPT_FAILED,
                        SubscriptionStatus.UNPAID, failures, null, now));
                publish(next, "PAYMENT_FAILED", now);
            }
            LOG.warn("Invoice {} of {} is uncollectible after {} failed attempts ({})", inv.number(), inv.orgId(),
                    failures, code);
            return Outcome.FAILED;
        }
        Instant retryAt = now.plus(RETRY_AFTER.get(failures - 1));
        invoices.recordAttempt(inv.id(), failures, retryAt, message, now);
        if (sub != null) {
            Subscription next = subs.update(sub.withDunning(DunningState.RETRYING, SubscriptionStatus.PAST_DUE,
                    failures, retryAt, now));
            publish(next, "PAYMENT_FAILED", now);
        }
        LOG.info("Invoice {} of {} failed ({}); retry {} ({} owed)", inv.number(), inv.orgId(), code, retryAt,
                PlanPricing.usd(inv.remainingCents()));
        return Outcome.RETRY;
    }

    /** After a payment: with no open invoice left the subscription is in good standing again. */
    private void resolveDunning(String orgId, Instant now) {
        subs.lock(orgId).filter(s -> s.dunning() != DunningState.NONE).ifPresent(s -> {
            boolean stillOpen = invoices.list(orgId, 20).stream().anyMatch(i -> Invoice.OPEN.equals(i.status()));
            if (!stillOpen) {
                Subscription next = subs.update(s.withDunning(DunningState.NONE, SubscriptionStatus.ACTIVE, 0, null,
                        now));
                publish(next, "PAYMENT_SUCCEEDED", now);
            }
        });
    }

    private void publish(String orgId, String change, Instant now) {
        subs.find(orgId).ifPresent(s -> publish(s, change, now));
    }

    private void publish(Subscription s, String change, Instant now) {
        events.publish(SellerEvents.SUBSCRIPTION_CHANGED, SellerEvents.AGGREGATE_SUBSCRIPTION, s.orgId(),
                SubscriptionEventPayload.of(s, change, s.planCode(), s.cadence() == null ? null : s.cadence().name(),
                        now));
    }
}
