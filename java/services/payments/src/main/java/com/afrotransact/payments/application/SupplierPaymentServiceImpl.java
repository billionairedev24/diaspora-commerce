package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.SupplierPayment;
import com.afrotransact.payments.domain.SupplierPaymentStatus;
import com.afrotransact.payments.port.SupplierPaymentRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Event over API, like label charges: retail-service writes {@code retail.payment.released} to its outbox when it closes
 * the purchase order, so closing a PO gets no synchronous dependency on payments, and a replay is safe (inbox on
 * event-id plus one payment per PO). Retail is the merchant of record: the payable is AfroTransact's own debt to the
 * supplier ({@code 2500:<supplier>}), never a seller payable, a Connect transfer or a payout.
 */
@Service
class SupplierPaymentServiceImpl implements SupplierPaymentService {

    static final String CONSUMER = "payments.retail-events";

    private static final Logger LOG = LoggerFactory.getLogger(SupplierPaymentServiceImpl.class);
    private static final String SYSTEM = "system:supplier-payments";
    private static final int MAX_LIST = 500;

    private final SupplierPaymentRepository payments;
    private final LedgerPoster ledger;
    private final InboxDeduplicator inbox;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final DomainEventPublisher events;
    private final PaymentsSettings settings;
    private final SupplierPaymentSettings terms;
    private final Clock clock;

    SupplierPaymentServiceImpl(SupplierPaymentRepository payments, LedgerPoster ledger, InboxDeduplicator inbox,
                               MoneyTransactions tx, PaymentsAccess access, DomainEventPublisher events,
                               PaymentsSettings settings, SupplierPaymentSettings terms, Clock clock) {
        this.payments = payments;
        this.ledger = ledger;
        this.inbox = inbox;
        this.tx = tx;
        this.access = access;
        this.events = events;
        this.settings = settings;
        this.terms = terms;
        this.clock = clock;
    }

    @Override
    public boolean released(SupplierPaymentNotice n) {
        if (n.poId() == null || n.supplierId() == null || n.currency() == null || n.currency().isBlank()
                || n.amountMinor() <= 0) {
            LOG.warn("Ignoring retail.payment.released {}: incomplete (po {}, supplier {}, amount {} {})",
                    n.eventId(), n.poId(), n.supplierId(), n.amountMinor(), n.currency());
            return false;
        }
        return tx.serializable(() -> {
            if (!inbox.firstDelivery(CONSUMER, n.eventId())) {
                return false;
            }
            Instant now = Instant.now(clock);
            Instant released = n.releasedAt() == null ? now : n.releasedAt();
            LocalDate dueOn = n.dueOn() != null ? n.dueOn()
                    : released.atZone(settings.payoutZone()).toLocalDate().plusDays(terms.defaultTermsDays());
            String currency = n.currency().trim().toUpperCase(Locale.ROOT);
            UUID journal = Fingerprint.id("journal", "supplier-payable", n.poId().toString());
            SupplierPayment payment = SupplierPayment.schedule(Fingerprint.id("supplier-payment",
                            n.poId().toString()), n.poId(), n.poNumber(), n.supplierId(), n.supplierCode(),
                    Money.of(n.amountMinor(), currency), n.invoices().stream().map(i -> new SupplierPayment.Invoice(
                            i.invoiceId(), i.invoiceNumber(), Money.of(i.amountMinor(),
                            i.currency() == null || i.currency().isBlank() ? currency : i.currency())))
                            .toList(), dueOn, journal, n.eventId(), now);
            if (!payments.insert(payment)) {
                LOG.info("Purchase order {} already has a supplier payment; event {} ignored", n.poNumber(),
                        n.eventId());
                return false;
            }
            ledger.post(Postings.supplierPayable(journal, released, "supplier-payable:" + n.poId(), payment,
                    settings.retailOrgId()), SYSTEM);
            publish(PaymentsEvents.SUPPLIER_PAYMENT_SCHEDULED, payment);
            return true;
        });
    }

    @Override
    public List<SupplierPayment> list(SupplierPaymentStatus status, UUID supplierId, LocalDate dueBy, int limit) {
        access.reader();
        return payments.list(Optional.ofNullable(status), Optional.ofNullable(supplierId), dueBy,
                Math.max(1, Math.min(limit, MAX_LIST)));
    }

    @Override
    public SupplierPayment get(UUID id) {
        access.reader();
        return payments.find(id).orElseThrow(() -> notFound(id));
    }

    @Override
    public SupplierPayment markPaid(UUID id, String paymentReference, Instant paidAt) {
        Actor actor = access.supplierPaymentApprover();
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new PaymentsRuleViolation("payments.reference-required", "Record the bank transfer reference",
                    Map.of("paymentReference", "Required"));
        }
        String reference = paymentReference.trim();
        return tx.serializable(() -> {
            SupplierPayment p = payments.lock(id).orElseThrow(() -> notFound(id));
            if (p.status() == SupplierPaymentStatus.PAID && reference.equals(p.paymentReference())) {
                return p;
            }
            Instant now = Instant.now(clock);
            Instant at = paidAt == null ? now : paidAt;
            UUID journal = Fingerprint.id("journal", "supplier-payment", p.id().toString());
            SupplierPayment paid = p.paid(journal, reference, actor.subject(), at, now);
            ledger.post(Postings.supplierPayment(journal, at, "supplier-payment:" + p.id(), paid, reference,
                    settings.retailOrgId()), actor.subject());
            SupplierPayment stored = payments.update(paid);
            publish(PaymentsEvents.SUPPLIER_PAYMENT_PAID, stored);
            return stored;
        });
    }

    @Override
    public SupplierPayment hold(UUID id, String reason) {
        access.supplierPaymentApprover();
        if (reason == null || reason.isBlank()) {
            throw new PaymentsRuleViolation("payments.reason-required", "A hold needs a reason",
                    Map.of("reason", "Required"));
        }
        return tx.serializable(() -> {
            SupplierPayment p = payments.lock(id).orElseThrow(() -> notFound(id));
            if (p.status() == SupplierPaymentStatus.ON_HOLD) {
                return p;
            }
            SupplierPayment stored = payments.update(p.hold(reason.trim(), Instant.now(clock)));
            publish(PaymentsEvents.SUPPLIER_PAYMENT_UPDATED, stored);
            return stored;
        });
    }

    @Override
    public SupplierPayment release(UUID id) {
        access.supplierPaymentApprover();
        return tx.serializable(() -> {
            SupplierPayment p = payments.lock(id).orElseThrow(() -> notFound(id));
            if (p.status() == SupplierPaymentStatus.SCHEDULED) {
                return p;
            }
            SupplierPayment stored = payments.update(p.release(Instant.now(clock)));
            publish(PaymentsEvents.SUPPLIER_PAYMENT_UPDATED, stored);
            return stored;
        });
    }

    private void publish(String type, SupplierPayment p) {
        events.publish(type, PaymentsEvents.AGGREGATE_PAYOUT, p.supplierId().toString(),
                PaymentsEventPayloads.SupplierPaymentPayload.of(p));
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("payments.supplier-payment-not-found", "Supplier payment " + id + " not found");
    }
}
