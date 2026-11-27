package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoicePayment;
import com.afrotransact.seller.domain.PaymentSource;
import com.afrotransact.seller.domain.PlanFeeDispute;
import com.afrotransact.seller.domain.PlanFeeRefund;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.InvoiceRepository;
import com.afrotransact.seller.port.PaymentMethodRepository;
import com.afrotransact.seller.port.PlanFeeLedger;
import com.afrotransact.seller.port.PlanPaymentsGateway;
import com.afrotransact.seller.port.WebhookInbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class PlanFeePaymentServiceImpl implements PlanFeePaymentService {

    /** The inbox "endpoint" of events handed over by payments (the Stripe event id is the key, as for Connect). */
    static final String SOURCE = "PAYMENTS";

    private static final Logger LOG = LoggerFactory.getLogger(PlanFeePaymentServiceImpl.class);

    private final WebhookInbox inbox;
    private final InvoiceCollectionService collection;
    private final InvoiceRepository invoices;
    private final PaymentMethodRepository methods;
    private final PlanFeeLedger ledger;
    private final BillingGrantRepository grants;
    private final PlanPaymentsGateway gateway;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;

    PlanFeePaymentServiceImpl(WebhookInbox inbox, InvoiceCollectionService collection, InvoiceRepository invoices,
                              PaymentMethodRepository methods, PlanFeeLedger ledger, BillingGrantRepository grants,
                              PlanPaymentsGateway gateway, AccessGuard access, DomainEventPublisher events,
                              TransactionOperations tx, Clock clock) {
        this.inbox = inbox;
        this.collection = collection;
        this.invoices = invoices;
        this.methods = methods;
        this.ledger = ledger;
        this.grants = grants;
        this.gateway = gateway;
        this.access = access;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    // ── events from the platform webhook ──────────────────────────────────────────────────────────────────────────

    @Override
    public String apply(PlanFeeStripeEvent e) {
        return tx.execute(s -> {
            if (!inbox.firstDelivery(e.stripeEventId(), SOURCE, e.stripeEventType(), null, e.created())) {
                return "duplicate";
            }
            String outcome = dispatch(e);
            inbox.outcome(e.stripeEventId(), outcome);
            return outcome;
        });
    }

    private String dispatch(PlanFeeStripeEvent e) {
        String pi = e.paymentIntentId();
        return switch (e.stripeEventType()) {
            case "payment_intent.succeeded" -> known(collection.onPaymentIntentResult(pi, true, null, null), "settled");
            case "payment_intent.payment_failed", "payment_intent.canceled" -> known(collection.onPaymentIntentResult(
                    pi, false, e.failureCode(), e.failureMessage()), "failed");
            case "payment_intent.processing" -> known(collection.onProcessing(pi), "processing");
            case "payment_intent.requires_action" -> known(collection.onActionRequired(pi), "action-required");
            case "charge.refunded" -> refunded(e);
            case "charge.dispute.created", "charge.dispute.funds_withdrawn", "charge.dispute.closed" -> dispute(e);
            case "setup_intent.succeeded" -> {
                if (e.paymentMethodId() == null) {
                    yield "ignored";
                }
                methods.markVerified(e.paymentMethodId(), e.mandateId());
                yield "payment-method-verified";
            }
            default -> "ignored";
        };
    }

    private static String known(boolean known, String outcome) {
        return known ? outcome : "unknown-payment";
    }

    /**
     * charge.refunded carries Stripe's cumulative amount_refunded: our pending refunds of the intent settle in order
     * while it covers them, and what is left over was refunded in the Stripe dashboard (recorded as a DASHBOARD refund).
     */
    private String refunded(PlanFeeStripeEvent e) {
        Optional<InvoicePayment> payment = invoices.findPaymentByReference(e.paymentIntentId());
        if (payment.isEmpty()) {
            return "unknown-payment";
        }
        Instant now = Instant.now(clock);
        long total = e.amountRefundedMinor();
        List<PlanFeeRefund> refunds = ledger.refundsOfIntent(e.paymentIntentId()).stream()
                .sorted(Comparator.comparing(PlanFeeRefund::createdAt)).toList();
        long settled = refunds.stream().filter(r -> PlanFeeRefund.SUCCEEDED.equals(r.status()))
                .mapToLong(PlanFeeRefund::amountCents).sum();
        for (PlanFeeRefund r : refunds) {
            if (PlanFeeRefund.PENDING.equals(r.status()) && settled + r.amountCents() <= total) {
                ledger.setRefund(r.id(), PlanFeeRefund.SUCCEEDED, null, now);
                settled += r.amountCents();
            }
        }
        long ours = refunds.stream().filter(PlanFeeRefund::counts).mapToLong(PlanFeeRefund::amountCents).sum();
        if (total > ours) {
            InvoicePayment p = payment.get();
            PlanFeeRefund outside = new PlanFeeRefund(UUID.randomUUID(), p.invoiceId(), p.orgId(), e.paymentIntentId(),
                    null, total - ours, PlanFeeRefund.SUCCEEDED, PlanFeeRefund.DASHBOARD, null,
                    "Refunded in the Stripe dashboard", null, "dashboard-" + e.chargeId() + "-" + total, now, now);
            if (ledger.addRefund(outside)) {
                publishAdjustment(SellerEvents.BILLING_REFUNDED, outside.orgId(), outside.invoiceId(), "REFUND",
                        e.chargeId(), outside.amountCents(), outside.status(), outside.origin(), now);
            }
            return "refund-reconciled";
        }
        return "refund-settled";
    }

    /**
     * created records the dispute (staff see it), funds_withdrawn marks it, closed decides it: LOST takes the payment
     * back (the invoice reopens, dunning restarts), WON just closes it.
     */
    private String dispute(PlanFeeStripeEvent e) {
        Optional<InvoicePayment> payment = invoices.findPaymentByReference(e.paymentIntentId());
        if (payment.isEmpty()) {
            return "unknown-payment";
        }
        Instant now = Instant.now(clock);
        InvoicePayment p = payment.get();
        boolean withdrawn = "charge.dispute.funds_withdrawn".equals(e.stripeEventType());
        String stripeStatus = e.disputeStatus() == null ? "needs_response" : e.disputeStatus();
        PlanFeeDispute fresh = new PlanFeeDispute(UUID.randomUUID(), e.disputeId(), p.invoiceId(), p.orgId(),
                e.paymentIntentId(), e.chargeId(), e.amountMinor(), e.disputeReason(), stripeStatus,
                PlanFeeDispute.OPEN, withdrawn, now, now, null);
        if (ledger.addDispute(fresh)) {
            publishAdjustment(SellerEvents.BILLING_DISPUTE_OPENED, p.orgId(), p.invoiceId(), "DISPUTE", e.disputeId(),
                    e.amountMinor(), PlanFeeDispute.OPEN, e.disputeReason(), now);
            LOG.warn("Dispute {} opened on plan payment {} of invoice {} ({})", e.disputeId(), e.paymentIntentId(),
                    p.invoiceId(), e.disputeReason());
        }
        PlanFeeDispute d = ledger.lockDispute(e.disputeId()).orElseThrow();
        if (!d.isOpen()) {
            return "dispute-already-closed";
        }
        if (!"charge.dispute.closed".equals(e.stripeEventType())) {
            ledger.updateDispute(d.stripeDisputeId(), stripeStatus, PlanFeeDispute.OPEN, d.fundsWithdrawn() || withdrawn,
                    null, now);
            return withdrawn ? "dispute-funds-withdrawn" : "dispute-opened";
        }
        String outcome = PlanFeeDispute.outcomeOf(stripeStatus);
        ledger.updateDispute(d.stripeDisputeId(), stripeStatus, outcome, d.fundsWithdrawn(), now, now);
        if (PlanFeeDispute.LOST.equals(outcome)) {
            collection.onPaymentTakenBack(e.paymentIntentId(), "dispute_lost", "A dispute on this payment was lost: "
                    + "the amount is due again");
        }
        publishAdjustment(SellerEvents.BILLING_DISPUTE_CLOSED, p.orgId(), p.invoiceId(), "DISPUTE", e.disputeId(),
                d.amountCents(), outcome, d.reason(), now);
        return "dispute-" + outcome.toLowerCase(Locale.ROOT);
    }

    // ── seller: confirm a charge that needs 3-D Secure ───────────────────────────────────────────────────────────

    @Override
    public PaymentConfirmation confirmPayment(String orgId, String invoiceId) {
        access.liveOrgRole(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING);
        Invoice inv = invoice(orgId, invoiceId);
        InvoicePayment pending = inv.payments().stream()
                .filter(p -> p.source() == PaymentSource.CARD && InvoicePayment.ACTION_REQUIRED.equals(p.status()))
                .findFirst().orElseThrow(() -> new SellerStateConflict("seller.no-action-needed",
                        "Invoice " + inv.number() + " has no payment to confirm"));
        PlanPaymentsGateway.Charge c = gateway.paymentStatus(pending.reference());
        if (c.status() == PlanPaymentsGateway.Status.SUCCEEDED) {
            collection.onPaymentIntentResult(pending.reference(), true, null, null);
        }
        return new PaymentConfirmation(inv.id(), pending.reference(),
                c.status() == PlanPaymentsGateway.Status.SUCCEEDED ? null : c.clientSecret(), c.paymentMethodId(),
                c.status().name());
    }

    // ── staff: refunds and disputes ───────────────────────────────────────────────────────────────────────────────

    @Override
    public PlanFeeRefund refund(String orgId, String invoiceId, long amountCents, String reasonCode, String note,
                                String requestKey) {
        Actor actor = access.staff(SellerPermissions.BILLING_ADJUST);
        boolean known = tx.execute(s -> grants.reasonCodes()).stream().anyMatch(r -> r.code().equals(reasonCode));
        if (!known) {
            throw invalid("reasonCode", "Choose a reason");
        }
        if ("OTHER".equals(reasonCode) && (note == null || note.isBlank())) {
            throw invalid("note", "Explain the reason");
        }
        String key = "plan-refund-" + invoiceId + "-" + (requestKey == null || requestKey.isBlank()
                ? UUID.randomUUID() : requestKey.trim());
        Optional<PlanFeeRefund> replay = tx.execute(s -> ledger.refundByKey(key));
        if (replay.isPresent()) {
            return replay.get();
        }
        Invoice inv = invoice(orgId, invoiceId);
        InvoicePayment paid = inv.payments().stream()
                .filter(p -> p.source() == PaymentSource.CARD && InvoicePayment.APPLIED.equals(p.status())
                        && p.reference() != null)
                .max(Comparator.comparing(InvoicePayment::createdAt))
                .orElseThrow(() -> new SellerStateConflict("seller.nothing-to-refund",
                        "Invoice " + inv.number() + " has no card or bank payment to refund"));
        long refunded = tx.execute(s -> ledger.refundsOfIntent(paid.reference())).stream()
                .filter(PlanFeeRefund::counts).mapToLong(PlanFeeRefund::amountCents).sum();
        long refundable = paid.amountCents() - refunded;
        if (amountCents < 1 || amountCents > refundable) {
            throw invalid("amountCents", "1 to " + refundable + " cents (what is left of the payment)");
        }
        Instant now = Instant.now(clock);
        PlanFeeRefund pending = new PlanFeeRefund(UUID.randomUUID(), inv.id(), orgId, paid.reference(), null,
                amountCents, PlanFeeRefund.PENDING, PlanFeeRefund.STAFF, reasonCode, note, actor.subject(), key, now,
                now);
        tx.executeWithoutResult(s -> ledger.addRefund(pending));
        PlanPaymentsGateway.RefundResult result;
        try {
            result = gateway.refund(paid.reference(), amountCents, reasonCode, key);
        } catch (GatewayException e) {
            if (e.rejected()) {
                tx.executeWithoutResult(s -> ledger.setRefund(pending.id(), PlanFeeRefund.FAILED, null, now));
            }
            throw e;
        }
        String status = switch (result.status() == null ? "" : result.status()) {
            case "succeeded" -> PlanFeeRefund.SUCCEEDED;
            case "failed", "canceled" -> PlanFeeRefund.FAILED;
            default -> PlanFeeRefund.PENDING;
        };
        tx.executeWithoutResult(s -> {
            ledger.setRefund(pending.id(), status, result.id(), now);
            publishAdjustment(SellerEvents.BILLING_REFUNDED, orgId, inv.id(), "REFUND", result.id(), amountCents,
                    status, PlanFeeRefund.STAFF, now);
        });
        LOG.info("Staff {} refunded {} cents of plan invoice {} ({}): {}", actor.subject(), amountCents, inv.number(),
                reasonCode, status);
        return tx.execute(s -> ledger.refundByKey(key)).orElseThrow();
    }

    @Override
    public InvoiceAdjustments adjustments(String orgId, String invoiceId) {
        access.liveOrgRoleOrStaff(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING,
                SellerPermissions.BILLING_ADJUST);
        Invoice inv = invoice(orgId, invoiceId);
        List<PlanFeeRefund> refunds = tx.execute(s -> ledger.refunds(inv.id()));
        long refunded = refunds.stream().filter(r -> PlanFeeRefund.SUCCEEDED.equals(r.status()))
                .mapToLong(PlanFeeRefund::amountCents).sum();
        return new InvoiceAdjustments(inv.id(), refunded, refunds, tx.execute(s -> ledger.disputes(inv.id())));
    }

    @Override
    public List<PlanFeeDispute> disputes(String outcome) {
        access.staffAny(SellerPermissions.BILLING, SellerPermissions.BILLING_ADJUST, SellerPermissions.PLANS_MANAGE);
        String o = outcome == null || outcome.isBlank() ? null : outcome.trim().toUpperCase(Locale.ROOT);
        if (o != null && !List.of(PlanFeeDispute.OPEN, PlanFeeDispute.WON, PlanFeeDispute.LOST).contains(o)) {
            throw invalid("outcome", "OPEN, WON or LOST");
        }
        return tx.execute(s -> ledger.disputesByOutcome(o, 200));
    }

    private Invoice invoice(String orgId, String invoiceId) {
        return tx.execute(s -> invoices.find(orgId, invoiceId)).orElseThrow(() -> new NotFoundException(
                "seller.invoice-not-found", "Invoice " + invoiceId + " not found"));
    }

    private void publishAdjustment(String type, String orgId, String invoiceId, String kind, String reference,
                                   long amountCents, String status, String origin, Instant now) {
        events.publish(type, SellerEvents.AGGREGATE_SUBSCRIPTION, orgId, new PlanFeeAdjustmentPayload(orgId,
                invoiceId, kind, reference, amountCents, status, origin, now));
    }

    private static SellerRuleViolation invalid(String field, String message) {
        return new SellerRuleViolation("seller.invalid-refund", message, Map.of(field, message));
    }
}
