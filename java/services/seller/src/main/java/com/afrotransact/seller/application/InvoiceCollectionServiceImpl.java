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
import com.afrotransact.seller.domain.PaymentMethodKind;
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
    /**
     * A bank debit settles in up to 4 business days; its outcome arrives on the platform webhook (through payments). The
     * collection job asks Stripe itself only after this, as a backstop for a lost event.
     */
    static final Duration BANK_DEBIT_WAIT = Duration.ofDays(7);
    /** A card charge Stripe left processing (rare) is asked about again after this when no event came. */
    static final Duration PROCESSING_RECHECK = Duration.ofHours(1);
    /** How long the seller has to confirm a charge that needs 3-D Secure before it counts as a failed attempt. */
    static final Duration ACTION_WAIT = Duration.ofDays(3);
    static final String BANK_DEBIT_IN_PROGRESS = "Bank debit in progress (up to 4 business days)";
    static final String CARD_PROCESSING = "Card payment processing";
    static final String ACTION_NEEDED = "Action needed: your bank asks you to confirm this payment. Open Plan & "
            + "billing and choose Confirm payment.";

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
        Optional<InvoicePayment> processing = pendingCardCharge(afterCredit);
        if (processing.isPresent()) {
            return settleProcessing(afterCredit, processing.get());
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
            return failed(inv.id(), "no_payment_method", "No payment method on file. Add a card in Plan & billing.",
                    now);
        }
        boolean bank = pm.get().kind() == PaymentMethodKind.US_BANK_ACCOUNT;
        if (bank && !pm.get().verified()) {
            return failed(inv.id(), "bank_not_verified", "Your bank account is not verified yet. Confirm the two "
                    + "small deposits in Plan & billing, or add a card.", now);
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
                    invoices.recordAttempt(inv.id(), inv.attempts(),
                            now.plus(bank ? BANK_DEBIT_WAIT : PROCESSING_RECHECK),
                            bank ? BANK_DEBIT_IN_PROGRESS : CARD_PROCESSING, now);
                });
                yield Outcome.PENDING;
            }
            case REQUIRES_ACTION -> {
                if (charge.reference() == null) {
                    yield failed(inv.id(), "requires_action", ACTION_NEEDED, now);
                }
                tx.executeWithoutResult(s -> {
                    invoices.addPayment(new InvoicePayment(UUID.randomUUID(), inv.id(), inv.orgId(),
                            PaymentSource.CARD, remaining, InvoicePayment.ACTION_REQUIRED, charge.reference(),
                            pm.get().label(), now));
                    actionRequired(inv, remaining, now);
                });
                yield Outcome.PENDING;
            }
            case FAILED -> failed(inv.id(), charge.failureCode(), charge.failureMessage() == null
                    ? "The payment was declined" : charge.failureMessage(), now);
        };
    }

    /**
     * A card or bank charge still in flight: PENDING (Stripe answered {@code processing}) or ACTION_REQUIRED (3-D
     * Secure); its reference is the PaymentIntent. While one exists the invoice is never charged again.
     */
    private static Optional<InvoicePayment> pendingCardCharge(Invoice inv) {
        return inv.payments().stream().filter(p -> (InvoicePayment.PENDING.equals(p.status())
                || InvoicePayment.ACTION_REQUIRED.equals(p.status()))
                && p.source() == PaymentSource.CARD && p.reference() != null).findFirst();
    }

    /** The invoice waits for the seller to confirm the charge; the owners are told (email, push). In a transaction. */
    private void actionRequired(Invoice inv, long amountCents, Instant now) {
        invoices.recordAttempt(inv.id(), inv.attempts(), now.plus(ACTION_WAIT), ACTION_NEEDED, now);
        events.publish(SellerEvents.BILLING_PAYMENT_ACTION_REQUIRED, SellerEvents.AGGREGATE_SUBSCRIPTION, inv.orgId(),
                new PaymentActionRequiredPayload(inv.orgId(), inv.id(), inv.number(), amountCents, inv.currency(),
                        now.plus(ACTION_WAIT), now));
    }

    /**
     * Backstop when the collection job reaches an invoice whose charge is still in flight (the event from the platform
     * webhook normally settles it first): asks Stripe. Succeeded settles; processing waits again; an unconfirmed 3-D
     * Secure charge past its wait, a failure or a cancellation counts as a failed attempt under the normal retry
     * schedule (the next attempt is a new PaymentIntent, so nothing is charged twice).
     */
    private Outcome settleProcessing(Invoice inv, InvoicePayment pending) {
        Instant now = Instant.now(clock);
        PlanPaymentsGateway.Charge status;
        try {
            status = gateway.paymentStatus(pending.reference());
        } catch (GatewayException e) {
            LOG.warn("Reading plan charge {} of invoice {} failed: {}", pending.reference(), inv.id(), e.getMessage());
            tx.executeWithoutResult(s -> invoices.recordAttempt(inv.id(), inv.attempts(), now.plus(UNAVAILABLE_RETRY),
                    "Payment system unavailable; retrying", now));
            return Outcome.RETRY;
        }
        return switch (status.status()) {
            case SUCCEEDED -> tx.execute(s -> {
                invoices.setPaymentStatus(pending.id(), InvoicePayment.APPLIED, null);
                invoices.refreshPaid(inv.id(), now);
                Invoice after = settleIfCovered(inv.id());
                return after == null || !after.isOpen() ? Outcome.PAID : Outcome.PENDING;
            });
            case PROCESSING -> {
                boolean bank = isBankDebit(inv.orgId(), status.paymentMethodId());
                tx.executeWithoutResult(s -> {
                    if (InvoicePayment.ACTION_REQUIRED.equals(pending.status())) {
                        invoices.setPaymentStatus(pending.id(), InvoicePayment.PENDING, null);
                    }
                    invoices.recordAttempt(inv.id(), inv.attempts(),
                            now.plus(bank ? BANK_DEBIT_WAIT : PROCESSING_RECHECK),
                            bank ? BANK_DEBIT_IN_PROGRESS : CARD_PROCESSING, now);
                });
                yield Outcome.PENDING;
            }
            case REQUIRES_ACTION -> {
                if (InvoicePayment.PENDING.equals(pending.status())) {
                    tx.executeWithoutResult(s -> {
                        invoices.setPaymentStatus(pending.id(), InvoicePayment.ACTION_REQUIRED, null);
                        actionRequired(inv, pending.amountCents(), now);
                    });
                    yield Outcome.PENDING;
                }
                yield tx.execute(s -> {
                    invoices.setPaymentStatus(pending.id(), InvoicePayment.FAILED, "Not confirmed in time");
                    invoices.refreshPaid(inv.id(), now);
                    return invoices.lock(inv.id()).filter(Invoice::isOpen)
                            .map(open -> failure(open, "authentication_required", "The payment was not confirmed. "
                                    + "We will try again; confirm it when your bank asks.", now))
                            .orElse(Outcome.PAID);
                });
            }
            case FAILED -> {
                String message = status.failureMessage() == null ? "The payment was declined" : status.failureMessage();
                yield tx.execute(s -> {
                    invoices.setPaymentStatus(pending.id(), InvoicePayment.FAILED, message);
                    invoices.refreshPaid(inv.id(), now);
                    return invoices.lock(inv.id()).filter(Invoice::isOpen)
                            .map(open -> failure(open, status.failureCode(), message, now)).orElse(Outcome.PAID);
                });
            }
        };
    }

    private boolean isBankDebit(String orgId, String stripePaymentMethodId) {
        return stripePaymentMethodId != null && tx.execute(s -> methods.list(orgId)).stream()
                .anyMatch(m -> stripePaymentMethodId.equals(m.stripePaymentMethodId())
                        && m.kind() == PaymentMethodKind.US_BANK_ACCOUNT);
    }

    // ── outcomes reported by Stripe through the platform webhook (payments.plan_fee.stripe_event) ───────────────────

    @Override
    public boolean onPaymentIntentResult(String paymentIntentId, boolean succeeded, String failureCode,
                                         String failureMessage) {
        Optional<InvoicePayment> found = tx.execute(s -> invoices.findPaymentByReference(paymentIntentId));
        if (found.isEmpty()) {
            return false;
        }
        InvoicePayment p = found.get();
        Instant now = Instant.now(clock);
        boolean inFlight = InvoicePayment.PENDING.equals(p.status())
                || InvoicePayment.ACTION_REQUIRED.equals(p.status());
        if (succeeded) {
            if (InvoicePayment.APPLIED.equals(p.status())) {
                return true;
            }
            tx.executeWithoutResult(s -> {
                Invoice inv = invoices.lock(p.invoiceId()).orElseThrow();
                if (!inFlight && (!inv.isOpen() || inv.remainingCents() < p.amountCents())) {
                    LOG.warn("Plan charge {} of invoice {} succeeded after it was replaced: {} received twice; refund "
                            + "it from the staff console", paymentIntentId, inv.number(), PlanPricing.usd(p.amountCents()));
                    return;
                }
                invoices.setPaymentStatus(p.id(), InvoicePayment.APPLIED, null);
                invoices.refreshPaid(p.invoiceId(), now);
                settleIfCovered(p.invoiceId());
                resolveDunning(p.orgId(), now);
                publish(p.orgId(), "PAYMENT_SUCCEEDED", now);
            });
            return true;
        }
        String message = failureMessage == null ? "The payment failed" : failureMessage;
        if (InvoicePayment.APPLIED.equals(p.status())) {
            // A bank debit can be returned days after it succeeded (insufficient funds, account closed).
            reverse(p, failureCode, "Your bank returned the payment: " + message, now);
            return true;
        }
        if (inFlight) {
            tx.executeWithoutResult(s -> {
                invoices.setPaymentStatus(p.id(), InvoicePayment.FAILED, message);
                invoices.refreshPaid(p.invoiceId(), now);
                invoices.lock(p.invoiceId()).filter(Invoice::isOpen).ifPresent(inv ->
                        failure(inv, failureCode, message, now));
            });
        }
        return true;
    }

    @Override
    public boolean onProcessing(String paymentIntentId) {
        Optional<InvoicePayment> found = tx.execute(s -> invoices.findPaymentByReference(paymentIntentId));
        found.filter(p -> InvoicePayment.ACTION_REQUIRED.equals(p.status())).ifPresent(p ->
                tx.executeWithoutResult(s -> invoices.setPaymentStatus(p.id(), InvoicePayment.PENDING, null)));
        return found.isPresent();
    }

    @Override
    public boolean onActionRequired(String paymentIntentId) {
        Optional<InvoicePayment> found = tx.execute(s -> invoices.findPaymentByReference(paymentIntentId));
        found.filter(p -> InvoicePayment.PENDING.equals(p.status())).ifPresent(p -> tx.executeWithoutResult(s -> {
            invoices.setPaymentStatus(p.id(), InvoicePayment.ACTION_REQUIRED, null);
            invoices.lock(p.invoiceId()).filter(Invoice::isOpen).ifPresent(inv ->
                    actionRequired(inv, p.amountCents(), Instant.now(clock)));
        }));
        return found.isPresent();
    }

    @Override
    public boolean onPaymentTakenBack(String paymentIntentId, String code, String message) {
        Optional<InvoicePayment> found = tx.execute(s -> invoices.findPaymentByReference(paymentIntentId));
        found.filter(p -> InvoicePayment.APPLIED.equals(p.status()))
                .ifPresent(p -> reverse(p, code, message, Instant.now(clock)));
        return found.isPresent();
    }

    /**
     * A settled payment was taken back (late bank-debit return, lost dispute): the amount is owed again, the invoice is
     * open and dunning restarts from where it was (the next attempt is scheduled by the normal retry schedule).
     */
    private void reverse(InvoicePayment p, String code, String message, Instant now) {
        tx.executeWithoutResult(s -> {
            invoices.setPaymentStatus(p.id(), InvoicePayment.REVERSED, message);
            invoices.refreshPaid(p.invoiceId(), now);
            invoices.reopenPaid(p.invoiceId(), now);
            invoices.lock(p.invoiceId()).filter(Invoice::isOpen).ifPresent(inv -> failure(inv, code, message, now));
        });
        LOG.warn("Plan payment {} of invoice {} was taken back ({}): the invoice is open again", p.reference(),
                p.invoiceId(), code);
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
